package org.yurlib.server.library.infrastructure.metadata;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

final class MetadataResourceBudget {

    private final MetadataResourceLimits limits;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private long bytesRead;
    private int openFiles;
    private int peakOpenFiles;
    private int largestControlledBufferBytes;

    MetadataResourceBudget(MetadataResourceLimits limits) {
        this(limits, System::nanoTime);
    }

    MetadataResourceBudget(MetadataResourceLimits limits, LongSupplier nanoTime) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        startedAt = nanoTime.getAsLong();
    }

    MetadataResourceLimits limits() {
        return limits;
    }

    void checkSourceSize(long size) throws MetadataParsingException {
        if (size < 0 || size > limits.maximumSourceBytes()) {
            throw MetadataParsingException.limit("source-size", limits.maximumSourceBytes(), "bytes");
        }
    }

    void recordRead(long count) throws MetadataParsingException {
        if (count <= 0) {
            checkpoint();
            return;
        }
        try {
            bytesRead = Math.addExact(bytesRead, count);
        } catch (ArithmeticException exception) {
            throw MetadataParsingException.limit("bytes-read", limits.maximumBytesRead(), "bytes", exception);
        }
        if (bytesRead > limits.maximumBytesRead()) {
            throw MetadataParsingException.limit("bytes-read", limits.maximumBytesRead(), "bytes");
        }
        checkpoint();
    }

    void recordControlledBuffer(int bytes) throws MetadataParsingException {
        if (bytes < 0 || bytes > limits.maximumBytesRead()) {
            throw MetadataParsingException.limit("controlled-buffer", limits.maximumBytesRead(), "bytes");
        }
        largestControlledBufferBytes = Math.max(largestControlledBufferBytes, bytes);
        checkpoint();
    }

    ResourceLease openFile() throws MetadataParsingException {
        checkpoint();
        if (openFiles >= limits.maximumOpenFiles()) {
            throw MetadataParsingException.limit("open-files", limits.maximumOpenFiles(), "files");
        }
        openFiles++;
        peakOpenFiles = Math.max(peakOpenFiles, openFiles);
        return new ResourceLease(this);
    }

    InputStream count(InputStream input) {
        return new CountingInputStream(input);
    }

    InputStream bound(InputStream input, long maximumBytes, String resource) {
        return new BoundedInputStream(input, maximumBytes, resource);
    }

    void checkDepth(int depth) throws MetadataParsingException {
        if (depth > limits.maximumStructuralDepth()) {
            throw MetadataParsingException.limit("structural-depth", limits.maximumStructuralDepth(), "levels");
        }
        checkpoint();
    }

    void checkSelectedValueBytes(int bytes) throws MetadataParsingException {
        if (bytes > limits.maximumSelectedValueBytes()) {
            throw MetadataParsingException.limit("selected-value", limits.maximumSelectedValueBytes(), "bytes");
        }
        checkpoint();
    }

    void checkpoint() throws MetadataParsingException {
        var elapsed = nanoTime.getAsLong() - startedAt;
        if (elapsed < 0 || elapsed > limits.maximumElapsed().toNanos()) {
            throw MetadataParsingException.limit(
                    "elapsed-time", limits.maximumElapsed().toMillis(), "milliseconds");
        }
    }

    Usage usage() {
        var elapsed = Math.max(0, nanoTime.getAsLong() - startedAt);
        return new Usage(bytesRead, peakOpenFiles, largestControlledBufferBytes, Duration.ofNanos(elapsed));
    }

    private void closeFile() {
        if (openFiles <= 0) {
            throw new IllegalStateException("Metadata resource lease was closed more than once.");
        }
        openFiles--;
    }

    record Usage(long bytesRead, int peakOpenFiles, int largestControlledBufferBytes, Duration elapsed) {}

    static final class ResourceLease implements AutoCloseable {

        private MetadataResourceBudget owner;

        private ResourceLease(MetadataResourceBudget owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner != null) {
                owner.closeFile();
                owner = null;
            }
        }
    }

    private final class CountingInputStream extends FilterInputStream {

        private CountingInputStream(InputStream input) {
            super(Objects.requireNonNull(input, "input"));
        }

        @Override
        public int read() throws IOException {
            var value = in.read();
            if (value >= 0) {
                recordRead(1);
            } else {
                checkpoint();
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            var count = in.read(target, offset, length);
            recordRead(count);
            return count;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private final long maximumBytes;
        private final String resource;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long maximumBytes, String resource) {
            super(Objects.requireNonNull(input, "input"));
            this.maximumBytes = maximumBytes;
            this.resource = Objects.requireNonNull(resource, "resource");
        }

        @Override
        public int read() throws IOException {
            requireRemaining();
            var value = in.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            requireRemaining();
            var allowed = (int) Math.min(length, maximumBytes - bytesRead);
            var count = in.read(target, offset, allowed);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        private void requireRemaining() throws MetadataParsingException {
            if (bytesRead >= maximumBytes) {
                throw MetadataParsingException.limit(resource, maximumBytes, "bytes");
            }
        }
    }
}

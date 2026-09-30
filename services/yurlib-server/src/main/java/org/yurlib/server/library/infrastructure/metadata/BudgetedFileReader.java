package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class BudgetedFileReader implements AutoCloseable {

    private final FileChannel channel;
    private final MetadataResourceBudget budget;
    private final MetadataResourceBudget.ResourceLease lease;

    private BudgetedFileReader(
            FileChannel channel, MetadataResourceBudget budget, MetadataResourceBudget.ResourceLease lease) {
        this.channel = channel;
        this.budget = budget;
        this.lease = lease;
    }

    static BudgetedFileReader open(Path path, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var lease = budget.openFile();
        try {
            return new BudgetedFileReader(FileChannel.open(path, StandardOpenOption.READ), budget, lease);
        } catch (IOException | RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    long size() throws IOException {
        return channel.size();
    }

    ByteBuffer read(long position, int length) throws IOException, MetadataParsingException {
        if (position < 0 || length < 0 || position > channel.size() - length) {
            throw new IOException("Requested file range is unavailable.");
        }
        budget.recordControlledBuffer(length);
        var buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            var count = channel.read(buffer, position + buffer.position());
            if (count < 0) {
                throw new IOException("Unexpected end of file.");
            }
            budget.recordRead(count);
        }
        return buffer.flip();
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            lease.close();
        }
    }
}

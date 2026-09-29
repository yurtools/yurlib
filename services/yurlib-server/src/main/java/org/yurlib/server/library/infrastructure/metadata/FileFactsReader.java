package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

@FunctionalInterface
interface FileFactsReader {

    FileFacts read(Path file) throws IOException;

    static FileFactsReader nio() {
        return file -> {
            var attributes =
                    java.nio.file.Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("The candidate is not a regular file.");
            }
            return new FileFacts(
                    attributes.size(), attributes.lastModifiedTime().toInstant(), attributes.fileKey());
        };
    }

    record FileFacts(long size, Instant modifiedAt, Object fileKey) {}
}

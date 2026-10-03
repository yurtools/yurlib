package org.yurlib.server.library.infrastructure.conversion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class EpubConversionValidator {

    private static final int MAXIMUM_ENTRIES = 10_000;
    private static final long MAXIMUM_EXPANDED_BYTES = 1_073_741_824L;
    private static final long MAXIMUM_ENTRY_BYTES = 268_435_456L;
    private static final long MAXIMUM_RATIO = 100L;
    private static final Pattern ROOTFILE =
            Pattern.compile("<rootfile\\b[^>]*\\bfull-path\\s*=\\s*([\"'])([^\"']+)\\1");

    public void validate(Path path) throws IOException {
        try (var archive = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            if (archive.size() == 0 || archive.size() > MAXIMUM_ENTRIES) {
                throw invalid("The EPUB entry count is invalid.");
            }
            var names = new HashSet<String>();
            long expanded = 0;
            var position = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (position == 0 && !"mimetype".equals(name)) {
                    throw invalid("The EPUB media type entry is not first.");
                }
                position++;
                if (!safeName(name) || !names.add(name)) {
                    throw invalid("The EPUB contains an unsafe or duplicate entry name.");
                }
                if (entry.getSize() < 0 || entry.getSize() > MAXIMUM_ENTRY_BYTES || entry.getCompressedSize() < 0) {
                    throw invalid("The EPUB contains an entry outside the size limits.");
                }
                expanded = Math.addExact(expanded, entry.getSize());
                if (expanded > MAXIMUM_EXPANDED_BYTES
                        || (entry.getCompressedSize() > 0
                                && entry.getSize() > Math.multiplyExact(entry.getCompressedSize(), MAXIMUM_RATIO))) {
                    throw invalid("The EPUB exceeds its expansion limits.");
                }
            }
            var mimetype = archive.getEntry("mimetype");
            var container = archive.getEntry("META-INF/container.xml");
            if (mimetype == null || container == null || mimetype.getMethod() != java.util.zip.ZipEntry.STORED) {
                throw invalid("The EPUB package structure is incomplete.");
            }
            try (var input = archive.getInputStream(mimetype)) {
                var value = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
                if (!"application/epub+zip".equals(value)) {
                    throw invalid("The EPUB media type is invalid.");
                }
            }
            try (var input = archive.getInputStream(container)) {
                var value = new String(input.readNBytes(65_537), StandardCharsets.UTF_8);
                var rootfile = ROOTFILE.matcher(value);
                if (value.length() > 65_536
                        || value.contains("<!DOCTYPE")
                        || value.contains("<!ENTITY")
                        || !rootfile.find()
                        || !safeName(rootfile.group(2))
                        || archive.getEntry(rootfile.group(2)) == null) {
                    throw invalid("The EPUB container document is invalid.");
                }
            }
        } catch (ArithmeticException failure) {
            throw invalid("The EPUB exceeds its expansion limits.", failure);
        }
    }

    private static boolean safeName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            return false;
        }
        var comparable = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        try {
            var normalized = Path.of(comparable).normalize();
            return !normalized.isAbsolute()
                    && !normalized.startsWith("..")
                    && normalized.toString().equals(comparable);
        } catch (InvalidPathException failure) {
            return false;
        }
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

    private static IOException invalid(String message, Throwable cause) {
        return new IOException(message, cause);
    }
}

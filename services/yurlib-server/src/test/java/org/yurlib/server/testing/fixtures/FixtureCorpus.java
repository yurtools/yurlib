package org.yurlib.server.testing.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.yurlib.server.testing.RepositoryPaths;

public final class FixtureCorpus {

    private static final int MOBI_HEADER_LENGTH = 232;
    private static final int PDB_HEADER_LENGTH = 78;
    private static final int RECORD_DIRECTORY_LENGTH = 16;

    private FixtureCorpus() {}

    public static Map<String, String> sourceHashes() throws IOException {
        var hashes = new LinkedHashMap<String, String>();
        try (var paths = Files.walk(sourceRoot())) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                hashes.put(sourceRoot().relativize(path).toString(), sha256(path));
            }
        }
        return Map.copyOf(hashes);
    }

    public static void assertSourcesUnchanged(Map<String, String> expected) throws IOException {
        var actual = sourceHashes();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("fixture source bytes changed during the test");
        }
    }

    public static Path materialize(Path libraryRoot) throws IOException {
        copyTextSources(libraryRoot);
        writeEpub(libraryRoot.resolve("valid/minimal.epub"), EpubKind.VALID);
        writeEpub(libraryRoot.resolve("security/traversal.epub"), EpubKind.TRAVERSAL);
        writeEpub(libraryRoot.resolve("security/decompression-limit.epub"), EpubKind.DECOMPRESSION_LIMIT);
        writeMobi(libraryRoot.resolve("valid/minimal.mobi"));
        writeEscapingSymlink(libraryRoot);
        return libraryRoot;
    }

    private static Path sourceRoot() {
        return RepositoryPaths.root().resolve("services/yurlib-server/src/test/resources/fixtures/source");
    }

    private static void copyTextSources(Path libraryRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot())) {
            for (var source : paths.toList()) {
                var target = libraryRoot.resolve(sourceRoot().relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void writeEpub(Path path, EpubKind kind) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            writeStoredEntry(output, "mimetype", "application/epub+zip");
            writeEntry(output, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            writeEntry(output, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">urn:uuid:yurlib-fixture</dc:identifier>
                        <dc:title>Minimal EPUB Fixture</dc:title><dc:language>en</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """);
            writeEntry(output, "OEBPS/chapter.xhtml", """
                    <!doctype html><html xmlns="http://www.w3.org/1999/xhtml" lang="en">
                    <head><title>Fixture</title></head><body><p>Generated test content.</p></body></html>
                    """);
            if (kind == EpubKind.TRAVERSAL) {
                writeEntry(output, "../escaped.txt", "must never be extracted");
            } else if (kind == EpubKind.DECOMPRESSION_LIMIT) {
                writeEntry(output, "OEBPS/oversized.txt", "A".repeat(2 * 1024 * 1024));
            }
        }
    }

    private static void writeStoredEntry(ZipOutputStream output, String name, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(bytes);
        var entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void writeMobi(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        var text = "Minimal MOBI fixture".getBytes(StandardCharsets.UTF_8);
        var firstRecordOffset = PDB_HEADER_LENGTH + RECORD_DIRECTORY_LENGTH;
        var secondRecordOffset = firstRecordOffset + 16 + MOBI_HEADER_LENGTH;
        var buffer = ByteBuffer.allocate(secondRecordOffset + text.length);
        putAscii(buffer, 0, 32, "Yurlib fixture");
        putAscii(buffer, 60, 4, "BOOK");
        putAscii(buffer, 64, 4, "MOBI");
        buffer.putShort(76, (short) 2);
        writeRecordEntry(buffer, 78, firstRecordOffset, 1);
        writeRecordEntry(buffer, 86, secondRecordOffset, 2);
        buffer.putShort(firstRecordOffset, (short) 1);
        buffer.putInt(firstRecordOffset + 4, text.length);
        buffer.putShort(firstRecordOffset + 8, (short) 1);
        buffer.putShort(firstRecordOffset + 10, (short) 4096);
        putAscii(buffer, firstRecordOffset + 16, 4, "MOBI");
        buffer.putInt(firstRecordOffset + 20, MOBI_HEADER_LENGTH);
        buffer.putInt(firstRecordOffset + 24, 2);
        buffer.putInt(firstRecordOffset + 28, 65001);
        buffer.putInt(firstRecordOffset + 32, 1);
        buffer.putInt(firstRecordOffset + 36, 6);
        buffer.position(secondRecordOffset);
        buffer.put(text);
        Files.write(path, buffer.array());
    }

    private static void writeRecordEntry(ByteBuffer buffer, int offset, int recordOffset, int uniqueId) {
        buffer.putInt(offset, recordOffset);
        buffer.put(offset + 5, (byte) (uniqueId >>> 16));
        buffer.put(offset + 6, (byte) (uniqueId >>> 8));
        buffer.put(offset + 7, (byte) uniqueId);
    }

    private static void putAscii(ByteBuffer buffer, int offset, int width, String value) {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        buffer.position(offset);
        buffer.put(bytes, 0, Math.min(width, bytes.length));
    }

    private static void writeEscapingSymlink(Path libraryRoot) throws IOException {
        var outside = libraryRoot.resolveSibling("outside-library.txt");
        Files.writeString(outside, "outside the approved root", StandardCharsets.UTF_8);
        var link = libraryRoot.resolve("security/symlink-escape");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private enum EpubKind {
        VALID,
        TRAVERSAL,
        DECOMPRESSION_LIMIT
    }
}

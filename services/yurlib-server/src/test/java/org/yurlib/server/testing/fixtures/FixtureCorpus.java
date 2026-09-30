package org.yurlib.server.testing.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.yurlib.server.testing.RepositoryPaths;

public final class FixtureCorpus {

    private static final int MOBI_HEADER_LENGTH = 232;
    private static final int MOBI_HEADER_START = 16;
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
        writeMobi(libraryRoot.resolve("valid/minimal.mobi"), "Кириллическая MOBI книга", null);
        writeMobi(libraryRoot.resolve("valid/updated-title.mobi"), "Header title", "Updated title");
        writeEscapingSymlink(libraryRoot);
        return libraryRoot;
    }

    public static void writeNearLimitEpub(Path path) throws IOException {
        writeEpub(path, EpubKind.NEAR_LIMIT_METADATA);
    }

    public static void writeEpubWithLargeUnparsedEntry(Path path) throws IOException {
        writeEpub(path, EpubKind.LARGE_UNRELATED_ENTRY);
    }

    public static void writeMobiWithFullName(Path path, String fullName) throws IOException {
        writeMobi(path, fullName, null);
    }

    public static void writeDocx(Path path) throws IOException {
        writeDocx(path, "Многоязычный DOCX", false, false);
    }

    public static void writeDocxWithTitle(Path path, String title) throws IOException {
        writeDocx(path, title, false, false);
    }

    public static void writeDocxWithExternalRelationship(Path path) throws IOException {
        writeDocx(path, "External relationship", true, false);
    }

    public static void writeMacroDocx(Path path) throws IOException {
        writeDocx(path, "Macro package", false, true);
    }

    public static void writeEncryptedDocx(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        var bytes = new byte[512];
        var signature =
                new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        Files.write(path, bytes);
    }

    public static void writeDjvu(Path path, String title) throws IOException {
        writeDjvu(path, title, 0);
    }

    public static Path writeSparseDjvu(Path path, String title, int unparsedBytes) throws IOException {
        writeDjvu(path, title, unparsedBytes);
        return path;
    }

    public static void writeLargeFb2(Path path, int binaryCharacters) throws IOException {
        Files.createDirectories(path.getParent());
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                      <description>
                        <title-info><book-title>Large streaming FB2</book-title><lang>en</lang></title-info>
                        <document-info><id>large-streaming-fixture</id></document-info>
                      </description>
                      <body><section><p>Generated content.</p></section></body>
                      <binary id="cover" content-type="image/jpeg">
                    """);
            var chunk = new char[8192];
            Arrays.fill(chunk, 'A');
            for (var remaining = binaryCharacters; remaining > 0; remaining -= chunk.length) {
                writer.write(chunk, 0, Math.min(chunk.length, remaining));
            }
            writer.write("</binary></FictionBook>");
        }
    }

    public static Path writeSparseMobi(Path source, Path target, long size) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        try (var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        return target;
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
            var packageDocument = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">urn:uuid:yurlib-fixture</dc:identifier>
                        <dc:title>Minimal EPUB Fixture</dc:title><dc:creator>Fixture Author</dc:creator>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """;
            if (kind == EpubKind.DECOMPRESSION_LIMIT || kind == EpubKind.NEAR_LIMIT_METADATA) {
                var targetBytes = 4 * 1024 * 1024 + (kind == EpubKind.DECOMPRESSION_LIMIT ? 1 : -1);
                writeStoredEntry(output, "OEBPS/content.opf", paddedXml(packageDocument, targetBytes));
            } else {
                writeEntry(output, "OEBPS/content.opf", packageDocument);
            }
            writeEntry(output, "OEBPS/chapter.xhtml", """
                    <!doctype html><html xmlns="http://www.w3.org/1999/xhtml" lang="en">
                    <head><title>Fixture</title></head><body><p>Generated test content.</p></body></html>
                    """);
            if (kind == EpubKind.TRAVERSAL) {
                writeEntry(output, "../escaped.txt", "must never be extracted");
            } else if (kind == EpubKind.LARGE_UNRELATED_ENTRY) {
                writeEntry(output, "OEBPS/oversized.txt", "A".repeat(2 * 1024 * 1024));
            }
        }
    }

    private static void writeDocx(Path path, String title, boolean externalRelationship, boolean macro)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            var documentContentType = macro
                    ? "application/vnd.ms-word.document.macroEnabled.main+xml"
                    : "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            writeEntry(output, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="%s"/>
                      <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                      <Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>
                    </Types>
                    """.formatted(documentContentType));
            writeEntry(output, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="officeDocument" Target="word/document.xml"/>
                      <Relationship Id="rId2" Type="core-properties" Target="docProps/core.xml"/>
                      %s
                    </Relationships>
                    """.formatted(
                            externalRelationship
                                    ? "<Relationship Id=\"rId3\" Type=\"hyperlink\" Target=\"https://example.invalid/private\" TargetMode=\"External\"/>"
                                    : ""));
            var coreProperties = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <cp:coreProperties
                      xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                      xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title>%s</dc:title>
                      <dc:creator>Анна Тестова</dc:creator>
                      <dc:language>ru</dc:language>
                      <dc:identifier>urn:yurlib:docx-fixture</dc:identifier>
                      <cp:keywords>тест, fixture</cp:keywords>
                    </cp:coreProperties>
                    """.formatted(xmlEscape(title));
            writeStoredUtf8Entry(output, "docProps/core.xml", coreProperties);
            writeEntry(output, "docProps/custom.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
                      xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                      <property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Collection">
                        <vt:lpwstr>Generated fixtures</vt:lpwstr>
                      </property>
                    </Properties>
                    """);
            writeEntry(output, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Generated test content.</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
            if (macro) {
                writeEntry(output, "word/vbaProject.bin", "generated inactive fixture marker");
            }
        }
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void writeDjvu(Path path, String title, int unparsedBytes) throws IOException {
        Files.createDirectories(path.getParent());
        var info = ByteBuffer.allocate(10);
        info.putShort((short) 1200).putShort((short) 1800).put((byte) 0).put((byte) 26);
        info.putShort((short) 300).put((byte) 22).put((byte) 0);
        var annotation = ("(metadata (Title \"" + title + "\") (Author \"Анна Тестова\")"
                        + " (Language \"ru\") (ISBN \"9780000000002\"))")
                .getBytes(StandardCharsets.UTF_8);
        var prefix = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(prefix)) {
            output.writeBytes("AT&T");
            output.writeBytes("FORM");
            var infoChunkBytes = 8 + info.array().length;
            var annotationChunkBytes = 8 + annotation.length + (annotation.length & 1);
            var unparsedChunkBytes = unparsedBytes == 0 ? 0 : 8 + unparsedBytes + (unparsedBytes & 1);
            output.writeInt(4 + infoChunkBytes + annotationChunkBytes + unparsedChunkBytes);
            output.writeBytes("DJVU");
            writeDjvuChunk(output, "INFO", info.array());
            writeDjvuChunk(output, "ANTa", annotation);
            if (unparsedBytes > 0) {
                output.writeBytes("BG44");
                output.writeInt(unparsedBytes);
            }
        }
        Files.write(path, prefix.toByteArray());
        if (unparsedBytes > 0) {
            try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                var finalSize = prefix.size() + (long) unparsedBytes + (unparsedBytes & 1);
                channel.position(finalSize - 1);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
        }
    }

    private static void writeDjvuChunk(DataOutputStream output, String id, byte[] value) throws IOException {
        output.writeBytes(id);
        output.writeInt(value.length);
        output.write(value);
        if ((value.length & 1) != 0) {
            output.write(0);
        }
    }

    private static void writeStoredEntry(ZipOutputStream output, String name, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        writeStoredEntry(output, name, bytes);
    }

    private static void writeStoredUtf8Entry(ZipOutputStream output, String name, String value) throws IOException {
        writeStoredEntry(output, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeStoredEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
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

    private static String paddedXml(String document, int targetBytes) {
        var wrapperBytes = "<!---->".length();
        var padding = targetBytes - document.getBytes(StandardCharsets.US_ASCII).length - wrapperBytes;
        if (padding < 0) {
            throw new IllegalArgumentException("Target XML size is smaller than the fixture document.");
        }
        return document + "<!--" + "N".repeat(padding) + "-->";
    }

    private static void writeMobi(Path path, String fullName, String updatedTitle) throws IOException {
        Files.createDirectories(path.getParent());
        var text = "Minimal MOBI fixture".getBytes(StandardCharsets.UTF_8);
        var fullNameBytes = fullName.getBytes(StandardCharsets.UTF_8);
        var exthRecords = new LinkedHashMap<Integer, byte[]>();
        exthRecords.put(100, "Анна Тестова".getBytes(StandardCharsets.UTF_8));
        exthRecords.put(104, "9780000000001".getBytes(StandardCharsets.UTF_8));
        exthRecords.put(113, "B000YURLIB".getBytes(StandardCharsets.UTF_8));
        if (updatedTitle != null) {
            exthRecords.put(503, updatedTitle.getBytes(StandardCharsets.UTF_8));
        }
        exthRecords.put(524, "ru".getBytes(StandardCharsets.UTF_8));
        var exthLength = 12
                + exthRecords.values().stream()
                        .mapToInt(value -> 8 + value.length)
                        .sum();
        var paddedExthLength = alignedToFourBytes(exthLength);
        var firstRecordOffset = PDB_HEADER_LENGTH + RECORD_DIRECTORY_LENGTH;
        var fullNameOffset = MOBI_HEADER_START + MOBI_HEADER_LENGTH + paddedExthLength;
        var secondRecordOffset = firstRecordOffset + fullNameOffset + fullNameBytes.length;
        var buffer = ByteBuffer.allocate(secondRecordOffset + text.length);
        putAscii(buffer, 0, 32, "ASCII fallback title");
        putAscii(buffer, 60, 4, "BOOK");
        putAscii(buffer, 64, 4, "MOBI");
        buffer.putShort(76, (short) 2);
        writeRecordEntry(buffer, 78, firstRecordOffset, 1);
        writeRecordEntry(buffer, 86, secondRecordOffset, 2);
        buffer.putShort(firstRecordOffset, (short) 1);
        buffer.putInt(firstRecordOffset + 4, text.length);
        buffer.putShort(firstRecordOffset + 8, (short) 1);
        buffer.putShort(firstRecordOffset + 10, (short) 4096);
        putAscii(buffer, firstRecordOffset + MOBI_HEADER_START, 4, "MOBI");
        buffer.putInt(firstRecordOffset + 20, MOBI_HEADER_LENGTH);
        buffer.putInt(firstRecordOffset + 24, 2);
        buffer.putInt(firstRecordOffset + 28, 65001);
        buffer.putInt(firstRecordOffset + 32, 1);
        buffer.putInt(firstRecordOffset + 36, 6);
        buffer.putInt(firstRecordOffset + MOBI_HEADER_START + 0x44, fullNameOffset);
        buffer.putInt(firstRecordOffset + MOBI_HEADER_START + 0x48, fullNameBytes.length);
        buffer.putInt(firstRecordOffset + MOBI_HEADER_START + 0x70, 0x40);
        var exthStart = firstRecordOffset + MOBI_HEADER_START + MOBI_HEADER_LENGTH;
        putAscii(buffer, exthStart, 4, "EXTH");
        buffer.putInt(exthStart + 4, exthLength);
        buffer.putInt(exthStart + 8, exthRecords.size());
        var exthPosition = exthStart + 12;
        for (var record : exthRecords.entrySet()) {
            buffer.putInt(exthPosition, record.getKey());
            buffer.putInt(exthPosition + 4, 8 + record.getValue().length);
            buffer.position(exthPosition + 8);
            buffer.put(record.getValue());
            exthPosition += 8 + record.getValue().length;
        }
        buffer.position(firstRecordOffset + fullNameOffset);
        buffer.put(fullNameBytes);
        buffer.position(secondRecordOffset);
        buffer.put(text);
        Files.write(path, buffer.array());
    }

    private static int alignedToFourBytes(int value) {
        return (value + 3) & ~3;
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
        DECOMPRESSION_LIMIT,
        LARGE_UNRELATED_ENTRY,
        NEAR_LIMIT_METADATA
    }
}

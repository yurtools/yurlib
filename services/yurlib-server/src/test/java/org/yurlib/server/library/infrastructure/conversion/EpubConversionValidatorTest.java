package org.yurlib.server.library.infrastructure.conversion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpubConversionValidatorTest {

    @TempDir
    private Path temporaryDirectory;

    private final EpubConversionValidator validator = new EpubConversionValidator();

    @Test
    void acceptsABoundedEpubPackage() throws Exception {
        var epub = temporaryDirectory.resolve("valid.epub");
        writeEpub(epub, "<container><rootfile full-path=\"OPS/book.opf\"/></container>", null, null);

        validator.validate(epub);
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        var epub = temporaryDirectory.resolve("traversal.epub");
        writeEpub(epub, "<container><rootfile/></container>", "../escaped.xhtml", "unsafe");

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsafe or duplicate");
    }

    @Test
    void rejectsXmlEntityDeclarations() throws Exception {
        var epub = temporaryDirectory.resolve("entity.epub");
        writeEpub(
                epub,
                "<!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><container><rootfile/></container>",
                null,
                null);

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("container document");
    }

    @Test
    void rejectsExcessiveExpansionRatio() throws Exception {
        var epub = temporaryDirectory.resolve("expansion.epub");
        writeEpub(epub, "<container><rootfile/></container>", "OPS/repeated.txt", "a".repeat(1_000_000));

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expansion limits");
    }

    private static void writeEpub(Path path, String container, String extraName, String extraContent)
            throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            var mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            var crc = new CRC32();
            crc.update(mimetype);
            var entry = new ZipEntry("mimetype");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            entry.setCompressedSize(mimetype.length);
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(mimetype);
            output.closeEntry();
            writeEntry(output, "META-INF/container.xml", container);
            writeEntry(output, "OPS/book.opf", "<package version=\"3.0\"></package>");
            if (extraName != null) {
                writeEntry(output, extraName, extraContent);
            }
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}

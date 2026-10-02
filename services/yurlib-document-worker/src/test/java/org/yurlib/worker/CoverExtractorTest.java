package org.yurlib.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverExtractorTest {

    @TempDir
    private Path directory;

    private final CoverExtractor extractor = new CoverExtractor();

    @Test
    void extractsAndNormalizesDeclaredEpubCover() throws Exception {
        var source = directory.resolve("covered.epub");
        writeZip(
                source,
                Map.of(
                        "META-INF/container.xml", """
                        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                          <rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles>
                        </container>
                        """.getBytes(StandardCharsets.UTF_8),
                        "OPS/package.opf", """
                        <package xmlns="http://www.idpf.org/2007/opf">
                          <metadata><meta name="cover" content="cover-image"/></metadata>
                          <manifest><item id="cover-image" href="images/cover.png" media-type="image/png"/></manifest>
                        </package>
                        """.getBytes(StandardCharsets.UTF_8),
                        "OPS/images/cover.png", image(2400, 3600, true)));

        var result = extractor.extract(source, CoverClaim.Format.EPUB);

        assertThat(result.state()).isEqualTo(CoverResult.State.READY);
        assertThat(result.selectionKind()).isEqualTo(CoverResult.SelectionKind.DECLARED_EMBEDDED);
        assertThat(result.sourceLocator()).isEqualTo("OPS/images/cover.png");
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(2400);
        assertThat(Base64.getDecoder().decode(result.outputBase64())).isNotEmpty();
    }

    @Test
    void rejectsArchiveTraversalAndActiveDocxContent() throws Exception {
        var traversal = directory.resolve("traversal.epub");
        writeZip(traversal, Map.of("../cover.png", image(10, 10, false)));
        var active = directory.resolve("active.docx");
        writeZip(active, Map.of("word/vbaProject.bin", new byte[] {1}, "docProps/thumbnail.png", image(10, 10, false)));

        assertThat(extractor.extract(traversal, CoverClaim.Format.EPUB).state())
                .isEqualTo(CoverResult.State.FAILED_SAFE);
        var activeResult = extractor.extract(active, CoverClaim.Format.DOCX);
        assertThat(activeResult.state()).isEqualTo(CoverResult.State.FAILED_SAFE);
        assertThat(activeResult.errorCode()).isEqualTo("ACTIVE_CONTENT_REJECTED");
    }

    @Test
    void rejectsImageDimensionsBeforeDecode() throws Exception {
        var source = directory.resolve("oversized.docx");
        writeZip(source, Map.of("docProps/thumbnail.png", pngHeader(50_000, 50_000)));

        var result = extractor.extract(source, CoverClaim.Format.DOCX);

        assertThat(result.state()).isEqualTo(CoverResult.State.FAILED_SAFE);
        assertThat(result.errorCode()).isEqualTo("PARSE_LIMIT_EXCEEDED");
    }

    @Test
    void rendersPdfPageOneWithinOutputLimits() throws Exception {
        var source = directory.resolve("document.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(source.toFile());
        }

        var result = extractor.extract(source, CoverClaim.Format.PDF);

        assertThat(result.state()).isEqualTo(CoverResult.State.READY);
        assertThat(result.selectionKind()).isEqualTo(CoverResult.SelectionKind.PDF_PAGE_ONE);
        assertThat(result.width()).isLessThanOrEqualTo(1600);
        assertThat(result.height()).isLessThanOrEqualTo(2400);
    }

    private static byte[] image(int width, int height, boolean transparent) throws Exception {
        var image = new BufferedImage(
                width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(33, 67, 99, transparent ? 180 : 255));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static byte[] pngHeader(int width, int height) {
        var bytes = new byte[33];
        var signature = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        bytes[11] = 13;
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        writeInt(bytes, 16, width);
        writeInt(bytes, 20, height);
        bytes[24] = 8;
        bytes[25] = 2;
        return bytes;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeZip(Path target, Map<String, byte[]> content) throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : new LinkedHashMap<>(content).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}

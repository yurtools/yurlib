package org.yurlib.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfMetadataExtractorTest {

    @TempDir
    private Path directory;

    private final PdfMetadataExtractor extractor = new PdfMetadataExtractor();

    @Test
    void extractsMultilingualInformationAndXmpWhilePreservingConflicts() throws Exception {
        var source = directory.resolve("multilingual.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("Information title");
            document.getDocumentInformation().setAuthor("Анна Тестова");
            var xmp = """
                    <?xpacket begin=""?>
                    <x:xmpmeta xmlns:x="adobe:ns:meta/">
                      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                        <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
                          <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Заголовок XMP</rdf:li></rdf:Alt></dc:title>
                          <dc:creator><rdf:Seq><rdf:li>作者</rdf:li></rdf:Seq></dc:creator>
                          <dc:language><rdf:Bag><rdf:li>ru</rdf:li></rdf:Bag></dc:language>
                          <dc:identifier><rdf:Bag><rdf:li>urn:yurlib:pdf</rdf:li></rdf:Bag></dc:identifier>
                        </rdf:Description>
                      </rdf:RDF>
                    </x:xmpmeta>
                    <?xpacket end="w"?>
                    """;
            document.getDocumentCatalog()
                    .setMetadata(
                            new PDMetadata(document, new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8))));
            document.save(source.toFile());
        }

        var result = extractor.extract(source);

        assertThat(result.state()).isEqualTo(PdfMetadataResult.State.EXTRACTED);
        assertThat(result.title()).isEqualTo("Заголовок XMP");
        assertThat(result.contributors()).containsExactly("作者", "Анна Тестова");
        assertThat(result.language()).isEqualTo("ru");
        assertThat(result.identifiers()).containsEntry("xmp", "urn:yurlib:pdf");
        assertThat(result.observations().get("pdf:info:title")).contains("Information title");
        assertThat(result.observations().get("pdf:xmp:title")).contains("Заголовок XMP");
        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void classifiesEncryptedMalformedAndOversizedSourcesWithoutMetadataDisclosure() throws Exception {
        var encrypted = directory.resolve("encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("Private title");
            document.protect(new StandardProtectionPolicy("owner secret", "user secret", new AccessPermission()));
            document.save(encrypted.toFile());
        }
        var malformed = directory.resolve("malformed.pdf");
        Files.writeString(malformed, "%PDF-1.7\nmalformed", StandardCharsets.US_ASCII);
        var oversized = directory.resolve("oversized.pdf");
        try (var channel = FileChannel.open(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(PdfMetadataExtractor.MAXIMUM_SOURCE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        var encryptedResult = extractor.extract(encrypted);
        assertThat(encryptedResult.state()).isEqualTo(PdfMetadataResult.State.FAILED);
        assertThat(encryptedResult.errorCode()).isEqualTo("ENCRYPTED_ASSET");
        assertThat(encryptedResult.title()).isNull();
        assertThat(extractor.extract(malformed).errorCode()).isEqualTo("CORRUPT_ASSET");
        assertThat(extractor.extract(oversized).errorCode()).isEqualTo("PARSE_LIMIT_EXCEEDED");
    }

    @Test
    void acceptsSelectedValueAtLimitAndRejectsOneByteOver() throws Exception {
        var near = directory.resolve("near.pdf");
        var over = directory.resolve("over.pdf");
        writeWithTitle(near, "N".repeat(PdfMetadataExtractor.MAXIMUM_SELECTED_BYTES));
        writeWithTitle(over, "O".repeat(PdfMetadataExtractor.MAXIMUM_SELECTED_BYTES + 1));

        assertThat(extractor.extract(near).state()).isEqualTo(PdfMetadataResult.State.EXTRACTED);
        assertThat(extractor.extract(over).errorCode()).isEqualTo("PARSE_LIMIT_EXCEEDED");
    }

    private static void writeWithTitle(Path target, String title) throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle(title);
            document.save(target.toFile());
        }
    }
}

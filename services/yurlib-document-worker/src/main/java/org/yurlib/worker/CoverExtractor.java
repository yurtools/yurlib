package org.yurlib.worker;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.w3c.dom.Element;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification =
                "The isolated parser boundary converts checked parser, codec, and rendering failures into safe result codes.")
final class CoverExtractor {

    private static final int MAXIMUM_ENCODED_BYTES = 32 * 1024 * 1024;
    private static final long MAXIMUM_SOURCE_BYTES = 256L * 1024 * 1024;
    private static final long MAXIMUM_INPUT_PIXELS = 40_000_000L;
    private static final long MAXIMUM_DECODED_BYTES = 128L * 1024 * 1024;
    private static final long MAXIMUM_OUTPUT_PIXELS = 8_000_000L;
    private static final int MAXIMUM_WIDTH = 1600;
    private static final int MAXIMUM_HEIGHT = 2400;
    private static final int MAXIMUM_ZIP_ENTRIES = 10_000;
    private static final long MAXIMUM_ZIP_EXPANDED_BYTES = 512L * 1024 * 1024;

    CoverResult extract(Path source, CoverClaim.Format format) {
        try {
            var size = Files.size(source);
            if (size <= 0 || size > MAXIMUM_SOURCE_BYTES) {
                return CoverResult.failed("PARSE_LIMIT_EXCEEDED", "The source exceeds the cover processing limit.");
            }
            var selected =
                    switch (format) {
                        case EPUB -> epub(source);
                        case FB2 -> fb2(source);
                        case MOBI -> mobi(source);
                        case DOCX -> docx(source);
                        case PDF -> pdf(source);
                        case DJVU -> djvu(source);
                    };
            if (selected == null) {
                return CoverResult.unavailable("The source has no supported cover candidate.");
            }
            var normalized = normalize(selected.bytes());
            return CoverResult.ready(
                    selected.kind(),
                    selected.locator(),
                    sha256(selected.bytes()),
                    normalized.width(),
                    normalized.height(),
                    normalized.mediaType(),
                    Base64.getEncoder().encodeToString(normalized.bytes()));
        } catch (LimitFailure failure) {
            return CoverResult.failed("PARSE_LIMIT_EXCEEDED", failure.getMessage());
        } catch (ActiveContentFailure failure) {
            return CoverResult.failed("ACTIVE_CONTENT_REJECTED", failure.getMessage());
        } catch (UnsupportedOperationException failure) {
            return CoverResult.failed("UNSUPPORTED_FORMAT", "The cover format is not supported safely.");
        } catch (Exception failure) {
            return CoverResult.failed("CORRUPT_ASSET", "The cover candidate could not be processed safely.");
        }
    }

    private static Candidate epub(Path source) throws Exception {
        try (var zip = checkedZip(source)) {
            var container = xml(readEntry(zip, "META-INF/container.xml", 1024 * 1024));
            var rootfiles = container.getElementsByTagNameNS("*", "rootfile");
            if (rootfiles.getLength() != 1) {
                throw new IOException("EPUB container rootfile is invalid.");
            }
            var packagePath = safeZipName(((Element) rootfiles.item(0)).getAttribute("full-path"));
            var packageDocument = xml(readEntry(zip, packagePath, 4 * 1024 * 1024));
            String coverId = null;
            var metadata = packageDocument.getElementsByTagNameNS("*", "meta");
            for (var index = 0; index < metadata.getLength(); index++) {
                var element = (Element) metadata.item(index);
                if ("cover".equals(element.getAttribute("name"))) {
                    coverId = element.getAttribute("content");
                    break;
                }
            }
            String href = null;
            var items = packageDocument.getElementsByTagNameNS("*", "item");
            for (var index = 0; index < items.getLength(); index++) {
                var element = (Element) items.item(index);
                var properties = element.getAttribute("properties");
                if ((coverId != null && coverId.equals(element.getAttribute("id")))
                        || java.util.Arrays.asList(properties.split("\\s+")).contains("cover-image")) {
                    var mediaType = element.getAttribute("media-type");
                    if (!"image/jpeg".equals(mediaType) && !"image/png".equals(mediaType)) {
                        throw new ActiveContentFailure("Active or unsupported EPUB cover content was rejected.");
                    }
                    href = element.getAttribute("href");
                    break;
                }
            }
            if (href == null || href.isBlank()) {
                return null;
            }
            var parent = Path.of(packagePath).getParent();
            var resolved = safeZipName((parent == null ? Path.of("") : parent)
                    .resolve(href)
                    .normalize()
                    .toString());
            return new Candidate(
                    readEntry(zip, resolved, MAXIMUM_ENCODED_BYTES),
                    CoverResult.SelectionKind.DECLARED_EMBEDDED,
                    resolved);
        }
    }

    private static Candidate docx(Path source) throws Exception {
        try (var zip = checkedZip(source)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (name.contains("vbaproject.bin")
                        || name.startsWith("word/activex/")
                        || name.startsWith("word/embeddings/")) {
                    throw new ActiveContentFailure("Active DOCX package content was rejected.");
                }
            }
            for (var name :
                    new String[] {"docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png"}) {
                if (zip.getEntry(name) != null) {
                    return new Candidate(
                            readEntry(zip, name, MAXIMUM_ENCODED_BYTES),
                            CoverResult.SelectionKind.DOCX_THUMBNAIL,
                            name);
                }
            }
            return null;
        }
    }

    private static Candidate fb2(Path source) throws Exception {
        var href = fb2CoverHref(source);
        if (href == null) {
            return null;
        }
        var binary = fb2Binary(source, href);
        return binary.isEmpty()
                ? null
                : new Candidate(binary.get(), CoverResult.SelectionKind.DECLARED_EMBEDDED, "binary#" + href);
    }

    @SuppressFBWarnings(
            value = "XXE_XMLSTREAMREADER",
            justification = "The StAX factory disables DTDs and external entities and installs a rejecting resolver.")
    private static String fb2CoverHref(Path source) throws Exception {
        var factory = safeXmlInputFactory();
        try (var input = Files.newInputStream(source)) {
            var reader = factory.createXMLStreamReader(input);
            var inCover = false;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("coverpage".equals(reader.getLocalName())) {
                        inCover = true;
                    } else if (inCover && "image".equals(reader.getLocalName())) {
                        for (var index = 0; index < reader.getAttributeCount(); index++) {
                            if ("href".equals(reader.getAttributeLocalName(index))) {
                                return stripFragment(reader.getAttributeValue(index));
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "coverpage".equals(reader.getLocalName())) {
                    inCover = false;
                }
            }
            return null;
        }
    }

    @SuppressFBWarnings(
            value = "XXE_XMLSTREAMREADER",
            justification = "The StAX factory disables DTDs and external entities and installs a rejecting resolver.")
    private static Optional<byte[]> fb2Binary(Path source, String coverId) throws Exception {
        var factory = safeXmlInputFactory();
        try (var input = Files.newInputStream(source)) {
            var reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "binary".equals(reader.getLocalName())) {
                    var id = reader.getAttributeValue(null, "id");
                    if (coverId.equals(id)) {
                        var encoded = reader.getElementText();
                        if (encoded.length() > ((MAXIMUM_ENCODED_BYTES * 4L / 3L) + 8192L)) {
                            throw new LimitFailure("The embedded FB2 cover exceeds the encoded-size limit.");
                        }
                        var decoded = Base64.getMimeDecoder().decode(encoded);
                        if (decoded.length > MAXIMUM_ENCODED_BYTES) {
                            throw new LimitFailure("The embedded FB2 cover exceeds the encoded-size limit.");
                        }
                        return Optional.of(decoded);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private static Candidate mobi(Path source) throws Exception {
        try (var file = new RandomAccessFile(source.toFile(), "r")) {
            if (file.length() < 100) {
                throw new IOException("MOBI source is truncated.");
            }
            file.seek(76);
            var recordCount = file.readUnsignedShort();
            if (recordCount < 2 || recordCount > 65_535) {
                throw new LimitFailure("The MOBI record-count limit was exceeded.");
            }
            var offsets = new long[recordCount + 1];
            for (var index = 0; index < recordCount; index++) {
                file.seek(78L + (index * 8L));
                offsets[index] = Integer.toUnsignedLong(file.readInt());
            }
            offsets[recordCount] = file.length();
            var record0 = offsets[0];
            file.seek(record0 + 16);
            if (!"MOBI".equals(readAscii(file, 4))) {
                return null;
            }
            var mobiStart = record0 + 16;
            var headerLength = readUnsignedInt(file);
            if (headerLength < 116 || mobiStart + headerLength > offsets[1]) {
                throw new IOException("MOBI header is invalid.");
            }
            file.seek(mobiStart + 108);
            var firstImage = readUnsignedInt(file);
            file.seek(mobiStart + 128);
            var exthFlags = file.readInt();
            if ((exthFlags & 0x40) == 0) {
                return null;
            }
            var exthStart = mobiStart + headerLength;
            file.seek(exthStart);
            if (!"EXTH".equals(readAscii(file, 4))) {
                throw new IOException("MOBI EXTH header is invalid.");
            }
            var exthLength = readUnsignedInt(file);
            var exthCount = readUnsignedInt(file);
            if (exthLength > 1024 * 1024 || exthCount > 1024) {
                throw new LimitFailure("The MOBI EXTH limit was exceeded.");
            }
            long coverOffset = -1;
            var cursor = exthStart + 12;
            for (var index = 0L; index < exthCount; index++) {
                file.seek(cursor);
                var type = readUnsignedInt(file);
                var length = readUnsignedInt(file);
                if (length < 8 || cursor + length > exthStart + exthLength) {
                    throw new IOException("MOBI EXTH record is invalid.");
                }
                if (type == 201 && length >= 12) {
                    coverOffset = readUnsignedInt(file);
                }
                cursor += length;
            }
            if (coverOffset < 0) {
                return null;
            }
            var imageIndex = Math.addExact(firstImage, coverOffset);
            if (imageIndex < 0 || imageIndex >= recordCount) {
                throw new IOException("MOBI cover record is invalid.");
            }
            var length = offsets[(int) imageIndex + 1] - offsets[(int) imageIndex];
            if (length <= 0 || length > MAXIMUM_ENCODED_BYTES) {
                throw new LimitFailure("The MOBI cover exceeds the encoded-size limit.");
            }
            var bytes = new byte[(int) length];
            file.seek(offsets[(int) imageIndex]);
            file.readFully(bytes);
            return new Candidate(bytes, CoverResult.SelectionKind.DECLARED_EMBEDDED, "palm-record:" + imageIndex);
        }
    }

    private static Candidate pdf(Path source) throws Exception {
        try (var document = Loader.loadPDF(source.toFile(), "", IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1) {
                return null;
            }
            var box = document.getPage(0).getCropBox();
            var scale = Math.min(MAXIMUM_WIDTH / box.getWidth(), MAXIMUM_HEIGHT / box.getHeight());
            scale = Math.min(scale, 2.0f);
            var width = Math.max(1, Math.round(box.getWidth() * scale));
            var height = Math.max(1, Math.round(box.getHeight() * scale));
            enforceDimensions(width, height);
            var image = new PDFRenderer(document).renderImage(0, scale);
            return new Candidate(encode(image, "jpg"), CoverResult.SelectionKind.PDF_PAGE_ONE, "page:1");
        }
    }

    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification = "The executable and arguments are fixed and the source is a worker-controlled UUID path.")
    @SuppressWarnings("PMD.CloseResource")
    private static Candidate djvu(Path source) throws Exception {
        var parent = java.util.Objects.requireNonNull(source.getParent(), "The worker input must have a parent.");
        var output = parent.resolve("djvu-page-one.tiff");
        var process = new ProcessBuilder(
                        "ddjvu",
                        "-format=tiff",
                        "-page=1",
                        "-size=" + MAXIMUM_WIDTH + "x" + MAXIMUM_HEIGHT,
                        source.toString(),
                        output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new LimitFailure("The DjVu page-one render deadline was exceeded.");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            throw new IOException("DjVu page-one rendering failed.");
        }
        return new Candidate(Files.readAllBytes(output), CoverResult.SelectionKind.DJVU_PAGE_ONE, "page:1");
    }

    private static NormalizedImage normalize(byte[] encoded) throws Exception {
        if (encoded.length == 0 || encoded.length > MAXIMUM_ENCODED_BYTES) {
            throw new LimitFailure("The cover exceeds the encoded-size limit.");
        }
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new UnsupportedOperationException("Unsupported image format");
            }
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                enforceDimensions(width, height);
                var source = reader.read(0);
                if (source == null) {
                    throw new IOException("Image decode failed.");
                }
                var scale = Math.min(1.0, Math.min((double) MAXIMUM_WIDTH / width, (double) MAXIMUM_HEIGHT / height));
                if ((long) Math.ceil(width * scale) * (long) Math.ceil(height * scale) > MAXIMUM_OUTPUT_PIXELS) {
                    scale = Math.sqrt((double) MAXIMUM_OUTPUT_PIXELS / ((double) width * height));
                }
                var outputWidth = Math.max(1, (int) Math.floor(width * scale));
                var outputHeight = Math.max(1, (int) Math.floor(height * scale));
                var transparent = source.getColorModel().hasAlpha();
                var target = new BufferedImage(
                        outputWidth,
                        outputHeight,
                        transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
                } finally {
                    graphics.dispose();
                }
                var format = transparent ? "png" : "jpg";
                var mediaType = transparent ? "image/png" : "image/jpeg";
                var bytes = encode(target, format);
                if (bytes.length > 16 * 1024 * 1024) {
                    throw new LimitFailure("The normalized cover exceeds the output-size limit.");
                }
                return new NormalizedImage(bytes, outputWidth, outputHeight, mediaType);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void enforceDimensions(int width, int height) throws LimitFailure {
        var pixels = Math.multiplyExact((long) width, height);
        if (width <= 0 || height <= 0 || pixels > MAXIMUM_INPUT_PIXELS || pixels * 4L > MAXIMUM_DECODED_BYTES) {
            throw new LimitFailure("The cover exceeds the pixel or decoded-allocation limit.");
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IOException("No safe image encoder is available.");
            }
            return output.toByteArray();
        }
    }

    private static ZipFile checkedZip(Path source) throws IOException, LimitFailure {
        var zip = new ZipFile(source.toFile());
        var count = 0;
        long expanded = 0;
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                safeZipName(entry.getName());
                count++;
                if (count > MAXIMUM_ZIP_ENTRIES) {
                    throw new LimitFailure("The archive entry-count limit was exceeded.");
                }
                if (entry.getSize() > 0) {
                    expanded = Math.addExact(expanded, entry.getSize());
                    if (expanded > MAXIMUM_ZIP_EXPANDED_BYTES
                            || (entry.getCompressedSize() > 0 && entry.getSize() / entry.getCompressedSize() > 100)) {
                        throw new LimitFailure("The archive expansion limit was exceeded.");
                    }
                }
            }
            return zip;
        } catch (IOException | RuntimeException failure) {
            zip.close();
            throw failure;
        }
    }

    private static byte[] readEntry(ZipFile zip, String name, int maximum) throws IOException, LimitFailure {
        var entry = zip.getEntry(safeZipName(name));
        if (entry == null || entry.isDirectory() || entry.getSize() > maximum) {
            throw new LimitFailure("The selected archive entry exceeds its size limit.");
        }
        try (var input = zip.getInputStream(entry)) {
            var bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new LimitFailure("The selected archive entry exceeds its size limit.");
            }
            return bytes;
        }
    }

    private static String safeZipName(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw new IOException("Archive path is unsafe.");
        }
        var normalized = Path.of(name).normalize().toString().replace('\\', '/');
        if (!normalized.equals(name) || normalized.startsWith("../") || "..".equals(normalized)) {
            throw new IOException("Archive path is unsafe.");
        }
        return normalized;
    }

    private static org.w3c.dom.Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static XMLInputFactory safeXmlInputFactory() {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("External XML resources are prohibited.");
        });
        return factory;
    }

    private static String stripFragment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var stripped = value.startsWith("#") ? value.substring(1) : value;
        return stripped.isBlank() ? null : stripped;
    }

    private static String readAscii(RandomAccessFile file, int count) throws IOException {
        var bytes = new byte[count];
        file.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static long readUnsignedInt(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(file.readInt());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    private record Candidate(byte[] bytes, CoverResult.SelectionKind kind, String locator) {}

    private record NormalizedImage(byte[] bytes, int width, int height, String mediaType) {}

    private static final class LimitFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private LimitFailure(String message) {
            super(message);
        }
    }

    private static final class ActiveContentFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private ActiveContentFailure(String message) {
            super(message);
        }
    }
}

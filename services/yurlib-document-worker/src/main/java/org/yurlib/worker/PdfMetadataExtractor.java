package org.yurlib.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public final class PdfMetadataExtractor {

    static final long MAXIMUM_SOURCE_BYTES = 4L * 1024 * 1024 * 1024;
    static final int MAXIMUM_XMP_BYTES = 4 * 1024 * 1024;
    static final int MAXIMUM_SELECTED_BYTES = 64 * 1024;
    static final int MAXIMUM_PAGES = 100_000;
    private static final int MAXIMUM_INFO_FIELDS = 256;
    private static final int HEADER_PREFIX_BYTES = 1024;
    private static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";

    public PdfMetadataResult extract(Path source) {
        try {
            validateSource(source);
            try (var document = Loader.loadPDF(source.toFile(), "", IOUtils.createTempFileOnlyStreamCache())) {
                if (document.isEncrypted()) {
                    return PdfMetadataResult.failed(
                            "ENCRYPTED_ASSET", "The PDF is encrypted and metadata was not disclosed.");
                }
                var pageCount = document.getNumberOfPages();
                if (pageCount < 1 || pageCount > MAXIMUM_PAGES) {
                    throw new LimitExceededException("The PDF page-count limit was exceeded.");
                }
                var observations = new LinkedHashMap<String, List<String>>();
                var information = readInformation(document.getDocumentInformation(), observations);
                var xmp = readXmp(document.getDocumentCatalog().getMetadata(), observations);
                var title = first(xmp, "title", first(information, "title", null));
                var language = first(xmp, "language", null);
                var contributors = new LinkedHashSet<String>();
                contributors.addAll(xmp.getOrDefault("creator", List.of()));
                contributors.addAll(information.getOrDefault("author", List.of()));
                var identifiers = new LinkedHashMap<String, String>();
                var identifier = first(xmp, "identifier", null);
                if (identifier != null) {
                    identifiers.put("xmp", identifier);
                }
                observations.put("pdf:page-count", List.of(Integer.toString(pageCount)));
                return PdfMetadataResult.extracted(
                        title, List.copyOf(contributors), language, identifiers, observations, pageCount);
            }
        } catch (InvalidPasswordException failure) {
            return PdfMetadataResult.failed("ENCRYPTED_ASSET", "The PDF requires a password.");
        } catch (LimitExceededException failure) {
            return PdfMetadataResult.failed("PARSE_LIMIT_EXCEEDED", failure.getMessage());
        } catch (IOException | SAXException | ParserConfigurationException failure) {
            return PdfMetadataResult.failed("CORRUPT_ASSET", "The PDF metadata could not be parsed safely.");
        } catch (RuntimeException failure) {
            return PdfMetadataResult.failed("UNSUPPORTED_FORMAT", "The PDF uses an unsupported structure.");
        }
    }

    private static void validateSource(Path source) throws IOException, LimitExceededException {
        var size = Files.size(source);
        if (size <= 0 || size > MAXIMUM_SOURCE_BYTES) {
            throw new LimitExceededException("The PDF source-size limit was exceeded.");
        }
        try (var input = Files.newInputStream(source)) {
            var prefix = input.readNBytes((int) Math.min(size, HEADER_PREFIX_BYTES));
            if (indexOf(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII)) < 0) {
                throw new IOException("PDF signature missing");
            }
        }
    }

    private static Map<String, List<String>> readInformation(
            PDDocumentInformation information, Map<String, List<String>> observations) throws LimitExceededException {
        var result = new LinkedHashMap<String, List<String>>();
        add(result, observations, "title", information.getTitle());
        add(result, observations, "author", information.getAuthor());
        add(result, observations, "subject", information.getSubject());
        add(result, observations, "keywords", information.getKeywords());
        add(result, observations, "creator", information.getCreator());
        add(result, observations, "producer", information.getProducer());
        add(result, observations, "trapped", information.getTrapped());
        if (information.getCreationDate() != null) {
            add(
                    result,
                    observations,
                    "creation-date",
                    DateTimeFormatter.ISO_INSTANT.format(
                            information.getCreationDate().toInstant().atOffset(ZoneOffset.UTC)));
        }
        if (information.getModificationDate() != null) {
            add(
                    result,
                    observations,
                    "modification-date",
                    DateTimeFormatter.ISO_INSTANT.format(
                            information.getModificationDate().toInstant().atOffset(ZoneOffset.UTC)));
        }
        var fieldCount = 0;
        for (var key : information.getMetadataKeys()) {
            fieldCount++;
            if (fieldCount > MAXIMUM_INFO_FIELDS) {
                throw new LimitExceededException("The PDF information-field limit was exceeded.");
            }
            var normalizedKey = selected(key);
            var value = selected(information.getCustomMetadataValue(key));
            if (normalizedKey != null && value != null) {
                observations
                        .computeIfAbsent(
                                "pdf:info:" + normalizedKey.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(value);
            }
        }
        return result;
    }

    private static void add(
            Map<String, List<String>> values, Map<String, List<String>> observations, String key, String rawValue)
            throws LimitExceededException {
        var value = selected(rawValue);
        if (value != null) {
            values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            observations
                    .computeIfAbsent("pdf:info:" + key, ignored -> new ArrayList<>())
                    .add(value);
        }
    }

    private static Map<String, List<String>> readXmp(PDMetadata metadata, Map<String, List<String>> observations)
            throws IOException, ParserConfigurationException, SAXException, LimitExceededException {
        if (metadata == null) {
            return Map.of();
        }
        byte[] bytes;
        try (var input = metadata.exportXMPMetadata()) {
            bytes = boundedRead(input, MAXIMUM_XMP_BYTES);
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document;
        try (var input = new java.io.ByteArrayInputStream(bytes)) {
            document = factory.newDocumentBuilder().parse(input);
        }
        var values = new LinkedHashMap<String, List<String>>();
        for (var key : List.of("title", "creator", "language", "identifier", "subject", "description")) {
            var nodes = document.getElementsByTagNameNS(DC_NAMESPACE, key);
            for (var index = 0; index < nodes.getLength(); index++) {
                if (nodes.item(index) instanceof Element element) {
                    var leaves = element.getElementsByTagNameNS("*", "li");
                    if (leaves.getLength() == 0) {
                        addXmp(values, observations, key, element.getTextContent());
                    } else {
                        for (var leafIndex = 0; leafIndex < leaves.getLength(); leafIndex++) {
                            addXmp(
                                    values,
                                    observations,
                                    key,
                                    leaves.item(leafIndex).getTextContent());
                        }
                    }
                }
            }
        }
        return values;
    }

    private static void addXmp(
            Map<String, List<String>> values, Map<String, List<String>> observations, String key, String rawValue)
            throws LimitExceededException {
        var value = selected(rawValue);
        if (value != null) {
            values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            observations
                    .computeIfAbsent("pdf:xmp:" + key, ignored -> new ArrayList<>())
                    .add(value);
        }
    }

    private static byte[] boundedRead(InputStream input, int maximum) throws IOException, LimitExceededException {
        var bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw new LimitExceededException("The PDF XMP-size limit was exceeded.");
        }
        return bytes;
    }

    private static String selected(String rawValue) throws LimitExceededException {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        var value = rawValue.strip();
        if (value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_SELECTED_BYTES) {
            throw new LimitExceededException("The PDF selected-value limit was exceeded.");
        }
        return value;
    }

    private static String first(Map<String, List<String>> values, String key, String fallback) {
        var selected = values.get(key);
        return selected == null || selected.isEmpty() ? fallback : selected.getFirst();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1)
                .indexOf(new String(needle, StandardCharsets.ISO_8859_1));
    }

    private static final class LimitExceededException extends Exception {
        private static final long serialVersionUID = 1L;

        private LimitExceededException(String message) {
            super(message);
        }
    }
}

package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class EpubMetadataParser implements MetadataParser {

    private static final int MAXIMUM_ENTRIES = 10_000;
    private static final long MAXIMUM_SOURCE_BYTES = 256L * 1024 * 1024;
    private static final long MAXIMUM_ENTRY_BYTES = 1024L * 1024;
    private static final long MAXIMUM_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAXIMUM_COMPRESSION_RATIO = 100;
    private static final int MAXIMUM_XML_BYTES = 1024 * 1024;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.EPUB;
    }

    @Override
    public long maximumSourceBytes() {
        return MAXIMUM_SOURCE_BYTES;
    }

    @Override
    public ParsedBookMetadata parse(Path file) throws IOException, MetadataParsingException {
        ZipSecurityInspector.rejectEncryptedEntries(file, MAXIMUM_ENTRIES);
        try (var archive = new ZipFile(file.toFile())) {
            validateArchive(archive);
            requireMimetype(archive);
            var packagePath = packagePath(archive);
            var packageEntry = requireSafeEntry(archive, packagePath);
            try (var input = archive.getInputStream(packageEntry)) {
                var document = SecureXml.parse(input, MAXIMUM_XML_BYTES);
                return new ParsedBookMetadata(
                        format(),
                        SecureXml.firstText(document, "title"),
                        texts(document, "creator"),
                        SecureXml.firstText(document, "language"),
                        identifiers(document),
                        "jdk-epub",
                        "1");
            }
        }
    }

    private static void validateArchive(ZipFile archive) throws MetadataParsingException {
        var entries = archive.entries();
        long expandedBytes = 0;
        var count = 0;
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            count++;
            if (count > MAXIMUM_ENTRIES) {
                throw limit("The EPUB contains too many entries.");
            }
            validateName(entry.getName());
            if (entry.isDirectory()) {
                continue;
            }
            var size = entry.getSize();
            var compressedSize = entry.getCompressedSize();
            if (size < 0 || compressedSize < 0) {
                throw corrupt("The EPUB contains an entry with unknown size.");
            }
            if (size > MAXIMUM_ENTRY_BYTES) {
                throw limit("An EPUB entry exceeds the configured size limit.");
            }
            if (size > MAXIMUM_COMPRESSION_RATIO * Math.max(1, compressedSize)) {
                throw limit("An EPUB entry exceeds the configured compression ratio.");
            }
            try {
                expandedBytes = Math.addExact(expandedBytes, size);
            } catch (ArithmeticException exception) {
                throw limit("The EPUB exceeds the configured expanded-size limit.", exception);
            }
            if (expandedBytes > MAXIMUM_EXPANDED_BYTES) {
                throw limit("The EPUB exceeds the configured expanded-size limit.");
            }
        }
    }

    private static void requireMimetype(ZipFile archive) throws IOException, MetadataParsingException {
        var entry = requireSafeEntry(archive, "mimetype");
        try (var input = archive.getInputStream(entry)) {
            var value = new String(input.readNBytes(64), java.nio.charset.StandardCharsets.US_ASCII);
            if (!"application/epub+zip".equals(value)) {
                throw corrupt("The EPUB mimetype is invalid.");
            }
        }
    }

    private static String packagePath(ZipFile archive) throws IOException, MetadataParsingException {
        var container = requireSafeEntry(archive, "META-INF/container.xml");
        try (var input = archive.getInputStream(container)) {
            var document = SecureXml.parse(input, MAXIMUM_XML_BYTES);
            var rootFiles = document.getElementsByTagNameNS("*", "rootfile");
            if (rootFiles.getLength() == 0 || !(rootFiles.item(0) instanceof Element rootFile)) {
                throw corrupt("The EPUB package reference is missing.");
            }
            var path = SecureXml.normalized(rootFile.getAttribute("full-path"));
            validateName(path);
            return path;
        }
    }

    private static ZipEntry requireSafeEntry(ZipFile archive, String name) throws MetadataParsingException {
        validateName(name);
        var entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw corrupt("The EPUB metadata structure is incomplete.");
        }
        return entry;
    }

    private static void validateName(String name) throws MetadataParsingException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw corrupt("The EPUB contains an unsafe entry path.");
        }
        try {
            var path = Path.of(name);
            var hasTraversal = false;
            for (var element : path) {
                if (".".equals(element.toString()) || "..".equals(element.toString())) {
                    hasTraversal = true;
                    break;
                }
            }
            if (path.isAbsolute()
                    || hasTraversal
                    || !path.normalize().toString().replace('\\', '/').equals(name)) {
                throw corrupt("The EPUB contains an unsafe entry path.");
            }
        } catch (java.nio.file.InvalidPathException exception) {
            throw corrupt("The EPUB contains an unsafe entry path.", exception);
        }
    }

    private static java.util.List<String> texts(org.w3c.dom.Document document, String localName) {
        var values = new ArrayList<String>();
        var nodes = document.getElementsByTagNameNS("*", localName);
        for (var index = 0; index < nodes.getLength(); index++) {
            var value = SecureXml.normalized(nodes.item(index).getTextContent());
            if (value != null) {
                values.add(value);
            }
        }
        return java.util.List.copyOf(values);
    }

    private static Map<String, String> identifiers(org.w3c.dom.Document document) {
        var values = new LinkedHashMap<String, String>();
        var nodes = document.getElementsByTagNameNS("*", "identifier");
        for (var index = 0; index < nodes.getLength(); index++) {
            var value = SecureXml.normalized(nodes.item(index).getTextContent());
            if (value != null) {
                values.putIfAbsent("identifier-" + (values.size() + 1), value);
            }
        }
        return Map.copyOf(values);
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private static MetadataParsingException corrupt(String message, Throwable cause) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message, cause);
    }

    private static MetadataParsingException limit(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED, message);
    }

    private static MetadataParsingException limit(String message, Throwable cause) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED, message, cause);
    }
}

package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

@SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
final class EpubMetadataParser implements MetadataParser {

    private static final long MAXIMUM_COMPRESSION_RATIO = 100;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.EPUB;
    }

    @Override
    public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        ZipSecurityInspector.inspect(file, budget);
        try (var archiveLease = budget.openFile();
                var archive = new ZipFile(file.toFile())) {
            validateArchive(archive, budget);
            requireMimetype(archive, budget);
            var packagePath = packagePath(archive, budget);
            var packageEntry =
                    requireSelectedEntry(archive, packagePath, budget.limits().maximumXmlBytes());
            try (var entryLease = budget.openFile();
                    var input = budget.count(archive.getInputStream(packageEntry))) {
                var document = SecureXml.parse(input, budget);
                return new ParsedBookMetadata(
                        format(),
                        SecureXml.firstText(document, "title", budget),
                        texts(document, "creator", budget),
                        SecureXml.firstText(document, "language", budget),
                        identifiers(document, budget),
                        "jdk-epub",
                        "3");
            }
        }
    }

    private static void validateArchive(ZipFile archive, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var entries = archive.entries();
        var canonicalNames = new HashSet<String>();
        var count = 0;
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            count++;
            if (count > budget.limits().maximumArchiveEntries()) {
                throw MetadataParsingException.limit(
                        "archive-entry-count", budget.limits().maximumArchiveEntries(), "entries");
            }
            var canonicalName = validateName(entry.getName(), entry.isDirectory());
            if (!canonicalNames.add(canonicalName)) {
                throw corrupt("The EPUB contains ambiguous entry paths.");
            }
            budget.checkpoint();
        }
    }

    private static void requireMimetype(ZipFile archive, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var entry = requireSelectedEntry(archive, "mimetype", 64);
        budget.recordControlledBuffer(65);
        try (var entryLease = budget.openFile();
                var input = budget.count(archive.getInputStream(entry))) {
            var value = new String(input.readNBytes(65), java.nio.charset.StandardCharsets.US_ASCII);
            if (!"application/epub+zip".equals(value)) {
                throw corrupt("The EPUB mimetype is invalid.");
            }
        }
    }

    private static String packagePath(ZipFile archive, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var container = requireSelectedEntry(
                archive, "META-INF/container.xml", budget.limits().maximumXmlBytes());
        try (var entryLease = budget.openFile();
                var input = budget.count(archive.getInputStream(container))) {
            var document = SecureXml.parse(input, budget);
            var rootFiles = document.getElementsByTagNameNS("*", "rootfile");
            if (rootFiles.getLength() == 0 || !(rootFiles.item(0) instanceof Element rootFile)) {
                throw corrupt("The EPUB package reference is missing.");
            }
            var path = SecureXml.normalizedSelected(rootFile.getAttribute("full-path"), budget);
            validateName(path, false);
            return path;
        }
    }

    private static ZipEntry requireSelectedEntry(ZipFile archive, String name, long maximumExpandedBytes)
            throws MetadataParsingException {
        validateName(name, false);
        var entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw corrupt("The EPUB metadata structure is incomplete.");
        }
        var size = entry.getSize();
        var compressedSize = entry.getCompressedSize();
        if (size < 0 || compressedSize < 0) {
            throw corrupt("The EPUB contains selected metadata with an unknown size.");
        }
        if (size > maximumExpandedBytes) {
            throw MetadataParsingException.limit("selected-entry-expansion", maximumExpandedBytes, "bytes");
        }
        if (size > MAXIMUM_COMPRESSION_RATIO * Math.max(1, compressedSize)) {
            throw MetadataParsingException.limit(
                    "selected-entry-compression-ratio", MAXIMUM_COMPRESSION_RATIO, "to-one");
        }
        return entry;
    }

    private static String validateName(String name, boolean directory) throws MetadataParsingException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw corrupt("The EPUB contains an unsafe entry path.");
        }
        try {
            var trailingSlash = name.endsWith("/");
            if (directory != trailingSlash) {
                throw corrupt("The EPUB contains an unsafe entry path.");
            }
            var canonicalName = trailingSlash ? name.substring(0, name.length() - 1) : name;
            if (canonicalName.isBlank()) {
                throw corrupt("The EPUB contains an unsafe entry path.");
            }
            for (var segment : canonicalName.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw corrupt("The EPUB contains an unsafe entry path.");
                }
            }
            var path = Path.of(canonicalName);
            if (path.isAbsolute()
                    || !path.normalize().toString().replace('\\', '/').equals(canonicalName)) {
                throw corrupt("The EPUB contains an unsafe entry path.");
            }
            return canonicalName;
        } catch (java.nio.file.InvalidPathException exception) {
            throw corrupt("The EPUB contains an unsafe entry path.", exception);
        }
    }

    private static java.util.List<String> texts(
            org.w3c.dom.Document document, String localName, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var values = new ArrayList<String>();
        var nodes = document.getElementsByTagNameNS("*", localName);
        for (var index = 0; index < nodes.getLength(); index++) {
            var value = SecureXml.normalizedSelected(nodes.item(index).getTextContent(), budget);
            if (value != null) {
                values.add(value);
            }
        }
        return java.util.List.copyOf(values);
    }

    private static Map<String, String> identifiers(org.w3c.dom.Document document, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var values = new LinkedHashMap<String, String>();
        var nodes = document.getElementsByTagNameNS("*", "identifier");
        for (var index = 0; index < nodes.getLength(); index++) {
            var value = SecureXml.normalizedSelected(nodes.item(index).getTextContent(), budget);
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
}

package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

@SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
final class DocxMetadataParser implements MetadataParser {

    private static final long MAXIMUM_COMPRESSION_RATIO = 100;
    private static final String DOCUMENT_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.DOCX;
    }

    @Override
    public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        rejectCompoundPackage(file, budget);
        ZipSecurityInspector.inspect(file, budget, "DOCX");
        try (var archiveLease = budget.openFile();
                var archive = new ZipFile(file.toFile())) {
            validateArchive(archive, budget);
            var contentTypes = parseRequiredXml(archive, "[Content_Types].xml", budget);
            validateContentTypes(contentTypes);
            validateRelationships(archive, budget);
            var core = parseOptionalXml(archive, "docProps/core.xml", budget);
            var custom = parseOptionalXml(archive, "docProps/custom.xml", budget);
            return metadata(core, custom, budget);
        }
    }

    private static void validateArchive(ZipFile archive, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var entries = archive.entries();
        var count = 0;
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            count++;
            if (count > budget.limits().maximumArchiveEntries()) {
                throw MetadataParsingException.limit(
                        "archive-entry-count", budget.limits().maximumArchiveEntries(), "entries");
            }
            validateName(entry.getName());
            var normalized = asciiLower(entry.getName());
            if ("word/vbaproject.bin".equals(normalized)
                    || normalized.startsWith("word/activex/")
                    || normalized.startsWith("word/embeddings/")) {
                throw unsupported("The DOCX package contains unsupported active or embedded content.");
            }
            budget.checkpoint();
        }
    }

    private static void validateContentTypes(Document document) throws MetadataParsingException {
        var overrides = document.getElementsByTagNameNS("*", "Override");
        var hasDocument = false;
        for (var index = 0; index < overrides.getLength(); index++) {
            if (!(overrides.item(index) instanceof Element element)) {
                continue;
            }
            var partName = element.getAttribute("PartName");
            var contentType = element.getAttribute("ContentType");
            if ("/word/document.xml".equals(partName) && DOCUMENT_CONTENT_TYPE.equals(contentType)) {
                hasDocument = true;
            }
            if (asciiLower(contentType).contains("macroenabled")) {
                throw unsupported("Macro-enabled Office packages are not supported as DOCX.");
            }
        }
        if (!hasDocument) {
            throw corrupt("The DOCX main-document content type is missing or unsupported.");
        }
    }

    private static void validateRelationships(ZipFile archive, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".rels")) {
                var document = parseXml(archive, entry, budget);
                var relationships = document.getElementsByTagNameNS("*", "Relationship");
                for (var index = 0; index < relationships.getLength(); index++) {
                    if (!(relationships.item(index) instanceof Element relationship)) {
                        continue;
                    }
                    if ("external".equals(asciiLower(relationship.getAttribute("TargetMode")))) {
                        throw unsupported("External DOCX relationships are not supported.");
                    }
                    validateRelationshipTarget(entry.getName(), relationship.getAttribute("Target"));
                }
            }
        }
    }

    private static void validateRelationshipTarget(String relationshipsName, String target)
            throws MetadataParsingException {
        if (target == null || target.isBlank() || target.startsWith("/") || target.contains("\\")) {
            throw corrupt("The DOCX package contains an unsafe relationship target.");
        }
        try {
            var uri = URI.create(target);
            if (uri.isAbsolute() || uri.getRawAuthority() != null) {
                throw corrupt("The DOCX package contains an unsafe relationship target.");
            }
            var sourcePart = sourcePart(relationshipsName);
            var base = sourcePart == null ? Path.of("") : Path.of(sourcePart).getParent();
            var resolved = (base == null ? Path.of(target) : base.resolve(target)).normalize();
            var first =
                    resolved.getNameCount() == 0 ? null : resolved.getName(0).toString();
            if (resolved.isAbsolute() || "..".equals(first)) {
                throw corrupt("The DOCX package contains an unsafe relationship target.");
            }
        } catch (IllegalArgumentException exception) {
            throw corrupt("The DOCX package contains an unsafe relationship target.", exception);
        }
    }

    private static String sourcePart(String relationshipsName) {
        if ("_rels/.rels".equals(relationshipsName)) {
            return null;
        }
        var marker = "/_rels/";
        var markerIndex = relationshipsName.lastIndexOf(marker);
        if (markerIndex < 0 || !relationshipsName.endsWith(".rels")) {
            return relationshipsName;
        }
        return relationshipsName.substring(0, markerIndex + 1)
                + relationshipsName.substring(markerIndex + marker.length(), relationshipsName.length() - 5);
    }

    private static String asciiLower(String value) {
        var result = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            result.append(character >= 'A' && character <= 'Z' ? (char) (character + 'a' - 'A') : character);
        }
        return result.toString();
    }

    private static void rejectCompoundPackage(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        try (var reader = BudgetedFileReader.open(file, budget)) {
            if (reader.size() < 8) {
                return;
            }
            var signature = reader.read(0, 8);
            var compound =
                    new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
                    };
            if (java.util.Arrays.equals(signature.array(), compound)) {
                throw new MetadataParsingException(
                        MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET,
                        "Encrypted compound Office packages are not supported.");
            }
        }
    }

    private static Document parseRequiredXml(ZipFile archive, String name, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var document = parseOptionalXml(archive, name, budget);
        if (document == null) {
            throw corrupt("The DOCX package structure is incomplete.");
        }
        return document;
    }

    private static Document parseOptionalXml(ZipFile archive, String name, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var entry = archive.getEntry(name);
        if (entry == null) {
            return null;
        }
        if (entry.isDirectory()) {
            throw corrupt("The DOCX package structure is invalid.");
        }
        return parseXml(archive, entry, budget);
    }

    private static Document parseXml(ZipFile archive, ZipEntry entry, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        validateSelectedEntry(entry, budget.limits().maximumXmlBytes());
        try (var entryLease = budget.openFile();
                var input = budget.count(archive.getInputStream(entry))) {
            return SecureXml.parse(input, budget);
        }
    }

    private static void validateSelectedEntry(ZipEntry entry, long maximumExpandedBytes)
            throws MetadataParsingException {
        var size = entry.getSize();
        var compressedSize = entry.getCompressedSize();
        if (size < 0 || compressedSize < 0) {
            throw corrupt("The DOCX package contains selected metadata with an unknown size.");
        }
        if (size > maximumExpandedBytes) {
            throw MetadataParsingException.limit("selected-entry-expansion", maximumExpandedBytes, "bytes");
        }
        if (size > MAXIMUM_COMPRESSION_RATIO * Math.max(1, compressedSize)) {
            throw MetadataParsingException.limit(
                    "selected-entry-compression-ratio", MAXIMUM_COMPRESSION_RATIO, "to-one");
        }
    }

    private static ParsedBookMetadata metadata(Document core, Document custom, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var observations = new LinkedHashMap<String, List<String>>();
        var title = coreValue(core, "title", "docx:core:title", observations, budget);
        var creator = coreValue(core, "creator", "docx:core:creator", observations, budget);
        var language = coreValue(core, "language", "docx:core:language", observations, budget);
        var identifier = coreValue(core, "identifier", "docx:core:identifier", observations, budget);
        coreValue(core, "subject", "docx:core:subject", observations, budget);
        coreValue(core, "description", "docx:core:description", observations, budget);
        coreValue(core, "keywords", "docx:core:keywords", observations, budget);
        coreValue(core, "category", "docx:core:category", observations, budget);
        coreValue(core, "lastModifiedBy", "docx:core:last-modified-by", observations, budget);
        customValues(custom, observations, budget);
        var identifiers = identifier == null ? Map.<String, String>of() : Map.of("docx-core", identifier);
        var contributors = creator == null ? List.<String>of() : List.of(creator);
        return new ParsedBookMetadata(
                ExtractedBookMetadata.Format.DOCX,
                title,
                contributors,
                language,
                identifiers,
                observations,
                "jdk-docx-opc",
                "1");
    }

    private static String coreValue(
            Document document,
            String localName,
            String observationName,
            Map<String, List<String>> observations,
            MetadataResourceBudget budget)
            throws MetadataParsingException {
        if (document == null) {
            return null;
        }
        var value = SecureXml.firstText(document, localName, budget);
        if (value != null) {
            observations.put(observationName, List.of(value));
        }
        return value;
    }

    private static void customValues(
            Document document, Map<String, List<String>> observations, MetadataResourceBudget budget)
            throws MetadataParsingException {
        if (document == null) {
            return;
        }
        var values = new ArrayList<String>();
        var properties = document.getElementsByTagNameNS("*", "property");
        for (var index = 0; index < properties.getLength(); index++) {
            if (!(properties.item(index) instanceof Element property)) {
                continue;
            }
            var name = SecureXml.normalizedSelected(property.getAttribute("name"), budget);
            var value = SecureXml.normalizedSelected(property.getTextContent(), budget);
            if (name != null && value != null) {
                var observation = name + "=" + value;
                budget.checkSelectedValueBytes(observation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                values.add(observation);
            }
        }
        if (!values.isEmpty()) {
            observations.put("docx:custom", List.copyOf(values));
        }
    }

    private static void validateName(String name) throws MetadataParsingException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw corrupt("The DOCX package contains an unsafe entry path.");
        }
        try {
            var path = Path.of(name);
            for (var element : path) {
                if (".".equals(element.toString()) || "..".equals(element.toString())) {
                    throw corrupt("The DOCX package contains an unsafe entry path.");
                }
            }
            if (path.isAbsolute()
                    || !path.normalize().toString().replace('\\', '/').equals(name)) {
                throw corrupt("The DOCX package contains an unsafe entry path.");
            }
        } catch (InvalidPathException exception) {
            throw corrupt("The DOCX package contains an unsafe entry path.", exception);
        }
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private static MetadataParsingException corrupt(String message, Throwable cause) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message, cause);
    }

    private static MetadataParsingException unsupported(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT, message);
    }
}

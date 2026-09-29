package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.w3c.dom.Element;
import org.yurlib.server.library.application.ExtractedBookMetadata;

final class Fb2MetadataParser implements MetadataParser {

    private static final int MAXIMUM_SOURCE_BYTES = 8 * 1024 * 1024;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.FB2;
    }

    @Override
    public long maximumSourceBytes() {
        return MAXIMUM_SOURCE_BYTES;
    }

    @Override
    public ParsedBookMetadata parse(Path file) throws IOException, MetadataParsingException {
        try (var input = Files.newInputStream(file)) {
            var document = SecureXml.parse(input, MAXIMUM_SOURCE_BYTES);
            return new ParsedBookMetadata(
                    format(),
                    SecureXml.firstText(document, "book-title"),
                    contributors(document),
                    SecureXml.firstText(document, "lang"),
                    identifiers(document),
                    "jdk-fb2",
                    "1");
        }
    }

    private static java.util.List<String> contributors(org.w3c.dom.Document document) {
        var values = new ArrayList<String>();
        var authors = document.getElementsByTagNameNS("*", "author");
        for (var index = 0; index < authors.getLength(); index++) {
            if (!(authors.item(index) instanceof Element author)) {
                continue;
            }
            var parts = new ArrayList<String>();
            addText(author, "first-name", parts);
            addText(author, "middle-name", parts);
            addText(author, "last-name", parts);
            addText(author, "nickname", parts);
            if (!parts.isEmpty()) {
                values.add(String.join(" ", parts));
            }
        }
        return java.util.List.copyOf(values);
    }

    private static void addText(Element parent, String localName, java.util.List<String> target) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) {
            var value = SecureXml.normalized(nodes.item(0).getTextContent());
            if (value != null) {
                target.add(value);
            }
        }
    }

    private static Map<String, String> identifiers(org.w3c.dom.Document document) {
        var values = new LinkedHashMap<String, String>();
        addIdentifier(document, "id", "fb2-document-id", values);
        addIdentifier(document, "isbn", "isbn", values);
        return Map.copyOf(values);
    }

    private static void addIdentifier(
            org.w3c.dom.Document document, String localName, String key, Map<String, String> target) {
        var nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) {
            var value = SecureXml.normalized(nodes.item(0).getTextContent());
            if (value != null) {
                target.put(key, value);
            }
        }
    }
}

package org.yurlib.server.library.infrastructure.metadata;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

@SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
final class Fb2MetadataParser implements MetadataParser {

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.FB2;
    }

    @Override
    @SuppressFBWarnings(
            value = "XXE_XMLSTREAMREADER",
            justification = "The StAX factory disables DTDs and external entities and installs a rejecting resolver.")
    public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        try (var fileLease = budget.openFile();
                var rawInput = Files.newInputStream(file);
                var boundedInput = budget.bound(rawInput, budget.limits().maximumXmlBytes(), "xml-metadata");
                var input = budget.count(boundedInput)) {
            XMLStreamReader reader = null;
            try {
                reader = xmlFactory().createXMLStreamReader(input);
                return readDescription(reader, budget);
            } catch (XMLStreamException exception) {
                var metadataFailure = MetadataParsingException.causedBy(exception);
                if (metadataFailure != null) {
                    throw metadataFailure;
                }
                throw corrupt("The FB2 file contains invalid or prohibited XML.", exception);
            } finally {
                close(reader);
            }
        }
    }

    private static ParsedBookMetadata readDescription(XMLStreamReader reader, MetadataResourceBudget budget)
            throws XMLStreamException, MetadataParsingException {
        var state = new DescriptionState();
        var path = new ArrayDeque<String>();
        SelectedText selected = null;
        while (reader.hasNext()) {
            var event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                var localName = reader.getLocalName();
                path.addLast(localName);
                budget.checkDepth(path.size());
                if (selected == null && selected(path, localName)) {
                    selected = new SelectedText(target(path, localName));
                }
                if ("author".equals(localName) && path.contains("title-info")) {
                    state.beginAuthor();
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (selected != null) {
                    selected.append(reader.getText(), budget);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                var localName = reader.getLocalName();
                if (selected != null && selected.target().elementName().equals(localName)) {
                    state.accept(selected.target(), selected.value(budget));
                    selected = null;
                }
                if ("author".equals(localName) && path.contains("title-info")) {
                    state.endAuthor();
                }
                if ("description".equals(localName)) {
                    return state.result();
                }
                path.removeLast();
            } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw corrupt("The FB2 file contains prohibited XML declarations.");
            }
            budget.checkpoint();
        }
        throw corrupt("The FB2 metadata description is missing.");
    }

    private static boolean selected(Deque<String> path, String localName) {
        return switch (localName) {
            case "book-title", "lang" -> path.contains("title-info");
            case "first-name", "middle-name", "last-name", "nickname" ->
                path.contains("title-info") && path.contains("author");
            case "id" -> path.contains("document-info");
            case "isbn" -> path.contains("publish-info");
            default -> false;
        };
    }

    private static Target target(Deque<String> path, String localName) {
        return switch (localName) {
            case "book-title" -> Target.TITLE;
            case "lang" -> Target.LANGUAGE;
            case "id" -> Target.DOCUMENT_ID;
            case "isbn" -> Target.ISBN;
            default -> path.contains("author") ? Target.AUTHOR_PART.withElementName(localName) : Target.IGNORED;
        };
    }

    private static XMLInputFactory xmlFactory() {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resolution is prohibited.");
        });
        return factory;
    }

    private static void close(XMLStreamReader reader) throws MetadataParsingException {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException exception) {
            throw corrupt("The FB2 parser could not close safely.", exception);
        }
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private static MetadataParsingException corrupt(String message, Throwable cause) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message, cause);
    }

    private static final class SelectedText {

        private final Target target;

        @SuppressWarnings("PMD.AvoidStringBufferField")
        private final StringBuilder value = new StringBuilder();

        private int utf8Bytes;

        private SelectedText(Target target) {
            this.target = target;
        }

        private void append(String text, MetadataResourceBudget budget) throws MetadataParsingException {
            try {
                utf8Bytes = Math.addExact(utf8Bytes, text.getBytes(StandardCharsets.UTF_8).length);
            } catch (ArithmeticException exception) {
                throw MetadataParsingException.limit(
                        "selected-value", budget.limits().maximumSelectedValueBytes(), "bytes", exception);
            }
            budget.checkSelectedValueBytes(utf8Bytes);
            value.append(text);
        }

        private Target target() {
            return target;
        }

        private String value(MetadataResourceBudget budget) throws MetadataParsingException {
            return SecureXml.normalizedSelected(value.toString(), budget);
        }
    }

    private static final class DescriptionState {

        private final List<String> contributors = new ArrayList<>();
        private final Map<String, String> identifiers = new LinkedHashMap<>();
        private List<String> authorParts;
        private String title;
        private String language;

        private void beginAuthor() {
            authorParts = new ArrayList<>();
        }

        private void accept(Target target, String value) {
            if (value == null) {
                return;
            }
            switch (target.kind()) {
                case TITLE -> title = value;
                case LANGUAGE -> language = value;
                case DOCUMENT_ID -> identifiers.putIfAbsent("fb2-document-id", value);
                case ISBN -> identifiers.putIfAbsent("isbn", value);
                case AUTHOR_PART -> {
                    if (authorParts != null) {
                        authorParts.add(value);
                    }
                }
                case IGNORED -> {
                    // No action.
                }
            }
        }

        private void endAuthor() {
            if (authorParts != null && !authorParts.isEmpty()) {
                contributors.add(String.join(" ", authorParts));
            }
            authorParts = null;
        }

        private ParsedBookMetadata result() {
            return new ParsedBookMetadata(
                    ExtractedBookMetadata.Format.FB2,
                    title,
                    List.copyOf(contributors),
                    language,
                    Map.copyOf(identifiers),
                    "jdk-fb2-stax",
                    "2");
        }
    }

    private enum TargetKind {
        TITLE,
        LANGUAGE,
        DOCUMENT_ID,
        ISBN,
        AUTHOR_PART,
        IGNORED
    }

    private record Target(TargetKind kind, String elementName) {

        private static final Target TITLE = new Target(TargetKind.TITLE, "book-title");
        private static final Target LANGUAGE = new Target(TargetKind.LANGUAGE, "lang");
        private static final Target DOCUMENT_ID = new Target(TargetKind.DOCUMENT_ID, "id");
        private static final Target ISBN = new Target(TargetKind.ISBN, "isbn");
        private static final Target AUTHOR_PART = new Target(TargetKind.AUTHOR_PART, "author-part");
        private static final Target IGNORED = new Target(TargetKind.IGNORED, "ignored");

        private Target withElementName(String name) {
            return new Target(kind, name);
        }
    }
}

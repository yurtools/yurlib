package org.yurlib.server.library.infrastructure.metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class SecureXml {

    private SecureXml() {}

    static Document parse(InputStream input, int maximumBytes) throws IOException, MetadataParsingException {
        var bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED,
                    "An XML metadata document exceeds the configured parsing limit.");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(THROWING_ERROR_HANDLER);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("The Java runtime does not support secure XML parsing.", exception);
        } catch (SAXException exception) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.CORRUPT_ASSET,
                    "The book contains invalid or prohibited XML.",
                    exception);
        }
    }

    static String firstText(Document document, String localName) {
        var nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return normalized(nodes.item(0).getTextContent());
    }

    static String normalized(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static final ErrorHandler THROWING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };
}

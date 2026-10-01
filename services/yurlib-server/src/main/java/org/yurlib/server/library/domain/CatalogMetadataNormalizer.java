package org.yurlib.server.library.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@SuppressFBWarnings(
        value = "IMPROPER_UNICODE",
        justification =
                "Catalog rules intentionally preserve Unicode text and use locale-neutral case mapping only for typed identifiers and roles.")
public final class CatalogMetadataNormalizer {

    public static final String NAME = "yurlib-catalog-normalizer";
    public static final String VERSION = "1";
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$");
    private static final Pattern DOI = Pattern.compile("^10\\.\\d{4,9}/\\S+$", Pattern.CASE_INSENSITIVE);

    private CatalogMetadataNormalizer() {}

    public static Result normalize(String fieldName, String observedValue) {
        var value = collapseWhitespace(observedValue);
        if (value.isEmpty()) {
            return Result.invalid("The observed value is empty after whitespace normalization.", "TEXT");
        }
        if ("title".equals(fieldName)) {
            return Result.present(value, "TITLE", NormalizedMetadataFact.Confidence.EXACT);
        }
        if (fieldName.startsWith("contributor")) {
            return Result.present(value, contributorType(fieldName), NormalizedMetadataFact.Confidence.HIGH);
        }
        if ("language".equals(fieldName)) {
            return normalizeLanguage(value);
        }
        if (fieldName.startsWith("identifier:")) {
            return normalizeIdentifier(fieldName.substring("identifier:".length()), value);
        }
        return Result.present(value, "TEXT", NormalizedMetadataFact.Confidence.UNKNOWN);
    }

    private static Result normalizeLanguage(String value) {
        if (!LANGUAGE_TAG.matcher(value).matches()) {
            return Result.invalid("The language is not an unambiguous BCP 47 language tag.", "BCP47");
        }
        var normalized = Locale.forLanguageTag(value).toLanguageTag();
        if (normalized.isBlank() || "und".equals(normalized)) {
            return Result.invalid("The language is not an unambiguous BCP 47 language tag.", "BCP47");
        }
        return Result.present(normalized, "BCP47", NormalizedMetadataFact.Confidence.EXACT);
    }

    private static Result normalizeIdentifier(String scheme, String value) {
        var normalizedScheme = scheme.strip().toLowerCase(Locale.ROOT);
        return switch (normalizedScheme) {
            case "isbn", "isbn10", "isbn-10" -> normalizeIsbn(value, 10);
            case "isbn13", "isbn-13" -> normalizeIsbn(value, 13);
            case "doi" ->
                DOI.matcher(value).matches()
                        ? Result.present(value.toLowerCase(Locale.ROOT), "DOI", NormalizedMetadataFact.Confidence.EXACT)
                        : Result.invalid("The DOI is invalid.", "DOI");
            case "uuid" -> normalizeUuid(value);
            case "uri", "url" -> normalizeUri(value);
            case "source", "source-local", "source_local" ->
                Result.present(value, "SOURCE_LOCAL", NormalizedMetadataFact.Confidence.HIGH);
            default -> Result.present(value, "OTHER", NormalizedMetadataFact.Confidence.UNKNOWN);
        };
    }

    private static Result normalizeIsbn(String value, int length) {
        var compact = value.replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
        var validShape = length == 10 ? compact.matches("\\d{9}[\\dX]") : compact.matches("\\d{13}");
        if (!validShape || !validIsbnChecksum(compact)) {
            return Result.invalid("The ISBN-" + length + " value or checksum is invalid.", "ISBN_" + length);
        }
        return Result.present(compact, "ISBN_" + length, NormalizedMetadataFact.Confidence.EXACT);
    }

    private static boolean validIsbnChecksum(String value) {
        if (value.length() == 10) {
            var sum = 0;
            for (var index = 0; index < 10; index++) {
                var digit = value.charAt(index) == 'X' ? 10 : value.charAt(index) - '0';
                sum += digit * (10 - index);
            }
            return sum % 11 == 0;
        }
        var sum = 0;
        for (var index = 0; index < 13; index++) {
            sum += (value.charAt(index) - '0') * (index % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    private static Result normalizeUuid(String value) {
        try {
            return Result.present(UUID.fromString(value).toString(), "UUID", NormalizedMetadataFact.Confidence.EXACT);
        } catch (IllegalArgumentException failure) {
            return Result.invalid("The UUID is invalid.", "UUID");
        }
    }

    private static Result normalizeUri(String value) {
        try {
            var uri = new URI(value).normalize();
            if (!uri.isAbsolute()) {
                return Result.invalid("The URI must be absolute.", "URI");
            }
            return Result.present(uri.toASCIIString(), "URI", NormalizedMetadataFact.Confidence.EXACT);
        } catch (URISyntaxException failure) {
            return Result.invalid("The URI is invalid.", "URI");
        }
    }

    private static String contributorType(String fieldName) {
        var separator = fieldName.indexOf(':');
        if (separator < 0) {
            return "CONTRIBUTOR:AUTHOR";
        }
        var role = fieldName.substring(separator + 1).toUpperCase(Locale.ROOT);
        return switch (role) {
            case "AUTHOR", "EDITOR", "TRANSLATOR", "ILLUSTRATOR" -> "CONTRIBUTOR:" + role;
            default -> "CONTRIBUTOR:OTHER";
        };
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.strip().replaceAll("(?U)\\s+", " ");
    }

    public record Result(
            boolean valid,
            String normalizedValue,
            String valueType,
            NormalizedMetadataFact.Confidence confidence,
            String problem) {

        private static Result present(String value, String valueType, NormalizedMetadataFact.Confidence confidence) {
            return new Result(true, value, valueType, confidence, null);
        }

        private static Result invalid(String problem, String valueType) {
            return new Result(false, null, valueType, NormalizedMetadataFact.Confidence.UNKNOWN, problem);
        }
    }
}

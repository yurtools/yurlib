package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.yurlib.server.library.domain.CatalogMetadataNormalizer;

class CatalogMetadataNormalizerTest {

    @Test
    void preservesUnicodeWhileCollapsingTitleAndContributorWhitespace() {
        assertThat(CatalogMetadataNormalizer.normalize("title", "  Война\n\tи мир ")
                        .normalizedValue())
                .isEqualTo("Война и мир");
        assertThat(CatalogMetadataNormalizer.normalize("contributor:editor", "  Анна   Иванова "))
                .satisfies(result -> {
                    assertThat(result.normalizedValue()).isEqualTo("Анна Иванова");
                    assertThat(result.valueType()).isEqualTo("CONTRIBUTOR:EDITOR");
                });
    }

    @Test
    void canonicalizesBcp47LanguagesAndTypedIdentifiers() {
        assertThat(CatalogMetadataNormalizer.normalize("language", "zh-hant-tw").normalizedValue())
                .isEqualTo("zh-Hant-TW");
        assertThat(CatalogMetadataNormalizer.normalize("identifier:isbn", "0-306-40615-2"))
                .satisfies(result -> {
                    assertThat(result.valid()).isTrue();
                    assertThat(result.normalizedValue()).isEqualTo("0306406152");
                    assertThat(result.valueType()).isEqualTo("ISBN_10");
                });
        assertThat(CatalogMetadataNormalizer.normalize("identifier:doi", "10.1000/ABC"))
                .extracting(CatalogMetadataNormalizer.Result::normalizedValue)
                .isEqualTo("10.1000/abc");
    }

    @Test
    void rejectsAmbiguousLanguagesAndInvalidChecksums() {
        assertThat(CatalogMetadataNormalizer.normalize("language", "English").valid())
                .isFalse();
        assertThat(CatalogMetadataNormalizer.normalize("identifier:isbn13", "9780000000000")
                        .valid())
                .isFalse();
    }
}

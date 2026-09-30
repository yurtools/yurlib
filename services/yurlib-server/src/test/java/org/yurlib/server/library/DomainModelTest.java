package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.AssetDerivationSource;
import org.yurlib.server.library.domain.CuratedMetadataOverride;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.domain.MetadataObservation;
import org.yurlib.server.library.domain.MetadataValueState;
import org.yurlib.server.library.domain.NormalizedMetadataFact;
import org.yurlib.server.library.domain.ResolvedMetadataValue;
import org.yurlib.server.library.domain.ScanJob;

class DomainModelTest {

    @Test
    void representsAllM2AssetFormatsAndDerivedAssets() {
        assertThat(Asset.Format.values())
                .containsExactly(
                        Asset.Format.EPUB,
                        Asset.Format.FB2,
                        Asset.Format.MOBI,
                        Asset.Format.PDF,
                        Asset.Format.DOCX,
                        Asset.Format.DJVU);
        var sourceId = UUID.randomUUID();
        var derivedId = UUID.randomUUID();
        var derived = new Asset(
                derivedId,
                UUID.randomUUID(),
                Asset.Format.EPUB,
                100,
                Asset.Derivation.DERIVED,
                "a".repeat(64),
                "converter-v1",
                0);

        assertThat(derived.derivation()).isEqualTo(Asset.Derivation.DERIVED);
        assertThat(new AssetDerivationSource(derivedId, sourceId, 0, "FORMAT_CONVERSION").sourceAssetId())
                .isEqualTo(sourceId);
    }

    @Test
    void enforcesMetadataStateInvariants() {
        var observationId = UUID.randomUUID();
        var subjectId = UUID.randomUUID();
        var fact = new NormalizedMetadataFact(
                UUID.randomUUID(),
                observationId,
                MetadataObservation.SubjectType.WORK,
                subjectId,
                "title",
                MetadataValueState.PRESENT,
                "Normalized title",
                "TEXT",
                "title-normalizer",
                "1",
                NormalizedMetadataFact.Confidence.HIGH,
                null,
                Instant.now());
        var resolved = new ResolvedMetadataValue(
                UUID.randomUUID(),
                MetadataObservation.SubjectType.WORK,
                subjectId,
                "title",
                MetadataValueState.PRESENT,
                "Normalized title",
                fact.id(),
                List.of(),
                "title-resolver",
                "1",
                1,
                null,
                true,
                Instant.now());
        var curated = new CuratedMetadataOverride(
                UUID.randomUUID(),
                MetadataObservation.SubjectType.WORK,
                subjectId,
                "title",
                MetadataValueState.ABSENT,
                null,
                UUID.randomUUID(),
                "Remove incorrect title",
                1,
                null,
                null,
                true,
                Instant.now());

        assertThat(resolved.selectedFactId()).isEqualTo(fact.id());
        assertThat(curated.valueState()).isEqualTo(MetadataValueState.ABSENT);
        assertThatThrownBy(() -> new NormalizedMetadataFact(
                        UUID.randomUUID(),
                        observationId,
                        MetadataObservation.SubjectType.WORK,
                        subjectId,
                        "title",
                        MetadataValueState.CONFLICT,
                        null,
                        "TEXT",
                        "title-normalizer",
                        "1",
                        NormalizedMetadataFact.Confidence.UNKNOWN,
                        null,
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot represent conflicts");
    }

    @Test
    void rejectsAnInvalidIdentityDigest() {
        assertThatThrownBy(() -> new LibraryRoot(
                        UUID.randomUUID(),
                        "Main library",
                        "library-main",
                        "books",
                        "not-a-digest",
                        LibraryRoot.Mode.READ_ONLY_SOURCE,
                        LibraryRoot.Availability.UNKNOWN,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void rejectsScanCountersThatExceedDiscovery() {
        assertThatThrownBy(() -> new ScanJob(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ScanJob.State.RUNNING,
                        "correlation-id",
                        "extractor-v1",
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        1,
                        1,
                        1,
                        0,
                        false,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void rejectsNonNormalizedRelativePaths() {
        assertThatThrownBy(() -> new FileOutcome(
                        UUID.randomUUID(),
                        "books/../outside.fb2",
                        FileOutcome.State.DISCOVERED,
                        null,
                        null,
                        1,
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized relative path");
    }
}

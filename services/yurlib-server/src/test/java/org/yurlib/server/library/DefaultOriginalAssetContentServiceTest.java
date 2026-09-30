package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.AssetContentLocation;
import org.yurlib.server.library.application.AssetContentStore;
import org.yurlib.server.library.application.AssetFileOpener;
import org.yurlib.server.library.application.DefaultOriginalAssetContentService;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.OpenedAssetContent;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.AssetLocation;
import org.yurlib.server.library.domain.LibraryRoot;

class DefaultOriginalAssetContentServiceTest {

    private static final UUID ASSET_ID = UUID.fromString("9d889ec8-9ae5-49db-8d75-79d2969dd202");
    private static final UUID ROOT_ID = UUID.fromString("238fe729-8360-440e-94a7-012a23ac49f3");

    private final AssetContentStore assets = mock(AssetContentStore.class);
    private final LibraryRootStore roots = mock(LibraryRootStore.class);
    private final AssetFileOpener opener = mock(AssetFileOpener.class);
    private final DefaultOriginalAssetContentService service =
            new DefaultOriginalAssetContentService(assets, roots, opener);

    @Test
    void delegatesAnAvailableAssetToTheVerifiedFileOpener() {
        var location = location(AssetLocation.Availability.AVAILABLE);
        var root = root();
        var expected = new OpenedAssetContent(
                ASSET_ID, Asset.Format.EPUB, 42, "book.epub", new ByteArrayInputStream(new byte[] {1, 2}));
        when(assets.findByAssetId(ASSET_ID)).thenReturn(Optional.of(location));
        when(roots.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(opener.open(root, location)).thenReturn(expected);

        assertThat(service.open(ASSET_ID)).isSameAs(expected);
    }

    @Test
    void distinguishesUnknownAssetsFromUnavailableLocations() {
        when(assets.findByAssetId(ASSET_ID)).thenReturn(Optional.empty());

        assertFailure(AssetContentFailure.Code.ASSET_NOT_FOUND);

        when(assets.findByAssetId(ASSET_ID)).thenReturn(Optional.of(location(AssetLocation.Availability.MISSING)));

        assertFailure(AssetContentFailure.Code.ASSET_UNAVAILABLE);
        verify(roots, never()).findById(ROOT_ID);
    }

    @Test
    void treatsAMissingOwningRootAsUnavailable() {
        when(assets.findByAssetId(ASSET_ID)).thenReturn(Optional.of(location(AssetLocation.Availability.AVAILABLE)));
        when(roots.findById(ROOT_ID)).thenReturn(Optional.empty());

        assertFailure(AssetContentFailure.Code.ASSET_UNAVAILABLE);
        verify(opener, never()).open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void assertFailure(AssetContentFailure.Code code) {
        assertThatThrownBy(() -> service.open(ASSET_ID))
                .isInstanceOfSatisfying(
                        AssetContentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static AssetContentLocation location(AssetLocation.Availability availability) {
        return new AssetContentLocation(
                ASSET_ID,
                ROOT_ID,
                "fiction/book.epub",
                Asset.Format.EPUB,
                42,
                Instant.parse("2026-09-29T12:00:00Z"),
                "file-key",
                availability);
    }

    private static LibraryRoot root() {
        return new LibraryRoot(
                ROOT_ID,
                "Main library",
                "main",
                "books",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null);
    }
}

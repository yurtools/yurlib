package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yurlib.server.library.application.ConfigureLibraryRootCommand;
import org.yurlib.server.library.application.ConfigureLibraryRootService;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.RootLocationVerifier;
import org.yurlib.server.library.domain.LibraryRoot;

class ConfigureLibraryRootServiceTest {

    private final LibraryRootStore store = mock(LibraryRootStore.class);
    private final RootLocationVerifier verifier = mock(RootLocationVerifier.class);
    private final ConfigureLibraryRootService service = new ConfigureLibraryRootService(store, verifier);

    @Test
    void persistsAReadOnlyAvailableRootWithOnlyTheIdentityDigest() {
        var command = new ConfigureLibraryRootCommand(
                "Main library", "main", "unicode/日本語", "private-token-1234");
        when(verifier.verify("main", "unicode/日本語", "private-token-1234"))
                .thenReturn(new RootLocationVerifier.VerifiedRootLocation("unicode/日本語", "a".repeat(64)));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.configure(command);

        var saved = ArgumentCaptor.forClass(LibraryRoot.class);
        verify(store).save(saved.capture());
        assertThat(result).isEqualTo(saved.getValue());
        assertThat(saved.getValue().expectedIdentityDigest()).isEqualTo("a".repeat(64));
        assertThat(saved.getValue().mode()).isEqualTo(LibraryRoot.Mode.READ_ONLY);
        assertThat(saved.getValue().availability()).isEqualTo(LibraryRoot.Availability.AVAILABLE);
    }

    @Test
    void rejectsASecondRootBeforeAccessingItsFilesystem() {
        when(store.hasAny()).thenReturn(true);

        assertThatThrownBy(() -> service.configure(new ConfigureLibraryRootCommand(
                "Second library", "second", "", "private-token-1234")))
                .isInstanceOfSatisfying(LibraryRootFailure.class, failure ->
                        assertThat(failure.code()).isEqualTo(LibraryRootFailure.Code.ROOT_ALREADY_CONFIGURED));
        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    void listsPersistedRootsWithoutFilesystemAccess() {
        when(store.findAll()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
        verify(verifier, never()).verify(any(), any(), any());
    }
}

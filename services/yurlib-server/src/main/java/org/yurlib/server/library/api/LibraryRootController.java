package org.yurlib.server.library.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.ConfigureLibraryRootCommand;
import org.yurlib.server.library.application.LibraryRootUseCases;

@RestController
@RequestMapping("/api/v1/library-roots")
public class LibraryRootController {

    private final LibraryRootUseCases useCases;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring owns the injected application service for the controller lifetime.")
    public LibraryRootController(LibraryRootUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public List<LibraryRootResponse> list() {
        return useCases.list().stream().map(LibraryRootResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryRootResponse create(@Valid @RequestBody CreateLibraryRootRequest request) {
        var command = new ConfigureLibraryRootCommand(
                request.name().strip(),
                request.mountAlias(),
                request.relativePath(),
                request.identityToken(),
                request.mode(),
                Boolean.TRUE.equals(request.defaultForCovers()),
                Boolean.TRUE.equals(request.defaultForConversions()));
        return LibraryRootResponse.from(useCases.configure(command));
    }

    @DeleteMapping("/{rootId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID rootId) {
        useCases.remove(rootId);
    }
}

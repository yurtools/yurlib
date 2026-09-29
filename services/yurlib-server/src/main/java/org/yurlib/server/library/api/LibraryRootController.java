package org.yurlib.server.library.api;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
                request.name().strip(), request.mountAlias(), request.relativePath(), request.identityToken());
        return LibraryRootResponse.from(useCases.configure(command));
    }
}

package org.yurlib.server.library.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.AllowedMountQuery;

@RestController
@RequestMapping("/api/v1/library-mounts")
public class LibraryMountController {

    private final AllowedMountQuery mounts;

    public LibraryMountController(AllowedMountQuery mounts) {
        this.mounts = mounts;
    }

    @GetMapping
    public List<LibraryMountResponse> list() {
        return mounts.aliases().stream().map(LibraryMountResponse::new).toList();
    }
}

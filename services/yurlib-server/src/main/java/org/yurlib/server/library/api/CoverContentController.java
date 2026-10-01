package org.yurlib.server.library.api;

import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.CoverContentUseCases;

@RestController
@RequestMapping("/api/v1/catalog/works")
public class CoverContentController {

    private final CoverContentUseCases covers;

    public CoverContentController(CoverContentUseCases covers) {
        this.covers = covers;
    }

    @GetMapping("/{workId}/cover")
    public ResponseEntity<InputStreamResource> cover(@PathVariable UUID workId) {
        var opened = covers.open(workId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(opened.mediaType()))
                .contentLength(opened.contentLength())
                .eTag(opened.etag())
                .cacheControl(
                        CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(opened.inputStream()));
    }
}

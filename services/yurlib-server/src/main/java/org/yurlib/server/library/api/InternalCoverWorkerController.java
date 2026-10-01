package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.yurlib.server.library.application.CoverWorkerClaim;
import org.yurlib.server.library.application.CoverWorkerJobService;
import org.yurlib.server.library.application.CoverWorkerResult;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/v1/covers/jobs")
public class InternalCoverWorkerController {

    private static final int MAXIMUM_RESULT_BYTES = 23 * 1024 * 1024;
    private final CoverWorkerJobService jobs;
    private final ObjectMapper objectMapper;

    public InternalCoverWorkerController(CoverWorkerJobService jobs, ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @PostMapping("/claim")
    public ResponseEntity<CoverWorkerClaim> claim() {
        return jobs.claim()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{jobId}/input")
    public ResponseEntity<FileSystemResource> input(
            @PathVariable UUID jobId, @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken) throws IOException {
        try {
            var resource = new FileSystemResource(jobs.leasedInput(jobId, leaseToken));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"input.bin\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(resource.contentLength())
                    .body(resource);
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The cover worker lease is invalid.", failure);
        }
    }

    @PostMapping("/{jobId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @PathVariable UUID jobId,
            @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken,
            HttpServletRequest request)
            throws IOException {
        var bytes = request.getInputStream().readNBytes(MAXIMUM_RESULT_BYTES + 1);
        if (bytes.length > MAXIMUM_RESULT_BYTES) {
            throw new ResponseStatusException(HttpStatus.valueOf(413), "The cover worker result is too large.");
        }
        try {
            jobs.complete(jobId, leaseToken, objectMapper.readValue(bytes, CoverWorkerResult.class));
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The cover worker result was rejected.", failure);
        }
    }
}

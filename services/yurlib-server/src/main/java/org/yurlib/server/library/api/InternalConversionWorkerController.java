package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.yurlib.server.library.application.ConversionWorkerClaim;
import org.yurlib.server.library.application.ConversionWorkerJobService;
import org.yurlib.server.library.application.ConversionWorkerResult;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/v1/conversions/jobs")
public class InternalConversionWorkerController {

    private static final long MAXIMUM_OUTPUT_BYTES = 1024L * 1024 * 1024;
    private static final int MAXIMUM_RESULT_BYTES = 4 * 1024;
    private final ConversionWorkerJobService jobs;
    private final ObjectMapper objectMapper;

    public InternalConversionWorkerController(ConversionWorkerJobService jobs, ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @PostMapping("/claim")
    ResponseEntity<ConversionWorkerClaim> claim() {
        return jobs.claim()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{jobId}/heartbeat")
    Map<String, Boolean> heartbeat(@PathVariable UUID jobId, @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken) {
        try {
            return Map.of("cancellationRequested", jobs.heartbeat(jobId, leaseToken));
        } catch (IllegalArgumentException failure) {
            throw leaseConflict(failure);
        }
    }

    @GetMapping("/{jobId}/input")
    ResponseEntity<FileSystemResource> input(
            @PathVariable UUID jobId, @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken) throws IOException {
        try {
            var resource = new FileSystemResource(jobs.leasedInput(jobId, leaseToken));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"input.bin\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(resource.contentLength())
                    .body(resource);
        } catch (IllegalArgumentException failure) {
            throw leaseConflict(failure);
        }
    }

    @PutMapping("/{jobId}/output")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(
            @PathVariable UUID jobId,
            @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken,
            @RequestHeader(HttpHeaders.CONTENT_LENGTH) long byteSize,
            @RequestHeader("X-Yurlib-Content-SHA256") String sha256,
            HttpServletRequest request) {
        if (byteSize <= 0 || byteSize > MAXIMUM_OUTPUT_BYTES) {
            throw new ResponseStatusException(HttpStatus.valueOf(413), "The conversion output is too large.");
        }
        try {
            jobs.upload(jobId, leaseToken, byteSize, sha256, request.getInputStream());
        } catch (IOException failure) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The conversion output could not be read.", failure);
        } catch (IllegalArgumentException failure) {
            throw leaseConflict(failure);
        }
    }

    @PostMapping("/{jobId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(
            @PathVariable UUID jobId,
            @RequestHeader("X-Yurlib-Lease-Token") UUID leaseToken,
            HttpServletRequest request)
            throws IOException {
        var bytes = request.getInputStream().readNBytes(MAXIMUM_RESULT_BYTES + 1);
        if (bytes.length > MAXIMUM_RESULT_BYTES) {
            throw new ResponseStatusException(HttpStatus.valueOf(413), "The conversion result is too large.");
        }
        try {
            jobs.complete(jobId, leaseToken, objectMapper.readValue(bytes, ConversionWorkerResult.class));
        } catch (IllegalArgumentException failure) {
            throw leaseConflict(failure);
        }
    }

    private static ResponseStatusException leaseConflict(IllegalArgumentException failure) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The conversion worker request was rejected.", failure);
    }
}

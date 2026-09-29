package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.ScanJobUseCases;

@RestController
@RequestMapping("/api/v1")
public class ScanJobController {

    private final ScanJobUseCases useCases;

    public ScanJobController(ScanJobUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping("/library-roots/{rootId}/scans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanJobResponse start(@PathVariable UUID rootId, HttpServletRequest request) {
        var correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return ScanJobResponse.queued(useCases.queue(rootId, correlationId));
    }

    @GetMapping("/jobs/{jobId}")
    public ScanJobResponse get(@PathVariable UUID jobId) {
        return ScanJobResponse.from(useCases.get(jobId));
    }
}

package org.yurlib.server.library.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.ConversionJob;
import org.yurlib.server.library.application.ConversionRoute;
import org.yurlib.server.library.application.ConversionUseCases;

@RestController
@RequestMapping("/api/v1")
public class ConversionController {

    private final ConversionUseCases conversions;

    public ConversionController(ConversionUseCases conversions) {
        this.conversions = conversions;
    }

    @PostMapping("/assets/{assetId}/conversions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ConversionJob request(@PathVariable UUID assetId, @Valid @RequestBody ConversionRequest request) {
        return conversions.request(assetId, request.route());
    }

    @GetMapping("/conversions/{jobId}")
    ConversionJob find(@PathVariable UUID jobId) {
        return conversions.find(jobId);
    }

    @PostMapping("/conversions/{jobId}/cancel")
    ConversionJob cancel(@PathVariable UUID jobId, @Valid @RequestBody CancelConversionRequest request) {
        return conversions.cancel(jobId, request.expectedVersion());
    }

    record ConversionRequest(@NotNull ConversionRoute route) {}

    record CancelConversionRequest(@PositiveOrZero long expectedVersion) {}
}

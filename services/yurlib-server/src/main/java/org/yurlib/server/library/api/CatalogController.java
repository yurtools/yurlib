package org.yurlib.server.library.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.CatalogQuery;

@RestController
@Validated
@RequestMapping("/api/v1/catalog/works")
public class CatalogController {

    private final CatalogQuery catalog;

    public CatalogController(CatalogQuery catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public CatalogPageResponse list(
            @RequestParam(required = false) @Size(max = 200) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return CatalogPageResponse.from(catalog.search(query, page, size));
    }
}

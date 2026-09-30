package org.yurlib.server.library.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.OpenedAssetContent;
import org.yurlib.server.library.application.OriginalAssetContentUseCases;
import org.yurlib.server.library.domain.Asset;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetContentController {

    private static final int MAXIMUM_FILENAME_LENGTH = 180;

    private final OriginalAssetContentUseCases content;

    public AssetContentController(OriginalAssetContentUseCases content) {
        this.content = content;
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID assetId) {
        var opened = content.open(assetId);
        var disposition = ContentDisposition.attachment()
                .filename(safeFilename(opened), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType(opened.format()))
                .contentLength(opened.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(opened.content()));
    }

    private static MediaType mediaType(Asset.Format format) {
        return switch (format) {
            case EPUB -> MediaType.parseMediaType("application/epub+zip");
            case FB2 -> MediaType.APPLICATION_XML;
            case MOBI -> MediaType.parseMediaType("application/x-mobipocket-ebook");
            case PDF -> MediaType.APPLICATION_PDF;
            case DOCX ->
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case DJVU -> MediaType.parseMediaType("image/vnd.djvu");
        };
    }

    private static String safeFilename(OpenedAssetContent opened) {
        var source = opened.sourceFilename();
        var sanitized = new StringBuilder(Math.min(source.length(), MAXIMUM_FILENAME_LENGTH));
        source.codePoints()
                .filter(codePoint -> codePoint >= 0x20 && codePoint != 0x7f)
                .limit(MAXIMUM_FILENAME_LENGTH)
                .forEach(codePoint -> sanitized.appendCodePoint(isHeaderSeparator(codePoint) ? '_' : codePoint));
        if (sanitized.isEmpty() || ".".contentEquals(sanitized) || "..".contentEquals(sanitized)) {
            return "asset-" + opened.assetId() + extension(opened.format());
        }
        return sanitized.toString();
    }

    private static boolean isHeaderSeparator(int codePoint) {
        return codePoint == '/' || codePoint == '\\' || codePoint == '"' || codePoint == ';' || codePoint == ':';
    }

    private static String extension(Asset.Format format) {
        return "." + format.name().toLowerCase(java.util.Locale.ROOT);
    }
}

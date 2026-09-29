package org.yurlib.server.library.application;

import java.nio.file.Path;

public interface MetadataExtractor {

    String extractionVersion();

    MetadataExtractionResult extract(Path containedFile);
}

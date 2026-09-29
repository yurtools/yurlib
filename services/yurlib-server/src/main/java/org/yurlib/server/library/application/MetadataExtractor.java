package org.yurlib.server.library.application;

import java.nio.file.Path;

public interface MetadataExtractor {

    MetadataExtractionResult extract(Path containedFile);
}

package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.Path;
import org.yurlib.server.library.application.ExtractedBookMetadata;

interface MetadataParser {

    ExtractedBookMetadata.Format format();

    ParsedBookMetadata parse(Path file, MetadataResourceBudget budget) throws IOException, MetadataParsingException;
}

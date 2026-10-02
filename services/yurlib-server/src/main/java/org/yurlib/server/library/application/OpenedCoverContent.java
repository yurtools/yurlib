package org.yurlib.server.library.application;

import java.io.InputStream;

public record OpenedCoverContent(InputStream inputStream, long contentLength, String mediaType, String etag) {}

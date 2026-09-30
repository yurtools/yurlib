package org.yurlib.worker;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PdfJobProcess {

    private PdfJobProcess() {}

    public static void main(String[] arguments) throws java.io.IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected input and result paths.");
        }
        var input = Path.of(arguments[0]).toAbsolutePath().normalize();
        var result = Path.of(arguments[1]).toAbsolutePath().normalize();
        var inputParent = java.util.Objects.requireNonNull(input.getParent(), "The input must have a parent path.");
        if (!inputParent.equals(result.getParent()) || !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Job paths are invalid.");
        }
        WorkerJson.MAPPER.writeValue(result.toFile(), new PdfMetadataExtractor().extract(input));
    }
}

package org.yurlib.worker;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CoverJobProcess {

    private CoverJobProcess() {}

    public static void main(String[] arguments) throws java.io.IOException {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected input, result, and format arguments.");
        }
        var input = Path.of(arguments[0]).toAbsolutePath().normalize();
        var result = Path.of(arguments[1]).toAbsolutePath().normalize();
        var inputParent = java.util.Objects.requireNonNull(input.getParent(), "The input must have a parent path.");
        if (!inputParent.equals(result.getParent()) || !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Cover job paths are invalid.");
        }
        var format = CoverClaim.Format.valueOf(arguments[2]);
        WorkerJson.MAPPER.writeValue(result.toFile(), new CoverExtractor().extract(input, format));
    }
}

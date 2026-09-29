package org.yurlib.server.library.domain;

import java.util.Objects;

final class DomainAssertions {

    private DomainAssertions() {}

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    static String notBlank(String value, String name) {
        required(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static String normalizedRelativePath(String value, String name, boolean emptyAllowed) {
        required(value, name);
        if ((!emptyAllowed && value.isEmpty())
                || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("\\")
                || value.contains("//")) {
            throw new IllegalArgumentException(name + " must be a normalized relative path");
        }
        for (var segment : value.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " must be a normalized relative path");
            }
        }
        return value;
    }
}

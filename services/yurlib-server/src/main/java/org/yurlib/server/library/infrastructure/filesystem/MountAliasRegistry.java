package org.yurlib.server.library.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.infrastructure.config.LibraryStorageProperties;

public final class MountAliasRegistry {

    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final Map<String, Path> prefixes;

    public MountAliasRegistry(Iterable<LibraryStorageProperties.Mount> configuredMounts) {
        var resolved = new LinkedHashMap<String, Path>();
        for (var mount : configuredMounts) {
            validateAlias(mount.alias());
            if (resolved.containsKey(mount.alias())) {
                throw new IllegalStateException("Duplicate library mount alias: " + mount.alias());
            }
            resolved.put(mount.alias(), canonicalDirectory(mount));
        }
        rejectOverlaps(resolved);
        prefixes = Map.copyOf(resolved);
    }

    public ResolvedRoot resolve(String alias, String relativePath) {
        var prefix = prefixes.get(alias);
        if (prefix == null) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_NOT_ALLOWED,
                    "The requested library mount alias is not allowed.");
        }
        var normalized = normalizeRelativePath(relativePath);
        try {
            var candidate = prefix.resolve(normalized).toRealPath();
            if (!candidate.startsWith(prefix)) {
                throw pathEscape();
            }
            if (!Files.isDirectory(candidate)) {
                throw unavailable();
            }
            return new ResolvedRoot(normalized, candidate);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private static Path canonicalDirectory(LibraryStorageProperties.Mount mount) {
        if (mount.path() == null) {
            throw new IllegalStateException("Library mount path is required for alias: " + mount.alias());
        }
        try {
            var path = mount.path().toRealPath();
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException("Library mount must be a directory for alias: " + mount.alias());
            }
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("Library mount is unavailable for alias: " + mount.alias());
        }
    }

    private static void validateAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new IllegalStateException("Invalid library mount alias");
        }
    }

    private static void rejectOverlaps(Map<String, Path> prefixes) {
        var entries = prefixes.entrySet().stream().toList();
        for (var leftIndex = 0; leftIndex < entries.size(); leftIndex++) {
            for (var rightIndex = leftIndex + 1; rightIndex < entries.size(); rightIndex++) {
                var left = entries.get(leftIndex);
                var right = entries.get(rightIndex);
                if (left.getValue().startsWith(right.getValue()) || right.getValue().startsWith(left.getValue())) {
                    throw new IllegalStateException(
                            "Library mount aliases must not overlap: " + left.getKey() + ", " + right.getKey());
                }
            }
        }
    }

    private static String normalizeRelativePath(String value) {
        if (value == null
                || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("\\")
                || value.contains("//")) {
            throw pathEscape();
        }
        try {
            var path = Path.of(value);
            if (path.isAbsolute()) {
                throw pathEscape();
            }
            var normalized = path.normalize().toString();
            if (!normalized.equals(value)) {
                throw pathEscape();
            }
            return normalized;
        } catch (InvalidPathException exception) {
            throw pathEscape();
        }
    }

    private static LibraryRootFailure unavailable() {
        return new LibraryRootFailure(
                LibraryRootFailure.Code.ROOT_UNAVAILABLE,
                "The configured library root is unavailable.");
    }

    private static LibraryRootFailure pathEscape() {
        return new LibraryRootFailure(
                LibraryRootFailure.Code.PATH_ESCAPE,
                "The requested library path is outside its allowed mount.");
    }

    public record ResolvedRoot(String normalizedRelativePath, Path path) {
    }
}

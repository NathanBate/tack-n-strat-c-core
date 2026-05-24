package com.tackstrat.graphics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Resolves paths whether the JVM cwd is repo root or the tack-and-strat module directory. */
final class RepoGraphicPaths {

    private static final Path SUB = Path.of("tack-and-strat");

    static List<Path> candidates(String relativeUnixPath) {
        String norm = normalize(relativeUnixPath);
        Path a = Path.of(norm);
        Path b = SUB.resolve(norm).normalize();
        if (a.equals(b) || b.startsWith(SUB.normalize()) && a.startsWith(SUB.normalize())) {
            return List.of(a);
        }
        return List.of(a, b);
    }

    static Optional<Path> firstExistingRegularFile(List<Path> candidates) {
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    static Optional<Path> firstExistingDir(List<Path> candidates) {
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static String normalize(String relativeUnixPath) {
        String s = relativeUnixPath.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }

    static Optional<Path> resolveCatalogSlotsFile() {
        return firstExistingRegularFile(candidates("assets/graphics/catalog/slots.json"));
    }

    /**
     * Folder for mutable graphics data ({@code uploads/}, {@code graphics_assignments.json}). Anchored to the same
     * tree as {@link #resolveCatalogSlotsFile()} so saves reload correctly regardless of JVM working directory
     * ({@code assets/graphics} vs {@code tack-and-strat/assets/graphics}).
     */
    static Path graphicsDataRoot() {
        Optional<Path> slots = resolveCatalogSlotsFile();
        if (slots.isPresent()) {
            Path catalogDir = slots.get().getParent();
            if (catalogDir != null) {
                Path graphics = catalogDir.getParent();
                if (graphics != null) {
                    return graphics.toAbsolutePath().normalize();
                }
            }
        }
        Optional<Path> existingGraphics = firstExistingDir(candidates("assets/graphics"));
        if (existingGraphics.isPresent()) {
            return existingGraphics.get().toAbsolutePath().normalize();
        }
        return Path.of("assets", "graphics").toAbsolutePath().normalize();
    }

    static List<Path> setsDirectoryCandidates() {
        return candidates("assets/graphics/sets");
    }

    static Optional<Path> resolveSetsDirectory() {
        return firstExistingDir(setsDirectoryCandidates());
    }
}

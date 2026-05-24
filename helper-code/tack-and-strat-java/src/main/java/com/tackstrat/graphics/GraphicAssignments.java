package com.tackstrat.graphics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-slot assignment token persistence.
 *
 * <p>Token grammar (string):
 * <ul>
 *   <li>{@code "default"} — bundled catalog art</li>
 *   <li>{@code "upload:<filename>"} — a PNG under {@link GraphicSlotUploads#slotDirectory(String)}</li>
 * </ul>
 */
final class GraphicAssignments {

    static final String DEFAULT_TOKEN = "default";
    static final String UPLOAD_TOKEN_PREFIX = "upload:";

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class PersistedRoot {
        @com.fasterxml.jackson.annotation.JsonProperty("tackstratGraphicAssignments")
        public int tackstratGraphicAssignments = 1;
        public LinkedHashMap<String, String> assignments = new LinkedHashMap<>();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private GraphicAssignments() {}

    static Path persistencePath() {
        return RepoGraphicPaths.graphicsDataRoot().resolve("graphics_assignments.json");
    }

    /** Reads tokens from disk; missing/invalid file → empty map. Always returns sanitized tokens. */
    static Map<String, String> loadAll() {
        Path file = persistencePath();
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            PersistedRoot root = JSON.readValue(file.toFile(), PersistedRoot.class);
            if (root == null || root.assignments == null) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : root.assignments.entrySet()) {
                String slotId = e.getKey() == null ? "" : e.getKey().strip();
                if (slotId.isEmpty()) {
                    continue;
                }
                out.put(slotId, normalizeToken(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        } catch (IOException ex) {
            return Map.of();
        }
    }

    static String tokenFor(String slotId) {
        if (slotId == null) {
            return DEFAULT_TOKEN;
        }
        return loadAll().getOrDefault(slotId.strip(), DEFAULT_TOKEN);
    }

    /** Writes the entire token map atomically. Creates parent directories as needed. */
    static void saveAll(Map<String, String> tokensBySlotId) throws IOException {
        Path file = persistencePath();
        Files.createDirectories(file.getParent());
        PersistedRoot root = new PersistedRoot();
        root.tackstratGraphicAssignments = 1;
        if (tokensBySlotId != null) {
            for (var e : tokensBySlotId.entrySet()) {
                String slotId = e.getKey() == null ? "" : e.getKey().strip();
                if (slotId.isEmpty()) {
                    continue;
                }
                root.assignments.put(slotId, normalizeToken(e.getValue()));
            }
        }
        try (OutputStream os = Files.newOutputStream(file)) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(os, root);
        }
    }

    /** Updates exactly one slot, preserving others; sanitizes incoming token. */
    static void putOne(String slotId, String token) throws IOException {
        if (slotId == null || slotId.isBlank()) {
            return;
        }
        Map<String, String> current = new LinkedHashMap<>(loadAll());
        current.put(slotId.strip(), normalizeToken(token));
        saveAll(current);
    }

    static boolean isUploadToken(String token) {
        return token != null && token.startsWith(UPLOAD_TOKEN_PREFIX);
    }

    static String uploadFileNameFromToken(String token) {
        if (!isUploadToken(token)) {
            return "";
        }
        return token.substring(UPLOAD_TOKEN_PREFIX.length()).trim();
    }

    static String makeUploadToken(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return DEFAULT_TOKEN;
        }
        return UPLOAD_TOKEN_PREFIX + fileName.strip();
    }

    private static String normalizeToken(String raw) {
        if (raw == null) {
            return DEFAULT_TOKEN;
        }
        String t = raw.strip();
        if (t.isEmpty() || t.equalsIgnoreCase(DEFAULT_TOKEN)) {
            return DEFAULT_TOKEN;
        }
        if (t.toLowerCase().startsWith(UPLOAD_TOKEN_PREFIX)) {
            String name = t.substring(UPLOAD_TOKEN_PREFIX.length()).trim();
            if (name.isEmpty()) {
                return DEFAULT_TOKEN;
            }
            return UPLOAD_TOKEN_PREFIX + name;
        }
        return DEFAULT_TOKEN;
    }
}

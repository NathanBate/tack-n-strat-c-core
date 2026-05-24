package com.tackstrat.graphics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scratch notes per catalog slot for the Graphics lab element workspace (persisted under ~/.tackstrat/). */
public final class GraphicSlotWorkspaceStore {

    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".tackstrat", "graphics_slot_workspace.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private GraphicSlotWorkspaceStore() {}

    public static Path persistencePath() {
        return FILE;
    }

    public static Map<String, String> loadAll() {
        if (!Files.isRegularFile(FILE)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> m = JSON.readValue(FILE.toFile(), new TypeReference<>() {});
            return m != null ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        } catch (IOException ex) {
            return new LinkedHashMap<>();
        }
    }

    public static void saveAll(Map<String, String> notes) throws IOException {
        Files.createDirectories(FILE.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(FILE.toFile(), notes == null ? Map.of() : notes);
    }
}

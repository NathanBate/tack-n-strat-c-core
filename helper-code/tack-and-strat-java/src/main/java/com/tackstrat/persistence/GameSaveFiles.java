package com.tackstrat.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Fixed location for saves — players never pick a folder in the OS UI. */
public final class GameSaveFiles {

    /** When set (e.g. in tests), all save paths resolve under this directory instead of {@code ~/.tackstrat}. */
    public static final String SAVE_ROOT_PROPERTY = "tackstrat.save.root";

    private GameSaveFiles() {}

    private static Path stateRoot() {
        String override = System.getProperty(SAVE_ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".tackstrat");
    }

    public static Path savesDirectory() {
        return stateRoot().resolve("saves");
    }

    /** Session snapshot updated after each turn handoff (separate from named saves in {@link #savesDirectory()}). */
    public static Path autosavePath() {
        return stateRoot().resolve("autosave.json");
    }

    public static boolean autosaveExists() {
        return Files.isRegularFile(autosavePath());
    }

    /** Previous autosave retained if the latest write fails mid-flight. */
    public static Path autosaveBackupPath() {
        return stateRoot().resolve("autosave.prev.json");
    }

    public static void ensureSavesDirectory() throws java.io.IOException {
        Files.createDirectories(savesDirectory());
    }

    /**
     * Safe single-segment filename ({@code *.json}) from a user-visible save name.
     */
    public static String toJsonFileName(String label) {
        String slug = slug(label);
        return slug + ".json";
    }

    public static Path pathForSaveLabel(String label) {
        return savesDirectory().resolve(toJsonFileName(label));
    }

    static String slug(String label) {
        if (label == null) {
            return "save";
        }
        String t = label.trim().replace(' ', '_');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length() && sb.length() < 48; i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "save" : sb.toString();
    }

    static String fileNameStem(Path path) {
        String n = path.getFileName().toString();
        if (n.toLowerCase().endsWith(".json")) {
            n = n.substring(0, n.length() - 5);
        }
        return n.isEmpty() ? "Save" : n;
    }
}

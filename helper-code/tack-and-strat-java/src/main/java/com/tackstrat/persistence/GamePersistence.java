package com.tackstrat.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tackstrat.model.GameSnapshot;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JSON saves under {@link GameSaveFiles#savesDirectory()} — no OS file picker required. */
public final class GamePersistence {

    private static final Logger LOG = Logger.getLogger(GamePersistence.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    private static final DateTimeFormatter WHEN_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
            .withZone(ZoneId.systemDefault())
            .withLocale(Locale.getDefault());

    private GamePersistence() {}

    /** Writes the rotating session file used by “Continue” on the main menu (not a named slot). */
    public static void writeAutosave(GameSnapshot snapshot, UiSaveState uiState) throws IOException {
        Path path = GameSaveFiles.autosavePath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = GameSaveFiles.autosaveBackupPath();
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (Files.exists(path)) {
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                write(tmp, snapshot, "Autosave", uiState == null ? UiSaveState.EMPTY : uiState);
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException ex) {
                last = ex;
                LOG.log(Level.FINE, "Autosave attempt " + (attempt + 1) + " failed", ex);
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
        if (last != null) {
            LOG.log(Level.WARNING, "Autosave failed after retries", last);
            throw last;
        }
    }

    /** Summary row for the load-game screen (Continue / last session). */
    public static SaveEntrySummary autosaveListEntry() throws IOException {
        Path path = GameSaveFiles.autosavePath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        SaveEntrySummary raw = peekSummary(path);
        if (raw == null) {
            return null;
        }
        return new SaveEntrySummary(
                raw.path(),
                "Last session (autosave)",
                raw.statusLine(),
                raw.whenLine(),
                raw.sortTimeMillis());
    }

    /** Writes into the managed saves folder with a visible title (creates directories as needed). */
    public static void writeNamedSave(String displayLabel, GameSnapshot snapshot, UiSaveState uiState) throws IOException {
        GameSaveFiles.ensureSavesDirectory();
        Path path = GameSaveFiles.pathForSaveLabel(displayLabel);
        write(
                path,
                snapshot,
                displayLabel.trim().isEmpty() ? GameSaveFiles.fileNameStem(path) : displayLabel.trim(),
                uiState == null ? UiSaveState.EMPTY : uiState);
    }

    /**
     * Writes to an exact path (tests, advanced use). Uses the file stem as the label when {@code label} is null.
     */
    public static void write(Path path, GameSnapshot snapshot) throws IOException {
        write(path, snapshot, GameSaveFiles.fileNameStem(path), UiSaveState.EMPTY);
    }

    public static void write(Path path, GameSnapshot snapshot, String label, UiSaveState uiState) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var meta = new SaveMetadata(label, System.currentTimeMillis());
        var env = new SaveFileEnvelope(
                SaveFileEnvelope.ENVELOPE_VERSION,
                meta,
                snapshot,
                uiState == null ? UiSaveState.EMPTY : uiState);
        MAPPER.writeValue(path.toFile(), env);
    }

    public static GameSnapshot read(Path path) throws IOException {
        return readLoaded(path).snapshot();
    }

    /** Reads snapshot plus UI strip (camera positions, etc.). */
    public static LoadedGame readLoaded(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        JsonNode snapNode;
        if (root.has("snapshot") && root.get("snapshot").isObject()) {
            snapNode = root.get("snapshot");
        } else {
            snapNode = root;
        }
        GameSnapshot snapshot = MAPPER.treeToValue(snapNode, GameSnapshot.class);
        UiSaveState ui = UiSaveState.EMPTY;
        if (root.has("uiSaveState") && !root.get("uiSaveState").isNull()) {
            ui = MAPPER.treeToValue(root.get("uiSaveState"), UiSaveState.class);
            if (ui == null) {
                ui = UiSaveState.EMPTY;
            }
        }
        return new LoadedGame(snapshot, ui);
    }

    public static List<SaveEntrySummary> listSaveSummaries() throws IOException {
        Path dir = GameSaveFiles.savesDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        var out = new ArrayList<SaveEntrySummary>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .map(GamePersistence::peekSummarySafe)
                    .filter(Objects::nonNull)
                    .forEach(out::add);
        }
        out.sort(Comparator.comparingLong(SaveEntrySummary::sortTimeMillis).reversed());
        return List.copyOf(out);
    }

    private static SaveEntrySummary peekSummarySafe(Path path) {
        try {
            return peekSummary(path);
        } catch (IOException e) {
            return null;
        }
    }

    static SaveEntrySummary peekSummary(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        JsonNode snap = root.has("snapshot") && root.get("snapshot").isObject()
                ? root.get("snapshot")
                : root;
        if (!snap.has("formatVersion")) {
            return null;
        }
        JsonNode meta = root.get("metadata");
        String label;
        long sortTime;
        if (meta != null && meta.has("label")) {
            label = meta.get("label").asText();
            sortTime = meta.has("savedAtMillis") ? meta.get("savedAtMillis").asLong(0L) : 0L;
        } else {
            label = GameSaveFiles.fileNameStem(path);
            sortTime = 0L;
        }
        if (sortTime <= 0L) {
            sortTime = Files.getLastModifiedTime(path).toMillis();
        }
        String whenLine = WHEN_FORMAT.format(Instant.ofEpochMilli(sortTime));

        int round = snap.get("round").asInt(1);
        int curIdx = snap.get("currentPlayerIndex").asInt(0);
        JsonNode players = snap.get("players");
        String turnName = "?";
        if (players != null && players.isArray() && curIdx >= 0 && curIdx < players.size()) {
            turnName = players.get(curIdx).get("name").asText("?");
        }

        String statusLine;
        JsonNode winnerNode = snap.get("winnerSeat");
        if (winnerNode != null && !winnerNode.isNull()) {
            int ws = winnerNode.asInt();
            String wname = "?";
            if (players != null && players.isArray()) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).get("seat").asInt(-1) == ws) {
                        wname = players.get(i).get("name").asText("?");
                        break;
                    }
                }
            }
            statusLine = "Finished — " + wname + " won";
        } else {
            statusLine = "Round " + round + " · " + turnName + "'s turn";
        }

        return new SaveEntrySummary(path, label, statusLine, whenLine, sortTime);
    }
}

package com.tackstrat;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;
import com.tackstrat.persistence.GamePersistence;
import com.tackstrat.persistence.GameSaveFiles;
import com.tackstrat.persistence.UiSaveState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutosavePersistenceTest {

    @TempDir
    Path root;

    @BeforeEach
    void installRoot() {
        System.setProperty(GameSaveFiles.SAVE_ROOT_PROPERTY, root.toString());
    }

    @AfterEach
    void clearRoot() {
        System.clearProperty(GameSaveFiles.SAVE_ROOT_PROPERTY);
    }

    @Test
    void autosave_rotates_backup_then_lists_as_last_session() throws Exception {
        var players = List.of(new Player(0, "A"), new Player(1, "B"));
        var session = new GameSession(players, 99_001L);

        GamePersistence.writeAutosave(session.capture(), UiSaveState.EMPTY);
        assertTrue(Files.isRegularFile(GameSaveFiles.autosavePath()));
        assertFalse(Files.exists(GameSaveFiles.autosaveBackupPath()));

        var snapBeforeTurn = GamePersistence.read(GameSaveFiles.autosavePath());
        session.endTurn();
        GamePersistence.writeAutosave(session.capture(), UiSaveState.EMPTY);

        assertTrue(Files.isRegularFile(GameSaveFiles.autosaveBackupPath()));
        assertEquals(snapBeforeTurn, GamePersistence.read(GameSaveFiles.autosaveBackupPath()));

        var row = GamePersistence.autosaveListEntry();
        assertNotNull(row);
        assertTrue(row.label().contains("Last session"));
        assertEquals(GameSaveFiles.autosavePath(), row.path());
    }
}

package com.tackstrat;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;
import com.tackstrat.persistence.GamePersistence;
import com.tackstrat.persistence.SeatCameraSnap;
import com.tackstrat.persistence.UiSaveState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveLoadRoundTripTest {

    @Test
    void json_roundTrip_snapshotEquals(@TempDir Path dir) throws Exception {
        var players = List.of(new Player(0, "Alpha"), new Player(1, "Beta"));
        var session = new GameSession(players, 77_777L);
        var before = session.capture();
        Path file = dir.resolve("game.json");
        GamePersistence.write(file, before);
        var readSnap = GamePersistence.read(file);
        assertEquals(before, readSnap);
        var restored = GameSession.restore(readSnap);
        assertEquals(before, restored.capture());
    }

    @Test
    void envelope_preserves_ui_cameras(@TempDir Path dir) throws Exception {
        var players = List.of(new Player(0, "Alpha"), new Player(1, "Beta"));
        var session = new GameSession(players, 77_777L);
        var snap = session.capture();
        var ui = new UiSaveState(List.of(new SeatCameraSnap(0, 1.25, 12.5, -30.0)));
        Path file = dir.resolve("game.json");
        GamePersistence.write(file, snap, "test", ui);
        var loaded = GamePersistence.readLoaded(file);
        assertEquals(snap, loaded.snapshot());
        assertEquals(1, loaded.uiSaveState().seatCameras().size());
        assertEquals(0, loaded.uiSaveState().seatCameras().getFirst().seat());
        assertTrue(loaded.uiSaveState().seatCameras().getFirst().scale() > 1.0);
    }

    @Test
    void json_preserves_cpu_player_flag(@TempDir Path dir) throws Exception {
        var players = List.of(new Player(0, "Lead", false), new Player(1, "Bot", true));
        var session = new GameSession(players, 66_606L);
        var snap = session.capture();
        Path file = dir.resolve("cpu.json");
        GamePersistence.write(file, snap);
        var readSnap = GamePersistence.read(file);
        assertTrue(readSnap.players().get(1).computer());
        assertFalse(readSnap.players().get(0).computer());
    }
}

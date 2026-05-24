package com.tackstrat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionAutoExploreTest {

    @Test
    void autoExplore_toggleAndPersistRoundTrip() {
        var session = new GameSession(List.of(new Player(0, "Solo")), 91_001L);
        Unit u = session.units().stream().filter(x -> x.ownerSeat() == 0).findFirst().orElseThrow();
        assertFalse(u.autoExplore());
        session.setUnitAutoExplore(u.id(), true);
        assertTrue(session.unitById(u.id()).orElseThrow().autoExplore());
        var snap = session.capture();
        var restored = GameSession.restore(snap);
        assertTrue(restored.unitById(u.id()).orElseThrow().autoExplore());
    }
}

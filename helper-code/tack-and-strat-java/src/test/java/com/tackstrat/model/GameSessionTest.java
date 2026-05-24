package com.tackstrat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionTest {

    @Test
    void twoPlayer_hotSeat_rotates_each_endTurn() {
        var players = List.of(new Player(0, "A"), new Player(1, "B"));
        var session = new GameSession(players, 42_424L);
        assertEquals(0, session.currentPlayer().seat());
        session.endTurn();
        assertEquals(1, session.currentPlayer().seat());
        session.endTurn();
        assertEquals(0, session.currentPlayer().seat());
    }

    @Test
    void chronology_ticks_after_full_rotation() {
        var players = List.of(new Player(0, "A"), new Player(1, "B"));
        var session = new GameSession(players, 99_001L);
        int yearsPer = session.yearsPerFullRound();
        int before = session.chronologyOffsetYears();
        session.endTurn();
        session.endTurn();
        assertEquals(before + yearsPer, session.chronologyOffsetYears());
        assertTrue(session.round() >= 2);
    }

    @Test
    void weatherHud_summary_nonempty() {
        var players = List.of(new Player(0, "Only"));
        var session = new GameSession(players, 77_007L);
        assertTrue(session.weatherHudSummary().contains("Weather"));
    }
}

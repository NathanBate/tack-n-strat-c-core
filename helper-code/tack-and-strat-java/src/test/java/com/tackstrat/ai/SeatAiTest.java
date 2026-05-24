package com.tackstrat.ai;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatAiTest {

    @Test
    void tick_is_noOp_when_current_seat_is_human() {
        var session = new GameSession(List.of(new Player(0, "Human"), new Player(1, "Other")), 120_003L);
        assertFalse(SeatAi.tick(session, new Random(1)));
    }

    @Test
    void tick_runs_when_current_seat_is_cpu() {
        var session = new GameSession(
                List.of(new Player(0, "Human", false), new Player(1, "CPU", true)), 120_004L);
        session.endTurn();
        assertTrue(session.currentPlayer().computer());
        SeatAi.tick(session, new Random(2));
    }
}

package com.tackstrat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionPreviewYieldTest {

    @Test
    void previewCityYield_pop2_at_least_as_much_total_as_pop1() {
        var session = new GameSession(List.of(new Player(0, "Solo")), 77_700L);
        HexCoord center = null;
        for (HexCoord c : session.map().allCells()) {
            if (session.terrainEffectiveAt(c).canFoundCityOn()) {
                center = c;
                break;
            }
        }
        assertTrue(center != null, "map should have at least one founding tile");
        var y1 = session.previewCityYieldAt(center, 1, CityFocus.BALANCED);
        var y2 = session.previewCityYieldAt(center, 2, CityFocus.BALANCED);
        int t1 = y1.food() + y1.production() + y1.gold();
        int t2 = y2.food() + y2.production() + y2.gold();
        assertTrue(t2 >= t1, "pop 2 should add a worker tile or match");
    }

    @Test
    void explainCannotFoundCity_nonSettler() {
        var session = new GameSession(List.of(new Player(0, "A"), new Player(1, "B")), 88_001L);
        Unit notSettler = session.units().stream()
                .filter(u -> u.ownerSeat() == 0)
                .filter(u -> u.kind() != UnitKind.SETTLER)
                .findFirst()
                .orElseThrow();
        var msg = session.explainCannotFoundCity(notSettler);
        assertTrue(msg.isPresent());
        assertTrue(msg.get().toLowerCase().contains("settler"), msg.get());
    }

    @Test
    void previewCityYieldRealistic_matchesOptimistic_when_no_claim_conflicts() {
        var session = new GameSession(List.of(new Player(0, "Solo")), 92_002L);
        HexCoord center = null;
        for (HexCoord c : session.map().allCells()) {
            if (session.terrainEffectiveAt(c).canFoundCityOn()) {
                center = c;
                break;
            }
        }
        assertTrue(center != null);
        var opt = session.previewCityYieldAt(center, 2, CityFocus.BALANCED);
        var real = session.previewCityYieldRealistic(center, 2, CityFocus.BALANCED, 0);
        assertTrue(real.food() <= opt.food() + 1);
        assertTrue(real.production() <= opt.production() + 1);
        assertTrue(real.gold() <= opt.gold() + 1);
    }
}

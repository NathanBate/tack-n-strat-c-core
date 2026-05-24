package com.tackstrat.ui;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;
import com.tackstrat.model.Unit;
import com.tackstrat.model.UnitKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexMapPanelSettleHintsTest {

    @Test
    void recommendations_exclude_city_and_impassable_tiles() {
        var session = new GameSession(List.of(new Player(0, "A"), new Player(1, "B")), 882244L);
        var panel = new HexMapPanel(() -> {}, s -> {}, s -> {});
        panel.bind(session);
        Unit settler = currentSettler(session);
        panel.setSettlerHintsVisible(true);
        panel.selectUnitById(settler.id());

        Map<?, Integer> scores = panel.debugSettleScoresFor(settler, session.visitedFor(session.currentPlayer().seat()));
        assertFalse(scores.isEmpty());
        for (var h : scores.keySet()) {
            var c = (com.tackstrat.model.HexCoord) h;
            assertTrue(session.cityAt(c).isEmpty(), "recommended tile cannot already contain a city");
            assertTrue(session.terrainEffectiveAt(c).canFoundCityOn(), "recommended tile must be valid for founding");
        }
    }

    @Test
    void travel_weight_penalizes_far_sites() {
        var session = new GameSession(List.of(new Player(0, "A"), new Player(1, "B")), 991177L);
        var panel = new HexMapPanel(() -> {}, s -> {}, s -> {});
        panel.bind(session);
        Unit settler = currentSettler(session);
        panel.selectUnitById(settler.id());
        panel.setSettlerHintsVisible(true);

        panel.setSettlerHintWeights(3, 3, 2, 0, 2);
        var lowTravel = panel.debugSettleScoresFor(settler, session.visitedFor(session.currentPlayer().seat()));
        Assumptions.assumeTrue(!lowTravel.isEmpty());

        var farCandidate = lowTravel.keySet().stream()
                .map(com.tackstrat.model.HexCoord.class::cast)
                .max((a, b) -> Integer.compare(settler.coord().distanceTo(a), settler.coord().distanceTo(b)))
                .orElseThrow();
        int lowScore = lowTravel.get(farCandidate);

        panel.setSettlerHintWeights(3, 3, 2, 9, 2);
        var highTravel = panel.debugSettleScoresFor(settler, session.visitedFor(session.currentPlayer().seat()));
        assertTrue(!highTravel.containsKey(farCandidate) || highTravel.get(farCandidate) < lowScore,
                "far site should drop or disappear when travel penalty increases");
    }

    @Test
    void rival_pressure_weight_penalizes_rival_claim_sites() {
        var session = new GameSession(List.of(new Player(0, "A"), new Player(1, "B")), 771133L);
        var panel = new HexMapPanel(() -> {}, s -> {}, s -> {});
        panel.bind(session);
        Unit settler = currentSettler(session);
        panel.selectUnitById(settler.id());
        panel.setSettlerHintsVisible(true);

        panel.setSettlerHintWeights(3, 3, 2, 2, 0);
        var weakRivalPenalty = panel.debugSettleScoresFor(settler, session.visitedFor(session.currentPlayer().seat()));
        var rivalTile = weakRivalPenalty.keySet().stream()
                .map(com.tackstrat.model.HexCoord.class::cast)
                .filter(c -> session.claimedOwnerAt(c).filter(owner -> owner != session.currentPlayer().seat()).isPresent())
                .findFirst();
        Assumptions.assumeTrue(rivalTile.isPresent());

        int weakScore = weakRivalPenalty.get(rivalTile.get());
        panel.setSettlerHintWeights(3, 3, 2, 2, 9);
        var strongRivalPenalty = panel.debugSettleScoresFor(settler, session.visitedFor(session.currentPlayer().seat()));
        assertTrue(!strongRivalPenalty.containsKey(rivalTile.get()) || strongRivalPenalty.get(rivalTile.get()) < weakScore,
                "rival-claimed sites should get worse with higher rival pressure penalty");
    }

    private static Unit currentSettler(GameSession session) {
        return session.units().stream()
                .filter(u -> u.ownerSeat() == session.currentPlayer().seat())
                .filter(u -> u.kind() == UnitKind.SETTLER)
                .findFirst()
                .orElseThrow();
    }
}

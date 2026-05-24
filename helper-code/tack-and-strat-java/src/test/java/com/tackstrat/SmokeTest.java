package com.tackstrat;

import com.tackstrat.model.City;
import com.tackstrat.model.GameSession;
import com.tackstrat.model.HexCoord;
import com.tackstrat.model.Player;
import com.tackstrat.model.Terrain;
import com.tackstrat.model.Unit;
import com.tackstrat.model.UnitKind;

import java.util.List;
import java.util.Set;

/** Standalone smoke test — exercises the full model end-to-end and prints a summary. */
public final class SmokeTest {

    public static void main(String[] args) {
        var players = List.of(new Player(0, "A"), new Player(1, "B"));
        var session = new GameSession(players, 12345L);

        require(session.units().size() >= 2, "starting units exist");
        require(session.cities().isEmpty(), "no cities yet");
        require(session.round() == 1, "round 1");

        // Each player should have visited some tiles already (around their start)
        Set<HexCoord> aSeen = session.visitedFor(0);
        require(!aSeen.isEmpty(), "A starts with visited tiles");

        // A founds first city (find a settler we can found from)
        Unit aSettler = settlerFor(session, 0);
        require(session.canFoundCity(aSettler), "A can found city");
        var aCity = session.foundCity(aSettler.id()).orElseThrow();
        require(session.cityCountFor(0) == 1, "A has 1 city");

        // B's turn → B founds
        session.endTurn();
        Unit bSettler = settlerFor(session, 1);
        require(session.foundCity(bSettler.id()).isPresent(), "B founded");

        // Hand back to A and queue settlers
        session.endTurn();
        require(session.currentPlayer().seat() == 0, "back to A");
        require(session.setCityProduction(aCity.id(), UnitKind.SETTLER), "queue settler");

        int loops = 0;
        while (!session.isOver() && loops < 600) {
            session.endTurn();
            loops++;
            if (session.currentPlayer().seat() == 0) {
                tryFoundFromAnySettler(session, 0, aCity);
            }
        }
        require(session.isOver(), "game ended (loops=" + loops + ")");
        var winner = session.winner().orElseThrow();
        System.out.println("Winner: " + winner.name()
                + "  ·  cities A=" + session.cityCountFor(0)
                + "  B=" + session.cityCountFor(1)
                + "  ·  rotations=" + loops);
        System.out.println("Cities: " + session.cities().stream().map(City::name).toList());

        // Verify terrain expansion is present
        var allTerrains = session.map().allCells().stream()
                .map(session.map()::terrainAt)
                .distinct()
                .toList();
        System.out.println("Terrains in world: " + allTerrains);
        require(allTerrains.contains(Terrain.WATER), "has water");
        boolean hasMountainOrDesert = allTerrains.contains(Terrain.MOUNTAIN)
                || allTerrains.contains(Terrain.DESERT);
        require(hasMountainOrDesert, "has mountains or deserts");
        System.out.println("Visited tiles: A=" + session.visitedFor(0).size()
                + " B=" + session.visitedFor(1).size()
                + "  (of " + session.map().allCells().size() + " total)");
    }

    private static Unit settlerFor(GameSession s, int seat) {
        return s.units().stream()
                .filter(u -> u.ownerSeat() == seat && u.kind() == UnitKind.SETTLER)
                .findFirst().orElseThrow();
    }

    private static void tryFoundFromAnySettler(GameSession session, int seat, City home) {
        for (var u : session.units()) {
            if (u.ownerSeat() != seat || u.kind() != UnitKind.SETTLER) continue;
            if (session.canFoundCity(u)) {
                session.foundCity(u.id());
                return;
            }
            walkAwayFrom(session, u, home.coord(), 6);
            if (session.canFoundCity(u)) {
                session.foundCity(u.id());
                return;
            }
        }
    }

    private static void walkAwayFrom(GameSession session, Unit u, HexCoord origin, int steps) {
        for (int i = 0; i < steps && u.movesRemaining() > 0; i++) {
            HexCoord best = null;
            int bestDist = u.coord().distanceTo(origin);
            for (var n : session.legalMoves(u)) {
                int d = n.distanceTo(origin);
                if (d > bestDist) {
                    best = n;
                    bestDist = d;
                }
            }
            if (best == null) return;
            if (!session.tryMoveUnit(u.id(), best)) return;
        }
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}

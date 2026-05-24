package com.tackstrat.ai;

import com.tackstrat.model.City;
import com.tackstrat.model.GameSession;
import com.tackstrat.model.HexCoord;
import com.tackstrat.model.Unit;
import com.tackstrat.model.UnitKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Minimal hot-seat opponent: expand production, fight when adjacent, follow routes, otherwise
 * march toward the nearest rival city or unit.
 */
public final class SeatAi {

    private SeatAi() {}

    /**
     * Performs one atomic UI-facing action for the current seat when it is an AI player.
     *
     * @return {@code true} if something changed and another tick may be useful this turn.
     */
    public static boolean tick(GameSession session, Random rng) {
        if (session.isOver()) {
            return false;
        }
        if (!session.currentPlayer().computer()) {
            return false;
        }
        int seat = session.currentPlayer().seat();

        if (clearStuckAutoExplore(session, seat)) {
            return true;
        }
        if (tryFoundCity(session)) {
            return true;
        }
        if (pickCityProduction(session, seat, rng)) {
            return true;
        }
        if (tryAdjacentAttacks(session, seat)) {
            return true;
        }
        if (followAnyQueuedRoute(session, seat)) {
            return true;
        }
        return moveOneUnit(session, seat, rng);
    }

    /**
     * Auto-explore units with MP but no legal step never get moves from the AI loop; turn explore off
     * so normal movement can run on the next tick (avoids spinning forever without ending the turn).
     */
    private static boolean clearStuckAutoExplore(GameSession session, int seat) {
        for (Unit u : session.units()) {
            if (u.ownerSeat() != seat || !u.autoExplore() || u.sleeping() || u.movesRemaining() <= 0) continue;
            if (session.legalMoves(u).isEmpty()) {
                session.setUnitAutoExplore(u.id(), false);
                return true;
            }
        }
        return false;
    }

    private static boolean tryFoundCity(GameSession session) {
        for (Unit u : session.units()) {
            if (u.ownerSeat() != session.currentPlayer().seat()) continue;
            if (u.kind() != UnitKind.SETTLER) continue;
            if (!session.canFoundCity(u)) continue;
            return session.foundCity(u.id()).isPresent();
        }
        return false;
    }

    private static boolean pickCityProduction(GameSession session, int seat, Random rng) {
        List<City> mine = new ArrayList<>();
        for (City c : session.cities()) {
            if (c.ownerSeat() == seat) mine.add(c);
        }
        mine.sort(Comparator.comparingInt(City::id));
        for (City c : mine) {
            if (c.currentBuild() != null) continue;
            UnitKind pick = session.cityCountFor(seat) <= 1 && session.round() <= 6
                    ? UnitKind.SCOUT
                    : UnitKind.WARRIOR;
            if (rng.nextDouble() < 0.22 && session.round() > 8) {
                pick = UnitKind.SETTLER;
            }
            return session.setCityProduction(c.id(), pick);
        }
        return false;
    }

    private static boolean tryAdjacentAttacks(GameSession session, int seat) {
        for (Unit u : session.units()) {
            if (u.ownerSeat() != seat || u.movesRemaining() <= 0) continue;
            if (u.kind().attackStrength() <= 0) continue;
            for (HexCoord n : u.coord().neighbors()) {
                Optional<Unit> foe = session.unitAt(n);
                if (foe.isPresent()
                        && foe.get().ownerSeat() != seat
                        && session.legalAttacks(u).contains(n)) {
                    return session.tryAttack(u.id(), n).isPresent();
                }
            }
        }
        return false;
    }

    private static boolean followAnyQueuedRoute(GameSession session, int seat) {
        List<Unit> mine = new ArrayList<>();
        for (Unit u : session.units()) {
            if (u.ownerSeat() == seat && !session.plannedRouteFor(u.id()).isEmpty()) {
                mine.add(u);
            }
        }
        mine.sort(Comparator.comparingInt(Unit::id));
        for (Unit u : mine) {
            if (session.followPlannedRoute(u.id())) {
                return true;
            }
        }
        return false;
    }

    private static boolean moveOneUnit(GameSession session, int seat, Random rng) {
        List<Unit> movers = new ArrayList<>();
        for (Unit u : session.units()) {
            if (u.ownerSeat() == seat && u.movesRemaining() > 0 && !u.autoExplore() && !u.sleeping()) {
                movers.add(u);
            }
        }
        movers.sort(Comparator.comparingInt(Unit::id));
        for (Unit u : movers) {
            List<HexCoord> legal = session.legalMoves(u);
            if (legal.isEmpty()) continue;
            HexCoord enemyFocus = nearestEnemyFrom(session, seat, u.coord());
            HexCoord dest = pickStepToward(legal, enemyFocus, rng);
            if (session.tryMoveUnit(u.id(), dest)) {
                return true;
            }
        }
        return false;
    }

    private static HexCoord nearestEnemyFrom(GameSession session, int mySeat, HexCoord from) {
        HexCoord best = null;
        int bd = Integer.MAX_VALUE;
        for (City c : session.cities()) {
            if (c.ownerSeat() == mySeat) continue;
            int d = from.distanceTo(c.coord());
            if (d < bd) {
                bd = d;
                best = c.coord();
            }
        }
        for (Unit u : session.units()) {
            if (u.ownerSeat() == mySeat) continue;
            int d = from.distanceTo(u.coord());
            if (d < bd) {
                bd = d;
                best = u.coord();
            }
        }
        return best;
    }

    private static HexCoord pickStepToward(List<HexCoord> legal, HexCoord enemyFocus, Random rng) {
        if (enemyFocus == null) {
            return legal.get(rng.nextInt(legal.size()));
        }
        HexCoord pick = legal.get(0);
        int best = Integer.MAX_VALUE;
        for (HexCoord n : legal) {
            int d = n.distanceTo(enemyFocus);
            if (d < best || (d == best && rng.nextBoolean())) {
                best = d;
                pick = n;
            }
        }
        return pick;
    }
}

package com.tackstrat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Continents, mountain ridges, desert patches, and spaced starts. */
public final class WorldGenerator {

    private static final int MAP_RADIUS = 16;
    private static final int CONTINENT_TARGET_TILES = 90;

    private WorldGenerator() {}

    public static GeneratedWorld generate(List<Player> players, long seed) {
        var rnd = new Random(seed);
        var builder = new GameMap.Builder(MAP_RADIUS);

        for (var c : new ArrayList<>(builder.mutableCells().keySet())) {
            builder.set(c, Terrain.WATER);
        }

        // 1. Continents (one per player + 1 wild)
        int continents = Math.max(2, players.size() + 1);
        List<HexCoord> seeds = pickContinentSeeds(builder, rnd, continents);
        Map<HexCoord, Integer> tileToContinent = new HashMap<>();
        for (int idx = 0; idx < seeds.size(); idx++) {
            growContinent(builder, seeds.get(idx), idx, tileToContinent, rnd);
        }

        // 2. Biomes for land tiles (coast/inland bias)
        for (var c : new ArrayList<>(builder.mutableCells().keySet())) {
            if (builder.mutableCells().get(c) != Terrain.WATER) {
                builder.set(c, pickBiome(c, builder, rnd));
            }
        }

        // 3. Mountain ridges (run a chain of mountains across some inland tiles)
        carveMountainRidges(builder, tileToContinent, rnd);

        // 4. Desert patches (small clusters in inland / non-forest)
        carveDeserts(builder, tileToContinent, rnd);

        var map = builder.build();
        List<HexCoord> spawns = pickSpawnsByContinent(map, tileToContinent, players.size(), rnd);

        var units = new ArrayList<Unit>();
        int uid = 1;
        for (int i = 0; i < players.size(); i++) {
            var p = players.get(i);
            HexCoord home = spawns.get(i);
            units.add(new Unit(uid++, p.seat(), UnitKind.SETTLER, home));
            // Each player also gets a Scout one tile away if possible
            HexCoord scoutCoord = pickAdjacent(home, map, units);
            if (scoutCoord != null) {
                units.add(new Unit(uid++, p.seat(), UnitKind.SCOUT, scoutCoord));
            }
        }

        Map<HexCoord, Integer> soil = new HashMap<>();
        for (HexCoord c : map.passableLand()) {
            soil.put(c, rnd.nextInt(3)); // 0–2 natural fertility
        }
        // Wildlife is spawned during play (herds or loners, irregular timing) — see GameSession.
        return new GeneratedWorld(map, units, soil, List.of());
    }

    private static List<HexCoord> pickContinentSeeds(GameMap.Builder builder, Random rnd, int n) {
        int innerRadius = Math.max(3, (MAP_RADIUS * 7) / 10);
        var candidates = new ArrayList<HexCoord>();
        for (var c : builder.mutableCells().keySet()) {
            if (Math.abs(c.q()) <= innerRadius
                    && Math.abs(c.r()) <= innerRadius
                    && Math.abs(c.q() + c.r()) <= innerRadius) {
                candidates.add(c);
            }
        }
        Collections.shuffle(candidates, rnd);

        var picked = new ArrayList<HexCoord>();
        int minDist = Math.max(5, MAP_RADIUS - 6);
        for (var c : candidates) {
            boolean ok = true;
            for (var p : picked) {
                if (p.distanceTo(c) < minDist) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                picked.add(c);
                if (picked.size() == n) {
                    break;
                }
            }
        }
        for (var c : candidates) {
            if (picked.size() == n) {
                break;
            }
            if (!picked.contains(c)) {
                picked.add(c);
            }
        }
        return picked;
    }

    private static void growContinent(
            GameMap.Builder builder,
            HexCoord seed,
            int continentId,
            Map<HexCoord, Integer> tileToContinent,
            Random rnd) {
        var cells = builder.mutableCells();
        if (!cells.containsKey(seed)) {
            return;
        }
        int target = CONTINENT_TARGET_TILES + rnd.nextInt(40) - 20;
        int converted = 0;

        var frontier = new ArrayList<HexCoord>();
        frontier.add(seed);

        while (converted < target && !frontier.isEmpty()) {
            int weight = Math.min(frontier.size(), 12);
            int pickIdx = rnd.nextInt(weight);
            var c = frontier.remove(pickIdx);

            if (cells.get(c) != Terrain.WATER || tileToContinent.containsKey(c)) {
                continue;
            }
            // Keep an outer ocean ring for cleaner coastlines
            if (Math.abs(c.q()) >= MAP_RADIUS
                    || Math.abs(c.r()) >= MAP_RADIUS
                    || Math.abs(c.q() + c.r()) >= MAP_RADIUS) {
                continue;
            }

            cells.put(c, Terrain.GRASS);
            tileToContinent.put(c, continentId);
            converted++;

            for (var n : c.neighbors()) {
                if (cells.containsKey(n)
                        && cells.get(n) == Terrain.WATER
                        && !tileToContinent.containsKey(n)) {
                    frontier.add(n);
                }
            }
            if (rnd.nextInt(4) == 0) {
                Collections.shuffle(frontier, rnd);
            }
        }
    }

    private static Terrain pickBiome(HexCoord c, GameMap.Builder builder, Random rnd) {
        var cells = builder.mutableCells();
        int waterNeighbors = 0;
        int existing = 0;
        for (var n : c.neighbors()) {
            if (!cells.containsKey(n)) {
                waterNeighbors++;
                continue;
            }
            existing++;
            if (cells.get(n) == Terrain.WATER) {
                waterNeighbors++;
            }
        }
        boolean coastal = waterNeighbors >= 2 || existing < 6;

        double roll = rnd.nextDouble();
        if (coastal) {
            if (roll < 0.55) return Terrain.GRASS;
            if (roll < 0.85) return Terrain.PLAINS;
            return Terrain.FOREST;
        }
        if (roll < 0.28) return Terrain.GRASS;
        if (roll < 0.55) return Terrain.PLAINS;
        if (roll < 0.85) return Terrain.FOREST;
        return Terrain.HILL;
    }

    private static void carveMountainRidges(
            GameMap.Builder builder,
            Map<HexCoord, Integer> tileToContinent,
            Random rnd) {
        var cells = builder.mutableCells();
        // Group land tiles by continent
        Map<Integer, List<HexCoord>> byContinent = new HashMap<>();
        for (var e : tileToContinent.entrySet()) {
            byContinent.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (var continent : byContinent.values()) {
            if (continent.size() < 25) {
                continue;
            }
            int ridgeLength = 4 + rnd.nextInt(5);
            // Start ridge at an inland tile
            HexCoord start = inlandTile(continent, cells, rnd);
            if (start == null) {
                continue;
            }
            HexCoord cursor = start;
            // Pick a primary direction (axial step)
            HexCoord[] dirs = {
                    new HexCoord(1, 0), new HexCoord(1, -1), new HexCoord(0, -1),
                    new HexCoord(-1, 0), new HexCoord(-1, 1), new HexCoord(0, 1),
            };
            HexCoord dir = dirs[rnd.nextInt(6)];
            for (int i = 0; i < ridgeLength; i++) {
                if (cells.containsKey(cursor)
                        && cells.get(cursor) != Terrain.WATER
                        && cells.get(cursor) != Terrain.MOUNTAIN) {
                    cells.put(cursor, Terrain.MOUNTAIN);
                }
                // Occasional jog
                HexCoord step = (rnd.nextInt(4) == 0)
                        ? dirs[rnd.nextInt(6)]
                        : dir;
                cursor = cursor.add(step);
            }
        }
    }

    private static HexCoord inlandTile(List<HexCoord> tiles, Map<HexCoord, Terrain> cells, Random rnd) {
        var copy = new ArrayList<>(tiles);
        Collections.shuffle(copy, rnd);
        for (var c : copy) {
            int waterNeighbors = 0;
            for (var n : c.neighbors()) {
                if (!cells.containsKey(n) || cells.get(n) == Terrain.WATER) {
                    waterNeighbors++;
                }
            }
            if (waterNeighbors == 0) {
                return c;
            }
        }
        return copy.isEmpty() ? null : copy.get(0);
    }

    private static void carveDeserts(
            GameMap.Builder builder,
            Map<HexCoord, Integer> tileToContinent,
            Random rnd) {
        var cells = builder.mutableCells();
        Map<Integer, List<HexCoord>> byContinent = new HashMap<>();
        for (var e : tileToContinent.entrySet()) {
            byContinent.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (var continent : byContinent.values()) {
            if (continent.size() < 30) {
                continue;
            }
            // 1-2 desert blobs per continent
            int blobs = 1 + rnd.nextInt(2);
            for (int b = 0; b < blobs; b++) {
                HexCoord seed = inlandTile(continent, cells, rnd);
                if (seed == null) {
                    continue;
                }
                int target = 4 + rnd.nextInt(5);
                var frontier = new ArrayList<HexCoord>();
                frontier.add(seed);
                int converted = 0;
                while (converted < target && !frontier.isEmpty()) {
                    var c = frontier.remove(rnd.nextInt(frontier.size()));
                    if (!cells.containsKey(c)) continue;
                    Terrain t = cells.get(c);
                    if (t == Terrain.WATER || t == Terrain.MOUNTAIN || t == Terrain.DESERT) {
                        continue;
                    }
                    cells.put(c, Terrain.DESERT);
                    converted++;
                    for (var n : c.neighbors()) {
                        if (cells.containsKey(n) && cells.get(n) != Terrain.WATER) {
                            frontier.add(n);
                        }
                    }
                }
            }
        }
    }

    private static List<HexCoord> pickSpawnsByContinent(
            GameMap map,
            Map<HexCoord, Integer> tileToContinent,
            int n,
            Random rnd) {
        Map<Integer, List<HexCoord>> byContinent = new HashMap<>();
        for (var c : map.passableLand()) {
            int cid = tileToContinent.getOrDefault(c, -1);
            byContinent.computeIfAbsent(cid, k -> new ArrayList<>()).add(c);
        }
        var continents = new ArrayList<>(byContinent.values());
        continents.removeIf(List::isEmpty);
        continents.sort(Comparator.<List<HexCoord>>comparingInt(List::size).reversed());

        var out = new ArrayList<HexCoord>();
        Set<Integer> used = new HashSet<>();
        for (var continent : continents) {
            if (out.size() == n) break;
            HexCoord pick = pickInteriorTile(continent, map);
            if (pick != null) {
                out.add(pick);
                used.add(System.identityHashCode(continent));
            }
        }
        // Fallback
        if (out.size() < n) {
            var fallback = new ArrayList<>(map.passableLand());
            Collections.shuffle(fallback, rnd);
            for (var c : fallback) {
                if (!out.contains(c)) {
                    out.add(c);
                    if (out.size() == n) break;
                }
            }
        }
        if (out.size() < n) {
            throw new IllegalStateException("Not enough land for " + n + " players");
        }
        return out;
    }

    private static HexCoord pickInteriorTile(List<HexCoord> tiles, GameMap map) {
        HexCoord best = null;
        int bestLand = -1;
        for (var c : tiles) {
            if (!map.terrainAt(c).canFoundCityOn()) {
                continue;
            }
            int land = 0;
            for (var nb : c.neighbors()) {
                if (map.contains(nb) && map.terrainAt(nb).passable()) {
                    land++;
                }
            }
            if (land > bestLand) {
                bestLand = land;
                best = c;
            }
            if (bestLand == 6) break;
        }
        if (best == null && !tiles.isEmpty()) {
            best = tiles.get(0);
        }
        return best;
    }

    private static HexCoord pickAdjacent(HexCoord c, GameMap map, List<Unit> existing) {
        for (var n : c.neighbors()) {
            if (!map.contains(n) || !map.terrainAt(n).passable()) continue;
            boolean occupied = false;
            for (var u : existing) {
                if (u.coord().equals(n)) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) {
                return n;
            }
        }
        return null;
    }
}

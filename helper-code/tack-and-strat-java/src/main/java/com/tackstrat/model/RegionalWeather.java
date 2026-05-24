package com.tackstrat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Spawns and evolves independent regional weather patches (few tiles to large blobs). */
public final class RegionalWeather {

    private RegionalWeather() {}

    public record Bootstrap(List<WeatherSystem> systems, int nextId) {}

    public record TickResult(List<WeatherSystem> systems, int nextId) {}

    public static Bootstrap initialSystems(GameMap map, long worldSeed) {
        long seed = mix64(worldSeed, 0xC1A07E10L, 1);
        Random rng = new Random(seed);
        var land = map.passableLand();
        if (land.isEmpty()) {
            return new Bootstrap(List.of(), 1);
        }
        int count = 2 + rng.nextInt(3);
        var list = new ArrayList<WeatherSystem>();
        int id = 1;
        for (int i = 0; i < count; i++) {
            HexCoord c = land.get(rng.nextInt(land.size()));
            int rad = 1 + rng.nextInt(7);
            Weather k = pickKind(rng, Season.SPRING, map, c);
            list.add(new WeatherSystem(id++, k, c.q(), c.r(), clampRadius(map, rad)));
        }
        return new Bootstrap(list, id);
    }

    public static TickResult tick(
            List<WeatherSystem> prev,
            GameMap map,
            Season season,
            long worldSeed,
            int round,
            int weatherNonce,
            int nextId) {
        long seed = mix64(worldSeed, round, weatherNonce ^ 0x51DEADL);
        Random rng = new Random(seed);
        var land = map.passableLand();
        var out = new ArrayList<WeatherSystem>();

        for (WeatherSystem ws : prev) {
            if (rng.nextDouble() < 0.07) {
                continue;
            }
            HexCoord center = ws.center();
            Weather kind = ws.kind();
            int radius = ws.radius();

            if (rng.nextDouble() < 0.38) {
                kind = wanderKind(kind, season, rng, map, center);
            }
            if (rng.nextDouble() < 0.42 && !land.isEmpty()) {
                var nbs = new ArrayList<>(center.neighbors());
                Collections.shuffle(nbs, rng);
                for (HexCoord mv : nbs) {
                    if (map.contains(mv) && map.terrainAt(mv).passable()) {
                        center = mv;
                        break;
                    }
                }
            }
            if (rng.nextDouble() < 0.28) {
                radius += rng.nextBoolean() ? 1 : -1;
                radius = clampRadius(map, radius);
            }

            out.add(new WeatherSystem(ws.id(), kind, center.q(), center.r(), radius));
        }

        final int maxSystems = 12;
        while (out.size() < maxSystems && !land.isEmpty() && rng.nextDouble() < 0.52) {
            HexCoord c = land.get(rng.nextInt(land.size()));
            int rad = 1 + rng.nextInt(8);
            Weather k = pickKind(rng, season, map, c);
            out.add(new WeatherSystem(nextId++, k, c.q(), c.r(), clampRadius(map, rad)));
            if (rng.nextDouble() < 0.38) {
                break;
            }
        }

        return new TickResult(out, nextId);
    }

    private static int clampRadius(GameMap map, int radius) {
        int maxR = Math.max(1, map.radius() + 2);
        return Math.max(1, Math.min(maxR, radius));
    }

    private static Weather wanderKind(
            Weather current, Season season, Random rng, GameMap map, HexCoord center) {
        Weather[] v = Weather.values();
        double[] w = new double[v.length];
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            double base = seasonWeight(v[i], season);
            if (v[i] == current) {
                base *= 0.42;
            }
            base *= terrainKindBias(v[i], map, center);
            w[i] = base * (0.82 + rng.nextDouble() * 0.38);
            sum += w[i];
        }
        double t = rng.nextDouble() * sum;
        double c = 0;
        for (int i = 0; i < v.length; i++) {
            c += w[i];
            if (t <= c) {
                return v[i];
            }
        }
        return current;
    }

    private static double seasonWeight(Weather wx, Season s) {
        return switch (s) {
            case SPRING -> switch (wx) {
                case RAIN -> 1.35;
                case FOG -> 1.15;
                case CLEAR -> 1.12;
                case STORM -> 0.95;
                default -> 0.85;
            };
            case SUMMER -> switch (wx) {
                case HEAT_WAVE -> 1.45;
                case DROUGHT -> 1.25;
                case CLEAR -> 1.1;
                case STORM -> 1.05;
                default -> 0.78;
            };
            case AUTUMN -> switch (wx) {
                case RAIN -> 1.2;
                case STORM -> 1.15;
                case FOG -> 1.2;
                case CLEAR -> 1.05;
                default -> 0.88;
            };
            case WINTER -> switch (wx) {
                case COLD_SNAP -> 1.5;
                case FOG -> 1.25;
                case STORM -> 1.05;
                case CLEAR -> 0.95;
                default -> 0.82;
            };
        };
    }

    private static Weather pickKind(Random rng, Season season, GameMap map, HexCoord center) {
        Weather[] v = Weather.values();
        double[] w = new double[v.length];
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            w[i] = seasonWeight(v[i], season)
                    * terrainKindBias(v[i], map, center)
                    * (0.9 + rng.nextDouble() * 0.25);
            sum += w[i];
        }
        double t = rng.nextDouble() * sum;
        double c = 0;
        for (int i = 0; i < v.length; i++) {
            c += w[i];
            if (t <= c) {
                return v[i];
            }
        }
        return Weather.CLEAR;
    }

    private static boolean isAdjacentToWater(GameMap map, HexCoord c) {
        for (HexCoord n : c.neighbors()) {
            if (map.contains(n) && map.terrainAt(n) == Terrain.WATER) {
                return true;
            }
        }
        return false;
    }

    /** Coast / desert / forest nudges that stack with season weights (deterministic roll uses RNG elsewhere). */
    private static double terrainKindBias(Weather wx, GameMap map, HexCoord center) {
        if (!map.contains(center)) {
            return 1.0;
        }
        Terrain t = map.terrainAt(center);
        boolean coast = isAdjacentToWater(map, center);
        return switch (wx) {
            case STORM -> coast ? 1.48 : 0.92;
            case RAIN -> coast ? 1.28 : 1.05;
            case DROUGHT -> (t == Terrain.DESERT) ? 1.45 : 0.95;
            case HEAT_WAVE -> (t == Terrain.DESERT) ? 1.4 : 1.0;
            case FOG -> (t == Terrain.FOREST) ? 1.32 : 1.0;
            case COLD_SNAP -> (t == Terrain.HILL || t == Terrain.FOREST) ? 1.15 : 1.0;
            default -> 1.0;
        };
    }

    private static long mix64(long a, long b, long c) {
        long x = a ^ Long.rotateLeft(b, 21) ^ Long.rotateLeft(c, 42);
        x ^= x >>> 33;
        x *= 0xff51_afd7_ed55_8ccdL;
        x ^= x >>> 33;
        x *= 0xc4ce_b9fe_1a85_ec53L;
        x ^= x >>> 33;
        return x;
    }
}

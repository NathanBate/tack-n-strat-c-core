package com.tackstrat.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Hex disk world with a terrain per tile. */
public final class GameMap {

    private final int radius;
    private final Map<HexCoord, Terrain> terrain;

    public GameMap(int radius, Map<HexCoord, Terrain> terrain) {
        if (radius < 1) {
            throw new IllegalArgumentException("radius must be >= 1");
        }
        this.radius = radius;
        this.terrain = Map.copyOf(terrain);
    }

    public int radius() {
        return radius;
    }

    public boolean contains(HexCoord c) {
        return terrain.containsKey(c);
    }

    public Terrain terrainAt(HexCoord c) {
        return terrain.get(c);
    }

    public List<HexCoord> allCells() {
        return List.copyOf(terrain.keySet());
    }

    public List<HexCoord> passableLand() {
        return terrain.entrySet().stream()
                .filter(e -> e.getValue().passable())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** Mutable builder for generation. */
    public static final class Builder {
        private final int radius;
        private final Map<HexCoord, Terrain> cells = new HashMap<>();

        public Builder(int radius) {
            this.radius = radius;
            for (var c : HexCoord.disk(radius)) {
                cells.put(c, Terrain.GRASS);
            }
        }

        public void set(HexCoord c, Terrain t) {
            if (!cells.containsKey(c)) {
                throw new IllegalArgumentException("Out of map: " + c);
            }
            cells.put(c, t);
        }

        public GameMap build() {
            return new GameMap(radius, cells);
        }

        public Map<HexCoord, Terrain> mutableCells() {
            return cells;
        }
    }
}

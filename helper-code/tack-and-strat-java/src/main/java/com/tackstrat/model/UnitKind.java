package com.tackstrat.model;

public enum UnitKind {
    //               glyph display       move cost  hp atk sight  weatherRes
    SETTLER('S',     "Settler",  2,  10, 10, 0, 2, 2),
    SCOUT('K',       "Scout",    4,   4, 10, 3, 3, 3),
    WARRIOR('W',     "Warrior",  2,   6, 20, 8, 2, 3),
    FARMER('f',      "Farmer",   2,   8,  8, 2, 2, 2),
    BUILDER('r',     "Builder",  2,  12, 10, 2, 2, 2),
    HUNTING_PARTY('H', "Hunting Party", 2, 16, 22, 0, 2, 3);

    private final char glyph;
    private final String displayName;
    private final int movement;
    private final int productionCost;
    private final int maxHp;
    private final int attackStrength;
    private final int sightRadius;
    /** 1–3: scouts/warriors cope better with harsh weather on the march. */
    private final int weatherResilience;

    UnitKind(
            char glyph,
            String displayName,
            int movement,
            int productionCost,
            int maxHp,
            int attackStrength,
            int sightRadius,
            int weatherResilience) {
        this.glyph = glyph;
        this.displayName = displayName;
        this.movement = movement;
        this.productionCost = productionCost;
        this.maxHp = maxHp;
        this.attackStrength = attackStrength;
        this.sightRadius = sightRadius;
        this.weatherResilience = weatherResilience;
    }

    /**
     * Maps persisted unit kind names. v3 and earlier use the same enum names as today ({@code FARMER},
     * {@code BUILDER}). Some interim v4 saves used {@code MINER} for the hill builder — map that to
     * {@link #BUILDER}.
     */
    public static UnitKind fromSnapshotName(String name, int formatVersion) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("unit kind name required");
        }
        if ("MINER".equals(name)) {
            return BUILDER;
        }
        return UnitKind.valueOf(name);
    }

    public char glyph() {
        return glyph;
    }

    public String displayName() {
        return displayName;
    }

    public int movement() {
        return movement;
    }

    public int productionCost() {
        return productionCost;
    }

    public int maxHp() {
        return maxHp;
    }

    public int attackStrength() {
        return attackStrength;
    }

    public int sightRadius() {
        return sightRadius;
    }

    public int weatherResilience() {
        return weatherResilience;
    }
}

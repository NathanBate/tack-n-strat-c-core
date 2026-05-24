package com.tackstrat.model;

/** Tile terrain. {@link #passable()} = can a unit step here at all. */
public enum Terrain {
    WATER(false, Integer.MAX_VALUE),
    GRASS(true, 1),
    PLAINS(true, 1),
    DESERT(true, 1),
    HILL(true, 2),
    FOREST(true, 2),
    MOUNTAIN(false, Integer.MAX_VALUE);

    private final boolean passable;
    private final int movementCost;

    Terrain(boolean passable, int movementCost) {
        this.passable = passable;
        this.movementCost = movementCost;
    }

    public boolean passable() {
        return passable;
    }

    /** Cost to enter this tile (used against unit's remaining moves). */
    public int movementCost() {
        return movementCost;
    }

    public boolean canFoundCityOn() {
        return this == GRASS || this == PLAINS || this == DESERT || this == HILL;
    }

    public int foodYield() {
        return switch (this) {
            case GRASS -> 2;
            case PLAINS, FOREST, WATER -> 1;
            default -> 0;
        };
    }

    public int productionYield() {
        return switch (this) {
            case HILL -> 2;
            case PLAINS, FOREST -> 1;
            default -> 0;
        };
    }

    public int goldYield() {
        return switch (this) {
            case DESERT, WATER -> 1;
            default -> 0;
        };
    }

    /** Soil can be worked harder (builder cultivate). */
    public boolean canCultivate() {
        return switch (this) {
            case GRASS, PLAINS, DESERT -> true;
            default -> false;
        };
    }

    /** Farm improvement (food) — clear forest to grass/plains first. */
    public boolean canSupportFarm() {
        return switch (this) {
            case GRASS, PLAINS, DESERT -> true;
            default -> false;
        };
    }

    /** Mine improvement (production) — hills only. */
    public boolean canSupportMine() {
        return this == HILL;
    }
}

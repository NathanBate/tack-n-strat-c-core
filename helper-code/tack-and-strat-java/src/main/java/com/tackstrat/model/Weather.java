package com.tackstrat.model;

/**
 * Tile-level weather from regional systems; effects scale with resilience (units, wildlife, cities).
 */
public enum Weather {
    CLEAR("Clear", 0, 0, 0, 0, 0, 0, 0),
    RAIN("Rain", 4, 0, -1, 0, 0, 0, 0),
    DROUGHT("Drought", -10, -2, 0, 1, 0, 1, 0),
    STORM("Storm", -2, -1, 0, 1, 0, 2, 1),
    COLD_SNAP("Cold snap", -4, 0, 0, 1, 1, 1, 0),
    HEAT_WAVE("Heat wave", -6, 0, 0, 0, 0, 1, 0),
    FOG("Fog", 0, 0, 0, 0, 1, 0, 0);

    private final String label;
    /** Food yield % change for cities (before resilience). */
    private final int cityFoodPercent;
    private final int cityProductionPercent;
    private final int cityGoldPercent;
    /** Extra flat movement cost entering any passable tile (before unit resilience). */
    private final int extraMoveCost;
    /** Base sight radius reduction (before unit resilience). */
    private final int sightPenalty;
    /** Extra damage dealt to wildlife in harsh weather (scaled by animal resilience). */
    private final int wildExtraDamage;
    /** Extra movement in forest during storm. */
    private final int forestStormMoveAdd;

    Weather(
            String label,
            int cityFoodPercent,
            int cityProductionPercent,
            int cityGoldPercent,
            int extraMoveCost,
            int sightPenalty,
            int wildExtraDamage,
            int forestStormMoveAdd) {
        this.label = label;
        this.cityFoodPercent = cityFoodPercent;
        this.cityProductionPercent = cityProductionPercent;
        this.cityGoldPercent = cityGoldPercent;
        this.extraMoveCost = extraMoveCost;
        this.sightPenalty = sightPenalty;
        this.wildExtraDamage = wildExtraDamage;
        this.forestStormMoveAdd = forestStormMoveAdd;
    }

    public String label() {
        return label;
    }

    /** City resilience 1–4 (larger settlements handle weather better). */
    public int mitigatedCityFoodPercent(int cityResilience) {
        return applyResilience(cityFoodPercent, cityResilience, 4);
    }

    public int mitigatedCityProductionPercent(int cityResilience) {
        return applyResilience(cityProductionPercent, cityResilience, 4);
    }

    public int mitigatedCityGoldPercent(int cityResilience) {
        return applyResilience(cityGoldPercent, cityResilience, 4);
    }

    private static int applyResilience(int raw, int resilience, int cap) {
        int r = Math.max(0, Math.min(cap, resilience));
        if (raw >= 0) {
            return raw;
        }
        double factor = 1.0 - (r / (double) cap) * 0.55;
        return (int) Math.round(raw * factor);
    }

    public int extraMovementCost(HexCoord dest, Terrain terrain, int unitResilience) {
        int extra = extraMoveCost;
        if (this == STORM && terrain == Terrain.FOREST) {
            extra += forestStormMoveAdd;
        }
        int mit = Math.max(0, extra - unitResilience / 2);
        return mit;
    }

    public int sightPenalty(int unitResilience) {
        return Math.max(0, sightPenalty - unitResilience / 2);
    }

    public int extraWildDamage(int animalResilience) {
        return Math.max(0, wildExtraDamage - animalResilience / 2);
    }

    /** When patches overlap, the higher priority kind wins for rules and display. */
    public int overlayPriority() {
        return switch (this) {
            case STORM -> 90;
            case HEAT_WAVE -> 74;
            case DROUGHT -> 70;
            case COLD_SNAP -> 68;
            case RAIN -> 58;
            case FOG -> 48;
            case CLEAR -> 0;
        };
    }

    /** Compact badge letter drawn on the map (empty for clear). */
    public char badgeChar() {
        return switch (this) {
            case CLEAR -> ' ';
            case RAIN -> 'R';
            case DROUGHT -> 'd';
            case STORM -> 'T';
            case COLD_SNAP -> 'C';
            case HEAT_WAVE -> 'H';
            case FOG -> 'F';
        };
    }
}

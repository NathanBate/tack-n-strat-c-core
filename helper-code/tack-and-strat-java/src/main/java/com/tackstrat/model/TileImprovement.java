package com.tackstrat.model;

/** Worked-tile upgrade (Civ-style). At most one per hex. */
public enum TileImprovement {
    NONE,
    /** Extra food when the tile is worked by a city. */
    FARM,
    /** Extra production when worked (hills). */
    MINE
}

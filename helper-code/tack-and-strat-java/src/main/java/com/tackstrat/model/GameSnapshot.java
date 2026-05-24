package com.tackstrat.model;

import java.util.List;

/**
 * Serializable game state for save/load. Versioned so older files can be rejected explicitly.
 */
public record GameSnapshot(
        int formatVersion,
        long worldSeed,
        int combatRngCallCount,
        int currentPlayerIndex,
        int round,
        int nextUnitId,
        int nextCityId,
        Integer winnerSeat,
        List<Player> players,
        int mapRadius,
        List<MapCell> mapCells,
        List<UnitSnap> units,
        List<CitySnap> cities,
        List<SeatCount> cityNamesIssued,
        List<VisTile> visited,
        List<SeatCount> goldBySeat,
        List<RouteTile> plannedRoutes,
        List<TileSoilSnap> tileSoil,
        List<TileModsSnap> tileMods,
        List<WildlifeSnap> wildlife,
        Integer nextWildAnimalId,
        /** Advances with wildlife spawn rolls (deterministic save/load). */
        Integer wildlifeSpawnNonce,
        /** Total years elapsed since start (see {@link com.tackstrat.model.Chronology}). */
        Integer chronologyOffsetYears,
        /** v2 only: single-world weather; superseded by {@link #weatherPatches}. */
        String currentWeather,
        Integer weatherNonce,
        /** Years added each full rotation (gameplay setting). */
        Integer yearsPerFullRound,
        /** Calendar-derived season index at capture time (0–3); gameplay uses {@link GameSession#season()} from chronology. */
        Integer seasonOrdinal,
        List<WeatherPatchSnap> weatherPatches,
        Integer nextWeatherPatchId
) {
    public static final int FORMAT_VERSION = 7;

    public record MapCell(int q, int r, String terrain) {}

    public record UnitSnap(
            int id,
            int ownerSeat,
            String kind,
            int q,
            int r,
            int hp,
            int movesRemaining,
            boolean autoExplore,
            Boolean sleeping,
            Integer carriedFood
    ) {
        /** v6 saves omit sleeping/carried food. */
        public UnitSnap(int id, int ownerSeat, String kind, int q, int r, int hp, int movesRemaining, boolean autoExplore, Integer carriedFood) {
            this(id, ownerSeat, kind, q, r, hp, movesRemaining, autoExplore, null, carriedFood);
        }

        /** v5 saves omit carried food. */
        public UnitSnap(int id, int ownerSeat, String kind, int q, int r, int hp, int movesRemaining, boolean autoExplore) {
            this(id, ownerSeat, kind, q, r, hp, movesRemaining, autoExplore, null, null);
        }

        /** Older saves omit auto-explore — defaults false. */
        public UnitSnap(int id, int ownerSeat, String kind, int q, int r, int hp, int movesRemaining) {
            this(id, ownerSeat, kind, q, r, hp, movesRemaining, false, null, null);
        }
    }

    public record HuntMissionSnap(int turnsRemaining, int targetAnimalId) {}

    public record CitySnap(
            int id,
            int ownerSeat,
            int q,
            int r,
            String name,
            String currentBuild,
            List<String> queuedBuilds,
            int productionStored,
            int hp,
            Integer population,
            Integer foodStored,
            List<HuntMissionSnap> huntMissions,
            String focus,
            List<String> buildings
    ) {}

    /** Per-seat count of cities owned (saved for diagnostics / older readers). */
    public record SeatCount(int seat, int count) {}

    public record VisTile(int seat, int q, int r) {}

    public record RouteTile(int unitId, int order, int q, int r) {}

    public record TileSoilSnap(int q, int r, int bonus) {}

    public record TileModsSnap(int q, int r, int cultivation, String improvement) {}

    public record WildlifeSnap(int id, String kind, int q, int r, int hp) {}

    public record WeatherPatchSnap(int id, String kind, int centerQ, int centerR, int radius) {}
}

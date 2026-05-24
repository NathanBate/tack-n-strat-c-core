package com.tackstrat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** In-progress game: human players (1–4), hex world, units, cities, fog of war, combat. */
public final class GameSession {

    public static final int CITIES_TO_WIN = 3;

    private final List<Player> players;
    private final GameMap map;
    private final List<Unit> units;
    private final List<City> cities;
    /** Shuffled pool of unique names; {@link #cityNameCursor} advances on each founding. */
    private final List<String> cityNameDeck;
    private int cityNameCursor;
    private final Map<Integer, Set<HexCoord>> visited = new HashMap<>();
    private final Map<Integer, Integer> playerGold = new HashMap<>();
    private final Map<Integer, List<HexCoord>> plannedRoutes = new HashMap<>();
    /** Natural fertility 0–2 per land hex (assigned at generation). */
    private final Map<HexCoord, Integer> soilFertilityBonus = new HashMap<>();
    /** Farmer cultivation tiers 0–2 (extra food when worked). */
    private final Map<HexCoord, Integer> cultivationTier = new HashMap<>();
    private final Map<HexCoord, TileImprovement> tileImprovements = new HashMap<>();
    /** Cleared forest → grass, etc.; merged into saves via {@link #terrainEffectiveAt(HexCoord)}. */
    private final Map<HexCoord, Terrain> terrainOverride = new HashMap<>();
    private final List<WildAnimal> wildlife = new ArrayList<>();
    /** Consecutive city raids per wildlife unit id (prevents endless city camping). */
    private final Map<Integer, Integer> wildlifeCityRaidStreak = new HashMap<>();
    private final long worldSeed;
    /** Drives deterministic spawn RNG; incremented each wildlife step. */
    private int wildlifeSpawnNonce;
    /** Telemetry for UI/event log: how many animals arrived in the latest wildlife step. */
    private int wildlifeSpawnedLastStep;
    /** Round index when {@link #wildlifeSpawnedLastStep} was recorded. */
    private int wildlifeSpawnedLastStepRound;
    /** Years since game start; advances each full round (see {@link Chronology}). */
    private int chronologyOffsetYears;
    /** Independent regional patches (overlap uses strongest {@link Weather#overlayPriority()}). */
    private final List<WeatherSystem> weatherSystems = new ArrayList<>();
    private int nextWeatherPatchId = 1;
    /** Mixing seed for regional weather RNG. */
    private int weatherNonce;
    /** Years added when every player finishes one turn (settings / save). */
    private final int yearsPerFullRound;

    private int currentPlayerIndex;
    private int round;
    private int nextUnitId;
    private int nextCityId;
    private int nextWildAnimalId;
    private Player winner;
    private Random combatRng;
    private boolean followingPlannedRouteNow;
    /** Number of {@code nextInt(3)} calls consumed on {@link #combatRng} (for deterministic save/load). */
    private int combatRngCallCount;

    public GameSession(List<Player> players) {
        this(players, new Random().nextLong(), Chronology.DEFAULT_YEARS_PER_FULL_ROUND);
    }

    public GameSession(List<Player> players, long worldSeed) {
        this(players, worldSeed, Chronology.DEFAULT_YEARS_PER_FULL_ROUND);
    }

    public GameSession(List<Player> players, long worldSeed, int yearsPerFullRound) {
        if (players.isEmpty() || players.size() > 4) {
            throw new IllegalArgumentException("Need 1–4 players");
        }
        this.players = List.copyOf(players);
        this.worldSeed = worldSeed;
        this.yearsPerFullRound = clampYearsPerRound(yearsPerFullRound);
        this.combatRng = newCombatRng(worldSeed, 0);
        this.combatRngCallCount = 0;
        GeneratedWorld gen = WorldGenerator.generate(players, worldSeed);
        this.map = gen.map();
        this.units = new ArrayList<>(gen.units());
        this.cities = new ArrayList<>();
        this.currentPlayerIndex = 0;
        this.round = 1;
        this.nextUnitId = units.stream().mapToInt(Unit::id).max().orElse(0) + 1;
        this.nextCityId = 1;
        this.soilFertilityBonus.putAll(gen.soilFertilityBonus());
        this.wildlife.addAll(gen.wildlife());
        this.nextWildAnimalId = this.wildlife.stream().mapToInt(WildAnimal::id).max().orElse(0) + 1;
        this.wildlifeSpawnNonce = 0;
        this.wildlifeSpawnedLastStep = 0;
        this.wildlifeSpawnedLastStepRound = 0;
        this.chronologyOffsetYears = 0;
        this.weatherNonce = 0;
        this.cityNameDeck = CityNamePool.shuffledDeck(worldSeed);
        this.cityNameCursor = 0;
        RegionalWeather.Bootstrap boot = RegionalWeather.initialSystems(this.map, worldSeed);
        this.weatherSystems.addAll(boot.systems());
        this.nextWeatherPatchId = boot.nextId();
        for (var p : players) {
            visited.put(p.seat(), new HashSet<>());
            playerGold.put(p.seat(), 0);
        }
        // Initial fog: each player sees around their starting units.
        for (var p : players) {
            updateVisitedFor(p.seat());
        }
        refreshMovesFor(currentPlayer().seat());
    }

    /**
     * Reconstructs a session from a snapshot (e.g. loaded from disk). Does not run world generation.
     */
    public static GameSession restore(GameSnapshot snap) {
        if (snap.formatVersion() < 1 || snap.formatVersion() > GameSnapshot.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported save format version: " + snap.formatVersion());
        }
        var players = List.copyOf(snap.players());
        if (players.isEmpty() || players.size() > 4) {
            throw new IllegalArgumentException("Save has invalid player count");
        }

        var terrainMap = new HashMap<HexCoord, Terrain>();
        for (var cell : snap.mapCells()) {
            terrainMap.put(new HexCoord(cell.q(), cell.r()), Terrain.valueOf(cell.terrain()));
        }
        var map = new GameMap(snap.mapRadius(), terrainMap);

        var units = new ArrayList<Unit>();
        for (var us : snap.units()) {
            var kind = UnitKind.fromSnapshotName(us.kind(), snap.formatVersion());
            var u = new Unit(us.id(), us.ownerSeat(), kind, new HexCoord(us.q(), us.r()));
            u.applySavedCombatState(us.hp(), us.movesRemaining());
            u.applySavedAutoExplore(us.autoExplore());
            u.applySavedSleeping(us.sleeping() != null && us.sleeping());
            u.applySavedCarriedFood(us.carriedFood() == null ? 0 : us.carriedFood());
            units.add(u);
        }

        var cities = new ArrayList<City>();
        for (var cs : snap.cities()) {
            UnitKind build = cs.currentBuild() == null || cs.currentBuild().isBlank()
                    ? null
                    : UnitKind.fromSnapshotName(cs.currentBuild(), snap.formatVersion());
            var queue = new ArrayList<UnitKind>();
            if (cs.queuedBuilds() != null) {
                for (String q : cs.queuedBuilds()) {
                    queue.add(UnitKind.fromSnapshotName(q, snap.formatVersion()));
                }
            }
            var city = new City(cs.id(), cs.ownerSeat(), new HexCoord(cs.q(), cs.r()), cs.name(), UnitKind.WARRIOR);
            int pop = cs.population() == null ? 1 : cs.population();
            int food = cs.foodStored() == null ? 0 : cs.foodStored();
            var huntList = new ArrayList<City.HuntMission>();
            if (cs.huntMissions() != null) {
                for (var hm : cs.huntMissions()) {
                    huntList.add(new City.HuntMission(hm.turnsRemaining(), hm.targetAnimalId()));
                }
            }
            city.applySavedState(cs.ownerSeat(), build, queue, cs.productionStored(), cs.hp(), pop, food, huntList);
            CityFocus savedFocus = CityFocus.BALANCED;
            if (cs.focus() != null && !cs.focus().isBlank()) {
                try {
                    savedFocus = CityFocus.valueOf(cs.focus());
                } catch (IllegalArgumentException ignored) {
                    savedFocus = CityFocus.BALANCED;
                }
            }
            var savedBuildings = new ArrayList<CityBuilding>();
            if (cs.buildings() != null) {
                for (String b : cs.buildings()) {
                    if (b == null || b.isBlank()) continue;
                    try {
                        savedBuildings.add(CityBuilding.valueOf(b));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            city.applySavedCityMeta(savedFocus, savedBuildings);
            cities.add(city);
        }

        var visitedFromSave = new HashMap<Integer, Set<HexCoord>>();
        for (var vt : snap.visited()) {
            visitedFromSave
                    .computeIfAbsent(vt.seat(), k -> new HashSet<>())
                    .add(new HexCoord(vt.q(), vt.r()));
        }
        var routesFromSave = new HashMap<Integer, List<HexCoord>>();
        if (snap.plannedRoutes() != null) {
            var grouped = new HashMap<Integer, List<GameSnapshot.RouteTile>>();
            for (var rt : snap.plannedRoutes()) {
                grouped.computeIfAbsent(rt.unitId(), k -> new ArrayList<>()).add(rt);
            }
            for (var e : grouped.entrySet()) {
                e.getValue().sort(Comparator.comparingInt(GameSnapshot.RouteTile::order));
                var route = new ArrayList<HexCoord>();
                for (var rt : e.getValue()) route.add(new HexCoord(rt.q(), rt.r()));
                routesFromSave.put(e.getKey(), route);
            }
        }

        var goldFromSave = new HashMap<Integer, Integer>();
        if (snap.goldBySeat() != null) {
            for (var g : snap.goldBySeat()) {
                goldFromSave.put(g.seat(), g.count());
            }
        }

        Player winnerPlayer = null;
        if (snap.winnerSeat() != null) {
            int ws = snap.winnerSeat();
            for (var p : players) {
                if (p.seat() == ws) {
                    winnerPlayer = p;
                    break;
                }
            }
        }

        var soilFromSave = new HashMap<HexCoord, Integer>();
        if (snap.tileSoil() != null) {
            for (var t : snap.tileSoil()) {
                soilFromSave.put(new HexCoord(t.q(), t.r()), t.bonus());
            }
        }
        var cultFromSave = new HashMap<HexCoord, Integer>();
        var imprFromSave = new HashMap<HexCoord, TileImprovement>();
        if (snap.tileMods() != null) {
            for (var m : snap.tileMods()) {
                var hc = new HexCoord(m.q(), m.r());
                cultFromSave.put(hc, m.cultivation());
                try {
                    imprFromSave.put(hc, TileImprovement.valueOf(m.improvement()));
                } catch (Exception ignored) {
                    imprFromSave.put(hc, TileImprovement.NONE);
                }
            }
        }
        var animals = new ArrayList<WildAnimal>();
        int nextW = 1;
        if (snap.wildlife() != null) {
            for (var ws : snap.wildlife()) {
                var kind = WildAnimalKind.valueOf(ws.kind());
                var a = new WildAnimal(ws.id(), kind, new HexCoord(ws.q(), ws.r()));
                a.applySavedHp(ws.hp());
                animals.add(a);
                nextW = Math.max(nextW, ws.id() + 1);
            }
        }
        if (snap.nextWildAnimalId() != null) {
            nextW = Math.max(nextW, snap.nextWildAnimalId());
        }
        int wildNonce = snap.wildlifeSpawnNonce() != null ? snap.wildlifeSpawnNonce() : 0;

        var wxSystems = new ArrayList<WeatherSystem>();
        int nextWxId = 1;
        if (snap.weatherPatches() != null && !snap.weatherPatches().isEmpty()) {
            for (var wp : snap.weatherPatches()) {
                try {
                    Weather k = Weather.valueOf(wp.kind());
                    wxSystems.add(new WeatherSystem(wp.id(), k, wp.centerQ(), wp.centerR(), wp.radius()));
                    nextWxId = Math.max(nextWxId, wp.id() + 1);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (wxSystems.isEmpty()) {
            Weather legacy = Weather.CLEAR;
            if (snap.currentWeather() != null && !snap.currentWeather().isBlank()) {
                try {
                    legacy = Weather.valueOf(snap.currentWeather());
                } catch (IllegalArgumentException ignored) {
                }
            }
            var land = map.passableLand();
            if (!land.isEmpty()) {
                HexCoord c = land.getFirst();
                int rad = Math.max(2, Math.min(map.radius() + 1, 6));
                wxSystems.add(new WeatherSystem(1, legacy, c.q(), c.r(), rad));
                nextWxId = 2;
            }
        }
        if (wxSystems.isEmpty()) {
            RegionalWeather.Bootstrap boot = RegionalWeather.initialSystems(map, snap.worldSeed());
            wxSystems.addAll(boot.systems());
            nextWxId = boot.nextId();
        }
        if (snap.nextWeatherPatchId() != null) {
            nextWxId = Math.max(nextWxId, snap.nextWeatherPatchId());
        }

        int chronoYears = snap.chronologyOffsetYears() != null ? snap.chronologyOffsetYears() : 0;
        int wxNonce = snap.weatherNonce() != null ? snap.weatherNonce() : 0;
        int ypr = snap.yearsPerFullRound() != null
                ? clampYearsPerRound(snap.yearsPerFullRound())
                : Chronology.DEFAULT_YEARS_PER_FULL_ROUND;
        Random rng = newCombatRng(snap.worldSeed(), snap.combatRngCallCount());
        return new GameSession(
                players,
                map,
                units,
                cities,
                visitedFromSave,
                goldFromSave,
                routesFromSave,
                soilFromSave,
                cultFromSave,
                imprFromSave,
                animals,
                nextW,
                wildNonce,
                chronoYears,
                wxSystems,
                nextWxId,
                wxNonce,
                ypr,
                snap.worldSeed(),
                snap.combatRngCallCount(),
                snap.currentPlayerIndex(),
                snap.round(),
                snap.nextUnitId(),
                snap.nextCityId(),
                winnerPlayer,
                rng);
    }

    private GameSession(
            List<Player> players,
            GameMap map,
            List<Unit> units,
            List<City> cities,
            Map<Integer, Set<HexCoord>> visitedFromSave,
            Map<Integer, Integer> goldFromSave,
            Map<Integer, List<HexCoord>> routesFromSave,
            Map<HexCoord, Integer> soilFertility,
            Map<HexCoord, Integer> cultivation,
            Map<HexCoord, TileImprovement> improvements,
            List<WildAnimal> animals,
            int nextWildAnimalId,
            int wildlifeSpawnNonce,
            int chronologyOffsetYears,
            List<WeatherSystem> weatherSystems,
            int nextWeatherPatchId,
            int weatherNonce,
            int yearsPerFullRound,
            long worldSeed,
            int combatRngCallCount,
            int currentPlayerIndex,
            int round,
            int nextUnitId,
            int nextCityId,
            Player winner,
            Random combatRng) {
        if (players.isEmpty() || players.size() > 4) {
            throw new IllegalArgumentException("Need 1–4 players");
        }
        this.players = List.copyOf(players);
        this.map = map;
        this.units = new ArrayList<>(units);
        this.cities = new ArrayList<>(cities);
        this.worldSeed = worldSeed;
        this.combatRngCallCount = combatRngCallCount;
        this.combatRng = combatRng;
        this.currentPlayerIndex = currentPlayerIndex;
        this.round = round;
        this.nextUnitId = nextUnitId;
        this.nextCityId = nextCityId;
        this.nextWildAnimalId = nextWildAnimalId;
        this.wildlifeSpawnNonce = wildlifeSpawnNonce;
        this.wildlifeSpawnedLastStep = 0;
        this.wildlifeSpawnedLastStepRound = 0;
        this.chronologyOffsetYears = chronologyOffsetYears;
        this.weatherSystems.addAll(weatherSystems);
        this.nextWeatherPatchId = nextWeatherPatchId;
        this.weatherNonce = weatherNonce;
        this.yearsPerFullRound = clampYearsPerRound(yearsPerFullRound);
        this.winner = winner;
        this.soilFertilityBonus.putAll(soilFertility);
        this.cultivationTier.putAll(cultivation);
        this.tileImprovements.putAll(improvements);
        this.wildlife.addAll(animals);
        this.cityNameDeck = CityNamePool.shuffledDeck(worldSeed);
        this.cityNameCursor = cities.size();
        for (var p : players) {
            visited.put(p.seat(), new HashSet<>());
            playerGold.put(p.seat(), goldFromSave.getOrDefault(p.seat(), 0));
        }
        for (var e : visitedFromSave.entrySet()) {
            visited.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        }
        plannedRoutes.putAll(routesFromSave);
    }

    private static int clampYearsPerRound(int years) {
        return Math.max(1, Math.min(99, years));
    }

    private static Random newCombatRng(long worldSeed, int callsConsumed) {
        Random r = new Random(worldSeed ^ 0x5a5aL);
        for (int i = 0; i < callsConsumed; i++) {
            r.nextInt(3);
        }
        return r;
    }

    /** Terrain for gameplay, UI, and saves (includes cleared forest, etc.). */
    public Terrain terrainEffectiveAt(HexCoord c) {
        return terrainOverride.getOrDefault(c, map.terrainAt(c));
    }

    /** Full mutable state for save files. */
    public GameSnapshot capture() {
        var playerSnap = new ArrayList<>(players);
        playerSnap.sort(Comparator.comparingInt(Player::seat));
        var mapCells = new ArrayList<GameSnapshot.MapCell>();
        for (var c : map.allCells()) {
            mapCells.add(new GameSnapshot.MapCell(c.q(), c.r(), terrainEffectiveAt(c).name()));
        }
        var unitSnaps = new ArrayList<GameSnapshot.UnitSnap>();
        for (var u : units) {
            unitSnaps.add(new GameSnapshot.UnitSnap(
                    u.id(),
                    u.ownerSeat(),
                    u.kind().name(),
                    u.coord().q(),
                    u.coord().r(),
                    u.hp(),
                    u.movesRemaining(),
                    u.autoExplore(),
                    u.sleeping(),
                    u.carriedFood()));
        }
        var citySnaps = new ArrayList<GameSnapshot.CitySnap>();
        for (var c : cities) {
            String buildName = c.currentBuild() == null ? null : c.currentBuild().name();
            var queueNames = c.queuedBuilds().stream().map(UnitKind::name).toList();
            var huntSnaps = new ArrayList<GameSnapshot.HuntMissionSnap>();
            for (var hm : c.huntMissions()) {
                huntSnaps.add(new GameSnapshot.HuntMissionSnap(hm.turnsRemaining, hm.targetAnimalId));
            }
            var buildingNames = new ArrayList<String>();
            for (CityBuilding b : c.buildings()) {
                buildingNames.add(b.name());
            }
            citySnaps.add(new GameSnapshot.CitySnap(
                    c.id(),
                    c.ownerSeat(),
                    c.coord().q(),
                    c.coord().r(),
                    c.name(),
                    buildName,
                    queueNames,
                    c.productionStored(),
                    c.hp(),
                    c.population(),
                    c.foodStored(),
                    huntSnaps,
                    c.focus().name(),
                    buildingNames));
        }
        var nameIssued = new ArrayList<GameSnapshot.SeatCount>();
        var foundedPerSeat = new HashMap<Integer, Integer>();
        for (City c : cities) {
            foundedPerSeat.merge(c.ownerSeat(), 1, Integer::sum);
        }
        for (var e : foundedPerSeat.entrySet()) {
            nameIssued.add(new GameSnapshot.SeatCount(e.getKey(), e.getValue()));
        }
        var vis = new ArrayList<GameSnapshot.VisTile>();
        for (var p : players) {
            for (var h : visitedFor(p.seat())) {
                vis.add(new GameSnapshot.VisTile(p.seat(), h.q(), h.r()));
            }
        }
        var goldSnap = new ArrayList<GameSnapshot.SeatCount>();
        for (var p : players) {
            goldSnap.add(new GameSnapshot.SeatCount(p.seat(), playerGold.getOrDefault(p.seat(), 0)));
        }
        var routes = new ArrayList<GameSnapshot.RouteTile>();
        for (var e : plannedRoutes.entrySet()) {
            int uid = e.getKey();
            var route = e.getValue();
            for (int i = 0; i < route.size(); i++) {
                routes.add(new GameSnapshot.RouteTile(uid, i, route.get(i).q(), route.get(i).r()));
            }
        }
        mapCells.sort(Comparator.comparingInt(GameSnapshot.MapCell::q).thenComparingInt(GameSnapshot.MapCell::r));
        unitSnaps.sort(Comparator.comparingInt(GameSnapshot.UnitSnap::id));
        citySnaps.sort(Comparator.comparingInt(GameSnapshot.CitySnap::id));
        nameIssued.sort(Comparator.comparingInt(GameSnapshot.SeatCount::seat));
        vis.sort(Comparator.comparingInt(GameSnapshot.VisTile::seat)
                .thenComparingInt(GameSnapshot.VisTile::q)
                .thenComparingInt(GameSnapshot.VisTile::r));
        Integer winnerSeat = winner == null ? null : winner.seat();
        var tileSoil = new ArrayList<GameSnapshot.TileSoilSnap>();
        for (var e : soilFertilityBonus.entrySet()) {
            tileSoil.add(new GameSnapshot.TileSoilSnap(e.getKey().q(), e.getKey().r(), e.getValue()));
        }
        var tileMods = new ArrayList<GameSnapshot.TileModsSnap>();
        for (var c : map.allCells()) {
            int cult = cultivationTier.getOrDefault(c, 0);
            TileImprovement imp = tileImprovements.getOrDefault(c, TileImprovement.NONE);
            if (cult > 0 || imp != TileImprovement.NONE) {
                tileMods.add(new GameSnapshot.TileModsSnap(c.q(), c.r(), cult, imp.name()));
            }
        }
        var wildSnaps = new ArrayList<GameSnapshot.WildlifeSnap>();
        for (var a : wildlife) {
            wildSnaps.add(new GameSnapshot.WildlifeSnap(
                    a.id(), a.kind().name(), a.coord().q(), a.coord().r(), a.hp()));
        }
        tileSoil.sort(Comparator.comparingInt(GameSnapshot.TileSoilSnap::q).thenComparingInt(GameSnapshot.TileSoilSnap::r));
        tileMods.sort(Comparator.comparingInt(GameSnapshot.TileModsSnap::q).thenComparingInt(GameSnapshot.TileModsSnap::r));
        wildSnaps.sort(Comparator.comparingInt(GameSnapshot.WildlifeSnap::id));
        var patchSnaps = new ArrayList<GameSnapshot.WeatherPatchSnap>();
        for (WeatherSystem ws : weatherSystems) {
            patchSnaps.add(new GameSnapshot.WeatherPatchSnap(
                    ws.id(), ws.kind().name(), ws.centerQ(), ws.centerR(), ws.radius()));
        }
        patchSnaps.sort(Comparator.comparingInt(GameSnapshot.WeatherPatchSnap::id));
        return new GameSnapshot(
                GameSnapshot.FORMAT_VERSION,
                worldSeed,
                combatRngCallCount,
                currentPlayerIndex,
                round,
                nextUnitId,
                nextCityId,
                winnerSeat,
                List.copyOf(playerSnap),
                map.radius(),
                mapCells,
                unitSnaps,
                citySnaps,
                nameIssued,
                vis,
                goldSnap,
                routes,
                tileSoil,
                tileMods,
                wildSnaps,
                nextWildAnimalId,
                wildlifeSpawnNonce,
                chronologyOffsetYears,
                null,
                weatherNonce,
                yearsPerFullRound,
                Chronology.seasonIndexFromElapsedYears(chronologyOffsetYears, yearsPerFullRound),
                patchSnaps,
                nextWeatherPatchId);
    }

    public long worldSeed() {
        return worldSeed;
    }

    public List<Player> players() {
        return players;
    }

    public GameMap map() {
        return map;
    }

    public List<Unit> units() {
        return Collections.unmodifiableList(units);
    }

    public List<City> cities() {
        return Collections.unmodifiableList(cities);
    }

    public int round() {
        return round;
    }

    /** In-game date label (from ~4000 BCE; advances each full round). */
    public String calendarEraLabel() {
        return Chronology.formatEra(chronologyOffsetYears);
    }

    public int chronologyOffsetYears() {
        return chronologyOffsetYears;
    }

    public int yearsPerFullRound() {
        return yearsPerFullRound;
    }

    public Season season() {
        return Chronology.seasonFromElapsedYears(chronologyOffsetYears, yearsPerFullRound);
    }

    /** Short line for HUD: counts of active weather patches by type. */
    public String weatherHudSummary() {
        if (weatherSystems.isEmpty()) {
            return "Weather: clear skies (no active systems)";
        }
        var counts = new LinkedHashMap<String, Integer>();
        for (WeatherSystem ws : weatherSystems) {
            String k = ws.kind().label();
            counts.merge(k, 1, Integer::sum);
        }
        var sb = new StringBuilder();
        sb.append("Weather: ")
                .append(weatherSystems.size())
                .append(weatherSystems.size() == 1 ? " system" : " systems")
                .append(" — ");
        int i = 0;
        for (var e : counts.entrySet()) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    /** Scoreboard summary at game end. */
    public String victoryRecapHtml(Player winner) {
        var esc = new StringBuilder();
        esc.append("<html><body style='width:440px;color:#e8ecf4'>");
        esc.append("<p style='margin-top:0'><b style='font-size:15px'>")
                .append(escapeHtml(winner.name()))
                .append("</b> wins — first to ")
                .append(CITIES_TO_WIN)
                .append(" cities.</p>");
        esc.append("<p style='color:#aab4c8'>")
                .append(escapeHtml(calendarEraLabel()))
                .append(" · Round ")
                .append(round())
                .append("</p><table cellpadding='5' cellspacing='0'>");
        for (Player p : players()) {
            esc.append("<tr><td><b>")
                    .append(escapeHtml(p.name()))
                    .append("</b></td><td>")
                    .append(cityCountFor(p.seat()))
                    .append(" cities</td><td>")
                    .append(unitCountFor(p.seat()))
                    .append(" units</td><td>")
                    .append(goldFor(p.seat()))
                    .append(" gold</td></tr>");
        }
        esc.append("</table><p style='color:#8b93a0;font-size:12px'>Tip: named saves and autosave are in ~/.tackstrat/</p>");
        esc.append("</body></html>");
        return esc.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Strongest weather affecting this hex (clear if none). */
    public Weather weatherAt(HexCoord h) {
        Weather best = Weather.CLEAR;
        int p = 0;
        for (WeatherSystem ws : weatherSystems) {
            if (ws.covers(h)) {
                int wp = ws.kind().overlayPriority();
                if (wp > p) {
                    p = wp;
                    best = ws.kind();
                }
            }
        }
        return best;
    }

    /** Total movement cost for {@code u} entering {@code dest} (terrain + weather, mitigated by unit resilience). */
    public int movementCostForStep(Unit u, HexCoord dest) {
        Terrain t = terrainEffectiveAt(dest);
        Weather wx = weatherAt(dest);
        return t.movementCost() + wx.extraMovementCost(dest, t, u.kind().weatherResilience());
    }

    private static int cityWeatherResilience(City c) {
        return Math.min(4, Math.max(1, 1 + c.population() / 4));
    }

    public Player currentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public int currentPlayerIndex() {
        return currentPlayerIndex;
    }

    public Optional<Player> winner() {
        return Optional.ofNullable(winner);
    }

    public boolean isOver() {
        return winner != null;
    }

    public Player playerBySeat(int seat) {
        for (var p : players) {
            if (p.seat() == seat) return p;
        }
        throw new IllegalArgumentException("No such seat: " + seat);
    }

    public int cityCountFor(int seat) {
        int n = 0;
        for (var c : cities) if (c.ownerSeat() == seat) n++;
        return n;
    }

    public int unitCountFor(int seat) {
        int n = 0;
        for (var u : units) if (u.ownerSeat() == seat) n++;
        return n;
    }

    public int goldFor(int seat) {
        return playerGold.getOrDefault(seat, 0);
    }

    public List<HexCoord> plannedRouteFor(int unitId) {
        return List.copyOf(plannedRoutes.getOrDefault(unitId, List.of()));
    }

    public boolean assignPlannedRoute(int unitId, List<HexCoord> fullPath) {
        var opt = unitById(unitId);
        if (opt.isEmpty()) return false;
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return false;
        if (fullPath == null || fullPath.size() < 2) return false;
        if (!u.coord().equals(fullPath.get(0))) return false;
        var remaining = new ArrayList<HexCoord>();
        for (int i = 1; i < fullPath.size(); i++) remaining.add(fullPath.get(i));
        plannedRoutes.put(unitId, remaining);
        return true;
    }

    public boolean clearPlannedRoute(int unitId) {
        return plannedRoutes.remove(unitId) != null;
    }

    public boolean followPlannedRoute(int unitId) {
        var opt = unitById(unitId);
        if (opt.isEmpty()) {
            plannedRoutes.remove(unitId);
            return false;
        }
        Unit u = opt.get();
        var route = plannedRoutes.get(unitId);
        if (route == null || route.isEmpty()) return false;
        boolean moved = false;
        followingPlannedRouteNow = true;
        while (!route.isEmpty() && u.movesRemaining() > 0) {
            HexCoord step = route.get(0);
            if (!tryMoveUnit(u.id(), step)) break;
            moved = true;
            route.remove(0);
        }
        followingPlannedRouteNow = false;
        if (route.isEmpty()) plannedRoutes.remove(unitId);
        return moved;
    }

    public Optional<Unit> unitAt(HexCoord c) {
        for (var u : units) if (u.coord().equals(c)) return Optional.of(u);
        return Optional.empty();
    }

    public Optional<City> cityAt(HexCoord c) {
        for (var ct : cities) if (ct.coord().equals(c)) return Optional.of(ct);
        return Optional.empty();
    }

    public Optional<Unit> unitById(int id) {
        for (var u : units) if (u.id() == id) return Optional.of(u);
        return Optional.empty();
    }

    public Optional<City> cityById(int id) {
        for (var c : cities) if (c.id() == id) return Optional.of(c);
        return Optional.empty();
    }

    // === Fog of war ===

    /** Tiles the player has ever seen (persistent). */
    public Set<HexCoord> visitedFor(int seat) {
        return Collections.unmodifiableSet(visited.getOrDefault(seat, Set.of()));
    }

    /** Tiles currently within sight of any owned unit/city. */
    public Set<HexCoord> visibleFor(int seat) {
        var out = new HashSet<HexCoord>();
        for (var u : units) {
            if (u.ownerSeat() == seat) {
                Weather uw = weatherAt(u.coord());
                int eff = Math.max(
                        1,
                        u.kind().sightRadius() - uw.sightPenalty(u.kind().weatherResilience()));
                addDisk(out, u.coord(), eff);
            }
        }
        for (var c : cities) {
            if (c.ownerSeat() == seat) {
                Weather cw = weatherAt(c.coord());
                int baseCitySight = 2;
                int eff = Math.max(
                        1,
                        baseCitySight - cw.sightPenalty(cityWeatherResilience(c)));
                addDisk(out, c.coord(), eff);
            }
        }
        out.removeIf(h -> !map.contains(h));
        return out;
    }

    private void addDisk(Set<HexCoord> out, HexCoord center, int radius) {
        for (int dq = -radius; dq <= radius; dq++) {
            int rMin = Math.max(-radius, -dq - radius);
            int rMax = Math.min(radius, -dq + radius);
            for (int dr = rMin; dr <= rMax; dr++) {
                out.add(new HexCoord(center.q() + dq, center.r() + dr));
            }
        }
    }

    private void updateVisitedFor(int seat) {
        var v = visited.computeIfAbsent(seat, k -> new HashSet<>());
        v.addAll(visibleFor(seat));
    }

    // === Movement ===

    /** Legal one-tile moves for {@code u} on its owner's turn, respecting terrain cost. */
    public List<HexCoord> legalMoves(Unit u) {
        if (isOver() || u.ownerSeat() != currentPlayer().seat() || u.movesRemaining() <= 0) {
            return List.of();
        }
        var moves = new ArrayList<HexCoord>();
        for (var n : u.coord().neighbors()) {
            if (!map.contains(n) || !terrainEffectiveAt(n).passable()) continue;
            int cost = movementCostForStep(u, n);
            if (cost > u.movesRemaining()) continue;
            // Tile occupied by any unit blocks (own or enemy)
            if (unitAt(n).isPresent()) continue;
            if (wildAnimalAt(n).isPresent() && u.kind() != UnitKind.HUNTING_PARTY) continue;
            // Enemy city only enterable if no defender (handled by unitAt above).
            // Own city is fine.
            moves.add(n);
        }
        return moves;
    }

    /**
     * Auto-explore is on and the unit still has MP, but every neighboring tile is blocked or too
     * expensive — so exploration cannot advance until the player turns auto-explore off, skips MP,
     * or fortifies (human UI), or the AI disables explore.
     */
    public boolean autoExploreBlockedWithMoves(Unit u) {
        return u.autoExplore()
                && u.ownerSeat() == currentPlayer().seat()
                && u.movesRemaining() > 0
                && legalMoves(u).isEmpty();
    }

    /** Any friendly auto-explore unit for the current player is blocked with movement left. */
    public boolean currentPlayerHasAutoExploreBlockedWithMoves() {
        if (isOver()) return false;
        int seat = currentPlayer().seat();
        for (Unit u : units) {
            if (u.ownerSeat() != seat) continue;
            if (autoExploreBlockedWithMoves(u)) return true;
        }
        return false;
    }

    public boolean tryMoveUnit(int unitId, HexCoord dest) {
        if (isOver()) return false;
        var opt = unitById(unitId);
        if (opt.isEmpty()) return false;
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return false;
        if (u.sleeping()) return false;
        if (u.coord().distanceTo(dest) != 1) return false;
        if (!map.contains(dest) || !terrainEffectiveAt(dest).passable()) return false;
        int cost = movementCostForStep(u, dest);
        if (cost > u.movesRemaining()) return false;
        if (unitAt(dest).isPresent()) return false;
        if (wildAnimalAt(dest).isPresent() && u.kind() != UnitKind.HUNTING_PARTY) return false;

        u.setCoord(dest);
        u.spendMoves(cost);
        if (!followingPlannedRouteNow && plannedRoutes.containsKey(unitId)) {
            plannedRoutes.remove(unitId);
        }

        // Capture undefended enemy city by walking onto it
        var cityHere = cityAt(dest);
        if (cityHere.isPresent() && cityHere.get().ownerSeat() != u.ownerSeat()) {
            cityHere.get().captureBy(u.ownerSeat());
            checkWin();
        }

        updateVisitedFor(u.ownerSeat());
        return true;
    }

    /** Toggle auto-explore; clears any queued route when enabling. */
    public void setUnitAutoExplore(int unitId, boolean on) {
        var opt = unitById(unitId);
        if (opt.isEmpty()) return;
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return;
        u.setAutoExplore(on);
        if (on) {
            u.setSleeping(false);
            plannedRoutes.remove(unitId);
        }
    }

    /** Toggle sleeping for current player's unit; sleeping units are ignored by "needs orders". */
    public void setUnitSleeping(int unitId, boolean on) {
        var opt = unitById(unitId);
        if (opt.isEmpty()) return;
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return;
        u.setSleeping(on);
        if (on) {
            u.setAutoExplore(false);
            plannedRoutes.remove(unitId);
        }
    }

    /**
     * Moves all friendly units with auto-explore until they run out of moves (current player only).
     * Call when the player ends their turn.
     */
    public void runAutoExploreForCurrentPlayer() {
        if (isOver()) return;
        var snapshot = new ArrayList<>(units);
        for (Unit u : snapshot) {
            if (u.ownerSeat() != currentPlayer().seat() || !u.autoExplore() || u.sleeping()) {
                continue;
            }
            int guard = 0;
            while (u.movesRemaining() > 0 && guard++ < 512) {
                if (!runAutoExploreStep(u)) {
                    break;
                }
            }
        }
    }

    private boolean runAutoExploreStep(Unit u) {
        if (!u.autoExplore() || u.sleeping() || u.ownerSeat() != currentPlayer().seat() || u.movesRemaining() <= 0) {
            return false;
        }
        var legal = legalMoves(u);
        if (legal.isEmpty()) {
            return false;
        }
        int seat = u.ownerSeat();
        var seen = visitedFor(seat);

        // Civ-like: strongly prefer stepping onto a tile you've never revealed yet (directly into the fog).
        var intoFog = new ArrayList<HexCoord>();
        var overMapped = new ArrayList<HexCoord>();
        for (HexCoord n : legal) {
            if (!seen.contains(n)) {
                intoFog.add(n);
            } else {
                overMapped.add(n);
            }
        }
        if (!intoFog.isEmpty()) {
            HexCoord pick = null;
            int bestCost = Integer.MAX_VALUE;
            for (HexCoord n : intoFog) {
                int cost = movementCostForStep(u, n);
                if (pick == null || cost < bestCost || (cost == bestCost && hexTieBreak(n, pick) < 0)) {
                    bestCost = cost;
                    pick = n;
                }
            }
            return tryMoveUnit(u.id(), pick);
        }

        // Surrounded by explored tiles only: steer toward the nearest still-hidden area (hex distance proxy).
        HexCoord best = null;
        int bestDist = Integer.MAX_VALUE;
        for (HexCoord n : overMapped) {
            int d = minDistanceToUnvisitedPassable(n, seat);
            if (best == null || d < bestDist || (d == bestDist && hexTieBreak(n, best) < 0)) {
                bestDist = d;
                best = n;
            }
        }
        if (bestDist == Integer.MAX_VALUE) {
            legal.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
            best = legal.get(0);
        }
        return tryMoveUnit(u.id(), best);
    }

    private static int hexTieBreak(HexCoord a, HexCoord b) {
        return Integer.compare(a.q() * 409 + a.r(), b.q() * 409 + b.r());
    }

    /** Minimum hex distance from {@code from} to any passable tile not yet visited by {@code seat}. */
    private int minDistanceToUnvisitedPassable(HexCoord from, int seat) {
        var seen = visitedFor(seat);
        int min = Integer.MAX_VALUE;
        for (HexCoord c : map.allCells()) {
            if (seen.contains(c)) continue;
            if (!terrainEffectiveAt(c).passable()) continue;
            min = Math.min(min, from.distanceTo(c));
        }
        return min;
    }

    // === Combat ===

    /** Adjacent enemy unit tiles attackable by {@code u}. */
    public List<HexCoord> legalAttacks(Unit u) {
        if (isOver() || u.ownerSeat() != currentPlayer().seat()
                || u.movesRemaining() <= 0
                || u.kind().attackStrength() <= 0) {
            return List.of();
        }
        var out = new ArrayList<HexCoord>();
        for (var n : u.coord().neighbors()) {
            if (!map.contains(n)) continue;
            var enemy = unitAt(n);
            if (enemy.isPresent() && enemy.get().ownerSeat() != u.ownerSeat()) {
                out.add(n);
            }
        }
        return out;
    }

    /** Resolve an attack: returns a string describing the outcome, or empty if illegal. */
    public Optional<String> tryAttack(int attackerId, HexCoord target) {
        if (isOver()) return Optional.empty();
        var aOpt = unitById(attackerId);
        if (aOpt.isEmpty()) return Optional.empty();
        Unit attacker = aOpt.get();
        if (attacker.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (attacker.movesRemaining() <= 0) return Optional.empty();
        if (attacker.kind().attackStrength() <= 0) return Optional.empty();
        if (attacker.coord().distanceTo(target) != 1) return Optional.empty();
        var dOpt = unitAt(target);
        if (dOpt.isEmpty() || dOpt.get().ownerSeat() == attacker.ownerSeat()) {
            return Optional.empty();
        }
        Unit defender = dOpt.get();

        int atkRoll = attacker.kind().attackStrength() + combatRng.nextInt(3) - 1;
        combatRngCallCount++;
        int defRoll = defender.kind().attackStrength() + combatRng.nextInt(3) - 1;
        combatRngCallCount++;
        int dmgToDef = Math.max(1, atkRoll);
        int dmgToAtk = Math.max(0, defRoll - 2);

        defender.takeDamage(dmgToDef);
        attacker.takeDamage(dmgToAtk);
        attacker.exhaustMoves();

        var msg = new StringBuilder();
        msg.append(attacker.kind().displayName()).append(" hits ")
                .append(defender.kind().displayName()).append(" for ").append(dmgToDef);
        if (dmgToAtk > 0) {
            msg.append(", takes ").append(dmgToAtk).append(" back");
        }

        if (defender.isDead()) {
            units.remove(defender);
            msg.append(" — defender destroyed");
        }
        if (attacker.isDead()) {
            units.remove(attacker);
            msg.append(" — attacker destroyed");
        }

        updateVisitedFor(attacker.ownerSeat());
        checkWin();
        return Optional.of(msg.toString());
    }

    // === Cities ===

    public boolean canFoundCity(Unit u) {
        if (isOver()) return false;
        if (u.kind() != UnitKind.SETTLER) return false;
        if (u.ownerSeat() != currentPlayer().seat()) return false;
        if (u.movesRemaining() <= 0) return false;
        if (cityAt(u.coord()).isPresent()) return false;
        if (!terrainEffectiveAt(u.coord()).canFoundCityOn()) return false;
        for (var nb : u.coord().neighbors()) {
            if (cityAt(nb).isPresent()) return false;
        }
        return true;
    }

    /**
     * Why founding is blocked for this unit (empty when {@link #canFoundCity(Unit)} is true).
     * For UI tooltips and toasts.
     */
    public Optional<String> explainCannotFoundCity(Unit u) {
        if (canFoundCity(u)) {
            return Optional.empty();
        }
        if (isOver()) {
            return Optional.of("Cannot found cities after the game has ended.");
        }
        if (u.kind() != UnitKind.SETTLER) {
            return Optional.of("Only settlers can found a city.");
        }
        if (u.ownerSeat() != currentPlayer().seat()) {
            return Optional.of("That unit is not under your control.");
        }
        if (u.movesRemaining() <= 0) {
            return Optional.of("Need at least one movement point remaining to found a city.");
        }
        if (cityAt(u.coord()).isPresent()) {
            return Optional.of("A city already occupies this tile.");
        }
        if (!terrainEffectiveAt(u.coord()).canFoundCityOn()) {
            return Optional.of("Cannot found on this terrain.");
        }
        for (var nb : u.coord().neighbors()) {
            if (cityAt(nb).isPresent()) {
                return Optional.of("Cannot found adjacent to another city.");
            }
        }
        return Optional.of("Cannot found a city here.");
    }

    public Optional<City> foundCity(int unitId) {
        var opt = unitById(unitId);
        if (opt.isEmpty()) return Optional.empty();
        Unit u = opt.get();
        if (!canFoundCity(u)) return Optional.empty();
        String name = nextFoundedCityName();
        var city = new City(nextCityId++, u.ownerSeat(), u.coord(), name, null);
        cities.add(city);
        units.remove(u);
        updateVisitedFor(city.ownerSeat());
        checkWin();
        return Optional.of(city);
    }

    private String nextFoundedCityName() {
        if (cityNameCursor >= cityNameDeck.size()) {
            return "Outpost " + nextCityId;
        }
        return cityNameDeck.get(cityNameCursor++);
    }

    public boolean setCityProduction(int cityId, UnitKind kind) {
        if (isOver()) return false;
        var opt = cityById(cityId);
        if (opt.isEmpty()) return false;
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return false;
        if (kind == UnitKind.HUNTING_PARTY && c.population() < 2) return false;
        c.setCurrentBuild(kind);
        return true;
    }

    public boolean enqueueCityProduction(int cityId, UnitKind kind) {
        if (isOver()) return false;
        var opt = cityById(cityId);
        if (opt.isEmpty()) return false;
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return false;
        if (kind == UnitKind.HUNTING_PARTY && c.population() < 2) return false;
        c.enqueueBuild(kind);
        return true;
    }

    public boolean removeCityQueuedProduction(int cityId, int queueIndex) {
        if (isOver()) return false;
        var opt = cityById(cityId);
        if (opt.isEmpty()) return false;
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return false;
        return c.removeQueuedBuild(queueIndex);
    }

    public boolean moveCityQueuedProduction(int cityId, int queueIndex, int delta) {
        if (isOver()) return false;
        var opt = cityById(cityId);
        if (opt.isEmpty()) return false;
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return false;
        return c.moveQueuedBuild(queueIndex, delta);
    }

    // === Turns ===

    public void endTurn() {
        if (isOver()) return;
        int seat = currentPlayer().seat();
        var autoMovedUnitIds = new HashSet<Integer>();
        for (var u : new ArrayList<>(units)) {
            if (u.ownerSeat() == seat && plannedRoutes.containsKey(u.id())) {
                if (followPlannedRoute(u.id())) {
                    autoMovedUnitIds.add(u.id());
                }
            }
        }
        for (var uid : autoMovedUnitIds) {
            var uOpt = unitById(uid);
            if (uOpt.isPresent() && uOpt.get().ownerSeat() == seat && uOpt.get().movesRemaining() > 0) {
                return; // keep same player; auto-move left actions to spend
            }
        }
        int next = (currentPlayerIndex + 1) % players.size();
        if (next == 0) {
            advanceAllHuntMissions();
            round++;
            chronologyOffsetYears += yearsPerFullRound;
            weatherNonce++;
            Season tickSeason = Chronology.seasonFromElapsedYears(chronologyOffsetYears, yearsPerFullRound);
            RegionalWeather.TickResult tr = RegionalWeather.tick(
                    weatherSystems,
                    map,
                    tickSeason,
                    worldSeed,
                    round,
                    weatherNonce,
                    nextWeatherPatchId);
            weatherSystems.clear();
            weatherSystems.addAll(tr.systems());
            nextWeatherPatchId = tr.nextId();
            stepWildlifeTurn();
        }
        currentPlayerIndex = next;
        startTurn();
    }

    private void startTurn() {
        int seat = currentPlayer().seat();
        refreshMovesFor(seat);
        produceFor(seat);
        updateVisitedFor(seat);
        checkWin();
    }

    private void refreshMovesFor(int seat) {
        for (var u : units) {
            if (u.ownerSeat() == seat) u.refreshMoves();
        }
    }

    private void produceFor(int seat) {
        var owned = new ArrayList<City>();
        for (var c : cities) if (c.ownerSeat() == seat) owned.add(c);
        for (var c : owned) {
            CityYield y = cityYield(c);
            c.applyFoodYield(y.food());
            c.addProduction(y.production());
            playerGold.merge(seat, y.gold(), Integer::sum);
            c.drainCompleted().ifPresent(kind -> spawnFromCity(c, kind));
        }
    }

    public CityYield cityYield(City c) {
        int cityRes = cityWeatherResilience(c);
        HexCoord centerCoord = c.coord();
        int food = yieldFoodAtTile(centerCoord, cityRes);
        int production = yieldProdAtTile(1, centerCoord, cityRes)
                + yieldProdAtTile(tileProductionYield(centerCoord), centerCoord, cityRes);
        int gold = yieldGoldAtTile(tileGoldYield(centerCoord), centerCoord, cityRes);
        if (c.hasBuilding(CityBuilding.GRANARY)) {
            food += 1;
        }
        if (c.hasBuilding(CityBuilding.WORKSHOP)) {
            production += 1;
        }
        if (c.hasBuilding(CityBuilding.MARKET)) {
            gold += 1;
        }

        int workers = Math.max(0, c.population() - 1);
        if (workers > 0) {
            var tiles = new ArrayList<HexCoord>();
            for (var n : c.coord().neighbors()) {
                if (map.contains(n)
                        && terrainEffectiveAt(n).passable()
                        && claimedOwnerAt(n).filter(seat -> seat == c.ownerSeat()).isPresent()) {
                    tiles.add(n);
                }
            }
            tiles.sort((a, b) -> Integer.compare(tileYieldScoreForFocus(b, c.focus()), tileYieldScoreForFocus(a, c.focus())));
            for (int i = 0; i < Math.min(workers, tiles.size()); i++) {
                HexCoord wc = tiles.get(i);
                food += yieldFoodAtTile(wc, cityRes);
                production += yieldProdAtTile(tileProductionYield(wc), wc, cityRes);
                gold += yieldGoldAtTile(tileGoldYield(wc), wc, cityRes);
            }
        }
        return new CityYield(food, production, gold);
    }

    /**
     * Estimated city yields if a city existed at {@code center} with the given population and focus.
     * Uses the same weather mitigation as real cities. Neighbor tiles are chosen from passable
     * adjacent hexes by focus score (optimistic — ignores territorial claim conflicts).
     */
    public CityYield previewCityYieldAt(HexCoord center, int population, CityFocus focus) {
        if (!map.contains(center) || !terrainEffectiveAt(center).passable()) {
            return new CityYield(0, 0, 0);
        }
        int pop = Math.max(1, population);
        int cityRes = Math.min(4, Math.max(1, 1 + pop / 4));
        int food = yieldFoodAtTile(center, cityRes);
        int production = yieldProdAtTile(1, center, cityRes)
                + yieldProdAtTile(tileProductionYield(center), center, cityRes);
        int gold = yieldGoldAtTile(tileGoldYield(center), center, cityRes);
        int workers = Math.max(0, pop - 1);
        if (workers > 0) {
            var tiles = new ArrayList<HexCoord>();
            for (var n : center.neighbors()) {
                if (map.contains(n) && terrainEffectiveAt(n).passable()) {
                    tiles.add(n);
                }
            }
            tiles.sort((a, b) -> Integer.compare(tileYieldScoreForFocus(b, focus), tileYieldScoreForFocus(a, focus)));
            for (int i = 0; i < Math.min(workers, tiles.size()); i++) {
                HexCoord wc = tiles.get(i);
                food += yieldFoodAtTile(wc, cityRes);
                production += yieldProdAtTile(tileProductionYield(wc), wc, cityRes);
                gold += yieldGoldAtTile(tileGoldYield(wc), wc, cityRes);
            }
        }
        return new CityYield(food, production, gold);
    }

    /**
     * Like {@link #previewCityYieldAt} but only counts adjacent tiles your empire could work today
     * (unclaimed or already your claim). Excludes tiles clearly in another seat's claim.
     */
    public CityYield previewCityYieldRealistic(HexCoord center, int population, CityFocus focus, int ownerSeat) {
        if (!map.contains(center) || !terrainEffectiveAt(center).passable()) {
            return new CityYield(0, 0, 0);
        }
        int pop = Math.max(1, population);
        int cityRes = Math.min(4, Math.max(1, 1 + pop / 4));
        int food = yieldFoodAtTile(center, cityRes);
        int production = yieldProdAtTile(1, center, cityRes)
                + yieldProdAtTile(tileProductionYield(center), center, cityRes);
        int gold = yieldGoldAtTile(tileGoldYield(center), center, cityRes);
        int workers = Math.max(0, pop - 1);
        if (workers > 0) {
            var tiles = new ArrayList<HexCoord>();
            for (var n : center.neighbors()) {
                if (!map.contains(n) || !terrainEffectiveAt(n).passable()) continue;
                var claim = claimedOwnerAt(n);
                if (claim.isPresent() && claim.get() != ownerSeat) {
                    continue;
                }
                tiles.add(n);
            }
            tiles.sort((a, b) -> Integer.compare(tileYieldScoreForFocus(b, focus), tileYieldScoreForFocus(a, focus)));
            for (int i = 0; i < Math.min(workers, tiles.size()); i++) {
                HexCoord wc = tiles.get(i);
                food += yieldFoodAtTile(wc, cityRes);
                production += yieldProdAtTile(tileProductionYield(wc), wc, cityRes);
                gold += yieldGoldAtTile(tileGoldYield(wc), wc, cityRes);
            }
        }
        return new CityYield(food, production, gold);
    }

    /** Non-empty when the city cannot grow this turn due to food (weather/upkeep context). */
    public Optional<String> explainFoodStagnation(City c) {
        int upkeep = 1 + c.population();
        CityYield y = cityYield(c);
        int surplus = y.food() - upkeep;
        if (surplus > 0) {
            return Optional.empty();
        }
        Weather wx = weatherAt(c.coord());
        var sb = new StringBuilder();
        sb.append("Growth stalled: ").append(upkeep).append(" food upkeep vs ").append(y.food())
                .append("/turn from tiles.");
        if (wx != Weather.CLEAR) {
            sb.append(" Weather on city tile (").append(wx.label()).append(") reduces yields.");
        }
        return Optional.of(sb.toString());
    }

    private int yieldFoodAtTile(HexCoord tile, int cityRes) {
        int raw = tileFoodYield(tile);
        int pct = weatherAt(tile).mitigatedCityFoodPercent(cityRes);
        return Math.max(0, (raw * (100 + pct)) / 100);
    }

    private int yieldProdAtTile(int raw, HexCoord tile, int cityRes) {
        int pct = weatherAt(tile).mitigatedCityProductionPercent(cityRes);
        return Math.max(0, (raw * (100 + pct)) / 100);
    }

    private int yieldGoldAtTile(int raw, HexCoord tile, int cityRes) {
        int pct = weatherAt(tile).mitigatedCityGoldPercent(cityRes);
        return Math.max(0, (raw * (100 + pct)) / 100);
    }

    public int tileFoodYield(HexCoord c) {
        if (!map.contains(c)) return 0;
        Terrain t = terrainEffectiveAt(c);
        int n = t.foodYield();
        n += soilFertilityBonus.getOrDefault(c, 0);
        n += cultivationTier.getOrDefault(c, 0);
        if (improvementAt(c) == TileImprovement.FARM) {
            n += 2;
        }
        return n;
    }

    public int tileProductionYield(HexCoord c) {
        if (!map.contains(c)) return 0;
        Terrain t = terrainEffectiveAt(c);
        int n = t.productionYield();
        if (improvementAt(c) == TileImprovement.MINE) {
            n += 2;
        }
        return n;
    }

    public int tileGoldYield(HexCoord c) {
        return map.contains(c) ? terrainEffectiveAt(c).goldYield() : 0;
    }

    public int soilFertilityAt(HexCoord c) {
        return soilFertilityBonus.getOrDefault(c, 0);
    }

    public int cultivationAt(HexCoord c) {
        return cultivationTier.getOrDefault(c, 0);
    }

    public TileImprovement improvementAt(HexCoord c) {
        return tileImprovements.getOrDefault(c, TileImprovement.NONE);
    }

    public List<WildAnimal> wildlife() {
        return Collections.unmodifiableList(wildlife);
    }

    /** How many wildlife units arrived during the most recent wildlife step. */
    public int wildlifeSpawnedLastStep() {
        return wildlifeSpawnedLastStep;
    }

    /** Round marker for {@link #wildlifeSpawnedLastStep()}; used to avoid duplicate UI toasts/logs. */
    public int wildlifeSpawnedLastStepRound() {
        return wildlifeSpawnedLastStepRound;
    }

    private int tileYieldScore(HexCoord c) {
        return tileProductionYield(c) * 2 + tileFoodYield(c) * 2 + tileGoldYield(c);
    }

    private int tileYieldScoreForFocus(HexCoord c, CityFocus focus) {
        int food = tileFoodYield(c);
        int prod = tileProductionYield(c);
        int gold = tileGoldYield(c);
        return switch (focus) {
            // Tuned to be meaningful without making off-focus yields irrelevant.
            case FOOD -> food * 4 + prod * 2 + gold;
            case PRODUCTION -> prod * 4 + food * 2 + gold;
            case GOLD -> gold * 4 + food * 2 + prod * 2;
            case BALANCED -> tileYieldScore(c);
        };
    }

    public Optional<String> setCityFocus(int cityId, CityFocus focus) {
        if (isOver()) return Optional.empty();
        var opt = cityById(cityId);
        if (opt.isEmpty()) return Optional.empty();
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        c.setFocus(focus);
        return Optional.of(c.name() + " now focuses " + c.focus().name().toLowerCase() + ".");
    }

    public Optional<String> tryConstructCityBuilding(int cityId, CityBuilding building) {
        if (isOver()) return Optional.empty();
        var opt = cityById(cityId);
        if (opt.isEmpty()) return Optional.empty();
        City c = opt.get();
        if (c.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (building == null) return Optional.empty();
        if (c.hasBuilding(building)) {
            return Optional.of(c.name() + " already has a " + building.label() + ".");
        }
        int seat = c.ownerSeat();
        int gold = playerGold.getOrDefault(seat, 0);
        if (gold < building.goldCost()) {
            return Optional.of("Need " + building.goldCost() + " gold to construct " + building.label() + ".");
        }
        playerGold.put(seat, gold - building.goldCost());
        c.addBuilding(building);
        return Optional.of(c.name() + " constructed " + building.label() + ".");
    }

    public int cityClaimRadius(City c) {
        if (c == null) return 0;
        int pop = c.population();
        if (pop >= 8) return 3;
        if (pop >= 4) return 2;
        return 1;
    }

    /**
     * Approximate ownership for rendering/working tiles.
     * Uses nearest city, then tie-breaks by city population and finally seat id
     * so the map doesn't produce too many permanently unclaimed "dead" tiles.
     */
    public Optional<Integer> claimedOwnerAt(HexCoord h) {
        if (!map.contains(h) || !terrainEffectiveAt(h).passable()) return Optional.empty();
        int bestDist = Integer.MAX_VALUE;
        City bestCity = null;
        for (City c : cities) {
            int d = c.coord().distanceTo(h);
            if (d > cityClaimRadius(c)) continue;
            if (d < bestDist) {
                bestDist = d;
                bestCity = c;
            } else if (d == bestDist && bestCity != null) {
                if (c.population() > bestCity.population()) {
                    bestCity = c;
                } else if (c.population() == bestCity.population() && c.ownerSeat() < bestCity.ownerSeat()) {
                    bestCity = c;
                }
            }
        }
        if (bestCity == null) return Optional.empty();
        return Optional.of(bestCity.ownerSeat());
    }

    /** Human-readable claim attribution for tooltips/debug overlays. */
    public String claimDebugAt(HexCoord h) {
        if (h == null || !map.contains(h) || !terrainEffectiveAt(h).passable()) {
            return "Claim: none";
        }
        int bestDist = Integer.MAX_VALUE;
        City bestCity = null;
        for (City c : cities) {
            int d = c.coord().distanceTo(h);
            if (d > cityClaimRadius(c)) continue;
            if (d < bestDist) {
                bestDist = d;
                bestCity = c;
            } else if (d == bestDist && bestCity != null) {
                if (c.population() > bestCity.population()) {
                    bestCity = c;
                } else if (c.population() == bestCity.population() && c.ownerSeat() < bestCity.ownerSeat()) {
                    bestCity = c;
                }
            }
        }
        if (bestCity == null) return "Claim: none";
        String owner = playerBySeat(bestCity.ownerSeat()).name();
        int radius = cityClaimRadius(bestCity);
        return "Claim: " + owner + " via " + bestCity.name()
                + " (d=" + bestDist + ", r=" + radius + ", pop=" + bestCity.population() + ")";
    }

    private boolean isInsideOwnBorder(HexCoord c, int seat) {
        return claimedOwnerAt(c).filter(owner -> owner == seat).isPresent();
    }

    private void spawnFromCity(City c, UnitKind kind) {
        if (kind == UnitKind.HUNTING_PARTY && !c.consumePopulationForParty()) {
            c.addProduction(kind.productionCost());
            return;
        }
        HexCoord placement = findFreeAdjacent(c.coord());
        if (placement == null) {
            if (kind == UnitKind.HUNTING_PARTY) {
                c.returnPopulationFromParty();
            }
            c.addProduction(kind.productionCost());
            return;
        }
        var u = new Unit(nextUnitId++, c.ownerSeat(), kind, placement);
        u.exhaustMoves();
        units.add(u);
    }

    private HexCoord findFreeAdjacent(HexCoord origin) {
        if (map.contains(origin) && terrainEffectiveAt(origin).passable()
                && unitAt(origin).isEmpty() && wildAnimalAt(origin).isEmpty()) {
            return origin;
        }
        for (var n : origin.neighbors()) {
            if (!map.contains(n) || !terrainEffectiveAt(n).passable()) continue;
            if (unitAt(n).isPresent()) continue;
            if (wildAnimalAt(n).isPresent()) continue;
            return n;
        }
        return null;
    }

    public Optional<WildAnimal> wildAnimalAt(HexCoord c) {
        for (var a : wildlife) if (a.coord().equals(c)) return Optional.of(a);
        return Optional.empty();
    }

    private void advanceAllHuntMissions() {
        for (City c : cities) {
            for (int id : c.advanceHuntMissions()) {
                wildlife.removeIf(w -> w.id() == id);
            }
        }
    }

    /** Upper bound on wildlife count; kept ~¼ of the old cap alongside {@link #targetWildlifeCount()}. */
    private static final int MAX_WILDLIFE_HARD_CAP = 12;
    private static final int MAX_CONSECUTIVE_CITY_RAIDS = 2;
    private static final double WILDLIFE_ATTACK_UNIT_CHANCE = 0.30;
    private static final double WILDLIFE_ATTACK_CITY_CHANCE = 0.10;
    /** After declining a same-tile attack, rarely linger — prefer wandering. */
    private static final double WILDLIFE_LINGER_ON_UNIT_TILE_CHANCE = 0.05;

    private void stepWildlifeTurn() {
        wildlifeSpawnNonce++;
        wildlifeSpawnedLastStep = maybeSpawnWildlifeThisRound();
        wildlifeSpawnedLastStepRound = round;
        if (wildlife.isEmpty()) {
            return;
        }
        Random orderRng = wildRng(round, 0x5EED);
        var order = new ArrayList<>(wildlife);
        Collections.shuffle(order, orderRng);
        for (WildAnimal a : order) {
            if (!a.isDead()) {
                stepOneWildAnimal(a, wildRng(round, a.id() ^ 0xDEADBEEF));
            }
        }
        wildlife.removeIf(WildAnimal::isDead);
        wildlifeCityRaidStreak.keySet().removeIf(id -> wildlife.stream().noneMatch(w -> w.id() == id));
    }

    /** Target wildlife population: ~¼ of the former density (about one animal per 40 land tiles). */
    private int targetWildlifeCount() {
        int landTiles = map.passableLand().size();
        int densityTarget = Math.max(1, (landTiles + 39) / 40); // ceil(land/40)
        return Math.min(MAX_WILDLIFE_HARD_CAP, densityTarget);
    }

    /**
     * Refill toward target wildlife density. Uses deterministic RNG so save/load behavior remains stable.
     */
    private int maybeSpawnWildlifeThisRound() {
        int target = targetWildlifeCount();
        if (wildlife.size() >= target) {
            return 0;
        }
        Random s = new Random(mix64World(worldSeed, wildlifeSpawnNonce, round ^ 0xA11CEL));
        int spawned = 0;
        int deficit = target - wildlife.size();
        // Refill pace tuned down ~25% to reduce wildlife spawn pressure.
        int pulses = Math.min(8, Math.max(1, (deficit * 3 + 3) / 6));
        for (int i = 0; i < pulses && wildlife.size() < target; i++) {
            boolean herd = s.nextDouble() < 0.68;
            int size = herd ? (2 + s.nextInt(4)) : 1; // herd 2..5, lone 1
            int room = target - wildlife.size();
            int requested = Math.max(1, Math.min(size, room));
            spawned += trySpawnWildlifeGroup(requested, s);
        }
        return spawned;
    }

    private WildAnimalKind pickSpawnKind(HexCoord anchor, Random s) {
        if (s.nextDouble() < 0.55) {
            return WildAnimalKind.pickFullyRandom(s);
        }
        return WildAnimalKind.pickForTerrain(terrainEffectiveAt(anchor), s);
    }

    private boolean isWildSpawnAnchorOk(HexCoord anchor) {
        if (!map.contains(anchor) || !terrainEffectiveAt(anchor).passable()) {
            return false;
        }
        if (unitAt(anchor).isPresent() || cityAt(anchor).isPresent() || wildAnimalAt(anchor).isPresent()) {
            return false;
        }
        for (WildAnimal a : wildlife) {
            if (a.coord().distanceTo(anchor) < 2) {
                return false;
            }
        }
        return true;
    }

    private boolean canExtendHerdTile(HexCoord n, LinkedHashSet<HexCoord> batch) {
        if (!map.contains(n) || !terrainEffectiveAt(n).passable()) {
            return false;
        }
        if (unitAt(n).isPresent() || cityAt(n).isPresent() || wildAnimalAt(n).isPresent()) {
            return false;
        }
        for (HexCoord b : batch) {
            if (n.distanceTo(b) == 1) {
                return true;
            }
        }
        return false;
    }

    private void expandHerdBatch(LinkedHashSet<HexCoord> batch, int targetSize, Random s) {
        while (batch.size() < targetSize) {
            var frontier = new ArrayList<>(batch);
            Collections.shuffle(frontier, s);
            HexCoord add = null;
            outer:
            for (HexCoord b : frontier) {
                var nbs = new ArrayList<>(b.neighbors());
                Collections.shuffle(nbs, s);
                for (HexCoord n : nbs) {
                    if (batch.contains(n)) {
                        continue;
                    }
                    if (canExtendHerdTile(n, batch)) {
                        add = n;
                        break outer;
                    }
                }
            }
            if (add == null) {
                break;
            }
            batch.add(add);
        }
    }

    private int trySpawnWildlifeGroup(int targetSize, Random s) {
        var land = new ArrayList<>(map.passableLand());
        Collections.shuffle(land, s);
        int maxTries = Math.min(land.size(), 200);
        for (int ai = 0; ai < maxTries; ai++) {
            HexCoord anchor = land.get(ai);
            if (!isWildSpawnAnchorOk(anchor)) {
                continue;
            }
            WildAnimalKind kind = pickSpawnKind(anchor, s);
            var batch = new LinkedHashSet<HexCoord>();
            batch.add(anchor);
            if (targetSize > 1) {
                expandHerdBatch(batch, targetSize, s);
            }
            if (targetSize > 1 && batch.size() < 2) {
                continue;
            }
            for (HexCoord c : batch) {
                wildlife.add(new WildAnimal(nextWildAnimalId++, kind, c));
            }
            return batch.size();
        }
        return 0;
    }

    private Random wildRng(int r, int salt) {
        return new Random(mix64World(worldSeed, r, salt));
    }

    private static long mix64World(long a, long b, long c) {
        long x = a ^ Long.rotateLeft(b, 21) ^ Long.rotateLeft(c, 42);
        x ^= x >>> 33;
        x *= 0xff51_afd7_ed55_8ccdL;
        x ^= x >>> 33;
        x *= 0xc4ce_b9fe_1a85_ec53L;
        x ^= x >>> 33;
        return x;
    }

    private void stepOneWildAnimal(WildAnimal a, Random rng) {
        HexCoord c = a.coord();
        unitAt(c).ifPresent(occupant -> {
            if (occupant.ownerSeat() >= 0 && rng.nextDouble() < WILDLIFE_ATTACK_UNIT_CHANCE) {
                wildAttackUnit(a, occupant, rng);
            }
        });
        if (a.isDead()) {
            return;
        }
        if (unitAt(c).isPresent() && rng.nextDouble() < WILDLIFE_LINGER_ON_UNIT_TILE_CHANCE) {
            return;
        }
        List<Unit> prey = new ArrayList<>();
        for (HexCoord n : c.neighbors()) {
            unitAt(n).ifPresent(u -> prey.add(u));
        }
        if (!prey.isEmpty() && rng.nextDouble() < WILDLIFE_ATTACK_UNIT_CHANCE) {
            Unit victim = prey.get(rng.nextInt(prey.size()));
            wildAttackUnit(a, victim, rng);
            wildlifeCityRaidStreak.put(a.id(), 0);
            return;
        }
        Optional<City> raid = Optional.empty();
        for (HexCoord n : c.neighbors()) {
            var ct = cityAt(n);
            if (ct.isPresent()) {
                raid = ct;
                break;
            }
        }
        int raidStreak = wildlifeCityRaidStreak.getOrDefault(a.id(), 0);
        if (raid.isPresent()
                && raidStreak < MAX_CONSECUTIVE_CITY_RAIDS
                && rng.nextDouble() < WILDLIFE_ATTACK_CITY_CHANCE) {
            wildRaidCity(a, raid.get(), rng);
            wildlifeCityRaidStreak.put(a.id(), raidStreak + 1);
            // Usually leave the city edge after a raid instead of camping.
        }
        List<HexCoord> legal = new ArrayList<>();
        for (HexCoord n : c.neighbors()) {
            if (!map.contains(n) || !terrainEffectiveAt(n).passable()) continue;
            if (unitAt(n).isPresent()) continue;
            if (wildAnimalAt(n).isPresent()) continue;
            if (cityAt(n).isPresent()) continue;
            legal.add(n);
        }
        if (legal.isEmpty()) {
            return;
        }
        HexCoord step = pickWildlifeWanderStep(legal, rng);
        a.setCoord(step);
        wildlifeCityRaidStreak.put(a.id(), 0);
    }

    private void wildAttackUnit(WildAnimal a, Unit u, Random rng) {
        int dmg = Math.max(1, a.kind().attack() + rng.nextInt(2) - 1);
        u.takeDamage(dmg);
        if (u.isDead()) {
            units.remove(u);
        }
    }

    private void wildRaidCity(WildAnimal a, City city, Random rng) {
        int dmg = Math.max(1, a.kind().attack() / 3 + rng.nextInt(2));
        city.applyWildlifeRaid(dmg);
    }

    /**
     * Wildlife roaming: no pull toward cities (only {@link #WILDLIFE_ATTACK_CITY_CHANCE} causes raids when
     * already adjacent). Prefers steps that increase distance from the nearest city, with jitter so herds
     * don’t march in rigid lines.
     */
    private HexCoord pickWildlifeWanderStep(List<HexCoord> legal, Random rng) {
        if (cities.isEmpty()) {
            return legal.get(rng.nextInt(legal.size()));
        }
        HexCoord best = legal.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (HexCoord n : legal) {
            int nearest = Integer.MAX_VALUE;
            for (City c : cities) {
                nearest = Math.min(nearest, n.distanceTo(c.coord()));
            }
            if (nearest == Integer.MAX_VALUE) {
                nearest = 999;
            }
            int s = nearest * 10 + rng.nextInt(14);
            if (s > bestScore || (s == bestScore && rng.nextBoolean())) {
                bestScore = s;
                best = n;
            }
        }
        return best;
    }

    public Optional<String> tryCultivateTile(int builderId) {
        if (isOver()) return Optional.empty();
        var opt = unitById(builderId);
        if (opt.isEmpty()) return Optional.empty();
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (u.kind() != UnitKind.FARMER) return Optional.empty();
        if (u.movesRemaining() <= 0) {
            return Optional.of("Farmer has no moves left this turn.");
        }
        HexCoord c = u.coord();
        if (!isInsideOwnBorder(c, u.ownerSeat())) {
            return Optional.of("Improvements can only be built inside your city borders.");
        }
        if (!terrainEffectiveAt(c).canCultivate()) {
            return Optional.of("This terrain cannot be cultivated (try grass, plains, or desert).");
        }
        int tier = cultivationTier.getOrDefault(c, 0);
        if (tier >= 2) {
            return Optional.of("This tile is already fully cultivated.");
        }
        cultivationTier.put(c, tier + 1);
        u.exhaustMoves();
        return Optional.of("Cultivated terrain (+1 farming tier).");
    }

    public Optional<String> tryClearForest(int builderId) {
        if (isOver()) return Optional.empty();
        var opt = unitById(builderId);
        if (opt.isEmpty()) return Optional.empty();
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (u.kind() != UnitKind.FARMER) return Optional.empty();
        if (u.movesRemaining() <= 0) {
            return Optional.of("Farmer has no moves left this turn.");
        }
        HexCoord c = u.coord();
        if (!isInsideOwnBorder(c, u.ownerSeat())) {
            return Optional.of("Improvements can only be built inside your city borders.");
        }
        if (terrainEffectiveAt(c) != Terrain.FOREST) {
            return Optional.of("Clear forest only works on forest tiles.");
        }
        terrainOverride.put(c, Terrain.GRASS);
        u.exhaustMoves();
        return Optional.of("Cleared forest — tile is now grassland.");
    }

    public Optional<String> tryBuildFarmImprovement(int builderId) {
        if (isOver()) return Optional.empty();
        var opt = unitById(builderId);
        if (opt.isEmpty()) return Optional.empty();
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (u.kind() != UnitKind.FARMER) return Optional.empty();
        if (u.movesRemaining() <= 0) {
            return Optional.of("Farmer has no moves left this turn.");
        }
        HexCoord c = u.coord();
        if (!isInsideOwnBorder(c, u.ownerSeat())) {
            return Optional.of("Improvements can only be built inside your city borders.");
        }
        Terrain t = terrainEffectiveAt(c);
        if (!t.canSupportFarm()) {
            return Optional.of("Farms can only be built on grass, plains, or desert (clear forest first).");
        }
        if (improvementAt(c) != TileImprovement.NONE) {
            return Optional.of("This tile already has an improvement.");
        }
        int cult = cultivationTier.getOrDefault(c, 0);
        int soil = soilFertilityBonus.getOrDefault(c, 0);
        if (cult < 1 && soil < 2) {
            return Optional.of("Need cultivation (use Cultivate) or naturally rich soil (soil +2) before a farm.");
        }
        tileImprovements.put(c, TileImprovement.FARM);
        u.exhaustMoves();
        return Optional.of("Built a farm (+2 food when this tile is worked).");
    }

    public Optional<String> tryBuildMineImprovement(int builderId) {
        if (isOver()) return Optional.empty();
        var opt = unitById(builderId);
        if (opt.isEmpty()) return Optional.empty();
        Unit u = opt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (u.kind() != UnitKind.BUILDER) return Optional.empty();
        if (u.movesRemaining() <= 0) {
            return Optional.of("Builder has no moves left this turn.");
        }
        HexCoord c = u.coord();
        if (!isInsideOwnBorder(c, u.ownerSeat())) {
            return Optional.of("Improvements can only be built inside your city borders.");
        }
        if (!terrainEffectiveAt(c).canSupportMine()) {
            return Optional.of("Mines can only be built on hills.");
        }
        if (improvementAt(c) != TileImprovement.NONE) {
            return Optional.of("This tile already has an improvement.");
        }
        tileImprovements.put(c, TileImprovement.MINE);
        u.exhaustMoves();
        return Optional.of("Built a mine (+2 production when worked).");
    }

    /** Only {@link UnitKind#HUNTING_PARTY} can hunt wildlife; must stand on the animal's tile. */
    public Optional<String> tryHuntWildlife(int hunterId, int animalId, HexCoord animalCoord) {
        if (isOver()) return Optional.empty();
        var uOpt = unitById(hunterId);
        if (uOpt.isEmpty()) return Optional.empty();
        Unit hunter = uOpt.get();
        if (hunter.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (hunter.kind() != UnitKind.HUNTING_PARTY) return Optional.empty();
        if (!hunter.coord().equals(animalCoord)) return Optional.empty();
        if (hunter.movesRemaining() <= 0) return Optional.empty();
        WildAnimal target = null;
        for (var a : wildlife) {
            if (a.id() == animalId && a.coord().equals(animalCoord)) {
                target = a;
                break;
            }
        }
        if (target == null || target.isDead()) return Optional.empty();
        int atk = hunter.kind().attackStrength() + combatRng.nextInt(3) - 1;
        combatRngCallCount++;
        int wxBonus = weatherAt(animalCoord).extraWildDamage(target.kind().weatherResilience());
        int dmgToAnimal = Math.max(1, atk + wxBonus);
        target.takeDamage(dmgToAnimal);
        int foodFromHit = Math.max(1, dmgToAnimal / 2);
        hunter.addCarriedFood(foodFromHit);
        int retaliation = Math.max(0, target.kind().attack() / 2 + combatRng.nextInt(2) - 1);
        combatRngCallCount++;
        hunter.takeDamage(retaliation);
        hunter.exhaustMoves();
        var msg = new StringBuilder(hunter.kind().displayName()).append(" hunts for ").append(dmgToAnimal);
        if (retaliation > 0) {
            msg.append(", takes ").append(retaliation);
        }
        if (target.isDead()) {
            wildlife.remove(target);
            msg.append(" — ").append(target.kind().label()).append(" eliminated");
            int killBonusFood = wildlifeFoodValue(target.kind());
            hunter.addCarriedFood(killBonusFood);
            msg.append(" (+").append(foodFromHit + killBonusFood).append(" carried food)");
        } else {
            msg.append(" (+").append(foodFromHit).append(" carried food)");
        }
        if (hunter.isDead()) {
            units.remove(hunter);
            msg.append(" — hunter fallen");
        }
        updateVisitedFor(hunter.ownerSeat());
        checkWin();
        return Optional.of(msg.toString());
    }

    public Optional<String> tryRebaseHuntingParty(int unitId) {
        if (isOver()) return Optional.empty();
        var uOpt = unitById(unitId);
        if (uOpt.isEmpty()) return Optional.empty();
        Unit u = uOpt.get();
        if (u.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        if (u.kind() != UnitKind.HUNTING_PARTY) return Optional.empty();
        var cityOpt = cityAt(u.coord());
        if (cityOpt.isEmpty() || cityOpt.get().ownerSeat() != u.ownerSeat()) {
            return Optional.of("Rebase requires standing on one of your city centers.");
        }
        City city = cityOpt.get();
        int food = u.clearCarriedFood();
        city.returnPopulationFromParty();
        city.addForagedFood(food);
        units.remove(u);
        updateVisitedFor(city.ownerSeat());
        return Optional.of("Hunting party rebased to " + city.name()
                + " (+1 population, +" + food + " food).");
    }

    private static int wildlifeFoodValue(WildAnimalKind kind) {
        return Math.max(2, kind.maxHp() / 3);
    }

    public Optional<String> trySendHunterFromCity(int cityId, int animalId, int roundsAway) {
        if (isOver()) return Optional.empty();
        var cOpt = cityById(cityId);
        if (cOpt.isEmpty()) return Optional.empty();
        City city = cOpt.get();
        if (city.ownerSeat() != currentPlayer().seat()) return Optional.empty();
        WildAnimal prey = null;
        for (var a : wildlife) {
            if (a.id() == animalId) {
                prey = a;
                break;
            }
        }
        if (prey == null) {
            return Optional.of("That quarry is no longer on the map.");
        }
        if (prey.coord().distanceTo(city.coord()) > 2) {
            return Optional.of("Wildlife is too far from the city to send a hunting party (need within 2 hexes).");
        }
        Optional<String> err = city.startHuntMission(animalId, roundsAway);
        if (err.isPresent()) {
            return err;
        }
        return Optional.of("Sent a hunting party (" + roundsAway + " rounds). Population temporarily reduced.");
    }

    private void checkWin() {
        // Win by city count
        for (var p : players) {
            if (cityCountFor(p.seat()) >= CITIES_TO_WIN) {
                winner = p;
                return;
            }
        }
        // Win by elimination (last player with cities OR units)
        Player alive = null;
        int alives = 0;
        for (var p : players) {
            if (cityCountFor(p.seat()) > 0 || unitCountFor(p.seat()) > 0) {
                alive = p;
                alives++;
            }
        }
        if (alives == 1 && players.size() > 1) {
            winner = alive;
        }
    }

    public record CityYield(int food, int production, int gold) {}
}

package com.tackstrat.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** A founded city: produces +2 production / turn, builds units, has HP and can be captured. */
public final class City {

    public static final int PRODUCTION_PER_TURN = 2;
    public static final int MAX_HP = 20;

    private final int id;
    private int ownerSeat;
    private final HexCoord coord;
    private final String name;
    private UnitKind currentBuild;
    private final Deque<UnitKind> buildQueue = new ArrayDeque<>();
    private int productionStored;
    private int population;
    private int foodStored;
    private int hp;
    private CityFocus focus = CityFocus.BALANCED;
    private final EnumSet<CityBuilding> buildings = EnumSet.noneOf(CityBuilding.class);
    /** Active hunting parties: population was reduced when each started. */
    private final ArrayList<HuntMission> huntMissions = new ArrayList<>();

    public static final class HuntMission {
        public int turnsRemaining;
        public final int targetAnimalId;

        public HuntMission(int turnsRemaining, int targetAnimalId) {
            this.turnsRemaining = turnsRemaining;
            this.targetAnimalId = targetAnimalId;
        }
    }

    public City(int id, int ownerSeat, HexCoord coord, String name, UnitKind initialBuild) {
        this.id = id;
        this.ownerSeat = ownerSeat;
        this.coord = coord;
        this.name = name;
        this.currentBuild = initialBuild;
        this.productionStored = 0;
        this.population = 1;
        this.foodStored = 0;
        this.hp = MAX_HP;
    }

    public int id() {
        return id;
    }

    public int ownerSeat() {
        return ownerSeat;
    }

    public HexCoord coord() {
        return coord;
    }

    public String name() {
        return name;
    }

    public UnitKind currentBuild() {
        return currentBuild;
    }

    public int productionStored() {
        return productionStored;
    }

    public int hp() {
        return hp;
    }

    public int population() {
        return population;
    }

    public int foodStored() {
        return foodStored;
    }

    public CityFocus focus() {
        return focus;
    }

    public boolean hasBuilding(CityBuilding b) {
        return buildings.contains(b);
    }

    public EnumSet<CityBuilding> buildings() {
        return EnumSet.copyOf(buildings);
    }

    /** Future builds after {@link #currentBuild()}. */
    public List<UnitKind> queuedBuilds() {
        return List.copyOf(buildQueue);
    }

    public int maxHp() {
        return MAX_HP;
    }

    public List<HuntMission> huntMissions() {
        return List.copyOf(huntMissions);
    }

    public int huntPartiesAway() {
        return huntMissions.size();
    }

    /**
     * Send a townsfolk hunting party. Population drops by 1 until the party returns.
     *
     * @return empty if started, or error message
     */
    public Optional<String> startHuntMission(int targetAnimalId, int roundsAway) {
        if (population < 2) {
            return Optional.of("Need at least 2 population to send a hunter.");
        }
        if (roundsAway < 1) {
            return Optional.of("Invalid hunt length.");
        }
        population--;
        huntMissions.add(new HuntMission(roundsAway, targetAnimalId));
        return Optional.empty();
    }

    /** End-of-turn tick: returning hunters restore +1 pop and complete the hunt against the target animal. */
    public List<Integer> advanceHuntMissions() {
        List<Integer> animalsToCull = new ArrayList<>();
        Iterator<HuntMission> it = huntMissions.iterator();
        while (it.hasNext()) {
            HuntMission m = it.next();
            m.turnsRemaining--;
            if (m.turnsRemaining <= 0) {
                population++;
                animalsToCull.add(m.targetAnimalId);
                it.remove();
            }
        }
        return animalsToCull;
    }

    /** Animals cannot wipe a city; HP is floored so it always remains contestable by units. */
    public void applyWildlifeRaid(int dmg) {
        hp = Math.max(1, hp - Math.max(0, dmg));
    }

    public void setCurrentBuild(UnitKind kind) {
        if (kind != currentBuild) {
            currentBuild = kind;
            productionStored = 0;
        }
    }

    public void addProduction(int amount) {
        productionStored += amount;
    }

    public void setFocus(CityFocus focus) {
        this.focus = focus == null ? CityFocus.BALANCED : focus;
    }

    public boolean addBuilding(CityBuilding building) {
        if (building == null) return false;
        return buildings.add(building);
    }

    public void applyFoodYield(int foodYield) {
        int upkeep = 1 + population;
        int surplus = foodYield - upkeep;
        if (surplus <= 0) {
            foodStored = Math.max(0, foodStored + surplus);
            return;
        }
        foodStored += surplus;
        while (foodStored >= growthThreshold()) {
            foodStored -= growthThreshold();
            population++;
        }
    }

    /** Foraging return from a hunting party (no upkeep subtraction). */
    public void addForagedFood(int food) {
        if (food <= 0) return;
        foodStored += food;
        while (foodStored >= growthThreshold()) {
            foodStored -= growthThreshold();
            population++;
        }
    }

    /** Reserve one population to form an external party; leaves at least 1 in city. */
    public boolean consumePopulationForParty() {
        if (population < 2) return false;
        population--;
        return true;
    }

    /** Party returns home and becomes population again. */
    public void returnPopulationFromParty() {
        population++;
    }

    public int growthThreshold() {
        int base = 8 + population * 4;
        if (hasBuilding(CityBuilding.GRANARY)) {
            base = Math.max(4, base - 2);
        }
        return base;
    }

    public void enqueueBuild(UnitKind kind) {
        if (currentBuild == null) {
            currentBuild = kind;
            return;
        }
        buildQueue.addLast(kind);
    }

    /** Remove queued item at index (0-based). */
    public boolean removeQueuedBuild(int index) {
        if (index < 0 || index >= buildQueue.size()) return false;
        var list = new ArrayList<>(buildQueue);
        list.remove(index);
        buildQueue.clear();
        buildQueue.addAll(list);
        return true;
    }

    /** Move queued item one position up/down; delta should be -1 or +1. */
    public boolean moveQueuedBuild(int index, int delta) {
        if (index < 0 || index >= buildQueue.size()) return false;
        int to = index + delta;
        if (to < 0 || to >= buildQueue.size()) return false;
        var list = new ArrayList<>(buildQueue);
        var item = list.remove(index);
        list.add(to, item);
        buildQueue.clear();
        buildQueue.addAll(list);
        return true;
    }

    public java.util.Optional<UnitKind> drainCompleted() {
        if (currentBuild == null) {
            return java.util.Optional.empty();
        }
        int cost = currentBuild.productionCost();
        if (productionStored < cost) {
            return java.util.Optional.empty();
        }
        UnitKind produced = currentBuild;
        productionStored -= cost;
        if (!buildQueue.isEmpty()) {
            currentBuild = buildQueue.removeFirst();
        } else {
            // Require explicit next choice; do not auto-repeat previous build forever.
            currentBuild = null;
        }
        return java.util.Optional.of(produced);
    }

    /** Hand the city to a new owner; reset HP so it isn't immediately recapturable. */
    public void captureBy(int newOwnerSeat) {
        ownerSeat = newOwnerSeat;
        hp = MAX_HP / 2;
        productionStored = 0;
    }

    /** Restore mutable fields when loading a save (bypasses {@link #setCurrentBuild} side effects). */
    public void applySavedState(int savedOwnerSeat, UnitKind savedBuild, int savedProductionStored, int savedHp) {
        applySavedState(savedOwnerSeat, savedBuild, List.of(), savedProductionStored, savedHp, 1, 0, null);
    }

    /** Restore mutable fields when loading a save, including queued builds. */
    public void applySavedState(
            int savedOwnerSeat,
            UnitKind savedBuild,
            List<UnitKind> savedQueue,
            int savedProductionStored,
            int savedHp,
            int savedPopulation,
            int savedFoodStored) {
        applySavedState(
                savedOwnerSeat, savedBuild, savedQueue, savedProductionStored, savedHp, savedPopulation, savedFoodStored,
                null);
    }

    public void applySavedState(
            int savedOwnerSeat,
            UnitKind savedBuild,
            List<UnitKind> savedQueue,
            int savedProductionStored,
            int savedHp,
            int savedPopulation,
            int savedFoodStored,
            List<HuntMission> savedHunts) {
        if (savedHp < 0 || savedHp > MAX_HP) {
            throw new IllegalArgumentException("Invalid city HP: " + savedHp);
        }
        if (savedProductionStored < 0) {
            throw new IllegalArgumentException("Invalid production: " + savedProductionStored);
        }
        if (savedPopulation < 1) {
            throw new IllegalArgumentException("Invalid population: " + savedPopulation);
        }
        if (savedFoodStored < 0) {
            throw new IllegalArgumentException("Invalid food storage: " + savedFoodStored);
        }
        this.ownerSeat = savedOwnerSeat;
        this.currentBuild = savedBuild;
        this.buildQueue.clear();
        this.buildQueue.addAll(new ArrayList<>(savedQueue));
        this.productionStored = savedProductionStored;
        this.population = savedPopulation;
        this.foodStored = savedFoodStored;
        this.hp = savedHp;
        this.focus = CityFocus.BALANCED;
        this.buildings.clear();
        huntMissions.clear();
        if (savedHunts != null) {
            huntMissions.addAll(savedHunts);
        }
    }

    public void applySavedCityMeta(CityFocus savedFocus, List<CityBuilding> savedBuildings) {
        this.focus = savedFocus == null ? CityFocus.BALANCED : savedFocus;
        this.buildings.clear();
        if (savedBuildings != null) {
            this.buildings.addAll(savedBuildings);
        }
    }
}

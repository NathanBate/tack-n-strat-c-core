package com.tackstrat.model;

/** A movable piece on the map. Tracks moves and HP. */
public final class Unit {

    private final int id;
    private final int ownerSeat;
    private final UnitKind kind;
    private HexCoord coord;
    private int movesRemaining;
    private int hp;
    /** Civ-style auto-explore: moves toward fog each turn until cleared. */
    private boolean autoExplore;
    /** Sleep state: ignored by "needs orders" until explicitly woken. */
    private boolean sleeping;
    /** For hunting parties: food carried back to a city by rebase. */
    private int carriedFood;

    public Unit(int id, int ownerSeat, UnitKind kind, HexCoord coord) {
        this.id = id;
        this.ownerSeat = ownerSeat;
        this.kind = kind;
        this.coord = coord;
        this.movesRemaining = kind.movement();
        this.hp = kind.maxHp();
    }

    public int id() {
        return id;
    }

    public int ownerSeat() {
        return ownerSeat;
    }

    public UnitKind kind() {
        return kind;
    }

    public HexCoord coord() {
        return coord;
    }

    public int movesRemaining() {
        return movesRemaining;
    }

    public int hp() {
        return hp;
    }

    public int maxHp() {
        return kind.maxHp();
    }

    public void setCoord(HexCoord coord) {
        this.coord = coord;
    }

    public void spendMoves(int n) {
        movesRemaining = Math.max(0, movesRemaining - n);
    }

    public void exhaustMoves() {
        movesRemaining = 0;
    }

    public void refreshMoves() {
        movesRemaining = kind.movement();
    }

    public void takeDamage(int dmg) {
        hp = Math.max(0, hp - dmg);
    }

    public boolean isDead() {
        return hp <= 0;
    }

    /** Restore HP and remaining moves when loading a save (must stay within kind limits). */
    public void applySavedCombatState(int savedHp, int savedMovesRemaining) {
        if (savedHp < 0 || savedHp > kind.maxHp()) {
            throw new IllegalArgumentException("Invalid HP for " + kind + ": " + savedHp);
        }
        if (savedMovesRemaining < 0 || savedMovesRemaining > kind.movement()) {
            throw new IllegalArgumentException("Invalid moves for " + kind + ": " + savedMovesRemaining);
        }
        this.hp = savedHp;
        this.movesRemaining = savedMovesRemaining;
    }

    public void applySavedAutoExplore(boolean savedAutoExplore) {
        this.autoExplore = savedAutoExplore;
    }

    public void applySavedSleeping(boolean savedSleeping) {
        this.sleeping = savedSleeping;
    }

    public boolean autoExplore() {
        return autoExplore;
    }

    public void setAutoExplore(boolean on) {
        this.autoExplore = on;
    }

    public boolean sleeping() {
        return sleeping;
    }

    public void setSleeping(boolean on) {
        this.sleeping = on;
    }

    public int carriedFood() {
        return carriedFood;
    }

    public void addCarriedFood(int food) {
        if (food <= 0) return;
        carriedFood += food;
    }

    public int clearCarriedFood() {
        int out = carriedFood;
        carriedFood = 0;
        return out;
    }

    public void applySavedCarriedFood(int savedCarriedFood) {
        if (savedCarriedFood < 0) {
            throw new IllegalArgumentException("Invalid carried food: " + savedCarriedFood);
        }
        this.carriedFood = savedCarriedFood;
    }
}

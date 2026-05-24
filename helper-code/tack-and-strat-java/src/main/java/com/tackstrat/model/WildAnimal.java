package com.tackstrat.model;

/** Neutral predator on the map; uses its own lightweight turn each round. */
public final class WildAnimal {

    private final int id;
    private final WildAnimalKind kind;
    private HexCoord coord;
    private int hp;

    public WildAnimal(int id, WildAnimalKind kind, HexCoord coord) {
        this.id = id;
        this.kind = kind;
        this.coord = coord;
        this.hp = kind.maxHp();
    }

    public int id() {
        return id;
    }

    public WildAnimalKind kind() {
        return kind;
    }

    public HexCoord coord() {
        return coord;
    }

    public int hp() {
        return hp;
    }

    public void setCoord(HexCoord c) {
        this.coord = c;
    }

    public void takeDamage(int dmg) {
        hp = Math.max(0, hp - dmg);
    }

    public boolean isDead() {
        return hp <= 0;
    }

    /** Apply saved HP when loading (must be positive and within kind max). */
    public void applySavedHp(int savedHp) {
        if (savedHp <= 0 || savedHp > kind.maxHp()) {
            throw new IllegalArgumentException("Invalid wildlife HP: " + savedHp);
        }
        this.hp = savedHp;
    }
}

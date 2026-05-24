package com.tackstrat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wildlife on the map. Terrain hints are used for some spawns; many spawns are purely random
 * for an unpredictable, natural feel.
 */
public enum WildAnimalKind {
    WOLF("Wolf", 2, 10, 8, 16, 3),
    BEAR("Bear", 1, 24, 14, 28, 4),
    BOAR("Boar", 2, 14, 9, 20, 3),
    COUGAR("Cougar", 3, 11, 11, 24, 3),
    JACKAL("Jackal", 3, 6, 5, 12, 2),
    ELK("Elk", 2, 12, 6, 14, 2),
    DEER("Deer", 3, 8, 4, 8, 1),
    HORSE("Horse", 4, 10, 3, 6, 2);

    private final String label;
    private final int movement;
    private final int maxHp;
    private final int attack;
    /** Hunger / aggression: higher = more likely to approach towns. */
    private final int menace;
    /** 1–4: hardier animals resist storms, drought, etc. */
    private final int weatherResilience;

    WildAnimalKind(String label, int movement, int maxHp, int attack, int menace, int weatherResilience) {
        this.label = label;
        this.movement = movement;
        this.maxHp = maxHp;
        this.attack = attack;
        this.menace = menace;
        this.weatherResilience = weatherResilience;
    }

    public String label() {
        return label;
    }

    public int movement() {
        return movement;
    }

    public int maxHp() {
        return maxHp;
    }

    public int attack() {
        return attack;
    }

    public int menace() {
        return menace;
    }

    public int weatherResilience() {
        return weatherResilience;
    }

    /** Biased habitat when terrain-guided spawning is used. */
    public boolean suitsSpawn(Terrain t) {
        return switch (this) {
            case WOLF -> t == Terrain.FOREST || t == Terrain.HILL;
            case BEAR -> t == Terrain.FOREST || t == Terrain.HILL;
            case BOAR -> t == Terrain.GRASS || t == Terrain.FOREST || t == Terrain.PLAINS;
            case COUGAR -> t == Terrain.HILL || t == Terrain.FOREST;
            case JACKAL -> t == Terrain.DESERT || t == Terrain.PLAINS;
            case ELK -> t == Terrain.PLAINS || t == Terrain.GRASS;
            case DEER -> t == Terrain.GRASS || t == Terrain.PLAINS || t == Terrain.FOREST;
            case HORSE -> t == Terrain.GRASS || t == Terrain.PLAINS || t == Terrain.DESERT;
        };
    }

    /** Pick a kind that fits this terrain, or any land kind if none match. */
    public static WildAnimalKind pickForTerrain(Terrain t, Random rnd) {
        List<WildAnimalKind> ok = new ArrayList<>();
        for (WildAnimalKind k : values()) {
            if (k.suitsSpawn(t)) {
                ok.add(k);
            }
        }
        if (ok.isEmpty()) {
            return values()[rnd.nextInt(values().length)];
        }
        return ok.get(rnd.nextInt(ok.size()));
    }

    /** Fully random species (wolves, deer, horses, etc. with no terrain bias). */
    public static WildAnimalKind pickFullyRandom(Random rnd) {
        WildAnimalKind[] v = values();
        return v[rnd.nextInt(v.length)];
    }

    public char glyph() {
        return switch (this) {
            case WOLF -> 'w';
            case BEAR -> 'B';
            case BOAR -> 'b';
            case COUGAR -> 'c';
            case JACKAL -> 'j';
            case ELK -> 'E';
            case DEER -> 'd';
            case HORSE -> 'h';
        };
    }
}

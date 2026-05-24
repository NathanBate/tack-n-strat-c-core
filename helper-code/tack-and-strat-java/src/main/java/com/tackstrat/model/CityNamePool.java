package com.tackstrat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Exactly 2,000 unique compound names (50 × 40); order is shuffled per world seed for variety. */
public final class CityNamePool {

    static final int UNIQUE_COUNT = 2000;

    private CityNamePool() {}

    private static final String[] PREFIXES = {
            "Ash", "Bel", "Cor", "Dun", "Elm", "Far", "Gar", "Haw", "Ill", "Jar",
            "Ken", "Lyn", "Mor", "Nor", "Oak", "Pet", "Quo", "Riv", "Sol", "Tal",
            "Ulv", "Ven", "Wyn", "Yew", "Zim", "Ald", "Bex", "Cray", "Dorn", "Ett",
            "Fell", "Gorn", "Hel", "Ith", "Jex", "Keth", "Lorn", "Mire", "Nock", "Oren",
            "Pell", "Rill", "Sorn", "Teth", "Usk", "Vorn", "Weth", "Yorn", "Zeth", "Bran"
    };

    private static final String[] SUFFIXES = {
            "ford", "brook", "haven", "moor", "wick", "dale", "burgh", "ton", "caster", "mouth",
            "ridge", "stead", "bury", "shire", "land", "fall", "mere", "well", "gate", "thorpe",
            "ham", "ley", "stone", "bridge", "wood", "field", "cross", "mill", "worth", "borough",
            "vale", "crest", "holm", "pool", "bay", "harbor", "reach", "glen", "fort", "mont"
    };

    static {
        if (PREFIXES.length * SUFFIXES.length != UNIQUE_COUNT) {
            throw new IllegalStateException("city name grid must total " + UNIQUE_COUNT);
        }
    }

    /** All names in deterministic grid order (prefix-major). */
    public static List<String> fullDeckInGridOrder() {
        var list = new ArrayList<String>(UNIQUE_COUNT);
        for (String p : PREFIXES) {
            for (String s : SUFFIXES) {
                list.add(p + s);
            }
        }
        return list;
    }

    /** Fresh deck order for this seed (used when issuing names to founded cities). */
    public static List<String> shuffledDeck(long worldSeed) {
        var copy = new ArrayList<>(fullDeckInGridOrder());
        Collections.shuffle(copy, new Random(worldSeed ^ 0xC174_9E5FL));
        return copy;
    }
}

package com.tackstrat.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityNamePoolTest {

    @Test
    void grid_has_two_thousand_unique_names() {
        var names = CityNamePool.fullDeckInGridOrder();
        assertEquals(CityNamePool.UNIQUE_COUNT, names.size());
        assertEquals(CityNamePool.UNIQUE_COUNT, new HashSet<>(names).size());
    }

    @Test
    void shuffle_preserves_size() {
        assertEquals(CityNamePool.UNIQUE_COUNT, CityNamePool.shuffledDeck(12345L).size());
    }
}

package com.tackstrat.model;

/** Four-season wheel; advances once each full player rotation. */
public enum Season {
    SPRING("Spring"),
    SUMMER("Summer"),
    AUTUMN("Autumn"),
    WINTER("Winter");

    private final String label;

    Season(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Season fromOrdinal(int ordinal) {
        Season[] v = values();
        return v[Math.floorMod(ordinal, v.length)];
    }
}

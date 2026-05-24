package com.tackstrat.model;

/** First-pass city buildings with flat yield effects. */
public enum CityBuilding {
    GRANARY("Granary", 45),
    WORKSHOP("Workshop", 60),
    MARKET("Market", 55);

    private final String label;
    private final int goldCost;

    CityBuilding(String label, int goldCost) {
        this.label = label;
        this.goldCost = goldCost;
    }

    public String label() {
        return label;
    }

    public int goldCost() {
        return goldCost;
    }
}


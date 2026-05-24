package com.tackstrat.model;

/**
 * A regional weather cell: hex disk around {@link #center()} affecting movement, yields, sight, etc.
 */
public record WeatherSystem(int id, Weather kind, int centerQ, int centerR, int radius) {

    public HexCoord center() {
        return new HexCoord(centerQ, centerR);
    }

    public boolean covers(HexCoord h) {
        return center().distanceTo(h) <= radius;
    }
}

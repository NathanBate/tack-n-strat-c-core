package com.tackstrat.model;

import java.util.List;
import java.util.Map;

public record GeneratedWorld(
        GameMap map,
        List<Unit> units,
        Map<HexCoord, Integer> soilFertilityBonus,
        List<WildAnimal> wildlife) {
    public GeneratedWorld {
        units = List.copyOf(units);
        wildlife = List.copyOf(wildlife);
        soilFertilityBonus = Map.copyOf(soilFertilityBonus);
    }

    public GeneratedWorld(GameMap map, List<Unit> units) {
        this(map, units, Map.of(), List.of());
    }
}

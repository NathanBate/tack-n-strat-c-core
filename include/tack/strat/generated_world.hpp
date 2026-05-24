#pragma once

#include <unordered_map>
#include <vector>

#include "tack/strat/game_map.hpp"
#include "tack/strat/unit.hpp"
#include "tack/strat/wild_animal.hpp"

namespace tack::strat {

struct GeneratedWorld {
  GameMap map;
  std::vector<Unit> units;
  std::unordered_map<HexCoord, int> soil_fertility_bonus;
  std::vector<WildAnimal> wildlife;
};

}  // namespace tack::strat

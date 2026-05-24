#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace tack::strat {

struct CityNamePool {
  static constexpr int UNIQUE_COUNT = 2000;

  [[nodiscard]] static std::vector<std::string> full_deck_grid_order();
  [[nodiscard]] static std::vector<std::string> shuffled_deck(std::int64_t world_seed);
};

}  // namespace tack::strat

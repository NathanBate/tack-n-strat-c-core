#pragma once

#include <vector>

#include "tack/strat/generated_world.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/player.hpp"

namespace tack::strat {

struct WorldGenerator {
  /** Legacy reference disk radius for the Java prototype / {@link MapSizePreset::Tiny} (maps are rectangular). */
  static constexpr int MAP_RADIUS = map_hex_radius(MapSizePreset::Tiny);

  [[nodiscard]] static GeneratedWorld generate(std::vector<Player> const& players,
                                               std::int64_t seed,
                                               MapSizePreset map_size = MapSizePreset::Tiny);
};

}  // namespace tack::strat

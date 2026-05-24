#pragma once

#include <vector>

#include "tack/strat/game_map.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/season.hpp"
#include "tack/strat/weather_system.hpp"

namespace tack::strat {

struct WeatherBootstrap {
  std::vector<WeatherSystem> systems;
  int next_id{};
};

struct WeatherTickResult {
  std::vector<WeatherSystem> systems;
  int next_id{};
};

[[nodiscard]] WeatherBootstrap initial_systems(GameMap const& map, std::int64_t world_seed);

[[nodiscard]] WeatherTickResult regional_weather_tick(std::vector<WeatherSystem> const& prev,
                                                      GameMap const& map, Season season,
                                                      std::int64_t world_seed, int round,
                                                      int weather_nonce, int next_id);

}  // namespace tack::strat

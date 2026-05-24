#pragma once

#include <algorithm>
#include <cmath>

#include "tack/strat/hex.hpp"
#include "tack/strat/terrain.hpp"

namespace tack::strat {

enum class Weather {
  CLEAR,
  RAIN,
  DROUGHT,
  STORM,
  COLD_SNAP,
  HEAT_WAVE,
  FOG,
};

[[nodiscard]] constexpr char const* weather_label(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return "Clear";
    case Weather::RAIN:
      return "Rain";
    case Weather::DROUGHT:
      return "Drought";
    case Weather::STORM:
      return "Storm";
    case Weather::COLD_SNAP:
      return "Cold snap";
    case Weather::HEAT_WAVE:
      return "Heat wave";
    case Weather::FOG:
      return "Fog";
  }
  return "Clear";
}

[[nodiscard]] constexpr int weather_city_food_percent(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return 4;
    case Weather::DROUGHT:
      return -10;
    case Weather::STORM:
      return -2;
    case Weather::COLD_SNAP:
      return -4;
    case Weather::HEAT_WAVE:
      return -6;
    case Weather::FOG:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_city_production_percent(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return 0;
    case Weather::DROUGHT:
      return -2;
    case Weather::STORM:
      return -1;
    case Weather::COLD_SNAP:
      return 0;
    case Weather::HEAT_WAVE:
      return 0;
    case Weather::FOG:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_city_gold_percent(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return -1;
    case Weather::DROUGHT:
      return 0;
    case Weather::STORM:
      return 0;
    case Weather::COLD_SNAP:
      return 0;
    case Weather::HEAT_WAVE:
      return 0;
    case Weather::FOG:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_extra_move_cost_base(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return 0;
    case Weather::DROUGHT:
      return 1;
    case Weather::STORM:
      return 1;
    case Weather::COLD_SNAP:
      return 1;
    case Weather::HEAT_WAVE:
      return 0;
    case Weather::FOG:
      return 1;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_sight_penalty_base(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return 0;
    case Weather::DROUGHT:
      return 0;
    case Weather::STORM:
      return 0;
    case Weather::COLD_SNAP:
      return 1;
    case Weather::HEAT_WAVE:
      return 0;
    case Weather::FOG:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_wild_extra_damage(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return 0;
    case Weather::RAIN:
      return 0;
    case Weather::DROUGHT:
      return 1;
    case Weather::STORM:
      return 2;
    case Weather::COLD_SNAP:
      return 1;
    case Weather::HEAT_WAVE:
      return 1;
    case Weather::FOG:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int weather_forest_storm_move_add(Weather w) noexcept {
  return w == Weather::STORM ? 1 : 0;
}

[[nodiscard]] constexpr int apply_weather_resilience_to_pct(int raw, int resilience,
                                                           int cap = 4) noexcept {
  int r = std::max(0, std::min(cap, resilience));
  if (raw >= 0) {
    return raw;
  }
  double factor = 1.0 - (static_cast<double>(r) / static_cast<double>(cap)) * 0.55;
  return static_cast<int>(std::lround(static_cast<double>(raw) * factor));
}

[[nodiscard]] inline int mitigated_city_food_percent(Weather w, int city_resilience) noexcept {
  return apply_weather_resilience_to_pct(weather_city_food_percent(w), city_resilience);
}

[[nodiscard]] inline int mitigated_city_production_percent(Weather w, int city_resilience) noexcept {
  return apply_weather_resilience_to_pct(weather_city_production_percent(w), city_resilience);
}

[[nodiscard]] inline int mitigated_city_gold_percent(Weather w, int city_resilience) noexcept {
  return apply_weather_resilience_to_pct(weather_city_gold_percent(w), city_resilience);
}

[[nodiscard]] inline int extra_movement_cost(Weather w, HexCoord /*dest*/, Terrain terrain,
                                            int unit_resilience) noexcept {
  int extra = weather_extra_move_cost_base(w);
  if (w == Weather::STORM && terrain == Terrain::FOREST) {
    extra += weather_forest_storm_move_add(w);
  }
  int mit = std::max(0, extra - unit_resilience / 2);
  return mit;
}

[[nodiscard]] inline int sight_penalty(Weather w, int unit_resilience) noexcept {
  return std::max(0, weather_sight_penalty_base(w) - unit_resilience / 2);
}

[[nodiscard]] inline int extra_wild_damage(Weather w, int animal_resilience) noexcept {
  return std::max(0, weather_wild_extra_damage(w) - animal_resilience / 2);
}

[[nodiscard]] constexpr int overlay_priority(Weather w) noexcept {
  switch (w) {
    case Weather::STORM:
      return 90;
    case Weather::HEAT_WAVE:
      return 74;
    case Weather::DROUGHT:
      return 70;
    case Weather::COLD_SNAP:
      return 68;
    case Weather::RAIN:
      return 58;
    case Weather::FOG:
      return 48;
    case Weather::CLEAR:
      return 0;
  }
  return 0;
}

}  // namespace tack::strat

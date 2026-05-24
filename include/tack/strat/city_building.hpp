#pragma once

#include <cstddef>
#include <functional>

namespace tack::strat {

enum class CityBuilding {
  GRANARY,
  WORKSHOP,
  MARKET,
};

[[nodiscard]] constexpr char const* city_building_label(CityBuilding b) noexcept {
  switch (b) {
    case CityBuilding::GRANARY:
      return "Granary";
    case CityBuilding::WORKSHOP:
      return "Workshop";
    case CityBuilding::MARKET:
      return "Market";
  }
  return "";
}

[[nodiscard]] constexpr int city_building_gold_cost(CityBuilding b) noexcept {
  switch (b) {
    case CityBuilding::GRANARY:
      return 45;
    case CityBuilding::WORKSHOP:
      return 60;
    case CityBuilding::MARKET:
      return 55;
  }
  return 0;
}

}  // namespace tack::strat

template <>
struct std::hash<tack::strat::CityBuilding> {
  std::size_t operator()(tack::strat::CityBuilding b) const noexcept {
    return static_cast<std::size_t>(b);
  }
};

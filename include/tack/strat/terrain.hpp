#pragma once

#include <limits>

namespace tack::strat {

enum class Terrain {
  WATER,
  GRASS,
  PLAINS,
  DESERT,
  HILL,
  FOREST,
  MOUNTAIN,
};

[[nodiscard]] constexpr bool passable(Terrain t) noexcept {
  return t != Terrain::WATER && t != Terrain::MOUNTAIN;
}

[[nodiscard]] constexpr int movement_cost(Terrain t) noexcept {
  switch (t) {
    case Terrain::WATER:
    case Terrain::MOUNTAIN:
      return std::numeric_limits<int>::max();
    case Terrain::GRASS:
    case Terrain::PLAINS:
    case Terrain::DESERT:
      return 1;
    case Terrain::HILL:
    case Terrain::FOREST:
      return 2;
  }
  return std::numeric_limits<int>::max();
}

[[nodiscard]] constexpr bool can_found_city_on(Terrain t) noexcept {
  return t == Terrain::GRASS || t == Terrain::PLAINS || t == Terrain::DESERT || t == Terrain::HILL;
}

[[nodiscard]] constexpr int food_yield(Terrain t) noexcept {
  switch (t) {
    case Terrain::GRASS:
      return 2;
    case Terrain::PLAINS:
    case Terrain::FOREST:
    case Terrain::WATER:
      return 1;
    default:
      return 0;
  }
}

[[nodiscard]] constexpr int production_yield(Terrain t) noexcept {
  switch (t) {
    case Terrain::HILL:
      return 2;
    case Terrain::PLAINS:
    case Terrain::FOREST:
      return 1;
    default:
      return 0;
  }
}

[[nodiscard]] constexpr int gold_yield(Terrain t) noexcept {
  switch (t) {
    case Terrain::DESERT:
    case Terrain::WATER:
      return 1;
    default:
      return 0;
  }
}

[[nodiscard]] constexpr bool can_cultivate(Terrain t) noexcept {
  return t == Terrain::GRASS || t == Terrain::PLAINS || t == Terrain::DESERT;
}

[[nodiscard]] constexpr bool can_support_farm(Terrain t) noexcept {
  return t == Terrain::GRASS || t == Terrain::PLAINS || t == Terrain::DESERT;
}

[[nodiscard]] constexpr bool can_support_mine(Terrain t) noexcept {
  return t == Terrain::HILL;
}

}  // namespace tack::strat

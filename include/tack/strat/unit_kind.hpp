#pragma once

namespace tack::strat {

enum class UnitKind {
  SETTLER,
  SCOUT,
  WARRIOR,
  FARMER,
  BUILDER,
  HUNTING_PARTY,
};

/** UI / event strings (matches Java {@link UnitKind#displayName()}). */
[[nodiscard]] constexpr char const* unit_kind_display_name(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return "Settler";
    case UnitKind::SCOUT:
      return "Scout";
    case UnitKind::WARRIOR:
      return "Warrior";
    case UnitKind::FARMER:
      return "Farmer";
    case UnitKind::BUILDER:
      return "Builder";
    case UnitKind::HUNTING_PARTY:
      return "Hunting Party";
  }
  return "Unit";
}

/** Persisted enum name (Java {@link Enum#name()}). */
[[nodiscard]] constexpr char const* unit_kind_snapshot_name(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return "SETTLER";
    case UnitKind::SCOUT:
      return "SCOUT";
    case UnitKind::WARRIOR:
      return "WARRIOR";
    case UnitKind::FARMER:
      return "FARMER";
    case UnitKind::BUILDER:
      return "BUILDER";
    case UnitKind::HUNTING_PARTY:
      return "HUNTING_PARTY";
  }
  return "SETTLER";
}

[[nodiscard]] constexpr int unit_movement(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
    case UnitKind::WARRIOR:
    case UnitKind::FARMER:
    case UnitKind::BUILDER:
    case UnitKind::HUNTING_PARTY:
      return 2;
    case UnitKind::SCOUT:
      return 4;
  }
  return 2;
}

[[nodiscard]] constexpr int unit_production_cost(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return 10;
    case UnitKind::SCOUT:
      return 4;
    case UnitKind::WARRIOR:
      return 6;
    case UnitKind::FARMER:
      return 8;
    case UnitKind::BUILDER:
      return 12;
    case UnitKind::HUNTING_PARTY:
      return 16;
  }
  return 0;
}

[[nodiscard]] constexpr int unit_max_hp(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return 10;
    case UnitKind::SCOUT:
      return 10;
    case UnitKind::WARRIOR:
      return 20;
    case UnitKind::FARMER:
      return 8;
    case UnitKind::BUILDER:
      return 10;
    case UnitKind::HUNTING_PARTY:
      return 22;
  }
  return 1;
}

[[nodiscard]] constexpr int unit_attack_strength(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return 0;
    case UnitKind::SCOUT:
      return 3;
    case UnitKind::WARRIOR:
      return 8;
    case UnitKind::FARMER:
      return 2;
    case UnitKind::BUILDER:
      return 2;
    case UnitKind::HUNTING_PARTY:
      return 0;
  }
  return 0;
}

[[nodiscard]] constexpr int unit_sight_radius(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return 2;
    case UnitKind::SCOUT:
      return 3;
    case UnitKind::WARRIOR:
      return 2;
    case UnitKind::FARMER:
      return 2;
    case UnitKind::BUILDER:
      return 2;
    case UnitKind::HUNTING_PARTY:
      return 2;
  }
  return 2;
}

[[nodiscard]] constexpr int unit_weather_resilience(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return 2;
    case UnitKind::SCOUT:
      return 3;
    case UnitKind::WARRIOR:
      return 3;
    case UnitKind::FARMER:
      return 2;
    case UnitKind::BUILDER:
      return 2;
    case UnitKind::HUNTING_PARTY:
      return 3;
  }
  return 2;
}

}  // namespace tack::strat

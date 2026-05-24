#pragma once

#include <algorithm>
#include <vector>

#include "tack/strat/hex.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/terrain.hpp"

namespace tack::strat {

enum class WildAnimalKind {
  WOLF,
  BEAR,
  BOAR,
  COUGAR,
  JACKAL,
  ELK,
  DEER,
  HORSE,
};

[[nodiscard]] constexpr int wild_movement(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return 2;
    case WildAnimalKind::BEAR:
      return 1;
    case WildAnimalKind::BOAR:
      return 2;
    case WildAnimalKind::COUGAR:
      return 3;
    case WildAnimalKind::JACKAL:
      return 3;
    case WildAnimalKind::ELK:
      return 2;
    case WildAnimalKind::DEER:
      return 3;
    case WildAnimalKind::HORSE:
      return 4;
  }
  return 2;
}

// Fixed table matching Java enum order:
[[nodiscard]] constexpr int wild_max_hp(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return 10;
    case WildAnimalKind::BEAR:
      return 24;
    case WildAnimalKind::BOAR:
      return 14;
    case WildAnimalKind::COUGAR:
      return 11;
    case WildAnimalKind::JACKAL:
      return 6;
    case WildAnimalKind::ELK:
      return 12;
    case WildAnimalKind::DEER:
      return 8;
    case WildAnimalKind::HORSE:
      return 10;
  }
  return 1;
}

[[nodiscard]] constexpr int wild_attack(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return 8;
    case WildAnimalKind::BEAR:
      return 14;
    case WildAnimalKind::BOAR:
      return 9;
    case WildAnimalKind::COUGAR:
      return 11;
    case WildAnimalKind::JACKAL:
      return 5;
    case WildAnimalKind::ELK:
      return 6;
    case WildAnimalKind::DEER:
      return 4;
    case WildAnimalKind::HORSE:
      return 3;
  }
  return 1;
}

[[nodiscard]] constexpr int wild_weather_resilience(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return 3;
    case WildAnimalKind::BEAR:
      return 4;
    case WildAnimalKind::BOAR:
      return 3;
    case WildAnimalKind::COUGAR:
      return 3;
    case WildAnimalKind::JACKAL:
      return 2;
    case WildAnimalKind::ELK:
      return 2;
    case WildAnimalKind::DEER:
      return 1;
    case WildAnimalKind::HORSE:
      return 2;
  }
  return 1;
}

/** Short label for UI (matches Java {@link WildAnimalKind#label()}). */
[[nodiscard]] constexpr char const* wild_animal_kind_label(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return "Wolf";
    case WildAnimalKind::BEAR:
      return "Bear";
    case WildAnimalKind::BOAR:
      return "Boar";
    case WildAnimalKind::COUGAR:
      return "Cougar";
    case WildAnimalKind::JACKAL:
      return "Jackal";
    case WildAnimalKind::ELK:
      return "Elk";
    case WildAnimalKind::DEER:
      return "Deer";
    case WildAnimalKind::HORSE:
      return "Horse";
  }
  return "?";
}

[[nodiscard]] constexpr bool suits_spawn(WildAnimalKind k, Terrain t) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return t == Terrain::FOREST || t == Terrain::HILL;
    case WildAnimalKind::BEAR:
      return t == Terrain::FOREST || t == Terrain::HILL;
    case WildAnimalKind::BOAR:
      return t == Terrain::GRASS || t == Terrain::FOREST || t == Terrain::PLAINS;
    case WildAnimalKind::COUGAR:
      return t == Terrain::HILL || t == Terrain::FOREST;
    case WildAnimalKind::JACKAL:
      return t == Terrain::DESERT || t == Terrain::PLAINS;
    case WildAnimalKind::ELK:
      return t == Terrain::PLAINS || t == Terrain::GRASS;
    case WildAnimalKind::DEER:
      return t == Terrain::GRASS || t == Terrain::PLAINS || t == Terrain::FOREST;
    case WildAnimalKind::HORSE:
      return t == Terrain::GRASS || t == Terrain::PLAINS || t == Terrain::DESERT;
  }
  return false;
}

[[nodiscard]] inline WildAnimalKind pick_for_terrain(Terrain t, JavaRandom& rnd) {
  WildAnimalKind const all[] = {WildAnimalKind::WOLF, WildAnimalKind::BEAR, WildAnimalKind::BOAR,
                                WildAnimalKind::COUGAR, WildAnimalKind::JACKAL, WildAnimalKind::ELK,
                                WildAnimalKind::DEER, WildAnimalKind::HORSE};
  std::vector<WildAnimalKind> ok;
  for (WildAnimalKind k : all) {
    if (suits_spawn(k, t)) {
      ok.push_back(k);
    }
  }
  if (ok.empty()) {
    return all[rnd.next_int_bounded(8)];
  }
  return ok[static_cast<std::size_t>(rnd.next_int_bounded(static_cast<int>(ok.size())))];
}

[[nodiscard]] inline WildAnimalKind pick_fully_random(JavaRandom& rnd) {
  WildAnimalKind const all[] = {WildAnimalKind::WOLF, WildAnimalKind::BEAR, WildAnimalKind::BOAR,
                                WildAnimalKind::COUGAR, WildAnimalKind::JACKAL, WildAnimalKind::ELK,
                                WildAnimalKind::DEER, WildAnimalKind::HORSE};
  return all[rnd.next_int_bounded(8)];
}

class WildAnimal {
 public:
  WildAnimal(int id, WildAnimalKind kind, HexCoord coord);

  [[nodiscard]] int id() const noexcept {
    return id_;
  }
  [[nodiscard]] WildAnimalKind kind() const noexcept {
    return kind_;
  }
  [[nodiscard]] HexCoord coord() const noexcept {
    return coord_;
  }
  [[nodiscard]] int hp() const noexcept {
    return hp_;
  }

  void set_coord(HexCoord c) noexcept {
    coord_ = c;
  }
  void take_damage(int dmg) noexcept {
    hp_ = std::max(0, hp_ - dmg);
  }
  [[nodiscard]] bool is_dead() const noexcept {
    return hp_ <= 0;
  }

  /** Save/load: set HP (must be 1..max for kind). */
  void apply_saved_hp(int saved_hp);

 private:
  int id_{};
  WildAnimalKind kind_{};
  HexCoord coord_{};
  int hp_{};
};

}  // namespace tack::strat

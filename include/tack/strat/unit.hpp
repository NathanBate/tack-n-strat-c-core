#pragma once

#include <algorithm>

#include "tack/strat/hex.hpp"
#include "tack/strat/unit_kind.hpp"

namespace tack::strat {

class Unit {
 public:
  Unit(int id, int owner_seat, UnitKind kind, HexCoord coord);

  [[nodiscard]] int id() const noexcept {
    return id_;
  }
  [[nodiscard]] int owner_seat() const noexcept {
    return owner_seat_;
  }
  [[nodiscard]] UnitKind kind() const noexcept {
    return kind_;
  }
  [[nodiscard]] HexCoord coord() const noexcept {
    return coord_;
  }
  [[nodiscard]] int moves_remaining() const noexcept {
    return moves_remaining_;
  }
  [[nodiscard]] int hp() const noexcept {
    return hp_;
  }
  [[nodiscard]] int max_hp() const noexcept {
    return unit_max_hp(kind_);
  }

  void set_coord(HexCoord c) noexcept {
    coord_ = c;
  }
  void spend_moves(int n) noexcept {
    moves_remaining_ = std::max(0, moves_remaining_ - n);
  }
  void exhaust_moves() noexcept {
    moves_remaining_ = 0;
  }
  void refresh_moves() noexcept {
    moves_remaining_ = unit_movement(kind_);
  }

  void take_damage(int dmg) noexcept {
    hp_ = std::max(0, hp_ - dmg);
  }
  [[nodiscard]] bool is_dead() const noexcept {
    return hp_ <= 0;
  }

  [[nodiscard]] bool auto_explore() const noexcept {
    return auto_explore_;
  }
  void set_auto_explore(bool on) noexcept {
    auto_explore_ = on;
  }

  [[nodiscard]] bool sleeping() const noexcept {
    return sleeping_;
  }
  void set_sleeping(bool on) noexcept {
    sleeping_ = on;
  }
  void add_carried_food(int food) noexcept {
    if (food <= 0) {
      return;
    }
    carried_food_ += food;
  }
  [[nodiscard]] int carried_food() const noexcept {
    return carried_food_;
  }
  [[nodiscard]] int clear_carried_food() noexcept {
    int out = carried_food_;
    carried_food_ = 0;
    return out;
  }

  /** Save/load (matches Java {@code Unit.applySavedCombatState}). */
  void apply_saved_combat_state(int saved_hp, int saved_moves_remaining);
  /** Save/load (matches Java {@code Unit.applySavedCarriedFood}). */
  void apply_saved_carried_food(int saved_carried_food);

 private:
  int id_{};
  int owner_seat_{};
  UnitKind kind_{};
  HexCoord coord_{};
  int moves_remaining_{};
  int hp_{};
  bool auto_explore_{};
  bool sleeping_{};
  int carried_food_{};
};

}  // namespace tack::strat

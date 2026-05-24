#include "tack/strat/unit.hpp"

#include <stdexcept>

namespace tack::strat {

Unit::Unit(int id, int owner_seat, UnitKind kind, HexCoord coord)
    : id_(id),
      owner_seat_(owner_seat),
      kind_(kind),
      coord_(coord),
      moves_remaining_(unit_movement(kind)),
      hp_(unit_max_hp(kind)) {}

void Unit::apply_saved_combat_state(int saved_hp, int saved_moves_remaining) {
  if (saved_hp < 0 || saved_hp > unit_max_hp(kind_)) {
    throw std::invalid_argument("Invalid saved HP for unit");
  }
  if (saved_moves_remaining < 0 || saved_moves_remaining > unit_movement(kind_)) {
    throw std::invalid_argument("Invalid saved moves for unit");
  }
  hp_ = saved_hp;
  moves_remaining_ = saved_moves_remaining;
}

void Unit::apply_saved_carried_food(int saved_carried_food) {
  if (saved_carried_food < 0) {
    throw std::invalid_argument("Invalid carried food");
  }
  carried_food_ = saved_carried_food;
}

}  // namespace tack::strat

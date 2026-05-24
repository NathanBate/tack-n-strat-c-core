#include "tack/strat/wild_animal.hpp"

#include <stdexcept>

namespace tack::strat {

WildAnimal::WildAnimal(int id, WildAnimalKind kind, HexCoord coord)
    : id_(id), kind_(kind), coord_(coord), hp_(wild_max_hp(kind)) {}

void WildAnimal::apply_saved_hp(int saved_hp) {
  if (saved_hp <= 0 || saved_hp > wild_max_hp(kind_)) {
    throw std::invalid_argument("Invalid wildlife HP");
  }
  hp_ = saved_hp;
}

}  // namespace tack::strat

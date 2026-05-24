#include "tack/strat/combat_rng.hpp"

namespace tack::strat {

CombatRng::CombatRng(std::int64_t world_seed, int calls_consumed) : rng_(world_seed ^ 0x5a5aLL) {
  for (int i = 0; i < calls_consumed; ++i) {
    rng_.next_int_bounded(3);
  }
}

int CombatRng::next_int_3() {
  ++call_count_;
  return rng_.next_int_bounded(3);
}

int CombatRng::next_int_2() {
  ++call_count_;
  return rng_.next_int_bounded(2);
}

}  // namespace tack::strat

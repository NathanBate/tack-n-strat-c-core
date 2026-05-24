#pragma once

#include <cstdint>

#include "tack/strat/java_random.hpp"

namespace tack::strat {

/** Mirrors GameSession combat RNG: {@code new Random(worldSeed ^ 0x5a5aL)} with consumed {@code nextInt(3)} calls. */
class CombatRng {
 public:
  CombatRng(std::int64_t world_seed, int calls_consumed);

  [[nodiscard]] int next_int_3();
  /** Matches Java {@code combatRng.nextInt(2)} for hunt retaliation rolls (counts toward save parity). */
  [[nodiscard]] int next_int_2();
  [[nodiscard]] int call_count() const noexcept {
    return call_count_;
  }

 private:
  JavaRandom rng_;
  int call_count_{};
};

}  // namespace tack::strat

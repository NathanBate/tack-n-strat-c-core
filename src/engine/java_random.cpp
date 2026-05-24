#include "tack/strat/java_random.hpp"

namespace tack::strat {

std::uint64_t JavaRandom::initial_scramble(std::int64_t seed) noexcept {
  return (static_cast<std::uint64_t>(seed) ^ MULTIPLIER) & MASK;
}

JavaRandom::JavaRandom(std::int64_t seed) : seed_(initial_scramble(seed)) {}

int JavaRandom::next_bits(int bits) {
  seed_ = (seed_ * MULTIPLIER + ADDEND) & MASK;
  return static_cast<int>(static_cast<std::int64_t>(seed_) >> (48 - bits));
}

int JavaRandom::next_int() {
  return next_bits(32);
}

int JavaRandom::next_int_bounded(int bound) {
  if (bound <= 0) {
    throw std::invalid_argument("bound must be positive");
  }
  if ((bound & -bound) == bound) {
    return static_cast<int>((static_cast<std::int64_t>(bound) * static_cast<std::int64_t>(next_bits(31))) >> 31);
  }
  int bits = 0;
  int val = 0;
  do {
    bits = next_bits(31);
    val = bits % bound;
  } while (bits - val + (bound - 1) < 0);
  return val;
}

std::int64_t JavaRandom::next_long() {
  return (static_cast<std::int64_t>(next_bits(32)) << 32) + static_cast<std::int32_t>(next_bits(32));
}

double JavaRandom::next_double() {
  return ((static_cast<std::int64_t>(next_bits(26)) << 27) + next_bits(27)) * 0x1.0p-53;
}

bool JavaRandom::next_bool() {
  return next_bits(1) != 0;
}

}  // namespace tack::strat

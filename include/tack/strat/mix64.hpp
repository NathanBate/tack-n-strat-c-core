#pragma once

#include <bit>
#include <cstdint>

namespace tack::strat {

inline std::uint64_t mix64(std::uint64_t a, std::uint64_t b, std::uint64_t c) noexcept {
  std::uint64_t x = a ^ std::rotl(b, 21) ^ std::rotl(c, 42);
  x ^= x >> 33;
  x *= 0xff51'afd7'ed55'8ccdULL;
  x ^= x >> 33;
  x *= 0xc4ce'b9fe'1a85'ec53ULL;
  x ^= x >> 33;
  return x;
}

}  // namespace tack::strat

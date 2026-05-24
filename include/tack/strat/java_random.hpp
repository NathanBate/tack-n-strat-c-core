#pragma once

#include <cstdint>
#include <stdexcept>
#include <vector>

namespace tack::strat {

/** Matches java.util.Random (single-threaded) for deterministic parity with the Java rules reference. */
class JavaRandom {
 public:
  explicit JavaRandom(std::int64_t seed);

  /** Same contract as {@code Random#next(int)} — returns {@code bits} high bits of next seed. */
  int next_bits(int bits);

  int next_int();
  int next_int_bounded(int bound);
  std::int64_t next_long();
  double next_double();
  bool next_bool();

 private:
  std::uint64_t seed_{};
  static constexpr std::uint64_t MULTIPLIER = 0x5DEECE66DULL;
  static constexpr std::uint64_t ADDEND = 0xBULL;
  static constexpr std::uint64_t MASK = (1ULL << 48) - 1;

  static std::uint64_t initial_scramble(std::int64_t seed) noexcept;
};

template <class T>
void java_collections_shuffle(std::vector<T>& list, JavaRandom& rnd) {
  int const size = static_cast<int>(list.size());
  if (size < 2) {
    return;
  }
  for (int i = size; i > 1; --i) {
    int j = rnd.next_int_bounded(i);
    std::swap(list[static_cast<std::size_t>(i - 1)], list[static_cast<std::size_t>(j)]);
  }
}

}  // namespace tack::strat

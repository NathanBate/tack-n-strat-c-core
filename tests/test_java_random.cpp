#include <cmath>

#include <doctest/doctest.h>

#include "tack/strat/java_random.hpp"

TEST_CASE("JavaRandom matches java.util.Random for seed 12345L") {
  tack::strat::JavaRandom r(12345LL);
  CHECK(r.next_int_bounded(100) == 51);
  CHECK(r.next_int_bounded(100) == 80);
  CHECK(r.next_int_bounded(100) == 41);
  CHECK(r.next_int_bounded(100) == 28);
  CHECK(r.next_int_bounded(100) == 55);

  tack::strat::JavaRandom r2(12345LL);
  CHECK(std::abs(r2.next_double() - 0.3618031071604718) < 1e-15);
}

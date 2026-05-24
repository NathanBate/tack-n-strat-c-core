#include <doctest/doctest.h>

#include "tack/strat/combat_rng.hpp"
#include "tack/strat/terrain.hpp"

TEST_CASE("terrain movement_cost matches Java Terrain enum") {
  using tack::strat::Terrain;
  CHECK(tack::strat::movement_cost(Terrain::GRASS) == 1);
  CHECK(tack::strat::movement_cost(Terrain::FOREST) == 2);
  CHECK(tack::strat::movement_cost(Terrain::WATER) > 1000000);
}

TEST_CASE("combat RNG consumes nextInt(3) like Java GameSession.newCombatRng") {
  tack::strat::CombatRng fresh(1LL, 0);
  CHECK(fresh.next_int_3() >= 0);
  CHECK(fresh.next_int_3() <= 2);

  tack::strat::CombatRng offset(1LL, 3);
  tack::strat::CombatRng baseline(1LL, 0);
  for (int i = 0; i < 3; ++i) {
    (void)baseline.next_int_3();
  }
  CHECK(offset.next_int_3() == baseline.next_int_3());
}

TEST_CASE("combat RNG nextInt(2) parity for hunt rolls") {
  tack::strat::CombatRng r(99LL, 0);
  CHECK(r.next_int_2() >= 0);
  CHECK(r.next_int_2() <= 1);
  CHECK(r.call_count() == 2);
}

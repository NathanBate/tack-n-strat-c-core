#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <unordered_set>

#include "tack/strat/chronology.hpp"
#include "tack/strat/hex.hpp"

TEST_CASE("Chronology matches Java START_YEAR_BCE label at era 0") {
  CHECK(tack::strat::Chronology::format_era(0) == "4000 BCE");
}

TEST_CASE("hex axial distance — neighbors are 1 apart") {
  tack::strat::HexCoord origin{0, 0};
  for (auto n : origin.neighbors()) {
    CHECK(origin.distance_to(n) == 1);
  }
}

TEST_CASE("odd-q rectangle has expected count and unique cells") {
  using tack::strat::HexCoord;
  auto const v = HexCoord::odd_q_rectangle(7, 5);
  CHECK(v.size() == 35);
  std::unordered_set<unsigned long long> seen;
  for (tack::strat::HexCoord h : v) {
    unsigned long long const key =
        (static_cast<unsigned long long>(static_cast<unsigned>(h.q)) << 32U) ^
        static_cast<unsigned long long>(static_cast<unsigned>(h.r));
    CHECK(seen.insert(key).second);
  }
}

#include <doctest/doctest.h>
#include <algorithm>
#include <string>

#include "tack/strat/game_session.hpp"
#include "tack/strat/game_snapshot.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/snapshot_json_io.hpp"

TEST_CASE("two_player hot_seat rotates each end_turn (Java GameSessionTest)") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "A"}, tack::strat::Player{1, "B"}};
  tack::strat::GameSession session(std::move(players), 42'424LL);
  CHECK(session.current_player().seat == 0);
  static_cast<void>(session.end_turn());
  CHECK(session.current_player().seat == 1);
  static_cast<void>(session.end_turn());
  CHECK(session.current_player().seat == 0);
}

TEST_CASE("chronology ticks after full rotation (Java GameSessionTest)") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "A"}, tack::strat::Player{1, "B"}};
  tack::strat::GameSession session(std::move(players), 99'001LL);
  int years_per = session.years_per_full_round();
  int before = session.chronology_offset_years();
  static_cast<void>(session.end_turn());
  static_cast<void>(session.end_turn());
  CHECK(before + years_per == session.chronology_offset_years());
  CHECK(session.round() >= 2);
}

TEST_CASE("weather HUD summary mentions Weather (Java GameSessionTest)") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "Only"}};
  tack::strat::GameSession session(std::move(players), 77'007LL);
  auto const summary = session.weather_hud_summary();
  CHECK(summary.find("Weather") != std::string::npos);
}

TEST_CASE("bypass session: settler founds city (Java founding rules)") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(10, 0, UnitKind::SETTLER, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {}, 5001LL);
  CHECK(session.cities().empty());
  auto city = session.found_city(10);
  REQUIRE(city.has_value());
  CHECK(session.city_count_for(0) == 1);
  CHECK(session.unit_count_for(0) == 0);
  REQUIRE(session.unit_at(HexCoord{0, 0}) == std::nullopt);
}

TEST_CASE("planned route moves unit at start of end_turn (Java)") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(7, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {}, 9001LL);
  HexCoord east{1, 0};
  CHECK(session.map().contains(east));
  std::vector<tack::strat::HexCoord> path{{0, 0}, east};
  CHECK(session.assign_planned_route(7, path));
  static_cast<void>(session.end_turn());
  auto u = session.unit_at(east);
  REQUIRE(u.has_value());
  CHECK(u->id() == 7);
}

TEST_CASE("manual move clears planned route when not following route (Java)") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(7, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {}, 9002LL);
  HexCoord east{1, 0};
  HexCoord south_east{0, 1};
  CHECK(session.assign_planned_route(7, std::vector<HexCoord>{{0, 0}, east}));
  CHECK(session.try_move_unit(7, south_east));
  CHECK(session.planned_route_for(7).empty());
}

TEST_CASE("solo city accumulates production and spawns warrior (Java produce/drain)") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::City;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Terrain;
  using tack::strat::UnitKind;

  tack::strat::GameMap::Builder mb(1);
  mb.set(HexCoord{0, 0}, Terrain::HILL);
  GameMap map = mb.build();

  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<tack::strat::Unit> units;
  std::vector<City> cities;
  cities.emplace_back(1, 0, HexCoord{0, 0}, "Cap", UnitKind::WARRIOR);

  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), std::move(cities), {}, {},
                      {}, 12003LL);

  int before_units = session.unit_count_for(0);
  int peak_units = before_units;
  for (int i = 0; i < 15; ++i) {
    static_cast<void>(session.end_turn());
    peak_units = std::max(peak_units, session.unit_count_for(0));
  }
  CHECK(peak_units > before_units);
}

TEST_CASE("claim_debug_at describes owning city (Java parity)") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::City;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Alice"}};
  std::vector<tack::strat::Unit> units;
  std::vector<City> cities;
  cities.emplace_back(2, 0, HexCoord{0, 0}, "Athens", UnitKind::WARRIOR);
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), std::move(cities), {}, {}, {},
                      777LL);
  HexCoord n = HexCoord{1, 0};
  REQUIRE(session.map().contains(n));
  std::string dbg = session.claim_debug_at(n);
  CHECK(dbg.find("Claim:") != std::string::npos);
  CHECK(dbg.find("Alice") != std::string::npos);
  CHECK(dbg.find("Athens") != std::string::npos);
}

TEST_CASE("GameSnapshot capture/restore round-trip") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::City;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::GameSnapshot;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(5, 0, UnitKind::WARRIOR, HexCoord{1, 0});
  std::vector<City> cities;
  cities.emplace_back(1, 0, HexCoord{0, 0}, "Rome", UnitKind::SCOUT);

  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), std::move(cities), {}, {}, {},
                      424242LL);

  GameSnapshot a = session.capture();
  GameSession back = GameSession::restore(a);
  GameSnapshot b = back.capture();
  CHECK(a == b);
}

TEST_CASE("GameSnapshot JSON stringify round-trip") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::City;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::GameSnapshot;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(5, 0, UnitKind::WARRIOR, HexCoord{1, 0});
  std::vector<City> cities;
  cities.emplace_back(1, 0, HexCoord{0, 0}, "Rome", UnitKind::SCOUT);

  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), std::move(cities), {}, {}, {},
                      424242LL);

  GameSnapshot const a = session.capture();
  std::string const js = tack::strat::snapshot_to_json_string(a);
  std::optional<GameSnapshot> parsed = tack::strat::snapshot_from_json_string(js);
  REQUIRE(parsed.has_value());
  CHECK(*parsed == a);
  GameSession back = tack::strat::GameSession::restore(std::move(*parsed));
  CHECK(back.capture() == a);
}

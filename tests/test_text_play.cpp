#include <doctest/doctest.h>

#include <sstream>
#include <string>

#include "tack/strat/game_session.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/text_play.hpp"
#include "tack/strat/unit_kind.hpp"

TEST_CASE("text play: unknown command") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "Solo"}};
  tack::strat::GameSession session(std::move(players), 12'345LL);
  tack::strat::TextPlayDriver driver(std::move(session));
  auto o = driver.handle_line("xyzzy");
  CHECK_FALSE(o.ok);
  CHECK_FALSE(o.exit_repl);
  CHECK(o.message.find("Unknown") != std::string::npos);
}

TEST_CASE("text play: help and quit") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "Solo"}};
  tack::strat::GameSession session(std::move(players), 12'345LL);
  tack::strat::TextPlayDriver driver(std::move(session));
  auto h = driver.handle_line("help");
  CHECK(h.ok);
  CHECK_FALSE(h.exit_repl);
  CHECK(h.message.find("move") != std::string::npos);

  auto q = driver.handle_line("quit");
  CHECK(q.ok);
  CHECK(q.exit_repl);
}

TEST_CASE("text play: REPL exits on quit") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "Solo"}};
  tack::strat::GameSession session(std::move(players), 12'345LL);
  tack::strat::TextPlayDriver driver(std::move(session));

  std::istringstream in("help\nquit\n");
  std::ostringstream out;
  driver.run_repl(in, out);
  std::string all = out.str();
  CHECK(all.find("Commands:") != std::string::npos);
  CHECK(all.find("Goodbye.") != std::string::npos);
}

TEST_CASE("text play: scripted founding on bypass map") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap map;
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(10, 0, UnitKind::SETTLER, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {},
                      5001LL);

  TextPlayDriver driver(std::move(session));
  CHECK(driver.handle_line("units").ok);
  CHECK(driver.session().cities().empty());

  auto r = driver.handle_line("found 10");
  REQUIRE(r.ok);
  CHECK(driver.session().city_count_for(0) == 1);
  CHECK(driver.session().unit_count_for(0) == 0);
}

TEST_CASE("text play: end advances solo round") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap::Builder mb(1);
  GameMap map = mb.build();
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(1, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {},
                      9003LL);

  TextPlayDriver driver(std::move(session));
  int const before_round = driver.session().round();
  int const before_years = driver.session().chronology_offset_years();
  REQUIRE(driver.handle_line("end").ok);
  CHECK(driver.session().round() == before_round + 1);
  CHECK(driver.session().chronology_offset_years() ==
        before_years + driver.session().years_per_full_round());
}

TEST_CASE("text play: two-player hot-seat chronology script") {
  using tack::strat::GameSession;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;

  std::vector<Player> players{Player{0, "A"}, Player{1, "B"}};
  GameSession session(std::move(players), 99'001LL);
  TextPlayDriver driver(std::move(session));

  int const ypr = driver.session().years_per_full_round();
  REQUIRE(driver.handle_line("end").ok);
  REQUIRE(driver.handle_line("end").ok);
  CHECK(driver.session().round() == 2);
  CHECK(driver.session().chronology_offset_years() == ypr);
}

TEST_CASE("text play: 50 end turns solo bypass — final state via status") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap::Builder mb(1);
  GameMap map = mb.build();
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(1, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {},
                      90'045LL);

  TextPlayDriver driver(std::move(session));
  int const ypr = driver.session().years_per_full_round();

  for (int i = 0; i < 50; ++i) {
    REQUIRE_FALSE(driver.session().is_over());
    REQUIRE(driver.handle_line("end").ok);
  }

  REQUIRE(driver.session().round() == 51);
  CHECK(driver.session().chronology_offset_years() == 50 * ypr);

  auto snapshot = driver.handle_line("status");
  REQUIRE(snapshot.ok);
  INFO("Final status after 50 end turns:\n", snapshot.message);
  CHECK(snapshot.message.find("Round 51") != std::string::npos);
  CHECK(snapshot.message.find("seat 0 human") != std::string::npos);

  auto roster = driver.handle_line("units");
  REQUIRE(roster.ok);
  INFO("Units after 50 end turns:\n", roster.message);
}

TEST_CASE("text play: REPL auto-passes computer seat") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;
  using tack::strat::Unit;
  using tack::strat::UnitKind;

  GameMap::Builder mb(1);
  GameMap map = mb.build();
  std::vector<Player> players{Player{0, "P0", false}, Player{1, "P1", true}};
  std::vector<Unit> units;
  units.emplace_back(1, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  units.emplace_back(2, 1, UnitKind::WARRIOR, HexCoord{1, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {},
                      90'046LL);

  TextPlayDriver driver(std::move(session));
  std::istringstream in("end\nquit\n");
  std::ostringstream out;
  driver.run_repl(in, out);
  std::string const all = out.str();
  CHECK(all.find("(computer) seat 1 ends turn.") != std::string::npos);
}

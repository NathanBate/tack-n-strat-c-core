#include <doctest/doctest.h>

#include <array>
#include <vector>

#include "tack/strat/chronology.hpp"
#include "tack/strat/city.hpp"
#include "tack/strat/game_session.hpp"
#include "tack/strat/game_snapshot.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/season.hpp"
#include "tack/strat/terrain.hpp"
#include "tack/strat/tile_improvement.hpp"
#include "tack/strat/unit_kind.hpp"
#include "tack/strat/wild_animal.hpp"

namespace tack::strat::long_run_test {

// Mirrors GameSession wildlife hard cap (private in game_session.cpp).
int constexpr kWildlifeHardCap = 12;

std::array<int, 4> constexpr kTurnCheckpoints{50, 100, 200, 300};

void assert_timekeeping_matches_rotation(GameSession const& session, int completed_end_turn_steps) {
  int const p = static_cast<int>(session.players().size());
  REQUIRE(p >= 1);
  int const ypr = session.years_per_full_round();
  int const full_rotations = completed_end_turn_steps / p;
  CHECK(session.round() == 1 + full_rotations);
  CHECK(session.chronology_offset_years() == full_rotations * ypr);
  int const idx =
      Chronology::season_index_from_elapsed_years(session.chronology_offset_years(), ypr);
  CHECK(static_cast<int>(session.season()) ==
        static_cast<int>(season_from_ordinal(idx)));
}

void assert_game_rules_hold(GameSession const& session, int completed_end_turn_steps) {
  assert_timekeeping_matches_rotation(session, completed_end_turn_steps);

  GameMap const& map = session.map();
  REQUIRE(map.radius() >= 0);

  for (Unit const& u : session.units()) {
    REQUIRE_MESSAGE(map.contains(u.coord()), "unit standing off-map");
    Terrain const t = session.terrain_effective_at(u.coord());
    REQUIRE_MESSAGE(passable(t), "unit on impassable terrain");
    CHECK(u.hp() >= 0);
    CHECK(u.hp() <= u.max_hp());
    CHECK(u.moves_remaining() >= 0);
    CHECK(u.moves_remaining() <= unit_movement(u.kind()));
  }

  for (City const& c : session.cities()) {
    REQUIRE(map.contains(c.coord()));
    Terrain const t = session.terrain_effective_at(c.coord());
    CHECK(can_found_city_on(t));
    CHECK(c.population() >= 1);
    CHECK(c.hp() >= 1);
    CHECK(c.hp() <= City::MAX_HP);
    CHECK(c.production_stored() >= 0);
    CHECK(c.food_stored() >= 0);
    CHECK(c.owner_seat() >= 0);
  }

  int cities_accounted = 0;
  for (Player const& pl : session.players()) {
    cities_accounted += session.city_count_for(pl.seat);
    CHECK(session.gold_for(pl.seat) >= 0);
    (void)session.visited_for(pl.seat);
  }
  CHECK(cities_accounted == static_cast<int>(session.cities().size()));

  CHECK(session.wildlife_list().size() <= kWildlifeHardCap);
  for (WildAnimal const& w : session.wildlife_list()) {
    REQUIRE(map.contains(w.coord()));
    CHECK(w.hp() >= 0);
    CHECK(w.hp() <= wild_max_hp(w.kind()));
  }

  for (HexCoord const c : map.all_cells()) {
    int const cult = session.cultivation_at(c);
    CHECK(cult >= 0);
    CHECK(cult <= 2);
    int const soil = session.soil_fertility_at(c);
    CHECK(soil >= -5);
    CHECK(soil <= 5);
    TileImprovement const imp = session.improvement_at(c);
    bool const known_imp = imp == TileImprovement::NONE || imp == TileImprovement::FARM ||
                           imp == TileImprovement::MINE;
    CHECK(known_imp);
  }

  if (session.is_over()) {
    REQUIRE(session.winner_seat().has_value());
    int const w = *session.winner_seat();
    CHECK(w >= 0);
    CHECK(static_cast<std::size_t>(w) < session.players().size());
  } else {
    CHECK_FALSE(session.winner_seat().has_value());
  }

  CHECK(session.combat_rng_call_count() >= 0);
}

}  // namespace tack::strat::long_run_test

TEST_CASE("rules and timekeeping at 50/100/200/300 end_turn steps — bypass solo") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::GameSnapshot;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::Unit;
  using tack::strat::UnitKind;
  namespace lr = tack::strat::long_run_test;

  GameMap::Builder mb(1);
  GameMap map = mb.build();

  // Solo avoids elimination victory when wildlife eventually kills the lone unit (multiplayer
  // elimination would end the session mid-loop).
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(1, 0, UnitKind::WARRIOR, HexCoord{0, 0});

  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {},
                      {}, 80'080LL);

  std::size_t next_checkpoint = 0;
  for (int step = 1; step <= 300; ++step) {
    REQUIRE_FALSE(session.is_over());
    static_cast<void>(session.end_turn());
    if (next_checkpoint < lr::kTurnCheckpoints.size() &&
        step == lr::kTurnCheckpoints[next_checkpoint]) {
      lr::assert_game_rules_hold(session, step);
      GameSnapshot snap = session.capture();
      GameSession restored = GameSession::restore(snap);
      lr::assert_game_rules_hold(restored, step);
      CHECK(restored.round() == session.round());
      CHECK(restored.chronology_offset_years() == session.chronology_offset_years());
      ++next_checkpoint;
    }
  }
  REQUIRE(next_checkpoint == lr::kTurnCheckpoints.size());
}

TEST_CASE("rules and timekeeping at 50/100/200/300 — generated world two-player") {
  using tack::strat::GameSession;
  using tack::strat::MapSizePreset;
  using tack::strat::Player;
  namespace lr = tack::strat::long_run_test;

  std::vector<Player> players{Player{0, "A"}, Player{1, "B"}};
  // Compact preset keeps this regression deterministic (medium/large maps + same seed can end early).
  GameSession session(std::move(players), 42'424LL, MapSizePreset::Tiny);

  std::size_t next_checkpoint = 0;
  for (int step = 1; step <= 300; ++step) {
    static_cast<void>(session.end_turn());
    if (next_checkpoint < lr::kTurnCheckpoints.size() &&
        step == lr::kTurnCheckpoints[next_checkpoint]) {
      lr::assert_game_rules_hold(session, step);
      ++next_checkpoint;
    }
    if (session.is_over()) {
      INFO("generated session ended early at end_turn step ", step);
      break;
    }
  }
  REQUIRE_MESSAGE(next_checkpoint == lr::kTurnCheckpoints.size(),
                  "fixed seed must stay active through 300 end_turn steps for all checkpoints");
}

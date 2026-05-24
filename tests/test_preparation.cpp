#include <doctest/doctest.h>

#include "tack/strat/command_script.hpp"
#include "tack/strat/engine_contract.hpp"
#include "tack/strat/game_session.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/net/turn_sync.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/snapshot_digest.hpp"
#include "tack/strat/snapshot_validate.hpp"
#include "tack/strat/text_play.hpp"

TEST_CASE("map presets: Civ VI-scale hex disk counts + tiny default path") {
  using tack::strat::MapSizePreset;
  using tack::strat::map_civ_hex_dims;
  using tack::strat::map_disk_hex_count_approx;
  using tack::strat::map_hex_radius;

  CHECK(map_hex_radius(MapSizePreset::Tiny) == 16);
  CHECK(map_hex_radius(MapSizePreset::Small) == 27);
  CHECK(map_hex_radius(MapSizePreset::Medium) == 39);
  CHECK(map_hex_radius(MapSizePreset::Large) == 48);

  CHECK(map_disk_hex_count_approx(MapSizePreset::Tiny) < 1'200);   // < Civ VI Duel (~1144)
  CHECK(map_disk_hex_count_approx(MapSizePreset::Small) > 2'000);  // ~ Civ VI Tiny (~2280)
  CHECK(map_disk_hex_count_approx(MapSizePreset::Small) < 2'500);
  CHECK(map_disk_hex_count_approx(MapSizePreset::Medium) > 4'000);  // ~ Civ VI Standard (~4536)
  CHECK(map_disk_hex_count_approx(MapSizePreset::Medium) < 5'200);
  CHECK(map_disk_hex_count_approx(MapSizePreset::Large) > 6'500);  // ~ Civ VI Huge (~6996)
  CHECK(map_disk_hex_count_approx(MapSizePreset::Large) < 7'500);

  for (auto p : {MapSizePreset::Tiny, MapSizePreset::Small, MapSizePreset::Medium,
                 MapSizePreset::Large}) {
    auto const d = map_civ_hex_dims(p);
    CHECK(d.cols >= 12);
    CHECK(d.rows >= 10);
    int const n = d.cols * d.rows;
    CHECK(n >= map_disk_hex_count_approx(p));
    CHECK(n <= map_disk_hex_count_approx(p) + d.cols + d.rows);
  }
}

TEST_CASE("GameSession default ctor uses Tiny map (fast tests)") {
  using tack::strat::GameSession;
  using tack::strat::MapSizePreset;
  using tack::strat::Player;
  using tack::strat::map_civ_hex_dims;

  std::vector<Player> players{Player{0, "A"}};
  GameSession session(std::move(players), 50'050LL);
  auto const d = map_civ_hex_dims(MapSizePreset::Tiny);
  CHECK(session.map().all_cells().size() == d.cols * d.rows);
  REQUIRE(session.map_size_preset() == MapSizePreset::Tiny);
}

TEST_CASE("validate_snapshot_for_engine accepts capture") {
  std::vector<tack::strat::Player> players{tack::strat::Player{0, "S"}};
  tack::strat::GameSession session(std::move(players), 77'707LL);
  auto snap = session.capture();
  auto v = tack::strat::validate_snapshot_for_engine(snap);
  REQUIRE(v.ok);
}

TEST_CASE("snapshot_stable_digest unchanged across capture/restore") {
  using tack::strat::BypassWorldGenTag;
  using tack::strat::GameMap;
  using tack::strat::GameSession;
  using tack::strat::HexCoord;
  using tack::strat::Player;
  using tack::strat::TextPlayDriver;
  using tack::strat::Unit;
  using tack::strat::UnitKind;
  using tack::strat::replay_command_lines;
  using tack::strat::snapshot_stable_digest;

  GameMap::Builder mb(1);
  tack::strat::GameMap map = mb.build();
  std::vector<Player> players{Player{0, "Solo"}};
  std::vector<Unit> units;
  units.emplace_back(5, 0, UnitKind::WARRIOR, HexCoord{0, 0});
  GameSession session(BypassWorldGenTag{}, std::move(players), map, std::move(units), {}, {}, {}, {},
                      61'061LL);

  TextPlayDriver driver(std::move(session));
  std::vector<std::string_view> script{"end", "end", "end"};
  REQUIRE(replay_command_lines(driver, script).ok);

  auto snap = driver.session().capture();
  REQUIRE(tack::strat::validate_snapshot_for_engine(snap).ok);
  std::uint64_t const d1 = snapshot_stable_digest(snap);
  tack::strat::GameSession back = tack::strat::GameSession::restore(snap);
  std::uint64_t const d2 = snapshot_stable_digest(back.capture());
  CHECK(d1 == d2);
}

TEST_CASE("TurnSyncPoller throttles polls") {
  using tack::strat::net::PollStateResult;
  using tack::strat::net::StubTurnSyncTransport;
  using tack::strat::net::TurnSyncClientConfig;
  using tack::strat::net::TurnSyncPoller;

  StubTurnSyncTransport stub;
  stub.next_poll = PollStateResult{.transport_ok = true,
                                  .http_status = 200,
                                  .error_message = {},
                                  .response_body = R"({"turn_seq":1})"};

  TurnSyncClientConfig cfg;
  cfg.poll_interval_seconds = 2;
  cfg.api_base_url = "https://example.test/api";
  TurnSyncPoller poller(cfg, stub);

  auto a = poller.poll_game_state_if_due("game-a");
  REQUIRE(a.transport_ok);
  CHECK(a.response_body.find("turn_seq") != std::string::npos);

  auto b = poller.poll_game_state_if_due("game-a");
  REQUIRE_FALSE(b.transport_ok);

  poller.reset_poll_clock_for_tests();
  auto c = poller.poll_game_state_if_due("game-a");
  REQUIRE(c.transport_ok);
}

TEST_CASE("engine_contract snapshot version matches GameSnapshot") {
  CHECK(tack::strat::engine_contract::kSnapshotFormatVersion ==
        tack::strat::GameSnapshot::FORMAT_VERSION);
}

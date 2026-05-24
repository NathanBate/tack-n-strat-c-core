#include <doctest/doctest.h>

#include "tack/strat/game_session.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/seat_ai.hpp"

using tack::strat::GameSession;
using tack::strat::JavaRandom;
using tack::strat::Player;
using tack::strat::seat_ai_tick;

TEST_CASE("seat_ai_tick is no-op when current seat is human") {
  GameSession session({Player(0, "Human"), Player(1, "Other")}, 120'003LL);
  JavaRandom rng(1);
  CHECK_FALSE(seat_ai_tick(session, rng));
}

TEST_CASE("seat_ai_tick runs when current seat is CPU") {
  GameSession session({Player(0, "Human", false), Player(1, "CPU", true)}, 120'004LL);
  static_cast<void>(session.end_turn());
  CHECK(session.current_player().computer);
  JavaRandom rng(2);
  CHECK_NOTHROW(static_cast<void>(seat_ai_tick(session, rng)));
}

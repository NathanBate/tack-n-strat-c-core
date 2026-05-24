#pragma once

#include "tack/strat/java_random.hpp"

namespace tack::strat {

class GameSession;

/** Same salt as Java {@code PlayPanel#runComputerTurn}: worldSeed ^ (round<<16) ^ (playerIndex<<24). */
[[nodiscard]] JavaRandom seat_ai_rng(GameSession const& session);

/**
 * One atomic AI step for the current seat (Java {@code SeatAi#tick}).
 * @return true if state changed and another tick may be useful this turn.
 */
[[nodiscard]] bool seat_ai_tick(GameSession& session, JavaRandom& rng);

}  // namespace tack::strat

#pragma once

#include <cstdint>

#include "tack/strat/game_snapshot.hpp"

namespace tack::strat {

/** Stable 64-bit fingerprint for regression tests (not cryptographic). */
[[nodiscard]] std::uint64_t snapshot_stable_digest(GameSnapshot const& snap);

}  // namespace tack::strat

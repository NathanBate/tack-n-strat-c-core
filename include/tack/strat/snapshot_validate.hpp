#pragma once

#include <string>

#include "tack/strat/game_snapshot.hpp"

namespace tack::strat {

struct SnapshotValidateResult {
  bool ok{};
  std::string message;
};

/** Structural checks before {@link GameSession::restore}; does not guarantee restore succeeds. */
[[nodiscard]] SnapshotValidateResult validate_snapshot_for_engine(GameSnapshot const& snap);

}  // namespace tack::strat

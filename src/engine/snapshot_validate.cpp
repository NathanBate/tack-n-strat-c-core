#include "tack/strat/snapshot_validate.hpp"

#include <algorithm>
#include <sstream>
#include <unordered_set>

#include "tack/strat/hex.hpp"

namespace tack::strat {

SnapshotValidateResult validate_snapshot_for_engine(GameSnapshot const& snap) {
  if (snap.format_version < 1 || snap.format_version > GameSnapshot::FORMAT_VERSION) {
    return SnapshotValidateResult{false, "Unsupported format_version"};
  }
  if (snap.players.empty() || snap.players.size() > 4) {
    return SnapshotValidateResult{false, "Invalid player count"};
  }
  if (snap.map_radius < 0 || snap.map_radius > 4096) {
    return SnapshotValidateResult{false, "Suspicious map_radius"};
  }
  std::unordered_set<int> seats;
  for (Player const& p : snap.players) {
    seats.insert(p.seat);
  }
  if (seats.size() != snap.players.size()) {
    return SnapshotValidateResult{false, "Duplicate player seats"};
  }

  auto seat_recorded = [&](int seat) {
    return seats.count(seat) != 0;
  };

  HexCoord origin{0, 0};
  for (auto const& cell : snap.map_cells) {
    HexCoord h{cell.q, cell.r};
    if (h.distance_to(origin) > snap.map_radius) {
      std::ostringstream o;
      o << "Map cell (" << cell.q << ',' << cell.r << ") outside disk radius " << snap.map_radius;
      return SnapshotValidateResult{false, o.str()};
    }
  }

  for (auto const& u : snap.units) {
    if (!seat_recorded(u.owner_seat)) {
      return SnapshotValidateResult{false, "Unit references unknown seat"};
    }
  }
  return SnapshotValidateResult{true, {}};
}

}  // namespace tack::strat

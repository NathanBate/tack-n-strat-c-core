#include "tack/strat/hex.hpp"

#include <algorithm>

namespace tack::strat {

namespace {

constexpr std::array<HexCoord, 6> kNeighborDeltas{{
    HexCoord{1, 0},
    HexCoord{1, -1},
    HexCoord{0, -1},
    HexCoord{-1, 0},
    HexCoord{-1, 1},
    HexCoord{0, 1},
}};

}  // namespace

std::array<HexCoord, 6> HexCoord::neighbors() const {
  std::array<HexCoord, 6> out{};
  for (std::size_t i = 0; i < kNeighborDeltas.size(); ++i) {
    out[i] = add(kNeighborDeltas[i]);
  }
  return out;
}

int HexCoord::distance_to(HexCoord o) const noexcept {
  int const x1 = q;
  int const z1 = r;
  int const y1 = -q - r;
  int const x2 = o.q;
  int const z2 = o.r;
  int const y2 = -o.q - o.r;
  return (std::abs(x1 - x2) + std::abs(y1 - y2) + std::abs(z1 - z2)) / 2;
}

HexCoord HexCoord::add(HexCoord d) const noexcept {
  return HexCoord{q + d.q, r + d.r};
}

std::vector<HexCoord> HexCoord::disk(int radius) {
  std::vector<HexCoord> out;
  out.reserve(static_cast<std::size_t>((2 * radius + 1) * (2 * radius + 1)));
  for (int dq = -radius; dq <= radius; ++dq) {
    int const r_min = std::max(-radius, -dq - radius);
    int const r_max = std::min(radius, -dq + radius);
    for (int dr = r_min; dr <= r_max; ++dr) {
      out.push_back(HexCoord{dq, dr});
    }
  }
  return out;
}

std::vector<HexCoord> HexCoord::odd_q_rectangle(int cols, int rows) {
  std::vector<HexCoord> out;
  if (cols <= 0 || rows <= 0) {
    return out;
  }
  out.reserve(static_cast<std::size_t>(cols) * static_cast<std::size_t>(rows));
  int const col0 = -(cols / 2);
  int const row0 = -(rows / 2);
  for (int ci = 0; ci < cols; ++ci) {
    int const col = col0 + ci;
    for (int ri = 0; ri < rows; ++ri) {
      int const row = row0 + ri;
      int const q = col;
      int const r = row - (col - (col & 1)) / 2;
      out.push_back(HexCoord{q, r});
    }
  }
  return out;
}

}  // namespace tack::strat

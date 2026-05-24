#include "tack/strat/game_map.hpp"

#include <algorithm>

namespace tack::strat {

GameMap::GameMap() : radius_(1), terrain_() {
  for (HexCoord const c : HexCoord::disk(1)) {
    terrain_[c] = Terrain::GRASS;
  }
}

GameMap::GameMap(int radius, std::unordered_map<HexCoord, Terrain> terrain)
    : radius_(radius), terrain_(std::move(terrain)) {}

std::vector<HexCoord> GameMap::all_cells() const {
  std::vector<HexCoord> out;
  out.reserve(terrain_.size());
  for (auto const& e : terrain_) {
    out.push_back(e.first);
  }
  std::sort(out.begin(), out.end(), [](HexCoord a, HexCoord b) {
    return a.q != b.q ? a.q < b.q : a.r < b.r;
  });
  return out;
}

std::vector<HexCoord> GameMap::passable_land() const {
  std::vector<HexCoord> out;
  for (auto const& e : terrain_) {
    if (passable(e.second)) {
      out.push_back(e.first);
    }
  }
  std::sort(out.begin(), out.end(), [](HexCoord a, HexCoord b) {
    return a.q != b.q ? a.q < b.q : a.r < b.r;
  });
  return out;
}

GameMap::Builder::Builder(int disk_radius) : radius_(disk_radius) {
  for (HexCoord const c : HexCoord::disk(disk_radius)) {
    cells_.emplace(c, Terrain::GRASS);
  }
}

GameMap::Builder::Builder(CivHexRectangleTag /*tag*/, int cols, int rows) : radius_(0) {
  for (HexCoord const c : HexCoord::odd_q_rectangle(cols, rows)) {
    cells_.emplace(c, Terrain::GRASS);
  }
  int mr = 0;
  for (auto const& e : cells_) {
    HexCoord const h = e.first;
    int const d = std::max({std::abs(h.q), std::abs(h.r), std::abs(h.q + h.r)});
    mr = std::max(mr, d);
  }
  radius_ = std::max(1, mr);
}

void GameMap::Builder::set(HexCoord c, Terrain t) {
  auto it = cells_.find(c);
  if (it == cells_.end()) {
    throw std::invalid_argument("Out of map");
  }
  it->second = t;
}

GameMap GameMap::Builder::build() {
  return GameMap(radius_, cells_);
}

}  // namespace tack::strat

#pragma once

#include <stdexcept>
#include <unordered_map>
#include <vector>

#include "tack/strat/hex.hpp"
#include "tack/strat/terrain.hpp"

namespace tack::strat {

struct CivHexRectangleTag {
  explicit CivHexRectangleTag() = default;
};

class GameMap {
 public:
  GameMap();

  GameMap(int radius, std::unordered_map<HexCoord, Terrain> terrain);

  [[nodiscard]] int radius() const noexcept {
    return radius_;
  }
  [[nodiscard]] bool contains(HexCoord c) const {
    return terrain_.find(c) != terrain_.end();
  }
  [[nodiscard]] Terrain terrain_at(HexCoord c) const {
    auto it = terrain_.find(c);
    if (it == terrain_.end()) {
      throw std::out_of_range("terrain_at: hex not on map");
    }
    return it->second;
  }

  [[nodiscard]] std::vector<HexCoord> all_cells() const;
  /** Sorted by (q, r) for stable engine behavior (Java used hash-map iteration where undefined). */
  [[nodiscard]] std::vector<HexCoord> passable_land() const;

  class Builder {
   public:
    explicit Builder(int disk_radius);
    Builder(CivHexRectangleTag, int cols, int rows);

    void set(HexCoord c, Terrain t);
    [[nodiscard]] GameMap build();

    [[nodiscard]] std::unordered_map<HexCoord, Terrain>& mutable_cells() noexcept {
      return cells_;
    }

   private:
    int radius_{};
    std::unordered_map<HexCoord, Terrain> cells_;
  };

 private:
  int radius_{};
  std::unordered_map<HexCoord, Terrain> terrain_;
};

}  // namespace tack::strat

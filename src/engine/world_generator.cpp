#include "tack/strat/world_generator.hpp"

#include <algorithm>
#include <optional>
#include <stdexcept>
#include <unordered_map>
#include <vector>

#include "tack/strat/java_random.hpp"
#include "tack/strat/map_size.hpp"

namespace tack::strat {

namespace {

int constexpr kContinentTargetTiles = 90;

std::vector<HexCoord> pick_continent_seeds(GameMap::Builder& builder, JavaRandom& rnd, int n,
                                           int cols, int rows) {
  int const span = std::max(8, std::min(cols, rows));
  int const inner_d = std::max(3, (span * 7) / 10);
  std::vector<HexCoord> candidates;
  for (auto const& e : builder.mutable_cells()) {
    HexCoord const c = e.first;
    int const d = std::max({std::abs(c.q), std::abs(c.r), std::abs(c.q + c.r)});
    if (d <= inner_d) {
      candidates.push_back(c);
    }
  }
  java_collections_shuffle(candidates, rnd);

  std::vector<HexCoord> picked;
  int const min_dist = std::max(5, span / 3);
  for (HexCoord const c : candidates) {
    bool ok = true;
    for (HexCoord const p : picked) {
      if (p.distance_to(c) < min_dist) {
        ok = false;
        break;
      }
    }
    if (ok) {
      picked.push_back(c);
      if (static_cast<int>(picked.size()) == n) {
        break;
      }
    }
  }
  for (HexCoord const c : candidates) {
    if (static_cast<int>(picked.size()) == n) {
      break;
    }
    if (std::find(picked.begin(), picked.end(), c) == picked.end()) {
      picked.push_back(c);
    }
  }
  return picked;
}

void grow_continent(GameMap::Builder& builder, HexCoord seed, int continent_id,
                    std::unordered_map<HexCoord, int>& tile_to_continent, JavaRandom& rnd) {
  auto& cells = builder.mutable_cells();
  int target = kContinentTargetTiles + rnd.next_int_bounded(40) - 20;
  int converted = 0;
  std::vector<HexCoord> frontier;
  frontier.push_back(seed);

  while (converted < target && !frontier.empty()) {
    int weight = std::min(static_cast<int>(frontier.size()), 12);
    int pick_idx = rnd.next_int_bounded(weight);
    HexCoord c = frontier[static_cast<std::size_t>(pick_idx)];
    frontier.erase(frontier.begin() + pick_idx);

    auto it = cells.find(c);
    if (it == cells.end() || it->second != Terrain::WATER || tile_to_continent.count(c)) {
      continue;
    }

    it->second = Terrain::GRASS;
    tile_to_continent[c] = continent_id;
    converted++;

    for (HexCoord n : c.neighbors()) {
      auto j = cells.find(n);
      if (j != cells.end() && j->second == Terrain::WATER && !tile_to_continent.count(n)) {
        frontier.push_back(n);
      }
    }
    if (rnd.next_int_bounded(4) == 0) {
      java_collections_shuffle(frontier, rnd);
    }
  }
}

Terrain pick_biome(HexCoord c, GameMap::Builder& builder, JavaRandom& rnd) {
  auto& cells = builder.mutable_cells();
  int water_neighbors = 0;
  int existing = 0;
  for (HexCoord n : c.neighbors()) {
    auto j = cells.find(n);
    if (j == cells.end()) {
      water_neighbors++;
      continue;
    }
    existing++;
    if (j->second == Terrain::WATER) {
      water_neighbors++;
    }
  }
  bool coastal = water_neighbors >= 2 || existing < 6;

  double roll = rnd.next_double();
  if (coastal) {
    if (roll < 0.55) return Terrain::GRASS;
    if (roll < 0.85) return Terrain::PLAINS;
    return Terrain::FOREST;
  }
  if (roll < 0.28) return Terrain::GRASS;
  if (roll < 0.55) return Terrain::PLAINS;
  if (roll < 0.85) return Terrain::FOREST;
  return Terrain::HILL;
}

std::optional<HexCoord> inland_tile(std::vector<HexCoord> const& tiles,
                                    std::unordered_map<HexCoord, Terrain> const& cells,
                                    JavaRandom& rnd) {
  auto copy = tiles;
  java_collections_shuffle(copy, rnd);
  for (HexCoord const c : copy) {
    int water_neighbors = 0;
    for (HexCoord n : c.neighbors()) {
      auto j = cells.find(n);
      if (j == cells.end() || j->second == Terrain::WATER) {
        water_neighbors++;
      }
    }
    if (water_neighbors == 0) {
      return c;
    }
  }
  if (!copy.empty()) {
    return copy.front();
  }
  return std::nullopt;
}

void carve_mountain_ridges(GameMap::Builder& builder,
                           std::unordered_map<HexCoord, int> const& tile_to_continent,
                           JavaRandom& rnd) {
  auto& cells = builder.mutable_cells();
  std::unordered_map<int, std::vector<HexCoord>> by_continent;
  for (auto const& e : tile_to_continent) {
    by_continent[e.second].push_back(e.first);
  }
  for (auto& entry : by_continent) {
    auto& continent = entry.second;
    if (continent.size() < 25) {
      continue;
    }
    int ridge_length = 4 + rnd.next_int_bounded(5);
    auto start_opt = inland_tile(continent, cells, rnd);
    if (!start_opt) {
      continue;
    }
    HexCoord cursor = *start_opt;
    HexCoord const dirs[] = {HexCoord{1, 0}, HexCoord{1, -1}, HexCoord{0, -1},
                             HexCoord{-1, 0}, HexCoord{-1, 1}, HexCoord{0, 1}};
    HexCoord dir = dirs[rnd.next_int_bounded(6)];
    for (int i = 0; i < ridge_length; ++i) {
      auto it = cells.find(cursor);
      if (it != cells.end() && it->second != Terrain::WATER && it->second != Terrain::MOUNTAIN) {
        it->second = Terrain::MOUNTAIN;
      }
      HexCoord step = (rnd.next_int_bounded(4) == 0) ? dirs[rnd.next_int_bounded(6)] : dir;
      cursor = cursor.add(step);
    }
  }
}

void carve_deserts(GameMap::Builder& builder,
                   std::unordered_map<HexCoord, int> const& tile_to_continent, JavaRandom& rnd) {
  auto& cells = builder.mutable_cells();
  std::unordered_map<int, std::vector<HexCoord>> by_continent;
  for (auto const& e : tile_to_continent) {
    by_continent[e.second].push_back(e.first);
  }
  for (auto& entry : by_continent) {
    auto& continent = entry.second;
    if (continent.size() < 30) {
      continue;
    }
    int blobs = 1 + rnd.next_int_bounded(2);
    for (int b = 0; b < blobs; ++b) {
      auto seed_opt = inland_tile(continent, cells, rnd);
      if (!seed_opt) {
        continue;
      }
      HexCoord seed = *seed_opt;
      int target = 4 + rnd.next_int_bounded(5);
      std::vector<HexCoord> frontier;
      frontier.push_back(seed);
      int converted = 0;
      while (converted < target && !frontier.empty()) {
        int idx = rnd.next_int_bounded(static_cast<int>(frontier.size()));
        HexCoord c = frontier[static_cast<std::size_t>(idx)];
        frontier.erase(frontier.begin() + idx);
        auto it = cells.find(c);
        if (it == cells.end()) continue;
        Terrain t = it->second;
        if (t == Terrain::WATER || t == Terrain::MOUNTAIN || t == Terrain::DESERT) {
          continue;
        }
        it->second = Terrain::DESERT;
        converted++;
        for (HexCoord n : c.neighbors()) {
          auto j = cells.find(n);
          if (j != cells.end() && j->second != Terrain::WATER) {
            frontier.push_back(n);
          }
        }
      }
    }
  }
}

HexCoord pick_interior_tile(std::vector<HexCoord> const& tiles, GameMap const& map) {
  HexCoord best{};
  int best_land = -1;
  for (HexCoord const c : tiles) {
    if (!can_found_city_on(map.terrain_at(c))) {
      continue;
    }
    int land = 0;
    for (HexCoord nb : c.neighbors()) {
      if (map.contains(nb) && passable(map.terrain_at(nb))) {
        land++;
      }
    }
    if (land > best_land) {
      best_land = land;
      best = c;
      if (best_land == 6) {
        break;
      }
    }
  }
  if (best_land < 0 && !tiles.empty()) {
    best = tiles.front();
  }
  return best;
}

std::vector<HexCoord> pick_spawns_by_continent(GameMap const& map,
                                               std::unordered_map<HexCoord, int> const& tile_to_continent,
                                               int n, JavaRandom& rnd) {
  std::unordered_map<int, std::vector<HexCoord>> by_continent;
  for (HexCoord const c : map.passable_land()) {
    int cid = -1;
    auto it = tile_to_continent.find(c);
    if (it != tile_to_continent.end()) {
      cid = it->second;
    }
    by_continent[cid].push_back(c);
  }
  std::vector<std::vector<HexCoord>> continents;
  continents.reserve(by_continent.size());
  for (auto& e : by_continent) {
    if (!e.second.empty()) {
      continents.push_back(std::move(e.second));
    }
  }
  std::sort(continents.begin(), continents.end(),
            [](auto const& a, auto const& b) { return a.size() > b.size(); });

  std::vector<HexCoord> out;
  for (auto const& continent : continents) {
    if (static_cast<int>(out.size()) == n) {
      break;
    }
    HexCoord pick = pick_interior_tile(continent, map);
    out.push_back(pick);
  }
  if (static_cast<int>(out.size()) < n) {
    auto fallback = map.passable_land();
    java_collections_shuffle(fallback, rnd);
    for (HexCoord const c : fallback) {
      if (std::find(out.begin(), out.end(), c) == out.end()) {
        out.push_back(c);
        if (static_cast<int>(out.size()) == n) {
          break;
        }
      }
    }
  }
  if (static_cast<int>(out.size()) < n) {
    throw std::runtime_error("Not enough land for player spawns");
  }
  return out;
}

std::optional<HexCoord> pick_adjacent(HexCoord home, GameMap const& map,
                                      std::vector<Unit> const& existing) {
  for (HexCoord n : home.neighbors()) {
    if (!map.contains(n) || !passable(map.terrain_at(n))) {
      continue;
    }
    bool occupied = false;
    for (Unit const& u : existing) {
      if (u.coord() == n) {
        occupied = true;
        break;
      }
    }
    if (!occupied) {
      return n;
    }
  }
  return std::nullopt;
}

}  // namespace

GeneratedWorld WorldGenerator::generate(std::vector<Player> const& players, std::int64_t seed,
                                        MapSizePreset map_size) {
  JavaRandom rnd(seed);
  MapCivHexDims const dim = map_civ_hex_dims(map_size);
  GameMap::Builder builder(CivHexRectangleTag{}, dim.cols, dim.rows);

  std::vector<HexCoord> cell_keys;
  cell_keys.reserve(builder.mutable_cells().size());
  for (auto const& e : builder.mutable_cells()) {
    cell_keys.push_back(e.first);
  }
  std::sort(cell_keys.begin(), cell_keys.end(),
             [](HexCoord a, HexCoord b) { return a.q != b.q ? a.q < b.q : a.r < b.r; });
  for (HexCoord c : cell_keys) {
    builder.set(c, Terrain::WATER);
  }

  int continents = std::max(2, static_cast<int>(players.size()) + 1);
  auto seeds = pick_continent_seeds(builder, rnd, continents, dim.cols, dim.rows);
  std::unordered_map<HexCoord, int> tile_to_continent;
  for (std::size_t idx = 0; idx < seeds.size(); ++idx) {
    grow_continent(builder, seeds[idx], static_cast<int>(idx), tile_to_continent, rnd);
  }

  std::vector<HexCoord> land_cells;
  for (auto const& e : builder.mutable_cells()) {
    if (e.second != Terrain::WATER) {
      land_cells.push_back(e.first);
    }
  }
  std::sort(land_cells.begin(), land_cells.end(),
            [](HexCoord a, HexCoord b) { return a.q != b.q ? a.q < b.q : a.r < b.r; });
  for (HexCoord c : land_cells) {
    builder.set(c, pick_biome(c, builder, rnd));
  }

  carve_mountain_ridges(builder, tile_to_continent, rnd);
  carve_deserts(builder, tile_to_continent, rnd);

  GameMap map = builder.build();
  auto spawns = pick_spawns_by_continent(map, tile_to_continent, static_cast<int>(players.size()), rnd);

  std::vector<Unit> units;
  int uid = 1;
  for (std::size_t i = 0; i < players.size(); ++i) {
    Player const& p = players[i];
    HexCoord home = spawns[i];
    units.emplace_back(uid++, p.seat, UnitKind::SETTLER, home);
    if (auto scout_coord = pick_adjacent(home, map, units)) {
      units.emplace_back(uid++, p.seat, UnitKind::SCOUT, *scout_coord);
    }
  }

  std::unordered_map<HexCoord, int> soil;
  for (HexCoord c : map.passable_land()) {
    soil[c] = rnd.next_int_bounded(3);
  }

  return GeneratedWorld{std::move(map), std::move(units), std::move(soil), {}};
}

}  // namespace tack::strat

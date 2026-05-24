#include "tack/strat/game_session.hpp"

#include <algorithm>
#include <climits>
#include <sstream>
#include <stdexcept>

#include "tack/strat/terrain.hpp"
#include "tack/strat/unit_kind.hpp"
#include "tack/strat/weather.hpp"

namespace tack::strat {
namespace {

[[nodiscard]] int wildlife_food_value(WildAnimalKind k) noexcept {
  return std::max(2, wild_max_hp(k) / 3);
}

[[nodiscard]] char const* focus_label_lower(CityFocus f) noexcept {
  switch (f) {
    case CityFocus::BALANCED:
      return "balanced";
    case CityFocus::FOOD:
      return "food";
    case CityFocus::PRODUCTION:
      return "production";
    case CityFocus::GOLD:
      return "gold";
  }
  return "balanced";
}

}  // namespace

Player const& GameSession::player_by_seat(int seat) const {
  for (Player const& p : players_) {
    if (p.seat == seat) {
      return p;
    }
  }
  throw std::invalid_argument("No such seat");
}

Season GameSession::season() const {
  return season_from_elapsed_years(chronology_offset_years_, years_per_full_round_);
}

int GameSession::city_count_for(int seat) const {
  int n = 0;
  for (City const& c : cities_) {
    if (c.owner_seat() == seat) {
      ++n;
    }
  }
  return n;
}

int GameSession::unit_count_for(int seat) const {
  int n = 0;
  for (Unit const& u : units_) {
    if (u.owner_seat() == seat) {
      ++n;
    }
  }
  return n;
}

int GameSession::gold_for(int seat) const {
  auto it = player_gold_.find(seat);
  return it == player_gold_.end() ? 0 : it->second;
}

std::unordered_set<HexCoord> GameSession::visited_for(int seat) const {
  auto it = visited_.find(seat);
  if (it == visited_.end()) {
    return {};
  }
  return it->second;
}

std::vector<HexCoord> GameSession::planned_route_for(int unit_id) const {
  auto it = planned_routes_.find(unit_id);
  if (it == planned_routes_.end()) {
    return {};
  }
  return it->second;
}

bool GameSession::assign_planned_route(int unit_id, std::vector<HexCoord> full_path) {
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return false;
  }
  Unit const& u = units_[*ui];
  if (u.owner_seat() != current_player().seat) {
    return false;
  }
  if (full_path.size() < 2) {
    return false;
  }
  if (full_path.front() != u.coord()) {
    return false;
  }
  std::vector<HexCoord> remaining;
  remaining.reserve(full_path.size() - 1);
  for (std::size_t i = 1; i < full_path.size(); ++i) {
    remaining.push_back(full_path[i]);
  }
  planned_routes_[unit_id] = std::move(remaining);
  return true;
}

bool GameSession::clear_planned_route(int unit_id) {
  return planned_routes_.erase(unit_id) != 0;
}

bool GameSession::follow_planned_route(int unit_id) {
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    planned_routes_.erase(unit_id);
    return false;
  }
  Unit& u = units_[*ui];
  auto it = planned_routes_.find(unit_id);
  if (it == planned_routes_.end() || it->second.empty()) {
    return false;
  }
  bool moved = false;
  following_planned_route_now_ = true;
  std::vector<HexCoord>& route = it->second;
  while (!route.empty() && u.moves_remaining() > 0) {
    HexCoord step = route.front();
    if (!try_move_unit(unit_id, step)) {
      break;
    }
    moved = true;
    route.erase(route.begin());
  }
  following_planned_route_now_ = false;
  if (route.empty()) {
    planned_routes_.erase(unit_id);
  }
  return moved;
}

std::optional<Unit> GameSession::unit_at(HexCoord c) const {
  if (auto i = unit_index_at(c)) {
    return units_[*i];
  }
  return std::nullopt;
}

std::optional<City> GameSession::city_at(HexCoord c) const {
  if (auto i = city_index_at(c)) {
    return cities_[*i];
  }
  return std::nullopt;
}

std::optional<Unit> GameSession::unit_by_id(int id) const {
  if (auto i = unit_index_by_id(id)) {
    return units_[*i];
  }
  return std::nullopt;
}

std::optional<City> GameSession::city_by_id(int id) const {
  if (auto i = city_index_by_id(id)) {
    return cities_[*i];
  }
  return std::nullopt;
}

std::optional<WildAnimal> GameSession::wild_animal_at(HexCoord c) const {
  if (auto i = wild_animal_index_at(c)) {
    return wildlife_[*i];
  }
  return std::nullopt;
}

std::vector<HexCoord> GameSession::legal_moves(Unit const& u) const {
  if (is_over() || u.owner_seat() != current_player().seat || u.moves_remaining() <= 0) {
    return {};
  }
  std::vector<HexCoord> moves;
  for (HexCoord n : u.coord().neighbors()) {
    if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
      continue;
    }
    int cost = movement_cost_for_step(u, n);
    if (cost > u.moves_remaining()) {
      continue;
    }
    if (unit_index_at(n)) {
      continue;
    }
    if (wild_animal_index_at(n) && u.kind() != UnitKind::HUNTING_PARTY) {
      continue;
    }
    moves.push_back(n);
  }
  return moves;
}

bool GameSession::auto_explore_blocked_with_moves(Unit const& u) const {
  return u.auto_explore() && u.owner_seat() == current_player().seat && u.moves_remaining() > 0 &&
         legal_moves(u).empty();
}

bool GameSession::current_player_has_auto_explore_blocked_with_moves() const {
  if (is_over()) {
    return false;
  }
  int seat = current_player().seat;
  for (Unit const& u : units_) {
    if (u.owner_seat() != seat) {
      continue;
    }
    if (auto_explore_blocked_with_moves(u)) {
      return true;
    }
  }
  return false;
}

void GameSession::set_unit_auto_explore(int unit_id, bool on) {
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat) {
    return;
  }
  u.set_auto_explore(on);
  if (on) {
    u.set_sleeping(false);
    planned_routes_.erase(unit_id);
  }
}

void GameSession::set_unit_sleeping(int unit_id, bool on) {
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat) {
    return;
  }
  u.set_sleeping(on);
  if (on) {
    u.set_auto_explore(false);
    planned_routes_.erase(unit_id);
  }
}

bool GameSession::fortify_unit(int unit_id) {
  if (is_over()) {
    return false;
  }
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return false;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat) {
    return false;
  }
  u.exhaust_moves();
  return true;
}

void GameSession::run_auto_explore_for_current_player() {
  if (is_over()) {
    return;
  }
  std::vector<Unit> snapshot = units_;
  for (Unit const& snap : snapshot) {
    auto ui = unit_index_by_id(snap.id());
    if (!ui) {
      continue;
    }
    Unit& u = units_[*ui];
    if (u.owner_seat() != current_player().seat || !u.auto_explore() || u.sleeping()) {
      continue;
    }
    int guard = 0;
    while (u.moves_remaining() > 0 && guard++ < 512) {
      if (!run_auto_explore_step(u)) {
        break;
      }
    }
  }
}

bool GameSession::run_auto_explore_step(Unit& u) {
  if (!u.auto_explore() || u.sleeping() || u.owner_seat() != current_player().seat ||
      u.moves_remaining() <= 0) {
    return false;
  }
  auto legal = legal_moves(u);
  if (legal.empty()) {
    return false;
  }
  int seat = u.owner_seat();
  auto seen = visited_for(seat);

  std::vector<HexCoord> into_fog;
  std::vector<HexCoord> over_mapped;
  for (HexCoord n : legal) {
    if (!seen.count(n)) {
      into_fog.push_back(n);
    } else {
      over_mapped.push_back(n);
    }
  }
  if (!into_fog.empty()) {
    HexCoord pick = into_fog.front();
    int best_cost = movement_cost_for_step(u, pick);
    for (HexCoord n : into_fog) {
      int cost = movement_cost_for_step(u, n);
      if (cost < best_cost || (cost == best_cost && hex_tie_break(n, pick) < 0)) {
        best_cost = cost;
        pick = n;
      }
    }
    return try_move_unit(u.id(), pick);
  }

  HexCoord best = over_mapped.front();
  int best_dist = min_distance_to_unvisited_passable(best, seat);
  for (HexCoord n : over_mapped) {
    int d = min_distance_to_unvisited_passable(n, seat);
    if (d < best_dist || (d == best_dist && hex_tie_break(n, best) < 0)) {
      best_dist = d;
      best = n;
    }
  }
  if (best_dist == INT_MAX) {
    std::sort(legal.begin(), legal.end(), [](HexCoord a, HexCoord b) {
      return a.q != b.q ? a.q < b.q : a.r < b.r;
    });
    best = legal.front();
  }
  return try_move_unit(u.id(), best);
}

int GameSession::min_distance_to_unvisited_passable(HexCoord from, int seat) const {
  auto seen = visited_for(seat);
  int min_d = INT_MAX;
  for (HexCoord c : map_.all_cells()) {
    if (seen.count(c)) {
      continue;
    }
    if (!passable(terrain_effective_at(c))) {
      continue;
    }
    min_d = std::min(min_d, from.distance_to(c));
  }
  return min_d;
}

std::vector<HexCoord> GameSession::legal_attacks(Unit const& u) const {
  if (is_over() || u.owner_seat() != current_player().seat || u.moves_remaining() <= 0 ||
      unit_attack_strength(u.kind()) <= 0) {
    return {};
  }
  std::vector<HexCoord> out;
  for (HexCoord n : u.coord().neighbors()) {
    if (!map_.contains(n)) {
      continue;
    }
    if (auto du = unit_at(n)) {
      if (du->owner_seat() != u.owner_seat()) {
        out.push_back(n);
      }
    }
  }
  return out;
}

bool GameSession::can_found_city(Unit const& u) const {
  if (is_over()) {
    return false;
  }
  if (u.kind() != UnitKind::SETTLER) {
    return false;
  }
  if (u.owner_seat() != current_player().seat) {
    return false;
  }
  if (u.moves_remaining() <= 0) {
    return false;
  }
  if (city_at(u.coord())) {
    return false;
  }
  if (!can_found_city_on(terrain_effective_at(u.coord()))) {
    return false;
  }
  for (HexCoord nb : u.coord().neighbors()) {
    if (city_at(nb)) {
      return false;
    }
  }
  return true;
}

std::optional<std::string> GameSession::explain_cannot_found_city(Unit const& u) const {
  if (can_found_city(u)) {
    return std::nullopt;
  }
  if (is_over()) {
    return std::string("Cannot found cities after the game has ended.");
  }
  if (u.kind() != UnitKind::SETTLER) {
    return std::string("Only settlers can found a city.");
  }
  if (u.owner_seat() != current_player().seat) {
    return std::string("That unit is not under your control.");
  }
  if (u.moves_remaining() <= 0) {
    return std::string("Need at least one movement point remaining to found a city.");
  }
  if (city_at(u.coord())) {
    return std::string("A city already occupies this tile.");
  }
  if (!can_found_city_on(terrain_effective_at(u.coord()))) {
    return std::string("Cannot found on this terrain.");
  }
  for (HexCoord nb : u.coord().neighbors()) {
    if (city_at(nb)) {
      return std::string("Cannot found adjacent to another city.");
    }
  }
  return std::string("Cannot found a city here.");
}

std::optional<City> GameSession::found_city(int unit_id) {
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit u = units_[*ui];
  if (!can_found_city(u)) {
    return std::nullopt;
  }
  std::string name = next_founded_city_name();
  City city(next_city_id_++, u.owner_seat(), u.coord(), std::move(name), std::nullopt);
  int owner = city.owner_seat();
  HexCoord coord = city.coord();
  cities_.push_back(std::move(city));
  units_.erase(std::remove_if(units_.begin(), units_.end(),
                              [&](Unit const& x) { return x.id() == unit_id; }),
               units_.end());
  update_visited_for(owner);
  check_win();
  return city_at(coord);
}

std::string GameSession::next_founded_city_name() {
  if (city_name_cursor_ >= static_cast<int>(city_name_deck_.size())) {
    return "Outpost " + std::to_string(next_city_id_);
  }
  return city_name_deck_[static_cast<std::size_t>(city_name_cursor_++)];
}

bool GameSession::set_city_production(int city_id, UnitKind kind) {
  if (is_over()) {
    return false;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return false;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return false;
  }
  if (kind == UnitKind::HUNTING_PARTY && c.population() < 2) {
    return false;
  }
  c.set_current_build(kind);
  return true;
}

bool GameSession::enqueue_city_production(int city_id, UnitKind kind) {
  if (is_over()) {
    return false;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return false;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return false;
  }
  if (kind == UnitKind::HUNTING_PARTY && c.population() < 2) {
    return false;
  }
  c.enqueue_build(kind);
  return true;
}

bool GameSession::remove_city_queued_production(int city_id, int queue_index) {
  if (is_over()) {
    return false;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return false;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return false;
  }
  return c.remove_queued_build(queue_index);
}

bool GameSession::move_city_queued_production(int city_id, int queue_index, int delta) {
  if (is_over()) {
    return false;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return false;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return false;
  }
  return c.move_queued_build(queue_index, delta);
}

void GameSession::produce_for(int seat) {
  for (City& c : cities_) {
    if (c.owner_seat() != seat) {
      continue;
    }
    CityYield y = city_yield(c);
    c.apply_food_yield(y.food);
    c.add_production(y.production);
    player_gold_[seat] += y.gold;
    if (auto kind = c.drain_completed()) {
      spawn_from_city(c, *kind);
    }
  }
}

int GameSession::yield_food_at_tile(HexCoord tile, int city_res) const {
  int raw = tile_food_yield(tile);
  int pct = mitigated_city_food_percent(weather_at(tile), city_res);
  return std::max(0, (raw * (100 + pct)) / 100);
}

int GameSession::yield_prod_at_tile(int raw, HexCoord tile, int city_res) const {
  int pct = mitigated_city_production_percent(weather_at(tile), city_res);
  return std::max(0, (raw * (100 + pct)) / 100);
}

int GameSession::yield_gold_at_tile(int raw, HexCoord tile, int city_res) const {
  int pct = mitigated_city_gold_percent(weather_at(tile), city_res);
  return std::max(0, (raw * (100 + pct)) / 100);
}

CityYield GameSession::city_yield(City const& c) const {
  int city_res = city_weather_resilience(c);
  HexCoord center_coord = c.coord();
  int food = yield_food_at_tile(center_coord, city_res);
  int production = yield_prod_at_tile(1, center_coord, city_res) +
                   yield_prod_at_tile(tile_production_yield(center_coord), center_coord, city_res);
  int gold = yield_gold_at_tile(tile_gold_yield(center_coord), center_coord, city_res);
  if (c.has_building(CityBuilding::GRANARY)) {
    food += 1;
  }
  if (c.has_building(CityBuilding::WORKSHOP)) {
    production += 1;
  }
  if (c.has_building(CityBuilding::MARKET)) {
    gold += 1;
  }

  int workers = std::max(0, c.population() - 1);
  if (workers > 0) {
    std::vector<HexCoord> tiles;
    for (HexCoord n : c.coord().neighbors()) {
      if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
        continue;
      }
      auto owner = claimed_owner_at(n);
      if (!owner || *owner != c.owner_seat()) {
        continue;
      }
      tiles.push_back(n);
    }
    std::sort(tiles.begin(), tiles.end(), [&](HexCoord a, HexCoord b) {
      int sa = tile_yield_score_for_focus(a, c.focus());
      int sb = tile_yield_score_for_focus(b, c.focus());
      if (sb != sa) {
        return sb < sa;
      }
      return hex_tie_break(a, b) < 0;
    });
    int nwork = std::min(workers, static_cast<int>(tiles.size()));
    for (int i = 0; i < nwork; ++i) {
      HexCoord wc = tiles[static_cast<std::size_t>(i)];
      food += yield_food_at_tile(wc, city_res);
      production += yield_prod_at_tile(tile_production_yield(wc), wc, city_res);
      gold += yield_gold_at_tile(tile_gold_yield(wc), wc, city_res);
    }
  }
  return CityYield{food, production, gold};
}

CityYield GameSession::preview_city_yield_at(HexCoord center, int population, CityFocus focus) const {
  if (!map_.contains(center) || !passable(terrain_effective_at(center))) {
    return CityYield{0, 0, 0};
  }
  int pop = std::max(1, population);
  int city_res = std::min(4, std::max(1, 1 + pop / 4));
  int food = yield_food_at_tile(center, city_res);
  int production = yield_prod_at_tile(1, center, city_res) +
                   yield_prod_at_tile(tile_production_yield(center), center, city_res);
  int gold = yield_gold_at_tile(tile_gold_yield(center), center, city_res);
  int workers = std::max(0, pop - 1);
  if (workers > 0) {
    std::vector<HexCoord> tiles;
    for (HexCoord n : center.neighbors()) {
      if (map_.contains(n) && passable(terrain_effective_at(n))) {
        tiles.push_back(n);
      }
    }
    std::sort(tiles.begin(), tiles.end(), [&](HexCoord a, HexCoord b) {
      int sa = tile_yield_score_for_focus(a, focus);
      int sb = tile_yield_score_for_focus(b, focus);
      if (sb != sa) {
        return sb < sa;
      }
      return hex_tie_break(a, b) < 0;
    });
    int nwork = std::min(workers, static_cast<int>(tiles.size()));
    for (int i = 0; i < nwork; ++i) {
      HexCoord wc = tiles[static_cast<std::size_t>(i)];
      food += yield_food_at_tile(wc, city_res);
      production += yield_prod_at_tile(tile_production_yield(wc), wc, city_res);
      gold += yield_gold_at_tile(tile_gold_yield(wc), wc, city_res);
    }
  }
  return CityYield{food, production, gold};
}

CityYield GameSession::preview_city_yield_realistic(HexCoord center, int population, CityFocus focus,
                                                   int owner_seat) const {
  if (!map_.contains(center) || !passable(terrain_effective_at(center))) {
    return CityYield{0, 0, 0};
  }
  int pop = std::max(1, population);
  int city_res = std::min(4, std::max(1, 1 + pop / 4));
  int food = yield_food_at_tile(center, city_res);
  int production = yield_prod_at_tile(1, center, city_res) +
                   yield_prod_at_tile(tile_production_yield(center), center, city_res);
  int gold = yield_gold_at_tile(tile_gold_yield(center), center, city_res);
  int workers = std::max(0, pop - 1);
  if (workers > 0) {
    std::vector<HexCoord> tiles;
    for (HexCoord n : center.neighbors()) {
      if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
        continue;
      }
      auto claim = claimed_owner_at(n);
      if (claim && *claim != owner_seat) {
        continue;
      }
      tiles.push_back(n);
    }
    std::sort(tiles.begin(), tiles.end(), [&](HexCoord a, HexCoord b) {
      int sa = tile_yield_score_for_focus(a, focus);
      int sb = tile_yield_score_for_focus(b, focus);
      if (sb != sa) {
        return sb < sa;
      }
      return hex_tie_break(a, b) < 0;
    });
    int nwork = std::min(workers, static_cast<int>(tiles.size()));
    for (int i = 0; i < nwork; ++i) {
      HexCoord wc = tiles[static_cast<std::size_t>(i)];
      food += yield_food_at_tile(wc, city_res);
      production += yield_prod_at_tile(tile_production_yield(wc), wc, city_res);
      gold += yield_gold_at_tile(tile_gold_yield(wc), wc, city_res);
    }
  }
  return CityYield{food, production, gold};
}

std::optional<std::string> GameSession::explain_food_stagnation(City const& c) const {
  int upkeep = 1 + c.population();
  CityYield y = city_yield(c);
  int surplus = y.food - upkeep;
  if (surplus > 0) {
    return std::nullopt;
  }
  Weather wx = weather_at(c.coord());
  std::ostringstream sb;
  sb << "Growth stalled: " << upkeep << " food upkeep vs " << y.food << "/turn from tiles.";
  if (wx != Weather::CLEAR) {
    sb << " Weather on city tile (" << weather_label(wx) << ") reduces yields.";
  }
  return sb.str();
}

int GameSession::tile_food_yield(HexCoord c) const {
  if (!map_.contains(c)) {
    return 0;
  }
  Terrain t = terrain_effective_at(c);
  int n = food_yield(t);
  auto sf = soil_fertility_bonus_.find(c);
  if (sf != soil_fertility_bonus_.end()) {
    n += sf->second;
  }
  auto ct = cultivation_tier_.find(c);
  if (ct != cultivation_tier_.end()) {
    n += ct->second;
  }
  if (improvement_at(c) == TileImprovement::FARM) {
    n += 2;
  }
  return n;
}

int GameSession::tile_production_yield(HexCoord c) const {
  if (!map_.contains(c)) {
    return 0;
  }
  Terrain t = terrain_effective_at(c);
  int n = production_yield(t);
  if (improvement_at(c) == TileImprovement::MINE) {
    n += 2;
  }
  return n;
}

int GameSession::tile_gold_yield(HexCoord c) const {
  if (!map_.contains(c)) {
    return 0;
  }
  return gold_yield(terrain_effective_at(c));
}

int GameSession::soil_fertility_at(HexCoord c) const {
  auto it = soil_fertility_bonus_.find(c);
  return it == soil_fertility_bonus_.end() ? 0 : it->second;
}

int GameSession::cultivation_at(HexCoord c) const {
  auto it = cultivation_tier_.find(c);
  return it == cultivation_tier_.end() ? 0 : it->second;
}

TileImprovement GameSession::improvement_at(HexCoord c) const {
  auto it = tile_improvements_.find(c);
  return it == tile_improvements_.end() ? TileImprovement::NONE : it->second;
}

int GameSession::tile_yield_score(HexCoord c) const {
  return tile_production_yield(c) * 2 + tile_food_yield(c) * 2 + tile_gold_yield(c);
}

int GameSession::tile_yield_score_for_focus(HexCoord c, CityFocus focus) const {
  int food = tile_food_yield(c);
  int prod = tile_production_yield(c);
  int gold = tile_gold_yield(c);
  switch (focus) {
    case CityFocus::FOOD:
      return food * 4 + prod * 2 + gold;
    case CityFocus::PRODUCTION:
      return prod * 4 + food * 2 + gold;
    case CityFocus::GOLD:
      return gold * 4 + food * 2 + prod * 2;
    case CityFocus::BALANCED:
      return tile_yield_score(c);
  }
  return tile_yield_score(c);
}

std::optional<std::string> GameSession::set_city_focus(int city_id, CityFocus focus) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return std::nullopt;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return std::nullopt;
  }
  c.set_focus(focus);
  std::ostringstream sb;
  sb << c.name() << " now focuses " << focus_label_lower(c.focus()) << ".";
  return sb.str();
}

std::optional<std::string> GameSession::try_construct_city_building(int city_id, CityBuilding building) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return std::nullopt;
  }
  City& c = cities_[*ci];
  if (c.owner_seat() != current_player().seat) {
    return std::nullopt;
  }
  if (c.has_building(building)) {
    std::ostringstream sb;
    sb << c.name() << " already has a " << city_building_label(building) << ".";
    return sb.str();
  }
  int seat = c.owner_seat();
  int gold = gold_for(seat);
  int cost = city_building_gold_cost(building);
  if (gold < cost) {
    std::ostringstream sb;
    sb << "Need " << cost << " gold to construct " << city_building_label(building) << ".";
    return sb.str();
  }
  player_gold_[seat] = gold - cost;
  c.add_building(building);
  std::ostringstream sb;
  sb << c.name() << " constructed " << city_building_label(building) << ".";
  return sb.str();
}

int GameSession::city_claim_radius(City const& c) const {
  int pop = c.population();
  if (pop >= 8) {
    return 3;
  }
  if (pop >= 4) {
    return 2;
  }
  return 1;
}

std::optional<int> GameSession::claimed_owner_at(HexCoord h) const {
  if (!map_.contains(h) || !passable(terrain_effective_at(h))) {
    return std::nullopt;
  }
  int best_dist = INT_MAX;
  City const* best_city = nullptr;
  for (City const& c : cities_) {
    int d = c.coord().distance_to(h);
    if (d > city_claim_radius(c)) {
      continue;
    }
    if (d < best_dist) {
      best_dist = d;
      best_city = &c;
    } else if (d == best_dist && best_city != nullptr) {
      if (c.population() > best_city->population()) {
        best_city = &c;
      } else if (c.population() == best_city->population() && c.owner_seat() < best_city->owner_seat()) {
        best_city = &c;
      }
    }
  }
  if (best_city == nullptr) {
    return std::nullopt;
  }
  return best_city->owner_seat();
}

std::string GameSession::claim_debug_at(HexCoord h) const {
  if (!map_.contains(h) || !passable(terrain_effective_at(h))) {
    return "Claim: none";
  }
  int best_dist = INT_MAX;
  City const* best_city = nullptr;
  for (City const& c : cities_) {
    int d = c.coord().distance_to(h);
    if (d > city_claim_radius(c)) {
      continue;
    }
    if (d < best_dist) {
      best_dist = d;
      best_city = &c;
    } else if (d == best_dist && best_city != nullptr) {
      if (c.population() > best_city->population()) {
        best_city = &c;
      } else if (c.population() == best_city->population() && c.owner_seat() < best_city->owner_seat()) {
        best_city = &c;
      }
    }
  }
  if (best_city == nullptr) {
    return "Claim: none";
  }
  Player const& owner = player_by_seat(best_city->owner_seat());
  int radius = city_claim_radius(*best_city);
  std::ostringstream sb;
  sb << "Claim: " << owner.name << " via " << best_city->name() << " (d=" << best_dist << ", r=" << radius
     << ", pop=" << best_city->population() << ")";
  return sb.str();
}

bool GameSession::is_inside_own_border(HexCoord c, int seat) const {
  auto o = claimed_owner_at(c);
  return o && *o == seat;
}

void GameSession::spawn_from_city(City& c, UnitKind kind) {
  if (kind == UnitKind::HUNTING_PARTY && !c.consume_population_for_party()) {
    c.add_production(unit_production_cost(kind));
    return;
  }
  std::optional<HexCoord> placement = find_free_adjacent(c.coord());
  if (!placement) {
    if (kind == UnitKind::HUNTING_PARTY) {
      c.return_population_from_party();
    }
    c.add_production(unit_production_cost(kind));
    return;
  }
  units_.emplace_back(next_unit_id_++, c.owner_seat(), kind, *placement);
  units_.back().exhaust_moves();
}

std::optional<HexCoord> GameSession::find_free_adjacent(HexCoord origin) const {
  if (map_.contains(origin) && passable(terrain_effective_at(origin)) && !unit_index_at(origin) &&
      !wild_animal_index_at(origin)) {
    return origin;
  }
  for (HexCoord n : origin.neighbors()) {
    if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
      continue;
    }
    if (unit_index_at(n)) {
      continue;
    }
    if (wild_animal_index_at(n)) {
      continue;
    }
    return n;
  }
  return std::nullopt;
}

std::optional<std::size_t> GameSession::city_index_by_id(int id) const {
  for (std::size_t i = 0; i < cities_.size(); ++i) {
    if (cities_[i].id() == id) {
      return i;
    }
  }
  return std::nullopt;
}

std::optional<std::string> GameSession::try_cultivate_tile(int builder_id) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ui = unit_index_by_id(builder_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat || u.kind() != UnitKind::FARMER) {
    return std::nullopt;
  }
  if (u.moves_remaining() <= 0) {
    return std::string("Farmer has no moves left this turn.");
  }
  HexCoord c = u.coord();
  if (!is_inside_own_border(c, u.owner_seat())) {
    return std::string("Improvements can only be built inside your city borders.");
  }
  if (!can_cultivate(terrain_effective_at(c))) {
    return std::string("This terrain cannot be cultivated (try grass, plains, or desert).");
  }
  int tier = cultivation_tier_[c];
  if (tier >= 2) {
    return std::string("This tile is already fully cultivated.");
  }
  cultivation_tier_[c] = tier + 1;
  u.exhaust_moves();
  return std::string("Cultivated terrain (+1 farming tier).");
}

std::optional<std::string> GameSession::try_clear_forest(int builder_id) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ui = unit_index_by_id(builder_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat || u.kind() != UnitKind::FARMER) {
    return std::nullopt;
  }
  if (u.moves_remaining() <= 0) {
    return std::string("Farmer has no moves left this turn.");
  }
  HexCoord c = u.coord();
  if (!is_inside_own_border(c, u.owner_seat())) {
    return std::string("Improvements can only be built inside your city borders.");
  }
  if (terrain_effective_at(c) != Terrain::FOREST) {
    return std::string("Clear forest only works on forest tiles.");
  }
  terrain_override_[c] = Terrain::GRASS;
  u.exhaust_moves();
  return std::string("Cleared forest — tile is now grassland.");
}

std::optional<std::string> GameSession::try_build_farm_improvement(int builder_id) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ui = unit_index_by_id(builder_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat || u.kind() != UnitKind::FARMER) {
    return std::nullopt;
  }
  if (u.moves_remaining() <= 0) {
    return std::string("Farmer has no moves left this turn.");
  }
  HexCoord c = u.coord();
  if (!is_inside_own_border(c, u.owner_seat())) {
    return std::string("Improvements can only be built inside your city borders.");
  }
  Terrain t = terrain_effective_at(c);
  if (!can_support_farm(t)) {
    return std::string("Farms can only be built on grass, plains, or desert (clear forest first).");
  }
  if (improvement_at(c) != TileImprovement::NONE) {
    return std::string("This tile already has an improvement.");
  }
  int cult = cultivation_tier_[c];
  int soil = soil_fertility_bonus_[c];
  if (cult < 1 && soil < 2) {
    return std::string("Need cultivation (use Cultivate) or naturally rich soil (soil +2) before a farm.");
  }
  tile_improvements_[c] = TileImprovement::FARM;
  u.exhaust_moves();
  return std::string("Built a farm (+2 food when this tile is worked).");
}

std::optional<std::string> GameSession::try_build_mine_improvement(int builder_id) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ui = unit_index_by_id(builder_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat || u.kind() != UnitKind::BUILDER) {
    return std::nullopt;
  }
  if (u.moves_remaining() <= 0) {
    return std::string("Builder has no moves left this turn.");
  }
  HexCoord c = u.coord();
  if (!is_inside_own_border(c, u.owner_seat())) {
    return std::string("Improvements can only be built inside your city borders.");
  }
  if (!can_support_mine(terrain_effective_at(c))) {
    return std::string("Mines can only be built on hills.");
  }
  if (improvement_at(c) != TileImprovement::NONE) {
    return std::string("This tile already has an improvement.");
  }
  tile_improvements_[c] = TileImprovement::MINE;
  u.exhaust_moves();
  return std::string("Built a mine (+2 production when worked).");
}

std::optional<std::string> GameSession::try_hunt_wildlife(int hunter_id, int animal_id,
                                                          HexCoord animal_coord) {
  if (is_over()) {
    return std::nullopt;
  }
  auto hi = unit_index_by_id(hunter_id);
  if (!hi) {
    return std::nullopt;
  }
  Unit& hunter = units_[*hi];
  if (hunter.owner_seat() != current_player().seat || hunter.kind() != UnitKind::HUNTING_PARTY) {
    return std::nullopt;
  }
  if (hunter.coord() != animal_coord || hunter.moves_remaining() <= 0) {
    return std::nullopt;
  }
  WildAnimal* target = nullptr;
  for (WildAnimal& a : wildlife_) {
    if (a.id() == animal_id && a.coord() == animal_coord && !a.is_dead()) {
      target = &a;
      break;
    }
  }
  if (!target) {
    return std::nullopt;
  }

  int atk = unit_attack_strength(hunter.kind()) + combat_rng_.next_int_3() - 1;
  int wx_bonus = extra_wild_damage(weather_at(animal_coord), wild_weather_resilience(target->kind()));
  int dmg_to_animal = std::max(1, atk + wx_bonus);
  target->take_damage(dmg_to_animal);
  int food_from_hit = std::max(1, dmg_to_animal / 2);
  hunter.add_carried_food(food_from_hit);
  int retaliation =
      std::max(0, wild_attack(target->kind()) / 2 + combat_rng_.next_int_2() - 1);
  hunter.take_damage(retaliation);
  hunter.exhaust_moves();

  std::ostringstream msg;
  msg << unit_kind_display_name(hunter.kind()) << " hunts for " << dmg_to_animal;
  if (retaliation > 0) {
    msg << ", takes " << retaliation;
  }
  if (target->is_dead()) {
    WildAnimalKind const dead_kind = target->kind();
    wildlife_.erase(std::remove_if(wildlife_.begin(), wildlife_.end(),
                                   [&](WildAnimal const& w) { return w.id() == animal_id; }),
                    wildlife_.end());
    msg << " — " << wild_animal_kind_label(dead_kind) << " eliminated";
    int kill_bonus_food = wildlife_food_value(dead_kind);
    hunter.add_carried_food(kill_bonus_food);
    msg << " (+" << (food_from_hit + kill_bonus_food) << " carried food)";
  } else {
    msg << " (+" << food_from_hit << " carried food)";
  }
  if (hunter.is_dead()) {
    units_.erase(std::remove_if(units_.begin(), units_.end(),
                                [&](Unit const& x) { return x.id() == hunter_id; }),
                 units_.end());
    msg << " — hunter fallen";
  }

  update_visited_for(hunter.owner_seat());
  check_win();
  return msg.str();
}

std::optional<std::string> GameSession::try_rebase_hunting_party(int unit_id) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ui = unit_index_by_id(unit_id);
  if (!ui) {
    return std::nullopt;
  }
  Unit& u = units_[*ui];
  if (u.owner_seat() != current_player().seat || u.kind() != UnitKind::HUNTING_PARTY) {
    return std::nullopt;
  }
  auto ci = city_index_at(u.coord());
  if (!ci) {
    return std::string("Rebase requires standing on one of your city centers.");
  }
  City& city = cities_[*ci];
  int food = u.clear_carried_food();
  city.return_population_from_party();
  city.add_foraged_food(food);
  int seat = city.owner_seat();
  units_.erase(std::remove_if(units_.begin(), units_.end(),
                              [&](Unit const& x) { return x.id() == unit_id; }),
               units_.end());
  update_visited_for(seat);
  std::ostringstream sb;
  sb << "Hunting party rebased to " << city.name() << " (+1 population, +" << food << " food).";
  return sb.str();
}

std::optional<std::string> GameSession::try_send_hunter_from_city(int city_id, int animal_id,
                                                                  int rounds_away) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ci = city_index_by_id(city_id);
  if (!ci) {
    return std::nullopt;
  }
  City& city = cities_[*ci];
  if (city.owner_seat() != current_player().seat) {
    return std::nullopt;
  }
  WildAnimal* prey = nullptr;
  for (WildAnimal& a : wildlife_) {
    if (a.id() == animal_id) {
      prey = &a;
      break;
    }
  }
  if (!prey) {
    return std::string("That quarry is no longer on the map.");
  }
  if (prey->coord().distance_to(city.coord()) > 2) {
    return std::string(
        "Wildlife is too far from the city to send a hunting party (need within 2 hexes).");
  }
  if (auto err = city.start_hunt_mission(animal_id, rounds_away)) {
    return err;
  }
  return std::string("Sent a hunting party (" + std::to_string(rounds_away) + " rounds). Population temporarily reduced.");
}

}  // namespace tack::strat

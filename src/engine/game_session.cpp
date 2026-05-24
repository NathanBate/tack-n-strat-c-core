#include "tack/strat/game_session.hpp"

#include <algorithm>
#include <climits>
#include <sstream>
#include <stdexcept>

#include "tack/strat/unit_kind.hpp"

#include "tack/strat/city_name_pool.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/mix64.hpp"
#include "tack/strat/world_generator.hpp"

namespace tack::strat {
namespace {

int constexpr kMaxWildlifeHardCap = 12;
int constexpr kMaxConsecutiveCityRaids = 2;
double constexpr kWildlifeAttackUnitChance = 0.30;
double constexpr kWildlifeAttackCityChance = 0.10;
double constexpr kWildlifeLingerOnUnitTileChance = 0.05;

bool batch_contains(std::vector<HexCoord> const& batch, HexCoord c) {
  return std::find(batch.begin(), batch.end(), c) != batch.end();
}

JavaRandom wild_rng_local(std::int64_t world_seed, int r, int salt) {
  std::uint64_t const s =
      mix64(static_cast<std::uint64_t>(world_seed), static_cast<std::uint64_t>(r),
            static_cast<std::uint64_t>(salt));
  return JavaRandom(static_cast<std::int64_t>(s));
}

}  // namespace

int GameSession::clamp_years_per_round(int years) noexcept {
  return std::max(1, std::min(99, years));
}

std::uint64_t GameSession::mix64_world(std::int64_t world_seed, int b, int c) noexcept {
  return mix64(static_cast<std::uint64_t>(world_seed), static_cast<std::uint64_t>(b),
               static_cast<std::uint64_t>(c));
}

int GameSession::hex_tie_break(HexCoord a, HexCoord b) noexcept {
  return (a.q * 409 + a.r) - (b.q * 409 + b.r);
}

GameSession::GameSession(std::vector<Player> players, std::int64_t world_seed)
    : GameSession(std::move(players), world_seed, Chronology::DEFAULT_YEARS_PER_FULL_ROUND,
                  MapSizePreset::Tiny) {}

GameSession::GameSession(std::vector<Player> players, std::int64_t world_seed, MapSizePreset map_size)
    : GameSession(std::move(players), world_seed, Chronology::DEFAULT_YEARS_PER_FULL_ROUND, map_size) {}

GameSession::GameSession(std::vector<Player> players, std::int64_t world_seed, int years_per_full_round)
    : GameSession(std::move(players), world_seed, years_per_full_round, MapSizePreset::Tiny) {}

GameSession::GameSession(std::vector<Player> players, std::int64_t world_seed, int years_per_full_round,
                         MapSizePreset map_size)
    : players_(std::move(players)),
      world_seed_(world_seed),
      map_gen_preset_(map_size),
      years_per_full_round_(clamp_years_per_round(years_per_full_round)),
      combat_rng_(world_seed, 0) {
  if (players_.empty() || players_.size() > 4) {
    throw std::invalid_argument("Need 1–4 players");
  }

  GeneratedWorld gen = WorldGenerator::generate(players_, world_seed_, map_size);
  map_ = std::move(gen.map);
  units_ = std::move(gen.units);
  soil_fertility_bonus_ = std::move(gen.soil_fertility_bonus);
  wildlife_ = std::move(gen.wildlife);

  bootstrap_session_state(true);
}

GameSession::GameSession(BypassWorldGenTag /*tag*/, std::vector<Player> players, GameMap map,
                         std::vector<Unit> units, std::vector<City> cities,
                         std::unordered_map<HexCoord, int> soil_fertility_bonus,
                         std::vector<WildAnimal> wildlife, std::int64_t world_seed,
                         int years_per_full_round)
    : players_(std::move(players)),
      world_seed_(world_seed),
      years_per_full_round_(clamp_years_per_round(years_per_full_round)),
      combat_rng_(world_seed, 0),
      map_(std::move(map)),
      units_(std::move(units)),
      cities_(std::move(cities)),
      soil_fertility_bonus_(std::move(soil_fertility_bonus)),
      wildlife_(std::move(wildlife)) {
  if (players_.empty() || players_.size() > 4) {
    throw std::invalid_argument("Need 1–4 players");
  }
  bootstrap_session_state(false);
}

void GameSession::bootstrap_session_state(bool new_game) {
  current_player_index_ = 0;
  round_ = 1;

  int max_uid = 0;
  for (Unit const& u : units_) {
    max_uid = std::max(max_uid, u.id());
  }
  next_unit_id_ = max_uid + 1;

  next_city_id_ = 1;
  for (City const& c : cities_) {
    next_city_id_ = std::max(next_city_id_, c.id() + 1);
  }

  int max_w = 0;
  for (WildAnimal const& w : wildlife_) {
    max_w = std::max(max_w, w.id());
  }
  next_wild_animal_id_ = max_w > 0 ? max_w + 1 : 1;

  wildlife_spawn_nonce_ = 0;
  wildlife_spawned_last_step_ = 0;
  wildlife_spawned_last_step_round_ = 0;
  chronology_offset_years_ = 0;
  weather_nonce_ = 0;

  WeatherBootstrap boot = initial_systems(map_, world_seed_);
  weather_systems_ = std::move(boot.systems);
  next_weather_patch_id_ = boot.next_id;

  city_name_deck_ = CityNamePool::shuffled_deck(world_seed_);
  city_name_cursor_ = new_game ? 0 : static_cast<int>(cities_.size());

  winner_seat_.reset();
  planned_routes_.clear();
  following_planned_route_now_ = false;

  for (Player const& p : players_) {
    visited_[p.seat] = {};
    player_gold_[p.seat] = 0;
  }
  for (Player const& p : players_) {
    update_visited_for(p.seat);
  }
  refresh_moves_for(current_player().seat);
}

Terrain GameSession::terrain_effective_at(HexCoord c) const {
  auto it = terrain_override_.find(c);
  if (it != terrain_override_.end()) {
    return it->second;
  }
  return map_.terrain_at(c);
}

Weather GameSession::weather_at(HexCoord h) const {
  Weather best = Weather::CLEAR;
  int p = 0;
  for (WeatherSystem const& ws : weather_systems_) {
    if (ws.covers(h)) {
      int wp = overlay_priority(ws.kind);
      if (wp > p) {
        p = wp;
        best = ws.kind;
      }
    }
  }
  return best;
}

int GameSession::city_weather_resilience(City const& c) const {
  return std::min(4, std::max(1, 1 + c.population() / 4));
}

int GameSession::movement_cost_for_step(Unit const& u, HexCoord dest) const {
  Terrain t = terrain_effective_at(dest);
  Weather wx = weather_at(dest);
  return movement_cost(t) + extra_movement_cost(wx, dest, t, unit_weather_resilience(u.kind()));
}

std::string GameSession::calendar_era_label() const {
  return Chronology::format_era(chronology_offset_years_);
}

std::string GameSession::weather_hud_summary() const {
  if (weather_systems_.empty()) {
    return "Weather: clear skies (no active systems)";
  }
  std::unordered_map<std::string, int> counts;
  std::vector<std::string> order;
  for (WeatherSystem const& ws : weather_systems_) {
    std::string k = weather_label(ws.kind);
    if (!counts.count(k)) {
      order.push_back(k);
    }
    counts[k]++;
  }
  std::ostringstream sb;
  sb << "Weather: " << weather_systems_.size()
     << (weather_systems_.size() == 1 ? " system" : " systems") << " — ";
  for (std::size_t i = 0; i < order.size(); ++i) {
    if (i > 0) {
      sb << " · ";
    }
    sb << order[i] << " ×" << counts[order[i]];
  }
  return sb.str();
}

void GameSession::add_disk(std::unordered_set<HexCoord>& out, HexCoord center, int radius) const {
  for (int dq = -radius; dq <= radius; ++dq) {
    int r_min = std::max(-radius, -dq - radius);
    int r_max = std::min(radius, -dq + radius);
    for (int dr = r_min; dr <= r_max; ++dr) {
      out.insert(HexCoord{center.q + dq, center.r + dr});
    }
  }
}

std::unordered_set<HexCoord> GameSession::visible_for(int seat) const {
  std::unordered_set<HexCoord> out;
  for (Unit const& u : units_) {
    if (u.owner_seat() == seat) {
      Weather uw = weather_at(u.coord());
      int eff = std::max(1, unit_sight_radius(u.kind()) -
                               sight_penalty(uw, unit_weather_resilience(u.kind())));
      add_disk(out, u.coord(), eff);
    }
  }
  for (City const& c : cities_) {
    if (c.owner_seat() == seat) {
      Weather cw = weather_at(c.coord());
      int base_city_sight = 2;
      int eff = std::max(1, base_city_sight - sight_penalty(cw, city_weather_resilience(c)));
      add_disk(out, c.coord(), eff);
    }
  }
  for (auto it = out.begin(); it != out.end();) {
    if (!map_.contains(*it)) {
      it = out.erase(it);
    } else {
      ++it;
    }
  }
  return out;
}

void GameSession::update_visited_for(int seat) {
  auto& v = visited_[seat];
  auto vis = visible_for(seat);
  v.insert(vis.begin(), vis.end());
}

std::optional<std::size_t> GameSession::unit_index_at(HexCoord c) const {
  for (std::size_t i = 0; i < units_.size(); ++i) {
    if (units_[i].coord() == c) {
      return i;
    }
  }
  return std::nullopt;
}

std::optional<std::size_t> GameSession::unit_index_by_id(int id) const {
  for (std::size_t i = 0; i < units_.size(); ++i) {
    if (units_[i].id() == id) {
      return i;
    }
  }
  return std::nullopt;
}

std::optional<std::size_t> GameSession::city_index_at(HexCoord c) const {
  for (std::size_t i = 0; i < cities_.size(); ++i) {
    if (cities_[i].coord() == c) {
      return i;
    }
  }
  return std::nullopt;
}

std::optional<std::size_t> GameSession::wild_animal_index_at(HexCoord c) const {
  for (std::size_t i = 0; i < wildlife_.size(); ++i) {
    if (wildlife_[i].coord() == c && !wildlife_[i].is_dead()) {
      return i;
    }
  }
  return std::nullopt;
}

bool GameSession::try_move_unit(int unit_id, HexCoord dest) {
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
  if (u.sleeping()) {
    return false;
  }
  if (u.coord().distance_to(dest) != 1) {
    return false;
  }
  if (!map_.contains(dest) || !passable(terrain_effective_at(dest))) {
    return false;
  }
  int cost = movement_cost_for_step(u, dest);
  if (cost > u.moves_remaining()) {
    return false;
  }
  if (unit_index_at(dest)) {
    return false;
  }
  if (wild_animal_index_at(dest) && u.kind() != UnitKind::HUNTING_PARTY) {
    return false;
  }

  u.set_coord(dest);
  u.spend_moves(cost);

  if (!following_planned_route_now_ && planned_routes_.count(u.id())) {
    planned_routes_.erase(u.id());
  }

  if (auto ci = city_index_at(dest)) {
    City& city_here = cities_[*ci];
    if (city_here.owner_seat() != u.owner_seat()) {
      city_here.capture_by(u.owner_seat());
      check_win();
    }
  }

  update_visited_for(u.owner_seat());
  return true;
}

std::optional<std::string> GameSession::try_attack(int attacker_id, HexCoord target) {
  if (is_over()) {
    return std::nullopt;
  }
  auto ai = unit_index_by_id(attacker_id);
  if (!ai) {
    return std::nullopt;
  }
  Unit& attacker = units_[*ai];
  if (attacker.owner_seat() != current_player().seat) {
    return std::nullopt;
  }
  if (attacker.moves_remaining() <= 0) {
    return std::nullopt;
  }
  if (unit_attack_strength(attacker.kind()) <= 0) {
    return std::nullopt;
  }
  if (attacker.coord().distance_to(target) != 1) {
    return std::nullopt;
  }
  auto di = unit_index_at(target);
  if (!di) {
    return std::nullopt;
  }
  Unit& defender = units_[*di];
  if (defender.owner_seat() == attacker.owner_seat()) {
    return std::nullopt;
  }

  int const defender_id = defender.id();
  int const attacker_id_copy = attacker.id();
  int const owner_seat = attacker.owner_seat();

  int atk_roll = unit_attack_strength(attacker.kind()) + combat_rng_.next_int_3() - 1;
  int def_roll = unit_attack_strength(defender.kind()) + combat_rng_.next_int_3() - 1;
  int dmg_to_def = std::max(1, atk_roll);
  int dmg_to_atk = std::max(0, def_roll - 2);

  defender.take_damage(dmg_to_def);
  attacker.take_damage(dmg_to_atk);
  attacker.exhaust_moves();

  std::ostringstream msg;
  msg << unit_kind_display_name(attacker.kind()) << " hits " << unit_kind_display_name(defender.kind())
      << " for " << dmg_to_def;
  if (dmg_to_atk > 0) {
    msg << ", takes " << dmg_to_atk << " back";
  }

  if (defender.is_dead()) {
    units_.erase(std::remove_if(units_.begin(), units_.end(),
                                [&](Unit const& x) { return x.id() == defender_id; }),
                 units_.end());
    msg << " — defender destroyed";
  }
  auto atk_it = std::find_if(units_.begin(), units_.end(),
                             [&](Unit const& x) { return x.id() == attacker_id_copy; });
  if (atk_it != units_.end() && atk_it->is_dead()) {
    units_.erase(atk_it);
    msg << " — attacker destroyed";
  }

  update_visited_for(owner_seat);
  check_win();
  return msg.str();
}

void GameSession::refresh_moves_for(int seat) {
  for (Unit& u : units_) {
    if (u.owner_seat() == seat) {
      u.refresh_moves();
    }
  }
}

void GameSession::check_win() {
  for (Player const& p : players_) {
    int n = 0;
    for (City const& c : cities_) {
      if (c.owner_seat() == p.seat) {
        ++n;
      }
    }
    if (n >= CITIES_TO_WIN) {
      winner_seat_ = p.seat;
      return;
    }
  }
  Player const* alive = nullptr;
  int alives = 0;
  for (Player const& p : players_) {
    bool has_city = false;
    for (City const& c : cities_) {
      if (c.owner_seat() == p.seat) {
        has_city = true;
        break;
      }
    }
    bool has_unit = false;
    for (Unit const& u : units_) {
      if (u.owner_seat() == p.seat) {
        has_unit = true;
        break;
      }
    }
    if (has_city || has_unit) {
      alive = &p;
      ++alives;
    }
  }
  if (alives == 1 && players_.size() > 1) {
    winner_seat_ = alive->seat;
  }
}

void GameSession::advance_all_hunt_missions() {
  for (City& c : cities_) {
    for (int animal_id : c.advance_hunt_missions()) {
      wildlife_.erase(std::remove_if(wildlife_.begin(), wildlife_.end(),
                                   [&](WildAnimal const& w) { return w.id() == animal_id; }),
                    wildlife_.end());
    }
  }
}

void GameSession::start_turn() {
  int seat = current_player().seat;
  refresh_moves_for(seat);
  produce_for(seat);
  update_visited_for(seat);
  check_win();
}

bool GameSession::end_turn() {
  if (is_over()) {
    return false;
  }
  int seat = current_player().seat;
  std::unordered_set<int> auto_moved_unit_ids;
  std::vector<Unit> units_snapshot = units_;
  for (Unit const& u : units_snapshot) {
    if (u.owner_seat() != seat) {
      continue;
    }
    if (!planned_routes_.count(u.id())) {
      continue;
    }
    if (follow_planned_route(u.id())) {
      auto_moved_unit_ids.insert(u.id());
    }
  }
  for (int uid : auto_moved_unit_ids) {
    auto ui = unit_index_by_id(uid);
    if (ui && units_[*ui].owner_seat() == seat && units_[*ui].moves_remaining() > 0) {
      return false;
    }
  }

  int next = (current_player_index_ + 1) % static_cast<int>(players_.size());
  if (next == 0) {
    advance_all_hunt_missions();
    ++round_;
    chronology_offset_years_ += years_per_full_round_;
    ++weather_nonce_;
    Season tick_season =
        season_from_elapsed_years(chronology_offset_years_, years_per_full_round_);
    WeatherTickResult tr =
        regional_weather_tick(weather_systems_, map_, tick_season, world_seed_, round_,
                              weather_nonce_, next_weather_patch_id_);
    weather_systems_ = std::move(tr.systems);
    next_weather_patch_id_ = tr.next_id;
    step_wildlife_turn();
  }
  current_player_index_ = next;
  start_turn();
  return true;
}

bool GameSession::is_wild_spawn_anchor_ok(HexCoord anchor) const {
  if (!map_.contains(anchor) || !passable(terrain_effective_at(anchor))) {
    return false;
  }
  if (unit_index_at(anchor) || city_index_at(anchor) || wild_animal_index_at(anchor)) {
    return false;
  }
  for (WildAnimal const& a : wildlife_) {
    if (a.coord().distance_to(anchor) < 2) {
      return false;
    }
  }
  return true;
}

bool GameSession::can_extend_herd_tile(HexCoord n, std::vector<HexCoord> const& batch) const {
  if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
    return false;
  }
  if (unit_index_at(n) || city_index_at(n) || wild_animal_index_at(n)) {
    return false;
  }
  for (HexCoord b : batch) {
    if (n.distance_to(b) == 1) {
      return true;
    }
  }
  return false;
}

void GameSession::expand_herd_batch(std::vector<HexCoord>& batch, int target_size, JavaRandom& s) {
  while (static_cast<int>(batch.size()) < target_size) {
    auto frontier = batch;
    java_collections_shuffle(frontier, s);
    HexCoord add{};
    bool found = false;
    for (HexCoord b : frontier) {
      auto nbs = b.neighbors();
      std::vector<HexCoord> nbv(nbs.begin(), nbs.end());
      java_collections_shuffle(nbv, s);
      for (HexCoord n : nbv) {
        if (batch_contains(batch, n)) {
          continue;
        }
        if (can_extend_herd_tile(n, batch)) {
          add = n;
          found = true;
          break;
        }
      }
      if (found) {
        break;
      }
    }
    if (!found) {
      break;
    }
    batch.push_back(add);
  }
}

int GameSession::target_wildlife_count() const {
  int land_tiles = static_cast<int>(map_.passable_land().size());
  int density_target = std::max(1, (land_tiles + 39) / 40);
  return std::min(kMaxWildlifeHardCap, density_target);
}

int GameSession::try_spawn_wildlife_group(int target_size, JavaRandom& s) {
  auto land = map_.passable_land();
  java_collections_shuffle(land, s);
  int max_tries = std::min(static_cast<int>(land.size()), 200);
  for (int ai = 0; ai < max_tries; ++ai) {
    HexCoord anchor = land[static_cast<std::size_t>(ai)];
    if (!is_wild_spawn_anchor_ok(anchor)) {
      continue;
    }
    WildAnimalKind const kind =
        s.next_double() < 0.55 ? pick_fully_random(s)
                             : pick_for_terrain(terrain_effective_at(anchor), s);
    std::vector<HexCoord> batch;
    batch.push_back(anchor);
    if (target_size > 1) {
      expand_herd_batch(batch, target_size, s);
    }
    if (target_size > 1 && static_cast<int>(batch.size()) < 2) {
      continue;
    }
    for (HexCoord c : batch) {
      wildlife_.emplace_back(next_wild_animal_id_++, kind, c);
    }
    return static_cast<int>(batch.size());
  }
  return 0;
}

int GameSession::maybe_spawn_wildlife_this_round() {
  int target = target_wildlife_count();
  if (static_cast<int>(wildlife_.size()) >= target) {
    return 0;
  }
  JavaRandom s(static_cast<std::int64_t>(
      mix64_world(world_seed_, wildlife_spawn_nonce_, round_ ^ 0xA11CE)));
  int spawned = 0;
  int deficit = target - static_cast<int>(wildlife_.size());
  int pulses = std::min(8, std::max(1, (deficit * 3 + 3) / 6));
  for (int i = 0; i < pulses && static_cast<int>(wildlife_.size()) < target; ++i) {
    bool herd = s.next_double() < 0.68;
    int size = herd ? (2 + s.next_int_bounded(4)) : 1;
    int room = target - static_cast<int>(wildlife_.size());
    int requested = std::max(1, std::min(size, room));
    spawned += try_spawn_wildlife_group(requested, s);
  }
  return spawned;
}

void GameSession::wild_attack_unit(WildAnimal& a, Unit& u, JavaRandom& rng) {
  int dmg = std::max(1, wild_attack(a.kind()) + rng.next_int_bounded(2) - 1);
  u.take_damage(dmg);
  if (u.is_dead()) {
    units_.erase(std::remove_if(units_.begin(), units_.end(),
                                [&](Unit const& x) { return x.id() == u.id(); }),
                 units_.end());
  }
}

void GameSession::wild_raid_city(WildAnimal& a, City& city, JavaRandom& rng) {
  int dmg = std::max(1, wild_attack(a.kind()) / 3 + rng.next_int_bounded(2));
  city.apply_wildlife_raid(dmg);
}

HexCoord GameSession::pick_wildlife_wander_step(std::vector<HexCoord> const& legal,
                                               JavaRandom& rng) const {
  if (cities_.empty()) {
    return legal[static_cast<std::size_t>(rng.next_int_bounded(static_cast<int>(legal.size())))];
  }
  HexCoord best = legal.front();
  int best_score = INT_MIN;
  for (HexCoord n : legal) {
    int nearest = INT_MAX;
    for (City const& c : cities_) {
      nearest = std::min(nearest, n.distance_to(c.coord()));
    }
    if (nearest == INT_MAX) {
      nearest = 999;
    }
    int score = nearest * 10 + rng.next_int_bounded(14);
    if (score > best_score || (score == best_score && rng.next_bool())) {
      best_score = score;
      best = n;
    }
  }
  return best;
}

void GameSession::step_one_wild_animal(WildAnimal& a, JavaRandom& rng) {
  HexCoord c = a.coord();
  if (auto ui = unit_index_at(c)) {
    Unit& occupant = units_[*ui];
    if (occupant.owner_seat() >= 0 && rng.next_double() < kWildlifeAttackUnitChance) {
      wild_attack_unit(a, occupant, rng);
    }
  }
  if (a.is_dead()) {
    return;
  }
  if (unit_index_at(c) && rng.next_double() < kWildlifeLingerOnUnitTileChance) {
    return;
  }
  std::vector<Unit*> prey;
  for (HexCoord n : c.neighbors()) {
    if (auto idx = unit_index_at(n)) {
      prey.push_back(&units_[*idx]);
    }
  }
  if (!prey.empty() && rng.next_double() < kWildlifeAttackUnitChance) {
    Unit* victim = prey[static_cast<std::size_t>(
        rng.next_int_bounded(static_cast<int>(prey.size())))];
    wild_attack_unit(a, *victim, rng);
    wildlife_city_raid_streak_[a.id()] = 0;
    return;
  }
  std::optional<std::size_t> raid_ci;
  for (HexCoord n : c.neighbors()) {
    if (auto ci = city_index_at(n)) {
      raid_ci = ci;
      break;
    }
  }
  int raid_streak = wildlife_city_raid_streak_[a.id()];
  if (raid_ci && raid_streak < kMaxConsecutiveCityRaids &&
      rng.next_double() < kWildlifeAttackCityChance) {
    wild_raid_city(a, cities_[*raid_ci], rng);
    wildlife_city_raid_streak_[a.id()] = raid_streak + 1;
  }
  std::vector<HexCoord> legal;
  for (HexCoord n : c.neighbors()) {
    if (!map_.contains(n) || !passable(terrain_effective_at(n))) {
      continue;
    }
    if (unit_index_at(n)) {
      continue;
    }
    if (wild_animal_index_at(n)) {
      continue;
    }
    if (city_index_at(n)) {
      continue;
    }
    legal.push_back(n);
  }
  if (legal.empty()) {
    return;
  }
  HexCoord step = pick_wildlife_wander_step(legal, rng);
  a.set_coord(step);
  wildlife_city_raid_streak_[a.id()] = 0;
}

void GameSession::step_wildlife_turn() {
  ++wildlife_spawn_nonce_;
  wildlife_spawned_last_step_ = maybe_spawn_wildlife_this_round();
  wildlife_spawned_last_step_round_ = round_;
  if (wildlife_.empty()) {
    return;
  }
  JavaRandom order_rng = wild_rng_local(world_seed_, round_, 0x5EED);
  std::vector<std::size_t> order;
  order.reserve(wildlife_.size());
  for (std::size_t i = 0; i < wildlife_.size(); ++i) {
    order.push_back(i);
  }
  java_collections_shuffle(order, order_rng);
  for (std::size_t idx : order) {
    WildAnimal& a = wildlife_[idx];
    if (!a.is_dead()) {
      JavaRandom step_rng = wild_rng_local(world_seed_, round_,
                                             static_cast<int>(a.id()) ^ 0xDEADBEEF);
      step_one_wild_animal(a, step_rng);
    }
  }
  wildlife_.erase(std::remove_if(wildlife_.begin(), wildlife_.end(),
                                 [](WildAnimal const& w) { return w.is_dead(); }),
                   wildlife_.end());
  for (auto it = wildlife_city_raid_streak_.begin(); it != wildlife_city_raid_streak_.end();) {
    bool seen = false;
    for (WildAnimal const& w : wildlife_) {
      if (w.id() == it->first) {
        seen = true;
        break;
      }
    }
    if (!seen) {
      it = wildlife_city_raid_streak_.erase(it);
    } else {
      ++it;
    }
  }
}

}  // namespace tack::strat

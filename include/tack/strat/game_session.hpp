#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "tack/strat/chronology.hpp"
#include "tack/strat/city.hpp"
#include "tack/strat/city_building.hpp"
#include "tack/strat/city_focus.hpp"
#include "tack/strat/combat_rng.hpp"
#include "tack/strat/game_map.hpp"
#include "tack/strat/game_snapshot.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/regional_weather.hpp"
#include "tack/strat/season.hpp"
#include "tack/strat/tile_improvement.hpp"
#include "tack/strat/unit.hpp"
#include "tack/strat/weather.hpp"
#include "tack/strat/weather_system.hpp"
#include "tack/strat/wild_animal.hpp"

namespace tack::strat {

struct SnapshotRestorer;

struct CityYield {
  int food{};
  int production{};
  int gold{};
};

/** Construct sessions without {@link WorldGenerator} (tests and tools). */
struct BypassWorldGenTag {
  explicit BypassWorldGenTag() = default;
};

class GameSession {
  friend struct SnapshotRestorer;

 public:
  static constexpr int CITIES_TO_WIN = 3;

  /** New procedural map. Defaults to {@link MapSizePreset::Tiny} (~Java prototype size); use Small/Medium/Large for Civ VI–scale disks. */
  GameSession(std::vector<Player> players, std::int64_t world_seed);
  GameSession(std::vector<Player> players, std::int64_t world_seed, MapSizePreset map_size);
  /** Same as single-argument ctor but with custom {@link Chronology} pacing; still defaults to {@link MapSizePreset::Tiny}. */
  GameSession(std::vector<Player> players, std::int64_t world_seed, int years_per_full_round);
  GameSession(std::vector<Player> players, std::int64_t world_seed, int years_per_full_round,
              MapSizePreset map_size);

  GameSession(BypassWorldGenTag tag, std::vector<Player> players, GameMap map, std::vector<Unit> units,
              std::vector<City> cities, std::unordered_map<HexCoord, int> soil_fertility_bonus,
              std::vector<WildAnimal> wildlife, std::int64_t world_seed,
              int years_per_full_round = Chronology::DEFAULT_YEARS_PER_FULL_ROUND);

  [[nodiscard]] std::int64_t world_seed() const noexcept {
    return world_seed_;
  }

  /** Preset used for procedural generation; unset for bypass maps. */
  [[nodiscard]] std::optional<MapSizePreset> map_size_preset() const noexcept {
    return map_gen_preset_;
  }

  [[nodiscard]] Player const& current_player() const {
    return players_[static_cast<std::size_t>(current_player_index_)];
  }
  [[nodiscard]] int current_player_index() const noexcept {
    return current_player_index_;
  }
  [[nodiscard]] Player const& player_by_seat(int seat) const;

  /** Advances to the next player (and round when wrapping). Returns false if the game is over or a
   *  same-seat unit still has moves after an auto step along a queued path (turn stays on you). */
  [[nodiscard]] bool end_turn();

  [[nodiscard]] int round() const noexcept {
    return round_;
  }
  [[nodiscard]] int chronology_offset_years() const noexcept {
    return chronology_offset_years_;
  }
  [[nodiscard]] int years_per_full_round() const noexcept {
    return years_per_full_round_;
  }
  [[nodiscard]] std::string calendar_era_label() const;
  [[nodiscard]] Season season() const;

  [[nodiscard]] std::string weather_hud_summary() const;

  [[nodiscard]] GameMap const& map() const noexcept {
    return map_;
  }
  [[nodiscard]] std::vector<Unit> const& units() const noexcept {
    return units_;
  }
  [[nodiscard]] std::vector<Player> const& players() const noexcept {
    return players_;
  }
  [[nodiscard]] std::vector<City> const& cities() const noexcept {
    return cities_;
  }

  [[nodiscard]] int city_count_for(int seat) const;
  [[nodiscard]] int unit_count_for(int seat) const;
  [[nodiscard]] int gold_for(int seat) const;

  [[nodiscard]] Terrain terrain_effective_at(HexCoord c) const;

  [[nodiscard]] Weather weather_at(HexCoord h) const;
  [[nodiscard]] int movement_cost_for_step(Unit const& u, HexCoord dest) const;

  [[nodiscard]] std::unordered_set<HexCoord> visited_for(int seat) const;
  [[nodiscard]] std::unordered_set<HexCoord> visible_for(int seat) const;

  [[nodiscard]] std::vector<HexCoord> planned_route_for(int unit_id) const;
  bool assign_planned_route(int unit_id, std::vector<HexCoord> full_path);
  bool clear_planned_route(int unit_id);
  bool follow_planned_route(int unit_id);

  [[nodiscard]] std::optional<Unit> unit_at(HexCoord c) const;
  [[nodiscard]] std::optional<City> city_at(HexCoord c) const;
  [[nodiscard]] std::optional<Unit> unit_by_id(int id) const;
  [[nodiscard]] std::optional<City> city_by_id(int id) const;
  [[nodiscard]] std::optional<WildAnimal> wild_animal_at(HexCoord c) const;

  [[nodiscard]] std::vector<HexCoord> legal_moves(Unit const& u) const;
  [[nodiscard]] bool auto_explore_blocked_with_moves(Unit const& u) const;
  [[nodiscard]] bool current_player_has_auto_explore_blocked_with_moves() const;

  bool try_move_unit(int unit_id, HexCoord dest);

  void set_unit_auto_explore(int unit_id, bool on);
  void set_unit_sleeping(int unit_id, bool on);
  /** Exhaust remaining MP for the unit if owned by the current player (Java fortify / skip moves). */
  bool fortify_unit(int unit_id);
  void run_auto_explore_for_current_player();

  [[nodiscard]] std::vector<HexCoord> legal_attacks(Unit const& u) const;
  [[nodiscard]] std::optional<std::string> try_attack(int attacker_id, HexCoord target);

  [[nodiscard]] bool can_found_city(Unit const& u) const;
  [[nodiscard]] std::optional<std::string> explain_cannot_found_city(Unit const& u) const;
  [[nodiscard]] std::optional<City> found_city(int unit_id);

  bool set_city_production(int city_id, UnitKind kind);
  bool enqueue_city_production(int city_id, UnitKind kind);
  bool remove_city_queued_production(int city_id, int queue_index);
  bool move_city_queued_production(int city_id, int queue_index, int delta);

  [[nodiscard]] CityYield city_yield(City const& c) const;
  [[nodiscard]] CityYield preview_city_yield_at(HexCoord center, int population, CityFocus focus) const;
  [[nodiscard]] CityYield preview_city_yield_realistic(HexCoord center, int population, CityFocus focus,
                                                       int owner_seat) const;
  [[nodiscard]] std::optional<std::string> explain_food_stagnation(City const& c) const;

  [[nodiscard]] int tile_food_yield(HexCoord c) const;
  [[nodiscard]] int tile_production_yield(HexCoord c) const;
  [[nodiscard]] int tile_gold_yield(HexCoord c) const;
  [[nodiscard]] int soil_fertility_at(HexCoord c) const;
  [[nodiscard]] int cultivation_at(HexCoord c) const;
  [[nodiscard]] TileImprovement improvement_at(HexCoord c) const;

  [[nodiscard]] std::vector<WildAnimal> wildlife_list() const {
    return wildlife_;
  }
  [[nodiscard]] int wildlife_spawned_last_step() const noexcept {
    return wildlife_spawned_last_step_;
  }
  [[nodiscard]] int wildlife_spawned_last_step_round() const noexcept {
    return wildlife_spawned_last_step_round_;
  }

  [[nodiscard]] std::optional<std::string> set_city_focus(int city_id, CityFocus focus);
  [[nodiscard]] std::optional<std::string> try_construct_city_building(int city_id, CityBuilding building);

  [[nodiscard]] int city_claim_radius(City const& c) const;
  [[nodiscard]] std::optional<int> claimed_owner_at(HexCoord h) const;

  [[nodiscard]] std::optional<std::string> try_cultivate_tile(int builder_id);
  [[nodiscard]] std::optional<std::string> try_clear_forest(int builder_id);
  [[nodiscard]] std::optional<std::string> try_build_farm_improvement(int builder_id);
  [[nodiscard]] std::optional<std::string> try_build_mine_improvement(int builder_id);

  [[nodiscard]] std::optional<std::string> try_hunt_wildlife(int hunter_id, int animal_id,
                                                             HexCoord animal_coord);
  [[nodiscard]] std::optional<std::string> try_rebase_hunting_party(int unit_id);
  [[nodiscard]] std::optional<std::string> try_send_hunter_from_city(int city_id, int animal_id,
                                                                     int rounds_away);

  [[nodiscard]] bool is_over() const noexcept {
    return winner_seat_.has_value();
  }
  [[nodiscard]] std::optional<int> winner_seat() const noexcept {
    return winner_seat_;
  }

  [[nodiscard]] int combat_rng_call_count() const noexcept {
    return combat_rng_.call_count();
  }

  /** Full rules state for save/load parity with Java {@link GameSession#capture()}. */
  [[nodiscard]] GameSnapshot capture() const;
  /** Rebuilds a session from a snapshot (no world generation). */
  [[nodiscard]] static GameSession restore(GameSnapshot snap);

  /** Tooltip/debug string for territorial claims (Java {@link GameSession#claimDebugAt}). */
  [[nodiscard]] std::string claim_debug_at(HexCoord h) const;

 private:
  std::vector<Player> players_;
  std::int64_t world_seed_{};
  std::optional<MapSizePreset> map_gen_preset_{};
  int years_per_full_round_{};
  CombatRng combat_rng_;
  GameMap map_;
  std::vector<Unit> units_;
  std::vector<City> cities_;
  std::vector<std::string> city_name_deck_;
  int city_name_cursor_{};
  std::unordered_map<int, std::unordered_set<HexCoord>> visited_;
  std::unordered_map<int, int> player_gold_;
  std::unordered_map<HexCoord, int> soil_fertility_bonus_;
  std::unordered_map<HexCoord, int> cultivation_tier_;
  std::unordered_map<HexCoord, TileImprovement> tile_improvements_;
  std::unordered_map<HexCoord, Terrain> terrain_override_;
  std::unordered_map<int, std::vector<HexCoord>> planned_routes_;
  std::vector<WildAnimal> wildlife_;
  std::unordered_map<int, int> wildlife_city_raid_streak_;
  int wildlife_spawn_nonce_{};
  int wildlife_spawned_last_step_{};
  int wildlife_spawned_last_step_round_{};
  int chronology_offset_years_{};
  std::vector<WeatherSystem> weather_systems_;
  int next_weather_patch_id_{};
  int weather_nonce_{};
  int current_player_index_{};
  int round_{};
  int next_unit_id_{};
  int next_city_id_{};
  int next_wild_animal_id_{};
  std::optional<int> winner_seat_;
  bool following_planned_route_now_{false};

  void bootstrap_session_state(bool new_game);

  static int clamp_years_per_round(int years) noexcept;
  static std::uint64_t mix64_world(std::int64_t world_seed, int b, int c) noexcept;
  static int hex_tie_break(HexCoord a, HexCoord b) noexcept;

  void start_turn();
  void refresh_moves_for(int seat);
  void produce_for(int seat);
  void update_visited_for(int seat);
  void check_win();
  void advance_all_hunt_missions();
  void step_wildlife_turn();

  void spawn_from_city(City& c, UnitKind kind);
  [[nodiscard]] std::optional<HexCoord> find_free_adjacent(HexCoord origin) const;

  [[nodiscard]] bool is_inside_own_border(HexCoord c, int seat) const;

  [[nodiscard]] int tile_yield_score(HexCoord c) const;
  [[nodiscard]] int tile_yield_score_for_focus(HexCoord c, CityFocus focus) const;

  [[nodiscard]] int yield_food_at_tile(HexCoord tile, int city_res) const;
  [[nodiscard]] int yield_prod_at_tile(int raw, HexCoord tile, int city_res) const;
  [[nodiscard]] int yield_gold_at_tile(int raw, HexCoord tile, int city_res) const;

  [[nodiscard]] int min_distance_to_unvisited_passable(HexCoord from, int seat) const;

  bool run_auto_explore_step(Unit& u);

  void add_disk(std::unordered_set<HexCoord>& out, HexCoord center, int radius) const;

  [[nodiscard]] std::optional<std::size_t> unit_index_at(HexCoord c) const;
  [[nodiscard]] std::optional<std::size_t> unit_index_by_id(int id) const;
  [[nodiscard]] std::optional<std::size_t> city_index_at(HexCoord c) const;
  [[nodiscard]] std::optional<std::size_t> wild_animal_index_at(HexCoord c) const;
  [[nodiscard]] std::optional<std::size_t> city_index_by_id(int id) const;

  [[nodiscard]] int city_weather_resilience(City const& c) const;

  bool is_wild_spawn_anchor_ok(HexCoord anchor) const;
  bool can_extend_herd_tile(HexCoord n, std::vector<HexCoord> const& batch) const;
  void expand_herd_batch(std::vector<HexCoord>& batch, int target_size, JavaRandom& s);
  [[nodiscard]] int target_wildlife_count() const;
  int try_spawn_wildlife_group(int target_size, JavaRandom& s);
  int maybe_spawn_wildlife_this_round();
  void wild_attack_unit(WildAnimal& a, Unit& u, JavaRandom& rng);
  void wild_raid_city(WildAnimal& a, City& city, JavaRandom& rng);
  [[nodiscard]] HexCoord pick_wildlife_wander_step(std::vector<HexCoord> const& legal,
                                                   JavaRandom& rng) const;
  void step_one_wild_animal(WildAnimal& a, JavaRandom& rng);

  [[nodiscard]] std::string next_founded_city_name();
};

inline constexpr BypassWorldGenTag bypass_world_gen{};

}  // namespace tack::strat

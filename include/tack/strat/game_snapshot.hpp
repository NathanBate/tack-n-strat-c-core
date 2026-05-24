#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tack/strat/player.hpp"

namespace tack::strat {

/** Serializable game state (mirrors Java {@code GameSnapshot}); used for capture/restore parity. */
struct GameSnapshot {
  static constexpr int FORMAT_VERSION = 7;

  struct MapCell {
    int q{};
    int r{};
    std::string terrain{};
    auto operator<=>(MapCell const&) const noexcept = default;
  };

  struct UnitSnap {
    int id{};
    int owner_seat{};
    std::string kind{};
    int q{};
    int r{};
    int hp{};
    int moves_remaining{};
    bool auto_explore{};
    std::optional<bool> sleeping{};
    std::optional<int> carried_food{};
    auto operator<=>(UnitSnap const&) const noexcept = default;
  };

  struct HuntMissionSnap {
    int turns_remaining{};
    int target_animal_id{};
    auto operator<=>(HuntMissionSnap const&) const noexcept = default;
  };

  struct CitySnap {
    int id{};
    int owner_seat{};
    int q{};
    int r{};
    std::string name{};
    std::optional<std::string> current_build{};
    std::vector<std::string> queued_builds{};
    int production_stored{};
    int hp{};
    std::optional<int> population{};
    std::optional<int> food_stored{};
    std::vector<HuntMissionSnap> hunt_missions{};
    std::optional<std::string> focus{};
    std::vector<std::string> buildings{};
    auto operator<=>(CitySnap const&) const noexcept = default;
  };

  struct SeatCount {
    int seat{};
    int count{};
    auto operator<=>(SeatCount const&) const noexcept = default;
  };

  struct VisTile {
    int seat{};
    int q{};
    int r{};
    auto operator<=>(VisTile const&) const noexcept = default;
  };

  struct RouteTile {
    int unit_id{};
    int order{};
    int q{};
    int r{};
    auto operator<=>(RouteTile const&) const noexcept = default;
  };

  struct TileSoilSnap {
    int q{};
    int r{};
    int bonus{};
    auto operator<=>(TileSoilSnap const&) const noexcept = default;
  };

  struct TileModsSnap {
    int q{};
    int r{};
    int cultivation{};
    std::string improvement{};
    auto operator<=>(TileModsSnap const&) const noexcept = default;
  };

  struct WildlifeSnap {
    int id{};
    std::string kind{};
    int q{};
    int r{};
    int hp{};
    auto operator<=>(WildlifeSnap const&) const noexcept = default;
  };

  struct WeatherPatchSnap {
    int id{};
    std::string kind{};
    int center_q{};
    int center_r{};
    int radius{};
    auto operator<=>(WeatherPatchSnap const&) const noexcept = default;
  };

  int format_version{};
  std::int64_t world_seed{};
  int combat_rng_call_count{};
  int current_player_index{};
  int round{};
  int next_unit_id{};
  int next_city_id{};
  std::optional<int> winner_seat{};
  std::vector<Player> players{};
  int map_radius{};
  std::vector<MapCell> map_cells{};
  std::vector<UnitSnap> units{};
  std::vector<CitySnap> cities{};
  std::vector<SeatCount> city_names_issued{};
  std::vector<VisTile> visited{};
  std::vector<SeatCount> gold_by_seat{};
  std::vector<RouteTile> planned_routes{};
  std::vector<TileSoilSnap> tile_soil{};
  std::vector<TileModsSnap> tile_mods{};
  std::vector<WildlifeSnap> wildlife{};
  std::optional<int> next_wild_animal_id{};
  std::optional<int> wildlife_spawn_nonce{};
  std::optional<int> chronology_offset_years{};
  /** Legacy v2 field; retained for format alignment. */
  std::optional<std::string> current_weather{};
  std::optional<int> weather_nonce{};
  std::optional<int> years_per_full_round{};
  std::optional<int> season_ordinal{};
  std::vector<WeatherPatchSnap> weather_patches{};
  std::optional<int> next_weather_patch_id{};

  friend bool operator==(GameSnapshot const& a, GameSnapshot const& b) noexcept {
    return a.format_version == b.format_version && a.world_seed == b.world_seed &&
           a.combat_rng_call_count == b.combat_rng_call_count &&
           a.current_player_index == b.current_player_index && a.round == b.round &&
           a.next_unit_id == b.next_unit_id && a.next_city_id == b.next_city_id &&
           a.winner_seat == b.winner_seat && a.players == b.players && a.map_radius == b.map_radius &&
           a.map_cells == b.map_cells && a.units == b.units && a.cities == b.cities &&
           a.city_names_issued == b.city_names_issued && a.visited == b.visited &&
           a.gold_by_seat == b.gold_by_seat && a.planned_routes == b.planned_routes &&
           a.tile_soil == b.tile_soil && a.tile_mods == b.tile_mods && a.wildlife == b.wildlife &&
           a.next_wild_animal_id == b.next_wild_animal_id &&
           a.wildlife_spawn_nonce == b.wildlife_spawn_nonce &&
           a.chronology_offset_years == b.chronology_offset_years &&
           a.current_weather == b.current_weather && a.weather_nonce == b.weather_nonce &&
           a.years_per_full_round == b.years_per_full_round && a.season_ordinal == b.season_ordinal &&
           a.weather_patches == b.weather_patches &&
           a.next_weather_patch_id == b.next_weather_patch_id;
  }
};

}  // namespace tack::strat

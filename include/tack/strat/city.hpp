#pragma once

#include <deque>
#include <optional>
#include <string>
#include <unordered_set>
#include <vector>

#include "tack/strat/city_building.hpp"
#include "tack/strat/city_focus.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/unit_kind.hpp"

namespace tack::strat {

/** Founded city: production, growth, buildings (ported from Java {@code City}). */
class City {
 public:
  static constexpr int PRODUCTION_PER_TURN = 2;
  static constexpr int MAX_HP = 20;

  struct HuntMission {
    int turns_remaining{};
    int target_animal_id{};
  };

  City(int id, int owner_seat, HexCoord coord, std::string name,
       std::optional<UnitKind> initial_build);

  [[nodiscard]] int id() const noexcept {
    return id_;
  }
  [[nodiscard]] int owner_seat() const noexcept {
    return owner_seat_;
  }
  [[nodiscard]] HexCoord coord() const noexcept {
    return coord_;
  }
  [[nodiscard]] std::string const& name() const noexcept {
    return name_;
  }

  [[nodiscard]] std::optional<UnitKind> current_build() const noexcept {
    return current_build_;
  }
  [[nodiscard]] int production_stored() const noexcept {
    return production_stored_;
  }
  [[nodiscard]] int hp() const noexcept {
    return hp_;
  }
  [[nodiscard]] int population() const noexcept {
    return population_;
  }
  [[nodiscard]] int food_stored() const noexcept {
    return food_stored_;
  }
  [[nodiscard]] CityFocus focus() const noexcept {
    return focus_;
  }
  [[nodiscard]] bool has_building(CityBuilding b) const {
    return buildings_.count(b) != 0;
  }
  [[nodiscard]] std::unordered_set<CityBuilding> const& buildings_set() const noexcept {
    return buildings_;
  }
  [[nodiscard]] std::vector<UnitKind> queued_builds() const;

  [[nodiscard]] int max_hp() const noexcept {
    return MAX_HP;
  }
  [[nodiscard]] std::vector<HuntMission> hunt_missions() const {
    return hunt_missions_;
  }
  [[nodiscard]] int hunt_parties_away() const noexcept {
    return static_cast<int>(hunt_missions_.size());
  }

  /** @return std::nullopt on success, error message on failure */
  [[nodiscard]] std::optional<std::string> start_hunt_mission(int target_animal_id, int rounds_away);

  [[nodiscard]] std::vector<int> advance_hunt_missions();

  void apply_wildlife_raid(int dmg) noexcept;

  void set_current_build(std::optional<UnitKind> kind);
  void add_production(int amount) noexcept {
    production_stored_ += amount;
  }
  void set_focus(CityFocus focus) noexcept {
    focus_ = focus;
  }
  bool add_building(CityBuilding building);

  void apply_food_yield(int food_yield);
  void add_foraged_food(int food);

  [[nodiscard]] bool consume_population_for_party();
  void return_population_from_party() noexcept {
    population_++;
  }

  [[nodiscard]] int growth_threshold() const;

  void enqueue_build(UnitKind kind);

  bool remove_queued_build(int index);
  bool move_queued_build(int index, int delta);

  [[nodiscard]] std::optional<UnitKind> drain_completed();

  void capture_by(int new_owner_seat) noexcept;

  /** Save/load (matches Java {@code City.applySavedState} / {@code applySavedCityMeta}). */
  void apply_saved_state(int saved_owner_seat, std::optional<UnitKind> saved_build,
                         std::vector<UnitKind> saved_queue, int saved_production_stored, int saved_hp,
                         int saved_population, int saved_food_stored,
                         std::vector<HuntMission> saved_hunts);
  void apply_saved_city_meta(CityFocus saved_focus, std::vector<CityBuilding> saved_buildings);

 private:
  int id_{};
  int owner_seat_{};
  HexCoord coord_{};
  std::string name_{};
  std::optional<UnitKind> current_build_{};
  std::deque<UnitKind> build_queue_{};
  int production_stored_{};
  int population_{1};
  int food_stored_{};
  int hp_{MAX_HP};
  CityFocus focus_{CityFocus::BALANCED};
  std::unordered_set<CityBuilding> buildings_{};
  std::vector<HuntMission> hunt_missions_{};
};

}  // namespace tack::strat

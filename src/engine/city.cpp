#include "tack/strat/city.hpp"

#include <algorithm>
#include <stdexcept>

namespace tack::strat {

City::City(int id, int owner_seat, HexCoord coord, std::string name,
           std::optional<UnitKind> initial_build)
    : id_(id),
      owner_seat_(owner_seat),
      coord_(coord),
      name_(std::move(name)),
      current_build_(initial_build) {}

std::vector<UnitKind> City::queued_builds() const {
  return std::vector<UnitKind>(build_queue_.begin(), build_queue_.end());
}

std::optional<std::string> City::start_hunt_mission(int target_animal_id, int rounds_away) {
  if (population_ < 2) {
    return std::string("Need at least 2 population to send a hunter.");
  }
  if (rounds_away < 1) {
    return std::string("Invalid hunt length.");
  }
  population_--;
  hunt_missions_.push_back(HuntMission{rounds_away, target_animal_id});
  return std::nullopt;
}

std::vector<int> City::advance_hunt_missions() {
  std::vector<int> animals_to_cull;
  for (auto it = hunt_missions_.begin(); it != hunt_missions_.end();) {
    it->turns_remaining--;
    if (it->turns_remaining <= 0) {
      population_++;
      animals_to_cull.push_back(it->target_animal_id);
      it = hunt_missions_.erase(it);
    } else {
      ++it;
    }
  }
  return animals_to_cull;
}

void City::apply_wildlife_raid(int dmg) noexcept {
  hp_ = std::max(1, hp_ - std::max(0, dmg));
}

void City::set_current_build(std::optional<UnitKind> kind) {
  if (kind != current_build_) {
    current_build_ = kind;
    production_stored_ = 0;
  }
}

bool City::add_building(CityBuilding building) {
  auto [_, inserted] = buildings_.insert(building);
  return inserted;
}

void City::apply_food_yield(int food_yield) {
  int upkeep = 1 + population_;
  int surplus = food_yield - upkeep;
  if (surplus <= 0) {
    food_stored_ = std::max(0, food_stored_ + surplus);
    return;
  }
  food_stored_ += surplus;
  while (food_stored_ >= growth_threshold()) {
    food_stored_ -= growth_threshold();
    population_++;
  }
}

void City::add_foraged_food(int food) {
  if (food <= 0) {
    return;
  }
  food_stored_ += food;
  while (food_stored_ >= growth_threshold()) {
    food_stored_ -= growth_threshold();
    population_++;
  }
}

bool City::consume_population_for_party() {
  if (population_ < 2) {
    return false;
  }
  population_--;
  return true;
}

int City::growth_threshold() const {
  int base = 8 + population_ * 4;
  if (has_building(CityBuilding::GRANARY)) {
    base = std::max(4, base - 2);
  }
  return base;
}

void City::enqueue_build(UnitKind kind) {
  if (!current_build_.has_value()) {
    current_build_ = kind;
    return;
  }
  build_queue_.push_back(kind);
}

bool City::remove_queued_build(int index) {
  if (index < 0 || index >= static_cast<int>(build_queue_.size())) {
    return false;
  }
  auto it = build_queue_.begin();
  std::advance(it, index);
  build_queue_.erase(it);
  return true;
}

bool City::move_queued_build(int index, int delta) {
  if (index < 0 || index >= static_cast<int>(build_queue_.size())) {
    return false;
  }
  int const to = index + delta;
  if (to < 0 || to >= static_cast<int>(build_queue_.size())) {
    return false;
  }
  std::vector<UnitKind> list(build_queue_.begin(), build_queue_.end());
  UnitKind item = list[static_cast<std::size_t>(index)];
  list.erase(list.begin() + index);
  list.insert(list.begin() + to, item);
  build_queue_.clear();
  for (UnitKind k : list) {
    build_queue_.push_back(k);
  }
  return true;
}

std::optional<UnitKind> City::drain_completed() {
  if (!current_build_.has_value()) {
    return std::nullopt;
  }
  int cost = unit_production_cost(*current_build_);
  if (production_stored_ < cost) {
    return std::nullopt;
  }
  UnitKind produced = *current_build_;
  production_stored_ -= cost;
  if (!build_queue_.empty()) {
    current_build_ = build_queue_.front();
    build_queue_.pop_front();
  } else {
    current_build_ = std::nullopt;
  }
  return produced;
}

void City::capture_by(int new_owner_seat) noexcept {
  owner_seat_ = new_owner_seat;
  hp_ = MAX_HP / 2;
  production_stored_ = 0;
}

void City::apply_saved_state(int saved_owner_seat, std::optional<UnitKind> saved_build,
                             std::vector<UnitKind> saved_queue, int saved_production_stored,
                             int saved_hp, int saved_population, int saved_food_stored,
                             std::vector<HuntMission> saved_hunts) {
  if (saved_hp < 0 || saved_hp > MAX_HP) {
    throw std::invalid_argument("Invalid city HP");
  }
  if (saved_production_stored < 0) {
    throw std::invalid_argument("Invalid production");
  }
  if (saved_population < 1) {
    throw std::invalid_argument("Invalid population");
  }
  if (saved_food_stored < 0) {
    throw std::invalid_argument("Invalid food storage");
  }
  owner_seat_ = saved_owner_seat;
  current_build_ = saved_build;
  build_queue_.clear();
  for (UnitKind k : saved_queue) {
    build_queue_.push_back(k);
  }
  production_stored_ = saved_production_stored;
  population_ = saved_population;
  food_stored_ = saved_food_stored;
  hp_ = saved_hp;
  focus_ = CityFocus::BALANCED;
  buildings_.clear();
  hunt_missions_ = std::move(saved_hunts);
}

void City::apply_saved_city_meta(CityFocus saved_focus, std::vector<CityBuilding> saved_buildings) {
  focus_ = saved_focus;
  buildings_.clear();
  for (CityBuilding b : saved_buildings) {
    buildings_.insert(b);
  }
}

}  // namespace tack::strat

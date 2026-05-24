#include "tack/strat/snapshot_json_io.hpp"

#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string_view>

#include <nlohmann/json.hpp>

#include "tack/strat/game_snapshot.hpp"
#include "tack/strat/player.hpp"

namespace tack::strat {

using json = nlohmann::json;

void to_json(json& j, Player const& p) {
  j = json{{"seat", p.seat}, {"name", p.name}, {"computer", p.computer}};
}

void from_json(json const& j, Player& p) {
  p = Player(j.at("seat").get<int>(), j.at("name").get<std::string>(),
             j.at("computer").get<bool>());
}

void to_json(json& j, GameSnapshot::MapCell const& c) {
  j = json{{"q", c.q}, {"r", c.r}, {"terrain", c.terrain}};
}

void from_json(json const& j, GameSnapshot::MapCell& c) {
  c.q = j.at("q").get<int>();
  c.r = j.at("r").get<int>();
  c.terrain = j.at("terrain").get<std::string>();
}

void to_json(json& j, GameSnapshot::HuntMissionSnap const& m) {
  j = json{{"turns_remaining", m.turns_remaining}, {"target_animal_id", m.target_animal_id}};
}

void from_json(json const& j, GameSnapshot::HuntMissionSnap& m) {
  m.turns_remaining = j.at("turns_remaining").get<int>();
  m.target_animal_id = j.at("target_animal_id").get<int>();
}

void to_json(json& j, GameSnapshot::UnitSnap const& u) {
  j = json{{"id", u.id},
           {"owner_seat", u.owner_seat},
           {"kind", u.kind},
           {"q", u.q},
           {"r", u.r},
           {"hp", u.hp},
           {"moves_remaining", u.moves_remaining},
           {"auto_explore", u.auto_explore}};
  j["sleeping"] = u.sleeping.has_value() ? json(*u.sleeping) : json(nullptr);
  j["carried_food"] = u.carried_food.has_value() ? json(*u.carried_food) : json(nullptr);
}

void from_json(json const& j, GameSnapshot::UnitSnap& u) {
  u.id = j.at("id").get<int>();
  u.owner_seat = j.at("owner_seat").get<int>();
  u.kind = j.at("kind").get<std::string>();
  u.q = j.at("q").get<int>();
  u.r = j.at("r").get<int>();
  u.hp = j.at("hp").get<int>();
  u.moves_remaining = j.at("moves_remaining").get<int>();
  u.auto_explore = j.at("auto_explore").get<bool>();
  if (j.contains("sleeping") && !j.at("sleeping").is_null()) {
    u.sleeping = j.at("sleeping").get<bool>();
  } else {
    u.sleeping.reset();
  }
  if (j.contains("carried_food") && !j.at("carried_food").is_null()) {
    u.carried_food = j.at("carried_food").get<int>();
  } else {
    u.carried_food.reset();
  }
}

void to_json(json& j, GameSnapshot::CitySnap const& c) {
  j = json{{"id", c.id},
           {"owner_seat", c.owner_seat},
           {"q", c.q},
           {"r", c.r},
           {"name", c.name},
           {"production_stored", c.production_stored},
           {"hp", c.hp},
           {"queued_builds", c.queued_builds},
           {"hunt_missions", json::array()}};
  j["current_build"] = c.current_build.has_value() ? json(*c.current_build) : json(nullptr);
  j["population"] = c.population.has_value() ? json(*c.population) : json(nullptr);
  j["food_stored"] = c.food_stored.has_value() ? json(*c.food_stored) : json(nullptr);
  j["focus"] = c.focus.has_value() ? json(*c.focus) : json(nullptr);
  j["buildings"] = c.buildings;
  for (auto const& hm : c.hunt_missions) {
    json hj;
    to_json(hj, hm);
    j["hunt_missions"].push_back(hj);
  }
}

void from_json(json const& j, GameSnapshot::CitySnap& c) {
  c.id = j.at("id").get<int>();
  c.owner_seat = j.at("owner_seat").get<int>();
  c.q = j.at("q").get<int>();
  c.r = j.at("r").get<int>();
  c.name = j.at("name").get<std::string>();
  c.production_stored = j.at("production_stored").get<int>();
  c.hp = j.at("hp").get<int>();
  c.queued_builds = j.at("queued_builds").get<std::vector<std::string>>();
  c.hunt_missions.clear();
  for (auto const& el : j.at("hunt_missions")) {
    GameSnapshot::HuntMissionSnap hm{};
    from_json(el, hm);
    c.hunt_missions.push_back(hm);
  }
  if (j.contains("current_build") && !j.at("current_build").is_null()) {
    c.current_build = j.at("current_build").get<std::string>();
  } else {
    c.current_build.reset();
  }
  if (j.contains("population") && !j.at("population").is_null()) {
    c.population = j.at("population").get<int>();
  } else {
    c.population.reset();
  }
  if (j.contains("food_stored") && !j.at("food_stored").is_null()) {
    c.food_stored = j.at("food_stored").get<int>();
  } else {
    c.food_stored.reset();
  }
  if (j.contains("focus") && !j.at("focus").is_null()) {
    c.focus = j.at("focus").get<std::string>();
  } else {
    c.focus.reset();
  }
  c.buildings = j.at("buildings").get<std::vector<std::string>>();
}

void to_json(json& j, GameSnapshot::SeatCount const& s) {
  j = json{{"seat", s.seat}, {"count", s.count}};
}

void from_json(json const& j, GameSnapshot::SeatCount& s) {
  s.seat = j.at("seat").get<int>();
  s.count = j.at("count").get<int>();
}

void to_json(json& j, GameSnapshot::VisTile const& v) {
  j = json{{"seat", v.seat}, {"q", v.q}, {"r", v.r}};
}

void from_json(json const& j, GameSnapshot::VisTile& v) {
  v.seat = j.at("seat").get<int>();
  v.q = j.at("q").get<int>();
  v.r = j.at("r").get<int>();
}

void to_json(json& j, GameSnapshot::RouteTile const& r) {
  j = json{{"unit_id", r.unit_id}, {"order", r.order}, {"q", r.q}, {"r", r.r}};
}

void from_json(json const& j, GameSnapshot::RouteTile& r) {
  r.unit_id = j.at("unit_id").get<int>();
  r.order = j.at("order").get<int>();
  r.q = j.at("q").get<int>();
  r.r = j.at("r").get<int>();
}

void to_json(json& j, GameSnapshot::TileSoilSnap const& t) {
  j = json{{"q", t.q}, {"r", t.r}, {"bonus", t.bonus}};
}

void from_json(json const& j, GameSnapshot::TileSoilSnap& t) {
  t.q = j.at("q").get<int>();
  t.r = j.at("r").get<int>();
  t.bonus = j.at("bonus").get<int>();
}

void to_json(json& j, GameSnapshot::TileModsSnap const& t) {
  j = json{{"q", t.q}, {"r", t.r}, {"cultivation", t.cultivation}, {"improvement", t.improvement}};
}

void from_json(json const& j, GameSnapshot::TileModsSnap& t) {
  t.q = j.at("q").get<int>();
  t.r = j.at("r").get<int>();
  t.cultivation = j.at("cultivation").get<int>();
  t.improvement = j.at("improvement").get<std::string>();
}

void to_json(json& j, GameSnapshot::WildlifeSnap const& w) {
  j = json{{"id", w.id}, {"kind", w.kind}, {"q", w.q}, {"r", w.r}, {"hp", w.hp}};
}

void from_json(json const& j, GameSnapshot::WildlifeSnap& w) {
  w.id = j.at("id").get<int>();
  w.kind = j.at("kind").get<std::string>();
  w.q = j.at("q").get<int>();
  w.r = j.at("r").get<int>();
  w.hp = j.at("hp").get<int>();
}

void to_json(json& j, GameSnapshot::WeatherPatchSnap const& w) {
  j = json{{"id", w.id},
           {"kind", w.kind},
           {"center_q", w.center_q},
           {"center_r", w.center_r},
           {"radius", w.radius}};
}

void from_json(json const& j, GameSnapshot::WeatherPatchSnap& w) {
  w.id = j.at("id").get<int>();
  w.kind = j.at("kind").get<std::string>();
  w.center_q = j.at("center_q").get<int>();
  w.center_r = j.at("center_r").get<int>();
  w.radius = j.at("radius").get<int>();
}

void to_json(json& j, GameSnapshot const& s) {
  j["format_version"] = s.format_version;
  j["world_seed"] = s.world_seed;
  j["combat_rng_call_count"] = s.combat_rng_call_count;
  j["current_player_index"] = s.current_player_index;
  j["round"] = s.round;
  j["next_unit_id"] = s.next_unit_id;
  j["next_city_id"] = s.next_city_id;
  j["winner_seat"] = s.winner_seat.has_value() ? json(*s.winner_seat) : json(nullptr);
  j["players"] = s.players;
  j["map_radius"] = s.map_radius;
  j["map_cells"] = s.map_cells;
  j["units"] = s.units;
  j["cities"] = s.cities;
  j["city_names_issued"] = s.city_names_issued;
  j["visited"] = s.visited;
  j["gold_by_seat"] = s.gold_by_seat;
  j["planned_routes"] = s.planned_routes;
  j["tile_soil"] = s.tile_soil;
  j["tile_mods"] = s.tile_mods;
  j["wildlife"] = s.wildlife;
  j["next_wild_animal_id"] =
      s.next_wild_animal_id.has_value() ? json(*s.next_wild_animal_id) : json(nullptr);
  j["wildlife_spawn_nonce"] =
      s.wildlife_spawn_nonce.has_value() ? json(*s.wildlife_spawn_nonce) : json(nullptr);
  j["chronology_offset_years"] =
      s.chronology_offset_years.has_value() ? json(*s.chronology_offset_years) : json(nullptr);
  j["current_weather"] =
      s.current_weather.has_value() ? json(*s.current_weather) : json(nullptr);
  j["weather_nonce"] = s.weather_nonce.has_value() ? json(*s.weather_nonce) : json(nullptr);
  j["years_per_full_round"] =
      s.years_per_full_round.has_value() ? json(*s.years_per_full_round) : json(nullptr);
  j["season_ordinal"] = s.season_ordinal.has_value() ? json(*s.season_ordinal) : json(nullptr);
  j["weather_patches"] = s.weather_patches;
  j["next_weather_patch_id"] =
      s.next_weather_patch_id.has_value() ? json(*s.next_weather_patch_id) : json(nullptr);
}

void from_json(json const& j, GameSnapshot& s) {
  s.format_version = j.at("format_version").get<int>();
  s.world_seed = j.at("world_seed").get<std::int64_t>();
  s.combat_rng_call_count = j.at("combat_rng_call_count").get<int>();
  s.current_player_index = j.at("current_player_index").get<int>();
  s.round = j.at("round").get<int>();
  s.next_unit_id = j.at("next_unit_id").get<int>();
  s.next_city_id = j.at("next_city_id").get<int>();
  if (j.contains("winner_seat") && !j.at("winner_seat").is_null()) {
    s.winner_seat = j.at("winner_seat").get<int>();
  } else {
    s.winner_seat.reset();
  }
  s.players.clear();
  for (auto const& el : j.at("players")) {
    s.players.emplace_back(el.at("seat").get<int>(), el.at("name").get<std::string>(),
                           el.at("computer").get<bool>());
  }
  s.map_radius = j.at("map_radius").get<int>();
  s.map_cells = j.at("map_cells").get<std::vector<GameSnapshot::MapCell>>();
  s.units = j.at("units").get<std::vector<GameSnapshot::UnitSnap>>();
  s.cities.clear();
  for (auto const& el : j.at("cities")) {
    GameSnapshot::CitySnap cs{};
    from_json(el, cs);
    s.cities.push_back(std::move(cs));
  }
  s.city_names_issued = j.at("city_names_issued").get<std::vector<GameSnapshot::SeatCount>>();
  s.visited = j.at("visited").get<std::vector<GameSnapshot::VisTile>>();
  s.gold_by_seat = j.at("gold_by_seat").get<std::vector<GameSnapshot::SeatCount>>();
  s.planned_routes = j.at("planned_routes").get<std::vector<GameSnapshot::RouteTile>>();
  s.tile_soil = j.at("tile_soil").get<std::vector<GameSnapshot::TileSoilSnap>>();
  s.tile_mods = j.at("tile_mods").get<std::vector<GameSnapshot::TileModsSnap>>();
  s.wildlife = j.at("wildlife").get<std::vector<GameSnapshot::WildlifeSnap>>();
  auto read_opt_int = [&j](char const* key) -> std::optional<int> {
    if (!j.contains(key) || j.at(key).is_null()) {
      return std::nullopt;
    }
    return j.at(key).get<int>();
  };
  s.next_wild_animal_id = read_opt_int("next_wild_animal_id");
  s.wildlife_spawn_nonce = read_opt_int("wildlife_spawn_nonce");
  s.chronology_offset_years = read_opt_int("chronology_offset_years");
  if (j.contains("current_weather") && !j.at("current_weather").is_null()) {
    s.current_weather = j.at("current_weather").get<std::string>();
  } else {
    s.current_weather.reset();
  }
  s.weather_nonce = read_opt_int("weather_nonce");
  s.years_per_full_round = read_opt_int("years_per_full_round");
  s.season_ordinal = read_opt_int("season_ordinal");
  s.weather_patches =
      j.at("weather_patches").get<std::vector<GameSnapshot::WeatherPatchSnap>>();
  s.next_weather_patch_id = read_opt_int("next_weather_patch_id");
}

std::string snapshot_to_json_string(GameSnapshot const& snap) {
  json j;
  to_json(j, snap);
  return j.dump(2);
}

std::optional<GameSnapshot> snapshot_from_json_string(std::string_view text) {
  try {
    json const j = json::parse(text);
    GameSnapshot out{};
    from_json(j, out);
    return out;
  } catch (...) {
    return std::nullopt;
  }
}

bool snapshot_write_json_file(GameSnapshot const& snap, std::string const& path_utf8) {
  try {
    std::ofstream out(path_utf8, std::ios::binary | std::ios::trunc);
    if (!out) {
      return false;
    }
    out << snapshot_to_json_string(snap);
    return static_cast<bool>(out);
  } catch (...) {
    return false;
  }
}

std::optional<GameSnapshot> snapshot_read_json_file(std::string const& path_utf8) {
  try {
    std::ifstream in(path_utf8, std::ios::binary);
    if (!in) {
      return std::nullopt;
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return snapshot_from_json_string(ss.str());
  } catch (...) {
    return std::nullopt;
  }
}

}  // namespace tack::strat

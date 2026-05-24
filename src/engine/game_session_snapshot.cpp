#include "tack/strat/game_session.hpp"

#include <algorithm>
#include <stdexcept>
#include <unordered_map>

#include "tack/strat/chronology.hpp"
#include "tack/strat/city_name_pool.hpp"
#include "tack/strat/game_snapshot.hpp"
#include "tack/strat/regional_weather.hpp"
#include "tack/strat/season.hpp"
#include "tack/strat/tile_improvement.hpp"
#include "tack/strat/unit_kind.hpp"

namespace tack::strat {
namespace {

Terrain parse_terrain(std::string const& s) {
  if (s == "WATER") return Terrain::WATER;
  if (s == "GRASS") return Terrain::GRASS;
  if (s == "PLAINS") return Terrain::PLAINS;
  if (s == "DESERT") return Terrain::DESERT;
  if (s == "HILL") return Terrain::HILL;
  if (s == "FOREST") return Terrain::FOREST;
  if (s == "MOUNTAIN") return Terrain::MOUNTAIN;
  throw std::invalid_argument("Unknown terrain: " + s);
}

UnitKind parse_unit_kind(std::string const& name, int format_version) {
  if (name == "MINER" && format_version <= 4) {
    return UnitKind::BUILDER;
  }
  if (name == "SETTLER") return UnitKind::SETTLER;
  if (name == "SCOUT") return UnitKind::SCOUT;
  if (name == "WARRIOR") return UnitKind::WARRIOR;
  if (name == "FARMER") return UnitKind::FARMER;
  if (name == "BUILDER") return UnitKind::BUILDER;
  if (name == "HUNTING_PARTY") return UnitKind::HUNTING_PARTY;
  throw std::invalid_argument("Unknown unit kind: " + name);
}

WildAnimalKind parse_wild_kind(std::string const& name) {
  if (name == "WOLF") return WildAnimalKind::WOLF;
  if (name == "BEAR") return WildAnimalKind::BEAR;
  if (name == "BOAR") return WildAnimalKind::BOAR;
  if (name == "COUGAR") return WildAnimalKind::COUGAR;
  if (name == "JACKAL") return WildAnimalKind::JACKAL;
  if (name == "ELK") return WildAnimalKind::ELK;
  if (name == "DEER") return WildAnimalKind::DEER;
  if (name == "HORSE") return WildAnimalKind::HORSE;
  throw std::invalid_argument("Unknown wildlife kind: " + name);
}

CityFocus parse_city_focus(std::string const& name) {
  if (name.empty()) return CityFocus::BALANCED;
  if (name == "BALANCED") return CityFocus::BALANCED;
  if (name == "FOOD") return CityFocus::FOOD;
  if (name == "PRODUCTION") return CityFocus::PRODUCTION;
  if (name == "GOLD") return CityFocus::GOLD;
  return CityFocus::BALANCED;
}

std::optional<CityBuilding> try_parse_city_building(std::string const& name) {
  if (name.empty()) return std::nullopt;
  if (name == "GRANARY") return CityBuilding::GRANARY;
  if (name == "WORKSHOP") return CityBuilding::WORKSHOP;
  if (name == "MARKET") return CityBuilding::MARKET;
  return std::nullopt;
}

TileImprovement parse_tile_improvement(std::string const& name) {
  if (name == "NONE") return TileImprovement::NONE;
  if (name == "FARM") return TileImprovement::FARM;
  if (name == "MINE") return TileImprovement::MINE;
  return TileImprovement::NONE;
}

Weather parse_weather(std::string const& name) {
  if (name == "CLEAR") return Weather::CLEAR;
  if (name == "RAIN") return Weather::RAIN;
  if (name == "DROUGHT") return Weather::DROUGHT;
  if (name == "STORM") return Weather::STORM;
  if (name == "COLD_SNAP") return Weather::COLD_SNAP;
  if (name == "HEAT_WAVE") return Weather::HEAT_WAVE;
  if (name == "FOG") return Weather::FOG;
  throw std::invalid_argument("Unknown weather: " + name);
}

std::string tile_improvement_snapshot_name(TileImprovement i) {
  switch (i) {
    case TileImprovement::NONE:
      return "NONE";
    case TileImprovement::FARM:
      return "FARM";
    case TileImprovement::MINE:
      return "MINE";
  }
  return "NONE";
}

std::string city_building_snapshot_name(CityBuilding b) {
  switch (b) {
    case CityBuilding::GRANARY:
      return "GRANARY";
    case CityBuilding::WORKSHOP:
      return "WORKSHOP";
    case CityBuilding::MARKET:
      return "MARKET";
  }
  return "GRANARY";
}

std::string city_focus_snapshot_name(CityFocus f) {
  switch (f) {
    case CityFocus::BALANCED:
      return "BALANCED";
    case CityFocus::FOOD:
      return "FOOD";
    case CityFocus::PRODUCTION:
      return "PRODUCTION";
    case CityFocus::GOLD:
      return "GOLD";
  }
  return "BALANCED";
}

std::string weather_snapshot_name(Weather w) {
  switch (w) {
    case Weather::CLEAR:
      return "CLEAR";
    case Weather::RAIN:
      return "RAIN";
    case Weather::DROUGHT:
      return "DROUGHT";
    case Weather::STORM:
      return "STORM";
    case Weather::COLD_SNAP:
      return "COLD_SNAP";
    case Weather::HEAT_WAVE:
      return "HEAT_WAVE";
    case Weather::FOG:
      return "FOG";
  }
  return "CLEAR";
}

std::string terrain_snapshot_name(Terrain t) {
  switch (t) {
    case Terrain::WATER:
      return "WATER";
    case Terrain::GRASS:
      return "GRASS";
    case Terrain::PLAINS:
      return "PLAINS";
    case Terrain::DESERT:
      return "DESERT";
    case Terrain::HILL:
      return "HILL";
    case Terrain::FOREST:
      return "FOREST";
    case Terrain::MOUNTAIN:
      return "MOUNTAIN";
  }
  return "GRASS";
}

std::string wild_kind_snapshot_name(WildAnimalKind k) {
  switch (k) {
    case WildAnimalKind::WOLF:
      return "WOLF";
    case WildAnimalKind::BEAR:
      return "BEAR";
    case WildAnimalKind::BOAR:
      return "BOAR";
    case WildAnimalKind::COUGAR:
      return "COUGAR";
    case WildAnimalKind::JACKAL:
      return "JACKAL";
    case WildAnimalKind::ELK:
      return "ELK";
    case WildAnimalKind::DEER:
      return "DEER";
    case WildAnimalKind::HORSE:
      return "HORSE";
  }
  return "WOLF";
}

int clamp_years_snapshot(int y) {
  return std::max(1, std::min(99, y));
}

}  // namespace

struct SnapshotRestorer {
  static GameSnapshot capture_session(GameSession const& s) {
    GameSnapshot out;
    out.format_version = GameSnapshot::FORMAT_VERSION;
    out.world_seed = s.world_seed();
    out.combat_rng_call_count = s.combat_rng_call_count();
    out.current_player_index = s.current_player_index();
    out.round = s.round();
    out.next_unit_id = s.next_unit_id_;
    out.next_city_id = s.next_city_id_;
    if (s.winner_seat()) {
      out.winner_seat = *s.winner_seat();
    }

    out.players = s.players();
    std::sort(out.players.begin(), out.players.end(),
              [](Player const& a, Player const& b) { return a.seat < b.seat; });

    out.map_radius = s.map().radius();
    for (HexCoord c : s.map().all_cells()) {
      GameSnapshot::MapCell cell;
      cell.q = c.q;
      cell.r = c.r;
      cell.terrain = terrain_snapshot_name(s.terrain_effective_at(c));
      out.map_cells.push_back(std::move(cell));
    }
    std::sort(out.map_cells.begin(), out.map_cells.end(), [](auto const& a, auto const& b) {
      return a.q != b.q ? a.q < b.q : a.r < b.r;
    });

    for (Unit const& u : s.units()) {
      GameSnapshot::UnitSnap us;
      us.id = u.id();
      us.owner_seat = u.owner_seat();
      us.kind = unit_kind_snapshot_name(u.kind());
      us.q = u.coord().q;
      us.r = u.coord().r;
      us.hp = u.hp();
      us.moves_remaining = u.moves_remaining();
      us.auto_explore = u.auto_explore();
      us.sleeping = u.sleeping();
      us.carried_food = u.carried_food();
      out.units.push_back(std::move(us));
    }
    std::sort(out.units.begin(), out.units.end(),
              [](auto const& a, auto const& b) { return a.id < b.id; });

    for (City const& c : s.cities()) {
      GameSnapshot::CitySnap cs;
      cs.id = c.id();
      cs.owner_seat = c.owner_seat();
      cs.q = c.coord().q;
      cs.r = c.coord().r;
      cs.name = c.name();
      if (c.current_build()) {
        cs.current_build = std::string(unit_kind_snapshot_name(*c.current_build()));
      }
      for (UnitKind q : c.queued_builds()) {
        cs.queued_builds.push_back(unit_kind_snapshot_name(q));
      }
      cs.production_stored = c.production_stored();
      cs.hp = c.hp();
      cs.population = c.population();
      cs.food_stored = c.food_stored();
      for (City::HuntMission const& hm : c.hunt_missions()) {
        GameSnapshot::HuntMissionSnap ms;
        ms.turns_remaining = hm.turns_remaining;
        ms.target_animal_id = hm.target_animal_id;
        cs.hunt_missions.push_back(ms);
      }
      cs.focus = city_focus_snapshot_name(c.focus());
      for (CityBuilding b : c.buildings_set()) {
        cs.buildings.push_back(city_building_snapshot_name(b));
      }
      std::sort(cs.buildings.begin(), cs.buildings.end());
      out.cities.push_back(std::move(cs));
    }
    std::sort(out.cities.begin(), out.cities.end(),
              [](auto const& a, auto const& b) { return a.id < b.id; });

    std::unordered_map<int, int> founded_per_seat;
    for (City const& c : s.cities()) {
      founded_per_seat[c.owner_seat()]++;
    }
    for (auto const& e : founded_per_seat) {
      GameSnapshot::SeatCount sc;
      sc.seat = e.first;
      sc.count = e.second;
      out.city_names_issued.push_back(sc);
    }
    std::sort(out.city_names_issued.begin(), out.city_names_issued.end(),
              [](auto const& a, auto const& b) { return a.seat < b.seat; });

    for (Player const& p : out.players) {
      for (HexCoord h : s.visited_for(p.seat)) {
        GameSnapshot::VisTile vt;
        vt.seat = p.seat;
        vt.q = h.q;
        vt.r = h.r;
        out.visited.push_back(vt);
      }
    }
    std::sort(out.visited.begin(), out.visited.end(), [](auto const& a, auto const& b) {
      if (a.seat != b.seat) return a.seat < b.seat;
      if (a.q != b.q) return a.q < b.q;
      return a.r < b.r;
    });

    for (Player const& p : out.players) {
      GameSnapshot::SeatCount g;
      g.seat = p.seat;
      g.count = s.gold_for(p.seat);
      out.gold_by_seat.push_back(g);
    }
    std::sort(out.gold_by_seat.begin(), out.gold_by_seat.end(),
              [](auto const& a, auto const& b) { return a.seat < b.seat; });

    for (Unit const& u : s.units()) {
      auto route = s.planned_route_for(u.id());
      for (std::size_t i = 0; i < route.size(); ++i) {
        GameSnapshot::RouteTile rt;
        rt.unit_id = u.id();
        rt.order = static_cast<int>(i);
        rt.q = route[i].q;
        rt.r = route[i].r;
        out.planned_routes.push_back(rt);
      }
    }
    std::sort(out.planned_routes.begin(), out.planned_routes.end(),
              [](auto const& a, auto const& b) {
                if (a.unit_id != b.unit_id) return a.unit_id < b.unit_id;
                return a.order < b.order;
              });

    for (HexCoord c : s.map().all_cells()) {
      int soil = s.soil_fertility_at(c);
      if (soil != 0) {
        GameSnapshot::TileSoilSnap ts;
        ts.q = c.q;
        ts.r = c.r;
        ts.bonus = soil;
        out.tile_soil.push_back(ts);
      }
    }
    std::sort(out.tile_soil.begin(), out.tile_soil.end(), [](auto const& a, auto const& b) {
      return a.q != b.q ? a.q < b.q : a.r < b.r;
    });

    for (HexCoord c : s.map().all_cells()) {
      int cult = s.cultivation_at(c);
      TileImprovement imp = s.improvement_at(c);
      if (cult > 0 || imp != TileImprovement::NONE) {
        GameSnapshot::TileModsSnap tm;
        tm.q = c.q;
        tm.r = c.r;
        tm.cultivation = cult;
        tm.improvement = tile_improvement_snapshot_name(imp);
        out.tile_mods.push_back(std::move(tm));
      }
    }
    std::sort(out.tile_mods.begin(), out.tile_mods.end(), [](auto const& a, auto const& b) {
      return a.q != b.q ? a.q < b.q : a.r < b.r;
    });

    for (WildAnimal const& a : s.wildlife_list()) {
      GameSnapshot::WildlifeSnap ws;
      ws.id = a.id();
      ws.kind = wild_kind_snapshot_name(a.kind());
      ws.q = a.coord().q;
      ws.r = a.coord().r;
      ws.hp = a.hp();
      out.wildlife.push_back(std::move(ws));
    }
    std::sort(out.wildlife.begin(), out.wildlife.end(),
              [](auto const& a, auto const& b) { return a.id < b.id; });

    out.next_wild_animal_id = s.next_wild_animal_id_;
    out.wildlife_spawn_nonce = s.wildlife_spawn_nonce_;
    out.chronology_offset_years = s.chronology_offset_years();
    out.current_weather = std::nullopt;
    out.weather_nonce = s.weather_nonce_;
    out.years_per_full_round = s.years_per_full_round();
    out.season_ordinal =
        Chronology::season_index_from_elapsed_years(s.chronology_offset_years(), s.years_per_full_round());

    for (WeatherSystem const& ws : s.weather_systems_) {
      GameSnapshot::WeatherPatchSnap wp;
      wp.id = ws.id;
      wp.kind = weather_snapshot_name(ws.kind);
      wp.center_q = ws.center_q;
      wp.center_r = ws.center_r;
      wp.radius = ws.radius;
      out.weather_patches.push_back(wp);
    }
    std::sort(out.weather_patches.begin(), out.weather_patches.end(),
              [](auto const& a, auto const& b) { return a.id < b.id; });

    out.next_weather_patch_id = s.next_weather_patch_id_;

    return out;
  }

  static GameSession restore_session(GameSnapshot snap) {
    if (snap.format_version < 1 || snap.format_version > GameSnapshot::FORMAT_VERSION) {
      throw std::invalid_argument("Unsupported save format version");
    }
    std::vector<Player> players = snap.players;
    if (players.empty() || players.size() > 4) {
      throw std::invalid_argument("Save has invalid player count");
    }

    std::unordered_map<HexCoord, Terrain> terrain_map;
    for (auto const& cell : snap.map_cells) {
      terrain_map[HexCoord{cell.q, cell.r}] = parse_terrain(cell.terrain);
    }
    GameMap map(snap.map_radius, std::move(terrain_map));

    std::vector<Unit> units;
    for (auto const& us : snap.units) {
      UnitKind kind = parse_unit_kind(us.kind, snap.format_version);
      Unit u(us.id, us.owner_seat, kind, HexCoord{us.q, us.r});
      u.apply_saved_combat_state(us.hp, us.moves_remaining);
      u.set_auto_explore(us.auto_explore);
      u.set_sleeping(us.sleeping.value_or(false));
      u.apply_saved_carried_food(us.carried_food.value_or(0));
      units.push_back(std::move(u));
    }

    std::vector<City> cities;
    for (auto const& cs : snap.cities) {
      std::optional<UnitKind> build;
      if (cs.current_build && !cs.current_build->empty()) {
        build = parse_unit_kind(*cs.current_build, snap.format_version);
      }
      std::vector<UnitKind> queue;
      for (std::string const& q : cs.queued_builds) {
        queue.push_back(parse_unit_kind(q, snap.format_version));
      }
      City city(cs.id, cs.owner_seat, HexCoord{cs.q, cs.r}, cs.name, UnitKind::WARRIOR);
      int pop = cs.population.value_or(1);
      int food = cs.food_stored.value_or(0);
      std::vector<City::HuntMission> hunts;
      for (auto const& hm : cs.hunt_missions) {
        hunts.push_back(City::HuntMission{hm.turns_remaining, hm.target_animal_id});
      }
      city.apply_saved_state(cs.owner_seat, build, std::move(queue), cs.production_stored, cs.hp, pop, food,
                             std::move(hunts));
      CityFocus f = parse_city_focus(cs.focus.value_or(""));
      std::vector<CityBuilding> blds;
      for (std::string const& bn : cs.buildings) {
        if (auto b = try_parse_city_building(bn)) {
          blds.push_back(*b);
        }
      }
      city.apply_saved_city_meta(f, std::move(blds));
      cities.push_back(std::move(city));
    }

    std::unordered_map<int, std::unordered_set<HexCoord>> visited;
    for (auto const& vt : snap.visited) {
      visited[vt.seat].insert(HexCoord{vt.q, vt.r});
    }

    std::unordered_map<int, std::vector<HexCoord>> routes_from_save;
    {
      std::unordered_map<int, std::vector<GameSnapshot::RouteTile>> grouped;
      for (auto const& rt : snap.planned_routes) {
        grouped[rt.unit_id].push_back(rt);
      }
      for (auto& e : grouped) {
        std::sort(e.second.begin(), e.second.end(),
                  [](auto const& a, auto const& b) { return a.order < b.order; });
        std::vector<HexCoord> path;
        for (auto const& rt : e.second) {
          path.push_back(HexCoord{rt.q, rt.r});
        }
        routes_from_save[e.first] = std::move(path);
      }
    }

    std::unordered_map<int, int> gold_from_save;
    for (auto const& g : snap.gold_by_seat) {
      gold_from_save[g.seat] = g.count;
    }

    std::unordered_map<HexCoord, int> soil_from_save;
    for (auto const& t : snap.tile_soil) {
      soil_from_save[HexCoord{t.q, t.r}] = t.bonus;
    }

    std::unordered_map<HexCoord, int> cult_from_save;
    std::unordered_map<HexCoord, TileImprovement> impr_from_save;
    for (auto const& m : snap.tile_mods) {
      HexCoord hc{m.q, m.r};
      cult_from_save[hc] = m.cultivation;
      impr_from_save[hc] = parse_tile_improvement(m.improvement);
    }

    std::vector<WildAnimal> animals;
    int next_w = 1;
    for (auto const& ws : snap.wildlife) {
      WildAnimalKind k = parse_wild_kind(ws.kind);
      WildAnimal a(ws.id, k, HexCoord{ws.q, ws.r});
      a.apply_saved_hp(ws.hp);
      animals.push_back(std::move(a));
      next_w = std::max(next_w, ws.id + 1);
    }
    if (snap.next_wild_animal_id) {
      next_w = std::max(next_w, *snap.next_wild_animal_id);
    }
    int wild_nonce = snap.wildlife_spawn_nonce.value_or(0);

    std::vector<WeatherSystem> wx_systems;
    int next_wx_id = 1;
    if (!snap.weather_patches.empty()) {
      for (auto const& wp : snap.weather_patches) {
        try {
          Weather k = parse_weather(wp.kind);
          wx_systems.push_back(WeatherSystem{wp.id, k, wp.center_q, wp.center_r, wp.radius});
          next_wx_id = std::max(next_wx_id, wp.id + 1);
        } catch (...) {
        }
      }
    }
    if (wx_systems.empty()) {
      Weather legacy = Weather::CLEAR;
      if (snap.current_weather && !snap.current_weather->empty()) {
        try {
          legacy = parse_weather(*snap.current_weather);
        } catch (...) {
        }
      }
      auto land = map.passable_land();
      if (!land.empty()) {
        HexCoord c = land.front();
        int rad = std::max(2, std::min(map.radius() + 1, 6));
        wx_systems.push_back(WeatherSystem{1, legacy, c.q, c.r, rad});
        next_wx_id = 2;
      }
    }
    if (wx_systems.empty()) {
      WeatherBootstrap boot = initial_systems(map, snap.world_seed);
      wx_systems = std::move(boot.systems);
      next_wx_id = boot.next_id;
    }
    if (snap.next_weather_patch_id) {
      next_wx_id = std::max(next_wx_id, *snap.next_weather_patch_id);
    }

    int chrono_years = snap.chronology_offset_years.value_or(0);
    int wx_nonce = snap.weather_nonce.value_or(0);
    int ypr = snap.years_per_full_round ? clamp_years_snapshot(*snap.years_per_full_round)
                                        : Chronology::DEFAULT_YEARS_PER_FULL_ROUND;

    GameSession session(BypassWorldGenTag{}, std::move(players), std::move(map), std::move(units),
                        std::move(cities), std::move(soil_from_save), std::move(animals), snap.world_seed, ypr);

    session.current_player_index_ = snap.current_player_index;
    session.round_ = snap.round;
    session.next_unit_id_ = snap.next_unit_id;
    session.next_city_id_ = snap.next_city_id;
    session.next_wild_animal_id_ = next_w;
    session.wildlife_spawn_nonce_ = wild_nonce;
    session.chronology_offset_years_ = chrono_years;
    session.weather_nonce_ = wx_nonce;
    session.weather_systems_ = std::move(wx_systems);
    session.next_weather_patch_id_ = next_wx_id;
    session.combat_rng_ = CombatRng(snap.world_seed, snap.combat_rng_call_count);
    session.visited_ = std::move(visited);
    session.player_gold_ = std::move(gold_from_save);
    session.planned_routes_ = std::move(routes_from_save);
    session.cultivation_tier_ = std::move(cult_from_save);
    session.tile_improvements_ = std::move(impr_from_save);
    session.terrain_override_.clear();
    session.winner_seat_ = snap.winner_seat;
    session.city_name_cursor_ = static_cast<int>(session.cities_.size());
    session.following_planned_route_now_ = false;
    session.wildlife_city_raid_streak_.clear();
    session.city_name_deck_ = CityNamePool::shuffled_deck(snap.world_seed);

    return session;
  }
};

GameSnapshot GameSession::capture() const {
  return SnapshotRestorer::capture_session(*this);
}

GameSession GameSession::restore(GameSnapshot snap) {
  return SnapshotRestorer::restore_session(std::move(snap));
}

}  // namespace tack::strat

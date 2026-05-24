#include "tack/strat/regional_weather.hpp"

#include <algorithm>
#include <unordered_map>
#include <vector>

#include "tack/strat/mix64.hpp"
#include "tack/strat/terrain.hpp"
#include "tack/strat/weather.hpp"

namespace tack::strat {

namespace {

Weather const kAllWeather[] = {Weather::CLEAR, Weather::RAIN,    Weather::DROUGHT,
                               Weather::STORM, Weather::COLD_SNAP, Weather::HEAT_WAVE, Weather::FOG};
int constexpr kWeatherCount = 7;

int clamp_radius(GameMap const& map, int radius) {
  int max_r = std::max(1, map.radius() + 2);
  return std::max(1, std::min(max_r, radius));
}

bool is_adjacent_to_water(GameMap const& map, HexCoord c) {
  for (HexCoord n : c.neighbors()) {
    if (map.contains(n) && map.terrain_at(n) == Terrain::WATER) {
      return true;
    }
  }
  return false;
}

double terrain_kind_bias(Weather wx, GameMap const& map, HexCoord center) {
  if (!map.contains(center)) {
    return 1.0;
  }
  Terrain t = map.terrain_at(center);
  bool coast = is_adjacent_to_water(map, center);
  switch (wx) {
    case Weather::STORM:
      return coast ? 1.48 : 0.92;
    case Weather::RAIN:
      return coast ? 1.28 : 1.05;
    case Weather::DROUGHT:
      return (t == Terrain::DESERT) ? 1.45 : 0.95;
    case Weather::HEAT_WAVE:
      return (t == Terrain::DESERT) ? 1.4 : 1.0;
    case Weather::FOG:
      return (t == Terrain::FOREST) ? 1.32 : 1.0;
    case Weather::COLD_SNAP:
      return (t == Terrain::HILL || t == Terrain::FOREST) ? 1.15 : 1.0;
    default:
      return 1.0;
  }
}

double season_weight(Season s, Weather wx) {
  switch (s) {
    case Season::SPRING:
      switch (wx) {
        case Weather::RAIN:
          return 1.35;
        case Weather::FOG:
          return 1.15;
        case Weather::CLEAR:
          return 1.12;
        case Weather::STORM:
          return 0.95;
        default:
          return 0.85;
      }
    case Season::SUMMER:
      switch (wx) {
        case Weather::HEAT_WAVE:
          return 1.45;
        case Weather::DROUGHT:
          return 1.25;
        case Weather::CLEAR:
          return 1.1;
        case Weather::STORM:
          return 1.05;
        default:
          return 0.78;
      }
    case Season::AUTUMN:
      switch (wx) {
        case Weather::RAIN:
          return 1.2;
        case Weather::STORM:
          return 1.15;
        case Weather::FOG:
          return 1.2;
        case Weather::CLEAR:
          return 1.05;
        default:
          return 0.88;
      }
    case Season::WINTER:
      switch (wx) {
        case Weather::COLD_SNAP:
          return 1.5;
        case Weather::FOG:
          return 1.25;
        case Weather::STORM:
          return 1.05;
        case Weather::CLEAR:
          return 0.95;
        default:
          return 0.82;
      }
  }
  return 1.0;
}

Weather wander_kind(Weather current, Season season, JavaRandom& rng, GameMap const& map,
                    HexCoord center) {
  double w[kWeatherCount]{};
  double sum = 0;
  for (int i = 0; i < kWeatherCount; ++i) {
    Weather v = kAllWeather[i];
    double base = season_weight(season, v);
    if (v == current) {
      base *= 0.42;
    }
    base *= terrain_kind_bias(v, map, center);
    w[i] = base * (0.82 + rng.next_double() * 0.38);
    sum += w[i];
  }
  double t = rng.next_double() * sum;
  double c = 0;
  for (int i = 0; i < kWeatherCount; ++i) {
    c += w[i];
    if (t <= c) {
      return kAllWeather[i];
    }
  }
  return current;
}

Weather pick_kind(JavaRandom& rng, Season season, GameMap const& map, HexCoord center) {
  double w[kWeatherCount]{};
  double sum = 0;
  for (int i = 0; i < kWeatherCount; ++i) {
    Weather v = kAllWeather[i];
    w[i] = season_weight(season, v) * terrain_kind_bias(v, map, center) *
           (0.9 + rng.next_double() * 0.25);
    sum += w[i];
  }
  double t = rng.next_double() * sum;
  double c = 0;
  for (int i = 0; i < kWeatherCount; ++i) {
    c += w[i];
    if (t <= c) {
      return kAllWeather[i];
    }
  }
  return Weather::CLEAR;
}

}  // namespace

WeatherBootstrap initial_systems(GameMap const& map, std::int64_t world_seed) {
  std::uint64_t const seed_u = mix64(static_cast<std::uint64_t>(world_seed), 0xC1A07E10ULL, 1);
  JavaRandom rng(static_cast<std::int64_t>(seed_u));
  auto land = map.passable_land();
  if (land.empty()) {
    return {{}, 1};
  }
  int count = 2 + rng.next_int_bounded(3);
  std::vector<WeatherSystem> list;
  int id = 1;
  for (int i = 0; i < count; ++i) {
    HexCoord c = land[static_cast<std::size_t>(rng.next_int_bounded(static_cast<int>(land.size())))];
    int rad = 1 + rng.next_int_bounded(7);
    Weather k = pick_kind(rng, Season::SPRING, map, c);
    list.push_back(WeatherSystem{id++, k, c.q, c.r, clamp_radius(map, rad)});
  }
  return {std::move(list), id};
}

WeatherTickResult regional_weather_tick(std::vector<WeatherSystem> const& prev,
                                        GameMap const& map, Season season,
                                        std::int64_t world_seed, int round, int weather_nonce,
                                        int next_id) {
  std::uint64_t const seed_u =
      mix64(static_cast<std::uint64_t>(world_seed), static_cast<std::uint64_t>(round),
            static_cast<std::uint64_t>(weather_nonce ^ 0x51DEAD));
  JavaRandom rng(static_cast<std::int64_t>(seed_u));
  auto land = map.passable_land();
  std::vector<WeatherSystem> out;

  for (WeatherSystem const& ws : prev) {
    if (rng.next_double() < 0.07) {
      continue;
    }
    HexCoord center = ws.center();
    Weather kind = ws.kind;
    int radius = ws.radius;

    if (rng.next_double() < 0.38) {
      kind = wander_kind(kind, season, rng, map, center);
    }
    if (rng.next_double() < 0.42 && !land.empty()) {
      auto arr = center.neighbors();
      std::vector<HexCoord> nbs(arr.begin(), arr.end());
      java_collections_shuffle(nbs, rng);
      for (HexCoord mv : nbs) {
        if (map.contains(mv) && passable(map.terrain_at(mv))) {
          center = mv;
          break;
        }
      }
    }
    if (rng.next_double() < 0.28) {
      radius += rng.next_bool() ? 1 : -1;
      radius = clamp_radius(map, radius);
    }

    out.push_back(WeatherSystem{ws.id, kind, center.q, center.r, radius});
  }

  int constexpr max_systems = 12;
  int nid = next_id;
  while (static_cast<int>(out.size()) < max_systems && !land.empty() && rng.next_double() < 0.52) {
    HexCoord c = land[static_cast<std::size_t>(rng.next_int_bounded(static_cast<int>(land.size())))];
    int rad = 1 + rng.next_int_bounded(8);
    Weather k = pick_kind(rng, season, map, c);
    out.push_back(WeatherSystem{nid++, k, c.q, c.r, clamp_radius(map, rad)});
    if (rng.next_double() < 0.38) {
      break;
    }
  }

  return {std::move(out), nid};
}

}  // namespace tack::strat

#include "tack/strat/snapshot_digest.hpp"

#include <algorithm>
#include <string>

namespace tack::strat {
namespace {

void fnv1a_append(std::uint64_t& h, char const* data, std::size_t len) noexcept {
  std::uint64_t constexpr prime = 1099511628211ULL;
  std::uint64_t constexpr offset = 14695981039346656037ULL;
  if (h == 0) {
    h = offset;
  }
  for (std::size_t i = 0; i < len; ++i) {
    h ^= static_cast<std::uint8_t>(data[i]);
    h *= prime;
  }
}

}  // namespace

std::uint64_t snapshot_stable_digest(GameSnapshot const& snap) {
  std::string canon;
  canon.reserve(256 + snap.map_cells.size() * 8);
  canon += std::to_string(snap.format_version);
  canon.push_back('|');
  canon += std::to_string(snap.world_seed);
  canon.push_back('|');
  canon += std::to_string(snap.round);
  canon.push_back('|');
  canon += std::to_string(snap.map_radius);
  canon.push_back('|');
  canon += std::to_string(snap.current_player_index);
  canon.push_back('|');
  canon += std::to_string(snap.units.size());
  canon.push_back('|');
  canon += std::to_string(snap.cities.size());
  canon.push_back('|');

  auto units = snap.units;
  std::sort(units.begin(), units.end(), [](auto const& a, auto const& b) { return a.id < b.id; });
  for (auto const& u : units) {
    canon += std::to_string(u.id);
    canon.push_back(',');
    canon += std::to_string(u.owner_seat);
    canon.push_back(',');
    canon += std::to_string(u.q);
    canon.push_back(',');
    canon += std::to_string(u.r);
    canon.push_back(',');
    canon += std::to_string(u.hp);
    canon.push_back(';');
  }

  auto cities = snap.cities;
  std::sort(cities.begin(), cities.end(), [](auto const& a, auto const& b) { return a.id < b.id; });
  for (auto const& c : cities) {
    canon += std::to_string(c.id);
    canon.push_back(',');
    canon += std::to_string(c.q);
    canon.push_back(',');
    canon += std::to_string(c.r);
    canon.push_back(',');
    canon += std::to_string(c.population.value_or(0));
    canon.push_back(';');
  }

  std::uint64_t h = 0;
  fnv1a_append(h, canon.data(), canon.size());
  return h;
}

}  // namespace tack::strat

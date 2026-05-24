#pragma once

#include <cmath>
#include <cstdint>
#include <optional>
#include <string_view>

namespace tack::strat {

/**
 * Hex map presets for {@link WorldGenerator}.
 *
 * Procedural maps use a **Civ VI–style odd-q rectangular** hex layout (see {@link map_civ_hex_dims});
 * {@link map_hex_radius} and {@link map_disk_hex_count_approx} remain as **reference disk** metrics for
 * sizing and tests (approximate Civ VI tile counts).
 */
enum class MapSizePreset : std::uint8_t { Tiny, Small, Medium, Large };

/** Axial hex disk radius (cells are those with distance from origin ≤ R in axial coords). */
[[nodiscard]] constexpr int map_hex_radius(MapSizePreset p) noexcept {
  switch (p) {
    case MapSizePreset::Tiny:
      return 16;
    case MapSizePreset::Small:
      return 27;
    case MapSizePreset::Medium:
      return 39;
    case MapSizePreset::Large:
      return 48;
  }
  return 16;
}

/** Cell count for a filled hex disk of radius R: 3·R·(R+1) + 1. */
[[nodiscard]] constexpr int map_disk_hex_count_approx(MapSizePreset p) noexcept {
  int const r = map_hex_radius(p);
  return 3 * r * (r + 1) + 1;
}

/** Odd-q rectangle used for procedural maps (Civ VI–style layout, approximate disk tile counts). */
struct MapCivHexDims {
  int cols{};
  int rows{};
};

[[nodiscard]] inline MapCivHexDims map_civ_hex_dims(MapSizePreset p) noexcept {
  int const target = map_disk_hex_count_approx(p);
  double const aspect = 1.52;
  int cols = static_cast<int>(std::ceil(std::sqrt(static_cast<double>(target) * aspect)));
  cols = std::max(cols, 12);
  int rows = (target + cols - 1) / cols;
  rows = std::max(rows, 10);
  while (cols * rows < target) {
    ++rows;
  }
  return {cols, rows};
}

[[nodiscard]] constexpr char const* map_size_label(MapSizePreset p) noexcept {
  switch (p) {
    case MapSizePreset::Tiny:
      return "tiny";
    case MapSizePreset::Small:
      return "small";
    case MapSizePreset::Medium:
      return "medium";
    case MapSizePreset::Large:
      return "large";
  }
  return "tiny";
}

/** Parse CLI / config token; returns nullopt if unknown. */
[[nodiscard]] std::optional<MapSizePreset> try_parse_map_size_preset(std::string_view s) noexcept;

}  // namespace tack::strat

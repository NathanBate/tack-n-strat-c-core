#pragma once

#include <array>
#include <cstddef>
#include <functional>
#include <vector>

namespace tack::strat {

struct HexCoord {
  int q{};
  int r{};

  constexpr auto operator<=>(HexCoord const&) const noexcept = default;

  [[nodiscard]] std::array<HexCoord, 6> neighbors() const;
  [[nodiscard]] int distance_to(HexCoord o) const noexcept;
  [[nodiscard]] HexCoord add(HexCoord d) const noexcept;

  [[nodiscard]] static std::vector<HexCoord> disk(int radius);
  /** Odd-q vertical layout: cols×rows offset grid converted to axial, centered near (0,0). */
  [[nodiscard]] static std::vector<HexCoord> odd_q_rectangle(int cols, int rows);
};

}  // namespace tack::strat

template <>
struct std::hash<tack::strat::HexCoord> {
  std::size_t operator()(tack::strat::HexCoord const& h) const noexcept {
    auto const u = (static_cast<unsigned long long>(static_cast<unsigned>(h.q)) << 32U) ^
                   static_cast<unsigned>(h.r);
    return std::hash<unsigned long long>{}(u);
  }
};

#pragma once

#include "tack/strat/hex.hpp"
#include "tack/strat/weather.hpp"

namespace tack::strat {

struct WeatherSystem {
  int id{};
  Weather kind{};
  int center_q{};
  int center_r{};
  int radius{};

  [[nodiscard]] HexCoord center() const noexcept {
    return HexCoord{center_q, center_r};
  }

  [[nodiscard]] bool covers(HexCoord h) const noexcept {
    return center().distance_to(h) <= radius;
  }
};

}  // namespace tack::strat

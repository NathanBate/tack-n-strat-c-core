#pragma once

#include <algorithm>
#include <string>

namespace tack::strat {

struct Chronology {
  static constexpr int START_YEAR_BCE = 4000;
  static constexpr int DEFAULT_YEARS_PER_FULL_ROUND = 5;

  Chronology() = delete;

  [[nodiscard]] static std::string format_era(int years_elapsed) {
    int y = years_elapsed;
    if (y < 0) {
      y = 0;
    }
    int bc_remaining = START_YEAR_BCE - y;
    if (bc_remaining > 0) {
      return std::to_string(bc_remaining) + " BCE";
    }
    int ce = years_elapsed - START_YEAR_BCE + 1;
    return std::to_string(ce) + " CE";
  }

  [[nodiscard]] static int season_stride_years(int years_per_full_round) noexcept {
    int ypr = std::max(1, years_per_full_round);
    return std::max(1, (ypr + 3) / 4);
  }

  [[nodiscard]] static int season_index_from_elapsed_years(int years_elapsed,
                                                           int years_per_full_round) noexcept {
    int stride = season_stride_years(years_per_full_round);
    int y = std::max(0, years_elapsed);
    return (y / stride) % 4;
  }
};

}  // namespace tack::strat

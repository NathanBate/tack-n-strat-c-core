#pragma once

#include "tack/strat/chronology.hpp"

namespace tack::strat {

enum class Season { SPRING, SUMMER, AUTUMN, WINTER };

[[nodiscard]] constexpr Season season_from_ordinal(int ordinal) noexcept {
  Season const values[] = {Season::SPRING, Season::SUMMER, Season::AUTUMN, Season::WINTER};
  int const i = ordinal % 4;
  int const idx = i >= 0 ? i : i + 4;
  return values[idx];
}

[[nodiscard]] constexpr Season season_from_elapsed_years(int years_elapsed,
                                                         int years_per_full_round) noexcept {
  int const idx = Chronology::season_index_from_elapsed_years(years_elapsed, years_per_full_round);
  return season_from_ordinal(idx);
}

}  // namespace tack::strat

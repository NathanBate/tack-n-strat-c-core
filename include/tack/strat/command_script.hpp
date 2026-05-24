#pragma once

#include <string_view>
#include <vector>

#include "tack/strat/text_play.hpp"

namespace tack::strat {

/** Replay scripted text commands through {@link TextPlayDriver} (integration / regression helper). */
inline TextPlayOutcome replay_command_lines(TextPlayDriver& driver,
                                            std::vector<std::string_view> const& lines) {
  TextPlayOutcome last{};
  for (std::string_view line : lines) {
    last = driver.handle_line(line);
    if (!last.ok) {
      break;
    }
  }
  return last;
}

}  // namespace tack::strat

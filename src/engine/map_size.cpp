#include "tack/strat/map_size.hpp"

#include <algorithm>
#include <cctype>
#include <string>

namespace tack::strat {

std::optional<MapSizePreset> try_parse_map_size_preset(std::string_view s) noexcept {
  std::string t;
  t.reserve(s.size());
  for (char c : s) {
    t.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
  }
  if (t == "tiny" || t == "micro" || t == "demo" || t == "java") {
    return MapSizePreset::Tiny;
  }
  if (t == "small" || t == "s" || t == "sm") {
    return MapSizePreset::Small;
  }
  if (t == "medium" || t == "m" || t == "med") {
    return MapSizePreset::Medium;
  }
  if (t == "large" || t == "l" || t == "lg") {
    return MapSizePreset::Large;
  }
  return std::nullopt;
}

}  // namespace tack::strat

#include "tack/strat/city_name_pool.hpp"

#include "tack/strat/java_random.hpp"

namespace tack::strat {

namespace {

char const* const PREFIXES[] = {
    "Ash", "Bel", "Cor", "Dun", "Elm", "Far", "Gar", "Haw", "Ill", "Jar", "Ken", "Lyn", "Mor",
    "Nor", "Oak", "Pet", "Quo", "Riv", "Sol", "Tal", "Ulv", "Ven", "Wyn", "Yew", "Zim", "Ald",
    "Bex", "Cray", "Dorn", "Ett", "Fell", "Gorn", "Hel", "Ith", "Jex", "Keth", "Lorn", "Mire",
    "Nock", "Oren", "Pell", "Rill", "Sorn", "Teth", "Usk", "Vorn", "Weth", "Yorn", "Zeth", "Bran"};

char const* const SUFFIXES[] = {"ford",   "brook",  "haven",  "moor",   "wick",   "dale",
                                "burgh",  "ton",    "caster", "mouth",  "ridge",  "stead",
                                "bury",   "shire",  "land",   "fall",   "mere",   "well",
                                "gate",   "thorpe", "ham",    "ley",    "stone",  "bridge",
                                "wood",   "field",  "cross",  "mill",   "worth",  "borough",
                                "vale",   "crest",  "holm",   "pool",   "bay",    "harbor",
                                "reach",  "glen",   "fort",   "mont"};

static_assert(sizeof(PREFIXES) / sizeof(PREFIXES[0]) == 50);
static_assert(sizeof(SUFFIXES) / sizeof(SUFFIXES[0]) == 40);

}  // namespace

std::vector<std::string> CityNamePool::full_deck_grid_order() {
  std::vector<std::string> out;
  out.reserve(UNIQUE_COUNT);
  for (char const* p : PREFIXES) {
    for (char const* s : SUFFIXES) {
      out.emplace_back(std::string(p) + s);
    }
  }
  return out;
}

std::vector<std::string> CityNamePool::shuffled_deck(std::int64_t world_seed) {
  auto copy = full_deck_grid_order();
  JavaRandom rng(world_seed ^ 0xC1749E5FL);
  java_collections_shuffle(copy, rng);
  return copy;
}

}  // namespace tack::strat

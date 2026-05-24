#include <iostream>
#include <string>
#include <string_view>
#include <vector>

#include "tack/strat/chronology.hpp"
#include "tack/strat/game_session.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/text_play.hpp"

namespace {

void print_demo_banner() {
  std::cout << "tack_strat — rules engine (terminal)\n";
  std::cout << "calendar start label: " << tack::strat::Chronology::format_era(0) << "\n";

  std::vector<tack::strat::Player> players{tack::strat::Player{0, "Solo"}};
  tack::strat::GameSession session(std::move(players), 77'007LL);
  std::cout << session.weather_hud_summary() << "\n";
  std::cout << "units: " << session.units().size() << "\n";
  std::cout << "Play interactively: tack_strat play [...] "
                 "[tiny|small|medium|large] (default: medium ≈ Civ VI Standard-scale disk)\n";
  std::cout << "SDL map (Java-style): tack_strat_gui [seed] [humans] [cpus] [tiny|small|medium|large]\n";
}

[[nodiscard]] int run_play_mode(int argc, char** argv) {
  std::int64_t seed = 77'007;
  int human_players = 1;
  int computer_players = 0;
  tack::strat::MapSizePreset map_size = tack::strat::MapSizePreset::Medium;

  try {
    if (argc >= 3) {
      seed = std::stoll(argv[2]);
    }
    if (argc >= 4) {
      human_players = std::stoi(argv[3]);
    }
    if (argc >= 5) {
      computer_players = std::stoi(argv[4]);
    }
    if (argc >= 6) {
      auto parsed = tack::strat::try_parse_map_size_preset(argv[5]);
      if (!parsed.has_value()) {
        std::cerr << "Invalid map size \"" << argv[5]
                  << "\" (use tiny, small, medium, or large).\n";
        return 2;
      }
      map_size = *parsed;
    }
  } catch (std::exception const&) {
    std::cerr << "Invalid numeric argument(s).\n"
                 "Usage: tack_strat play [world_seed] [human_players] [computer_players] "
                 "[tiny|small|medium|large]\n"
                 "  Seats 0..H-1 are human (hot-seat); seats H..H+C-1 are computer (auto end turn).\n"
                 "  Require 1 ≤ H+C ≤ 4, each of H and C between 0 and 4.\n";
    return 2;
  }

  int const total = human_players + computer_players;
  if (human_players < 0 || computer_players < 0 || human_players > 4 || computer_players > 4 ||
      total < 1 || total > 4) {
    std::cerr << "Need 1–4 players total; human_players and computer_players each 0–4.\n";
    return 2;
  }

  std::vector<tack::strat::Player> players;
  players.reserve(static_cast<std::size_t>(total));
  for (int i = 0; i < total; ++i) {
    bool const computer = i >= human_players;
    players.emplace_back(i, "P" + std::to_string(i), computer);
  }

  tack::strat::GameSession session(std::move(players), seed, map_size);
  std::cout << "world_seed=" << session.world_seed() << " players=" << total
            << " (humans=" << human_players << " computers=" << computer_players << ") map="
            << tack::strat::map_size_label(map_size)
            << " radius=" << session.map().radius() << "\n";
  tack::strat::TextPlayDriver driver(std::move(session));
  driver.run_repl(std::cin, std::cout);
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc >= 2 && std::string_view(argv[1]) == "play") {
    return run_play_mode(argc, argv);
  }

  print_demo_banner();
  return 0;
}

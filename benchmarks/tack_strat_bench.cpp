#include <chrono>
#include <iostream>

#include "tack/strat/game_session.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/world_generator.hpp"

int main() {
  using tack::strat::GameSession;
  using tack::strat::MapSizePreset;
  using tack::strat::Player;
  using tack::strat::WorldGenerator;

  std::vector<Player> players{Player{0, "Bench"}};
  auto const t0 = std::chrono::steady_clock::now();
  // Swap to Medium/Large for Civ VI-scale timing; Tiny stays responsive by default.
  GameSession session(std::move(players), 42'424LL, MapSizePreset::Tiny);
  for (int i = 0; i < 25; ++i) {
    static_cast<void>(session.end_turn());
  }
  auto const t1 = std::chrono::steady_clock::now();
  auto const ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
  std::cout << "bench: 25 end_turn tiny map units=" << session.units().size()
            << " radius=" << session.map().radius() << " ms=" << ms << "\n";
  std::cout << "WorldGenerator::MAP_RADIUS (legacy tiny/java)=" << WorldGenerator::MAP_RADIUS << "\n";
  return 0;
}

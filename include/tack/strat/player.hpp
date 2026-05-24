#pragma once

#include <stdexcept>
#include <string>

namespace tack::strat {

struct Player {
  int seat{};
  std::string name{};
  bool computer{};

  Player(int seat_param, std::string name_param, bool computer_param = false)
      : seat(seat_param), name(std::move(name_param)), computer(computer_param) {
    if (seat < 0 || seat > 3) {
      throw std::invalid_argument("seat must be 0..3");
    }
    if (name.empty()) {
      throw std::invalid_argument("name must be non-blank");
    }
  }

  friend bool operator==(Player const& a, Player const& b) noexcept {
    return a.seat == b.seat && a.name == b.name && a.computer == b.computer;
  }
};

}  // namespace tack::strat

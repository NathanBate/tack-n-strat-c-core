#pragma once

#include <istream>
#include <ostream>
#include <string>
#include <string_view>

#include "tack/strat/game_session.hpp"

namespace tack::strat {

/** Result of executing one text command against a {@link GameSession}. */
struct TextPlayOutcome {
  bool ok{};
  /** When true, interactive {@link TextPlayDriver::run_repl} should exit. */
  bool exit_repl{};
  std::string message;
};

/**
 * Text command adapter around {@link GameSession}. {@link #run_repl} is the interactive game loop
 * (read line → apply rules → print); tests call {@link #handle_line} with scripted commands.
 */
class TextPlayDriver {
 public:
  explicit TextPlayDriver(GameSession session);

  GameSession& session() noexcept {
    return session_;
  }
  GameSession const& session() const noexcept {
    return session_;
  }

  TextPlayOutcome handle_line(std::string_view line);

  /** Reads lines until quit/exit or EOF. Computer-controlled players auto-pass (call end_turn) before each prompt. */
  void run_repl(std::istream& in, std::ostream& out);

 private:
  GameSession session_;
};

void print_text_play_help(std::ostream& out);

}  // namespace tack::strat

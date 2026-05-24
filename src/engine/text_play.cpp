#include "tack/strat/text_play.hpp"

#include <charconv>
#include <cctype>
#include <sstream>
#include <string>
#include <vector>

#include "tack/strat/season.hpp"
#include "tack/strat/terrain.hpp"
#include "tack/strat/seat_ai.hpp"
#include "tack/strat/unit_kind.hpp"
#include "tack/strat/wild_animal.hpp"

namespace tack::strat {
namespace {

[[nodiscard]] std::string_view trim(std::string_view s) {
  while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) {
    s.remove_prefix(1);
  }
  while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) {
    s.remove_suffix(1);
  }
  return s;
}

[[nodiscard]] std::string to_lower_ascii(std::string s) {
  for (char& c : s) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return s;
}

[[nodiscard]] bool parse_i32_strict(std::string const& s, int& out) {
  int v{};
  char const* begin = s.data();
  char const* end = begin + s.size();
  auto res = std::from_chars(begin, end, v);
  if (res.ec != std::errc{} || res.ptr != end) {
    return false;
  }
  out = v;
  return true;
}

[[nodiscard]] std::vector<std::string> split_tokens(std::string_view line) {
  std::vector<std::string> out;
  std::size_t i = 0;
  while (i < line.size()) {
    while (i < line.size() && std::isspace(static_cast<unsigned char>(line[i]))) {
      ++i;
    }
    if (i >= line.size()) {
      break;
    }
    std::size_t j = i;
    while (j < line.size() && !std::isspace(static_cast<unsigned char>(line[j]))) {
      ++j;
    }
    out.emplace_back(line.substr(i, j - i));
    i = j;
  }
  return out;
}

[[nodiscard]] char terrain_abbrev(Terrain t) noexcept {
  switch (t) {
    case Terrain::WATER:
      return '~';
    case Terrain::GRASS:
      return 'g';
    case Terrain::PLAINS:
      return 'p';
    case Terrain::DESERT:
      return 'd';
    case Terrain::HILL:
      return 'h';
    case Terrain::FOREST:
      return 'f';
    case Terrain::MOUNTAIN:
      return '^';
  }
  return '?';
}

[[nodiscard]] char const* season_name(Season s) noexcept {
  switch (s) {
    case Season::SPRING:
      return "Spring";
    case Season::SUMMER:
      return "Summer";
    case Season::AUTUMN:
      return "Autumn";
    case Season::WINTER:
      return "Winter";
  }
  return "?";
}

[[nodiscard]] std::string format_units(GameSession const& g) {
  std::ostringstream o;
  if (g.units().empty()) {
    return "(no units)\n";
  }
  for (Unit const& u : g.units()) {
    HexCoord c = u.coord();
    o << "id=" << u.id() << " seat=" << u.owner_seat() << " " << unit_kind_display_name(u.kind())
      << " @(" << c.q << ',' << c.r << ") hp=" << u.hp() << '/' << u.max_hp()
      << " moves=" << u.moves_remaining() << '\n';
  }
  return o.str();
}

[[nodiscard]] std::string format_cities(GameSession const& g) {
  std::ostringstream o;
  if (g.cities().empty()) {
    return "(no cities)\n";
  }
  for (City const& c : g.cities()) {
    HexCoord h = c.coord();
    o << "id=" << c.id() << " seat=" << c.owner_seat() << " \"" << c.name() << "\" @(" << h.q << ','
      << h.r << ") pop=" << c.population() << " hp=" << c.hp() << '/' << c.max_hp() << '\n';
  }
  return o.str();
}

[[nodiscard]] std::string format_wildlife(GameSession const& g) {
  std::ostringstream o;
  auto list = g.wildlife_list();
  if (list.empty()) {
    return "(no wildlife)\n";
  }
  for (WildAnimal const& w : list) {
    if (w.is_dead()) {
      continue;
    }
    HexCoord c = w.coord();
    o << "id=" << w.id() << ' ' << wild_animal_kind_label(w.kind()) << " @(" << c.q << ',' << c.r
      << ") hp=" << w.hp() << '\n';
  }
  return o.str();
}

[[nodiscard]] std::string format_map(GameSession const& g) {
  std::ostringstream o;
  o << "Hex (q,r) terrain\n";
  for (HexCoord const c : g.map().all_cells()) {
    Terrain t = g.terrain_effective_at(c);
    o << '(' << c.q << ',' << c.r << ") " << terrain_abbrev(t) << '\n';
  }
  return o.str();
}

[[nodiscard]] std::string format_status(GameSession const& g) {
  std::ostringstream o;
  if (g.is_over()) {
    if (g.winner_seat().has_value()) {
      o << "Game over — winner seat " << *g.winner_seat() << '\n';
    } else {
      o << "Game over — no winner recorded\n";
    }
  } else {
    o << "Round " << g.round() << ", calendar " << g.calendar_era_label() << ", "
      << season_name(g.season()) << '\n';
    o << g.weather_hud_summary() << '\n';
    o << "Current player: seat " << g.current_player().seat << " (" << g.current_player().name
      << ")\n";
  }
  for (Player const& p : g.players()) {
    o << "  seat " << p.seat << (p.computer ? " computer" : " human") << " gold=" << g.gold_for(p.seat)
      << " cities=" << g.city_count_for(p.seat) << " units=" << g.unit_count_for(p.seat) << '\n';
  }
  return o.str();
}

TextPlayOutcome outcome_ok(std::string msg = {}) {
  return TextPlayOutcome{.ok = true, .exit_repl = false, .message = std::move(msg)};
}

TextPlayOutcome outcome_fail(std::string msg) {
  return TextPlayOutcome{.ok = false, .exit_repl = false, .message = std::move(msg)};
}

TextPlayOutcome outcome_quit(std::string msg = {}) {
  return TextPlayOutcome{.ok = true, .exit_repl = true, .message = std::move(msg)};
}

}  // namespace

void print_text_play_help(std::ostream& out) {
  out << "Commands:\n"
         "  help              Show this list\n"
         "  status            Round, calendar, season, weather, players, gold\n"
         "  units             List units (id, owner, kind, hex, hp, moves)\n"
         "  cities            List cities\n"
         "  wildlife          List wild animals\n"
         "  map               Terrain per hex (abbrev: ~gpdh f ^)\n"
         "  move ID q r       Move current player's unit one adjacent step\n"
         "  attack ID q r     Attack adjacent enemy unit standing at hex (q,r)\n"
         "  found ID          Found a city with the settler (current player)\n"
         "  end               End turn (advance hot-seat / world)\n"
         "  quit | exit       Leave the REPL\n"
         "\n"
         "Computer seats run SeatAi ticks (Java parity), then end turn, before each prompt.\n";
}

TextPlayDriver::TextPlayDriver(GameSession session) : session_(std::move(session)) {}

TextPlayOutcome TextPlayDriver::handle_line(std::string_view line) {
  line = trim(line);
  if (line.empty() || line.front() == '#') {
    return outcome_ok();
  }

  std::vector<std::string> tok = split_tokens(line);
  std::string cmd = to_lower_ascii(tok[0]);

  if (cmd == "quit" || cmd == "exit") {
    return outcome_quit("Goodbye.\n");
  }
  if (cmd == "help" || cmd == "?") {
    std::ostringstream o;
    print_text_play_help(o);
    return outcome_ok(o.str());
  }
  if (cmd == "status") {
    return outcome_ok(format_status(session_));
  }
  if (cmd == "units") {
    return outcome_ok(format_units(session_));
  }
  if (cmd == "cities") {
    return outcome_ok(format_cities(session_));
  }
  if (cmd == "wildlife" || cmd == "wild") {
    return outcome_ok(format_wildlife(session_));
  }
  if (cmd == "map") {
    return outcome_ok(format_map(session_));
  }

  if (cmd == "end") {
    if (session_.is_over()) {
      return outcome_ok("Turn skipped — game already ended.\n");
    }
    if (!session_.end_turn()) {
      return outcome_ok(
          "Turn not advanced: a unit still has moves on its queued path (finish/clear route "
          "first).\n");
    }
    std::ostringstream o;
    o << "Turn ended.";
    if (session_.is_over()) {
      o << " Game over.";
      if (session_.winner_seat().has_value()) {
        o << " Winner seat " << *session_.winner_seat() << '.';
      }
    }
    o << '\n';
    return outcome_ok(o.str());
  }

  if (cmd == "move") {
    if (tok.size() != 4) {
      return outcome_fail("usage: move <unit_id> <q> <r>\n");
    }
    int uid{};
    int q{};
    int r{};
    if (!parse_i32_strict(tok[1], uid) || !parse_i32_strict(tok[2], q) || !parse_i32_strict(tok[3], r)) {
      return outcome_fail("move: unit_id, q, r must be integers\n");
    }
    if (session_.is_over()) {
      return outcome_fail("move: game over\n");
    }
    if (session_.try_move_unit(uid, HexCoord{q, r})) {
      return outcome_ok("Moved.\n");
    }
    return outcome_fail(
        "Move failed (not current player, not adjacent, blocked terrain, no moves, occupied, "
        "sleeping, etc.).\n");
  }

  if (cmd == "attack") {
    if (tok.size() != 4) {
      return outcome_fail("usage: attack <unit_id> <target_q> <target_r>\n");
    }
    int uid{};
    int q{};
    int r{};
    if (!parse_i32_strict(tok[1], uid) || !parse_i32_strict(tok[2], q) || !parse_i32_strict(tok[3], r)) {
      return outcome_fail("attack: unit_id, q, r must be integers\n");
    }
    if (session_.is_over()) {
      return outcome_fail("attack: game over\n");
    }
    auto msg = session_.try_attack(uid, HexCoord{q, r});
    if (!msg.has_value()) {
      return outcome_fail(
          "Attack failed (not current player, not adjacent enemy, no moves, zero strength).\n");
    }
    return outcome_ok(*msg + "\n");
  }

  if (cmd == "found") {
    if (tok.size() != 2) {
      return outcome_fail("usage: found <settler_unit_id>\n");
    }
    int uid{};
    if (!parse_i32_strict(tok[1], uid)) {
      return outcome_fail("found: unit_id must be an integer\n");
    }
    if (session_.is_over()) {
      return outcome_fail("found: game over\n");
    }
    auto u = session_.unit_by_id(uid);
    if (!u.has_value()) {
      return outcome_fail("found: no such unit\n");
    }
    auto city = session_.found_city(uid);
    if (!city.has_value()) {
      auto why = session_.explain_cannot_found_city(*u);
      return outcome_fail(why.has_value() ? (*why + "\n")
                                          : std::string("Cannot found city.\n"));
    }
    std::ostringstream o;
    o << "Founded city \"" << city->name() << "\" id=" << city->id() << ".\n";
    return outcome_ok(o.str());
  }

  return outcome_fail("Unknown command \"" + tok[0] + "\" (type help).\n");
}

void TextPlayDriver::run_repl(std::istream& in, std::ostream& out) {
  out << "tack_strat text play — type 'help' for commands.\n";
  std::string line;
  for (;;) {
    while (!session_.is_over() && session_.current_player().computer) {
      JavaRandom rng = seat_ai_rng(session_);
      while (!session_.is_over() && session_.current_player().computer) {
        if (seat_ai_tick(session_, rng)) {
          continue;
        }
        int const seat = session_.current_player().seat;
        if (session_.end_turn()) {
          out << "(computer) seat " << seat << " ends turn.\n";
        }
        break;
      }
    }

    out << "> " << std::flush;
    if (!std::getline(in, line)) {
      break;
    }

    TextPlayOutcome o = handle_line(line);
    if (!o.message.empty()) {
      out << o.message;
      if (o.message.back() != '\n') {
        out << '\n';
      }
    }
    if (o.exit_repl) {
      break;
    }
  }
}

}  // namespace tack::strat

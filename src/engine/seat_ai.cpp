#include "tack/strat/seat_ai.hpp"

#include <algorithm>
#include <limits>
#include <optional>
#include <vector>

#include "tack/strat/game_session.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/unit_kind.hpp"

namespace tack::strat {
namespace {

[[nodiscard]] bool hex_vec_contains(std::vector<HexCoord> const& v, HexCoord n) {
  return std::find(v.begin(), v.end(), n) != v.end();
}

[[nodiscard]] bool clear_stuck_auto_explore(GameSession& session, int seat) {
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != seat || !u.auto_explore() || u.sleeping() || u.moves_remaining() <= 0) {
      continue;
    }
    if (session.legal_moves(u).empty()) {
      session.set_unit_auto_explore(u.id(), false);
      return true;
    }
  }
  return false;
}

[[nodiscard]] bool try_found_city(GameSession& session) {
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != session.current_player().seat) {
      continue;
    }
    if (u.kind() != UnitKind::SETTLER) {
      continue;
    }
    if (!session.can_found_city(u)) {
      continue;
    }
    return session.found_city(u.id()).has_value();
  }
  return false;
}

[[nodiscard]] bool pick_city_production(GameSession& session, int seat, JavaRandom& rng) {
  std::vector<int> mine_ids;
  for (City const& c : session.cities()) {
    if (c.owner_seat() == seat) {
      mine_ids.push_back(c.id());
    }
  }
  std::sort(mine_ids.begin(), mine_ids.end());
  for (int cid : mine_ids) {
    auto co = session.city_by_id(cid);
    if (!co || co->current_build().has_value()) {
      continue;
    }
    UnitKind pick =
        session.city_count_for(seat) <= 1 && session.round() <= 6 ? UnitKind::SCOUT : UnitKind::WARRIOR;
    if (rng.next_double() < 0.22 && session.round() > 8) {
      pick = UnitKind::SETTLER;
    }
    return session.set_city_production(cid, pick);
  }
  return false;
}

[[nodiscard]] bool try_adjacent_attacks(GameSession& session, int seat) {
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != seat || u.moves_remaining() <= 0) {
      continue;
    }
    if (unit_attack_strength(u.kind()) <= 0) {
      continue;
    }
    std::vector<HexCoord> const legal_atk = session.legal_attacks(u);
    for (HexCoord n : u.coord().neighbors()) {
      auto foe = session.unit_at(n);
      if (foe && foe->owner_seat() != seat && hex_vec_contains(legal_atk, n)) {
        return session.try_attack(u.id(), n).has_value();
      }
    }
  }
  return false;
}

[[nodiscard]] bool follow_any_queued_route(GameSession& session, int seat) {
  std::vector<int> mine_ids;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat && !session.planned_route_for(u.id()).empty()) {
      mine_ids.push_back(u.id());
    }
  }
  std::sort(mine_ids.begin(), mine_ids.end());
  for (int uid : mine_ids) {
    if (session.follow_planned_route(uid)) {
      return true;
    }
  }
  return false;
}

[[nodiscard]] std::optional<HexCoord> nearest_enemy_from(GameSession const& session, int my_seat,
                                                         HexCoord from) {
  std::optional<HexCoord> best;
  int bd = std::numeric_limits<int>::max();
  for (City const& c : session.cities()) {
    if (c.owner_seat() == my_seat) {
      continue;
    }
    int d = from.distance_to(c.coord());
    if (d < bd) {
      bd = d;
      best = c.coord();
    }
  }
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == my_seat) {
      continue;
    }
    int d = from.distance_to(u.coord());
    if (d < bd) {
      bd = d;
      best = u.coord();
    }
  }
  return best;
}

[[nodiscard]] HexCoord pick_step_toward(std::vector<HexCoord> const& legal,
                                        std::optional<HexCoord> enemy_focus, JavaRandom& rng) {
  if (!enemy_focus.has_value()) {
    return legal[static_cast<std::size_t>(rng.next_int_bounded(static_cast<int>(legal.size())))];
  }
  HexCoord pick = legal.front();
  int best = std::numeric_limits<int>::max();
  for (HexCoord n : legal) {
    int d = n.distance_to(*enemy_focus);
    if (d < best || (d == best && rng.next_bool())) {
      best = d;
      pick = n;
    }
  }
  return pick;
}

[[nodiscard]] bool move_one_unit(GameSession& session, int seat, JavaRandom& rng) {
  std::vector<int> mover_ids;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat && u.moves_remaining() > 0 && !u.auto_explore() && !u.sleeping()) {
      mover_ids.push_back(u.id());
    }
  }
  std::sort(mover_ids.begin(), mover_ids.end());
  for (int uid : mover_ids) {
    auto uo = session.unit_by_id(uid);
    if (!uo) {
      continue;
    }
    Unit const& u = *uo;
    std::vector<HexCoord> legal = session.legal_moves(u);
    if (legal.empty()) {
      continue;
    }
    std::optional<HexCoord> focus = nearest_enemy_from(session, seat, u.coord());
    HexCoord dest = pick_step_toward(legal, focus, rng);
    if (session.try_move_unit(uid, dest)) {
      return true;
    }
  }
  return false;
}

}  // namespace

JavaRandom seat_ai_rng(GameSession const& session) {
  std::int64_t const salt =
      session.world_seed() ^ (static_cast<std::int64_t>(session.round()) << 16) ^
      (static_cast<std::int64_t>(session.current_player_index()) << 24);
  return JavaRandom(salt);
}

bool seat_ai_tick(GameSession& session, JavaRandom& rng) {
  if (session.is_over()) {
    return false;
  }
  if (!session.current_player().computer) {
    return false;
  }
  int const seat = session.current_player().seat;

  if (clear_stuck_auto_explore(session, seat)) {
    return true;
  }
  if (try_found_city(session)) {
    return true;
  }
  if (pick_city_production(session, seat, rng)) {
    return true;
  }
  if (try_adjacent_attacks(session, seat)) {
    return true;
  }
  if (follow_any_queued_route(session, seat)) {
    return true;
  }
  return move_one_unit(session, seat, rng);
}

}  // namespace tack::strat

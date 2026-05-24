#include "tack/strat/net/turn_sync.hpp"

#include <sstream>
#include <utility>

namespace tack::strat::net {

TurnSyncPoller::TurnSyncPoller(TurnSyncClientConfig config, ITurnSyncTransport& transport)
    : config_(std::move(config)), transport_(&transport) {}

PollStateResult TurnSyncPoller::poll_game_state_if_due(std::string_view game_id) {
  auto const now = std::chrono::steady_clock::now();
  if (last_poll_ != std::chrono::steady_clock::time_point{}) {
    auto const elapsed = now - last_poll_;
    if (elapsed < std::chrono::seconds(config_.poll_interval_seconds)) {
      return PollStateResult{.transport_ok = false,
                           .http_status = 0,
                           .error_message = "poll_interval_seconds not elapsed",
                           .response_body = {}};
    }
  }
  last_poll_ = now;

  std::ostringstream path;
  path << "/games/" << game_id << "/state";
  std::string const path_str = path.str();
  return transport_->http_get(path_str);
}

SubmitCommandResult TurnSyncPoller::submit_command(std::string_view game_id,
                                                   std::string_view json_body) {
  std::ostringstream path;
  path << "/games/" << game_id << "/commands";
  std::string const path_str = path.str();
  return transport_->http_post(path_str, "application/json", json_body);
}

void TurnSyncPoller::reset_poll_clock_for_tests() {
  last_poll_ = {};
}

}  // namespace tack::strat::net

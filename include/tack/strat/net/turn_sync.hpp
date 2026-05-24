#pragma once

#include <chrono>
#include <string>
#include <string_view>

namespace tack::strat::net {

/**
 * Laravel-oriented REST shape (implement {@link ITurnSyncTransport} with HTTP later).
 *
 * Poll roughly every {@link TurnSyncClientConfig::poll_interval_seconds} (e.g. 5).
 *
 * Suggested routes (relative to {@link TurnSyncClientConfig::api_base_url}):
 * - GET  /games/{game_id}/state
 * - POST /games/{game_id}/commands  (JSON body: seat + command line)
 */
struct TurnSyncClientConfig {
  std::string api_base_url;
  int poll_interval_seconds = 5;
};

struct PollStateResult {
  bool transport_ok{};
  int http_status{};
  std::string error_message;
  /** Raw JSON from the server; parsing stays outside core until schema is fixed. */
  std::string response_body;
};

struct SubmitCommandResult {
  bool transport_ok{};
  int http_status{};
  std::string error_message;
  std::string response_body;
};

class ITurnSyncTransport {
 public:
  virtual ~ITurnSyncTransport() = default;

  /** GET request; {@code path} begins with '/' (e.g. /games/abc/state). */
  virtual PollStateResult http_get(std::string_view path) = 0;

  /** POST request with raw body (caller sets JSON). */
  virtual SubmitCommandResult http_post(std::string_view path, std::string_view content_type,
                                       std::string_view body) = 0;
};

/** Returns preset responses for tests and offline tooling. */
class StubTurnSyncTransport final : public ITurnSyncTransport {
 public:
  PollStateResult next_poll{};
  SubmitCommandResult next_submit{};

  PollStateResult http_get(std::string_view /*path*/) override {
    return next_poll;
  }

  SubmitCommandResult http_post(std::string_view /*path*/, std::string_view /*content_type*/,
                                std::string_view /*body*/) override {
    return next_submit;
  }
};

/** Throttles polls to {@link TurnSyncClientConfig::poll_interval_seconds}. */
class TurnSyncPoller {
 public:
  TurnSyncPoller(TurnSyncClientConfig config, ITurnSyncTransport& transport);

  /** First call always polls; later calls no-op until interval elapsed (returns transport_ok=false). */
  [[nodiscard]] PollStateResult poll_game_state_if_due(std::string_view game_id);

  [[nodiscard]] SubmitCommandResult submit_command(std::string_view game_id, std::string_view json_body);

  /** Test hook: clears throttle so the next poll runs immediately. */
  void reset_poll_clock_for_tests();

 private:
  TurnSyncClientConfig config_;
  ITurnSyncTransport* transport_{};
  std::chrono::steady_clock::time_point last_poll_{};
};

}  // namespace tack::strat::net

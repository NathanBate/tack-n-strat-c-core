#pragma once

#include <optional>
#include <string>

namespace tack::strat {

struct GameSnapshot;

[[nodiscard]] std::string snapshot_to_json_string(GameSnapshot const& snap);
[[nodiscard]] std::optional<GameSnapshot> snapshot_from_json_string(std::string_view text);

/** Writes UTF-8 JSON (pretty-printed). Returns false on I/O error. */
bool snapshot_write_json_file(GameSnapshot const& snap, std::string const& path_utf8);

/** Reads UTF-8 JSON. Returns nullopt on parse / structural errors. */
[[nodiscard]] std::optional<GameSnapshot> snapshot_read_json_file(std::string const& path_utf8);

}  // namespace tack::strat

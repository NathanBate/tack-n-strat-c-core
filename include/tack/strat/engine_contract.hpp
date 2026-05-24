#pragma once

/** @file engine_contract.hpp
 *
 * Contract notes for downstream consumers (C ABI, 2D/3D clients, Laravel sync):
 *
 * - Threading: {@link tack::strat::GameSession} is not thread-safe. Serialize all calls on one thread
 *   or guard with an external mutex.
 * - Saves: {@link tack::strat::GameSnapshot::FORMAT_VERSION} must stay in sync with capture/restore;
 *   bump when schema changes and migrate loaders accordingly.
 * - Determinism: iteration order for map cells from {@link tack::strat::GameMap::all_cells} is sorted
 *   by (q, r). Prefer sorted snapshots when hashing (see {@link snapshot_stable_digest}).
 * - Maps: playable presets approximate Sid Meier's Civilization VI rectangular sizes using a hex disk
 *   (~2k–7k cells for Small–Large). {@link MapSizePreset::Tiny} is smaller (~817), default for fast tests.
 * - Network: turn-based HTTP polling lives in {@link tack::strat::net}; wire Laravel endpoints into
 *   {@link tack::strat::net::ITurnSyncTransport} when ready (core stays JSON-agnostic).
 * - I/O: core avoids stdout in hot paths; CLI lives in {@code tack_strat} binary.
 */

#include "tack/strat/game_snapshot.hpp"

namespace tack::strat::engine_contract {

/** Mirrors {@link GameSnapshot::FORMAT_VERSION} for ABI docs. */
inline constexpr int kSnapshotFormatVersion = GameSnapshot::FORMAT_VERSION;

}  // namespace tack::strat::engine_contract

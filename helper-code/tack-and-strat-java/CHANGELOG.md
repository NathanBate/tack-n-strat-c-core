# Changelog

## Unreleased

### Multiplayer / AI

- **CPU opponents**: New game setup has a **CPU** checkbox per seat. Computer seats skip the pass-device gate and play automatically using a simple seat AI (found city when legal, choose production, melee when adjacent, follow queued routes, otherwise march toward the nearest rival city/unit).
- **All-CPU guard**: If every seat is CPU, setup asks for confirmation before starting (no silent “spectator only” games).

### Saves & format

- **Save format v5**: units persist Civ-style **auto-explore** (`GameSnapshot.UnitSnap.autoExplore`). Older JSON without this field still loads (defaults to off).
- **Players** persist **`computer`** for CPU seats; older saves omit it and load as human.

### Exploration & movement

- **Auto-explore**: prefers stepping onto **never-seen tiles** first (fog), then steers toward unseen land; **Shift+E** or HUD toggle. If explore is on but the unit **still has MP and no legal step** (boxed in, costs too high, etc.), **End turn** stays blocked until you turn explore off, skip moves, or fortify — same as other unfinished orders.
- **Project moves into fog**: clicks and paths work into unexplored hexes when commanding your own units (Civ-style routing).

### UI / UX

- **Production selection**: Turn-start auto-focus and the primary-action button no longer jump away from a selected city that still needs production — choose builds from the right-hand panel without losing selection.
- **City HUD**: food **stagnation** note when growth stalls.
- **Settle preview**: **optimistic** vs **realistic** yields (rival claims on neighbors).
- **Minimap**: optional **tint only my territory claims** (Settings → Gameplay).
- **First-run tips** dialog (re-enable in Settings → Gameplay).
- **Hotkeys**: two-column overlay; **Toggle Auto-Explore** (default Shift+E).
- **Wildlife HUD**: ribbon clarifies animals **only render in line of sight**; hunt kills grant **+8 gold**.
- **Weather balance**: slightly lower extra movement from **storm** and **heat wave** on entry.

### World / cities

- **City names**: Over **2,000** unique generated compound names; each newly founded city draws the next name from a deck shuffled by **world seed** (deterministic across save/load). Beyond that pool, names fall back to `Outpost` plus the city id.

### World / wildlife

- **Wildlife spawns** tuned (fewer empty rounds; slightly higher arrival odds over time).
- **Animal aggression**: slightly softer wild attacks on units and raids on cities.

### Audio

- Distinct cue aliases for **found city**, **illegal actions**, and **turn advance** (reuse bundled WAVs until dedicated assets exist).

### Performance

- Settler recommendation lens results are **memoized** until selection, weights, bind, or moves invalidate the cache.

# Runtime assets used by the game

The app now loads assets directly from this folder at runtime.

## Graphics

Assignments and optional art sets are described in the repo and under `~/.tackstrat/graphics_assignments.json` (see Settings → Graphics lab).

- `assets/graphics/catalog/slots.json` — versioned slot catalog (`tackstratGraphicsCatalog`). Add new drawable elements here as the game grows.
- `assets/graphics/sets/*/manifest.json` — optional alternate art (`tackstratGraphicSetManifest` v1); folder name or `id` field selects the set from Settings.
- `assets/graphics/schema/` — generation bundle + set manifest JSON Schema and examples (shared with Claude / future importers).

Bundled defaults:

- `assets/graphics/city.txt`
- `assets/graphics/scout.txt`
- `assets/graphics/settler.txt`
- `assets/graphics/farm.txt` (farm improvement; slot `improvement.farm`)
- `assets/graphics/city.png`
- `assets/graphics/scout.png`
- `assets/graphics/settler.png`
- `assets/graphics/ui_menu.png`

The renderer prefers PNG icons (scalable draw) and falls back to text sprites.

Text sprites are 16x16 pattern files:

- `#` (or `X`, `1`, `@`) = filled pixel
- `.` = empty pixel

## Music

- `assets/music/envato-bundles/*.zip` (runtime-discovered)

At runtime, the game scans `assets/music/envato-bundles` for any `.zip` files.

- Each ZIP is treated as one track source.
- The first `.wav` file in each ZIP is extracted to a temp file only when needed.
- Tracks are randomized each run and reshuffled in cycles.
- Add or remove ZIPs over time; the game will discover them next launch.

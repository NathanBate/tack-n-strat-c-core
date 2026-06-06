/**
 * SDL3 hex map viewer aligned with the Java {@code HexMapPanel} / {@code UiTheme} behaviour:
 * same axial geometry (HEX_R=36), fog (visited vs visible), terrain styling, pan/zoom, selection,
 * legal move/attack highlights, and click → move / attack / multi-step route assignment.
 *
 * Launch with no arguments for a Java-style {@code SetupPanel} (player count, CPU seats, seed,
 * map, C continue autosave, L load named JSON) then {@code HandoffPanel} before each human's
 * turn. Command-line args skip straight to play (legacy): {@code tack_strat_gui seed human_count computer_count [tiny|small|medium|large]}.
 */

#include <SDL3/SDL.h>
#include <SDL3_ttf/SDL_ttf.h>

#include <array>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <optional>
#include <random>
#include <queue>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "tack/strat/city.hpp"
#include "tack/strat/city_building.hpp"
#include "tack/strat/city_focus.hpp"
#include "tack/strat/game_session.hpp"
#include "tack/strat/hex.hpp"
#include "tack/strat/java_random.hpp"
#include "tack/strat/map_size.hpp"
#include "tack/strat/player.hpp"
#include "tack/strat/season.hpp"
#include "tack/strat/seat_ai.hpp"
#include "tack/strat/snapshot_json_io.hpp"
#include "tack/strat/snapshot_validate.hpp"
#include "tack/strat/tile_improvement.hpp"
#include "tack/strat/terrain.hpp"
#include "tack/strat/unit.hpp"
#include "tack/strat/unit_kind.hpp"
#include "tack/strat/weather.hpp"
#include "tack/strat/wild_animal.hpp"

#include <nlohmann/json.hpp>

using tack::strat::City;
using tack::strat::CityYield;
using tack::strat::CityBuilding;
using tack::strat::CityFocus;
using tack::strat::GameSession;
using tack::strat::HexCoord;
using tack::strat::Player;
using tack::strat::Season;
using tack::strat::Terrain;
using tack::strat::TileImprovement;
using tack::strat::Unit;
using tack::strat::UnitKind;
using tack::strat::Weather;
using tack::strat::WildAnimal;
using tack::strat::WildAnimalKind;

namespace {

constexpr float HEX_R = 36.f;
constexpr float LIGHT_DIR_X = -0.7071067811865476f;
constexpr float LIGHT_DIR_Y = -0.7071067811865476f;
constexpr float MIN_SCALE = 0.45f;
constexpr float MAX_SCALE = 2.4f;
constexpr float ZOOM_STEP = 1.12f;
/** Keyboard zoom: {@link PlayPanel#installKeyBindings} uses 1.2. */
constexpr float JAVA_KEYBOARD_ZOOM = 1.2f;
/** Arrow pan: {@link PlayPanel#onDirectionKey} uses 80 screen px. */
constexpr float JAVA_PAN_PX = 80.f;
constexpr float JAVA_CURSOR_MARGIN = 120.f;
constexpr float k_top_bar_h = 56.f;
constexpr float k_footer_bar_h = 72.f;
constexpr float k_event_log_h = 54.f;
constexpr float k_event_log_expanded_h = 132.f;
constexpr float k_event_log_w = 420.f;
constexpr float k_event_log_line_h = 15.f;
constexpr std::size_t k_event_log_max_lines = 48;
constexpr float k_city_panel_w = 264.f;
constexpr float k_city_panel_margin = 10.f;
constexpr float k_production_drawer_w = 200.f;
constexpr float k_prod_btn_h = 28.f;
constexpr float k_prod_btn_gap = 4.f;
constexpr float k_inspector_btn_h = 26.f;
constexpr float k_inspector_btn_gap = 3.f;
constexpr float k_hud_action_orb_d = 72.f;
constexpr std::array<UnitKind, 6> k_production_kinds = {
    UnitKind::SETTLER, UnitKind::SCOUT,     UnitKind::WARRIOR,
    UnitKind::FARMER,  UnitKind::BUILDER,  UnitKind::HUNTING_PARTY,
};
constexpr std::array<CityFocus, 4> k_city_focuses = {
    CityFocus::BALANCED, CityFocus::FOOD, CityFocus::PRODUCTION, CityFocus::GOLD,
};
constexpr std::array<CityBuilding, 3> k_city_buildings = {
    CityBuilding::GRANARY, CityBuilding::WORKSHOP, CityBuilding::MARKET,
};
constexpr char const* k_city_focus_short_labels[] = {"Balanced", "Food", "Prod", "Gold"};
constexpr float k_minimap_w = 170.f;
constexpr float k_minimap_h = 128.f;
constexpr float k_minimap_pad = 14.f;
/** Vertical layout for city name + population plate (must match plate height in draw_city_population_plate). */
constexpr float k_city_label_name_h = 10.f;
constexpr float k_city_label_gap = 2.f;
constexpr float k_city_plate_h = 13.f;
constexpr float k_lod_detail_min_scale = 0.58f;
constexpr float k_edge_pan_margin = 22.f;
constexpr float k_edge_pan_step = 24.f;

struct SettlerLensHint {
  HexCoord hex;
  int rank = 0;
  int score = 0;
  int travel_turns = 0;
  std::vector<std::string> positives;
  std::vector<std::string> negatives;
};

constexpr std::uint8_t kBgTopRgb[] = {10, 18, 29};          // paintMapContent gradient top
constexpr std::uint8_t kBgBotRgb[] = {24, 42, 68};          // gradient bottom

struct RGB {
  float r{};
  float g{};
  float b{};
};

struct RGBA {
  float r{};
  float g{};
  float b{};
  float a{};
};

[[nodiscard]] constexpr RGB rgb_u8(std::uint8_t R, std::uint8_t G, std::uint8_t B) {
  return {R / 255.f, G / 255.f, B / 255.f};
}

// ---- TrueType text (SDL3_ttf) ---------------------------------------------------------------
// All UI text routes through menu_ui::draw_text / text_width. When a font is loaded these use
// SDL3_ttf with a per-(string,color) texture cache; otherwise they fall back to SDL's 8x8 debug
// font so the GUI still works if no system font is found.
TTF_Font* g_ui_font = nullptr;

struct CachedTextTexture {
  SDL_Texture* tex = nullptr;
  int w = 0;
  int h = 0;
};
std::unordered_map<std::string, CachedTextTexture> g_text_cache;

[[nodiscard]] inline Uint8 to_u8(float c) {
  return static_cast<Uint8>(std::lround(std::clamp(c, 0.f, 1.f) * 255.f));
}

SDL_Texture* acquire_text_texture(SDL_Renderer* ren, char const* text, RGB c, int* out_w,
                                  int* out_h) {
  if (g_ui_font == nullptr || text == nullptr || text[0] == '\0') {
    return nullptr;
  }
  char prefix[8];
  std::snprintf(prefix, sizeof(prefix), "%02x%02x%02x|", to_u8(c.r), to_u8(c.g), to_u8(c.b));
  std::string key = std::string(prefix) + text;
  if (auto it = g_text_cache.find(key); it != g_text_cache.end()) {
    *out_w = it->second.w;
    *out_h = it->second.h;
    return it->second.tex;
  }
  SDL_Color const col{to_u8(c.r), to_u8(c.g), to_u8(c.b), 255};
  SDL_Surface* surf = TTF_RenderText_Blended(g_ui_font, text, 0, col);
  if (surf == nullptr) {
    return nullptr;
  }
  SDL_Texture* tex = SDL_CreateTextureFromSurface(ren, surf);
  int const tw = surf->w;
  int const th = surf->h;
  SDL_DestroySurface(surf);
  if (tex == nullptr) {
    return nullptr;
  }
  SDL_SetTextureScaleMode(tex, SDL_SCALEMODE_LINEAR);
  if (g_text_cache.size() > 1500) {
    for (auto& [k, v] : g_text_cache) {
      if (v.tex != nullptr) {
        SDL_DestroyTexture(v.tex);
      }
    }
    g_text_cache.clear();
  }
  g_text_cache.emplace(std::move(key), CachedTextTexture{tex, tw, th});
  *out_w = tw;
  *out_h = th;
  return tex;
}

void init_ui_font() {
  if (!TTF_Init()) {
    return;
  }
  // macOS system fonts (the user selected a system-font source). First that opens wins.
  char const* candidates[] = {
      "/System/Library/Fonts/Supplemental/Arial.ttf",
      "/Library/Fonts/Arial.ttf",
      "/System/Library/Fonts/Helvetica.ttc",
      "/System/Library/Fonts/Avenir.ttc",
      "/System/Library/Fonts/Supplemental/Verdana.ttf",
      "/System/Library/Fonts/Geneva.ttf",
      "/System/Library/Fonts/SFNS.ttf",
  };
  for (char const* path : candidates) {
    g_ui_font = TTF_OpenFont(path, 14.0f);
    if (g_ui_font != nullptr) {
      break;
    }
  }
  if (g_ui_font != nullptr) {
    TTF_SetFontHinting(g_ui_font, TTF_HINTING_LIGHT);
  }
}

void shutdown_ui_font() {
  for (auto& [k, v] : g_text_cache) {
    if (v.tex != nullptr) {
      SDL_DestroyTexture(v.tex);
    }
  }
  g_text_cache.clear();
  if (g_ui_font != nullptr) {
    TTF_CloseFont(g_ui_font);
    g_ui_font = nullptr;
  }
  TTF_Quit();
}

namespace menu_ui {

constexpr RGB k_bg_deep = rgb_u8(0x0c, 0x12, 0x1d);
constexpr RGB k_card_bg = rgb_u8(0xf6, 0xf7, 0xf9);
constexpr RGB k_card_line = rgb_u8(0xd2, 0xd4, 0xd8);
constexpr RGB k_ink = rgb_u8(0x10, 0x18, 0x22);
constexpr RGB k_muted = rgb_u8(0x8d, 0x9a, 0xad);
constexpr RGB k_hint = rgb_u8(0xb8, 0xc0, 0xcc);
constexpr RGB k_title = rgb_u8(0xff, 0xff, 0xff);
constexpr RGB k_accent = rgb_u8(0xf5, 0xa8, 0x25);
constexpr RGB k_primary = rgb_u8(0x35, 0xb1, 0x4f);
constexpr RGB k_primary_dark = rgb_u8(0x2a, 0x8f, 0x3f);
constexpr RGB k_secondary = rgb_u8(0xe8, 0xea, 0xee);
constexpr RGB k_secondary_line = rgb_u8(0xc8, 0xcc, 0xd4);
constexpr RGB k_row_sel = rgb_u8(0xe3, 0xec, 0xf8);
constexpr RGB k_row_line = rgb_u8(0xe8, 0xea, 0xee);
constexpr RGB k_disabled = rgb_u8(0xa8, 0xb0, 0xbc);
constexpr RGB k_modal_bg = rgb_u8(0x14, 0x1f, 0x31);
constexpr RGB k_modal_line = rgb_u8(0x2a, 0x3d, 0x58);
constexpr RGB k_ok = rgb_u8(0x7c, 0xd6, 0x8c);
constexpr RGB k_err = rgb_u8(0xf3, 0x8b, 0x8b);
constexpr RGB k_victory_gold = rgb_u8(0xff, 0xd5, 0x4a);
constexpr RGB k_meta = rgb_u8(0xa8, 0xb0, 0xbc);
constexpr RGB k_recap = rgb_u8(0xc8, 0xd0, 0xdc);

void fill_rect(SDL_Renderer* ren, SDL_FRect r, RGB c, float a = 1.f);
void stroke_rect(SDL_Renderer* ren, SDL_FRect r, RGB c, float a = 1.f);
void draw_modal_card(SDL_Renderer* ren, SDL_FRect r);
[[nodiscard]] std::string truncate_line(std::string s, std::size_t max_len);
[[nodiscard]] std::vector<std::string> wrap_text_to_width(std::string text, float max_width);
[[nodiscard]] float text_width(char const* text) noexcept;
void draw_text(SDL_Renderer* ren, float x, float y, char const* text, RGB c, float a = 1.f);
void draw_text_wrapped(SDL_Renderer* ren, float x, float y, std::string const& text, float max_w,
                       float line_h, RGB c, float a = 1.f);
void draw_text_centered(SDL_Renderer* ren, float cx, float y, char const* text, RGB c, float a = 1.f);
void draw_text_centered_wrapped(SDL_Renderer* ren, float cx, float y, std::string const& text,
                                float max_w, float line_h, RGB c, float a = 1.f);
void draw_button(SDL_Renderer* ren, SDL_FRect r, char const* label, RGB fill, RGB border, RGB text,
                 bool enabled, bool primary = false);

struct SetupMenuLayout {
  float card_x{};
  float card_y{};
  float card_w{};
  float card_h{};
  SDL_FRect minus_btn{};
  SDL_FRect plus_btn{};
  SDL_FRect seed_btn{};
  SDL_FRect map_btn{};
  SDL_FRect continue_btn{};
  SDL_FRect load_btn{};
  SDL_FRect start_btn{};
  std::array<SDL_FRect, 4> seat_rows{};
};

[[nodiscard]] SetupMenuLayout compute_setup_menu_layout(int win_w, int win_h, int player_count);
[[nodiscard]] std::string format_menu_seed(std::int64_t seed);

struct HandoffLayout {
  SDL_FRect card{};
  SDL_FRect begin_btn{};
  SDL_FRect retry_btn{};
};

struct VictoryLayout {
  SDL_FRect card{};
  SDL_FRect review_btn{};
  SDL_FRect menu_btn{};
  SDL_FRect again_btn{};
};

[[nodiscard]] HandoffLayout compute_handoff_layout(int win_w, int win_h, bool show_retry);
[[nodiscard]] VictoryLayout compute_victory_layout(int win_w, int win_h);

}  // namespace menu_ui

[[nodiscard]] RGB blend_rgb(RGB a, RGB b, float t) {
  return {a.r * (1 - t) + b.r * t, a.g * (1 - t) + b.g * t, a.b * (1 - t) + b.b * t};
}

[[nodiscard]] SDL_FColor to_fcolor(RGB c, float a = 1.f) {
  return {c.r, c.g, c.b, a};
}

[[nodiscard]] RGB terrain_fill(Terrain t) {
  switch (t) {
    case Terrain::WATER:
      return rgb_u8(0x1f, 0x4d, 0x8b);
    case Terrain::GRASS:
      return rgb_u8(0x6e, 0xc2, 0x70);
    case Terrain::PLAINS:
      return rgb_u8(0xd5, 0xdd, 0x84);
    case Terrain::DESERT:
      return rgb_u8(0xe6, 0xc8, 0x84);
    case Terrain::HILL:
      return rgb_u8(0xb5, 0x8e, 0x5d);
    case Terrain::FOREST:
      return rgb_u8(0x39, 0x84, 0x5b);
    case Terrain::MOUNTAIN:
      return rgb_u8(0x6f, 0x6c, 0x70);
  }
  return rgb_u8(80, 80, 80);
}

[[nodiscard]] RGB terrain_shade(Terrain t) {
  RGB b = terrain_fill(t);
  return {std::max(0.f, b.r - 28.f / 255.f), std::max(0.f, b.g - 28.f / 255.f),
          std::max(0.f, b.b - 28.f / 255.f)};
}

[[nodiscard]] RGB terrain_highlight(Terrain t) {
  RGB b = terrain_fill(t);
  return {std::min(1.f, b.r + 22.f / 255.f), std::min(1.f, b.g + 22.f / 255.f),
          std::min(1.f, b.b + 22.f / 255.f)};
}

[[nodiscard]] RGB player_rgb(int seat) {
  static RGB const palette[] = {
      rgb_u8(0x3a, 0x7c, 0xff),
      rgb_u8(0xe2, 0x3e, 0x3e),
      rgb_u8(0x35, 0xb1, 0x4f),
      rgb_u8(0xf5, 0xa8, 0x25),
  };
  return palette[static_cast<std::size_t>(seat) % 4];
}

[[nodiscard]] float axial_to_world_x(HexCoord h) {
  return HEX_R * std::sqrt(3.f) * (static_cast<float>(h.q) + static_cast<float>(h.r) * 0.5f);
}

[[nodiscard]] float axial_to_world_y(HexCoord h) {
  return HEX_R * 1.5f * static_cast<float>(h.r);
}

void hex_corner_world(float cx, float cy, int i, float* ox, float* oy) {
  float const a = -1.57079632679f + static_cast<float>(i) * 1.0471975512f;
  *ox = cx + HEX_R * std::cos(a);
  *oy = cy + HEX_R * std::sin(a);
}

[[nodiscard]] bool point_in_tri(float px, float py, float ax, float ay, float bx, float by, float cx,
                                float cy) {
  auto orient = [](float x1, float y1, float x2, float y2, float x3, float y3) {
    return (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3);
  };
  float const d1 = orient(px, py, ax, ay, bx, by);
  float const d2 = orient(px, py, bx, by, cx, cy);
  float const d3 = orient(px, py, cx, cy, ax, ay);
  bool const has_neg = (d1 < 0.f) || (d2 < 0.f) || (d3 < 0.f);
  bool const has_pos = (d1 > 0.f) || (d2 > 0.f) || (d3 > 0.f);
  return !(has_neg && has_pos);
}

[[nodiscard]] bool point_in_hex_world(float px, float py, float cx, float cy) {
  float prev_x = 0;
  float prev_y = 0;
  hex_corner_world(cx, cy, 5, &prev_x, &prev_y);
  for (int i = 0; i < 6; ++i) {
    float x1 = 0;
    float y1 = 0;
    hex_corner_world(cx, cy, i, &x1, &y1);
    if (point_in_tri(px, py, cx, cy, prev_x, prev_y, x1, y1)) {
      return true;
    }
    prev_x = x1;
    prev_y = y1;
  }
  return false;
}

[[nodiscard]] HexCoord axial_round_fr(float fq, float fr) {
  float const fs = -fq - fr;
  int q = static_cast<int>(std::lround(fq));
  int r = static_cast<int>(std::lround(fr));
  int s = static_cast<int>(std::lround(fs));
  float const dq = std::abs(q - fq);
  float const dr = std::abs(r - fr);
  float const ds = std::abs(s - fs);
  if (dq > dr && dq > ds) {
    q = -r - s;
  } else if (dr > ds) {
    r = -q - s;
  } else {
    s = -q - r;
  }
  return HexCoord{q, r};
}

[[nodiscard]] HexCoord world_to_axial_round(float wx, float wy) {
  float const fq = (std::sqrt(3.f) / 3.f * wx - 1.f / 3.f * wy) / HEX_R;
  float const fr = (2.f / 3.f * wy) / HEX_R;
  return axial_round_fr(fq, fr);
}

struct View {
  float scale = 1.f;
  float offset_x = 0.f;
  float offset_y = 0.f;
  float world_min_x = 0.f;
  float world_max_x = 1.f;
  float world_min_y = 0.f;
  float world_max_y = 1.f;
  /** When true, pan/zoom/minimap use discovered territory only (Civ-style). */
  bool use_active_bounds = false;
  float active_min_x = 0.f;
  float active_max_x = 1.f;
  float active_min_y = 0.f;
  float active_max_y = 1.f;
};

[[nodiscard]] float map_area_height(int win_h) noexcept {
  return std::max(100.f, static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h);
}

[[nodiscard]] float map_center_x(int win_w) noexcept {
  return static_cast<float>(win_w) * 0.5f;
}

[[nodiscard]] float map_center_y(int win_h) noexcept {
  return k_top_bar_h + map_area_height(win_h) * 0.5f;
}

[[nodiscard]] float view_bounds_min_x(View const& v) noexcept {
  return v.use_active_bounds ? v.active_min_x : v.world_min_x;
}
[[nodiscard]] float view_bounds_max_x(View const& v) noexcept {
  return v.use_active_bounds ? v.active_max_x : v.world_max_x;
}
[[nodiscard]] float view_bounds_min_y(View const& v) noexcept {
  return v.use_active_bounds ? v.active_min_y : v.world_min_y;
}
[[nodiscard]] float view_bounds_max_y(View const& v) noexcept {
  return v.use_active_bounds ? v.active_max_y : v.world_max_y;
}

void sync_view_bounds_for_player(View* v, GameSession const& session, int seat) {
  if (v == nullptr || session.is_over()) {
    if (v != nullptr) {
      v->use_active_bounds = false;
    }
    return;
  }
  auto const visited = session.visited_for(seat);
  int const total = static_cast<int>(session.map().all_cells().size());
  if (visited.empty() || static_cast<int>(visited.size()) >= total) {
    v->use_active_bounds = false;
    return;
  }
  float const hw = std::sqrt(3.f) * HEX_R;
  float const hh = 2.f * HEX_R;
  float const pad = HEX_R * 1.4f;
  float min_x = std::numeric_limits<float>::infinity();
  float max_x = -std::numeric_limits<float>::infinity();
  float min_y = std::numeric_limits<float>::infinity();
  float max_y = -std::numeric_limits<float>::infinity();
  for (HexCoord const c : visited) {
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    min_x = std::min(min_x, cx - hw / 2.f);
    max_x = std::max(max_x, cx + hw / 2.f);
    min_y = std::min(min_y, cy - hh / 2.f);
    max_y = std::max(max_y, cy + hh / 2.f);
  }
  if (!std::isfinite(min_x) || !std::isfinite(min_y)) {
    v->use_active_bounds = false;
    return;
  }
  v->active_min_x = min_x - pad;
  v->active_max_x = max_x + pad;
  v->active_min_y = min_y - pad;
  v->active_max_y = max_y + pad;
  v->use_active_bounds = true;
}

[[nodiscard]] float min_allowed_scale(View const& v, int window_w, int window_h) noexcept {
  if (!v.use_active_bounds) {
    return MIN_SCALE;
  }
  float const dx = v.active_max_x - v.active_min_x;
  float const dy = v.active_max_y - v.active_min_y;
  if (dx <= 0.f || dy <= 0.f) {
    return MIN_SCALE;
  }
  float const w = static_cast<float>(std::max(100, window_w));
  float const map_h = map_area_height(window_h);
  float const sx = (w - 80.f) / dx;
  float const sy = (map_h - 80.f) / dy;
  return std::clamp(std::min(sx, sy), MIN_SCALE, MAX_SCALE);
}

void world_to_screen(View const& v, float wx, float wy, float* sx, float* sy) {
  *sx = wx * v.scale + v.offset_x;
  *sy = wy * v.scale + v.offset_y;
}

struct MapScreen {
  int w = 0;
  int h = 0;
};

[[nodiscard]] bool hex_on_map_screen(View const& v, float cx, float cy,
                                     MapScreen const& screen) noexcept {
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx, cy, &sx, &sy);
  float const margin = HEX_R * v.scale + 6.f;
  return sx >= -margin && sx <= static_cast<float>(screen.w) + margin &&
         sy >= k_top_bar_h - margin &&
         sy <= static_cast<float>(screen.h) - k_footer_bar_h + margin;
}

[[nodiscard]] HexCoord pick_hex_at_screen(GameSession const& session, View const& v, float scr_x,
                                           float scr_y) {
  float const wx = (scr_x - v.offset_x) / v.scale;
  float const wy = (scr_y - v.offset_y) / v.scale;
  HexCoord rounded = world_to_axial_round(wx, wy);
  if (!session.map().contains(rounded)) {
    return rounded;
  }
  float cx = axial_to_world_x(rounded);
  float cy = axial_to_world_y(rounded);
  if (point_in_hex_world(wx, wy, cx, cy)) {
    return rounded;
  }
  for (HexCoord n : rounded.neighbors()) {
    if (!session.map().contains(n)) {
      continue;
    }
    float ncx = axial_to_world_x(n);
    float ncy = axial_to_world_y(n);
    if (point_in_hex_world(wx, wy, ncx, ncy)) {
      return n;
    }
  }
  return rounded;
}

void recompute_world_bounds(GameSession const& session, View* v) {
  float const hw = std::sqrt(3.f) * HEX_R;
  float const hh = 2.f * HEX_R;
  v->world_min_x = std::numeric_limits<float>::infinity();
  v->world_max_x = -std::numeric_limits<float>::infinity();
  v->world_min_y = std::numeric_limits<float>::infinity();
  v->world_max_y = -std::numeric_limits<float>::infinity();
  for (HexCoord const c : session.map().all_cells()) {
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    v->world_min_x = std::min(v->world_min_x, cx - hw / 2.f);
    v->world_max_x = std::max(v->world_max_x, cx + hw / 2.f);
    v->world_min_y = std::min(v->world_min_y, cy - hh / 2.f);
    v->world_max_y = std::max(v->world_max_y, cy + hh / 2.f);
  }
}

void clamp_view(View* v, int window_w, int window_h) {
  float const w = static_cast<float>(std::max(100, window_w));
  float const map_top = k_top_bar_h;
  float const map_bot = static_cast<float>(window_h) - k_footer_bar_h;
  float const bmin_x = view_bounds_min_x(*v);
  float const bmax_x = view_bounds_max_x(*v);
  float const bmin_y = view_bounds_min_y(*v);
  float const bmax_y = view_bounds_max_y(*v);
  float const min_ox = w - 200.f - bmax_x * v->scale;
  float const max_ox = 200.f - bmin_x * v->scale;
  float const min_oy = map_bot - 200.f - bmax_y * v->scale;
  float const max_oy = map_top + 200.f - bmin_y * v->scale;
  v->offset_x = std::min(max_ox, std::max(min_ox, v->offset_x));
  v->offset_y = std::min(max_oy, std::max(min_oy, v->offset_y));
}

[[nodiscard]] bool view_shows_any_discovered(View const& v, GameSession const& session,
                                             int seat, int win_w, int win_h) {
  auto const visited = session.visited_for(seat);
  if (visited.empty()) {
    return true;
  }
  float const x0 = 0.f;
  float const y0 = k_top_bar_h;
  float const x1 = static_cast<float>(win_w);
  float const y1 = static_cast<float>(win_h) - k_footer_bar_h;
  for (HexCoord const c : visited) {
    float sx = 0.f;
    float sy = 0.f;
    world_to_screen(v, axial_to_world_x(c), axial_to_world_y(c), &sx, &sy);
    if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) {
      return true;
    }
  }
  return false;
}

void maybe_edge_pan_view(View* v, float mx, float my, int win_w, int win_h) {
  float dx = 0.f;
  float dy = 0.f;
  if (mx <= k_edge_pan_margin) {
    dx = k_edge_pan_step;
  } else if (mx >= static_cast<float>(win_w) - k_edge_pan_margin) {
    dx = -k_edge_pan_step;
  }
  float const map_top = k_top_bar_h + k_edge_pan_margin;
  float const map_bot = static_cast<float>(win_h) - k_footer_bar_h - k_edge_pan_margin;
  if (my <= map_top) {
    dy = k_edge_pan_step;
  } else if (my >= map_bot) {
    dy = -k_edge_pan_step;
  }
  if (dx != 0.f || dy != 0.f) {
    v->offset_x += dx;
    v->offset_y += dy;
    clamp_view(v, win_w, win_h);
  }
}

void enforce_view_discovered_constraints(View* v, GameSession const& session, int seat, int win_w,
                                         int win_h) {
  sync_view_bounds_for_player(v, session, seat);
  float const min_s = min_allowed_scale(*v, win_w, win_h);
  if (v->scale < min_s) {
    v->scale = min_s;
  }
  clamp_view(v, win_w, win_h);
}

void fit_map_to_window(View* v, int window_w, int window_h) {
  float const w = static_cast<float>(std::max(100, window_w));
  float const map_h = map_area_height(window_h);
  float const bmin_x = view_bounds_min_x(*v);
  float const bmax_x = view_bounds_max_x(*v);
  float const bmin_y = view_bounds_min_y(*v);
  float const bmax_y = view_bounds_max_y(*v);
  float const dx = bmax_x - bmin_x;
  float const dy = bmax_y - bmin_y;
  if (!(dx > 0 && dy > 0)) {
    return;
  }
  float const sx = (w - 80.f) / dx;
  float const sy = (map_h - 80.f) / dy;
  float const min_s = min_allowed_scale(*v, window_w, window_h);
  v->scale = std::max(min_s, std::min(MAX_SCALE, std::min(sx, sy)));
  float const cx = (bmin_x + bmax_x) * 0.5f;
  float const cy = (bmin_y + bmax_y) * 0.5f;
  v->offset_x = map_center_x(window_w) - cx * v->scale;
  v->offset_y = map_center_y(window_h) - cy * v->scale;
  clamp_view(v, window_w, window_h);
}

void zoom_at(View* v, float factor, float anchor_sx, float anchor_sy, int window_w, int window_h) {
  float const min_s = min_allowed_scale(*v, window_w, window_h);
  float const new_scale = std::max(min_s, std::min(MAX_SCALE, v->scale * factor));
  if (new_scale == v->scale) {
    return;
  }
  float const wx = (anchor_sx - v->offset_x) / v->scale;
  float const wy = (anchor_sy - v->offset_y) / v->scale;
  v->scale = new_scale;
  v->offset_x = anchor_sx - wx * v->scale;
  v->offset_y = anchor_sy - wy * v->scale;
  clamp_view(v, window_w, window_h);
}

void fill_hex_gradient(SDL_Renderer* ren, View const& v, float cx, float cy, RGB hi, RGB shade,
                       float alpha = 1.f) {
  float lit_x = cx + LIGHT_DIR_X * HEX_R * 0.98f;
  float lit_y = cy + LIGHT_DIR_Y * HEX_R * 0.98f;
  float dim_x = cx - LIGHT_DIR_X * HEX_R * 0.96f;
  float dim_y = cy - LIGHT_DIR_Y * HEX_R * 0.96f;
  SDL_Vertex vtx[7]{};
  float sx = 0;
  float sy = 0;
  world_to_screen(v, cx, cy, &sx, &sy);
  vtx[0].position = {sx, sy};
  vtx[0].color = {hi.r, hi.g, hi.b, alpha};
  vtx[0].tex_coord = {0, 0};
  for (int i = 0; i < 6; ++i) {
    float wx = 0;
    float wy = 0;
    hex_corner_world(cx, cy, i, &wx, &wy);
    world_to_screen(v, wx, wy, &sx, &sy);
    vtx[i + 1].position = {sx, sy};
    // Corner blend between lit (toward sun) and shade — matches Java gradient feel.
    float lx = wx - lit_x;
    float ly = wy - lit_y;
    float dx = wx - dim_x;
    float dy = wy - dim_y;
    float dl = std::sqrt(lx * lx + ly * ly);
    float dd = std::sqrt(dx * dx + dy * dy);
    float t = dd / (dl + dd + 1e-6f);
    RGB corner = blend_rgb(hi, shade, std::clamp(t, 0.f, 1.f));
    vtx[i + 1].color = {corner.r, corner.g, corner.b, alpha};
    vtx[i + 1].tex_coord = {0, 0};
  }
  int ind[18]{};
  for (int i = 0; i < 6; ++i) {
    ind[i * 3] = 0;
    ind[i * 3 + 1] = 1 + i;
    ind[i * 3 + 2] = 1 + ((i + 1) % 6);
  }
  SDL_RenderGeometry(ren, nullptr, vtx, 7, ind, 18);
}

void fill_hex_solid(SDL_Renderer* ren, View const& v, float cx, float cy, SDL_FColor color) {
  SDL_Vertex vtx[7]{};
  float sx = 0;
  float sy = 0;
  world_to_screen(v, cx, cy, &sx, &sy);
  vtx[0].position = {sx, sy};
  vtx[0].color = color;
  vtx[0].tex_coord = {0, 0};
  for (int i = 0; i < 6; ++i) {
    float wx = 0;
    float wy = 0;
    hex_corner_world(cx, cy, i, &wx, &wy);
    world_to_screen(v, wx, wy, &sx, &sy);
    vtx[i + 1].position = {sx, sy};
    vtx[i + 1].color = color;
    vtx[i + 1].tex_coord = {0, 0};
  }
  int ind[18]{};
  for (int i = 0; i < 6; ++i) {
    ind[i * 3] = 0;
    ind[i * 3 + 1] = 1 + i;
    ind[i * 3 + 2] = 1 + ((i + 1) % 6);
  }
  SDL_RenderGeometry(ren, nullptr, vtx, 7, ind, 18);
}

void stroke_hex_screen(SDL_Renderer* ren, View const& v, float cx, float cy, float r, float g,
                       float b, float a) {
  SDL_SetRenderDrawColorFloat(ren, r, g, b, a);
  for (int i = 0; i < 6; ++i) {
    float wx0 = 0;
    float wy0 = 0;
    float wx1 = 0;
    float wy1 = 0;
    hex_corner_world(cx, cy, i, &wx0, &wy0);
    hex_corner_world(cx, cy, (i + 1) % 6, &wx1, &wy1);
    float sx0 = 0;
    float sy0 = 0;
    float sx1 = 0;
    float sy1 = 0;
    world_to_screen(v, wx0, wy0, &sx0, &sy0);
    world_to_screen(v, wx1, wy1, &sx1, &sy1);
    SDL_RenderLine(ren, sx0, sy0, sx1, sy1);
  }
}

void stroke_hex_edge(SDL_Renderer* ren, View const& v, float cx, float cy, int edge_idx, float r,
                     float g, float b, float a) {
  int const i0 = ((edge_idx % 6) + 6) % 6;
  int const i1 = (i0 + 1) % 6;
  float wx0 = 0;
  float wy0 = 0;
  float wx1 = 0;
  float wy1 = 0;
  hex_corner_world(cx, cy, i0, &wx0, &wy0);
  hex_corner_world(cx, cy, i1, &wx1, &wy1);
  float sx0 = 0;
  float sy0 = 0;
  float sx1 = 0;
  float sy1 = 0;
  world_to_screen(v, wx0, wy0, &sx0, &sy0);
  world_to_screen(v, wx1, wy1, &sx1, &sy1);
  SDL_SetRenderDrawColorFloat(ren, r, g, b, a);
  SDL_RenderLine(ren, sx0, sy0, sx1, sy1);
}

[[nodiscard]] HexCoord neighbor_for_edge(HexCoord c, int edge_idx) {
  static int const dqdr[6][2] = {{0, -1}, {1, -1}, {1, 0}, {0, 1}, {-1, 1}, {-1, 0}};
  int const i = ((edge_idx % 6) + 6) % 6;
  return HexCoord{c.q + dqdr[i][0], c.r + dqdr[i][1]};
}

[[nodiscard]] int edge_index_toward(HexCoord from, HexCoord to) noexcept {
  for (int i = 0; i < 6; ++i) {
    if (neighbor_for_edge(from, i) == to) {
      return i;
    }
  }
  return -1;
}

void fill_hex_edge_inward_quad(SDL_Renderer* ren, View const& v, float cx, float cy, int edge_idx,
                               float er, float eg, float eb, float ea, float ir, float ig,
                               float ib, float ia, float inset_len) {
  int const i0 = ((edge_idx % 6) + 6) % 6;
  int const i1 = (i0 + 1) % 6;
  float wx0 = 0.f;
  float wy0 = 0.f;
  float wx1 = 0.f;
  float wy1 = 0.f;
  hex_corner_world(cx, cy, i0, &wx0, &wy0);
  hex_corner_world(cx, cy, i1, &wx1, &wy1);
  float const mx = (wx0 + wx1) * 0.5f;
  float const my = (wy0 + wy1) * 0.5f;
  float vx = cx - mx;
  float vy = cy - my;
  float const vl = std::hypot(vx, vy);
  if (vl < 1e-4f) {
    return;
  }
  float const scale = inset_len / vl;
  vx *= scale;
  vy *= scale;
  float const ix0 = wx0 + vx;
  float const iy0 = wy0 + vy;
  float const ix1 = wx1 + vx;
  float const iy1 = wy1 + vy;
  float sx0 = 0.f;
  float sy0 = 0.f;
  float sx1 = 0.f;
  float sy1 = 0.f;
  float sx2 = 0.f;
  float sy2 = 0.f;
  float sx3 = 0.f;
  float sy3 = 0.f;
  world_to_screen(v, wx0, wy0, &sx0, &sy0);
  world_to_screen(v, wx1, wy1, &sx1, &sy1);
  world_to_screen(v, ix1, iy1, &sx2, &sy2);
  world_to_screen(v, ix0, iy0, &sx3, &sy3);
  SDL_Vertex vtx[4]{};
  vtx[0].position = {sx0, sy0};
  vtx[0].color = {er, eg, eb, ea};
  vtx[0].tex_coord = {0, 0};
  vtx[1].position = {sx1, sy1};
  vtx[1].color = {er, eg, eb, ea};
  vtx[1].tex_coord = {0, 0};
  vtx[2].position = {sx2, sy2};
  vtx[2].color = {ir, ig, ib, ia};
  vtx[2].tex_coord = {0, 0};
  vtx[3].position = {sx3, sy3};
  vtx[3].color = {ir, ig, ib, ia};
  vtx[3].tex_coord = {0, 0};
  int ind[6] = {0, 1, 2, 0, 2, 3};
  SDL_RenderGeometry(ren, nullptr, vtx, 4, ind, 6);
}

[[nodiscard]] RGBA weather_wash_rgba(Weather w, bool in_sight) noexcept {
  float const a = (in_sight ? 52.f : 34.f) / 255.f;
  switch (w) {
    case Weather::CLEAR:
      return {1.f, 1.f, 1.f, 0.f};
    case Weather::RAIN:
      return {70.f / 255.f, 145.f / 255.f, 255.f / 255.f, a};
    case Weather::DROUGHT:
      return {215.f / 255.f, 165.f / 255.f, 85.f / 255.f, a};
    case Weather::STORM:
      return {125.f / 255.f, 95.f / 255.f, 205.f / 255.f, std::min(1.f, a + 10.f / 255.f)};
    case Weather::COLD_SNAP:
      return {205.f / 255.f, 232.f / 255.f, 255.f / 255.f, a};
    case Weather::HEAT_WAVE:
      return {255.f / 255.f, 115.f / 255.f, 55.f / 255.f, a};
    case Weather::FOG:
      return {188.f / 255.f, 190.f / 255.f, 198.f / 255.f, std::min(1.f, a + 8.f / 255.f)};
  }
  return {1.f, 1.f, 1.f, 0.f};
}

[[nodiscard]] char weather_badge_char(Weather w) noexcept {
  switch (w) {
    case Weather::CLEAR:
      return ' ';
    case Weather::RAIN:
      return 'R';
    case Weather::DROUGHT:
      return 'd';
    case Weather::STORM:
      return 'T';
    case Weather::COLD_SNAP:
      return 'C';
    case Weather::HEAT_WAVE:
      return 'H';
    case Weather::FOG:
      return 'F';
  }
  return ' ';
}

[[nodiscard]] RGB weather_badge_ink(Weather w) noexcept {
  switch (w) {
    case Weather::RAIN:
      return rgb_u8(215, 238, 255);
    case Weather::DROUGHT:
      return rgb_u8(255, 235, 190);
    case Weather::STORM:
      return rgb_u8(235, 225, 255);
    case Weather::COLD_SNAP:
      return rgb_u8(225, 242, 255);
    case Weather::HEAT_WAVE:
      return rgb_u8(255, 245, 230);
    case Weather::FOG:
      return rgb_u8(235, 236, 242);
    case Weather::CLEAR:
      return rgb_u8(255, 255, 255);
  }
  return rgb_u8(255, 255, 255);
}

void fill_hex_overlay(SDL_Renderer* ren, View const& v, float cx, float cy, float lr, float lg,
                      float lb, float la, float dr, float dg, float db, float da) {
  float lit_x = cx + LIGHT_DIR_X * HEX_R * 0.55f;
  float lit_y = cy + LIGHT_DIR_Y * HEX_R * 0.55f;
  float dim_x = cx - LIGHT_DIR_X * HEX_R * 0.55f;
  float dim_y = cy - LIGHT_DIR_Y * HEX_R * 0.55f;
  SDL_Vertex vtx[7]{};
  float sx = 0;
  float sy = 0;
  world_to_screen(v, cx, cy, &sx, &sy);
  vtx[0].position = {sx, sy};
  vtx[0].color = {lr, lg, lb, la};
  vtx[0].tex_coord = {0, 0};
  for (int i = 0; i < 6; ++i) {
    float wx = 0;
    float wy = 0;
    hex_corner_world(cx, cy, i, &wx, &wy);
    world_to_screen(v, wx, wy, &sx, &sy);
    vtx[i + 1].position = {sx, sy};
    float lx = wx - lit_x;
    float ly = wy - lit_y;
    float dx = wx - dim_x;
    float dy = wy - dim_y;
    float dl = std::sqrt(lx * lx + ly * ly);
    float dd = std::sqrt(dx * dx + dy * dy);
    float t = dd / (dl + dd + 1e-6f);
    t = std::clamp(t, 0.f, 1.f);
    vtx[i + 1].color = {(lr * (1 - t) + dr * t), (lg * (1 - t) + dg * t), (lb * (1 - t) + db * t),
                        (la * (1 - t) + da * t)};
    vtx[i + 1].tex_coord = {0, 0};
  }
  int ind[18]{};
  for (int i = 0; i < 6; ++i) {
    ind[i * 3] = 0;
    ind[i * 3 + 1] = 1 + i;
    ind[i * 3 + 2] = 1 + ((i + 1) % 6);
  }
  SDL_RenderGeometry(ren, nullptr, vtx, 7, ind, 18);
}

/** Horizontal bar: outer black rim, dark fill, colored HP portion (green→red). */
void draw_unit_hp_bar(SDL_Renderer* ren, float sx_center, float sy_top, float width, float height,
                      float hp_frac, float pulse = 0.f) {
  float const x = sx_center - width * 0.5f;
  SDL_FRect border{x - 1.f, sy_top - 1.f, width + 2.f, height + 2.f};
  SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, 1.f);
  SDL_RenderFillRect(ren, &border);
  SDL_FRect bg{x, sy_top, width, height};
  SDL_SetRenderDrawColorFloat(ren, 0.1f, 0.1f, 0.1f, 1.f);
  SDL_RenderFillRect(ren, &bg);
  float const f = std::clamp(hp_frac, 0.f, 1.f);
  if (f > 0.f) {
    SDL_FRect fill{x, sy_top, width * f, height};
    float const er = 0.88f * (1.f - f) + 0.22f * f;
    float const eg = 0.26f * (1.f - f) + 0.80f * f;
    float const eb = 0.20f * (1.f - f) + 0.26f * f;
    SDL_SetRenderDrawColorFloat(ren, er, eg, eb, 1.f);
    SDL_RenderFillRect(ren, &fill);
  }
  if (pulse > 0.f && f < 1.f) {
    float const pa = std::min(1.f, (95.f + 55.f * pulse) / 255.f);
    SDL_SetRenderDrawColorFloat(ren, 1.f, 120.f / 255.f, 100.f / 255.f, pa);
    SDL_RenderRect(ren, &border);
  }
}

void draw_ground_ellipse_shadow(SDL_Renderer* ren, View const& v, float cx, float cy, float rx,
                                float ry, float alpha) {
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx + HEX_R * 0.11f, cy + HEX_R * 0.13f, &sx, &sy);
  float const srx = rx * v.scale;
  float const sry = ry * v.scale;
  int const segments = 16;
  SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, alpha);
  for (int i = 0; i < segments; ++i) {
    float const a0 = 6.2831853f * static_cast<float>(i) / static_cast<float>(segments);
    float const a1 = 6.2831853f * static_cast<float>(i + 1) / static_cast<float>(segments);
    SDL_RenderLine(ren, sx + std::cos(a0) * srx, sy + std::sin(a0) * sry,
                   sx + std::cos(a1) * srx, sy + std::sin(a1) * sry);
  }
  SDL_Vertex center{};
  center.position = {sx, sy};
  center.color = {0.f, 0.f, 0.f, alpha * 0.55f};
  center.tex_coord = {0, 0};
  for (int i = 0; i < segments; ++i) {
    float const a0 = 6.2831853f * static_cast<float>(i) / static_cast<float>(segments);
    float const a1 = 6.2831853f * static_cast<float>(i + 1) / static_cast<float>(segments);
    SDL_Vertex vtx[3]{center,
                      {{sx + std::cos(a0) * srx, sy + std::sin(a0) * sry},
                       {0.f, 0.f, 0.f, alpha * 0.55f},
                       {0, 0}},
                      {{sx + std::cos(a1) * srx, sy + std::sin(a1) * sry},
                       {0.f, 0.f, 0.f, alpha * 0.55f},
                       {0, 0}}};
    int ind[3] = {0, 1, 2};
    SDL_RenderGeometry(ren, nullptr, vtx, 3, ind, 3);
  }
}

[[nodiscard]] int city_population_tier(int population) noexcept {
  if (population >= 9) {
    return 3;
  }
  if (population >= 5) {
    return 2;
  }
  return 1;
}

[[nodiscard]] char wild_animal_glyph(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return 'w';
    case WildAnimalKind::BEAR:
      return 'B';
    case WildAnimalKind::BOAR:
      return 'b';
    case WildAnimalKind::COUGAR:
      return 'c';
    case WildAnimalKind::JACKAL:
      return 'j';
    case WildAnimalKind::ELK:
      return 'E';
    case WildAnimalKind::DEER:
      return 'd';
    case WildAnimalKind::HORSE:
      return 'h';
  }
  return '?';
}

[[nodiscard]] RGB wild_animal_tint(WildAnimalKind k) noexcept {
  switch (k) {
    case WildAnimalKind::WOLF:
      return rgb_u8(120, 128, 148);
    case WildAnimalKind::BEAR:
      return rgb_u8(92, 68, 48);
    case WildAnimalKind::BOAR:
      return rgb_u8(148, 98, 72);
    case WildAnimalKind::COUGAR:
      return rgb_u8(168, 118, 78);
    case WildAnimalKind::JACKAL:
      return rgb_u8(158, 138, 88);
    case WildAnimalKind::ELK:
      return rgb_u8(132, 108, 78);
    case WildAnimalKind::DEER:
      return rgb_u8(178, 138, 98);
    case WildAnimalKind::HORSE:
      return rgb_u8(188, 152, 108);
  }
  return rgb_u8(160, 120, 90);
}

void draw_terrain_tile_deco(SDL_Renderer* ren, View const& v, Terrain t, HexCoord c, float cx,
                            float cy, bool has_city) {
  if (v.scale < k_lod_detail_min_scale || has_city) {
    return;
  }
  float const drift_x = 0.f;
  float const drift_y = 0.f;
  auto draw_tree = [&](float ox, float oy) {
    float sx = 0.f;
    float sy = 0.f;
    world_to_screen(v, cx + ox + drift_x * 0.4f, cy + oy + drift_y * 0.4f, &sx, &sy);
    float const h = std::max(7.f, 10.f * v.scale);
    SDL_SetRenderDrawColorFloat(ren, 0.28f, 0.48f, 0.18f, 0.90f);
    SDL_FRect trunk{sx - 1.5f, sy, 3.f, h * 0.38f};
    SDL_RenderFillRect(ren, &trunk);
    SDL_SetRenderDrawColorFloat(ren, 0.16f, 0.52f, 0.22f, 0.92f);
    SDL_FRect crown{sx - h * 0.30f, sy - h * 0.58f, h * 0.60f, h * 0.58f};
    SDL_RenderFillRect(ren, &crown);
  };
  if (t == Terrain::FOREST) {
    draw_tree(-HEX_R * 0.32f, HEX_R * 0.14f);
    draw_tree(HEX_R * 0.10f, -HEX_R * 0.18f);
    draw_tree(HEX_R * 0.30f, HEX_R * 0.22f);
  } else if (t == Terrain::HILL) {
    auto draw_hill_arc = [&](float ox, float oy, float rad) {
      float sx = 0.f;
      float sy = 0.f;
      world_to_screen(v, cx + ox, cy + oy, &sx, &sy);
      float const sr = rad * v.scale;
      SDL_SetRenderDrawColorFloat(ren, 0.42f, 0.31f, 0.19f, 0.82f);
      for (int i = 0; i < 8; ++i) {
        float const a0 = 0.35f + static_cast<float>(i) * 0.18f;
        float const a1 = a0 + 0.18f;
        SDL_RenderLine(ren, sx + std::cos(a0) * sr, sy + std::sin(a0) * sr * 0.55f,
                       sx + std::cos(a1) * sr, sy + std::sin(a1) * sr * 0.55f);
      }
    };
    draw_hill_arc(-HEX_R * 0.08f + drift_x * 0.2f, -HEX_R * 0.05f, HEX_R * 0.45f);
    draw_hill_arc(HEX_R * 0.12f, -HEX_R * 0.22f, HEX_R * 0.35f);
  } else if (t == Terrain::MOUNTAIN) {
    float sx = 0.f;
    float sy = 0.f;
    world_to_screen(v, cx, cy, &sx, &sy);
    float const base = std::max(10.f, 16.f * v.scale);
    SDL_Vertex peak[3]{};
    peak[0].position = {sx, sy - base * 0.95f};
    peak[0].color = {0.72f, 0.74f, 0.78f, 0.95f};
    peak[0].tex_coord = {0, 0};
    peak[1].position = {sx - base, sy + base * 0.45f};
    peak[1].color = {0.28f, 0.28f, 0.30f, 0.95f};
    peak[1].tex_coord = {0, 0};
    peak[2].position = {sx + base, sy + base * 0.45f};
    peak[2].color = {0.34f, 0.34f, 0.36f, 0.95f};
    peak[2].tex_coord = {0, 0};
    int ind[3] = {0, 1, 2};
    SDL_RenderGeometry(ren, nullptr, peak, 3, ind, 3);
    SDL_SetRenderDrawColorFloat(ren, 0.92f, 0.94f, 0.98f, 0.88f);
    SDL_FRect snow{sx - base * 0.22f, sy - base * 0.82f, base * 0.44f, base * 0.28f};
    SDL_RenderFillRect(ren, &snow);
  } else if (t == Terrain::PLAINS || t == Terrain::GRASS) {
    auto draw_tuft = [&](float ox, float oy) {
      float sx = 0.f;
      float sy = 0.f;
      world_to_screen(v, cx + ox + drift_x * 0.25f, cy + oy + drift_y * 0.25f, &sx, &sy);
      float const h = std::max(4.f, 6.f * v.scale);
      SDL_SetRenderDrawColorFloat(ren, 0.52f, 0.68f, 0.28f, 0.78f);
      SDL_FRect tuft{sx - h * 0.5f, sy - h, h, h};
      SDL_RenderFillRect(ren, &tuft);
    };
    draw_tuft(-HEX_R * 0.22f, HEX_R * 0.08f);
    draw_tuft(HEX_R * 0.18f, -HEX_R * 0.12f);
    draw_tuft(HEX_R * 0.05f, HEX_R * 0.20f);
  } else if (t == Terrain::DESERT) {
    auto draw_dune = [&](float ox, float oy, float rad) {
      float sx = 0.f;
      float sy = 0.f;
      world_to_screen(v, cx + ox, cy + oy, &sx, &sy);
      float const sr = rad * v.scale;
      SDL_SetRenderDrawColorFloat(ren, 0.78f, 0.66f, 0.38f, 0.55f);
      for (int i = 0; i < 7; ++i) {
        float const a0 = 3.4f + static_cast<float>(i) * 0.16f;
        float const a1 = a0 + 0.16f;
        SDL_RenderLine(ren, sx + std::cos(a0) * sr, sy + std::sin(a0) * sr * 0.45f,
                       sx + std::cos(a1) * sr, sy + std::sin(a1) * sr * 0.45f);
      }
    };
    draw_dune(-HEX_R * 0.12f + drift_x * 0.15f, HEX_R * 0.05f, HEX_R * 0.38f);
    draw_dune(HEX_R * 0.20f, -HEX_R * 0.10f, HEX_R * 0.28f);
  }
}

[[nodiscard]] bool tile_should_show_yield_badge(
    HexCoord c, std::optional<HexCoord> hovered_hex,
    std::optional<HexCoord> selected_unit_hex) noexcept {
  return (hovered_hex.has_value() && *hovered_hex == c) ||
         (selected_unit_hex.has_value() && *selected_unit_hex == c);
}

void draw_tile_yield_badge(SDL_Renderer* ren, View const& v, GameSession const& session,
                           HexCoord c, float cx, float cy) {
  if (v.scale < 1.0f || session.city_at(c).has_value()) {
    return;
  }
  int const food = session.tile_food_yield(c);
  int const prod = session.tile_production_yield(c);
  int const gold = session.tile_gold_yield(c);
  if (food == 0 && prod == 0 && gold == 0) {
    return;
  }
  std::string label;
  if (food > 0) {
    label += "+" + std::to_string(food) + "f";
  }
  if (prod > 0) {
    label += "+" + std::to_string(prod) + "p";
  }
  if (gold > 0) {
    label += "+" + std::to_string(gold) + "g";
  }
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx - HEX_R * 0.42f, cy + HEX_R * 0.48f, &sx, &sy);
  float const tw = menu_ui::text_width(label.c_str()) + 6.f;
  SDL_FRect bg{sx - 3.f, sy - 2.f, tw, 12.f};
  menu_ui::fill_rect(ren, bg, rgb_u8(10, 14, 20), 0.72f);
  menu_ui::draw_text(ren, sx, sy, label.c_str(), menu_ui::k_hint, 0.95f);
}

void draw_wild_animal_marker(SDL_Renderer* ren, View const& v, WildAnimal const& beast,
                             bool hunt_target) {
  float const cx = axial_to_world_x(beast.coord());
  float const cy = axial_to_world_y(beast.coord());
  if (v.scale >= k_lod_detail_min_scale) {
    draw_ground_ellipse_shadow(ren, v, cx, cy, HEX_R * 0.36f, HEX_R * 0.13f, 0.20f);
  }
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx, cy, &sx, &sy);
  float const r = std::max(8.f, 11.f * v.scale);
  RGB tint = wild_animal_tint(beast.kind());
  SDL_SetRenderDrawColorFloat(ren, tint.r * 0.55f, tint.g * 0.55f, tint.b * 0.55f, 0.95f);
  SDL_FRect rim{sx - r - 2.f, sy - r - 1.5f, 2.f * r + 4.f, 2.f * r + 3.f};
  SDL_RenderFillRect(ren, &rim);
  SDL_SetRenderDrawColorFloat(ren, tint.r, tint.g, tint.b, 0.92f);
  SDL_FRect disk{sx - r, sy - r, 2.f * r, 2.f * r};
  SDL_RenderFillRect(ren, &disk);
  char label[2] = {wild_animal_glyph(beast.kind()), '\0'};
  float const glyph_w = menu_ui::text_width(label);
  menu_ui::draw_text(ren, sx - glyph_w * 0.5f + 1.f, sy - 6.f + 1.f, label, menu_ui::k_ink, 0.82f);
  menu_ui::draw_text(ren, sx - glyph_w * 0.5f, sy - 6.f, label, rgb_u8(252, 250, 240), 0.94f);
  int const max_hp = std::max(1, tack::strat::wild_max_hp(beast.kind()));
  if (beast.hp() < max_hp) {
    float const hp_frac = static_cast<float>(beast.hp()) / static_cast<float>(max_hp);
    draw_unit_hp_bar(ren, sx, sy + r + 4.f, 2.f * r + 4.f, 4.f, hp_frac);
  }
  if (hunt_target) {
    SDL_SetRenderDrawColorFloat(ren, 1.f, 0.72f, 0.18f, 0.80f);
    SDL_FRect hunt_ring{sx - r - 5.f, sy - r - 5.f, 2.f * r + 10.f, 2.f * r + 10.f};
    SDL_RenderRect(ren, &hunt_ring);
  }
}

/** Civ I-style population plate: tan fill, black border, centered digits. */
void draw_city_population_plate(SDL_Renderer* ren, float sx_center, float sy_top, int population) {
  std::string const pop_str = std::to_string(population);
  float const pw = 8.f + 7.f * static_cast<float>(pop_str.size());
  float const ph = k_city_plate_h;
  SDL_FRect plate{sx_center - pw * 0.5f, sy_top, pw, ph};
  SDL_SetRenderDrawColorFloat(ren, 0.91f, 0.82f, 0.52f, 1.f);
  SDL_RenderFillRect(ren, &plate);
  SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, 1.f);
  SDL_RenderRect(ren, &plate);
  float const tw = menu_ui::text_width(pop_str.c_str());
  menu_ui::draw_text(ren, sx_center - tw * 0.5f, sy_top + 1.f, pop_str.c_str(), menu_ui::k_ink);
}

void draw_city_map_banner(SDL_Renderer* ren, float hcx, float name_ty, float name_half_w,
                          std::string const& banner, int population, int pop_tier,
                          bool show_build, char build_glyph) {
  float const extra = (pop_tier >= 2 ? 16.f : 0.f) + (show_build ? 18.f : 0.f);
  float const banner_w = name_half_w * 2.f + extra + 24.f;
  float const banner_h = k_city_label_name_h + k_city_label_gap + k_city_plate_h + 10.f;
  SDL_FRect card{hcx - banner_w * 0.5f, name_ty - 5.f, banner_w, banner_h};
  menu_ui::fill_rect(ren, {card.x + 2.f, card.y + 3.f, card.w, card.h}, menu_ui::k_ink, 0.28f);
  menu_ui::fill_rect(ren, card, rgb_u8(18, 22, 30), 0.93f);
  menu_ui::stroke_rect(ren, card, menu_ui::k_modal_line, 0.78f);
  float const name_w = menu_ui::text_width(banner.c_str());
  menu_ui::draw_text(ren, hcx - name_w * 0.5f, name_ty, banner.c_str(), menu_ui::k_title, 0.96f);
  if (pop_tier >= 3) {
    menu_ui::draw_text(ren, hcx + name_half_w + 4.f, name_ty, "*", menu_ui::k_victory_gold, 0.97f);
  } else if (pop_tier >= 2) {
    menu_ui::draw_text(ren, hcx + name_half_w + 4.f, name_ty, "+", menu_ui::k_hint, 0.84f);
  }
  if (show_build) {
    SDL_FRect build_bg{hcx + name_half_w + 14.f, name_ty - 1.f, 14.f, 14.f};
    menu_ui::fill_rect(ren, build_bg, rgb_u8(28, 34, 44), 0.95f);
    char build_label[2] = {build_glyph, '\0'};
    menu_ui::draw_text(ren, build_bg.x + 3.f, build_bg.y + 1.f, build_label, menu_ui::k_ok);
  }
  float const plate_ty = name_ty + k_city_label_name_h + k_city_label_gap;
  draw_city_population_plate(ren, hcx, plate_ty, population);
}

[[nodiscard]] char const* unit_kind_glyph(UnitKind k) noexcept {
  switch (k) {
    case UnitKind::SETTLER:
      return "T";
    case UnitKind::SCOUT:
      return "S";
    case UnitKind::WARRIOR:
      return "W";
    case UnitKind::FARMER:
      return "F";
    case UnitKind::BUILDER:
      return "B";
    case UnitKind::HUNTING_PARTY:
      return "H";
  }
  return "?";
}

void draw_unit_kind_glyph_badge(SDL_Renderer* ren, float sx, float sy, UnitKind kind, float alpha) {
  menu_ui::draw_text_centered(ren, sx, sy + 2.f, unit_kind_glyph(kind), menu_ui::k_title, alpha);
}

[[nodiscard]] char const* season_label(Season s) noexcept {
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

[[nodiscard]] char const* city_focus_label(CityFocus f) noexcept {
  switch (f) {
    case CityFocus::BALANCED:
      return "Balanced";
    case CityFocus::FOOD:
      return "Food";
    case CityFocus::PRODUCTION:
      return "Production";
    case CityFocus::GOLD:
      return "Gold";
  }
  return "?";
}

[[nodiscard]] Player const* player_by_seat(GameSession const& session, int seat) noexcept;
[[nodiscard]] int count_human_players(GameSession const& session) noexcept;

struct ProductionDrawerLayout {
  bool visible = false;
  SDL_FRect panel{};
  std::array<SDL_FRect, 6> build_buttons{};
};

struct UnitInspectorLayout {
  bool visible = false;
  bool is_mine = false;
  int unit_id = -1;
  SDL_FRect panel{};
  SDL_FRect skip_btn{};
  SDL_FRect fortify_btn{};
  SDL_FRect explore_btn{};
  SDL_FRect sleep_btn{};
  SDL_FRect found_city_btn{};
  SDL_FRect cultivate_btn{};
  SDL_FRect clear_forest_btn{};
  SDL_FRect build_farm_btn{};
  SDL_FRect build_mine_btn{};
  SDL_FRect rebase_btn{};
  SDL_FRect hunt_btn{};
  float content_max_y = 0.f;
  bool show_found_city = false;
  bool show_cultivate = false;
  bool show_clear_forest = false;
  bool show_build_farm = false;
  bool show_build_mine = false;
  bool show_rebase = false;
  bool show_hunt = false;
};

struct CityInspectorLayout {
  bool visible = false;
  bool is_mine = false;
  int city_id = -1;
  SDL_FRect panel{};
  std::array<SDL_FRect, 4> focus_buttons{};
  std::array<SDL_FRect, 3> building_buttons{};
  float content_max_y = 0.f;
};

struct ProductionNeedsLayout {
  bool visible = false;
  SDL_FRect panel{};
  std::vector<SDL_FRect> jump_buttons{};
  std::vector<int> city_ids{};
};

struct EventLogLayout {
  bool visible = false;
  bool expanded = false;
  SDL_FRect panel{};
  SDL_FRect toggle_btn{};
};

[[nodiscard]] bool production_drawer_visible(GameSession const& session,
                                             std::optional<int> selected_city_id) noexcept {
  if (!selected_city_id.has_value()) {
    return false;
  }
  auto c = session.city_by_id(*selected_city_id);
  if (!c.has_value()) {
    return false;
  }
  return c->owner_seat() == session.current_player().seat;
}

[[nodiscard]] float play_screen_right_ui_margin(GameSession const& session,
                                                std::optional<int> selected_city_id) noexcept {
  if (!selected_city_id.has_value()) {
    return 0.f;
  }
  auto c = session.city_by_id(*selected_city_id);
  if (!c.has_value()) {
    return 0.f;
  }
  float margin = k_city_panel_w + k_city_panel_margin;
  if (c->owner_seat() == session.current_player().seat) {
    margin += k_production_drawer_w;
  }
  return margin;
}

[[nodiscard]] ProductionDrawerLayout compute_production_drawer_layout(int win_w, int win_h,
                                                                        bool visible) noexcept {
  ProductionDrawerLayout layout;
  layout.visible = visible;
  if (!visible) {
    return layout;
  }
  float const x = static_cast<float>(win_w) - k_production_drawer_w;
  float const y = k_top_bar_h;
  float const h = static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h;
  layout.panel = {x, y, k_production_drawer_w, h};
  float cy = y + 42.f;
  for (int i = 0; i < 6; ++i) {
    layout.build_buttons[static_cast<std::size_t>(i)] = {x + 10.f, cy, k_production_drawer_w - 20.f,
                                                         k_prod_btn_h};
    cy += k_prod_btn_h + k_prod_btn_gap;
  }
  return layout;
}

[[nodiscard]] int turns_to_complete_unit(int cost, int stored, int prod_per_turn) noexcept {
  int const remaining = std::max(0, cost - stored);
  if (prod_per_turn <= 0) {
    return 999;
  }
  return (remaining + prod_per_turn - 1) / prod_per_turn;
}

struct GuiState;
struct SdlSessionFlow;

bool apply_city_production_choice(GameSession& session, GuiState* gui, View* view,
                                  std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                  int city_id, UnitKind kind, bool enqueue, int win_w, int win_h);
bool try_handle_production_drawer_click(GameSession& session, int city_id,
                                          ProductionDrawerLayout const& layout, bool shift,
                                          GuiState* gui, View* view, std::optional<int>* sel_unit,
                                          std::optional<int>* sel_city, int win_w, int win_h,
                                          float mx, float my);
void pick_default_human_unit(GameSession const& session, std::optional<int>* out);
[[nodiscard]] bool screen_point_in_rect(float px, float py, SDL_FRect const& r) noexcept;
[[nodiscard]] bool sdl_player_has_city_without_production(GameSession const& session);
[[nodiscard]] bool sdl_has_pending_manual_unit_orders(GameSession const& session,
                                                      GuiState const& gui);
void select_first_city_missing_production_sdl(GameSession const& session, View* view,
                                              std::optional<int>* selected_unit_id,
                                              std::optional<int>* selected_city_id, int win_w,
                                              int win_h);
[[nodiscard]] bool city_name_less_ci(std::string const& a, std::string const& b);
[[nodiscard]] bool civilian_kind(UnitKind k);
void try_found_city_sdl(GameSession& session, std::optional<int>* selected_unit_id,
                        std::optional<int>* selected_city_id, View* view, int win_w, int win_h,
                        GuiState* gui);
void do_end_turn_sdl(GameSession& session, GuiState* gui, View* view,
                     std::optional<int>* selected_unit_id, std::optional<int>* selected_city_id,
                     int win_w, int win_h, SdlSessionFlow const* flow);
void cycle_next_unit(GameSession& session, std::optional<int>* selected_unit_id, View* v, int win_w,
                     int win_h, GuiState* gui, std::optional<int>* selected_city_id = nullptr);
void cycle_prev_unit(GameSession& session, std::optional<int>* selected_unit_id, View* v, int win_w,
                     int win_h, GuiState* gui, std::optional<int>* selected_city_id = nullptr);
void on_skip_action(GameSession& session, GuiState* gui, std::optional<int>* selected_unit_id,
                    std::optional<int>* selected_city_id, View* view, int win_w, int win_h,
                    SdlSessionFlow const* flow);
[[nodiscard]] UnitInspectorLayout compute_unit_inspector_layout(GameSession const& session,
                                                                std::optional<int> selected_unit_id,
                                                                int cur_seat, int win_h,
                                                                float max_panel_bottom_y);
[[nodiscard]] CityInspectorLayout compute_city_inspector_layout(
    GameSession const& session, std::optional<int> selected_city_id, int win_w, int win_h,
    bool show_production_drawer, float max_panel_bottom_y);
[[nodiscard]] ProductionNeedsLayout compute_production_needs_layout(
    GameSession const& session, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id, int win_h, float max_panel_bottom_y);
[[nodiscard]] EventLogLayout compute_event_log_layout(GuiState const& gui, int win_w, int win_h);
void maybe_auto_advance_action_queue_sdl(GameSession& session, GuiState* gui, View* v,
                                         std::optional<int>* sel_unit,
                                         std::optional<int>* sel_city, int win_w, int win_h);
void toggle_auto_focus_next_unit(GuiState* gui);
void load_gui_options(GuiState* gui);
void save_gui_options(GuiState const& gui);
void dismiss_intro_tips(GuiState* gui);
struct GameMenuLayout;
[[nodiscard]] GameMenuLayout compute_game_menu_layout(int win_w) noexcept;
void draw_game_menu_ui(SDL_Renderer* ren, GuiState const& gui, GameSession const& session,
                       int win_w);
bool try_handle_game_menu_click(GameSession& session, GuiState* gui, View* view,
                                std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                GameMenuLayout const& layout, float mx, float my, int win_w,
                                int win_h, SdlSessionFlow const* flow);
void draw_intro_tips_overlay(SDL_Renderer* ren, int win_w, int win_h);
bool try_handle_event_log_click(GuiState* gui, EventLogLayout const& layout, float mx, float my);
bool try_handle_event_log_wheel(GuiState* gui, EventLogLayout const& layout, float mx, float my,
                                float wheel_y);
bool try_handle_unit_inspector_click(GameSession& session, GuiState* gui, View* view,
                                     std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                     UnitInspectorLayout const& layout, float mx, float my,
                                     int win_w, int win_h, SdlSessionFlow const* flow);
bool try_handle_city_inspector_click(GameSession& session, GuiState* gui,
                                     CityInspectorLayout const& layout, float mx, float my);
bool try_handle_production_needs_click(GameSession& session, GuiState* gui, View* view,
                                       std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                       ProductionNeedsLayout const& layout, float mx, float my,
                                       int win_w, int win_h);

enum class HudActionKind : uint8_t {
  Wait,
  EndTurn,
  NextUnit,
  Move,
  FollowRoute,
  FoundCity,
  Wake,
  Production,
  CommitMove,
};

struct PrimaryActionState {
  HudActionKind kind = HudActionKind::Wait;
  std::string label = "Select unit";
  std::string detail = "Click a unit/city, or press N for next unit";
  bool enabled = false;
  bool end_turn_ready = false;
};

struct BottomActionBarLayout {
  bool visible = false;
  SDL_FRect action_orb{};
  SDL_FRect action_hit{};
  PrimaryActionState action{};
  int pending_unit_count = 0;
};

[[nodiscard]] PrimaryActionState compute_primary_action_state(
    GameSession const& session, GuiState const& gui, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id);
[[nodiscard]] BottomActionBarLayout compute_bottom_action_bar_layout(
    GameSession const& session, GuiState const& gui, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id, int win_w, int win_h);
void draw_bottom_action_bar(SDL_Renderer* ren, BottomActionBarLayout const& layout,
                            bool hovered = false);
void clear_move_command_mode(GuiState* gui);
void advance_hud_action_queue(GameSession& session, GuiState* gui, View* view,
                                std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                int win_w, int win_h);
void on_hud_action_click(GameSession& session, GuiState* gui, View* view,
                         std::optional<int>* sel_unit, std::optional<int>* sel_city,
                         PrimaryActionState const& action, std::optional<HexCoord> hovered_hex,
                         int win_w, int win_h, SdlSessionFlow const* flow);
[[nodiscard]] int count_units_needing_manual_orders(GameSession const& session, GuiState const& gui);
void on_primary_action_sdl(GameSession& session, GuiState* gui, View* view,
                           std::optional<int>* sel_unit, std::optional<int>* sel_city,
                           std::optional<HexCoord> hovered_hex, int win_w, int win_h,
                           SdlSessionFlow const* flow);
bool try_handle_bottom_action_bar_click(GameSession& session, GuiState* gui, View* view,
                                        std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                        BottomActionBarLayout const& layout, float mx, float my,
                                        std::optional<HexCoord> hovered_hex, int win_w, int win_h,
                                        SdlSessionFlow const* flow);
void draw_hotkeys_overlay(SDL_Renderer* ren, int win_w, int win_h, float* out_below_y);
bool commit_move_command(GameSession& session, GuiState* gui, std::optional<int>* selected_unit_id,
                         std::optional<int>* selected_city_id, std::optional<HexCoord> dest,
                         View* view, int win_w, int win_h);
[[nodiscard]] std::string sdl_turn_stats_hud_line(GameSession const& session, GuiState const& gui);
[[nodiscard]] std::size_t path_last_index_reachable_this_turn(
    Unit const& unit, std::vector<HexCoord> const& path, GameSession const& session);
void append_event_log(GuiState* gui, std::string line);
void trigger_combat_flash(GuiState* gui, HexCoord h);
void present_action_feedback(GuiState* gui, std::string msg, Uint64 toast_ms = 4800,
                             std::optional<HexCoord> combat_flash = std::nullopt);
void toggle_weather_legend(GuiState* gui);
void toggle_claim_legend(GuiState* gui);
void toggle_minimap_own_claims(GuiState* gui);
[[nodiscard]] std::optional<std::vector<HexCoord>> projected_path_to(GameSession const& session,
                                                                     Unit const& unit,
                                                                     HexCoord dest);
[[nodiscard]] int projected_path_turns(Unit const& unit, std::vector<HexCoord> const& path,
                                       GameSession const& session);
void ensure_event_log_seeded(GameSession const& session, GuiState* gui);
[[nodiscard]] float play_ui_max_panel_bottom(int win_h, GuiState const* gui) noexcept;
void draw_event_log_panel(SDL_Renderer* ren, EventLogLayout const& layout, GuiState const& gui);

void draw_top_hud(SDL_Renderer* ren, GameSession const& session, GuiState const* gui, int win_w) {
  float const h = k_top_bar_h;
  SDL_SetRenderDrawColorFloat(ren, 0.055f, 0.09f, 0.14f, 0.96f);
  SDL_FRect bar{0.f, 0.f, static_cast<float>(win_w), h};
  SDL_RenderFillRect(ren, &bar);
  SDL_SetRenderDrawColorFloat(ren, 0.28f, 0.42f, 0.58f, 0.7f);
  SDL_RenderLine(ren, 0.f, h, static_cast<float>(win_w), h);

  Player const* cur = nullptr;
  for (Player const& p : session.players()) {
    if (p.seat == session.current_player().seat) {
      cur = &p;
      break;
    }
  }
  std::string left = cur ? cur->name : ("P" + std::to_string(session.current_player().seat));
  if (session.current_player().computer) {
    left += " (AI)";
  }

  std::string mid;
  if (session.is_over()) {
    mid = "Game over";
    if (auto w = session.winner_seat()) {
      if (Player const* wp = player_by_seat(session, *w)) {
        mid += " - " + wp->name + " wins";
      } else {
        mid += " - Winner: " + std::to_string(*w);
      }
    }
  } else {
    int const cs = session.current_player().seat;
    mid = "Round " + std::to_string(session.round()) + " - " +
          std::to_string(session.city_count_for(cs)) + "/" +
          std::to_string(GameSession::CITIES_TO_WIN) + " cities | " +
          std::to_string(session.unit_count_for(cs)) + " units | your turn";
    if (count_human_players(session) > 1) {
      mid += " | hot seat";
    }
  }

  int empire_food = 0;
  int empire_prod = 0;
  int empire_gold = 0;
  if (!session.is_over()) {
    int const cs = session.current_player().seat;
    for (City const& c : session.cities()) {
      if (c.owner_seat() != cs) {
        continue;
      }
      CityYield const y = session.city_yield(c);
      empire_food += y.food;
      empire_prod += y.production;
      empire_gold += y.gold;
    }
  }

  std::string right = "Gold " + std::to_string(session.gold_for(session.current_player().seat));
  if (!session.is_over()) {
    right += "  +" + std::to_string(empire_food) + "f +" + std::to_string(empire_prod) + "p +" +
             std::to_string(empire_gold) + "g";
  }

  menu_ui::draw_text(ren, 12.f, 10.f, left.c_str(), menu_ui::k_title);
  float const mid_x = std::max(160.f, static_cast<float>(win_w) * 0.5f - 140.f);
  float const right_w = menu_ui::text_width(right.c_str());
  float const right_start = static_cast<float>(win_w) - right_w - 56.f;
  float const mid_max_w = right_start - mid_x - 16.f;
  if (mid_max_w > 72.f) {
    int const mid_cols = std::max(24, static_cast<int>(mid_max_w / 7.f));
    mid = menu_ui::truncate_line(std::move(mid), mid_cols);
  }
  menu_ui::draw_text(ren, mid_x, 10.f, mid.c_str(), menu_ui::k_accent);
  menu_ui::draw_text(ren, right_start, 10.f, right.c_str(), menu_ui::k_hint);

  std::string era = std::string(season_label(session.season())) + " | ";
  era += session.calendar_era_label();
  if (!session.current_player().computer && !session.is_over() &&
      session.current_player_has_auto_explore_blocked_with_moves()) {
    era += "  [explore blocked]";
  }
  era = menu_ui::truncate_line(std::move(era), 58);
  menu_ui::draw_text(ren, 12.f, 34.f, era.c_str(), menu_ui::k_muted);
  if (gui != nullptr && !session.is_over() && !session.current_player().computer) {
    std::string const stats = sdl_turn_stats_hud_line(session, *gui);
    if (!stats.empty()) {
      float const stats_x = std::max(240.f, static_cast<float>(win_w) * 0.34f);
      menu_ui::draw_text(ren, stats_x, 34.f, stats.c_str(), menu_ui::k_hint);
    }
  }
  std::string wx = session.weather_hud_summary();
  wx = menu_ui::truncate_line(std::move(wx), std::max(28, static_cast<int>(win_w / 9)));
  float const wx_x = std::max(200.f, static_cast<float>(win_w) * 0.48f);
  menu_ui::draw_text(ren, wx_x, 34.f, wx.c_str(), menu_ui::k_muted, 0.92f);
}

void draw_inspector_panel_bg(SDL_Renderer* ren, SDL_FRect panel) {
  menu_ui::fill_rect(ren, panel, rgb_u8(0x06, 0x0a, 0x12), 0.92f);
  menu_ui::stroke_rect(ren, panel, menu_ui::k_modal_line, 0.75f);
}

void draw_inspector_action_btn(SDL_Renderer* ren, SDL_FRect r, char const* label, bool enabled,
                               bool active = false) {
  RGB fill = active ? rgb_u8(0x2a, 0x4a, 0x38) : menu_ui::k_secondary;
  RGB border = active ? menu_ui::k_primary : menu_ui::k_secondary_line;
  RGB text = active ? menu_ui::k_ok : menu_ui::k_ink;
  if (!enabled && !active) {
    fill = rgb_u8(0x1a, 0x22, 0x2e);
    text = menu_ui::k_disabled;
  }
  menu_ui::draw_button(ren, r, label, fill, border, text, enabled, active);
}

[[nodiscard]] bool inspector_draw_text(SDL_Renderer* ren, float tx, float* ty, float max_y,
                                       char const* text, RGB c, float line_h = 16.f,
                                       float alpha = 1.f) {
  if (*ty + line_h > max_y) {
    return false;
  }
  menu_ui::draw_text(ren, tx, *ty, text, c, alpha);
  *ty += line_h;
  return true;
}

[[nodiscard]] std::string sdl_settle_preview_short(GameSession const& session, HexCoord h,
                                                   int owner_seat) {
  if (!session.map().contains(h) || !session.visited_for(owner_seat).count(h)) {
    return {};
  }
  if (!can_found_city_on(session.terrain_effective_at(h))) {
    return {};
  }
  CityYield const y1 = session.preview_city_yield_at(h, 1, CityFocus::BALANCED);
  CityYield const y2 = session.preview_city_yield_at(h, 2, CityFocus::BALANCED);
  CityYield const y1r = session.preview_city_yield_realistic(h, 1, CityFocus::BALANCED, owner_seat);
  return "City yield (balanced): pop1 +" + std::to_string(y1.food) + "f +" +
         std::to_string(y1.production) + "p +" + std::to_string(y1.gold) + "g · pop2 +" +
         std::to_string(y2.food) + "f +" + std::to_string(y2.production) + "p +" +
         std::to_string(y2.gold) + "g · realistic pop1 +" + std::to_string(y1r.food) + "f +" +
         std::to_string(y1r.production) + "p +" + std::to_string(y1r.gold) + "g";
}

[[nodiscard]] UnitInspectorLayout compute_unit_inspector_layout(
    GameSession const& session, std::optional<int> selected_unit_id, int cur_seat, int win_h,
    float max_panel_bottom_y) {
  UnitInspectorLayout layout;
  if (!selected_unit_id.has_value()) {
    return layout;
  }
  auto u = session.unit_by_id(*selected_unit_id);
  if (!u.has_value()) {
    return layout;
  }

  layout.visible = true;
  layout.unit_id = *selected_unit_id;
  layout.is_mine = u->owner_seat() == cur_seat && !session.is_over() &&
                   !session.current_player().computer;

  float const panel_x = 10.f;
  float const panel_y = k_top_bar_h + 6.f;
  float const panel_w = k_city_panel_w;
  float const desired_h = layout.is_mine ? 380.f : 220.f;
  float const panel_bottom = std::min(max_panel_bottom_y, panel_y + desired_h);
  float panel_h = std::max(150.f, panel_bottom - panel_y);
  layout.panel = {panel_x, panel_y, panel_w, panel_h};
  layout.content_max_y = layout.panel.y + layout.panel.h - 8.f;

  if (!layout.is_mine) {
    return layout;
  }

  if (u->kind() == UnitKind::SETTLER && !u->is_dead()) {
    layout.show_found_city = true;
  } else if (u->kind() == UnitKind::FARMER) {
    layout.show_cultivate = true;
    layout.show_clear_forest = true;
    layout.show_build_farm = true;
  } else if (u->kind() == UnitKind::BUILDER) {
    layout.show_build_mine = true;
  } else if (u->kind() == UnitKind::HUNTING_PARTY) {
    layout.show_rebase = true;
    if (auto beast = session.wild_animal_at(u->coord());
        beast.has_value() && !beast->is_dead()) {
      layout.show_hunt = true;
    }
  }

  float const btn_w = (panel_w - 24.f - k_inspector_btn_gap) * 0.5f;
  float const btn_x0 = panel_x + 12.f;
  float const btn_x1 = btn_x0 + btn_w + k_inspector_btn_gap;
  float const full_w = panel_w - 24.f;
  float y = panel_y + panel_h - 8.f;

  auto place_full = [&](SDL_FRect& slot, bool show) {
    if (!show) {
      return;
    }
    y -= k_inspector_btn_h;
    slot = {btn_x0, y, full_w, k_inspector_btn_h};
    y -= k_inspector_btn_gap;
  };
  auto place_row = [&](SDL_FRect& left, SDL_FRect& right) {
    y -= k_inspector_btn_h;
    left = {btn_x0, y, btn_w, k_inspector_btn_h};
    right = {btn_x1, y, btn_w, k_inspector_btn_h};
    y -= k_inspector_btn_gap;
  };

  place_full(layout.hunt_btn, layout.show_hunt);
  place_full(layout.rebase_btn, layout.show_rebase);
  place_full(layout.build_mine_btn, layout.show_build_mine);
  place_full(layout.build_farm_btn, layout.show_build_farm);
  place_full(layout.clear_forest_btn, layout.show_clear_forest);
  place_full(layout.cultivate_btn, layout.show_cultivate);
  place_full(layout.found_city_btn, layout.show_found_city);
  place_row(layout.explore_btn, layout.sleep_btn);
  place_row(layout.skip_btn, layout.fortify_btn);
  layout.content_max_y = layout.skip_btn.y - 20.f;

  float btn_bottom = panel_y;
  auto track_btn = [&](SDL_FRect const& r) { btn_bottom = std::max(btn_bottom, r.y + r.h); };
  track_btn(layout.skip_btn);
  track_btn(layout.fortify_btn);
  track_btn(layout.explore_btn);
  track_btn(layout.sleep_btn);
  if (layout.show_found_city) {
    track_btn(layout.found_city_btn);
  }
  if (layout.show_cultivate) {
    track_btn(layout.cultivate_btn);
    track_btn(layout.clear_forest_btn);
    track_btn(layout.build_farm_btn);
  }
  if (layout.show_build_mine) {
    track_btn(layout.build_mine_btn);
  }
  if (layout.show_rebase) {
    track_btn(layout.rebase_btn);
  }
  if (layout.show_hunt) {
    track_btn(layout.hunt_btn);
  }
  float const used_bottom = btn_bottom + 12.f;
  float const max_h = std::max(120.f, max_panel_bottom_y - panel_y);
  layout.panel.h = std::clamp(used_bottom - panel_y, 120.f, max_h);
  float const panel_max_y = layout.panel.y + layout.panel.h;
  if (used_bottom > panel_max_y) {
    float const shift = used_bottom - panel_max_y;
    auto shift_btn = [&](SDL_FRect& btn) { btn.y -= shift; };
    shift_btn(layout.skip_btn);
    shift_btn(layout.fortify_btn);
    shift_btn(layout.explore_btn);
    shift_btn(layout.sleep_btn);
    if (layout.show_found_city) {
      shift_btn(layout.found_city_btn);
    }
    if (layout.show_cultivate) {
      shift_btn(layout.cultivate_btn);
      shift_btn(layout.clear_forest_btn);
      shift_btn(layout.build_farm_btn);
    }
    if (layout.show_build_mine) {
      shift_btn(layout.build_mine_btn);
    }
    if (layout.show_rebase) {
      shift_btn(layout.rebase_btn);
    }
    if (layout.show_hunt) {
      shift_btn(layout.hunt_btn);
    }
  }
  layout.content_max_y =
      std::min(layout.skip_btn.y - 20.f, layout.panel.y + layout.panel.h - 36.f);
  static_cast<void>(y);
  static_cast<void>(win_h);
  return layout;
}

void draw_unit_inspector(SDL_Renderer* ren, GameSession const& session,
                         UnitInspectorLayout const& layout, int cur_seat) {
  if (!layout.visible) {
    return;
  }
  auto u = session.unit_by_id(layout.unit_id);
  if (!u.has_value()) {
    return;
  }

  draw_inspector_panel_bg(ren, layout.panel);
  float ty = layout.panel.y + 10.f;
  float const tx = layout.panel.x + 12.f;
  float const max_y = layout.content_max_y;
  if (!inspector_draw_text(ren, tx, &ty, max_y, "Unit", menu_ui::k_accent, 18.f)) {
    return;
  }

  std::string title = tack::strat::unit_kind_display_name(u->kind());
  if (!inspector_draw_text(ren, tx, &ty, max_y, title.c_str(), menu_ui::k_title, 18.f)) {
    return;
  }

  std::string own =
      u->owner_seat() == cur_seat ? std::string("Yours") : std::string("Seat ") + std::to_string(u->owner_seat());
  if (!inspector_draw_text(ren, tx, &ty, max_y, own.c_str(), menu_ui::k_hint)) {
    return;
  }

  std::string hp_line = "HP " + std::to_string(u->hp()) + "/" + std::to_string(u->max_hp());
  if (!inspector_draw_text(ren, tx, &ty, max_y, hp_line.c_str(), menu_ui::k_title)) {
    return;
  }

  int const mm = tack::strat::unit_movement(u->kind());
  std::string mv =
      "Moves " + std::to_string(u->moves_remaining()) + "/" + std::to_string(std::max(1, mm));
  if (!inspector_draw_text(ren, tx, &ty, max_y, mv.c_str(), menu_ui::k_title)) {
    return;
  }

  std::string flags =
      std::string("Explore ") + (u->auto_explore() ? "on" : "off") + "   Sleep " +
      (u->sleeping() ? "on" : "off");
  if (!inspector_draw_text(ren, tx, &ty, max_y, flags.c_str(), menu_ui::k_hint)) {
    return;
  }

  std::vector<HexCoord> const route = session.planned_route_for(layout.unit_id);
  if (!route.empty()) {
    std::string rq = "Queued route: " + std::to_string(route.size()) + " hex";
    if (route.size() != 1) {
      rq += "es";
    }
    if (u->moves_remaining() > 0) {
      std::size_t const reach = path_last_index_reachable_this_turn(*u, route, session);
      if (reach > 0) {
        rq += " (" + std::to_string(reach) + " this turn)";
      }
    } else {
      rq += " (continues next turn)";
    }
    if (!inspector_draw_text(ren, tx, &ty, max_y, rq.c_str(), menu_ui::k_hint, 15.f)) {
      return;
    }
  }
  if (u->carried_food() > 0) {
    std::string cf = "Carrying food: " + std::to_string(u->carried_food());
    if (!inspector_draw_text(ren, tx, &ty, max_y, cf.c_str(), menu_ui::k_title, 15.f)) {
      return;
    }
  }
  if (u->kind() == UnitKind::SETTLER && u->owner_seat() == cur_seat && !u->is_dead()) {
    if (session.can_found_city(*u)) {
      if (!inspector_draw_text(ren, tx, &ty, max_y, "Can found city on this tile (B)", menu_ui::k_ok,
                               15.f)) {
        return;
      }
    } else if (auto why = session.explain_cannot_found_city(*u)) {
      std::string w = "Cannot found: " + menu_ui::truncate_line(*why, 36);
      if (!inspector_draw_text(ren, tx, &ty, max_y, w.c_str(), menu_ui::k_err, 15.f, 0.9f)) {
        return;
      }
    }
    if (auto preview = sdl_settle_preview_short(session, u->coord(), cur_seat); !preview.empty()) {
      if (inspector_draw_text(ren, tx, &ty, max_y, "Found city preview", menu_ui::k_accent, 16.f)) {
        std::string line1 = menu_ui::truncate_line(preview, 44);
        static_cast<void>(inspector_draw_text(ren, tx, &ty, max_y, line1.c_str(), menu_ui::k_hint, 15.f));
      }
    }
  }

  if (layout.is_mine) {
    float const actions_y = layout.explore_btn.y - 14.f;
    if (actions_y > layout.panel.y + 10.f) {
      menu_ui::draw_text(ren, tx, actions_y, "Actions", menu_ui::k_accent);
    }
    bool const has_moves = u->moves_remaining() > 0;
    draw_inspector_action_btn(ren, layout.skip_btn, "Skip (Space)", true);
    draw_inspector_action_btn(ren, layout.fortify_btn, "Fortify (F)", has_moves);
    draw_inspector_action_btn(ren, layout.explore_btn, u->auto_explore() ? "Explore (on)" : "Explore",
                              true, u->auto_explore());
    draw_inspector_action_btn(ren, layout.sleep_btn, u->sleeping() ? "Wake" : "Sleep", true,
                              u->sleeping());
    if (layout.show_found_city) {
      draw_inspector_action_btn(ren, layout.found_city_btn, "Found city (B)",
                                session.can_found_city(*u));
    }
    if (layout.show_cultivate) {
      draw_inspector_action_btn(ren, layout.cultivate_btn, "Cultivate tile", has_moves);
      draw_inspector_action_btn(ren, layout.clear_forest_btn, "Clear forest", has_moves);
      draw_inspector_action_btn(ren, layout.build_farm_btn, "Build farm", has_moves);
    }
    if (layout.show_build_mine) {
      draw_inspector_action_btn(ren, layout.build_mine_btn, "Build mine (hill)", has_moves);
    }
    if (layout.show_rebase) {
      draw_inspector_action_btn(ren, layout.rebase_btn, "Rebase at city", true);
    }
    if (layout.show_hunt) {
      draw_inspector_action_btn(ren, layout.hunt_btn, "Hunt wildlife", has_moves);
    }
  }
}

[[nodiscard]] CityInspectorLayout compute_city_inspector_layout(
    GameSession const& session, std::optional<int> selected_city_id, int win_w, int win_h,
    bool show_production_drawer, float max_panel_bottom_y) {
  CityInspectorLayout layout;
  if (!selected_city_id.has_value()) {
    return layout;
  }
  auto c = session.city_by_id(*selected_city_id);
  if (!c.has_value()) {
    return layout;
  }

  layout.visible = true;
  layout.city_id = *selected_city_id;
  layout.is_mine = c->owner_seat() == session.current_player().seat && !session.is_over() &&
                   !session.current_player().computer;

  float const right_margin = show_production_drawer ? k_production_drawer_w : 0.f;
  float const panel_x =
      static_cast<float>(win_w) - k_city_panel_w - k_city_panel_margin - right_margin;
  float const panel_y = k_top_bar_h + 6.f;
  float const desired_h = layout.is_mine ? 520.f : 360.f;
  float const panel_bottom = std::min(max_panel_bottom_y, panel_y + desired_h);
  float panel_h = std::max(180.f, panel_bottom - panel_y);
  layout.panel = {panel_x, panel_y, k_city_panel_w, panel_h};
  layout.content_max_y = layout.panel.y + layout.panel.h - 8.f;

  if (!layout.is_mine) {
    return layout;
  }

  float const btn_w = (k_city_panel_w - 24.f - k_inspector_btn_gap) * 0.5f;
  float const btn_x0 = panel_x + 12.f;
  float const btn_x1 = btn_x0 + btn_w + k_inspector_btn_gap;
  float const full_w = k_city_panel_w - 24.f;
  float y = panel_y + panel_h - 8.f;

  for (int i = 2; i >= 0; --i) {
    y -= k_inspector_btn_h;
    layout.building_buttons[static_cast<std::size_t>(i)] = {btn_x0, y, full_w, k_inspector_btn_h};
    y -= k_inspector_btn_gap;
  }
  y -= 8.f;
  for (int row = 1; row >= 0; --row) {
    y -= k_inspector_btn_h;
    float const row_y = y;
    layout.focus_buttons[static_cast<std::size_t>(row * 2)] = {btn_x0, row_y, btn_w,
                                                              k_inspector_btn_h};
    layout.focus_buttons[static_cast<std::size_t>(row * 2 + 1)] = {btn_x1, row_y, btn_w,
                                                                    k_inspector_btn_h};
    y -= k_inspector_btn_gap;
  }
  float btn_bottom = panel_y;
  for (SDL_FRect const& btn : layout.focus_buttons) {
    btn_bottom = std::max(btn_bottom, btn.y + btn.h);
  }
  for (SDL_FRect const& btn : layout.building_buttons) {
    btn_bottom = std::max(btn_bottom, btn.y + btn.h);
  }
  float const used_bottom = btn_bottom + 12.f;
  float const max_h = std::max(180.f, max_panel_bottom_y - panel_y);
  layout.panel.h = std::clamp(used_bottom - panel_y, 180.f, max_h);
  float const panel_max_y = layout.panel.y + layout.panel.h;
  if (used_bottom > panel_max_y) {
    float const shift = used_bottom - panel_max_y;
    for (SDL_FRect& btn : layout.focus_buttons) {
      btn.y -= shift;
    }
    for (SDL_FRect& btn : layout.building_buttons) {
      btn.y -= shift;
    }
  }
  layout.content_max_y =
      std::min(layout.focus_buttons[0].y - 20.f, layout.panel.y + layout.panel.h - 36.f);
  static_cast<void>(win_h);
  return layout;
}

void draw_city_inspector(SDL_Renderer* ren, GameSession const& session,
                         CityInspectorLayout const& layout, bool show_production_drawer) {
  if (!layout.visible) {
    return;
  }
  auto c = session.city_by_id(layout.city_id);
  if (!c.has_value()) {
    return;
  }

  draw_inspector_panel_bg(ren, layout.panel);
  float ty = layout.panel.y + 10.f;
  float const tx = layout.panel.x + 12.f;
  float const panel_w = layout.panel.w;
  float const max_y = layout.content_max_y;
  if (!inspector_draw_text(ren, tx, &ty, max_y, "City", menu_ui::k_accent, 18.f)) {
    return;
  }
  if (!inspector_draw_text(ren, tx, &ty, max_y, c->name().c_str(), menu_ui::k_title, 18.f)) {
    return;
  }

  std::string line =
      "Pop " + std::to_string(c->population()) + "  HP " + std::to_string(c->hp()) + "/" +
      std::to_string(c->max_hp());
  if (c->hunt_parties_away() > 0) {
    line += " (" + std::to_string(c->hunt_parties_away()) + " hunting)";
  }
  if (!inspector_draw_text(ren, tx, &ty, max_y, line.c_str(), menu_ui::k_title)) {
    return;
  }
  line = std::string("Focus: ") + city_focus_label(c->focus());
  if (!inspector_draw_text(ren, tx, &ty, max_y, line.c_str(), menu_ui::k_hint)) {
    return;
  }

  CityYield const yld = session.city_yield(*c);
  line = std::string("Yield/turn +") + std::to_string(yld.food) + " food  +" +
         std::to_string(yld.production) + " prod  +" + std::to_string(yld.gold) + " gold";
  if (!inspector_draw_text(ren, tx, &ty, max_y, line.c_str(), menu_ui::k_title)) {
    return;
  }

  line = "Production: ";
  RGB prod_color = menu_ui::k_title;
  if (auto kb = c->current_build()) {
    line += tack::strat::unit_kind_display_name(*kb);
    line += " (" + std::to_string(c->production_stored()) + "/" +
            std::to_string(std::max(1, tack::strat::unit_production_cost(*kb))) + ")";
  } else if (layout.is_mine) {
    line += "(choose in Build panel)";
    prod_color = rgb_u8(255, 160, 60);
  } else {
    line += "(none)";
  }
  if (!inspector_draw_text(ren, tx, &ty, max_y, line.c_str(), prod_color)) {
    return;
  }

  if (auto kb = c->current_build()) {
    if (ty + 18.f <= max_y) {
      int const cost = std::max(1, tack::strat::unit_production_cost(*kb));
      int const stored = c->production_stored();
      float const bar_w = panel_w - 24.f;
      float const bar_h = 10.f;
      SDL_SetRenderDrawColorFloat(ren, 0.08f, 0.12f, 0.18f, 1.f);
      SDL_FRect bar_bg{tx, ty, bar_w, bar_h};
      SDL_RenderFillRect(ren, &bar_bg);
      float const fill_w =
          bar_w * static_cast<float>(std::min(stored, cost)) / static_cast<float>(cost);
      SDL_SetRenderDrawColorFloat(ren, 0.35f, 0.75f, 0.45f, 1.f);
      SDL_FRect bar_fill{tx, ty, fill_w, bar_h};
      SDL_RenderFillRect(ren, &bar_fill);
      menu_ui::stroke_rect(ren, bar_bg, menu_ui::k_modal_line, 0.8f);
      ty += bar_h + 8.f;
    }
  }

  auto q = c->queued_builds();
  if (!q.empty()) {
    if (inspector_draw_text(ren, tx, &ty, max_y, "Queue:", menu_ui::k_hint, 15.f)) {
      int n = 0;
      for (UnitKind k : q) {
        if (n >= 4) {
          static_cast<void>(inspector_draw_text(ren, tx, &ty, max_y, "...", menu_ui::k_hint, 14.f));
          break;
        }
        std::string ql = "  - ";
        ql += tack::strat::unit_kind_display_name(k);
        if (!inspector_draw_text(ren, tx, &ty, max_y, ql.c_str(), menu_ui::k_hint, 14.f)) {
          break;
        }
        ++n;
      }
    }
  }

  if (!layout.is_mine) {
    std::vector<CityBuilding> buildings(c->buildings_set().begin(), c->buildings_set().end());
    if (!buildings.empty()) {
      std::string bline = "Buildings: ";
      for (std::size_t i = 0; i < buildings.size(); ++i) {
        if (i != 0) {
          bline += ", ";
        }
        bline += tack::strat::city_building_label(buildings[i]);
      }
      static_cast<void>(inspector_draw_text(ren, tx, &ty, max_y, bline.c_str(), menu_ui::k_hint));
    }
  }

  line = "Food stored: " + std::to_string(c->food_stored()) + " / " +
         std::to_string(c->growth_threshold()) + " to grow";
  int const upkeep = 1 + c->population();
  int const surplus = yld.food - upkeep;
  if (surplus <= 0) {
    line += " (stagnant)";
  } else {
    int const need = std::max(0, c->growth_threshold() - c->food_stored());
    int const turns = (need + surplus - 1) / surplus;
    line += " (" + std::to_string(turns) + "t)";
  }
  if (inspector_draw_text(ren, tx, &ty, max_y, line.c_str(), menu_ui::k_title)) {
    if (auto st = session.explain_food_stagnation(*c); st.has_value()) {
      static_cast<void>(inspector_draw_text(ren, tx, &ty, max_y,
                                            menu_ui::truncate_line(*st, 42).c_str(), menu_ui::k_err,
                                            15.f, 0.92f));
    }
  }

  if (layout.is_mine) {
    float const focus_label_y = layout.focus_buttons[0].y - 14.f;
    if (focus_label_y > layout.panel.y + 80.f) {
      menu_ui::draw_text(ren, tx, focus_label_y, "City focus", menu_ui::k_accent);
    }
    int const seat_gold = session.gold_for(session.current_player().seat);
    for (int i = 0; i < 4; ++i) {
      CityFocus const focus = k_city_focuses[static_cast<std::size_t>(i)];
      bool const active = c->focus() == focus;
      std::string label = k_city_focus_short_labels[i];
      if (active) {
        label += " *";
      }
      draw_inspector_action_btn(ren, layout.focus_buttons[static_cast<std::size_t>(i)],
                                  label.c_str(), !active, active);
    }
    ty = layout.building_buttons[0].y - 14.f;
    if (ty > layout.focus_buttons[0].y + k_inspector_btn_h + 4.f) {
      menu_ui::draw_text(ren, tx, ty, "Buildings (gold)", menu_ui::k_accent);
    }
    for (int i = 0; i < 3; ++i) {
      CityBuilding const building = k_city_buildings[static_cast<std::size_t>(i)];
      bool const owned = c->has_building(building);
      int const cost = tack::strat::city_building_gold_cost(building);
      std::string blabel = tack::strat::city_building_label(building);
      if (owned) {
        blabel += " (Built)";
      } else {
        blabel += " (" + std::to_string(cost) + "g)";
      }
      bool const can_buy = !owned && seat_gold >= cost;
      draw_inspector_action_btn(ren, layout.building_buttons[static_cast<std::size_t>(i)],
                                  blabel.c_str(), can_buy, owned);
    }
  }
}

[[nodiscard]] ProductionNeedsLayout compute_production_needs_layout(
    GameSession const& session, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id, int win_h, float max_panel_bottom_y) {
  ProductionNeedsLayout layout;
  if (selected_unit_id.has_value() || selected_city_id.has_value() || session.is_over() ||
      session.current_player().computer || !sdl_player_has_city_without_production(session)) {
    return layout;
  }

  int const seat = session.current_player().seat;
  std::vector<City const*> lacking;
  for (City const& c : session.cities()) {
    if (c.owner_seat() == seat && !c.current_build().has_value()) {
      lacking.push_back(&c);
    }
  }
  if (lacking.empty()) {
    return layout;
  }
  std::sort(lacking.begin(), lacking.end(),
            [](City const* a, City const* b) { return city_name_less_ci(a->name(), b->name()); });

  float const panel_x = 10.f;
  float const panel_y = k_top_bar_h + 6.f;
  float const panel_w = k_city_panel_w;
  float btn_y = panel_y + 36.f;
  for (City const* c : lacking) {
    layout.jump_buttons.push_back({panel_x + 12.f, btn_y, panel_w - 24.f, k_inspector_btn_h});
    layout.city_ids.push_back(c->id());
    btn_y += k_inspector_btn_h + k_inspector_btn_gap;
    if (layout.jump_buttons.size() >= 6) {
      break;
    }
  }
  float panel_h = btn_y + 8.f - panel_y;
  float const max_h = max_panel_bottom_y - panel_y;
  if (panel_h > max_h && max_h > 120.f) {
    panel_h = max_h;
  }
  layout.visible = true;
  layout.panel = {panel_x, panel_y, panel_w, panel_h};
  static_cast<void>(win_h);
  return layout;
}

void draw_production_needs_panel(SDL_Renderer* ren, GameSession const& session,
                                 ProductionNeedsLayout const& layout) {
  if (!layout.visible) {
    return;
  }
  draw_inspector_panel_bg(ren, layout.panel);
  float const tx = layout.panel.x + 12.f;
  std::string const header =
      "Cities need production (" + std::to_string(layout.jump_buttons.size()) + ")";
  menu_ui::draw_text(ren, tx, layout.panel.y + 10.f, header.c_str(), menu_ui::k_accent);
  int const seat = session.current_player().seat;
  for (std::size_t i = 0; i < layout.jump_buttons.size(); ++i) {
    auto c = session.city_by_id(layout.city_ids[i]);
    if (!c.has_value()) {
      continue;
    }
    std::string label = "Choose for " + c->name();
    draw_inspector_action_btn(ren, layout.jump_buttons[i], label.c_str(), true);
    static_cast<void>(seat);
  }
}

void draw_production_drawer(SDL_Renderer* ren, GameSession const& session, City const& c,
                            ProductionDrawerLayout const& layout) {
  if (!layout.visible) {
    return;
  }

  menu_ui::fill_rect(ren, layout.panel, rgb_u8(0x06, 0x0a, 0x12), 0.92f);
  menu_ui::stroke_rect(ren, layout.panel, menu_ui::k_accent, 0.55f);

  float const tx = layout.panel.x + 10.f;
  float ty = layout.panel.y + 10.f;
  menu_ui::draw_text(ren, tx, ty, "Build", menu_ui::k_accent);
  ty += 18.f;
  menu_ui::draw_text(ren, tx, ty, "Shift+click to queue", menu_ui::k_hint, 0.85f);

  CityYield const yld = session.city_yield(c);
  int const prod = yld.production;
  char const* labels[] = {"Settler", "Scout", "Warrior", "Farmer", "Builder", "Hunting Party"};

  for (int i = 0; i < 6; ++i) {
    UnitKind const kind = k_production_kinds[static_cast<std::size_t>(i)];
    int const cost = tack::strat::unit_production_cost(kind);
    int const turns = turns_to_complete_unit(cost, c.production_stored(), prod);
    bool const active = c.current_build() == kind;
    bool const can_build = kind != UnitKind::HUNTING_PARTY || c.population() >= 2;

    std::string label = std::string(1, static_cast<char>('1' + i)) + " ";
    label += labels[i];
    if (active) {
      label += " (Active)";
    } else if (!can_build) {
      label += " (need 2 pop)";
    } else {
      label += " (" + std::to_string(turns) + "t)";
    }

    SDL_FRect const btn = layout.build_buttons[static_cast<std::size_t>(i)];
    RGB fill = active ? rgb_u8(0x2a, 0x4a, 0x38) : menu_ui::k_secondary;
    RGB border = active ? menu_ui::k_primary : menu_ui::k_secondary_line;
    RGB text = active ? menu_ui::k_ok : menu_ui::k_ink;
    bool const enabled = !active && can_build;
    if (!enabled && !active) {
      fill = rgb_u8(0x1a, 0x22, 0x2e);
      text = menu_ui::k_disabled;
    }
    menu_ui::draw_button(ren, btn, label.c_str(), fill, border, text, enabled, active);
    if (active && can_build) {
      float const frac = static_cast<float>(c.production_stored()) /
                         static_cast<float>(std::max(1, cost));
      SDL_FRect prog{btn.x + 2.f, btn.y + btn.h - 4.f, (btn.w - 4.f) * std::clamp(frac, 0.f, 1.f),
                     3.f};
      menu_ui::fill_rect(ren, prog, menu_ui::k_ok, 0.85f);
    }
  }
}

void draw_footer_bar(SDL_Renderer* ren, int win_w, int win_h) {
  float const y0 = static_cast<float>(win_h) - k_footer_bar_h;
  SDL_SetRenderDrawColorFloat(ren, 0.028f, 0.052f, 0.085f, 0.9f);
  SDL_FRect foot{0.f, y0, static_cast<float>(win_w), k_footer_bar_h};
  SDL_RenderFillRect(ren, &foot);
  SDL_SetRenderDrawColorFloat(ren, 0.24f, 0.36f, 0.5f, 0.55f);
  SDL_RenderLine(ren, 0.f, y0, static_cast<float>(win_w), y0);
}

void draw_hotkeys_overlay(SDL_Renderer* ren, int win_w, int win_h, float* out_below_y) {
  static char const* const hotkey_lines[] = {
      "F1 / Shift+? - toggle this overlay",
      "Enter / P - trigger the action button (move, next unit, production, or end turn)",
      "Action button (circle, bottom-right) - context changes with selection and turn state",
      "Space - skip selected unit (cycles to next when auto-cycle is on)",
      "Esc - cancel move mode / deselect",
      "Arrows - pan map (move mode: nudge destination)",
      "Trackpad - vertical zoom, horizontal pan; mouse wheel zoom",
      "+ / - - keyboard zoom    Shift+F - fit map    F11 - fullscreen",
      "Tab / N / . - next unit    Shift+Tab / , - previous",
      "Double-click map tile to center camera",
      "Home - center on selection    Backspace - clear queued route",
      "Shift+click map - queue multi-turn route without moving this turn",
      "M - move mode (cursor at screen edge pans map)    B - found city    F - fortify    Z - sleep    P - primary action",
      "U - toggle auto unit cycling (cycles when a unit runs out of moves)",
      "H - toggle settler recommendation lens (ranked city sites)",
      "W - toggle weather tint key (map overlay legend)",
      "L - toggle territory borders legend",
      "Shift+E - toggle auto-explore    1-6 - city production when city selected",
      "Ctrl/Cmd+Q - quit    Ctrl/Cmd+S - save    Ctrl/Cmd+O - load",
      "Esc - clear selection, then game menu    Menu button (top-right) - same menu",
      "Right-click - clear selection / cancel move mode",
      "Hot seat: handoff screen after end turn before next human",
  };
  float const panel_w = std::min(560.f, static_cast<float>(win_w) - 24.f);
  float const line_h = 16.f;
  float const panel_h = 36.f + line_h * static_cast<float>(sizeof(hotkey_lines) / sizeof(hotkey_lines[0]));
  float const panel_top = k_top_bar_h + 8.f;
  SDL_FRect card{12.f, panel_top, panel_w, panel_h};
  menu_ui::fill_rect(ren, {0.f, k_top_bar_h, static_cast<float>(win_w),
                           static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h},
                     menu_ui::k_ink, 0.42f);
  menu_ui::draw_modal_card(ren, card);
  menu_ui::draw_text(ren, card.x + 14.f, card.y + 12.f, "Hotkeys", menu_ui::k_accent);
  float y = card.y + 32.f;
  for (char const* line : hotkey_lines) {
    menu_ui::draw_text(ren, card.x + 14.f, y, line, menu_ui::k_hint);
    y += line_h;
  }
  if (out_below_y != nullptr) {
    *out_below_y = card.y + panel_h + 8.f;
  }
}

void draw_game_over_overlay(SDL_Renderer* ren, GameSession const& session, int win_w, int win_h) {
  if (!session.is_over()) {
    return;
  }
  float const y0 = k_top_bar_h;
  float const h = static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h;
  menu_ui::fill_rect(ren, {0.f, y0, static_cast<float>(win_w), h}, menu_ui::k_ink, 0.38f);

  float const panel_w = std::min(440.f, static_cast<float>(win_w) - 80.f);
  float const panel_h = 118.f;
  float const px = (static_cast<float>(win_w) - panel_w) * 0.5f;
  float const py = y0 + h * 0.5f - panel_h * 0.5f;
  menu_ui::draw_modal_card(ren, {px, py, panel_w, panel_h});

  menu_ui::draw_text_centered(ren, static_cast<float>(win_w) * 0.5f, py + 18.f, "GAME OVER",
                              menu_ui::k_victory_gold);
  std::string sub;
  if (auto w = session.winner_seat()) {
    if (Player const* wp = player_by_seat(session, *w)) {
      sub = wp->name + " controls " + std::to_string(GameSession::CITIES_TO_WIN) + " cities!";
    } else {
      sub = "Winner: seat " + std::to_string(*w);
    }
  } else {
    sub = "No winner recorded.";
  }
  sub = menu_ui::truncate_line(std::move(sub), 54);
  menu_ui::draw_text_centered(ren, static_cast<float>(win_w) * 0.5f, py + 44.f, sub.c_str(),
                              menu_ui::k_title);
  menu_ui::draw_text_centered(ren, static_cast<float>(win_w) * 0.5f, py + 78.f,
                              "Esc/M victory screen   R play again", menu_ui::k_hint);
}

void drain_cpu_turns(GameSession& session) {
  while (!session.is_over() && session.current_player().computer) {
    tack::strat::JavaRandom rng = tack::strat::seat_ai_rng(session);
    while (!session.is_over() && session.current_player().computer) {
      if (!tack::strat::seat_ai_tick(session, rng)) {
        static_cast<void>(session.end_turn());
        break;
      }
    }
  }
}

struct TurnTracker {
  int round{-1};
  int player_index{-1};
};

struct SeatCamera {
  float scale = 1.f;
  float offset_x = 0.f;
  float offset_y = 0.f;
};

struct GuiState {
  bool move_command_mode{};
  std::optional<HexCoord> move_cursor;
  bool move_cursor_locked{};
  std::unordered_set<int> skipped_units;
  std::string toast_text;
  Uint64 toast_until_ticks{};
  bool toast_is_warning = false;
  bool show_hotkeys{};
  bool request_save{};
  bool request_load{};
  /** Seat whose pan/zoom is currently shown; -1 before first human turn. */
  int camera_seat = -1;
  std::unordered_map<int, SeatCamera> seat_cameras;
  std::deque<std::string> event_log;
  bool event_log_seeded = false;
  bool event_log_expanded = false;
  int event_log_scroll_offset = 0;
  bool auto_focus_next_unit = true;
  bool auto_advance_in_progress = false;
  bool tips_dismissed = false;
  bool show_intro_tips = false;
  bool show_game_menu = false;
  bool request_main_menu = false;
  int last_wildlife_arrival_round_logged = -1;
  bool show_settler_lens = true;
  bool show_weather_legend = true;
  bool show_claim_legend = true;
  bool minimap_tint_own_claims_only = false;
  int settler_lens_unit_id = -1;
  int settler_lens_seat = -1;
  std::vector<SettlerLensHint> settler_lens_hints;
  std::optional<HexCoord> combat_flash_hex;
  Uint64 combat_flash_start_ticks = 0;
  Uint64 combat_flash_until_ticks = 0;
  Uint64 last_map_click_ticks = 0;
  std::optional<HexCoord> last_map_click_hex;
  HexCoord settler_lens_origin{0, 0};
};

void draw_move_command_overlay(SDL_Renderer* ren, GuiState const& gui, View const& v,
                               GameSession const& session, std::optional<int> selected_unit_id,
                               std::optional<HexCoord> hovered_hex, int win_w, int win_h) {
  if (!gui.move_command_mode || !selected_unit_id.has_value()) {
    return;
  }
  float const y0 = k_top_bar_h;
  float const map_h = static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h;
  SDL_FRect frame{4.f, y0 + 4.f, static_cast<float>(win_w) - 8.f, map_h - 8.f};
  // Border only — a full-viewport translucent fill looked like screen flicker when the camera panned.
  menu_ui::stroke_rect(ren, frame, rgb_u8(70, 210, 255), 0.72f);
  SDL_FRect inner{frame.x + 2.f, frame.y + 2.f, frame.w - 4.f, frame.h - 4.f};
  menu_ui::stroke_rect(ren, inner, rgb_u8(35, 140, 185), 0.38f);

  auto su = session.unit_by_id(*selected_unit_id);
  if (!su.has_value()) {
    return;
  }
  std::optional<HexCoord> dest = gui.move_cursor;
  if (!dest.has_value()) {
    dest = hovered_hex;
  }
  if (!dest.has_value() || *dest == su->coord()) {
    return;
  }
  float const cx = axial_to_world_x(*dest);
  float const cy = axial_to_world_y(*dest);
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx, cy, &sx, &sy);
  float const pin = std::max(10.f, 14.f * v.scale);
  SDL_SetRenderDrawColorFloat(ren, 1.f, 0.86f, 0.18f, 0.95f);
  SDL_FRect head{sx - pin * 0.5f, sy - pin * 1.1f, pin, pin};
  SDL_RenderFillRect(ren, &head);
  SDL_SetRenderDrawColorFloat(ren, 0.12f, 0.08f, 0.02f, 0.9f);
  SDL_RenderRect(ren, &head);
  SDL_SetRenderDrawColorFloat(ren, 1.f, 0.86f, 0.18f, 0.95f);
  SDL_FRect stem{sx - pin * 0.18f, sy - pin * 0.15f, pin * 0.36f, pin * 0.95f};
  SDL_RenderFillRect(ren, &stem);
}

void draw_play_status_chips(SDL_Renderer* ren, GuiState const& gui, GameSession const& session,
                            std::optional<int> selected_unit_id, int win_w) {
  if (session.is_over() || session.current_player().computer) {
    return;
  }
  float const x_chip_right = static_cast<float>(win_w) - 52.f - 8.f;
  float x = x_chip_right;
  auto chip = [&](char const* text, RGB color) {
    float const w = menu_ui::text_width(text) + 14.f;
    x -= w;
    if (x < 220.f) {
      return;
    }
    SDL_FRect r{x, 14.f, w, 18.f};
    menu_ui::fill_rect(ren, r, color, 0.22f);
    menu_ui::stroke_rect(ren, r, color, 0.72f);
    menu_ui::draw_text(ren, x + 7.f, 17.f, text, color);
    x -= 6.f;
  };
  if (!gui.skipped_units.empty()) {
    chip(("Skipped " + std::to_string(gui.skipped_units.size())).c_str(), menu_ui::k_muted);
  }
  if (gui.move_command_mode) {
    chip("Move mode", rgb_u8(70, 210, 255));
  }
  int const pending_orders = count_units_needing_manual_orders(session, gui);
  if (pending_orders > 0) {
    chip(("Orders " + std::to_string(pending_orders)).c_str(), rgb_u8(255, 140, 38));
  }
  if (gui.show_settler_lens && selected_unit_id.has_value()) {
    if (auto su = session.unit_by_id(*selected_unit_id);
        su.has_value() && su->kind() == UnitKind::SETTLER &&
        su->owner_seat() == session.current_player().seat) {
      chip("Settler lens", menu_ui::k_victory_gold);
    }
  }
}

enum class AppPhase { SetupMenu, HandoffGate, Playing, Victory };

struct StartMenuState {
  int player_count = 2;
  int edit_seat = 0;
  std::array<bool, 4> seat_cpu{};
  std::int64_t world_seed = 77'007;
  tack::strat::MapSizePreset map_size = tack::strat::MapSizePreset::Tiny;
  /** If non-zero and SDL_GetTicks() < this, Enter accepts an all-CPU lineup (Java-style warning). */
  Uint64 all_cpu_confirm_deadline{};
};

struct SdlSessionFlow {
  bool handoff_enabled = false;
  AppPhase* phase = nullptr;
  bool* handoff_autosave_ok = nullptr;
};

constexpr char k_gui_autosave_file[] = "tack_strat_gui_autosave.json";
constexpr char k_gui_save_file[] = "tack_strat_gui_save.json";

enum class RestoreEntryMode {
  InGame,
  Menu,
};

[[nodiscard]] bool gui_save_file_exists(char const* path) {
  namespace fs = std::filesystem;
  fs::path const p(path);
  return fs::exists(p) && fs::is_regular_file(p);
}

void gui_set_phase_after_restore(GameSession const& session, AppPhase* out_phase,
                                 RestoreEntryMode mode) {
  if (out_phase == nullptr) {
    return;
  }
  if (session.is_over()) {
    *out_phase = AppPhase::Victory;
  } else if (mode == RestoreEntryMode::Menu && !session.current_player().computer) {
    *out_phase = AppPhase::HandoffGate;
  } else {
    *out_phase = AppPhase::Playing;
  }
}

[[nodiscard]] Player const* player_by_seat(GameSession const& session, int seat) noexcept {
  for (Player const& p : session.players()) {
    if (p.seat == seat) {
      return &p;
    }
  }
  return nullptr;
}

[[nodiscard]] int count_human_players(GameSession const& session) noexcept {
  int n = 0;
  for (Player const& p : session.players()) {
    if (!p.computer) {
      ++n;
    }
  }
  return n;
}

[[nodiscard]] bool try_gui_autosave(GameSession const& session) {
  return tack::strat::snapshot_write_json_file(session.capture(), k_gui_autosave_file);
}

void after_session_end_turn(GameSession& session, AppPhase* phase, bool* autosave_ok) {
  if (!session.is_over() && session.current_player().computer) {
    drain_cpu_turns(session);
  }
  if (autosave_ok != nullptr) {
    *autosave_ok = try_gui_autosave(session);
  }
  if (phase == nullptr) {
    return;
  }
  if (session.is_over()) {
    *phase = AppPhase::Victory;
  } else if (!session.current_player().computer) {
    *phase = AppPhase::HandoffGate;
  }
}

[[nodiscard]] bool sdl_action_message_is_failure(std::string const& msg) noexcept {
  std::string m;
  m.reserve(msg.size());
  for (unsigned char ch : msg) {
    m.push_back(static_cast<char>(std::tolower(ch)));
  }
  return m.find("no moves") != std::string::npos || m.find("cannot") != std::string::npos ||
         m.find("already") != std::string::npos || m.find("only ") != std::string::npos ||
         m.find("need ") != std::string::npos || m.find("too far") != std::string::npos ||
         m.find("no longer") != std::string::npos || m.find("invalid") != std::string::npos ||
         m.find("blocked") != std::string::npos || m.find("failed") != std::string::npos;
}

[[nodiscard]] bool sdl_action_message_is_success(std::string const& msg) noexcept {
  if (sdl_action_message_is_failure(msg)) {
    return false;
  }
  std::string m;
  m.reserve(msg.size());
  for (unsigned char ch : msg) {
    m.push_back(static_cast<char>(std::tolower(ch)));
  }
  return m.find("founded") != std::string::npos || m.find("eliminated") != std::string::npos ||
         m.find("building ") != std::string::npos || m.find("queued ") != std::string::npos ||
         m.find("fortified") != std::string::npos || m.find("move queued") != std::string::npos ||
         m.find("following route") != std::string::npos || m.find("hunts for") != std::string::npos ||
         m.find("saved ") != std::string::npos || m.find("loaded ") != std::string::npos ||
         m.find("centered on") != std::string::npos || m.find("cleared") != std::string::npos ||
         m.find("unskipped") != std::string::npos || m.find("skipped for") != std::string::npos ||
         m.find(" awake") != std::string::npos || m.find("woken") != std::string::npos ||
         m.find("cycling: on") != std::string::npos || m.find("lens: on") != std::string::npos ||
         m.find("tint key: on") != std::string::npos || m.find("borders legend: on") != std::string::npos ||
         m.find("own-claims tint: on") != std::string::npos ||
         m.find("fitted to discovered") != std::string::npos;
}

void set_toast(GuiState* gui, std::string msg, Uint64 ms = 3200, bool warning = false) {
  gui->toast_text = std::move(msg);
  gui->toast_until_ticks = SDL_GetTicks() + ms;
  gui->toast_is_warning = warning;
}

void append_event_log(GuiState* gui, std::string line) {
  if (gui == nullptr || line.empty()) {
    return;
  }
  gui->event_log.push_back(std::move(line));
  while (gui->event_log.size() > k_event_log_max_lines) {
    gui->event_log.pop_front();
  }
  gui->event_log_scroll_offset = 0;
}

void present_action_feedback(GuiState* gui, std::string msg, Uint64 toast_ms,
                             std::optional<HexCoord> combat_flash) {
  if (gui == nullptr || msg.empty()) {
    return;
  }
  append_event_log(gui, msg);
  bool const warning = sdl_action_message_is_failure(msg);
  set_toast(gui, std::move(msg), toast_ms, warning);
  if (combat_flash.has_value()) {
    trigger_combat_flash(gui, *combat_flash);
  }
}

void ensure_event_log_seeded(GameSession const& session, GuiState* gui) {
  if (gui == nullptr || gui->event_log_seeded) {
    return;
  }
  append_event_log(gui, "World seed " + std::to_string(session.world_seed()) +
                            " - combat and founding appear here.");
  gui->event_log_seeded = true;
}

[[nodiscard]] std::string sdl_turn_summary(GameSession const& session) {
  if (session.is_over()) {
    return {};
  }
  int const seat = session.current_player().seat;
  int ready_units = 0;
  int routed_units = 0;
  int cities_owned = 0;
  int food = 0;
  int prod = 0;
  int gold = 0;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != seat) {
      continue;
    }
    if (u.moves_remaining() > 0) {
      ++ready_units;
    }
    if (!session.planned_route_for(u.id()).empty()) {
      ++routed_units;
    }
  }
  for (City const& c : session.cities()) {
    if (c.owner_seat() != seat) {
      continue;
    }
    ++cities_owned;
    CityYield const y = session.city_yield(c);
    food += y.food;
    prod += y.production;
    gold += y.gold;
  }
  std::string summary = "Turn start: " + std::to_string(ready_units) + " units ready";
  if (routed_units > 0) {
    summary += " (" + std::to_string(routed_units) + " routed)";
  }
  summary += " · " + std::to_string(cities_owned) + " cities · +" + std::to_string(food) + "f +" +
             std::to_string(prod) + "p +" + std::to_string(gold) + "g";
  for (City const& c : session.cities()) {
    if (c.owner_seat() != seat) {
      continue;
    }
    if (auto kb = c.current_build()) {
      summary += " · building: " + std::string(tack::strat::unit_kind_display_name(*kb)) + " (" +
                 std::to_string(c.production_stored()) + "/" +
                 std::to_string(tack::strat::unit_production_cost(*kb)) + ")";
      break;
    }
  }
  summary += " · " + session.calendar_era_label() + " · " + season_label(session.season());
  return summary;
}

[[nodiscard]] std::string sdl_turn_stats_hud_line(GameSession const& session, GuiState const& gui) {
  if (session.is_over() || session.current_player().computer) {
    return {};
  }
  int const seat = session.current_player().seat;
  int ready_units = 0;
  int routed_units = 0;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != seat) {
      continue;
    }
    if (u.moves_remaining() > 0) {
      ++ready_units;
    }
    if (!session.planned_route_for(u.id()).empty()) {
      ++routed_units;
    }
  }
  int const pending = count_units_needing_manual_orders(session, gui);
  std::string line = std::to_string(ready_units) + " ready";
  if (pending > 0) {
    line += ", " + std::to_string(pending) + " need orders";
  }
  if (routed_units > 0) {
    line += ", " + std::to_string(routed_units) + " routed";
  }
  for (City const& c : session.cities()) {
    if (c.owner_seat() != seat) {
      continue;
    }
    if (auto kb = c.current_build()) {
      line += " · ";
      line += tack::strat::unit_kind_display_name(*kb);
      line += " (" + std::to_string(c.production_stored()) + "/" +
              std::to_string(tack::strat::unit_production_cost(*kb)) + ")";
      break;
    }
  }
  if (sdl_player_has_city_without_production(session)) {
    line += " · production needed";
  }
  return line;
}

[[nodiscard]] int event_log_visible_lines(EventLogLayout const& layout) noexcept {
  float const content_h = std::max(0.f, layout.panel.h - 22.f);
  int const lines = static_cast<int>(content_h / k_event_log_line_h);
  if (layout.expanded) {
    return std::max(2, lines);
  }
  return std::max(1, std::min(2, lines));
}

void draw_event_log_panel(SDL_Renderer* ren, EventLogLayout const& layout, GuiState const& gui) {
  if (!layout.visible) {
    return;
  }
  menu_ui::fill_rect(ren, layout.panel, rgb_u8(0x0a, 0x10, 0x18), 0.94f);
  menu_ui::stroke_rect(ren, layout.panel, menu_ui::k_modal_line, 0.7f);
  int const visible_lines = event_log_visible_lines(layout);
  int const total = static_cast<int>(gui.event_log.size());
  int const max_scroll = std::max(0, total - visible_lines);
  int const scroll = std::clamp(gui.event_log_scroll_offset, 0, max_scroll);
  std::string header = layout.expanded ? "Event log (click to collapse" : "Event log (click to expand";
  if (layout.expanded && max_scroll > 0) {
    header += " · scroll wheel";
  }
  header += ")";
  menu_ui::draw_text(ren, layout.panel.x + 10.f, layout.panel.y + 6.f, header.c_str(),
                      menu_ui::k_accent);

  std::size_t const start =
      total > visible_lines ? static_cast<std::size_t>(total - visible_lines - scroll) : 0;
  float ty = layout.panel.y + 22.f;
  for (std::size_t i = start; i < gui.event_log.size(); ++i) {
    if (ty + k_event_log_line_h > layout.panel.y + layout.panel.h - 4.f) {
      break;
    }
    std::string line = gui.event_log[i];
    float const wrap_w = std::max(120.f, layout.panel.w - 20.f);
    auto const wrapped = menu_ui::wrap_text_to_width(line, wrap_w);
    for (std::size_t wi = 0; wi < wrapped.size(); ++wi) {
      if (ty + k_event_log_line_h > layout.panel.y + layout.panel.h - 4.f) {
        break;
      }
      bool const warn = wi == 0 && sdl_action_message_is_failure(gui.event_log[i]);
      bool const ok = wi == 0 && !warn && sdl_action_message_is_success(gui.event_log[i]);
      RGB ink = warn ? menu_ui::k_err : ok ? menu_ui::k_ok : menu_ui::k_hint;
      menu_ui::draw_text(ren, layout.panel.x + 10.f, ty, wrapped[wi].c_str(), ink,
                         warn ? 0.92f : 1.f);
      ty += k_event_log_line_h;
    }
  }
  if (layout.expanded && max_scroll > 0) {
    std::string scroll_hint = "Older +" + std::to_string(scroll) + "/" + std::to_string(max_scroll);
    menu_ui::draw_text(ren, layout.panel.x + layout.panel.w - 96.f, layout.panel.y + 6.f,
                       scroll_hint.c_str(), menu_ui::k_muted, 0.85f);
  }
}

[[nodiscard]] EventLogLayout compute_event_log_layout(GuiState const& gui, int win_w, int win_h) {
  EventLogLayout layout;
  if (gui.event_log.empty()) {
    return layout;
  }
  layout.visible = true;
  layout.expanded = gui.event_log_expanded;
  float const foot_y = static_cast<float>(win_h) - k_footer_bar_h;
  float const panel_w = std::min(k_event_log_w, k_city_panel_w + 20.f);
  if (panel_w < 180.f) {
    layout.visible = false;
    return layout;
  }
  float const panel_h = layout.expanded ? k_event_log_expanded_h : k_event_log_h;
  float const panel_y = foot_y - panel_h - 6.f;
  layout.panel = {10.f, panel_y, panel_w, panel_h};
  layout.toggle_btn = layout.panel;
  return layout;
}

bool try_handle_event_log_click(GuiState* gui, EventLogLayout const& layout, float mx, float my) {
  if (!layout.visible || gui == nullptr) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.toggle_btn)) {
    return false;
  }
  gui->event_log_expanded = !gui->event_log_expanded;
  if (!gui->event_log_expanded) {
    gui->event_log_scroll_offset = 0;
  }
  return true;
}

bool try_handle_event_log_wheel(GuiState* gui, EventLogLayout const& layout, float mx, float my,
                                float wheel_y) {
  if (!layout.visible || !layout.expanded || gui == nullptr || wheel_y == 0.f) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    return false;
  }
  int const visible_lines = event_log_visible_lines(layout);
  int const total = static_cast<int>(gui->event_log.size());
  int const max_scroll = std::max(0, total - visible_lines);
  if (max_scroll <= 0) {
    return true;
  }
  if (wheel_y > 0.f) {
    gui->event_log_scroll_offset = std::min(max_scroll, gui->event_log_scroll_offset + 1);
  } else {
    gui->event_log_scroll_offset = std::max(0, gui->event_log_scroll_offset - 1);
  }
  return true;
}

[[nodiscard]] float play_ui_max_panel_bottom(int win_h, GuiState const* gui) noexcept {
  float reserve = k_footer_bar_h + 10.f;
  if (gui != nullptr && !gui->event_log.empty()) {
    reserve += (gui->event_log_expanded ? k_event_log_expanded_h : k_event_log_h) + 8.f;
  }
  return static_cast<float>(win_h) - reserve;
}

[[nodiscard]] float left_ui_column_bottom(UnitInspectorLayout const& unit_layout,
                                          ProductionNeedsLayout const& needs_layout) noexcept {
  float bottom = k_top_bar_h + 6.f;
  if (unit_layout.visible) {
    bottom = std::max(bottom, unit_layout.panel.y + unit_layout.panel.h);
  }
  if (needs_layout.visible) {
    bottom = std::max(bottom, needs_layout.panel.y + needs_layout.panel.h);
  }
  return bottom + 6.f;
}

void stack_production_needs_below_unit(UnitInspectorLayout const& unit_layout,
                                       ProductionNeedsLayout* needs_layout) {
  if (!unit_layout.visible || needs_layout == nullptr || !needs_layout->visible) {
    return;
  }
  float const target_y = unit_layout.panel.y + unit_layout.panel.h + 8.f;
  float const dy = target_y - needs_layout->panel.y;
  if (dy <= 0.f) {
    return;
  }
  needs_layout->panel.y += dy;
  for (SDL_FRect& btn : needs_layout->jump_buttons) {
    btn.y += dy;
  }
}

void clamp_panel_max_bottom(float max_bottom_y, SDL_FRect* panel) {
  if (panel == nullptr || max_bottom_y <= panel->y + 80.f) {
    return;
  }
  if (panel->y + panel->h > max_bottom_y) {
    panel->h = std::max(80.f, max_bottom_y - panel->y);
  }
}

void trim_production_needs_buttons(ProductionNeedsLayout* layout) {
  if (layout == nullptr || !layout->visible) {
    return;
  }
  float const max_bottom = layout->panel.y + layout->panel.h - 4.f;
  while (!layout->jump_buttons.empty()) {
    SDL_FRect const& btn = layout->jump_buttons.back();
    if (btn.y + btn.h <= max_bottom) {
      break;
    }
    layout->jump_buttons.pop_back();
    layout->city_ids.pop_back();
  }
}

[[nodiscard]] float footer_hint_max_x(BottomActionBarLayout const& action_bar,
                                      int win_w) noexcept {
  if (action_bar.visible) {
    return std::max(240.f, action_bar.action_orb.x - 18.f);
  }
  return static_cast<float>(win_w) - 24.f;
}

[[nodiscard]] char const* claim_legend_body_text() noexcept {
  return "Border color = owner. Double edge = pressure. Claimed tiles affect city working and "
         "expansion.";
}

[[nodiscard]] float claim_legend_card_height() noexcept {
  float const inner_w = std::min(220.f, k_city_panel_w + 8.f);
  float const pad = 10.f;
  auto const lines = menu_ui::wrap_text_to_width(claim_legend_body_text(), inner_w);
  return pad + 14.f + 6.f + 14.f * static_cast<float>(lines.size()) + pad;
}

[[nodiscard]] std::vector<std::string> tile_hover_lines(std::string const& full, float wrap_w) {
  std::vector<std::string> lines;
  for (std::size_t pos = 0; pos < full.size();) {
    std::size_t const next = full.find('\n', pos);
    if (next == std::string::npos) {
      for (std::string const& wrapped : menu_ui::wrap_text_to_width(full.substr(pos), wrap_w)) {
        lines.push_back(wrapped);
      }
      break;
    }
    for (std::string const& wrapped :
         menu_ui::wrap_text_to_width(full.substr(pos, next - pos), wrap_w)) {
      lines.push_back(wrapped);
    }
    pos = next + 1;
  }
  return lines;
}

[[nodiscard]] float tile_hover_card_total_height(std::size_t line_count) noexcept {
  if (line_count == 0) {
    return 0.f;
  }
  float const panel_h = 8.f + 14.f * static_cast<float>(line_count);
  return panel_h + 8.f;
}

struct TileHoverPlacement {
  float x = 10.f;
  float y = 0.f;
  float wrap_w = 0.f;
  bool visible = false;
};

[[nodiscard]] TileHoverPlacement compute_tile_hover_placement(
    std::string const& hover_line, float hint_max_x,
    UnitInspectorLayout const& unit_layout, ProductionNeedsLayout const& needs_layout,
    float legend_ceiling, bool show_claim_legend, float stack_top_y) {
  TileHoverPlacement out;
  if (hover_line.empty()) {
    return out;
  }
  out.wrap_w = std::max(160.f, std::min(hint_max_x - 20.f, k_city_panel_w + 20.f));
  std::vector<std::string> const lines = tile_hover_lines(hover_line, out.wrap_w);
  float const card_h = tile_hover_card_total_height(lines.size());
  if (card_h <= 0.f) {
    return out;
  }

  float const gap_top = stack_top_y + 6.f;
  float claim_top = legend_ceiling;
  if (show_claim_legend) {
    claim_top -= claim_legend_card_height() + 8.f;
  }
  float const gap_bottom = claim_top - 6.f;

  if (gap_bottom - card_h >= gap_top + 12.f) {
    out.x = 10.f;
    out.y = gap_bottom - card_h;
  } else {
    out.x = k_city_panel_w + 24.f;
    out.y = gap_top;
    if (out.y + card_h > gap_bottom - 4.f) {
      out.y = std::max(k_top_bar_h + 6.f, gap_bottom - card_h - 4.f);
    }
  }
  out.visible = true;
  return out;
}

void maybe_auto_advance_action_queue_sdl(GameSession& session, GuiState* gui, View* v,
                                       std::optional<int>* sel_unit,
                                       std::optional<int>* sel_city, int win_w, int win_h) {
  if (gui == nullptr || !gui->auto_focus_next_unit || gui->auto_advance_in_progress) {
    return;
  }
  if (session.is_over() || session.current_player().computer) {
    return;
  }
  int const seat = session.current_player().seat;
  if (sel_unit->has_value()) {
    auto u = session.unit_by_id(**sel_unit);
    if (!u.has_value() || u->owner_seat() != seat) {
      return;
    }
    if (u->moves_remaining() > 0) {
      return;
    }
  } else {
    return;
  }

  gui->auto_advance_in_progress = true;
  std::optional<int> const before_unit = *sel_unit;
  cycle_next_unit(session, sel_unit, v, win_w, win_h, gui, sel_city);
  bool advanced = false;
  if (sel_unit->has_value() && (!before_unit.has_value() || *sel_unit != before_unit)) {
    advanced = true;
  } else if (!sdl_has_pending_manual_unit_orders(session, *gui) &&
             sdl_player_has_city_without_production(session)) {
    select_first_city_missing_production_sdl(session, v, sel_unit, sel_city, win_w, win_h);
    advanced = sel_city->has_value();
  }
  gui->auto_advance_in_progress = false;
  static_cast<void>(advanced);
}

void toggle_auto_focus_next_unit(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->auto_focus_next_unit = !gui->auto_focus_next_unit;
  std::string msg = std::string("Auto unit cycling: ") + (gui->auto_focus_next_unit ? "ON" : "OFF");
  present_action_feedback(gui, std::move(msg), 2800);
  save_gui_options(*gui);
}

constexpr char k_gui_options_json[] = "tack_strat_gui_options.json";

void load_gui_options(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  std::ifstream in(k_gui_options_json);
  if (!in) {
    return;
  }
  try {
    nlohmann::json const j = nlohmann::json::parse(in, nullptr, false);
    if (j.is_discarded() || !j.is_object()) {
      return;
    }
    if (j.contains("auto_focus_next_unit")) {
      gui->auto_focus_next_unit = j.at("auto_focus_next_unit").get<bool>();
    }
    if (j.contains("tips_dismissed")) {
      gui->tips_dismissed = j.at("tips_dismissed").get<bool>();
    }
    if (j.contains("show_settler_lens")) {
      gui->show_settler_lens = j.at("show_settler_lens").get<bool>();
    }
    if (j.contains("show_weather_legend")) {
      gui->show_weather_legend = j.at("show_weather_legend").get<bool>();
    }
    if (j.contains("show_claim_legend")) {
      gui->show_claim_legend = j.at("show_claim_legend").get<bool>();
    }
    if (j.contains("minimap_tint_own_claims_only")) {
      gui->minimap_tint_own_claims_only = j.at("minimap_tint_own_claims_only").get<bool>();
    }
  } catch (...) {
  }
}

void save_gui_options(GuiState const& gui) {
  try {
    nlohmann::json j;
    j["auto_focus_next_unit"] = gui.auto_focus_next_unit;
    j["tips_dismissed"] = gui.tips_dismissed;
    j["show_settler_lens"] = gui.show_settler_lens;
    j["show_weather_legend"] = gui.show_weather_legend;
    j["show_claim_legend"] = gui.show_claim_legend;
    j["minimap_tint_own_claims_only"] = gui.minimap_tint_own_claims_only;
    std::ofstream out(k_gui_options_json);
    out << j.dump(2);
  } catch (...) {
  }
}

void toggle_settler_lens(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->show_settler_lens = !gui->show_settler_lens;
  gui->settler_lens_unit_id = -1;
  std::string msg =
      std::string("Settler recommendation lens: ") + (gui->show_settler_lens ? "ON" : "OFF");
  present_action_feedback(gui, std::move(msg), 2800);
  save_gui_options(*gui);
}

void toggle_weather_legend(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->show_weather_legend = !gui->show_weather_legend;
  std::string msg =
      std::string("Weather tint key: ") + (gui->show_weather_legend ? "ON" : "OFF");
  present_action_feedback(gui, std::move(msg), 2400);
  save_gui_options(*gui);
}

void toggle_claim_legend(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->show_claim_legend = !gui->show_claim_legend;
  std::string msg = std::string("Territory legend: ") + (gui->show_claim_legend ? "ON" : "OFF");
  present_action_feedback(gui, std::move(msg), 2400);
  save_gui_options(*gui);
}

void toggle_minimap_own_claims(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->minimap_tint_own_claims_only = !gui->minimap_tint_own_claims_only;
  std::string msg = std::string("Minimap own-claims tint: ") +
                      (gui->minimap_tint_own_claims_only ? "ON" : "OFF");
  present_action_feedback(gui, std::move(msg), 2400);
  save_gui_options(*gui);
}

void trigger_combat_flash(GuiState* gui, HexCoord h) {
  if (gui == nullptr) {
    return;
  }
  Uint64 const now = SDL_GetTicks();
  gui->combat_flash_hex = h;
  gui->combat_flash_start_ticks = now;
  gui->combat_flash_until_ticks = now + 520;
}

void rebuild_settler_lens_cache(GuiState* gui, GameSession const& session, int seat,
                                int settler_id) {
  if (gui == nullptr) {
    return;
  }
  gui->settler_lens_hints.clear();
  if (!gui->show_settler_lens) {
    return;
  }
  auto u = session.unit_by_id(settler_id);
  if (!u.has_value() || u->kind() != UnitKind::SETTLER || u->owner_seat() != seat || u->is_dead()) {
    return;
  }
  constexpr int k_weight_food = 3;
  constexpr int k_weight_prod = 3;
  constexpr int k_weight_gold = 2;
  constexpr int k_weight_travel = 3;
  constexpr int k_weight_rival = 2;

  auto const visited = session.visited_for(seat);
  struct Scored {
    SettlerLensHint hint;
  };
  std::vector<Scored> scored;
  scored.reserve(visited.size());
  for (HexCoord const c : visited) {
    if (!session.map().contains(c) || !can_found_city_on(session.terrain_effective_at(c))) {
      continue;
    }
    if (session.city_at(c).has_value() || session.wild_animal_at(c).has_value()) {
      continue;
    }
    if (u->coord().distance_to(c) > 12) {
      continue;
    }
    auto path = projected_path_to(session, *u, c);
    if (!path.has_value() || path->size() < 2) {
      continue;
    }
    int const travel_turns = projected_path_turns(*u, *path, session);
    if (travel_turns > 6) {
      continue;
    }
    bool adjacent_city = false;
    int adjacent_water = 0;
    int adjacent_hostile = 0;
    int rough_neighbors = 0;
    for (HexCoord const n : c.neighbors()) {
      if (!session.map().contains(n)) {
        continue;
      }
      if (session.terrain_effective_at(n) == Terrain::WATER) {
        ++adjacent_water;
      }
      if (auto owner = session.claimed_owner_at(n); owner.has_value() && *owner != seat) {
        ++adjacent_hostile;
      }
      Terrain const tn = session.terrain_effective_at(n);
      if (tn == Terrain::FOREST || tn == Terrain::HILL) {
        ++rough_neighbors;
      }
      if (session.city_at(n).has_value()) {
        adjacent_city = true;
        break;
      }
    }
    if (adjacent_city) {
      continue;
    }
    int food = session.tile_food_yield(c);
    int prod = session.tile_production_yield(c);
    int gold = session.tile_gold_yield(c);
    int workable = 0;
    int rich = 0;
    for (HexCoord const n : c.neighbors()) {
      if (!session.map().contains(n) || !tack::strat::passable(session.terrain_effective_at(n))) {
        continue;
      }
      ++workable;
      int const tf = session.tile_food_yield(n);
      int const tp = session.tile_production_yield(n);
      int const tg = session.tile_gold_yield(n);
      food += tf;
      prod += tp;
      gold += tg;
      if (tf + tp + tg >= 4) {
        ++rich;
      }
    }
    int near_enemy_penalty = 0;
    int near_own_bonus = 0;
    for (City const& ct : session.cities()) {
      int const d = ct.coord().distance_to(c);
      if (ct.owner_seat() == seat) {
        if (d <= 5) {
          near_own_bonus += 2;
        }
      } else if (d <= 4) {
        near_enemy_penalty += (5 - d) * 2;
      }
    }
    Terrain const center = session.terrain_effective_at(c);
    int terrain_bonus = 0;
    switch (center) {
      case Terrain::HILL:
        terrain_bonus = 4;
        break;
      case Terrain::PLAINS:
      case Terrain::GRASS:
        terrain_bonus = 2;
        break;
      case Terrain::DESERT:
        terrain_bonus = -2;
        break;
      default:
        break;
    }
    int const coastline_bonus = std::min(3, adjacent_water) * 2;
    int const defense_bonus = center == Terrain::HILL ? 3 : 0;
    int score = food * k_weight_food + prod * k_weight_prod + gold * k_weight_gold + rich * 2 +
                workable * 2 + near_own_bonus + terrain_bonus + coastline_bonus + defense_bonus -
                near_enemy_penalty - travel_turns * k_weight_travel -
                adjacent_hostile * k_weight_rival;
    SettlerLensHint hint;
    hint.hex = c;
    hint.score = score;
    hint.travel_turns = travel_turns;
    if (food >= 14) {
      hint.positives.push_back("strong food");
    }
    if (prod >= 11) {
      hint.positives.push_back("strong production");
    }
    if (gold >= 9) {
      hint.positives.push_back("good gold");
    }
    if (rich >= 3) {
      hint.positives.push_back("many rich tiles");
    }
    if (near_own_bonus > 0) {
      hint.positives.push_back("supported by nearby city");
    }
    if (adjacent_water >= 2) {
      hint.positives.push_back("coastal access");
    }
    if (center == Terrain::HILL || rough_neighbors >= 3) {
      hint.positives.push_back("defensible terrain");
    }
    if (travel_turns <= 2) {
      hint.positives.push_back("quick to reach");
    }
    if (workable <= 3) {
      hint.negatives.push_back("few workable neighbors");
    }
    if (food <= 9) {
      hint.negatives.push_back("weak food");
    }
    if (prod <= 7) {
      hint.negatives.push_back("weak production");
    }
    if (near_enemy_penalty >= 4) {
      hint.negatives.push_back("close to enemy city");
    }
    if (travel_turns >= 4) {
      hint.negatives.push_back("long travel time");
    }
    if (center == Terrain::DESERT && food <= 10) {
      hint.negatives.push_back("harsh center tile");
    }
    if (adjacent_hostile >= 2) {
      hint.negatives.push_back("pressure from nearby rival claims");
    }
    if (auto owner = session.claimed_owner_at(c); owner.has_value() && *owner != seat) {
      hint.negatives.push_back("currently in rival claim");
      score -= 2 + k_weight_rival;
      hint.score = score;
    }
    if (hint.positives.empty()) {
      hint.positives.push_back("balanced nearby yields");
    }
    scored.push_back({std::move(hint)});
  }
  std::sort(scored.begin(), scored.end(), [](Scored const& a, Scored const& b) {
    return a.hint.score > b.hint.score;
  });
  int const cap = std::min(10, static_cast<int>(scored.size()));
  for (int i = 0; i < cap; ++i) {
    SettlerLensHint hint = scored[static_cast<std::size_t>(i)].hint;
    hint.rank = i < 3 ? i + 1 : 4;
    gui->settler_lens_hints.push_back(std::move(hint));
  }
  gui->settler_lens_unit_id = settler_id;
  gui->settler_lens_seat = seat;
  gui->settler_lens_origin = u->coord();
}

[[nodiscard]] SettlerLensHint const* settler_lens_hint_at(GuiState const& gui, HexCoord h) {
  for (SettlerLensHint const& hint : gui.settler_lens_hints) {
    if (hint.hex == h) {
      return &hint;
    }
  }
  return nullptr;
}

[[nodiscard]] std::string settler_lens_hover_line(SettlerLensHint const& hint) {
  std::string line = "Settle rank #" + std::to_string(hint.rank) + " · score " +
                     std::to_string(hint.score) + " · " + std::to_string(hint.travel_turns) +
                     " turn travel";
  if (!hint.positives.empty()) {
    line += " · +";
    for (std::size_t i = 0; i < hint.positives.size(); ++i) {
      if (i > 0) {
        line += ", ";
      }
      line += hint.positives[i];
    }
  }
  if (!hint.negatives.empty()) {
    line += " · -";
    for (std::size_t i = 0; i < hint.negatives.size(); ++i) {
      if (i > 0) {
        line += ", ";
      }
      line += hint.negatives[i];
    }
  }
  return menu_ui::truncate_line(std::move(line), 110);
}

void draw_exploration_frontier_shroud(SDL_Renderer* ren, GameSession const& session, View const& v,
                                      std::unordered_set<HexCoord> const& visited,
                                      MapScreen const& screen) {
  for (HexCoord const c : visited) {
    bool frontier = false;
    for (HexCoord const n : c.neighbors()) {
      if (session.map().contains(n) && !visited.count(n)) {
        frontier = true;
        break;
      }
    }
    if (!frontier) {
      continue;
    }
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    fill_hex_solid(ren, v, cx, cy, {0.02f, 0.05f, 0.10f, 0.38f});
  }
}

void draw_claim_borders(SDL_Renderer* ren, GameSession const& session, View const& v,
                        std::unordered_set<HexCoord> const& visible,
                        std::unordered_set<HexCoord> const& visited, MapScreen const& screen) {
  for (HexCoord const c : session.map().all_cells()) {
    if (!visited.count(c)) {
      continue;
    }
    auto const owner = session.claimed_owner_at(c);
    if (!owner.has_value()) {
      continue;
    }
    RGB const base = player_rgb(*owner);
    bool const in_sight = visible.count(c);
    float const er = base.r;
    float const eg = base.g;
    float const eb = base.b;
    float const ea = in_sight ? 228.f / 255.f : 138.f / 255.f;
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    for (int edge = 0; edge < 6; ++edge) {
      HexCoord const n = neighbor_for_edge(c, edge);
      std::optional<int> n_owner;
      if (session.map().contains(n)) {
        n_owner = session.claimed_owner_at(n);
      }
      if (n_owner == owner) {
        continue;
      }
      stroke_hex_edge(ren, v, cx, cy, edge, er, eg, eb, ea);
      if (n_owner.has_value() && *n_owner != *owner) {
        float const wa = in_sight ? 118.f / 255.f : 78.f / 255.f;
        stroke_hex_edge(ren, v, cx, cy, edge, 1.f, 1.f, 1.f, wa);
      }
    }
  }
}

void draw_weather_tile_overlays(SDL_Renderer* ren, GameSession const& session, View const& v,
                                std::unordered_set<HexCoord> const& visited,
                                std::unordered_set<HexCoord> const& visible,
                                MapScreen const& screen) {
  for (HexCoord const c : visited) {
    Weather const w = session.weather_at(c);
    if (w == Weather::CLEAR) {
      continue;
    }
    bool const in_sight = visible.count(c);
    RGBA wash = weather_wash_rgba(w, in_sight);
    if (wash.a <= 0.f) {
      continue;
    }
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    float const fill_a = std::min(1.f, wash.a * 0.90f);
    fill_hex_solid(ren, v, cx, cy, {wash.r, wash.g, wash.b, fill_a});
    char const badge = weather_badge_char(w);
    if (badge == ' ') {
      continue;
    }
    float sx = 0.f;
    float sy = 0.f;
    world_to_screen(v, cx + HEX_R * 0.38f, cy - HEX_R * 0.52f, &sx, &sy);
    SDL_FRect badge_bg{sx - 7.f, sy - 11.f, 14.f, 14.f};
    SDL_SetRenderDrawColorFloat(ren, 18.f / 255.f, 22.f / 255.f, 30.f / 255.f, 228.f / 255.f);
    SDL_RenderFillRect(ren, &badge_bg);
    char label[2] = {badge, '\0'};
    RGB ink = weather_badge_ink(w);
    menu_ui::draw_text(ren, sx - 3.f, sy - 2.f, label, ink);
  }
}

void draw_selected_unit_tile_glow(SDL_Renderer* ren, GameSession const& session, View const& v,
                                  std::optional<int> selected_unit_id, int cur_seat,
                                  std::unordered_set<HexCoord> const& visible,
                                  std::unordered_set<HexCoord> const& visited) {
  if (!selected_unit_id.has_value()) {
    return;
  }
  auto u = session.unit_by_id(*selected_unit_id);
  if (!u.has_value() || u->owner_seat() != cur_seat) {
    return;
  }
  HexCoord const coord = u->coord();
  if (!visited.count(coord) || !visible.count(coord)) {
    return;
  }
  float const cx = axial_to_world_x(coord);
  float const cy = axial_to_world_y(coord);
  fill_hex_overlay(ren, v, cx, cy, 110.f / 255.f, 215.f / 255.f, 255.f / 255.f, 62.f / 255.f,
                   1.f, 248.f / 255.f, 210.f / 255.f, 22.f / 255.f);
}

void draw_coast_foam(SDL_Renderer* ren, GameSession const& session, View const& v,
                     std::unordered_set<HexCoord> const& visible,
                     std::unordered_set<HexCoord> const& visited, MapScreen const& screen) {
  if (v.scale < k_lod_detail_min_scale) {
    return;
  }
  for (HexCoord const c : session.map().all_cells()) {
    if (!visited.count(c) || !visible.count(c)) {
      continue;
    }
    if (session.terrain_effective_at(c) != Terrain::WATER) {
      continue;
    }
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    for (int edge = 0; edge < 6; ++edge) {
      HexCoord const n = neighbor_for_edge(c, edge);
      if (!session.map().contains(n) || !visited.count(n)) {
        continue;
      }
      if (session.terrain_effective_at(n) == Terrain::WATER) {
        continue;
      }
      fill_hex_edge_inward_quad(ren, v, cx, cy, edge, 220.f / 255.f, 246.f / 255.f, 1.f,
                                72.f / 255.f, 220.f / 255.f, 246.f / 255.f, 1.f, 0.f,
                                HEX_R * 0.16f);
    }
  }
}

void draw_shore_water_depth(SDL_Renderer* ren, GameSession const& session, View const& v,
                            std::unordered_set<HexCoord> const& visible,
                            std::unordered_set<HexCoord> const& visited, MapScreen const& screen) {
  if (v.scale < k_lod_detail_min_scale) {
    return;
  }
  for (HexCoord const c : session.map().all_cells()) {
    if (!visited.count(c) || !visible.count(c)) {
      continue;
    }
    if (session.terrain_effective_at(c) != Terrain::WATER) {
      continue;
    }
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    for (int edge = 0; edge < 6; ++edge) {
      HexCoord const n = neighbor_for_edge(c, edge);
      if (!session.map().contains(n) || !visited.count(n)) {
        continue;
      }
      if (session.terrain_effective_at(n) == Terrain::WATER) {
        continue;
      }
      fill_hex_edge_inward_quad(ren, v, cx, cy, edge, 5.f / 255.f, 35.f / 255.f, 72.f / 255.f,
                                108.f / 255.f, 0.f, 55.f / 255.f, 95.f / 255.f, 0.f,
                                HEX_R * 0.42f);
    }
  }
}

[[nodiscard]] bool water_tile_is_deep(GameSession const& session, HexCoord c) {
  for (int edge = 0; edge < 6; ++edge) {
    HexCoord const n = neighbor_for_edge(c, edge);
    if (!session.map().contains(n)) {
      continue;
    }
    if (session.terrain_effective_at(n) != Terrain::WATER) {
      return false;
    }
  }
  return true;
}

void draw_deep_water_overlay(SDL_Renderer* ren, GameSession const& session, View const& v,
                             std::unordered_set<HexCoord> const& visible,
                             std::unordered_set<HexCoord> const& visited,
                             MapScreen const& screen) {
  if (v.scale < k_lod_detail_min_scale) {
    return;
  }
  for (HexCoord const c : session.map().all_cells()) {
    if (!visited.count(c) || !visible.count(c)) {
      continue;
    }
    if (session.terrain_effective_at(c) != Terrain::WATER || !water_tile_is_deep(session, c)) {
      continue;
    }
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    fill_hex_solid(ren, v, cx, cy, {3.f / 255.f, 18.f / 255.f, 42.f / 255.f, 0.28f});
  }
}

void draw_terrain_edge_blends(SDL_Renderer* ren, GameSession const& session, View const& v,
                              std::unordered_set<HexCoord> const& visible,
                              std::unordered_set<HexCoord> const& visited,
                              MapScreen const& screen) {
  if (v.scale < k_lod_detail_min_scale) {
    return;
  }
  for (HexCoord const c : session.map().all_cells()) {
    if (!visited.count(c)) {
      continue;
    }
    Terrain const t = session.terrain_effective_at(c);
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    for (int edge = 0; edge < 6; ++edge) {
      HexCoord const n = neighbor_for_edge(c, edge);
      if (!session.map().contains(n) || !visited.count(n)) {
        continue;
      }
      if (!(c.q < n.q || (c.q == n.q && c.r < n.r))) {
        continue;
      }
      Terrain const tn = session.terrain_effective_at(n);
      if (tn == t) {
        continue;
      }
      if (!visible.count(c) && !visible.count(n)) {
        continue;
      }
      RGB const ca = terrain_fill(t);
      RGB const cb = terrain_fill(tn);
      RGB const mid = blend_rgb(ca, cb, 0.5f);
      fill_hex_edge_inward_quad(ren, v, cx, cy, edge, mid.r, mid.g, mid.b, 118.f / 255.f, ca.r,
                                ca.g, ca.b, 0.f, HEX_R * 0.42f);
      float const ncx = axial_to_world_x(n);
      float const ncy = axial_to_world_y(n);
      int const j = edge_index_toward(n, c);
      if (j < 0) {
        continue;
      }
      fill_hex_edge_inward_quad(ren, v, ncx, ncy, j, mid.r, mid.g, mid.b, 118.f / 255.f, cb.r,
                                cb.g, cb.b, 0.f, HEX_R * 0.42f);
    }
  }
}

void draw_hex_hover_highlight(SDL_Renderer* ren, View const& v, float cx, float cy,
                              bool fully_known) {
  float const lit_a = fully_known ? 52.f / 255.f : 34.f / 255.f;
  fill_hex_overlay(ren, v, cx, cy, 1.f, 1.f, 1.f, lit_a, 170.f / 255.f, 215.f / 255.f,
                   255.f / 255.f, fully_known ? 14.f / 255.f : 10.f / 255.f);
  float const edge_a = fully_known ? 175.f / 255.f : 112.f / 255.f;
  stroke_hex_screen(ren, v, cx, cy, 205.f / 255.f, 235.f / 255.f, 1.f, edge_a);
  stroke_hex_screen(ren, v, cx, cy, 1.f, 1.f, 1.f, std::min(1.f, edge_a + 28.f / 255.f));
}

void draw_thick_screen_line(SDL_Renderer* ren, float x0, float y0, float x1, float y1, float r,
                            float g, float b, float a, float width) {
  float const dx = x1 - x0;
  float const dy = y1 - y0;
  float const len = std::hypot(dx, dy);
  if (len < 0.5f) {
    return;
  }
  float const nx = -dy / len;
  float const ny = dx / len;
  int const half = std::max(1, static_cast<int>(width * 0.5f));
  for (int i = -half; i <= half; ++i) {
    float const ox = nx * static_cast<float>(i);
    float const oy = ny * static_cast<float>(i);
    SDL_SetRenderDrawColorFloat(ren, r, g, b, a);
    SDL_RenderLine(ren, x0 + ox, y0 + oy, x1 + ox, y1 + oy);
  }
}

[[nodiscard]] std::size_t path_last_index_reachable_this_turn(
    Unit const& unit, std::vector<HexCoord> const& path, GameSession const& session) {
  if (path.size() < 2) {
    return 0;
  }
  int budget = std::max(0, unit.moves_remaining());
  int spent = 0;
  std::size_t last = 0;
  for (std::size_t i = 1; i < path.size(); ++i) {
    int const cost = session.movement_cost_for_step(unit, path[i]);
    if (spent + cost > budget) {
      break;
    }
    spent += cost;
    last = i;
  }
  return last;
}

[[nodiscard]] std::vector<HexCoord> path_turn_endpoints(Unit const& unit,
                                                        std::vector<HexCoord> const& path,
                                                        GameSession const& session) {
  if (path.size() < 2) {
    return {};
  }
  std::vector<HexCoord> markers;
  int moves_left = std::max(1, unit.moves_remaining());
  int const per_turn = std::max(1, tack::strat::unit_movement(unit.kind()));
  int spent = 0;
  for (std::size_t i = 1; i < path.size(); ++i) {
    int const step = session.movement_cost_for_step(unit, path[i]);
    while (step > moves_left - spent) {
      if (!markers.empty() && markers.back() == path[i - 1]) {
        break;
      }
      markers.push_back(path[i - 1]);
      spent = 0;
      moves_left = per_turn;
    }
    spent += step;
    if (spent >= moves_left && i + 1 < path.size()) {
      if (markers.empty() || markers.back() != path[i]) {
        markers.push_back(path[i]);
      }
      spent = 0;
      moves_left = per_turn;
    }
  }
  return markers;
}

void draw_projected_path(SDL_Renderer* ren, View const& v, std::vector<HexCoord> const& path,
                         int turns, Unit const* unit = nullptr, GameSession const* session = nullptr,
                         bool dimmed = false) {
  if (path.size() < 2) {
    return;
  }
  bool const detail = v.scale >= k_lod_detail_min_scale;
  float const outer_w = (dimmed ? 3.f : (detail ? 5.2f : 4.f)) * v.scale;
  float const inner_w = (dimmed ? 2.f : (detail ? 3.f : 2.4f)) * v.scale;
  float const dim_scale = dimmed ? 0.45f : 1.f;
  std::size_t const last_this_turn =
      unit != nullptr && session != nullptr
          ? path_last_index_reachable_this_turn(*unit, path, *session)
          : path.size() - 1;
  for (std::size_t i = 0; i + 1 < path.size(); ++i) {
    float sx0 = 0.f;
    float sy0 = 0.f;
    float sx1 = 0.f;
    float sy1 = 0.f;
    world_to_screen(v, axial_to_world_x(path[i]), axial_to_world_y(path[i]), &sx0, &sy0);
    world_to_screen(v, axial_to_world_x(path[i + 1]), axial_to_world_y(path[i + 1]), &sx1, &sy1);
    bool const this_turn = (i + 1) <= last_this_turn;
    float const outer_a = (detail ? 95.f : 75.f) / 255.f * dim_scale * (this_turn ? 1.f : 0.55f);
    float const inner_a = (detail ? 228.f : 200.f) / 255.f * dim_scale * (this_turn ? 1.f : 0.42f);
    draw_thick_screen_line(ren, sx0, sy0, sx1, sy1, 1.f, 1.f, 1.f, outer_a, outer_w);
    draw_thick_screen_line(ren, sx0, sy0, sx1, sy1, 45.f / 255.f, 195.f / 255.f, 1.f, inner_a,
                           inner_w);
  }
  if (dimmed) {
    return;
  }
  HexCoord const end = path.back();
  float ex = 0.f;
  float ey = 0.f;
  world_to_screen(v, axial_to_world_x(end), axial_to_world_y(end), &ex, &ey);
  SDL_FRect badge_bg{ex - 16.f, ey - 26.f, 32.f, 14.f};
  SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, 170.f / 255.f);
  SDL_RenderFillRect(ren, &badge_bg);
  char label[8];
  std::snprintf(label, sizeof(label), "%dT", turns);
  menu_ui::draw_text(ren, ex - menu_ui::text_width(label) * 0.5f, ey - 15.f, label, menu_ui::k_title);

  if (unit != nullptr && session != nullptr && turns > 1) {
    std::vector<HexCoord> const waypoints = path_turn_endpoints(*unit, path, *session);
    for (HexCoord const wp : waypoints) {
      if (wp == path.back()) {
        continue;
      }
      float wx = 0.f;
      float wy = 0.f;
      world_to_screen(v, axial_to_world_x(wp), axial_to_world_y(wp), &wx, &wy);
      float const d = std::max(5.f, 7.f * v.scale);
      SDL_SetRenderDrawColorFloat(ren, 1.f, 1.f, 1.f, 0.85f);
      SDL_FRect ring{wx - d * 0.5f, wy - d * 0.5f, d, d};
      SDL_RenderRect(ren, &ring);
      SDL_SetRenderDrawColorFloat(ren, 35.f / 255.f, 200.f / 255.f, 1.f, 0.92f);
      SDL_FRect core{wx - d * 0.28f, wy - d * 0.28f, d * 0.56f, d * 0.56f};
      SDL_RenderFillRect(ren, &core);
    }
  }
}

void draw_other_queued_routes(SDL_Renderer* ren, View const& v, GameSession const& session,
                              int seat, std::optional<int> selected_unit_id) {
  for (Unit const& u : session.units()) {
    if (u.owner_seat() != seat) {
      continue;
    }
    if (selected_unit_id.has_value() && u.id() == *selected_unit_id) {
      continue;
    }
    std::vector<HexCoord> const route = session.planned_route_for(u.id());
    if (route.empty()) {
      continue;
    }
    std::vector<HexCoord> path;
    path.push_back(u.coord());
    path.insert(path.end(), route.begin(), route.end());
    int const turns = projected_path_turns(u, path, session);
    draw_projected_path(ren, v, path, turns, &u, &session, true);
  }
}

void draw_claim_legend(SDL_Renderer* ren, int win_w, float max_bottom_y, float min_top_y) {
  float const inner_w = std::min(220.f, k_city_panel_w + 8.f);
  float const pad = 10.f;
  char const* const body = claim_legend_body_text();
  auto const lines = menu_ui::wrap_text_to_width(body, inner_w);
  float const card_h = claim_legend_card_height();
  float y = max_bottom_y - card_h - 8.f;
  if (y < min_top_y || y < k_top_bar_h + 16.f) {
    return;
  }
  SDL_FRect card{10.f, y, inner_w + pad * 2.f, card_h};
  menu_ui::fill_rect(ren, card, menu_ui::k_modal_bg, 0.80f);
  menu_ui::stroke_rect(ren, card, menu_ui::k_modal_line, 0.82f);
  menu_ui::draw_text(ren, card.x + pad, card.y + pad, "Territory borders", menu_ui::k_hint);
  menu_ui::draw_text_wrapped(ren, card.x + pad, card.y + pad + 16.f, body, inner_w, 14.f,
                             menu_ui::k_meta, 0.90f);
}

void draw_time_of_day_overlay(SDL_Renderer* ren, GameSession const& session, int win_w, int win_h) {
  if (session.is_over()) {
    return;
  }
  float const round_phase = static_cast<float>(session.round()) /
                            static_cast<float>(std::max(1, session.years_per_full_round()));
  float const season_phase = static_cast<float>(static_cast<int>(session.season())) / 4.f;
  float const ph = (round_phase + season_phase) * 6.2831853f;
  float const sun = std::max(0.f, std::sin(ph));
  float const twi = std::cos(ph) * std::cos(ph);
  float const y0 = k_top_bar_h;
  float const h = static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h;
  float const mid = 0.5f;
  RGB const col{(255.f * (1.f - mid) * (0.11f * twi + 0.05f * sun) + 25.f * mid * (1.f - sun * 0.85f)) /
                    255.f,
                (248.f * (1.f - mid) * (0.10f * twi + 0.04f * sun) + 45.f * mid * (1.f - sun * 0.7f)) /
                    255.f,
                (220.f * (1.f - mid) * (0.08f * twi) + 92.f * mid * (0.55f + (1.f - sun) * 0.35f)) /
                    255.f};
  float const a = 0.08f + 0.04f * (1.f - sun);
  SDL_FRect band{0.f, y0, static_cast<float>(win_w), h};
  menu_ui::fill_rect(ren, band, col, a);
}

void draw_unit_ground_selection_ring(SDL_Renderer* ren, View const& v, Unit const& u,
                                     GameSession const& session) {
  float cx = axial_to_world_x(u.coord());
  float cy = axial_to_world_y(u.coord());
  if (session.city_at(u.coord()).has_value()) {
    cy += HEX_R * 0.22f;
  }
  cy += HEX_R * 0.12f;
  float const ring_strength = u.hp() < u.max_hp() ? 0.72f : 0.82f;
  float const rx = HEX_R * 0.58f * v.scale;
  float const ry = HEX_R * 0.30f * v.scale;
  float sx = 0.f;
  float sy = 0.f;
  world_to_screen(v, cx, cy, &sx, &sy);
  bool const injured = u.hp() < u.max_hp();
  int const segments = 28;
  for (int i = 0; i < segments; ++i) {
    float const a0 = 6.2831853f * static_cast<float>(i) / static_cast<float>(segments);
    float const a1 = 6.2831853f * static_cast<float>(i + 1) / static_cast<float>(segments);
    float const x0 = sx + std::cos(a0) * (rx + 1.2f);
    float const y0 = sy + std::sin(a0) * (ry + 0.8f);
    float const x1 = sx + std::cos(a1) * (rx + 1.2f);
    float const y1 = sy + std::sin(a1) * (ry + 0.8f);
    SDL_SetRenderDrawColorFloat(ren, 1.f, 1.f, 1.f,
                                std::min(1.f, (72.f + 115.f * ring_strength) / 255.f));
    SDL_RenderLine(ren, x0, y0, x1, y1);
  }
  for (int i = 0; i < segments; ++i) {
    float const a0 = 6.2831853f * static_cast<float>(i) / static_cast<float>(segments);
    float const a1 = 6.2831853f * static_cast<float>(i + 1) / static_cast<float>(segments);
    float const x0 = sx + std::cos(a0) * rx;
    float const y0 = sy + std::sin(a0) * ry;
    float const x1 = sx + std::cos(a1) * rx;
    float const y1 = sy + std::sin(a1) * ry;
    float const core_a = std::min(1.f, (210.f + 35.f * ring_strength) / 255.f);
    if (injured) {
      SDL_SetRenderDrawColorFloat(ren, 1.f, 0.28f, 0.20f, core_a);
    } else {
      SDL_SetRenderDrawColorFloat(ren, 0.88f, 0.20f, 0.16f, core_a);
    }
    SDL_RenderLine(ren, x0, y0, x1, y1);
  }
}

void draw_weather_legend(SDL_Renderer* ren, int win_w, int win_h, float max_bottom_y) {
  static Weather const k_legend_weather[] = {Weather::RAIN,     Weather::DROUGHT, Weather::STORM,
                                             Weather::COLD_SNAP, Weather::HEAT_WAVE, Weather::FOG};
  float const chip = 18.f;
  float const gap = 7.f;
  float const pad = 12.f;
  float const chips_w = static_cast<float>(sizeof(k_legend_weather) / sizeof(k_legend_weather[0])) *
                          chip +
                      static_cast<float>(
                          std::max<std::size_t>(0, sizeof(k_legend_weather) / sizeof(k_legend_weather[0]) - 1)) *
                      gap;
  float const inner_w = pad * 2.f + chips_w;
  float const inner_h = pad + 14.f + 10.f + chip + pad;
  float const box_pad = 10.f;
  float const x = static_cast<float>(win_w) - inner_w - box_pad - 6.f;
  float y = max_bottom_y - inner_h - box_pad;
  if (x < 16.f || y < k_top_bar_h + 16.f) {
    return;
  }
  SDL_FRect card{x, y, inner_w, inner_h};
  menu_ui::fill_rect(ren, card, menu_ui::k_modal_bg, 0.86f);
  menu_ui::stroke_rect(ren, card, menu_ui::k_modal_line, 0.78f);
  menu_ui::draw_text(ren, x + pad, y + pad, "Weather tint key", menu_ui::k_hint);
  float row_top = y + pad + 14.f + 10.f;
  float cx = x + pad;
  for (Weather w : k_legend_weather) {
    RGBA wash = weather_wash_rgba(w, true);
    SDL_FRect chip_rect{cx, row_top, chip, chip};
    menu_ui::fill_rect(ren, chip_rect, {wash.r, wash.g, wash.b}, 248.f / 255.f);
    char const badge = weather_badge_char(w);
    if (badge != ' ') {
      char label[2] = {badge, '\0'};
      RGB ink = weather_badge_ink(w);
      menu_ui::draw_text(ren, cx + 5.f, row_top + 3.f, label, ink);
    }
    cx += chip + gap;
  }
}

void draw_settler_lens_markers(SDL_Renderer* ren, View const& v,
                               std::vector<SettlerLensHint> const& hints) {
  for (SettlerLensHint const& hint : hints) {
    float sx = 0.f;
    float sy = 0.f;
    float const wx = axial_to_world_x(hint.hex);
    float const wy = axial_to_world_y(hint.hex) - HEX_R * 0.62f;
    world_to_screen(v, wx, wy, &sx, &sy);
    float const r = std::max(6.f, 10.f * v.scale);
    RGB fill = hint.rank == 1   ? rgb_u8(0xff, 0xd6, 0x60)
               : hint.rank == 2 ? rgb_u8(0xf5, 0xc2, 0x4e)
               : hint.rank == 3 ? rgb_u8(0xe4, 0xb6, 0x3e)
                                : rgb_u8(0xc8, 0xa0, 0x36);
    float const tail_h = std::max(8.f, 12.f * v.scale);
    SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, 0.22f);
    SDL_FRect shadow{r - r * 0.58f + 1.2f, sy + r * 0.42f + 1.4f, r * 1.16f, tail_h};
    SDL_RenderFillRect(ren, &shadow);
    SDL_SetRenderDrawColorFloat(ren, 212.f / 255.f, 168.f / 255.f, 52.f / 255.f, 0.97f);
    SDL_FRect tail{sx - r * 0.58f, sy + r * 0.42f, r * 1.16f, tail_h};
    SDL_RenderFillRect(ren, &tail);
    SDL_SetRenderDrawColorFloat(ren, fill.r, fill.g, fill.b, 0.96f);
    SDL_FRect head{sx - r, sy - r, 2.f * r, 2.f * r};
    SDL_RenderFillRect(ren, &head);
    SDL_SetRenderDrawColorFloat(ren, 0.28f, 0.20f, 0.10f, 0.95f);
    SDL_RenderRect(ren, &head);
    SDL_RenderRect(ren, &tail);
    float const bx = sx - r * 0.35f;
    float const by = sy - r * 0.28f;
    SDL_FRect block{bx, by, r * 0.7f, r * 0.85f};
    SDL_SetRenderDrawColorFloat(ren, 1.f, 0.99f, 0.96f, 0.97f);
    SDL_RenderFillRect(ren, &block);
    SDL_SetRenderDrawColorFloat(ren, 0.37f, 0.28f, 0.15f, 0.94f);
    SDL_RenderRect(ren, &block);
    if (hint.rank <= 3) {
      char label[4];
      std::snprintf(label, sizeof(label), "%d", hint.rank);
      menu_ui::draw_text(ren, sx - 3.f, sy - 5.f, label, rgb_u8(72, 52, 22));
    }
    if (v.scale >= 0.68f) {
      char eta[8];
      std::snprintf(eta, sizeof(eta), "%dt", hint.travel_turns);
      SDL_FRect eta_bg{sx - 10.f, sy + r + tail_h - 2.f, 20.f, 12.f};
      SDL_SetRenderDrawColorFloat(ren, 16.f / 255.f, 22.f / 255.f, 30.f / 255.f, 215.f / 255.f);
      SDL_RenderFillRect(ren, &eta_bg);
      menu_ui::draw_text(ren, sx - 6.f, sy + r + tail_h - 1.f, eta, rgb_u8(235, 242, 255));
    }
  }
}

void draw_combat_flash(SDL_Renderer* ren, View const& v, HexCoord h, Uint64 start_ticks,
                       Uint64 until_ticks) {
  Uint64 const now = SDL_GetTicks();
  if (now >= until_ticks) {
    return;
  }
  float const span = static_cast<float>(std::max<Uint64>(1, until_ticks - start_ticks));
  float u = static_cast<float>(now - start_ticks) / span;
  u = std::clamp(u, 0.f, 1.f);
  float const cx = axial_to_world_x(h);
  float const cy = axial_to_world_y(h);
  int const core_a = static_cast<int>(155.f * (1.f - u * 0.92f));
  fill_hex_solid(ren, v, cx, cy,
                 {1.f, 65.f / 255.f, 65.f / 255.f, std::min(220, std::max(0, core_a)) / 255.f});
  float const edge = 1.f - u * 0.88f;
  stroke_hex_screen(ren, v, cx, cy, 1.f, 210.f / 255.f, 140.f / 255.f, edge * 0.85f);
  if (u < 0.88f) {
    stroke_hex_screen(ren, v, cx, cy, 1.f, 1.f, 1.f, (1.f - u) * 0.49f);
  }
}

void dismiss_intro_tips(GuiState* gui) {
  if (gui == nullptr || !gui->show_intro_tips) {
    return;
  }
  gui->show_intro_tips = false;
  gui->tips_dismissed = true;
  save_gui_options(*gui);
}

struct GameMenuLayout {
  SDL_FRect menu_btn{};
  SDL_FRect panel{};
  std::array<SDL_FRect, 10> items{};
};

[[nodiscard]] GameMenuLayout compute_game_menu_layout(int win_w) noexcept {
  GameMenuLayout layout{};
  float const btn_w = 44.f;
  float const btn_h = 24.f;
  layout.menu_btn = {static_cast<float>(win_w) - btn_w - 8.f, 12.f, btn_w, btn_h};
  float const row_h = 28.f;
  float const panel_w = 248.f;
  float const panel_h = row_h * 10.f + 10.f;
  layout.panel = {layout.menu_btn.x + layout.menu_btn.w - panel_w,
                  layout.menu_btn.y + layout.menu_btn.h + 2.f, panel_w, panel_h};
  float y = layout.panel.y + 5.f;
  for (SDL_FRect& row : layout.items) {
    row = {layout.panel.x + 4.f, y, panel_w - 8.f, row_h - 2.f};
    y += row_h;
  }
  return layout;
}

void draw_game_menu_ui(SDL_Renderer* ren, GuiState const& gui, GameSession const& session,
                       int win_w) {
  GameMenuLayout const layout = compute_game_menu_layout(win_w);
  menu_ui::draw_button(ren, layout.menu_btn, "Menu", menu_ui::k_secondary, menu_ui::k_secondary_line,
                       menu_ui::k_ink, true);
  if (!gui.show_game_menu) {
    return;
  }
  menu_ui::fill_rect(ren, layout.panel, menu_ui::k_modal_bg, 0.98f);
  menu_ui::stroke_rect(ren, layout.panel, menu_ui::k_modal_line, 0.9f);
  std::string auto_label = std::string("Auto unit cycling: ") +
                           (gui.auto_focus_next_unit ? "On (toggle)" : "Off (toggle)");
  std::string lens_label = std::string("Settler lens: ") +
                           (gui.show_settler_lens ? "On (toggle)" : "Off (toggle)");
  std::string minimap_label = std::string("Minimap own claims: ") +
                              (gui.minimap_tint_own_claims_only ? "On (toggle)" : "Off (toggle)");
  std::string end_turn_label = "End turn";
  if (!session.is_over() && !session.current_player().computer) {
    bool const units_pending = sdl_has_pending_manual_unit_orders(session, gui);
    bool const prod_pending = sdl_player_has_city_without_production(session);
    if (units_pending) {
      end_turn_label = "End turn (units need orders)";
    } else if (prod_pending) {
      end_turn_label = "End turn (production needed)";
    }
  }
  char const* labels[10] = {"Save game...",        "Load game...",       "Next unit",
                            end_turn_label.c_str(), auto_label.c_str(), lens_label.c_str(),
                            minimap_label.c_str(),  "Fit discovered map", "Clear unit skips",
                            "Main menu"};
  for (int i = 0; i < 10; ++i) {
    menu_ui::draw_button(ren, layout.items[static_cast<std::size_t>(i)], labels[i],
                         menu_ui::k_secondary, menu_ui::k_secondary_line, menu_ui::k_ink, true);
  }
}

bool try_handle_game_menu_click(GameSession& session, GuiState* gui, View* view,
                                std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                GameMenuLayout const& layout, float mx, float my, int win_w,
                                int win_h, SdlSessionFlow const* flow) {
  if (gui == nullptr) {
    return false;
  }
  if (screen_point_in_rect(mx, my, layout.menu_btn)) {
    gui->show_game_menu = !gui->show_game_menu;
    return true;
  }
  if (!gui->show_game_menu) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    gui->show_game_menu = false;
    return true;
  }
  for (int i = 0; i < 10; ++i) {
    if (!screen_point_in_rect(mx, my, layout.items[static_cast<std::size_t>(i)])) {
      continue;
    }
    gui->show_game_menu = false;
    switch (i) {
      case 0:
        gui->request_save = true;
        break;
      case 1:
        gui->request_load = true;
        break;
      case 2:
        cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
        break;
      case 3: {
        PrimaryActionState const act =
            compute_primary_action_state(session, *gui, *sel_unit, *sel_city);
        on_hud_action_click(session, gui, view, sel_unit, sel_city, act, std::nullopt, win_w, win_h,
                            flow);
        break;
      }
      case 4:
        toggle_auto_focus_next_unit(gui);
        break;
      case 5:
        toggle_settler_lens(gui);
        break;
      case 6:
        toggle_minimap_own_claims(gui);
        break;
      case 7:
        fit_map_to_window(view, win_w, win_h);
        present_action_feedback(gui, "View fitted to discovered territory.", 2400);
        break;
      case 8:
        if (!gui->skipped_units.empty()) {
          gui->skipped_units.clear();
          present_action_feedback(gui, "Cleared unit skip marks for this turn.", 2400);
        } else {
          set_toast(gui, "No units are marked skipped this turn.", 2400);
        }
        break;
      case 9:
        gui->request_main_menu = true;
        break;
      default:
        break;
    }
    return true;
  }
  return true;
}

void draw_intro_tips_overlay(SDL_Renderer* ren, int win_w, int win_h) {
  float const y0 = k_top_bar_h;
  float const h = static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h;
  menu_ui::fill_rect(ren, {0.f, y0, static_cast<float>(win_w), h}, menu_ui::k_ink, 0.48f);
  float const panel_w = std::min(420.f, static_cast<float>(win_w) - 48.f);
  float const panel_h = 212.f;
  float const px = (static_cast<float>(win_w) - panel_w) * 0.5f;
  float const py = y0 + h * 0.5f - panel_h * 0.5f;
  SDL_FRect card{px, py, panel_w, panel_h};
  menu_ui::draw_modal_card(ren, card);
  menu_ui::draw_text(ren, card.x + 16.f, card.y + 14.f, "Welcome", menu_ui::k_accent);
  char const* lines[] = {
      "Pan and zoom with drag, wheel, and arrow keys.",
      "The round action button (bottom-right) shows Move, Next, or End Turn.",
      "Enter or P triggers that action; green pulse means you can end the turn.",
      "Click distant tiles to move; Shift+click queues multi-turn routes.",
      "Auto unit cycling is on (U to toggle) - finishes jump to the next unit or city.",
      "B founds a city; production opens when you select a city.",
      "Shift+E toggles auto-explore; H toggles settler site pins.",
      "F1 lists all hotkeys. Esc opens the game menu.",
      "Press Enter, Esc, or click to dismiss these tips.",
  };
  float ty = card.y + 38.f;
  for (char const* line : lines) {
    menu_ui::draw_text(ren, card.x + 16.f, ty, line, menu_ui::k_hint);
    ty += 16.f;
  }
}

[[nodiscard]] PrimaryActionState compute_primary_action_state(
    GameSession const& session, GuiState const& gui, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id) {
  PrimaryActionState state;
  if (session.is_over() || session.current_player().computer) {
    return state;
  }

  bool const units_pending = sdl_has_pending_manual_unit_orders(session, gui);
  bool const prod_pending = sdl_player_has_city_without_production(session);
  int const seat = session.current_player().seat;

  if (!units_pending && !prod_pending) {
    state.kind = HudActionKind::EndTurn;
    state.label = "End Turn";
    state.detail = "All orders given — click or press Enter to play your turn";
    state.enabled = true;
    state.end_turn_ready = true;
    return state;
  }

  state.enabled = true;

  if (gui.move_command_mode && selected_unit_id.has_value()) {
    if (auto u = session.unit_by_id(*selected_unit_id);
        u.has_value() && u->owner_seat() == seat) {
      state.kind = HudActionKind::CommitMove;
      state.label = "Confirm";
      state.detail =
          "Arrows adjust route; Enter confirms or advances if the move is not valid";
      return state;
    }
  }

  if (selected_unit_id.has_value()) {
    auto u = session.unit_by_id(*selected_unit_id);
    if (u.has_value() && u->owner_seat() != seat) {
      state.kind = HudActionKind::Wait;
      state.label = "Inspect";
      state.detail = "Enemy unit — select one of yours";
      state.enabled = false;
      return state;
    }
    if (u.has_value()) {
      if (u->kind() == UnitKind::SETTLER) {
        state.kind = HudActionKind::FoundCity;
        state.label = "Found";
        if (session.can_found_city(*u)) {
          state.detail = "Found a city on this tile (B)";
        } else if (auto why = session.explain_cannot_found_city(*u)) {
          state.detail = *why;
        } else {
          state.detail = "Cannot found here.";
        }
        return state;
      }
      if (!session.planned_route_for(*selected_unit_id).empty() && u->moves_remaining() > 0) {
        state.kind = HudActionKind::FollowRoute;
        state.label = "Go";
        state.detail = "Follow the queued route (click map or P)";
        return state;
      }
      if (u->sleeping()) {
        state.kind = HudActionKind::Wake;
        state.label = "Wake";
        state.detail = "Wake this unit so it can take orders";
        return state;
      }
      if (u->moves_remaining() <= 0) {
        state.kind = HudActionKind::NextUnit;
        state.label = "Next";
        state.detail = "This unit is done — find the next needing orders";
        return state;
      }
      state.kind = HudActionKind::Move;
      state.label = "Move";
      if (civilian_kind(u->kind())) {
        state.detail = "Click a tile to move; Shift+click queues a multi-turn route";
      } else {
        state.detail = "Click to move, or M for move mode";
      }
      if (u->auto_explore()) {
        state.detail += " (auto-explore at end turn)";
      }
      return state;
    }
  }

  if (!selected_unit_id.has_value() && selected_city_id.has_value()) {
    if (auto c = session.city_by_id(*selected_city_id);
        c.has_value() && c->owner_seat() == seat) {
      if (units_pending) {
        state.kind = HudActionKind::NextUnit;
        state.label = "Next";
        state.detail = "Units still need orders — jump to the next";
        return state;
      }
      state.kind = HudActionKind::Production;
      state.label = "Build";
      state.detail = prod_pending ? "Choose city production in the Build panel (keys 1-6)"
                                : "Inspect city production in the Build panel";
      return state;
    }
  }

  if (prod_pending) {
    state.kind = HudActionKind::Production;
    state.label = "Build";
    state.detail = "Choose city production in the Build panel (keys 1-6)";
    return state;
  }

  if (units_pending) {
    state.kind = HudActionKind::NextUnit;
    state.label = "Next";
    state.detail = "Find the next unit needing orders";
    return state;
  }

  state.kind = HudActionKind::Wait;
  state.enabled = false;
  return state;
}

[[nodiscard]] BottomActionBarLayout compute_bottom_action_bar_layout(
    GameSession const& session, GuiState const& gui, std::optional<int> selected_unit_id,
    std::optional<int> selected_city_id, int win_w, int win_h) {
  BottomActionBarLayout layout;
  if (session.is_over() || session.current_player().computer) {
    return layout;
  }
  layout.visible = true;
  layout.action =
      compute_primary_action_state(session, gui, selected_unit_id, selected_city_id);

  float const foot_y = static_cast<float>(win_h) - k_footer_bar_h;
  float const orb_cx = static_cast<float>(win_w) - k_hud_action_orb_d * 0.5f - 20.f;
  float const orb_cy = foot_y - k_hud_action_orb_d * 0.12f;
  layout.action_orb = {orb_cx - k_hud_action_orb_d * 0.5f, orb_cy - k_hud_action_orb_d * 0.5f,
                       k_hud_action_orb_d, k_hud_action_orb_d};
  layout.pending_unit_count = count_units_needing_manual_orders(session, gui);
  float const caption_h = 36.f;
  layout.action_hit = {layout.action_orb.x - 8.f, layout.action_orb.y - 8.f,
                       layout.action_orb.w + 16.f, layout.action_orb.h + caption_h};
  return layout;
}

[[nodiscard]] bool screen_point_in_circle(float px, float py, SDL_FRect const& orb) noexcept {
  float const cx = orb.x + orb.w * 0.5f;
  float const cy = orb.y + orb.h * 0.5f;
  float const r = orb.w * 0.5f;
  float const dx = px - cx;
  float const dy = py - cy;
  return dx * dx + dy * dy <= r * r;
}

void fill_circle(SDL_Renderer* ren, float cx, float cy, float r, RGB c, float a) {
  int const steps = std::max(4, static_cast<int>(r * 1.6f));
  for (int i = 0; i < steps; ++i) {
    float const t = (static_cast<float>(i) + 0.5f) / static_cast<float>(steps);
    float const y = cy - r + t * 2.f * r;
    float const dy = y - cy;
    float const half = std::sqrt(std::max(0.f, r * r - dy * dy));
    menu_ui::fill_rect(ren, {cx - half, y, half * 2.f, 1.f}, c, a);
  }
}

void fill_triangle(SDL_Renderer* ren, float x0, float y0, float x1, float y1, float x2, float y2,
                   RGB c, float a) {
  float const min_y = std::min({y0, y1, y2});
  float const max_y = std::max({y0, y1, y2});
  int const steps = std::max(2, static_cast<int>((max_y - min_y) * 1.2f));
  for (int i = 0; i < steps; ++i) {
    float const y = min_y + (static_cast<float>(i) + 0.5f) / static_cast<float>(steps) *
                                  (max_y - min_y);
    auto edge_x = [&](float ax, float ay, float bx, float by) -> std::optional<float> {
      if ((ay <= y && by > y) || (by <= y && ay > y)) {
        float const t = (y - ay) / (by - ay);
        return ax + t * (bx - ax);
      }
      return std::nullopt;
    };
    std::vector<float> xs;
    for (auto const x : {edge_x(x0, y0, x1, y1), edge_x(x1, y1, x2, y2), edge_x(x2, y2, x0, y0)}) {
      if (x.has_value()) {
        xs.push_back(*x);
      }
    }
    if (xs.size() >= 2) {
      float const left = std::min(xs[0], xs[1]);
      float const right = std::max(xs[0], xs[1]);
      menu_ui::fill_rect(ren, {left, y, right - left, 1.f}, c, a);
    }
  }
}

void draw_hud_action_icon(SDL_Renderer* ren, HudActionKind kind, float cx, float cy, float r,
                          RGB color, float alpha) {
  float const s = r * 0.42f;
  auto draw_chevron = [&](float tip_x, float half_h, float back_x) {
    fill_triangle(ren, back_x, cy - half_h, back_x, cy + half_h, tip_x, cy, color, alpha);
  };
  switch (kind) {
    case HudActionKind::EndTurn: {
      float const x0 = cx - s * 0.62f;
      float const y0 = cy - s * 0.82f;
      float const x1 = cx - s * 0.62f;
      float const y1 = cy + s * 0.82f;
      float const x2 = cx + s * 1.05f;
      float const y2 = cy;
      fill_triangle(ren, x0, y0, x1, y1, x2, y2, color, alpha);
      break;
    }
    case HudActionKind::NextUnit:
      draw_chevron(cx + s * 0.15f, s * 0.72f, cx - s * 0.42f);
      draw_chevron(cx + s * 0.72f, s * 0.72f, cx + s * 0.15f);
      break;
    case HudActionKind::Move:
      draw_chevron(cx + s * 0.55f, s * 0.78f, cx - s * 0.35f);
      break;
    case HudActionKind::FollowRoute:
      draw_chevron(cx + s * 0.62f, s * 0.82f, cx - s * 0.42f);
      break;
    case HudActionKind::FoundCity:
      menu_ui::draw_text_centered(ren, cx, cy - 7.f, "+", color, alpha);
      break;
    case HudActionKind::Wake:
      menu_ui::draw_text_centered(ren, cx, cy - 7.f, "!", color, alpha);
      break;
    case HudActionKind::Production:
      menu_ui::draw_text_centered(ren, cx, cy - 7.f, "B", color, alpha);
      break;
    case HudActionKind::CommitMove:
      menu_ui::draw_text_centered(ren, cx, cy - 7.f, "OK", color, alpha);
      break;
    default:
      menu_ui::draw_text_centered(ren, cx, cy - 7.f, "...", menu_ui::k_muted, alpha * 0.7f);
      break;
  }
}

void draw_bottom_action_bar(SDL_Renderer* ren, BottomActionBarLayout const& layout, bool hovered) {
  if (!layout.visible) {
    return;
  }
  PrimaryActionState const& act = layout.action;
  float const cx = layout.action_orb.x + layout.action_orb.w * 0.5f;
  float const cy = layout.action_orb.y + layout.action_orb.h * 0.5f;
  float const r = layout.action_orb.w * 0.5f;
  Uint64 const now = SDL_GetTicks();

  float const pulse = 0.55f + 0.35f * std::sin(static_cast<float>(now) * 0.005f);
  if (act.end_turn_ready) {
    fill_circle(ren, cx, cy, r + 10.f, menu_ui::k_ok, pulse * 0.28f);
    fill_circle(ren, cx, cy, r + 5.f, menu_ui::k_ok, pulse * 0.42f);
  } else if (layout.pending_unit_count > 0) {
    fill_circle(ren, cx, cy, r + 7.f, rgb_u8(255, 150, 50), pulse * 0.22f);
  }
  if (hovered && act.enabled) {
    fill_circle(ren, cx, cy, r + 5.f, menu_ui::k_title, 0.18f);
  }

  fill_circle(ren, cx, cy, r + 2.f, menu_ui::k_ink, 0.92f);
  RGB const face = act.end_turn_ready ? menu_ui::k_ok
                                      : (act.enabled ? menu_ui::k_primary : rgb_u8(48, 58, 72));
  float const face_a = act.enabled ? (hovered ? 1.f : 0.96f) : 0.72f;
  fill_circle(ren, cx, cy, r, face, face_a);
  RGB const ring = act.end_turn_ready ? menu_ui::k_victory_gold : menu_ui::k_title;
  float const ring_a = act.enabled ? (hovered ? 1.f : 0.92f) : 0.45f;
  for (int i = 0; i < 28; ++i) {
    float const a0 = 6.2831853f * static_cast<float>(i) / 28.f;
    float const a1 = 6.2831853f * static_cast<float>(i + 1) / 28.f;
    SDL_SetRenderDrawColorFloat(ren, ring.r, ring.g, ring.b, ring_a);
    SDL_RenderLine(ren, cx + std::cos(a0) * r, cy + std::sin(a0) * r, cx + std::cos(a1) * r,
                   cy + std::sin(a1) * r);
  }

  RGB const icon_color = act.end_turn_ready ? menu_ui::k_ink : menu_ui::k_title;
  draw_hud_action_icon(ren, act.kind, cx, cy, r, icon_color, act.enabled ? 1.f : 0.55f);

  if (layout.pending_unit_count > 0 && !act.end_turn_ready) {
    float const bx = cx + r * 0.58f;
    float const by = cy - r * 0.62f;
    fill_circle(ren, bx, by, 11.f, rgb_u8(255, 120, 40), 0.96f);
    std::string const badge = std::to_string(layout.pending_unit_count);
    menu_ui::draw_text_centered(ren, bx, by - 7.f, badge.c_str(), menu_ui::k_ink, 1.f);
  }

  std::string caption = act.label;
  if (act.end_turn_ready) {
    caption = "End Turn";
  }
  float const cap_w = menu_ui::text_width(caption.c_str());
  menu_ui::draw_text(ren, cx - cap_w * 0.5f, layout.action_orb.y + layout.action_orb.h + 4.f,
                     caption.c_str(), act.end_turn_ready ? menu_ui::k_ok : menu_ui::k_hint, 0.95f);
  if (act.end_turn_ready) {
    menu_ui::draw_text_centered(ren, cx, layout.action_orb.y + layout.action_orb.h + 18.f, "Enter",
                                menu_ui::k_muted, 0.82f);
  } else if (act.enabled && act.kind != HudActionKind::Wait) {
    menu_ui::draw_text_centered(ren, cx, layout.action_orb.y + layout.action_orb.h + 18.f,
                                "P / Enter", menu_ui::k_muted, 0.78f);
  }
}

void clear_move_command_mode(GuiState* gui) {
  if (gui == nullptr) {
    return;
  }
  gui->move_command_mode = false;
  gui->move_cursor.reset();
  gui->move_cursor_locked = false;
}

void advance_hud_action_queue(GameSession& session, GuiState* gui, View* view,
                              std::optional<int>* sel_unit, std::optional<int>* sel_city,
                              int win_w, int win_h) {
  std::optional<int> const before_unit = *sel_unit;
  std::optional<int> const before_city = *sel_city;

  if (sdl_has_pending_manual_unit_orders(session, *gui)) {
    cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
    if (*sel_unit != before_unit || *sel_city != before_city) {
      return;
    }
  }
  if (sdl_player_has_city_without_production(session)) {
    select_first_city_missing_production_sdl(session, view, sel_unit, sel_city, win_w, win_h);
    if (*sel_city != before_city) {
      set_toast(gui, "Choose production in the Build panel (keys 1-6).", 3600);
      return;
    }
  }
  if (!sdl_has_pending_manual_unit_orders(session, *gui) &&
      !sdl_player_has_city_without_production(session)) {
    set_toast(gui, "No units need orders this turn.", 2800);
  }
}

void on_hud_action_click(GameSession& session, GuiState* gui, View* view,
                         std::optional<int>* sel_unit, std::optional<int>* sel_city,
                         PrimaryActionState const& action, std::optional<HexCoord> hovered_hex,
                         int win_w, int win_h, SdlSessionFlow const* flow) {
  if (action.end_turn_ready) {
    do_end_turn_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h, flow);
    return;
  }
  if (!action.enabled) {
    if (!action.detail.empty()) {
      set_toast(gui, action.detail, 3600);
    }
    return;
  }
  if (action.kind == HudActionKind::CommitMove) {
    if (commit_move_command(session, gui, sel_unit, sel_city, hovered_hex, view, win_w, win_h)) {
      return;
    }
    clear_move_command_mode(gui);
    advance_hud_action_queue(session, gui, view, sel_unit, sel_city, win_w, win_h);
    return;
  }
  if (action.kind == HudActionKind::NextUnit) {
    advance_hud_action_queue(session, gui, view, sel_unit, sel_city, win_w, win_h);
    return;
  }
  if (action.kind == HudActionKind::Production) {
    select_first_city_missing_production_sdl(session, view, sel_unit, sel_city, win_w, win_h);
    set_toast(gui, "Choose production in the Build panel (keys 1-6).", 3600);
    return;
  }
  on_primary_action_sdl(session, gui, view, sel_unit, sel_city, hovered_hex, win_w, win_h, flow);
}

void draw_menu_toast(SDL_Renderer* ren, GuiState const& gui, int win_w, int win_h,
                     float toast_max_right = 0.f) {
  Uint64 const now = SDL_GetTicks();
  if (now >= gui.toast_until_ticks || gui.toast_text.empty()) {
    return;
  }
  Uint64 const remaining = gui.toast_until_ticks - now;
  float fade = 1.f;
  if (remaining < 650) {
    fade = static_cast<float>(remaining) / 650.f;
  }
  bool const warn = gui.toast_is_warning;
  bool const ok = !warn && sdl_action_message_is_success(gui.toast_text);
  float const max_w = std::min(560.f, static_cast<float>(win_w) - 96.f);
  int const budget = std::max(48, static_cast<int>(max_w / 7.f));
  std::string line1 = gui.toast_text;
  std::string line2;
  if (static_cast<int>(line1.size()) > budget) {
    std::size_t split = line1.rfind(' ', static_cast<std::size_t>(budget));
    if (split == std::string::npos || split < 24) {
      split = static_cast<std::size_t>(budget);
    }
    line2 = line1.substr(split);
    while (!line2.empty() && line2.front() == ' ') {
      line2.erase(line2.begin());
    }
    line1 = line1.substr(0, split);
    while (!line1.empty() && line1.back() == ' ') {
      line1.pop_back();
    }
    if (line2.size() > static_cast<std::size_t>(budget)) {
      line2.resize(static_cast<std::size_t>(budget - 3));
      line2 += "...";
    }
  }
  float const toast_h = line2.empty() ? 34.f : 50.f;
  float const toast_w = max_w + 28.f;
  float tx = (static_cast<float>(win_w) - toast_w) * 0.5f;
  if (toast_max_right > 0.f) {
    float const max_tx = toast_max_right - toast_w - 16.f;
    tx = std::min(tx, max_tx);
    tx = std::max(12.f, tx);
  }
  float const ty = static_cast<float>(win_h) - toast_h - 28.f;
  RGB const bg = warn ? rgb_u8(0x2a, 0x12, 0x12) : ok ? rgb_u8(0x12, 0x2a, 0x18) : rgb_u8(0x14, 0x1f, 0x31);
  RGB const border =
      warn ? rgb_u8(0xff, 0x88, 0x66) : ok ? menu_ui::k_ok : menu_ui::k_accent;
  RGB const ink =
      warn ? rgb_u8(0xff, 0xc8, 0xb8) : ok ? rgb_u8(0xc8, 0xff, 0xd8) : menu_ui::k_accent;
  menu_ui::fill_rect(ren, {tx + 3.f, ty + 3.f, toast_w, toast_h}, menu_ui::k_ink, 0.35f * fade);
  menu_ui::fill_rect(ren, {tx, ty, toast_w, toast_h}, bg, 0.96f * fade);
  menu_ui::stroke_rect(ren, {tx, ty, toast_w, toast_h}, border, 0.85f * fade);
  menu_ui::draw_text_centered(ren, static_cast<float>(win_w) * 0.5f, ty + 8.f, line1.c_str(), ink,
                              fade);
  if (!line2.empty()) {
    menu_ui::draw_text_centered(ren, static_cast<float>(win_w) * 0.5f, ty + 24.f, line2.c_str(),
                                menu_ui::k_hint, fade);
  }
}

void center_on_hex(View* v, HexCoord h, int win_w, int win_h) {
  float const wx = axial_to_world_x(h);
  float const wy = axial_to_world_y(h);
  float const map_cx = static_cast<float>(win_w) * 0.5f;
  float const map_cy =
      k_top_bar_h + (static_cast<float>(win_h) - k_top_bar_h - k_footer_bar_h) * 0.5f;
  v->offset_x = map_cx - wx * v->scale;
  v->offset_y = map_cy - wy * v->scale;
  clamp_view(v, win_w, win_h);
}

void keep_hex_visible(View* v, HexCoord h, int win_w, int win_h) {
  float const wx = axial_to_world_x(h);
  float const wy = axial_to_world_y(h);
  float const sx = wx * v->scale + v->offset_x;
  float const sy = wy * v->scale + v->offset_y;
  float shift_x = 0.f;
  float shift_y = 0.f;
  float const m = JAVA_CURSOR_MARGIN;
  float const wf = static_cast<float>(win_w);
  float const hf = static_cast<float>(win_h);
  if (sx < m) {
    shift_x = m - sx;
  } else if (sx > wf - m) {
    shift_x = (wf - m) - sx;
  }
  if (sy < m) {
    shift_y = m - sy;
  } else if (sy > hf - m) {
    shift_y = (hf - m) - sy;
  }
  if (shift_x != 0.f || shift_y != 0.f) {
    v->offset_x += shift_x;
    v->offset_y += shift_y;
    clamp_view(v, win_w, win_h);
  }
}

void zoom_by_keyboard(View* v, float factor, int win_w, int win_h) {
  zoom_at(v, factor, static_cast<float>(win_w) * 0.5f, static_cast<float>(win_h) * 0.5f, win_w, win_h);
}

void pan_java_pixels(View* v, float dx, float dy, int win_w, int win_h) {
  v->offset_x += dx;
  v->offset_y += dy;
  clamp_view(v, win_w, win_h);
}

void sync_skipped_for_new_turn(GameSession const& session, TurnTracker* tt,
                               std::unordered_set<int>* skipped) {
  TurnTracker cur{session.round(), session.current_player_index()};
  if (cur.round != tt->round || cur.player_index != tt->player_index) {
    skipped->clear();
    *tt = cur;
  }
}

void stash_camera_for_seat(GuiState* gui, View const& v, int seat);

bool nudge_move_cursor(GameSession const& session, GuiState* gui, std::optional<int> selected_unit_id,
                       View* v, int dq, int dr, int win_w, int win_h) {
  if (!gui->move_command_mode || !selected_unit_id.has_value()) {
    return false;
  }
  auto u = session.unit_by_id(*selected_unit_id);
  if (!u.has_value()) {
    return false;
  }
  HexCoord const base = gui->move_cursor.has_value() ? *gui->move_cursor : u->coord();
  HexCoord const next{base.q + dq, base.r + dr};
  if (!session.map().contains(next)) {
    return false;
  }
  gui->move_cursor = next;
  gui->move_cursor_locked = true;
  keep_hex_visible(v, next, win_w, win_h);
  return true;
}

[[nodiscard]] bool try_follow_projected_path(GameSession& session, Unit const& u, HexCoord dest);

bool commit_move_command(GameSession& session, GuiState* gui, std::optional<int>* selected_unit_id,
                         std::optional<int>* selected_city_id, std::optional<HexCoord> hovered_hex,
                         View* v, int win_w, int win_h) {
  (void)v;
  (void)win_w;
  (void)win_h;
  if (!gui->move_command_mode || !selected_unit_id->has_value()) {
    return false;
  }
  auto u = session.unit_by_id(**selected_unit_id);
  if (!u.has_value()) {
    return false;
  }
  std::optional<HexCoord> target;
  if (gui->move_cursor_locked && gui->move_cursor.has_value()) {
    target = gui->move_cursor;
  } else if (hovered_hex.has_value()) {
    target = hovered_hex;
  }
  if (!target.has_value()) {
    return false;
  }
  if (try_follow_projected_path(session, *u, *target)) {
    gui->move_command_mode = false;
    gui->move_cursor.reset();
    gui->move_cursor_locked = false;
    present_action_feedback(gui, "Move queued.", 2200);
    maybe_auto_advance_action_queue_sdl(session, gui, v, selected_unit_id, selected_city_id, win_w,
                                        win_h);
    return true;
  }
  return false;
}

void cycle_next_unit(GameSession& session, std::optional<int>* selected_unit_id, View* v, int win_w,
                     int win_h, GuiState* gui, std::optional<int>* selected_city_id) {
  int const seat = session.current_player().seat;
  std::vector<Unit const*> mine;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat) {
      mine.push_back(&u);
    }
  }
  if (mine.empty()) {
    return;
  }
  std::sort(mine.begin(), mine.end(),
             [](Unit const* a, Unit const* b) { return a->id() < b->id(); });
  int const n = static_cast<int>(mine.size());
  int start = 0;
  if (selected_unit_id->has_value()) {
    for (int i = 0; i < n; ++i) {
      if (mine[static_cast<std::size_t>(i)]->id() == **selected_unit_id) {
        start = (i + 1) % n;
        break;
      }
    }
  }

  auto try_pick = [&](auto&& ok) -> Unit const* {
    for (int k = 0; k < n; ++k) {
      Unit const* u = mine[static_cast<std::size_t>((start + k) % n)];
      if (u->sleeping()) {
        continue;
      }
      if (gui->skipped_units.count(u->id())) {
        continue;
      }
      if (ok(*u)) {
        return u;
      }
    }
    return nullptr;
  };

  Unit const* pick = try_pick([&](Unit const& u) { return session.auto_explore_blocked_with_moves(u); });
  if (pick == nullptr) {
    pick = try_pick([&](Unit const& u) {
      if (u.auto_explore()) {
        return false;
      }
      bool const has_moves = u.moves_remaining() > 0;
      bool const has_route = !session.planned_route_for(u.id()).empty();
      return has_moves && !has_route;
    });
  }
  if (pick == nullptr) {
    pick = try_pick([&](Unit const& u) {
      if (u.auto_explore()) {
        return false;
      }
      return u.moves_remaining() > 0;
    });
  }
  if (pick != nullptr) {
    *selected_unit_id = pick->id();
    if (selected_city_id != nullptr) {
      selected_city_id->reset();
    }
    gui->move_command_mode = false;
    gui->move_cursor.reset();
    gui->move_cursor_locked = false;
    center_on_hex(v, pick->coord(), win_w, win_h);
  }
}

void cycle_prev_unit(GameSession& session, std::optional<int>* selected_unit_id, View* v, int win_w,
                     int win_h, GuiState* gui, std::optional<int>* selected_city_id) {
  int const seat = session.current_player().seat;
  std::vector<Unit const*> mine;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat) {
      mine.push_back(&u);
    }
  }
  if (mine.empty()) {
    return;
  }
  std::sort(mine.begin(), mine.end(),
             [](Unit const* a, Unit const* b) { return a->id() < b->id(); });
  int const n = static_cast<int>(mine.size());
  int start = n - 1;
  if (selected_unit_id->has_value()) {
    for (int i = 0; i < n; ++i) {
      if (mine[static_cast<std::size_t>(i)]->id() == **selected_unit_id) {
        start = (i - 1 + n) % n;
        break;
      }
    }
  }

  auto try_pick = [&](auto&& ok) -> Unit const* {
    for (int k = 0; k < n; ++k) {
      Unit const* u = mine[static_cast<std::size_t>((start - k + n) % n)];
      if (u->sleeping()) {
        continue;
      }
      if (gui->skipped_units.count(u->id())) {
        continue;
      }
      if (ok(*u)) {
        return u;
      }
    }
    return nullptr;
  };

  Unit const* pick = try_pick([&](Unit const& u) { return session.auto_explore_blocked_with_moves(u); });
  if (pick == nullptr) {
    pick = try_pick([&](Unit const& u) {
      if (u.auto_explore()) {
        return false;
      }
      bool const has_moves = u.moves_remaining() > 0;
      bool const has_route = !session.planned_route_for(u.id()).empty();
      return has_moves && !has_route;
    });
  }
  if (pick == nullptr) {
    pick = try_pick([&](Unit const& u) {
      if (u.auto_explore()) {
        return false;
      }
      return u.moves_remaining() > 0;
    });
  }
  if (pick != nullptr) {
    *selected_unit_id = pick->id();
    if (selected_city_id != nullptr) {
      selected_city_id->reset();
    }
    gui->move_command_mode = false;
    gui->move_cursor.reset();
    gui->move_cursor_locked = false;
    center_on_hex(v, pick->coord(), win_w, win_h);
  }
}

void on_skip_action(GameSession& session, GuiState* gui, std::optional<int>* selected_unit_id,
                    std::optional<int>* selected_city_id, View* v, int win_w, int win_h,
                    SdlSessionFlow const* flow) {
  if (session.is_over() || session.current_player().computer) {
    return;
  }
  if (selected_unit_id->has_value()) {
    auto u = session.unit_by_id(**selected_unit_id);
    if (u.has_value() && u->owner_seat() == session.current_player().seat) {
      if (gui->skipped_units.count(u->id())) {
        gui->skipped_units.erase(u->id());
        present_action_feedback(gui, "Unit unskipped for this turn.", 2200);
      } else if (u->moves_remaining() > 0) {
        gui->skipped_units.insert(u->id());
        present_action_feedback(gui, "Unit skipped for this turn.", 2200);
        if (gui->auto_focus_next_unit) {
          cycle_next_unit(session, selected_unit_id, v, win_w, win_h, gui, selected_city_id);
        }
      } else {
        maybe_auto_advance_action_queue_sdl(session, gui, v, selected_unit_id, selected_city_id,
                                            win_w, win_h);
      }
      return;
    }
  }
  std::optional<int> before = *selected_unit_id;
  cycle_next_unit(session, selected_unit_id, v, win_w, win_h, gui, selected_city_id);
  std::optional<int> after = *selected_unit_id;
  if (before == after) {
    selected_city_id->reset();
    do_end_turn_sdl(session, gui, v, selected_unit_id, selected_city_id, win_w, win_h, flow);
  }
}

void try_found_city_sdl(GameSession& session, std::optional<int>* selected_unit_id,
                        std::optional<int>* selected_city_id, View* v, int win_w, int win_h,
                        GuiState* gui) {
  if (!selected_unit_id->has_value()) {
    return;
  }
  auto u = session.unit_by_id(**selected_unit_id);
  if (!u.has_value()) {
    return;
  }
  if (session.can_found_city(*u)) {
    auto city = session.found_city(**selected_unit_id);
    if (city.has_value()) {
      *selected_city_id = city->id();
      selected_unit_id->reset();
      center_on_hex(v, city->coord(), win_w, win_h);
      append_event_log(gui, "Founded " + city->name() + ".");
      set_toast(gui, "Founded " + city->name() + ".", 4200);
    }
  } else if (u->kind() == UnitKind::SETTLER && u->owner_seat() == session.current_player().seat) {
    auto why = session.explain_cannot_found_city(*u);
    if (why.has_value()) {
      set_toast(gui, *why, 5200);
    }
  }
}

[[nodiscard]] bool sdl_unit_needs_manual_orders(GameSession const& session, GuiState const& gui,
                                              Unit const& u) {
  if (session.is_over() || session.current_player().computer) {
    return false;
  }
  int const seat = session.current_player().seat;
  if (u.owner_seat() != seat) {
    return false;
  }
  if (u.sleeping() || u.auto_explore()) {
    return false;
  }
  if (gui.skipped_units.count(u.id()) != 0) {
    return false;
  }
  if (u.moves_remaining() <= 0) {
    return false;
  }
  return session.planned_route_for(u.id()).empty();
}

[[nodiscard]] bool sdl_has_pending_manual_unit_orders(GameSession const& session,
                                                     GuiState const& gui) {
  return count_units_needing_manual_orders(session, gui) > 0;
}

[[nodiscard]] int count_units_needing_manual_orders(GameSession const& session,
                                                    GuiState const& gui) {
  int count = 0;
  int const seat = session.current_player().seat;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat && sdl_unit_needs_manual_orders(session, gui, u)) {
      ++count;
    }
  }
  return count;
}

[[nodiscard]] bool sdl_player_has_city_without_production(GameSession const& session) {
  int const seat = session.current_player().seat;
  for (City const& c : session.cities()) {
    if (c.owner_seat() == seat && !c.current_build().has_value()) {
      return true;
    }
  }
  return false;
}

[[nodiscard]] std::string sdl_missing_production_city_names(GameSession const& session) {
  int const seat = session.current_player().seat;
  std::string out;
  for (City const& c : session.cities()) {
    if (c.owner_seat() != seat || c.current_build().has_value()) {
      continue;
    }
    if (!out.empty()) {
      out += ", ";
    }
    out += c.name();
  }
  return out;
}

[[nodiscard]] bool city_name_less_ci(std::string const& a, std::string const& b) {
  return std::lexicographical_compare(
      a.begin(), a.end(), b.begin(), b.end(), [](char ca, char cb) {
        return std::tolower(static_cast<unsigned char>(ca)) <
               std::tolower(static_cast<unsigned char>(cb));
      });
}

void select_first_city_missing_production_sdl(GameSession const& session, View* view,
                                              std::optional<int>* selected_unit_id,
                                              std::optional<int>* selected_city_id, int win_w,
                                              int win_h) {
  int const seat = session.current_player().seat;
  City const* pick = nullptr;
  for (City const& c : session.cities()) {
    if (c.owner_seat() != seat || c.current_build().has_value()) {
      continue;
    }
    if (pick == nullptr || city_name_less_ci(c.name(), pick->name())) {
      pick = &c;
    }
  }
  if (pick != nullptr) {
    selected_unit_id->reset();
    *selected_city_id = pick->id();
    center_on_hex(view, pick->coord(), win_w, win_h);
  }
}

bool apply_city_production_choice(GameSession& session, GuiState* gui, View* view,
                                  std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                  int city_id, UnitKind kind, bool enqueue, int win_w, int win_h) {
  auto c = session.city_by_id(city_id);
  if (!c.has_value() || c->owner_seat() != session.current_player().seat) {
    return false;
  }
  if (c->current_build() == kind) {
    return false;
  }
  if (kind == UnitKind::HUNTING_PARTY && c->population() < 2) {
    set_toast(gui, "Need at least 2 population to train a Hunting Party.", 4200);
    return false;
  }

  if (enqueue) {
    if (!session.enqueue_city_production(city_id, kind)) {
      if (kind == UnitKind::HUNTING_PARTY) {
        set_toast(gui, "Need at least 2 population to train a Hunting Party.", 4200);
      }
      return false;
    }
    set_toast(gui, std::string("Queued ") + tack::strat::unit_kind_display_name(kind), 2800);
    return true;
  }

  if (!session.set_city_production(city_id, kind)) {
    if (kind == UnitKind::HUNTING_PARTY) {
      set_toast(gui, "Need at least 2 population to train a Hunting Party.", 4200);
    }
    return false;
  }

  sel_city->reset();
  if (sdl_player_has_city_without_production(session)) {
    select_first_city_missing_production_sdl(session, view, sel_unit, sel_city, win_w, win_h);
  } else {
    sel_unit->reset();
    cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
  }
  set_toast(gui, std::string("Building ") + tack::strat::unit_kind_display_name(kind), 2800);
  append_event_log(gui, "Building " + std::string(tack::strat::unit_kind_display_name(kind)) + ".");
  return true;
}

bool try_handle_production_drawer_click(GameSession& session, int city_id,
                                          ProductionDrawerLayout const& layout, bool shift,
                                          GuiState* gui, View* view, std::optional<int>* sel_unit,
                                          std::optional<int>* sel_city, int win_w, int win_h,
                                          float mx, float my) {
  if (!layout.visible) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    return false;
  }
  for (int i = 0; i < 6; ++i) {
    SDL_FRect const& btn = layout.build_buttons[static_cast<std::size_t>(i)];
    if (!screen_point_in_rect(mx, my, btn)) {
      continue;
    }
    static_cast<void>(apply_city_production_choice(
        session, gui, view, sel_unit, sel_city, city_id,
        k_production_kinds[static_cast<std::size_t>(i)], shift, win_w, win_h));
    return true;
  }
  return true;
}

bool try_handle_unit_inspector_click(GameSession& session, GuiState* gui, View* view,
                                     std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                     UnitInspectorLayout const& layout, float mx, float my,
                                     int win_w, int win_h, SdlSessionFlow const* flow) {
  if (!layout.visible || !layout.is_mine) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    return false;
  }

  auto u = session.unit_by_id(layout.unit_id);
  if (!u.has_value()) {
    return true;
  }

  auto feedback_optional = [&](std::optional<std::string> const& msg,
                               std::optional<HexCoord> combat_flash = std::nullopt) {
    if (msg.has_value()) {
      present_action_feedback(gui, *msg, 4800, combat_flash);
    }
  };
  auto finish_unit_action = [&]() {
    maybe_auto_advance_action_queue_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h);
  };

  if (screen_point_in_rect(mx, my, layout.skip_btn)) {
    on_skip_action(session, gui, sel_unit, sel_city, view, win_w, win_h, flow);
    return true;
  }
  if (screen_point_in_rect(mx, my, layout.fortify_btn) && u->moves_remaining() > 0) {
    if (session.fortify_unit(layout.unit_id)) {
      present_action_feedback(gui, "Fortified.", 2200);
    }
    finish_unit_action();
    return true;
  }
  if (screen_point_in_rect(mx, my, layout.explore_btn)) {
    bool const on = !u->auto_explore();
    session.set_unit_auto_explore(layout.unit_id, on);
    present_action_feedback(gui, on ? "Auto-explore on." : "Auto-explore off.", 2400);
    return true;
  }
  if (screen_point_in_rect(mx, my, layout.sleep_btn)) {
    bool const on = !u->sleeping();
    session.set_unit_sleeping(layout.unit_id, on);
    present_action_feedback(gui, on ? "Unit sleeping until manually woken." : "Unit awake.", 2800);
    return true;
  }
  if (layout.show_found_city && screen_point_in_rect(mx, my, layout.found_city_btn)) {
    try_found_city_sdl(session, sel_unit, sel_city, view, win_w, win_h, gui);
    return true;
  }
  if (layout.show_cultivate && screen_point_in_rect(mx, my, layout.cultivate_btn) &&
      u->moves_remaining() > 0) {
    feedback_optional(session.try_cultivate_tile(layout.unit_id));
    finish_unit_action();
    return true;
  }
  if (layout.show_clear_forest && screen_point_in_rect(mx, my, layout.clear_forest_btn) &&
      u->moves_remaining() > 0) {
    feedback_optional(session.try_clear_forest(layout.unit_id));
    finish_unit_action();
    return true;
  }
  if (layout.show_build_farm && screen_point_in_rect(mx, my, layout.build_farm_btn) &&
      u->moves_remaining() > 0) {
    feedback_optional(session.try_build_farm_improvement(layout.unit_id));
    finish_unit_action();
    return true;
  }
  if (layout.show_build_mine && screen_point_in_rect(mx, my, layout.build_mine_btn) &&
      u->moves_remaining() > 0) {
    feedback_optional(session.try_build_mine_improvement(layout.unit_id));
    finish_unit_action();
    return true;
  }
  if (layout.show_rebase && screen_point_in_rect(mx, my, layout.rebase_btn)) {
    feedback_optional(session.try_rebase_hunting_party(layout.unit_id));
    finish_unit_action();
    return true;
  }
  if (layout.show_hunt && screen_point_in_rect(mx, my, layout.hunt_btn) &&
      u->moves_remaining() > 0) {
    if (auto beast = session.wild_animal_at(u->coord()); beast.has_value()) {
      feedback_optional(session.try_hunt_wildlife(layout.unit_id, beast->id(), u->coord()),
                        u->coord());
    }
    finish_unit_action();
    return true;
  }
  return true;
}

bool try_handle_city_inspector_click(GameSession& session, GuiState* gui,
                                     CityInspectorLayout const& layout, float mx, float my) {
  if (!layout.visible || !layout.is_mine) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    return false;
  }

  for (int i = 0; i < 4; ++i) {
    if (!screen_point_in_rect(mx, my, layout.focus_buttons[static_cast<std::size_t>(i)])) {
      continue;
    }
    CityFocus const focus = k_city_focuses[static_cast<std::size_t>(i)];
    if (auto msg = session.set_city_focus(layout.city_id, focus)) {
      set_toast(gui, *msg, 3200);
      append_event_log(gui, *msg);
    }
    return true;
  }

  int const seat_gold = session.gold_for(session.current_player().seat);
  for (int i = 0; i < 3; ++i) {
    if (!screen_point_in_rect(mx, my, layout.building_buttons[static_cast<std::size_t>(i)])) {
      continue;
    }
    CityBuilding const building = k_city_buildings[static_cast<std::size_t>(i)];
    auto c = session.city_by_id(layout.city_id);
    if (!c.has_value() || c->has_building(building)) {
      return true;
    }
    if (seat_gold < tack::strat::city_building_gold_cost(building)) {
      return true;
    }
    if (auto msg = session.try_construct_city_building(layout.city_id, building)) {
      set_toast(gui, *msg, 4800);
      append_event_log(gui, *msg);
    }
    return true;
  }
  return true;
}

bool try_handle_production_needs_click(GameSession& session, GuiState* gui, View* view,
                                       std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                       ProductionNeedsLayout const& layout, float mx, float my,
                                       int win_w, int win_h) {
  if (!layout.visible) {
    return false;
  }
  if (!screen_point_in_rect(mx, my, layout.panel)) {
    return false;
  }
  for (std::size_t i = 0; i < layout.jump_buttons.size(); ++i) {
    if (!screen_point_in_rect(mx, my, layout.jump_buttons[i])) {
      continue;
    }
    sel_unit->reset();
    *sel_city = layout.city_ids[i];
    if (auto c = session.city_by_id(layout.city_ids[i]); c.has_value()) {
      center_on_hex(view, c->coord(), win_w, win_h);
      set_toast(gui, "Choose production for " + c->name(), 3600);
    }
    return true;
  }
  return true;
}

void on_primary_action_sdl(GameSession& session, GuiState* gui, View* view,
                           std::optional<int>* sel_unit, std::optional<int>* sel_city,
                           std::optional<HexCoord> hovered_hex, int win_w, int win_h,
                           SdlSessionFlow const* flow) {
  if (session.is_over() || session.current_player().computer) {
    return;
  }

  if (gui->move_command_mode) {
    if (commit_move_command(session, gui, sel_unit, sel_city, hovered_hex, view, win_w, win_h)) {
      return;
    }
    clear_move_command_mode(gui);
    advance_hud_action_queue(session, gui, view, sel_unit, sel_city, win_w, win_h);
    return;
  }

  if (sel_unit->has_value()) {
    auto u = session.unit_by_id(**sel_unit);
    if (!u.has_value() || u->owner_seat() != session.current_player().seat) {
      return;
    }
    if (u->sleeping()) {
      session.set_unit_sleeping(**sel_unit, false);
      gui->skipped_units.erase(**sel_unit);
      append_event_log(gui, "Unit awakened.");
      set_toast(gui, "Unit awakened.", 2400);
      return;
    }
    if (u->kind() == UnitKind::SETTLER) {
      try_found_city_sdl(session, sel_unit, sel_city, view, win_w, win_h, gui);
      return;
    }
    if (!session.planned_route_for(**sel_unit).empty()) {
      if (u->moves_remaining() <= 0) {
        cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
        maybe_auto_advance_action_queue_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h);
        return;
      }
      if (session.follow_planned_route(**sel_unit)) {
        set_toast(gui, "Following route.", 2200);
      } else {
        set_toast(gui, "Route blocked - move manually or clear with Backspace.", 4200);
      }
      maybe_auto_advance_action_queue_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h);
      return;
    }
    gui->move_command_mode = true;
    gui->move_cursor = u->coord();
    gui->move_cursor_locked = false;
    set_toast(gui, "Move mode: arrows nudge destination, Enter commits.", 3800);
    return;
  }

  if (sel_city->has_value()) {
    auto c = session.city_by_id(**sel_city);
    if (c.has_value() && c->owner_seat() == session.current_player().seat) {
      set_toast(gui, "Pick production in the Build panel (keys 1-6).", 3600);
      return;
    }
  }

  cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
}

bool try_handle_bottom_action_bar_click(GameSession& session, GuiState* gui, View* view,
                                        std::optional<int>* sel_unit, std::optional<int>* sel_city,
                                        BottomActionBarLayout const& layout, float mx, float my,
                                        std::optional<HexCoord> hovered_hex, int win_w, int win_h,
                                        SdlSessionFlow const* flow) {
  if (!layout.visible) {
    return false;
  }
  if (screen_point_in_rect(mx, my, layout.action_hit)) {
    on_hud_action_click(session, gui, view, sel_unit, sel_city, layout.action, hovered_hex, win_w,
                        win_h, flow);
    return true;
  }
  return false;
}

void do_end_turn_sdl(GameSession& session, GuiState* gui, View* view,
                     std::optional<int>* selected_unit_id, std::optional<int>* selected_city_id,
                     int win_w, int win_h, SdlSessionFlow const* flow) {
  if (session.is_over() || session.current_player().computer) {
    return;
  }
  if (sdl_has_pending_manual_unit_orders(session, *gui)) {
    cycle_next_unit(session, selected_unit_id, view, win_w, win_h, gui, selected_city_id);
    set_toast(gui, "Units still need orders. Skipping to next unit.", 4200);
    return;
  }
  if (sdl_player_has_city_without_production(session)) {
    select_first_city_missing_production_sdl(session, view, selected_unit_id, selected_city_id,
                                             win_w, win_h);
    std::string const names = sdl_missing_production_city_names(session);
    set_toast(gui, "Choose production (keys 1-6): " + names, 6200);
    return;
  }
  session.run_auto_explore_for_current_player();
  if (!session.current_player().computer) {
    stash_camera_for_seat(gui, *view, session.current_player().seat);
  }
  if (!session.end_turn()) {
    set_toast(gui,
              "Cannot end turn: a unit still has moves on its queued path. "
              "Finish the route, move manually, or clear it (Backspace).",
              5600);
    return;
  }
  gui->move_command_mode = false;
  gui->move_cursor.reset();
  gui->move_cursor_locked = false;
  selected_unit_id->reset();
  selected_city_id->reset();
  if (flow != nullptr && flow->handoff_enabled && flow->phase != nullptr &&
      flow->handoff_autosave_ok != nullptr) {
    after_session_end_turn(session, flow->phase, flow->handoff_autosave_ok);
  } else {
    drain_cpu_turns(session);
  }
}

void handle_direction_key(GameSession& session, GuiState* gui, std::optional<int>* selected_unit_id,
                          View* v, int dq, int dr, float pan_dx, float pan_dy, int win_w, int win_h) {
  if (gui->move_command_mode && selected_unit_id->has_value() &&
      nudge_move_cursor(session, gui, *selected_unit_id, v, dq, dr, win_w, win_h)) {
    return;
  }
  pan_java_pixels(v, pan_dx, pan_dy, win_w, win_h);
}

[[nodiscard]] std::optional<std::vector<HexCoord>> projected_path_to(GameSession const& session,
                                                                     Unit const& unit,
                                                                     HexCoord dest) {
  if (unit.coord() == dest) {
    return std::vector<HexCoord>{dest};
  }
  if (!session.map().contains(dest) || !tack::strat::passable(session.terrain_effective_at(dest))) {
    return std::nullopt;
  }
  if (session.unit_at(dest).has_value()) {
    return std::nullopt;
  }
  if (session.wild_animal_at(dest).has_value() && unit.kind() != UnitKind::HUNTING_PARTY) {
    return std::nullopt;
  }

  HexCoord const start = unit.coord();
  struct Node {
    int dist{};
    HexCoord h{};
    bool operator>(Node const& o) const noexcept {
      return dist > o.dist;
    }
  };
  std::priority_queue<Node, std::vector<Node>, std::greater<Node>> pq;
  std::unordered_map<HexCoord, int> dist;
  std::unordered_map<HexCoord, HexCoord> prev;

  dist[start] = 0;
  pq.push(Node{0, start});

  while (!pq.empty()) {
    Node cur = pq.top();
    pq.pop();
    int const d = cur.dist;
    HexCoord const c = cur.h;
    auto it = dist.find(c);
    if (it == dist.end() || d != it->second) {
      continue;
    }
    if (c == dest) {
      break;
    }
    for (HexCoord n : c.neighbors()) {
      if (!session.map().contains(n)) {
        continue;
      }
      Terrain const nt = session.terrain_effective_at(n);
      if (!tack::strat::passable(nt)) {
        continue;
      }
      if (n != dest && session.unit_at(n).has_value()) {
        continue;
      }
      if (n != dest && session.wild_animal_at(n).has_value() && unit.kind() != UnitKind::HUNTING_PARTY) {
        continue;
      }
      int const nd = d + session.movement_cost_for_step(unit, n);
      auto dn = dist.find(n);
      if (dn == dist.end() || nd < dn->second) {
        dist[n] = nd;
        prev[n] = c;
        pq.push(Node{nd, n});
      }
    }
  }

  if (!dist.count(dest)) {
    return std::nullopt;
  }
  std::vector<HexCoord> rev;
  HexCoord cur = dest;
  rev.push_back(cur);
  while (cur != start) {
    auto pi = prev.find(cur);
    if (pi == prev.end()) {
      return std::nullopt;
    }
    cur = pi->second;
    rev.push_back(cur);
  }
  std::vector<HexCoord> path(rev.size());
  for (std::size_t i = 0; i < rev.size(); ++i) {
    path[i] = rev[rev.size() - 1 - i];
  }
  return path;
}

[[nodiscard]] int projected_path_turns(Unit const& unit, std::vector<HexCoord> const& path,
                                       GameSession const& session) {
  if (path.size() < 2) {
    return 0;
  }
  int cost = 0;
  for (std::size_t i = 1; i < path.size(); ++i) {
    cost += session.movement_cost_for_step(unit, path[i]);
  }
  int const per_turn = tack::strat::unit_movement(unit.kind());
  int const first_turn_moves = std::max(1, unit.moves_remaining());
  if (cost <= first_turn_moves) {
    return 1;
  }
  return 1 + (cost - first_turn_moves + per_turn - 1) / per_turn;
}

[[nodiscard]] bool try_queue_projected_path(GameSession& session, Unit const& u, HexCoord dest,
                                            std::string* feedback = nullptr) {
  auto path = projected_path_to(session, u, dest);
  if (!path.has_value() || path->size() < 2) {
    return false;
  }
  if (!session.assign_planned_route(u.id(), *path)) {
    return false;
  }
  if (feedback != nullptr) {
    int const turns = projected_path_turns(u, *path, session);
    *feedback = "Route queued (~" + std::to_string(turns) + " turn" + (turns == 1 ? "" : "s") +
                  "). Press P or click to follow.";
  }
  return true;
}

[[nodiscard]] bool try_follow_projected_path(GameSession& session, Unit const& u, HexCoord dest) {
  auto path = projected_path_to(session, u, dest);
  if (!path.has_value() || path->size() < 2) {
    return false;
  }
  return session.assign_planned_route(u.id(), *path) && session.follow_planned_route(u.id());
}

[[nodiscard]] bool civilian_kind(UnitKind k) {
  return k == UnitKind::SETTLER || k == UnitKind::SCOUT || k == UnitKind::FARMER ||
         k == UnitKind::BUILDER || k == UnitKind::HUNTING_PARTY;
}

[[nodiscard]] bool try_selected_unit_interact(GameSession& session, GuiState* gui,
                                              std::optional<int>& selected_unit_id,
                                              std::optional<int>& selected_city_id, HexCoord h,
                                              std::unordered_set<HexCoord> const& visible,
                                              View* v, int win_w, int win_h, bool queue_only) {
  if (!selected_unit_id.has_value()) {
    return false;
  }
  auto me_opt = session.unit_by_id(*selected_unit_id);
  if (!me_opt.has_value()) {
    return false;
  }
  Unit const me = *me_opt;
  int const cur_seat = session.current_player().seat;
  if (me.owner_seat() != cur_seat) {
    return false;
  }

  auto unit_here = session.unit_at(h);
  if (visible.count(h) && me.coord().distance_to(h) == 1 && unit_here.has_value() &&
      unit_here->owner_seat() != cur_seat) {
    if (auto msg = session.try_attack(me.id(), h)) {
      if (gui != nullptr) {
        present_action_feedback(gui, *msg, 4800, h);
      }
      if (!session.unit_by_id(*selected_unit_id).has_value()) {
        selected_unit_id.reset();
      } else if (gui != nullptr && v != nullptr) {
        maybe_auto_advance_action_queue_sdl(session, gui, v, &selected_unit_id, &selected_city_id,
                                            win_w, win_h);
      }
    }
    return true;
  }

  if (me.coord().distance_to(h) > 1) {
    if (queue_only) {
      std::string msg;
      if (try_queue_projected_path(session, me, h, &msg)) {
        if (gui != nullptr) {
          present_action_feedback(gui, msg, 3600);
        }
      } else if (gui != nullptr) {
        set_toast(gui, "Cannot queue a route to that tile.", 3200);
      }
    } else if (try_follow_projected_path(session, me, h)) {
      if (gui != nullptr) {
        present_action_feedback(gui, "Move queued.", 2200);
      }
    } else if (gui != nullptr) {
      set_toast(gui, "No path to that tile.", 3200);
    }
  } else if (!queue_only) {
    static_cast<void>(session.try_move_unit(me.id(), h));
  }
  if (!queue_only && gui != nullptr && v != nullptr) {
    maybe_auto_advance_action_queue_sdl(session, gui, v, &selected_unit_id, &selected_city_id,
                                        win_w, win_h);
  }
  return true;
}

void handle_left_click(GameSession& session, View* v, GuiState* gui,
                       std::optional<int>& selected_unit_id, std::optional<int>& selected_city_id,
                       float sx, float sy, int win_w, int win_h, bool shift_queue_only) {
  if (session.is_over()) {
    return;
  }
  HexCoord const h = pick_hex_at_screen(session, *v, sx, sy);
  if (!session.map().contains(h)) {
    return;
  }

  if (gui != nullptr) {
    Uint64 const now = SDL_GetTicks();
    bool const double_click = gui->last_map_click_hex.has_value() && *gui->last_map_click_hex == h &&
                              now - gui->last_map_click_ticks < 400;
    gui->last_map_click_ticks = now;
    gui->last_map_click_hex = h;
    if (double_click) {
      center_on_hex(v, h, win_w, win_h);
      present_action_feedback(gui, "Centered on (" + std::to_string(h.q) + "," + std::to_string(h.r) + ").",
                              1800);
      return;
    }
  }

  int const cur_seat = session.current_player().seat;
  if (gui != nullptr && gui->move_command_mode && selected_unit_id.has_value()) {
    auto u = session.unit_by_id(*selected_unit_id);
    if (u.has_value() && u->owner_seat() == cur_seat) {
      if (commit_move_command(session, gui, &selected_unit_id, &selected_city_id, h, v, win_w,
                              win_h)) {
        return;
      }
    }
  }
  auto visible = session.visible_for(cur_seat);
  auto visited = session.visited_for(cur_seat);

  if (!visited.count(h)) {
    static_cast<void>(try_selected_unit_interact(session, gui, selected_unit_id, selected_city_id,
                                                 h, visible, v, win_w, win_h, shift_queue_only));
    return;
  }

  auto city_here = session.city_at(h);
  if (city_here.has_value() && city_here->owner_seat() == cur_seat) {
    if (gui != nullptr) {
      clear_move_command_mode(gui);
    }
    selected_city_id = city_here->id();
    selected_unit_id.reset();
    return;
  }

  auto unit_here = session.unit_at(h);
  if (unit_here.has_value() && unit_here->owner_seat() == cur_seat) {
    if (gui != nullptr) {
      clear_move_command_mode(gui);
    }
    selected_unit_id = unit_here->id();
    selected_city_id.reset();
    return;
  }

  if (try_selected_unit_interact(session, gui, selected_unit_id, selected_city_id, h, visible, v,
                                 win_w, win_h, shift_queue_only)) {
    return;
  }

  selected_unit_id.reset();
  selected_city_id.reset();
}

void paint_vertical_background(SDL_Renderer* ren, int w, int h) {
  // Solid fill avoids horizontal banding from stepped gradient rects.
  menu_ui::fill_rect(ren, {0.f, 0.f, static_cast<float>(w), static_cast<float>(h)},
                     rgb_u8(0x0e, 0x1c, 0x2c), 1.f);
}

[[nodiscard]] std::string tile_hover_summary(GameSession const& session, HexCoord h, int seat,
                                             std::optional<int> selected_unit_id = std::nullopt) {
  if (!session.map().contains(h)) {
    return {};
  }
  auto const visited = session.visited_for(seat);
  auto const visible = session.visible_for(seat);
  if (!visited.count(h)) {
    return std::string("Unexplored  (") + std::to_string(h.q) + "," + std::to_string(h.r) + ")";
  }
  bool const in_sight = visible.count(h);
  Terrain const t = session.terrain_effective_at(h);
  std::string s = "(terrain)";
  switch (t) {
    case Terrain::WATER:
      s = "Water";
      break;
    case Terrain::GRASS:
      s = "Grass";
      break;
    case Terrain::PLAINS:
      s = "Plains";
      break;
    case Terrain::DESERT:
      s = "Desert";
      break;
    case Terrain::HILL:
      s = "Hills";
      break;
    case Terrain::FOREST:
      s = "Forest";
      break;
    case Terrain::MOUNTAIN:
      s = "Mountain";
      break;
  }
  s += "  (" + std::to_string(h.q) + "," + std::to_string(h.r) + ")";
  if (!in_sight) {
    s += "  (fog)";
  }
  if (auto city = session.city_at(h)) {
    s += "  City " + city->name();
  }
  if (auto u = session.unit_at(h)) {
    if (u->owner_seat() == seat || in_sight) {
      s += "  " + std::string(tack::strat::unit_kind_display_name(u->kind()));
    }
  }
  if (in_sight) {
    if (auto wa = session.wild_animal_at(h); wa.has_value() && !wa->is_dead()) {
      s += "  Wildlife: ";
      s += tack::strat::wild_animal_kind_label(wa->kind());
      s += " (" + std::to_string(wa->hp()) + " HP)";
    }
    TileImprovement const imp = session.improvement_at(h);
    if (imp == TileImprovement::FARM) {
      s += "  Farm";
    } else if (imp == TileImprovement::MINE) {
      s += "  Mine";
    }
    s += "  Wx ";
    s += tack::strat::weather_label(session.weather_at(h));
    if (auto terr = session.claimed_owner_at(h)) {
      s += "  Terr ";
      s += std::to_string(*terr);
    }
    std::string claim_dbg = session.claim_debug_at(h);
    if (claim_dbg.size() > 7) {
      s += "  ";
      s += menu_ui::truncate_line(std::move(claim_dbg), 42);
    }
    s += "  ";
    s += std::to_string(session.tile_food_yield(h));
    s += "f/";
    s += std::to_string(session.tile_production_yield(h));
    s += "p/";
    s += std::to_string(session.tile_gold_yield(h));
    s += "g";
  }
  if (selected_unit_id.has_value() && !session.is_over() &&
      session.current_player().seat == seat) {
    if (auto su = session.unit_by_id(*selected_unit_id);
        su.has_value() && su->owner_seat() == seat && su->coord() != h) {
      if (auto path = projected_path_to(session, *su, h); path.has_value() && path->size() >= 2) {
        int const turns = projected_path_turns(*su, *path, session);
        s += "  Route ~";
        s += std::to_string(turns);
        s += " turn";
        if (turns != 1) {
          s += "s";
        }
      } else {
        for (HexCoord const lc : session.legal_moves(*su)) {
          if (lc == h) {
            s += "  Route ~1 turn";
            break;
          }
        }
      }
    }
  }
  return s;
}

void update_map_hover_from_screen(GameSession const& session, View const& v, float mx, float my,
                                  int seat, std::optional<int> selected_unit_id, GuiState const* gui,
                                  std::optional<HexCoord>* hovered_hex, std::string* hover_line) {
  if (hovered_hex == nullptr || hover_line == nullptr) {
    return;
  }
  HexCoord const hh = pick_hex_at_screen(session, v, mx, my);
  if (!session.map().contains(hh)) {
    hovered_hex->reset();
    hover_line->clear();
    return;
  }
  *hovered_hex = hh;
  *hover_line = tile_hover_summary(session, hh, seat, selected_unit_id);
  if (!selected_unit_id.has_value() || session.is_over() || session.current_player().computer) {
    return;
  }
  auto const visited = session.visited_for(seat);
  if (auto u = session.unit_by_id(*selected_unit_id);
      u.has_value() && u->kind() == UnitKind::SETTLER && u->owner_seat() == seat && !u->is_dead() &&
      visited.count(hh)) {
    if (std::string preview = sdl_settle_preview_short(session, hh, seat); !preview.empty()) {
      *hover_line += "\n";
      *hover_line += menu_ui::truncate_line(std::move(preview), 92);
    }
  }
  if (gui != nullptr && gui->show_settler_lens) {
    if (SettlerLensHint const* hint = settler_lens_hint_at(*gui, hh)) {
      *hover_line += "\n";
      *hover_line += settler_lens_hover_line(*hint);
    }
  }
}

bool handle_java_key_down(SDL_Event const& e, GameSession& session, GuiState* gui, View* view,
                          std::optional<int>* sel_unit, std::optional<int>* sel_city,
                          std::optional<HexCoord> hovered_hex, int win_w, int win_h, bool* running,
                          SDL_Window* window, SdlSessionFlow const* flow) {
  if (e.type != SDL_EVENT_KEY_DOWN || e.key.repeat) {
    return false;
  }
  SDL_Keycode const key = e.key.key;
  bool const enter_pressed = key == SDLK_RETURN || key == SDLK_KP_ENTER ||
                             e.key.scancode == SDL_SCANCODE_RETURN ||
                             e.key.scancode == SDL_SCANCODE_RETURN2 ||
                             e.key.scancode == SDL_SCANCODE_KP_ENTER;
  SDL_Keymod const mods = SDL_GetModState();
  bool const shift = (mods & SDL_KMOD_SHIFT) != 0;
  bool const ctrl = (mods & SDL_KMOD_CTRL) != 0;
  bool const gui_key = (mods & SDL_KMOD_GUI) != 0;

  if ((ctrl || gui_key) && key == SDLK_Q) {
    *running = false;
    return true;
  }
  if ((ctrl || gui_key) && key == SDLK_S) {
    gui->request_save = true;
    return true;
  }
  if ((ctrl || gui_key) && key == SDLK_O) {
    gui->request_load = true;
    return true;
  }

  if (key == SDLK_F11 && window != nullptr && !e.key.repeat) {
    Uint32 const wf = SDL_GetWindowFlags(window);
    bool const fs = (wf & SDL_WINDOW_FULLSCREEN) != 0;
    static_cast<void>(SDL_SetWindowFullscreen(window, !fs));
    return true;
  }

  if (key == SDLK_F1 || (shift && key == SDLK_SLASH)) {
    gui->show_hotkeys = !gui->show_hotkeys;
    if (gui->show_hotkeys) {
      gui->show_game_menu = false;
    }
    return true;
  }

  if (gui->show_intro_tips) {
    if (enter_pressed || key == SDLK_ESCAPE || key == SDLK_SPACE) {
      dismiss_intro_tips(gui);
      return true;
    }
    return true;
  }

  if (session.is_over()) {
    return false;
  }

  if (key == SDLK_ESCAPE) {
    if (gui->show_game_menu) {
      gui->show_game_menu = false;
      return true;
    }
    if (gui->show_hotkeys) {
      gui->show_hotkeys = false;
      return true;
    }
    if (gui->move_command_mode) {
      clear_move_command_mode(gui);
      return true;
    }
    if (sel_unit->has_value() || sel_city->has_value()) {
      clear_move_command_mode(gui);
      sel_unit->reset();
      sel_city->reset();
      return true;
    }
    gui->show_game_menu = true;
    return true;
  }

  if (session.current_player().computer) {
    return false;
  }

  if (gui->show_game_menu) {
    return true;
  }

  if (sel_city->has_value() && production_drawer_visible(session, *sel_city)) {
    int idx = -1;
    if (key >= SDLK_1 && key <= SDLK_6) {
      idx = static_cast<int>(key - SDLK_1);
    } else if (key >= SDLK_KP_1 && key <= SDLK_KP_6) {
      idx = static_cast<int>(key - SDLK_KP_1);
    }
    if (idx >= 0 && idx < 6) {
      apply_city_production_choice(session, gui, view, sel_unit, sel_city, **sel_city,
                                   k_production_kinds[static_cast<std::size_t>(idx)], shift, win_w,
                                   win_h);
      return true;
    }
  }

  if (enter_pressed) {
    PrimaryActionState const act =
        compute_primary_action_state(session, *gui, *sel_unit, *sel_city);
    on_hud_action_click(session, gui, view, sel_unit, sel_city, act, hovered_hex, win_w, win_h,
                        flow);
    return true;
  }

  float const pan = JAVA_PAN_PX;
  switch (key) {
    case SDLK_UP:
      handle_direction_key(session, gui, sel_unit, view, 0, -1, 0.f, pan, win_w, win_h);
      return true;
    case SDLK_DOWN:
      handle_direction_key(session, gui, sel_unit, view, 0, 1, 0.f, -pan, win_w, win_h);
      return true;
    case SDLK_LEFT:
      handle_direction_key(session, gui, sel_unit, view, -1, 0, pan, 0.f, win_w, win_h);
      return true;
    case SDLK_RIGHT:
      handle_direction_key(session, gui, sel_unit, view, 1, 0, -pan, 0.f, win_w, win_h);
      return true;
    case SDLK_EQUALS:
    case SDLK_PLUS:
      zoom_by_keyboard(view, JAVA_KEYBOARD_ZOOM, win_w, win_h);
      return true;
    case SDLK_MINUS:
      zoom_by_keyboard(view, 1.f / JAVA_KEYBOARD_ZOOM, win_w, win_h);
      return true;
    case SDLK_SPACE:
      on_skip_action(session, gui, sel_unit, sel_city, view, win_w, win_h, flow);
      return true;
    case SDLK_B:
      try_found_city_sdl(session, sel_unit, sel_city, view, win_w, win_h, gui);
      return true;
    case SDLK_F:
      if (shift) {
        fit_map_to_window(view, win_w, win_h);
      } else if (sel_unit->has_value()) {
        if (session.fortify_unit(**sel_unit)) {
          present_action_feedback(gui, "Fortified.", 2200);
          maybe_auto_advance_action_queue_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h);
        }
      }
      return true;
    case SDLK_M:
      if (!sel_unit->has_value()) {
        return true;
      }
      {
        auto u = session.unit_by_id(**sel_unit);
        if (!u.has_value() || u->owner_seat() != session.current_player().seat) {
          return true;
        }
        gui->move_command_mode = !gui->move_command_mode;
        if (gui->move_command_mode) {
          gui->move_cursor = u->coord();
          gui->move_cursor_locked = false;
          set_toast(gui, "Move mode: arrows nudge destination, Enter commits.", 3800);
        } else {
          gui->move_cursor.reset();
          gui->move_cursor_locked = false;
        }
      }
      return true;
    case SDLK_TAB:
      if (shift) {
        cycle_prev_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
      } else {
        cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
      }
      return true;
    case SDLK_N:
    case SDLK_PERIOD:
      cycle_next_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
      return true;
    case SDLK_COMMA:
      cycle_prev_unit(session, sel_unit, view, win_w, win_h, gui, sel_city);
      return true;
    case SDLK_HOME:
      if (!session.is_over() && !session.current_player().computer) {
        if (sel_city->has_value()) {
          auto c = session.city_by_id(**sel_city);
          if (c.has_value()) {
            center_on_hex(view, c->coord(), win_w, win_h);
          }
        } else if (sel_unit->has_value()) {
          auto u = session.unit_by_id(**sel_unit);
          if (u.has_value()) {
            center_on_hex(view, u->coord(), win_w, win_h);
          }
        } else {
          for (City const& ct : session.cities()) {
            if (ct.owner_seat() == session.current_player().seat) {
              center_on_hex(view, ct.coord(), win_w, win_h);
              break;
            }
          }
        }
      }
      return true;
    case SDLK_BACKSPACE:
      if (!session.is_over() && !session.current_player().computer && sel_unit->has_value()) {
        auto u = session.unit_by_id(**sel_unit);
        if (u.has_value() && u->owner_seat() == session.current_player().seat) {
          if (session.clear_planned_route(**sel_unit)) {
            set_toast(gui, "Queued route cleared.", 2000);
          }
        }
      }
      return true;
    case SDLK_E:
      if (shift && sel_unit->has_value()) {
        auto u = session.unit_by_id(**sel_unit);
        if (u.has_value() && u->owner_seat() == session.current_player().seat) {
          bool const on = !u->auto_explore();
          session.set_unit_auto_explore(**sel_unit, on);
          append_event_log(gui, on ? "Auto-explore on." : "Auto-explore off.");
          set_toast(gui, on ? "Auto-explore on." : "Auto-explore off.", 2400);
        }
      }
      return true;
    case SDLK_Z:
      if (sel_unit->has_value()) {
        auto u = session.unit_by_id(**sel_unit);
        if (u.has_value() && u->owner_seat() == session.current_player().seat) {
          bool const on = !u->sleeping();
          session.set_unit_sleeping(**sel_unit, on);
          append_event_log(gui, on ? "Unit sleeping until manually woken." : "Unit awake.");
          set_toast(gui, on ? "Unit sleeping until manually woken." : "Unit awake.", 2800);
        }
      }
      return true;
    case SDLK_P: {
      PrimaryActionState const act =
          compute_primary_action_state(session, *gui, *sel_unit, *sel_city);
      on_hud_action_click(session, gui, view, sel_unit, sel_city, act, hovered_hex, win_w, win_h,
                          flow);
      return true;
    }
    case SDLK_U:
      toggle_auto_focus_next_unit(gui);
      return true;
    case SDLK_H:
      toggle_settler_lens(gui);
      return true;
    case SDLK_W:
      toggle_weather_legend(gui);
      return true;
    case SDLK_L:
      toggle_claim_legend(gui);
      return true;
    default:
      break;
  }
  return false;
}

void draw_tile_hover_text(SDL_Renderer* ren, float x, float y, std::string const& full,
                          float wrap_w) {
  std::vector<std::string> const lines = tile_hover_lines(full, wrap_w);
  if (lines.empty()) {
    return;
  }
  float panel_content_w = 0.f;
  for (std::string const& line : lines) {
    panel_content_w = std::max(panel_content_w, menu_ui::text_width(line.c_str()));
  }
  panel_content_w = std::min(wrap_w, panel_content_w);
  float const panel_w = panel_content_w + 16.f;
  float const panel_h = 8.f + 14.f * static_cast<float>(lines.size());
  SDL_FRect panel{x - 4.f, y - 4.f, panel_w, panel_h};
  menu_ui::fill_rect(ren, panel, rgb_u8(0x06, 0x0a, 0x12), 0.92f);
  menu_ui::stroke_rect(ren, panel, menu_ui::k_modal_line, 0.75f);
  float ty = y;
  for (std::string const& line : lines) {
    menu_ui::draw_text(ren, x, ty, line.c_str(), menu_ui::k_hint);
    ty += 14.f;
  }
}

[[nodiscard]] bool screen_point_in_rect(float px, float py, SDL_FRect const& r) noexcept;

[[nodiscard]] SDL_FRect minimap_screen_rect(int win_w, float right_ui_margin = 0.f) noexcept {
  return {static_cast<float>(win_w) - k_minimap_w - k_minimap_pad - right_ui_margin,
          k_top_bar_h + k_minimap_pad, k_minimap_w, k_minimap_h};
}

struct MinimapPlot {
  float x = 0.f;
  float y = 0.f;
  float w = 0.f;
  float h = 0.f;
  float world_min_x = 0.f;
  float world_min_y = 0.f;
  float px_per_world = 1.f;
};

[[nodiscard]] MinimapPlot make_minimap_plot(SDL_FRect const& mm, float bmin_x, float bmin_y,
                                            float bmax_x, float bmax_y) noexcept {
  MinimapPlot plot;
  float const world_w = std::max(1.f, bmax_x - bmin_x);
  float const world_h = std::max(1.f, bmax_y - bmin_y);
  float const inner_pad = 8.f;
  float const inner_w = std::max(1.f, mm.w - inner_pad * 2.f);
  float const inner_h = std::max(1.f, mm.h - inner_pad * 2.f);
  float const world_aspect = world_w / world_h;
  float const inner_aspect = inner_w / inner_h;
  if (world_aspect >= inner_aspect) {
    plot.w = inner_w;
    plot.h = inner_w / world_aspect;
  } else {
    plot.h = inner_h;
    plot.w = inner_h * world_aspect;
  }
  plot.x = mm.x + inner_pad + (inner_w - plot.w) * 0.5f;
  plot.y = mm.y + inner_pad + (inner_h - plot.h) * 0.5f;
  plot.world_min_x = bmin_x;
  plot.world_min_y = bmin_y;
  plot.px_per_world = plot.w / world_w;
  return plot;
}

void minimap_plot_to_screen(MinimapPlot const& plot, float wx, float wy, float* sx,
                            float* sy) noexcept {
  *sx = plot.x + (wx - plot.world_min_x) * plot.px_per_world;
  *sy = plot.y + (wy - plot.world_min_y) * plot.px_per_world;
}

[[nodiscard]] bool minimap_screen_to_world(MinimapPlot const& plot, float sx, float sy, float* wx,
                                           float* wy) noexcept {
  if (!screen_point_in_rect(sx, sy, {plot.x, plot.y, plot.w, plot.h})) {
    return false;
  }
  *wx = plot.world_min_x + (sx - plot.x) / plot.px_per_world;
  *wy = plot.world_min_y + (sy - plot.y) / plot.px_per_world;
  return true;
}

void expand_bounds_for_viewport(float* bmin_x, float* bmin_y, float* bmax_x, float* bmax_y,
                                float vx0, float vy0, float vx1, float vy1) noexcept {
  *bmin_x = std::min(*bmin_x, std::min(vx0, vx1));
  *bmin_y = std::min(*bmin_y, std::min(vy0, vy1));
  *bmax_x = std::max(*bmax_x, std::max(vx0, vx1));
  *bmax_y = std::max(*bmax_y, std::max(vy0, vy1));
}

void pan_view_to_world_point(View* v, float wx, float wy, int win_w, int win_h) {
  v->offset_x = map_center_x(win_w) - wx * v->scale;
  v->offset_y = map_center_y(win_h) - wy * v->scale;
  clamp_view(v, win_w, win_h);
}

[[nodiscard]] bool handle_minimap_click(GameSession const& session, View* v, float mx, float my,
                                        int win_w, int win_h, float right_ui_margin = 0.f) {
  (void)session;
  SDL_FRect const mm = minimap_screen_rect(win_w, right_ui_margin);
  if (!screen_point_in_rect(mx, my, mm)) {
    return false;
  }
  float bmin_x = view_bounds_min_x(*v);
  float bmin_y = view_bounds_min_y(*v);
  float bmax_x = view_bounds_max_x(*v);
  float bmax_y = view_bounds_max_y(*v);
  float const map_top = k_top_bar_h;
  float const map_bot = static_cast<float>(win_h) - k_footer_bar_h;
  float const vx0 = (0.f - v->offset_x) / v->scale;
  float const vy0 = (map_top - v->offset_y) / v->scale;
  float const vx1 = (static_cast<float>(win_w) - v->offset_x) / v->scale;
  float const vy1 = (map_bot - v->offset_y) / v->scale;
  expand_bounds_for_viewport(&bmin_x, &bmin_y, &bmax_x, &bmax_y, vx0, vy0, vx1, vy1);
  MinimapPlot const plot = make_minimap_plot(mm, bmin_x, bmin_y, bmax_x, bmax_y);
  float wx = 0.f;
  float wy = 0.f;
  if (!minimap_screen_to_world(plot, mx, my, &wx, &wy)) {
    return false;
  }
  pan_view_to_world_point(v, wx, wy, win_w, win_h);
  return true;
}

void draw_minimap(SDL_Renderer* ren, GameSession const& session, View const& v, int cur_seat,
                  int win_w, int win_h, float right_ui_margin = 0.f,
                  std::optional<int> selected_unit_id = std::nullopt,
                  bool minimap_tint_own_claims_only = false) {
  SDL_FRect const mm = minimap_screen_rect(win_w, right_ui_margin);
  float bmin_x = view_bounds_min_x(v);
  float bmin_y = view_bounds_min_y(v);
  float bmax_x = view_bounds_max_x(v);
  float bmax_y = view_bounds_max_y(v);
  if (bmax_x - bmin_x <= 1.f || bmax_y - bmin_y <= 1.f) {
    return;
  }

  SDL_SetRenderDrawColorFloat(ren, 0.f, 0.f, 0.f, 0.45f);
  SDL_FRect shadow{mm.x + 2.f, mm.y + 2.f, mm.w, mm.h};
  SDL_RenderFillRect(ren, &shadow);
  SDL_SetRenderDrawColorFloat(ren, 0.04f, 0.07f, 0.12f, 0.94f);
  SDL_RenderFillRect(ren, &mm);
  SDL_SetRenderDrawColorFloat(ren, 0.35f, 0.48f, 0.62f, 0.85f);
  SDL_RenderRect(ren, &mm);
  menu_ui::draw_text(ren, mm.x + 6.f, mm.y + 3.f, "Map", menu_ui::k_muted, 0.88f);

  float const map_top = k_top_bar_h;
  float const map_bot = static_cast<float>(win_h) - k_footer_bar_h;
  float const vx0 = (0.f - v.offset_x) / v.scale;
  float const vy0 = (map_top - v.offset_y) / v.scale;
  float const vx1 = (static_cast<float>(win_w) - v.offset_x) / v.scale;
  float const vy1 = (map_bot - v.offset_y) / v.scale;
  expand_bounds_for_viewport(&bmin_x, &bmin_y, &bmax_x, &bmax_y, vx0, vy0, vx1, vy1);
  MinimapPlot const plot = make_minimap_plot(mm, bmin_x, bmin_y, bmax_x, bmax_y);
  SDL_SetRenderDrawColorFloat(ren, 0.08f, 0.12f, 0.18f, 0.55f);
  SDL_FRect plot_bg{plot.x - 1.f, plot.y - 1.f, plot.w + 2.f, plot.h + 2.f};
  SDL_RenderFillRect(ren, &plot_bg);

  auto to_mm = [&](float wx, float wy, float* ox, float* oy) {
    minimap_plot_to_screen(plot, wx, wy, ox, oy);
  };

  auto const visited = session.visited_for(cur_seat);
  auto const visible = session.visible_for(cur_seat);
  for (HexCoord const c : visited) {
    float px = 0.f;
    float py = 0.f;
    to_mm(axial_to_world_x(c), axial_to_world_y(c), &px, &py);
    RGB col = terrain_fill(session.terrain_effective_at(c));
    if (!visible.count(c)) {
      col = blend_rgb(col, rgb_u8(64, 64, 64), 0.55f);
    }
    if (auto owner = session.claimed_owner_at(c); owner.has_value()) {
      bool const tint = !minimap_tint_own_claims_only || *owner == cur_seat;
      if (tint) {
        RGB const claim = player_rgb(*owner);
        float const claim_blend = visible.count(c) ? 0.36f : 0.22f;
        col = blend_rgb(col, claim, claim_blend);
      }
    }
    SDL_SetRenderDrawColorFloat(ren, col.r, col.g, col.b, 1.f);
    SDL_FRect dot{px - 1.5f, py - 1.5f, 3.f, 3.f};
    SDL_RenderFillRect(ren, &dot);
  }

  for (City const& city : session.cities()) {
    if (!visited.count(city.coord())) {
      continue;
    }
    float px = 0.f;
    float py = 0.f;
    to_mm(axial_to_world_x(city.coord()), axial_to_world_y(city.coord()), &px, &py);
    RGB pr = player_rgb(city.owner_seat());
    SDL_SetRenderDrawColorFloat(ren, pr.r, pr.g, pr.b, 1.f);
    SDL_FRect sw{px - 3.f, py - 3.f, 6.f, 6.f};
    SDL_RenderFillRect(ren, &sw);
  }

  for (WildAnimal const& beast : session.wildlife_list()) {
    if (beast.is_dead() || !visible.count(beast.coord())) {
      continue;
    }
    float px = 0.f;
    float py = 0.f;
    to_mm(axial_to_world_x(beast.coord()), axial_to_world_y(beast.coord()), &px, &py);
    RGB tint = wild_animal_tint(beast.kind());
    SDL_SetRenderDrawColorFloat(ren, tint.r, tint.g, tint.b, 1.f);
    SDL_FRect dot{px - 2.f, py - 2.f, 4.f, 4.f};
    SDL_RenderFillRect(ren, &dot);
  }

  for (Unit const& u : session.units()) {
    if (!visited.count(u.coord())) {
      continue;
    }
    bool const own = u.owner_seat() == cur_seat;
    if (!own && !visible.count(u.coord())) {
      continue;
    }
    float px = 0.f;
    float py = 0.f;
    to_mm(axial_to_world_x(u.coord()), axial_to_world_y(u.coord()), &px, &py);
    RGB pr = player_rgb(u.owner_seat());
    if (!visible.count(u.coord())) {
      pr = blend_rgb(pr, rgb_u8(90, 90, 90), 0.45f);
    }
    bool const selected = selected_unit_id.has_value() && *selected_unit_id == u.id();
    if (selected) {
      SDL_SetRenderDrawColorFloat(ren, 1.f, 1.f, 1.f, 0.95f);
      SDL_FRect ring{px - 4.f, py - 4.f, 8.f, 8.f};
      SDL_RenderRect(ren, &ring);
    }
    SDL_SetRenderDrawColorFloat(ren, pr.r, pr.g, pr.b, 1.f);
    SDL_FRect dot{px - 2.f, py - 2.f, 4.f, 4.f};
    SDL_RenderFillRect(ren, &dot);
  }

  float x0 = 0.f;
  float y0 = 0.f;
  float x1 = 0.f;
  float y1 = 0.f;
  to_mm(vx0, vy0, &x0, &y0);
  to_mm(vx1, vy1, &x1, &y1);
  SDL_FRect vp{std::min(x0, x1), std::min(y0, y1), std::abs(x1 - x0), std::abs(y1 - y0)};
  vp.x = std::max(plot.x, vp.x);
  vp.y = std::max(plot.y, vp.y);
  float const vp_right = std::min(plot.x + plot.w, vp.x + vp.w);
  float const vp_bottom = std::min(plot.y + plot.h, vp.y + vp.h);
  vp.w = std::max(0.f, vp_right - vp.x);
  vp.h = std::max(0.f, vp_bottom - vp.y);
  SDL_SetRenderDrawColorFloat(ren, 1.f, 0.92f, 0.35f, 0.14f);
  SDL_RenderFillRect(ren, &vp);
  SDL_SetRenderDrawColorFloat(ren, 1.f, 0.92f, 0.35f, 0.95f);
  SDL_RenderRect(ren, &vp);

  for (int i = 0; i < 3; ++i) {
    float const t = static_cast<float>(i) / 3.f;
    float const pad = 3.f + t * 5.f;
    float const a = 0.10f * (1.f - t);
    menu_ui::stroke_rect(ren, {mm.x + pad, mm.y + pad, mm.w - pad * 2.f, mm.h - pad * 2.f},
                         menu_ui::k_ink, a);
  }
}

void ensure_selection_for_current_seat(GameSession const& session, std::optional<int>* sel_unit,
                                       std::optional<int>* sel_city) {
  if (session.is_over() || session.current_player().computer) {
    return;
  }
  int const seat = session.current_player().seat;
  if (sel_unit->has_value()) {
    auto u = session.unit_by_id(**sel_unit);
    if (!u.has_value() || u->owner_seat() != seat) {
      sel_unit->reset();
    }
  }
  if (sel_city->has_value()) {
    auto c = session.city_by_id(**sel_city);
    if (!c.has_value() || c->owner_seat() != seat) {
      sel_city->reset();
    }
  }
}

void touch_camera_for_current_seat(GameSession const& session, GuiState* gui, View const& v) {
  if (gui == nullptr || session.current_player().computer) {
    return;
  }
  int const seat = session.current_player().seat;
  if (gui->camera_seat < 0) {
    gui->camera_seat = seat;
  }
  if (gui->camera_seat == seat) {
    stash_camera_for_seat(gui, v, seat);
  }
}

void render_frame(SDL_Renderer* ren, GameSession& session, View const& v, GuiState* gui,
                  std::optional<int> selected_unit_id, std::optional<int> selected_city_id,
                  std::optional<HexCoord> hovered_hex, std::string const& hover_line, int win_w,
                  int win_h, float mouse_x, float mouse_y) {
  paint_vertical_background(ren, win_w, win_h);

  int const cur_seat = session.current_player().seat;
  auto visible = session.visible_for(cur_seat);
  auto visited = session.visited_for(cur_seat);
  MapScreen const screen{win_w, win_h};
  std::optional<HexCoord> selected_unit_hex;
  if (selected_unit_id.has_value()) {
    if (auto su = session.unit_by_id(*selected_unit_id); su.has_value()) {
      selected_unit_hex = su->coord();
    }
  }

  // --- Tiles ---
  for (HexCoord const c : session.map().all_cells()) {
    Terrain const t = session.terrain_effective_at(c);
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);

    if (!visited.count(c)) {
      continue;
    }
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }

    RGB base = terrain_fill(t);
    RGB shade = terrain_shade(t);
    RGB hi = terrain_highlight(t);
    if (session.improvement_at(c) == TileImprovement::FARM) {
      base = blend_rgb(base, rgb_u8(200, 225, 140), 0.38f);
      shade = blend_rgb(shade, rgb_u8(110, 140, 75), 0.32f);
      hi = blend_rgb(hi, rgb_u8(235, 248, 190), 0.25f);
    } else if (session.improvement_at(c) == TileImprovement::MINE) {
      base = blend_rgb(base, rgb_u8(130, 132, 148), 0.28f);
      shade = blend_rgb(shade, rgb_u8(72, 74, 92), 0.22f);
      hi = blend_rgb(hi, rgb_u8(175, 178, 195), 0.18f);
    }
    if (!visible.count(c)) {
      RGB const dk = rgb_u8(64, 64, 64);
      base = blend_rgb(base, dk, 0.55f);
      shade = blend_rgb(shade, rgb_u8(0, 0, 0), 0.55f);
      hi = blend_rgb(hi, dk, 0.55f);
    }

    fill_hex_gradient(ren, v, cx, cy, hi, shade, 1.f);

    if (!visible.count(c)) {
      stroke_hex_screen(ren, v, cx, cy, 0.f, 0.f, 0.f, 90.f / 255.f);
      continue;
    }

    if (t == Terrain::WATER) {
      stroke_hex_screen(ren, v, cx, cy, 0.f, 18.f / 255.f, 28.f / 255.f, 26.f / 255.f);
    } else {
      stroke_hex_screen(ren, v, cx, cy, 0.f, 0.f, 0.f, 68.f / 255.f);
    }

    TileImprovement const imp_here = session.improvement_at(c);
    if (visible.count(c)) {
      if (imp_here == TileImprovement::FARM) {
        float sx = 0;
        float sy = 0;
        world_to_screen(v, cx, cy, &sx, &sy);
        float const d = std::max(4.f, 6.f * v.scale);
        SDL_SetRenderDrawColorFloat(ren, 0.94f, 0.82f, 0.18f, 0.92f);
        SDL_FRect grain{sx - d * 0.5f, sy - d * 0.5f, d, d};
        SDL_RenderFillRect(ren, &grain);
        SDL_SetRenderDrawColorFloat(ren, 0.55f, 0.42f, 0.08f, 0.65f);
        SDL_RenderRect(ren, &grain);
      } else if (imp_here == TileImprovement::MINE) {
        float sx = 0;
        float sy = 0;
        world_to_screen(v, cx, cy, &sx, &sy);
        float const w = std::max(8.f, 11.f * v.scale);
        float const ht = std::max(3.5f, 4.5f * v.scale);
        SDL_SetRenderDrawColorFloat(ren, 0.72f, 0.76f, 0.84f, 0.93f);
        SDL_FRect ore{sx - w * 0.5f, sy - ht * 0.5f, w, ht};
        SDL_RenderFillRect(ren, &ore);
        SDL_SetRenderDrawColorFloat(ren, 0.38f, 0.34f, 0.28f, 0.72f);
        SDL_RenderRect(ren, &ore);
      }
      bool const has_city = session.city_at(c).has_value();
      if (!has_city && gui != nullptr) {
        draw_terrain_tile_deco(ren, v, t, c, cx, cy, false);
      }
      int const cult = session.cultivation_at(c);
      if (cult > 0 && imp_here == TileImprovement::NONE) {
        float sx = 0.f;
        float sy = 0.f;
        world_to_screen(v, cx + HEX_R * 0.25f, cy + HEX_R * 0.45f, &sx, &sy);
        char cult_label[8];
        std::snprintf(cult_label, sizeof(cult_label), "+%d", cult);
        menu_ui::draw_text(ren, sx - menu_ui::text_width(cult_label) * 0.5f, sy - 4.f, cult_label,
                           menu_ui::k_ok, 0.78f);
      }
      if (!has_city &&
          tile_should_show_yield_badge(c, hovered_hex, selected_unit_hex)) {
        draw_tile_yield_badge(ren, v, session, c, cx, cy);
      }
    }
  }

  draw_terrain_edge_blends(ren, session, v, visible, visited, screen);
  draw_coast_foam(ren, session, v, visible, visited, screen);
  draw_shore_water_depth(ren, session, v, visible, visited, screen);
  draw_deep_water_overlay(ren, session, v, visible, visited, screen);
  draw_claim_borders(ren, session, v, visible, visited, screen);
  draw_weather_tile_overlays(ren, session, v, visited, visible, screen);
  draw_exploration_frontier_shroud(ren, session, v, visited, screen);
  // --- Legal move / attack overlays ---
  std::unordered_set<HexCoord> moves;
  std::unordered_set<HexCoord> attacks;
  Unit const* selected = nullptr;
  if (selected_unit_id.has_value()) {
    auto su = session.unit_by_id(*selected_unit_id);
    if (su.has_value()) {
      selected = &*su;
      auto lm = session.legal_moves(*su);
      auto la = session.legal_attacks(*su);
      moves.insert(lm.begin(), lm.end());
      attacks.insert(la.begin(), la.end());
    }
  }
  bool const civilian_mode = selected && civilian_kind(selected->kind());

  for (HexCoord const c : moves) {
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    if (civilian_mode) {
      fill_hex_overlay(ren, v, cx, cy, 110.f / 255.f, 228.f / 255.f, 255.f / 255.f, 108.f / 255.f,
                       25.f / 255.f, 110.f / 255.f, 165.f / 255.f, 72.f / 255.f);
      stroke_hex_screen(ren, v, cx, cy, 170.f / 255.f, 245.f / 255.f, 1.f, 235.f / 255.f);
      stroke_hex_screen(ren, v, cx, cy, 30.f / 255.f, 140.f / 255.f, 210.f / 255.f, 210.f / 255.f);
      float sx = 0.f;
      float sy = 0.f;
      world_to_screen(v, cx, cy, &sx, &sy);
      float const d = std::max(7.f, 9.f * v.scale);
      SDL_SetRenderDrawColorFloat(ren, 248.f / 255.f, 1.f, 1.f, 238.f / 255.f);
      SDL_FRect dot{sx - d * 0.5f, sy - d * 0.5f, d, d};
      SDL_RenderFillRect(ren, &dot);
    } else {
      fill_hex_overlay(ren, v, cx, cy, 1.f, 238.f / 255.f, 140.f / 255.f, 118.f / 255.f,
                       190.f / 255.f, 120.f / 255.f, 25.f / 255.f, 68.f / 255.f);
      stroke_hex_screen(ren, v, cx, cy, 1.f, 225.f / 255.f, 140.f / 255.f, 235.f / 255.f);
      stroke_hex_screen(ren, v, cx, cy, 190.f / 255.f, 120.f / 255.f, 25.f / 255.f, 210.f / 255.f);
    }
  }
  for (HexCoord const c : attacks) {
    float const cx = axial_to_world_x(c);
    float const cy = axial_to_world_y(c);
    if (!hex_on_map_screen(v, cx, cy, screen)) {
      continue;
    }
    fill_hex_overlay(ren, v, cx, cy, 1.f, 120.f / 255.f, 110.f / 255.f, 125.f / 255.f,
                     140.f / 255.f, 25.f / 255.f, 25.f / 255.f, 85.f / 255.f);
    stroke_hex_screen(ren, v, cx, cy, 1.f, 190.f / 255.f, 185.f / 255.f, 228.f / 255.f);
    stroke_hex_screen(ren, v, cx, cy, 190.f / 255.f, 35.f / 255.f, 35.f / 255.f, 215.f / 255.f);
  }

  std::vector<HexCoord> projected_path;
  int projected_turns = 0;
  if (selected_unit_id.has_value()) {
    auto su = session.unit_by_id(*selected_unit_id);
    if (su.has_value() && su->owner_seat() == cur_seat) {
      std::vector<HexCoord> const route = session.planned_route_for(*selected_unit_id);
      if (!route.empty()) {
        projected_path.push_back(su->coord());
        projected_path.insert(projected_path.end(), route.begin(), route.end());
        projected_turns = projected_path_turns(*su, projected_path, session);
      } else {
        std::optional<HexCoord> dest;
        if (gui != nullptr && gui->move_command_mode) {
          dest = gui->move_cursor;
          if (!dest.has_value()) {
            dest = hovered_hex;
          }
        } else if (hovered_hex.has_value() && su->moves_remaining() > 0 && !session.is_over() &&
                   !session.current_player().computer) {
          dest = hovered_hex;
        }
        if (dest.has_value() && *dest != su->coord()) {
          if (auto path = projected_path_to(session, *su, *dest);
              path.has_value() && path->size() >= 2) {
            projected_path = *path;
            projected_turns = projected_path_turns(*su, projected_path, session);
          } else if (moves.count(*dest)) {
            projected_path = {su->coord(), *dest};
            projected_turns = 1;
          }
        }
      }
      if (gui != nullptr && gui->move_command_mode && gui->move_cursor.has_value()) {
        float const cx = axial_to_world_x(*gui->move_cursor);
        float const cy = axial_to_world_y(*gui->move_cursor);
        bool const known = visited.count(*gui->move_cursor);
        stroke_hex_screen(ren, v, cx, cy, 1.f, 0.85f, 0.2f, known ? 0.9f : 0.35f);
      }
    }
  }

  if (gui != nullptr && selected_unit_id.has_value() && !session.is_over() &&
      !session.current_player().computer) {
    if (auto su = session.unit_by_id(*selected_unit_id);
        su.has_value() && su->kind() == UnitKind::SETTLER && su->owner_seat() == cur_seat &&
        gui->show_settler_lens) {
      if (gui->settler_lens_unit_id != *selected_unit_id || gui->settler_lens_seat != cur_seat ||
          gui->settler_lens_origin != su->coord()) {
        rebuild_settler_lens_cache(gui, session, cur_seat, *selected_unit_id);
      }
      if (!gui->settler_lens_hints.empty()) {
        draw_settler_lens_markers(ren, v, gui->settler_lens_hints);
      }
    } else if (gui->settler_lens_unit_id >= 0) {
      gui->settler_lens_unit_id = -1;
      gui->settler_lens_hints.clear();
    }
  }

  draw_selected_unit_tile_glow(ren, session, v, selected_unit_id, cur_seat, visible, visited);

  // --- Cities ---
  for (City const& ct : session.cities()) {
    if (!visited.count(ct.coord()) && ct.owner_seat() != cur_seat) {
      continue;
    }
    float cx = axial_to_world_x(ct.coord());
    float cy = axial_to_world_y(ct.coord());
    bool const needs_production =
        ct.owner_seat() == cur_seat && !session.is_over() && !session.current_player().computer &&
        !ct.current_build().has_value();
    if (needs_production && visible.count(ct.coord())) {
      stroke_hex_screen(ren, v, cx, cy, 1.f, 0.78f, 0.22f, 0.80f);
    }
    RGB base = player_rgb(ct.owner_seat());
    if (!visible.count(ct.coord())) {
      base = blend_rgb(base, rgb_u8(128, 128, 128), 0.5f);
    }
    fill_hex_overlay(ren, v, cx, cy, base.r, base.g, base.b, 0.35f, base.r * 0.6f, base.g * 0.6f,
                     base.b * 0.6f, 0.45f);
    stroke_hex_screen(ren, v, cx, cy, base.r * 0.5f, base.g * 0.5f, base.b * 0.5f, 0.95f);
    int const pop_tier = city_population_tier(ct.population());
    if (pop_tier >= 2) {
      stroke_hex_screen(ren, v, cx, cy, 1.f, 218.f / 255.f, 150.f / 255.f,
                          pop_tier >= 3 ? 200.f / 255.f : 155.f / 255.f);
    }
    if (pop_tier >= 3) {
      stroke_hex_screen(ren, v, cx, cy, 1.f, 248.f / 255.f, 210.f / 255.f, 175.f / 255.f);
    }
    if (v.scale >= k_lod_detail_min_scale) {
      draw_ground_ellipse_shadow(ren, v, cx, cy, HEX_R * 0.74f, HEX_R * 0.35f, 0.18f);
    }
    if (selected_city_id.has_value() && *selected_city_id == ct.id()) {
      stroke_hex_screen(ren, v, cx, cy, 1.f, 0.9f, 0.28f, 1.f);
    }
    float hcx = 0;
    float hcy = 0;
    world_to_screen(v, cx, cy, &hcx, &hcy);
    std::string banner = ct.name();
    if (banner.size() > 18) {
      banner.resize(15);
      banner += "...";
    }
    float const name_half_w = menu_ui::text_width(banner.c_str()) * 0.5f;
    float const stack_h = k_city_label_name_h + k_city_label_gap + k_city_plate_h;
    float const name_ty = hcy - stack_h * 0.5f;
    bool const show_build = ct.current_build().has_value() && visible.count(ct.coord());
    char build_glyph = show_build ? unit_kind_glyph(*ct.current_build())[0] : '\0';
    draw_city_map_banner(ren, hcx, name_ty, name_half_w, banner, ct.population(), pop_tier,
                         show_build, build_glyph);
  }

  bool hunt_highlight = false;
  HexCoord hunt_coord{0, 0};
  if (selected_unit_id.has_value()) {
    if (auto su = session.unit_by_id(*selected_unit_id);
        su.has_value() && su->kind() == UnitKind::HUNTING_PARTY &&
        su->owner_seat() == cur_seat && su->moves_remaining() > 0) {
      hunt_highlight = session.wild_animal_at(su->coord()).has_value();
      hunt_coord = su->coord();
    }
  }

  // --- Wildlife ---
  for (auto const& beast : session.wildlife_list()) {
    if (beast.is_dead() || !visible.count(beast.coord())) {
      continue;
    }
    bool const hunt_target = hunt_highlight && beast.coord() == hunt_coord;
    draw_wild_animal_marker(ren, v, beast, hunt_target);
  }

  if (selected_unit_id.has_value()) {
    if (auto su = session.unit_by_id(*selected_unit_id);
        su.has_value() && su->owner_seat() == cur_seat && visited.count(su->coord())) {
      draw_unit_ground_selection_ring(ren, v, *su, session);
    }
  }

  // --- Units ---
  for (Unit const& u : session.units()) {
    bool const in_sight = visible.count(u.coord());
    bool const own = u.owner_seat() == cur_seat;
    if (!in_sight && !(own && visited.count(u.coord()))) {
      continue;
    }
    bool const fog_unit = own && visited.count(u.coord()) && !in_sight;
    float cx = axial_to_world_x(u.coord());
    float cy = axial_to_world_y(u.coord());
    if (session.city_at(u.coord()).has_value()) {
      cy += HEX_R * 0.22f;
    }
    RGB base = player_rgb(u.owner_seat());
    RGB rim = blend_rgb(base, rgb_u8(0, 0, 0), 0.35f);
    bool const exhausted =
        u.moves_remaining() == 0 && u.owner_seat() == session.current_player().seat;
    if (exhausted) {
      base = blend_rgb(base, rgb_u8(200, 200, 200), 0.45f);
      rim = blend_rgb(rim, rgb_u8(128, 128, 128), 0.45f);
    }
    if (fog_unit) {
      base = blend_rgb(base, rgb_u8(110, 110, 110), 0.50f);
      rim = blend_rgb(rim, rgb_u8(70, 70, 70), 0.50f);
    }

    float sx = 0;
    float sy = 0;
    float const r = HEX_R * 0.42f * v.scale;
    world_to_screen(v, cx, cy, &sx, &sy);
    if (v.scale >= k_lod_detail_min_scale && !fog_unit) {
      draw_ground_ellipse_shadow(ren, v, cx, cy, HEX_R * 0.46f, HEX_R * 0.17f, 0.22f);
    }
    SDL_FColor rim_c = to_fcolor(rim);
    SDL_FColor base_c = to_fcolor(base);
    float const unit_alpha = fog_unit ? 0.62f : 1.f;
    SDL_SetRenderDrawColorFloat(ren, rim_c.r, rim_c.g, rim_c.b, rim_c.a * unit_alpha);
    SDL_FRect ring_outer{sx - r - 2.5f, sy - r - 2.5f, 2 * r + 5.f, 2 * r + 5.f};
    SDL_RenderFillRect(ren, &ring_outer);
    SDL_SetRenderDrawColorFloat(ren, base_c.r, base_c.g, base_c.b, base_c.a * unit_alpha);
    SDL_FRect disk{sx - r, sy - r, 2 * r, 2 * r};
    SDL_RenderFillRect(ren, &disk);
    SDL_SetRenderDrawColorFloat(ren, 1.f, 1.f, 1.f, (105.f / 255.f) * unit_alpha);
    SDL_FRect sheen{sx - r + 2.f, sy - r + 2.f, 2 * r - 4.f, 2 * r - 4.f};
    SDL_RenderRect(ren, &sheen);

    draw_unit_kind_glyph_badge(ren, sx, sy, u.kind(), unit_alpha);

    if (u.hp() < u.max_hp()) {
      float const bar_w = 2.f * r + 6.f;
      float const bar_h = 4.f;
      float const hp_frac =
          static_cast<float>(u.hp()) / static_cast<float>(std::max(1, u.max_hp()));
      draw_unit_hp_bar(ren, sx, sy + r + 4.f, bar_w, bar_h, hp_frac);
    }

    if (!session.planned_route_for(u.id()).empty()) {
      SDL_FRect route_bg{sx + r * 0.45f - 7.f, sy - r * 0.55f - 6.f, 14.f, 12.f};
      menu_ui::fill_rect(ren, route_bg, rgb_u8(35, 200, 255), 0.90f * unit_alpha);
      menu_ui::draw_text(ren, route_bg.x + 4.f, route_bg.y + 1.f, ">", menu_ui::k_title, unit_alpha);
    }
    if (own && u.sleeping()) {
      menu_ui::draw_text(ren, sx - r + 2.f, sy - r + 1.f, "Z", menu_ui::k_hint, 0.92f * unit_alpha);
    } else if (own && u.auto_explore()) {
      menu_ui::draw_text(ren, sx - r + 2.f, sy - r + 1.f, "E", menu_ui::k_ok, 0.92f * unit_alpha);
    }
    if (own && u.carried_food() > 0) {
      char food_label[8];
      std::snprintf(food_label, sizeof(food_label), "+%d", u.carried_food());
      SDL_FRect food_bg{sx + r * 0.35f - 2.f, sy + r * 0.35f, 18.f, 12.f};
      menu_ui::fill_rect(ren, food_bg, rgb_u8(18, 22, 30), 0.90f * unit_alpha);
      menu_ui::draw_text(ren, food_bg.x + 2.f, food_bg.y + 1.f, food_label, menu_ui::k_ok,
                         unit_alpha);
    }

    bool const ring =
        selected_unit_id.has_value() && *selected_unit_id == u.id();
    if (ring) {
      SDL_SetRenderDrawColorFloat(ren, 1.f, 0.92f, 0.35f, 1.f);
      SDL_FRect sel{sx - r - 5.f, sy - r - 5.f, 2 * r + 10.f, 2 * r + 10.f};
      SDL_RenderRect(ren, &sel);
    }
    if (gui != nullptr && own && !session.is_over() && !session.current_player().computer) {
      if (gui->skipped_units.count(u.id())) {
        menu_ui::draw_text(ren, sx - 4.f, sy - r - 15.f, "S", menu_ui::k_muted, 0.95f);
      } else if (sdl_unit_needs_manual_orders(session, *gui, u)) {
        SDL_FRect alert{sx - 5.f, sy - r - 17.f, 10.f, 10.f};
        menu_ui::fill_rect(ren, alert, rgb_u8(255, 140, 38), 0.95f);
        menu_ui::draw_text(ren, sx - 2.f, sy - r - 15.f, "!", menu_ui::k_ink, 1.f);
      }
    }
  }

  if (!session.is_over() && !session.current_player().computer) {
    draw_other_queued_routes(ren, v, session, cur_seat, selected_unit_id);
  }
  if (projected_path.size() >= 2) {
    draw_projected_path(ren, v, projected_path, projected_turns, selected, &session);
  }

  if (hovered_hex.has_value() && session.map().contains(*hovered_hex)) {
    float const cx = axial_to_world_x(*hovered_hex);
    float const cy = axial_to_world_y(*hovered_hex);
    bool const hover_known = visited.count(*hovered_hex);
    bool outline_fog_target = false;
    if (selected_unit_id.has_value()) {
      if (auto su = session.unit_by_id(*selected_unit_id);
          su.has_value() && su->owner_seat() == cur_seat) {
        outline_fog_target = true;
      }
    }
    if (hover_known || outline_fog_target) {
      draw_hex_hover_highlight(ren, v, cx, cy, hover_known && visible.count(*hovered_hex));
    }
  }

  if (gui != nullptr && gui->combat_flash_hex.has_value()) {
    draw_combat_flash(ren, v, *gui->combat_flash_hex, gui->combat_flash_start_ticks,
                      gui->combat_flash_until_ticks);
    if (SDL_GetTicks() >= gui->combat_flash_until_ticks) {
      gui->combat_flash_hex.reset();
    }
  }

  if (!session.is_over()) {
    draw_time_of_day_overlay(ren, session, win_w, win_h);
  }

  if (gui != nullptr) {
    draw_move_command_overlay(ren, *gui, v, session, selected_unit_id, hovered_hex, win_w, win_h);
  }

  draw_minimap(ren, session, v, cur_seat, win_w, win_h,
               play_screen_right_ui_margin(session, selected_city_id), selected_unit_id,
               gui != nullptr && gui->minimap_tint_own_claims_only);
  float const max_panel_bottom = play_ui_max_panel_bottom(win_h, gui);
  draw_top_hud(ren, session, gui, win_w);
  if (gui != nullptr) {
    draw_game_menu_ui(ren, *gui, session, win_w);
    draw_play_status_chips(ren, *gui, session, selected_unit_id, win_w);
  }
  UnitInspectorLayout const unit_layout = compute_unit_inspector_layout(
      session, selected_unit_id, cur_seat, win_h, max_panel_bottom);
  ProductionNeedsLayout needs_layout = compute_production_needs_layout(
      session, selected_unit_id, selected_city_id, win_h, max_panel_bottom);
  stack_production_needs_below_unit(unit_layout, &needs_layout);
  clamp_panel_max_bottom(max_panel_bottom, &needs_layout.panel);
  trim_production_needs_buttons(&needs_layout);
  draw_unit_inspector(ren, session, unit_layout, cur_seat);
  draw_production_needs_panel(ren, session, needs_layout);
  bool const show_production = production_drawer_visible(session, selected_city_id);
  ProductionDrawerLayout const prod_layout =
      compute_production_drawer_layout(win_w, win_h, show_production);
  CityInspectorLayout const city_layout = compute_city_inspector_layout(
      session, selected_city_id, win_w, win_h, show_production, max_panel_bottom);
  if (show_production && selected_city_id.has_value()) {
    if (auto c = session.city_by_id(*selected_city_id); c.has_value()) {
      draw_production_drawer(ren, session, *c, prod_layout);
    }
  }
  draw_city_inspector(ren, session, city_layout, show_production);
  BottomActionBarLayout const action_bar = compute_bottom_action_bar_layout(
      session, *gui, selected_unit_id, selected_city_id, win_w, win_h);
  if (gui != nullptr) {
    EventLogLayout const event_log_layout = compute_event_log_layout(*gui, win_w, win_h);
    draw_event_log_panel(ren, event_log_layout, *gui);
    float const legend_ceiling =
        event_log_layout.visible ? event_log_layout.panel.y - 6.f
                                 : static_cast<float>(win_h) - k_footer_bar_h - 8.f;
    if (gui->show_weather_legend && !session.is_over() && !session.current_player().computer) {
      draw_weather_legend(ren, win_w, win_h, legend_ceiling);
    }
    if (gui->show_claim_legend && !session.is_over() && !session.current_player().computer) {
      float const left_min_top =
          left_ui_column_bottom(unit_layout, needs_layout);
      draw_claim_legend(ren, win_w, legend_ceiling, left_min_top);
    }
  }
  draw_game_over_overlay(ren, session, win_w, win_h);
  draw_footer_bar(ren, win_w, win_h);
  bool const orb_hovered =
      action_bar.visible && screen_point_in_rect(mouse_x, mouse_y, action_bar.action_hit);
  draw_bottom_action_bar(ren, action_bar, orb_hovered);

  float const foot_y = static_cast<float>(win_h) - k_footer_bar_h;
  float const hint_max_x = footer_hint_max_x(action_bar, win_w);
  int const hint_cols = std::max(32, static_cast<int>((hint_max_x - 20.f) / 7.f));
  if (action_bar.visible && action_bar.action.end_turn_ready) {
    menu_ui::draw_text(ren, 10.f, foot_y + 8.f,
                       menu_ui::truncate_line("Ready to end your turn — click the green play button or press Enter",
                                              hint_cols)
                           .c_str(),
                       menu_ui::k_ok);
  } else if (action_bar.visible && !action_bar.action.detail.empty()) {
    std::string detail = menu_ui::truncate_line(action_bar.action.detail, hint_cols);
    menu_ui::draw_text(ren, 10.f, foot_y + 8.f, detail.c_str(), menu_ui::k_hint);
  } else {
    menu_ui::draw_text(ren, 10.f, foot_y + 8.f,
                       menu_ui::truncate_line("Pan: Shift+drag / middle-drag / arrows  Zoom: wheel or +/-",
                                              hint_cols)
                           .c_str(),
                       menu_ui::k_hint);
  }
  menu_ui::draw_text(
      ren, 10.f, foot_y + 26.f,
      menu_ui::truncate_line(
          "Action button (right) changes by context  P / Enter = same action  F1 help",
          hint_cols)
          .c_str(),
      menu_ui::k_hint);
  if (sdl_player_has_city_without_production(session)) {
    menu_ui::draw_text(ren, 10.f, foot_y + 44.f,
                       menu_ui::truncate_line(
                           "Choose city production in the Build panel (keys 1-6, Shift+click to queue).",
                           hint_cols)
                           .c_str(),
                       menu_ui::k_accent);
  } else if (action_bar.visible && !action_bar.action.end_turn_ready &&
             action_bar.action.kind == HudActionKind::NextUnit) {
    menu_ui::draw_text(ren, 10.f, foot_y + 44.f,
                       menu_ui::truncate_line("Units still need orders — use the action button to jump to the next",
                                              hint_cols)
                           .c_str(),
                       menu_ui::k_hint);
  } else {
    menu_ui::draw_text(ren, 10.f, foot_y + 44.f,
                       menu_ui::truncate_line(
                           "Hot seat: pass-device screen may appear before the next human plays.",
                           hint_cols)
                           .c_str(),
                       menu_ui::k_hint);
  }

  Uint64 const now = SDL_GetTicks();
  if (gui != nullptr && now >= gui->toast_until_ticks) {
    gui->toast_text.clear();
  }
  if (gui != nullptr) {
    float const toast_max_right =
        action_bar.visible ? action_bar.action_orb.x - 8.f : 0.f;
    draw_menu_toast(ren, *gui, win_w, win_h, toast_max_right);
  }

  float stack_top_y = left_ui_column_bottom(unit_layout, needs_layout);
  if (gui != nullptr && gui->show_hotkeys) {
    draw_hotkeys_overlay(ren, win_w, win_h, &stack_top_y);
  }
  if (gui != nullptr && gui->show_intro_tips) {
    draw_intro_tips_overlay(ren, win_w, win_h);
  }

  EventLogLayout const hover_event_log =
      gui != nullptr ? compute_event_log_layout(*gui, win_w, win_h) : EventLogLayout{};
  float const hover_legend_ceiling =
      hover_event_log.visible ? hover_event_log.panel.y - 6.f
                              : static_cast<float>(win_h) - k_footer_bar_h - 8.f;
  bool const show_claim = gui != nullptr && gui->show_claim_legend && !session.is_over() &&
                          !session.current_player().computer;
  TileHoverPlacement const hover_place = compute_tile_hover_placement(
      hover_line, hint_max_x, unit_layout, needs_layout, hover_legend_ceiling, show_claim,
      stack_top_y);
  if (hover_place.visible) {
    draw_tile_hover_text(ren, hover_place.x, hover_place.y, hover_line, hover_place.wrap_w);
  }

}

constexpr char k_gui_camera_json[] = "tack_strat_gui_camera.json";

void stash_camera_for_seat(GuiState* gui, View const& v, int seat) {
  if (gui == nullptr || seat < 0) {
    return;
  }
  gui->seat_cameras[seat] = SeatCamera{v.scale, v.offset_x, v.offset_y};
}

[[nodiscard]] bool restore_camera_for_seat(GuiState* gui, View* v, int seat, int win_w, int win_h) {
  if (gui == nullptr || seat < 0) {
    return false;
  }
  auto it = gui->seat_cameras.find(seat);
  if (it == gui->seat_cameras.end()) {
    return false;
  }
  v->scale = it->second.scale;
  v->offset_x = it->second.offset_x;
  v->offset_y = it->second.offset_y;
  clamp_view(v, win_w, win_h);
  return true;
}

void zoom_view_on_unit_hex(View* v, GameSession const& session, int unit_id, int win_w, int win_h) {
  auto u = session.unit_by_id(unit_id);
  if (!u.has_value()) {
    return;
  }
  float const world_span = HEX_R * std::sqrt(3.f) * 6.5f;
  float const wf = static_cast<float>(std::max(100, win_w));
  v->scale = std::clamp(0.92f * wf / world_span, MIN_SCALE, MAX_SCALE);
  center_on_hex(v, u->coord(), win_w, win_h);
}

void center_on_current_player(GameSession const& session, View* v, int win_w, int win_h) {
  int const seat = session.current_player().seat;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat) {
      zoom_view_on_unit_hex(v, session, u.id(), win_w, win_h);
      return;
    }
  }
  for (City const& c : session.cities()) {
    if (c.owner_seat() == seat) {
      float const world_span = HEX_R * std::sqrt(3.f) * 6.5f;
      float const wf = static_cast<float>(std::max(100, win_w));
      v->scale = std::clamp(0.92f * wf / world_span, MIN_SCALE, MAX_SCALE);
      center_on_hex(v, c.coord(), win_w, win_h);
      return;
    }
  }
  fit_map_to_window(v, win_w, win_h);
}

void ensure_view_shows_discovered(View* v, GameSession const& session, int seat, int win_w,
                                  int win_h) {
  if (view_shows_any_discovered(*v, session, seat, win_w, win_h)) {
    return;
  }
  center_on_current_player(session, v, win_w, win_h);
  enforce_view_discovered_constraints(v, session, seat, win_w, win_h);
  if (!view_shows_any_discovered(*v, session, seat, win_w, win_h)) {
    fit_map_to_window(v, win_w, win_h);
  }
}

/** Java {@code HexMapPanel#syncCameraToCurrentPlayer}: stash outgoing seat, restore incoming. */
[[nodiscard]] bool sync_camera_to_current_player(GameSession const& session, GuiState* gui, View* v,
                                                 int win_w, int win_h) {
  if (gui == nullptr || session.current_player().computer) {
    return false;
  }
  int const seat = session.current_player().seat;
  if (seat == gui->camera_seat) {
    return gui->seat_cameras.count(seat) != 0;
  }
  if (gui->camera_seat >= 0) {
    stash_camera_for_seat(gui, *v, gui->camera_seat);
  }
  gui->camera_seat = seat;
  return restore_camera_for_seat(gui, v, seat, win_w, win_h);
}

void pick_default_human_unit(GameSession const& session, std::optional<int>* out) {
  out->reset();
  if (session.current_player().computer) {
    return;
  }
  int const seat = session.current_player().seat;
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat && u.kind() == UnitKind::SETTLER) {
      *out = u.id();
      return;
    }
  }
  for (Unit const& u : session.units()) {
    if (u.owner_seat() == seat) {
      *out = u.id();
      return;
    }
  }
}

bool try_load_gui_camera_json(GameSession const& session, GuiState* gui, View* v, int win_w,
                              int win_h) {
  std::ifstream in(k_gui_camera_json);
  if (!in || gui == nullptr) {
    return false;
  }
  try {
    nlohmann::json const j = nlohmann::json::parse(in, nullptr, false);
    if (j.is_discarded() || !j.is_object()) {
      return false;
    }
    if (!j.contains("world_seed") || j.at("world_seed").get<std::int64_t>() != session.world_seed()) {
      return false;
    }
    gui->seat_cameras.clear();
    gui->camera_seat = -1;
    if (j.contains("seat_cameras") && j.at("seat_cameras").is_array()) {
      for (nlohmann::json const& row : j.at("seat_cameras")) {
        if (!row.is_object() || !row.contains("seat")) {
          continue;
        }
        int const seat = row.at("seat").get<int>();
        SeatCamera cam{};
        if (row.contains("scale")) {
          cam.scale = std::clamp(static_cast<float>(row.at("scale").get<double>()), MIN_SCALE,
                                 MAX_SCALE);
        }
        if (row.contains("offset_x")) {
          cam.offset_x = static_cast<float>(row.at("offset_x").get<double>());
        }
        if (row.contains("offset_y")) {
          cam.offset_y = static_cast<float>(row.at("offset_y").get<double>());
        }
        gui->seat_cameras[seat] = cam;
      }
      return !gui->seat_cameras.empty();
    }
    // Legacy single-camera file: treat as seat 0 only.
    SeatCamera legacy{};
    if (j.contains("scale")) {
      legacy.scale =
          std::clamp(static_cast<float>(j.at("scale").get<double>()), MIN_SCALE, MAX_SCALE);
    }
    if (j.contains("offset_x")) {
      legacy.offset_x = static_cast<float>(j.at("offset_x").get<double>());
    }
    if (j.contains("offset_y")) {
      legacy.offset_y = static_cast<float>(j.at("offset_y").get<double>());
    }
    gui->seat_cameras[0] = legacy;
    return true;
  } catch (...) {
    return false;
  }
}

void save_gui_camera_json(GameSession const& session, GuiState* gui, View const& v) {
  if (gui == nullptr) {
    return;
  }
  try {
    if (gui->camera_seat >= 0) {
      stash_camera_for_seat(gui, v, gui->camera_seat);
    }
    nlohmann::json j;
    j["world_seed"] = session.world_seed();
    nlohmann::json seats = nlohmann::json::array();
    std::vector<int> seat_ids;
    seat_ids.reserve(gui->seat_cameras.size());
    for (auto const& [seat, cam] : gui->seat_cameras) {
      seat_ids.push_back(seat);
    }
    std::sort(seat_ids.begin(), seat_ids.end());
    for (int seat : seat_ids) {
      SeatCamera const& cam = gui->seat_cameras.at(seat);
      seats.push_back({{"seat", seat},
                       {"scale", static_cast<double>(cam.scale)},
                       {"offset_x", static_cast<double>(cam.offset_x)},
                       {"offset_y", static_cast<double>(cam.offset_y)}});
    }
    j["seat_cameras"] = std::move(seats);
    std::ofstream out(k_gui_camera_json);
    out << j.dump(2);
  } catch (...) {
  }
}

/** Java {@code PlayPanel#onTurnBegan}: per-seat camera + select current player's unit. */
void on_turn_began_sdl(GameSession& session, GuiState* gui, View* v, std::optional<int>* sel_unit,
                       std::optional<int>* sel_city, int win_w, int win_h) {
  if (sel_city != nullptr) {
    sel_city->reset();
  }
  gui->move_command_mode = false;
  gui->move_cursor.reset();
  gui->move_cursor_locked = false;
  gui->skipped_units.clear();

  sync_view_bounds_for_player(v, session, session.current_player().seat);

  if (session.current_player().computer) {
    fit_map_to_window(v, win_w, win_h);
    sel_unit->reset();
    enforce_view_discovered_constraints(v, session, session.current_player().seat, win_w, win_h);
    return;
  }

  bool const restored = sync_camera_to_current_player(session, gui, v, win_w, win_h);
  if (!restored) {
    center_on_current_player(session, v, win_w, win_h);
  }
  sel_unit->reset();
  if (gui->auto_focus_next_unit) {
    if (sdl_player_has_city_without_production(session)) {
      select_first_city_missing_production_sdl(session, v, sel_unit, sel_city, win_w, win_h);
    } else {
      sel_unit->reset();
      cycle_next_unit(session, sel_unit, v, win_w, win_h, gui, sel_city);
    }
  }
  if (sel_unit->has_value()) {
    auto u = session.unit_by_id(**sel_unit);
    if (u.has_value()) {
      keep_hex_visible(v, u->coord(), win_w, win_h);
    }
  }
  enforce_view_discovered_constraints(v, session, session.current_player().seat, win_w, win_h);
  ensure_view_shows_discovered(v, session, session.current_player().seat, win_w, win_h);

  Player const& cur = session.current_player();
  ensure_event_log_seeded(session, gui);
  if (session.current_player().seat == 0) {
    int const spawned = session.wildlife_spawned_last_step();
    int const spawned_round = session.wildlife_spawned_last_step_round();
    if (spawned > 0 && spawned_round != gui->last_wildlife_arrival_round_logged) {
      append_event_log(gui, "Wildlife activity: " + std::to_string(spawned) + " new animal" +
                                 (spawned == 1 ? "" : "s") + " arrived this round.");
      gui->last_wildlife_arrival_round_logged = spawned_round;
    }
  }
  std::string const summary = sdl_turn_summary(session);
  if (!summary.empty()) {
    append_event_log(gui, menu_ui::truncate_line(summary, 96));
    set_toast(gui, menu_ui::truncate_line(summary, 88), 5200);
  } else {
    set_toast(gui, "Round " + std::to_string(session.round()) + " - " + cur.name + "'s turn", 3400);
  }
}

void bootstrap_player_view(GameSession& session, GuiState* gui, View* v,
                           std::optional<int>* sel_unit, std::optional<int>* sel_city, int win_w,
                           int win_h, bool reset_cameras) {
  if (reset_cameras) {
    gui->camera_seat = -1;
    gui->seat_cameras.clear();
    static_cast<void>(try_load_gui_camera_json(session, gui, v, win_w, win_h));
  }
  on_turn_began_sdl(session, gui, v, sel_unit, sel_city, win_w, win_h);
}

[[nodiscard]] char const* map_preset_label(tack::strat::MapSizePreset p) noexcept {
  using tack::strat::MapSizePreset;
  switch (p) {
    case MapSizePreset::Tiny:
      return "tiny";
    case MapSizePreset::Small:
      return "small";
    case MapSizePreset::Medium:
      return "medium";
    case MapSizePreset::Large:
      return "large";
  }
  return "tiny";
}

[[nodiscard]] bool screen_point_in_rect(float px, float py, SDL_FRect const& r) noexcept {
  return px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h;
}

void reroll_menu_world_seed(StartMenuState* menu) {
  std::random_device rd;
  std::mt19937_64 gen(rd());
  menu->world_seed = static_cast<std::int64_t>(gen());
}

void cycle_menu_map_size(StartMenuState* menu) {
  using tack::strat::MapSizePreset;
  switch (menu->map_size) {
    case MapSizePreset::Tiny:
      menu->map_size = MapSizePreset::Small;
      break;
    case MapSizePreset::Small:
      menu->map_size = MapSizePreset::Medium;
      break;
    case MapSizePreset::Medium:
      menu->map_size = MapSizePreset::Large;
      break;
    case MapSizePreset::Large:
      menu->map_size = MapSizePreset::Tiny;
      break;
  }
}

[[nodiscard]] std::vector<Player> build_players_from_menu(StartMenuState const& menu) {
  std::vector<Player> out;
  int const n = std::clamp(menu.player_count, 1, 4);
  out.reserve(static_cast<std::size_t>(n));
  for (int i = 0; i < n; ++i) {
    std::string name = "Player " + std::to_string(i + 1);
    bool const cpu = menu.seat_cpu[static_cast<std::size_t>(i)];
    out.emplace_back(i, std::move(name), cpu);
  }
  return out;
}

[[nodiscard]] bool menu_has_human_seat(StartMenuState const& menu) noexcept {
  int const n = std::clamp(menu.player_count, 1, 4);
  for (int i = 0; i < n; ++i) {
    if (!menu.seat_cpu[static_cast<std::size_t>(i)]) {
      return true;
    }
  }
  return false;
}

namespace menu_ui {

void fill_rect(SDL_Renderer* ren, SDL_FRect r, RGB c, float a) {
  SDL_SetRenderDrawColorFloat(ren, c.r, c.g, c.b, a);
  SDL_RenderFillRect(ren, &r);
}

void stroke_rect(SDL_Renderer* ren, SDL_FRect r, RGB c, float a) {
  SDL_SetRenderDrawColorFloat(ren, c.r, c.g, c.b, a);
  SDL_RenderRect(ren, &r);
}

void draw_modal_card(SDL_Renderer* ren, SDL_FRect r) {
  fill_rect(ren, {r.x + 4.f, r.y + 5.f, r.w, r.h}, k_ink, 0.32f);
  fill_rect(ren, r, k_modal_bg, 0.97f);
  stroke_rect(ren, r, k_accent, 0.72f);
}

[[nodiscard]] std::string truncate_line(std::string s, std::size_t max_len) {
  if (s.size() <= max_len) {
    return s;
  }
  if (max_len <= 3) {
    return s.substr(0, max_len);
  }
  s.resize(max_len - 3);
  s += "...";
  return s;
}

[[nodiscard]] std::vector<std::string> wrap_text_to_width(std::string text, float max_width) {
  std::vector<std::string> lines;
  if (text.empty()) {
    return lines;
  }
  auto trim_front = [](std::string& s) {
    while (!s.empty() && s.front() == ' ') {
      s.erase(s.begin());
    }
  };
  std::size_t pos = 0;
  while (pos < text.size()) {
    std::size_t const nl = text.find('\n', pos);
    std::string paragraph =
        nl == std::string::npos ? text.substr(pos) : text.substr(pos, nl - pos);
    pos = nl == std::string::npos ? text.size() : nl + 1;
    trim_front(paragraph);
    while (!paragraph.empty()) {
      if (text_width(paragraph.c_str()) <= max_width) {
        lines.push_back(paragraph);
        break;
      }
      std::size_t break_at = paragraph.size();
      while (break_at > 0 && text_width(paragraph.substr(0, break_at).c_str()) > max_width) {
        std::size_t const sp = paragraph.rfind(' ', break_at - 1);
        if (sp == std::string::npos || sp < break_at / 4) {
          break_at = std::max<std::size_t>(1, break_at - 1);
        } else {
          break_at = sp;
        }
      }
      if (break_at == 0) {
        break_at = 1;
      }
      lines.push_back(paragraph.substr(0, break_at));
      paragraph = paragraph.substr(break_at);
      trim_front(paragraph);
    }
  }
  return lines;
}

[[nodiscard]] float text_width(char const* text) noexcept {
  if (g_ui_font != nullptr && text != nullptr && text[0] != '\0') {
    int w = 0;
    int h = 0;
    if (TTF_GetStringSize(g_ui_font, text, 0, &w, &h)) {
      return static_cast<float>(w);
    }
  }
  return static_cast<float>(std::strlen(text)) * 7.15f;
}

void draw_text(SDL_Renderer* ren, float x, float y, char const* text, RGB c, float a) {
  if (g_ui_font != nullptr) {
    int w = 0;
    int h = 0;
    if (SDL_Texture* tex = acquire_text_texture(ren, text, c, &w, &h); tex != nullptr) {
      SDL_SetTextureAlphaModFloat(tex, a);
      // Debug-font baseline sat ~8px tall at y; nudge the taller TTF glyphs up slightly so
      // existing y positions still read as the text top.
      SDL_FRect dst{x, y - 1.f, static_cast<float>(w), static_cast<float>(h)};
      SDL_RenderTexture(ren, tex, nullptr, &dst);
      return;
    }
  }
  SDL_SetRenderDrawColorFloat(ren, c.r, c.g, c.b, a);
  SDL_RenderDebugText(ren, x, y, text);
}

void draw_text_wrapped(SDL_Renderer* ren, float x, float y, std::string const& text, float max_w,
                       float line_h, RGB c, float a) {
  for (std::string const& line : wrap_text_to_width(text, max_w)) {
    draw_text(ren, x, y, line.c_str(), c, a);
    y += line_h;
  }
}

void draw_text_centered(SDL_Renderer* ren, float cx, float y, char const* text, RGB c, float a) {
  draw_text(ren, cx - text_width(text) * 0.5f, y, text, c, a);
}

void draw_text_centered_wrapped(SDL_Renderer* ren, float cx, float y, std::string const& text,
                                float max_w, float line_h, RGB c, float a) {
  for (std::string const& line : wrap_text_to_width(text, max_w)) {
    draw_text_centered(ren, cx, y, line.c_str(), c, a);
    y += line_h;
  }
}

void draw_button(SDL_Renderer* ren, SDL_FRect r, char const* label, RGB fill, RGB border, RGB text,
                 bool enabled, bool primary) {
  if (!enabled) {
    fill = k_secondary;
    text = k_disabled;
  }
  if (primary && enabled) {
    fill_rect(ren, {r.x, r.y + 2.f, r.w, r.h}, k_primary_dark, 0.55f);
  }
  fill_rect(ren, r, fill, enabled ? 1.f : 0.72f);
  stroke_rect(ren, r, border, enabled ? 1.f : 0.55f);
  float const tx = r.x + std::max(8.f, (r.w - text_width(label)) * 0.5f);
  draw_text(ren, tx, r.y + (r.h - 14.f) * 0.5f, label, text, enabled ? 1.f : 0.85f);
}

[[nodiscard]] SetupMenuLayout compute_setup_menu_layout(int win_w, int win_h, int player_count) {
  SetupMenuLayout L{};
  int const n = std::clamp(player_count, 1, 4);
  L.card_w = std::min(520.f, static_cast<float>(win_w) - 64.f);
  float const card_pad = 24.f;
  float const row_h = 34.f;
  float const inner_h = 52.f + static_cast<float>(n) * row_h + 88.f + 118.f;
  L.card_h = card_pad * 2.f + inner_h;
  L.card_x = (static_cast<float>(win_w) - L.card_w) * 0.5f;
  float const content_top = 118.f;
  float const content_bottom = static_cast<float>(win_h) - 56.f;
  float const free = std::max(0.f, content_bottom - content_top - L.card_h);
  L.card_y = content_top + free * 0.42f;

  float const cx = L.card_x + L.card_w * 0.5f;
  float y = L.card_y + card_pad + 36.f;

  L.minus_btn = {L.card_x + card_pad + 92.f, y - 4.f, 34.f, 30.f};
  L.plus_btn = {L.minus_btn.x + L.minus_btn.w + 8.f, y - 4.f, 34.f, 30.f};
  y += 18.f + static_cast<float>(n) * row_h + 14.f;

  L.seed_btn = {L.card_x + card_pad, y, L.card_w * 0.48f - card_pad, 26.f};
  L.map_btn = {L.card_x + L.card_w * 0.52f, y, L.card_w * 0.48f - card_pad, 26.f};
  y += 38.f;

  L.continue_btn = {L.card_x + card_pad, y, L.card_w - card_pad * 2.f, 36.f};
  y += 44.f;
  L.load_btn = {L.card_x + card_pad, y, L.card_w - card_pad * 2.f, 34.f};
  y += 44.f;
  L.start_btn = {cx - 120.f, y, 240.f, 40.f};

  float row_y = L.card_y + card_pad + 54.f;
  for (int i = 0; i < 4; ++i) {
    L.seat_rows[static_cast<std::size_t>(i)] = {L.card_x + card_pad - 4.f, row_y - 3.f,
                                                L.card_w - card_pad * 2.f + 8.f, row_h - 4.f};
    row_y += row_h;
  }
  return L;
}

[[nodiscard]] std::string format_menu_seed(std::int64_t seed) {
  std::string s = std::to_string(seed);
  if (s.size() > 14) {
    return s.substr(0, 11) + "...";
  }
  return s;
}

[[nodiscard]] HandoffLayout compute_handoff_layout(int win_w, int win_h, bool show_retry) {
  HandoffLayout L{};
  L.card.w = std::min(520.f, static_cast<float>(win_w) - 64.f);
  L.card.h = show_retry ? 404.f : 364.f;
  L.card.x = (static_cast<float>(win_w) - L.card.w) * 0.5f;
  L.card.y = (static_cast<float>(win_h) - L.card.h) * 0.5f - 16.f;
  L.begin_btn = {L.card.x + (L.card.w - 220.f) * 0.5f, L.card.y + L.card.h - (show_retry ? 98.f : 58.f),
                 220.f, 40.f};
  if (show_retry) {
    L.retry_btn = {L.card.x + (L.card.w - 168.f) * 0.5f, L.begin_btn.y + 48.f, 168.f, 32.f};
  }
  return L;
}

[[nodiscard]] VictoryLayout compute_victory_layout(int win_w, int win_h) {
  VictoryLayout L{};
  L.card.w = std::min(520.f, static_cast<float>(win_w) - 64.f);
  L.card.h = 340.f;
  L.card.x = (static_cast<float>(win_w) - L.card.w) * 0.5f;
  L.card.y = (static_cast<float>(win_h) - L.card.h) * 0.5f - 20.f;
  float const cx = L.card.x + L.card.w * 0.5f;
  L.review_btn = {cx - 130.f, L.card.y + L.card.h - 118.f, 260.f, 38.f};
  L.menu_btn = {L.card.x + 24.f, L.card.y + L.card.h - 68.f, (L.card.w - 56.f) * 0.5f, 36.f};
  L.again_btn = {L.menu_btn.x + L.menu_btn.w + 8.f, L.menu_btn.y, L.menu_btn.w, 36.f};
  return L;
}

}  // namespace menu_ui

void render_start_menu(SDL_Renderer* ren, StartMenuState const& menu, GuiState const& gui, int win_w,
                        int win_h) {
  using namespace menu_ui;
  paint_vertical_background(ren, win_w, win_h);

  float const cx = static_cast<float>(win_w) * 0.5f;
  draw_text_centered(ren, cx, 34.f, "Tack & Strat", k_muted, 0.92f);
  draw_text_centered(ren, cx, 62.f, "New game", k_title);
  draw_text_centered(ren, cx, 88.f,
                     "1-4 seats. Pass the device between humans; Space toggles CPU.", k_hint);

  int const n = std::clamp(menu.player_count, 1, 4);
  SetupMenuLayout const L = compute_setup_menu_layout(win_w, win_h, n);

  fill_rect(ren, {L.card_x + 4.f, L.card_y + 5.f, L.card_w, L.card_h}, k_ink, 0.22f);
  fill_rect(ren, {L.card_x, L.card_y, L.card_w, L.card_h}, k_card_bg);
  stroke_rect(ren, {L.card_x, L.card_y, L.card_w, L.card_h}, k_card_line);

  float const pad = 24.f;
  float y = L.card_y + pad;
  draw_text(ren, L.card_x + pad, y, "Players", k_ink);
  draw_button(ren, L.minus_btn, "-", k_secondary, k_secondary_line, k_ink, n > 1);
  std::string count_label = std::to_string(n);
  draw_text(ren, L.minus_btn.x + L.minus_btn.w + 12.f, y + 2.f, count_label.c_str(), k_ink);
  draw_button(ren, L.plus_btn, "+", k_secondary, k_secondary_line, k_ink, n < 4);
  draw_text(ren, L.plus_btn.x + L.plus_btn.w + 12.f, y + 2.f, "keys [ ]", k_muted, 0.9f);

  y += 38.f;
  stroke_rect(ren, {L.card_x + pad, y, L.card_w - pad * 2.f, 1.f}, k_row_line);
  y += 12.f;

  int const es = std::clamp(menu.edit_seat, 0, n - 1);
  for (int i = 0; i < n; ++i) {
    SDL_FRect const row = L.seat_rows[static_cast<std::size_t>(i)];
    if (i == es) {
      fill_rect(ren, row, k_row_sel, 0.95f);
      stroke_rect(ren, row, rgb_u8(0x9a, 0xb8, 0xe8), 0.55f);
    }
    RGB pr = player_rgb(i);
    fill_rect(ren, {row.x + 6.f, row.y + 7.f, 14.f, 18.f}, pr);
    stroke_rect(ren, {row.x + 6.f, row.y + 7.f, 14.f, 18.f}, k_card_line, 0.7f);

    std::string pname = "Player " + std::to_string(i + 1);
    draw_text(ren, row.x + 28.f, row.y + 9.f, pname.c_str(), k_ink);

    bool const cpu = menu.seat_cpu[static_cast<std::size_t>(i)];
    std::string role = cpu ? "CPU" : "Human";
    draw_text(ren, row.x + 150.f, row.y + 9.f, role.c_str(), cpu ? k_muted : k_ink);

    std::string box = std::string("CPU [") + (cpu ? "x" : " ") + "]";
    draw_text(ren, row.x + row.w - 72.f, row.y + 9.f, box.c_str(), cpu ? k_ink : k_muted);
    y += 34.f;
  }

  y = L.seed_btn.y - 6.f;
  stroke_rect(ren, {L.card_x + pad, y, L.card_w - pad * 2.f, 1.f}, k_row_line);
  y += 10.f;

  std::string seed_line = "Seed: " + format_menu_seed(menu.world_seed) + "  (R random)";
  draw_text(ren, L.card_x + pad, L.seed_btn.y + 6.f, seed_line.c_str(), k_muted);
  std::string map_line = std::string("Map: ") + map_preset_label(menu.map_size) + "  (G cycle)";
  draw_text(ren, L.map_btn.x, L.map_btn.y + 6.f, map_line.c_str(), k_muted);

  bool const autosave_ready = gui_save_file_exists(k_gui_autosave_file);
  bool const named_ready = gui_save_file_exists(k_gui_save_file);

  draw_button(ren, L.continue_btn,
              autosave_ready ? "Continue last game  (C)" : "Continue last game  (no autosave yet)",
              k_secondary, k_secondary_line, k_ink, autosave_ready);
  draw_button(ren, L.load_btn,
              named_ready ? "Load saved game...  (L)" : "Load saved game...  (none yet)",
              k_secondary, k_secondary_line, k_ink, named_ready);
  draw_button(ren, L.start_btn, "Start game", k_primary, k_primary_dark, k_title, true, true);

  draw_text_centered(ren, cx, static_cast<float>(win_h) - 36.f,
                     "Esc quit   Enter start   All-CPU lineup: Enter twice within 10s", k_muted,
                     0.88f);

  draw_menu_toast(ren, gui, win_w, win_h);
}

void render_handoff_gate(SDL_Renderer* ren, GameSession const& session, int win_w, int win_h,
                         bool autosave_ok) {
  using namespace menu_ui;
  paint_vertical_background(ren, win_w, win_h);

  Player const* cur = nullptr;
  for (Player const& p : session.players()) {
    if (p.seat == session.current_player().seat) {
      cur = &p;
      break;
    }
  }

  HandoffLayout const L = compute_handoff_layout(win_w, win_h, !autosave_ok);
  draw_modal_card(ren, L.card);

  float const cx = L.card.x + L.card.w * 0.5f;
  std::string title = "Round " + std::to_string(session.round()) + " - pass to ";
  title += cur ? cur->name : ("Seat " + std::to_string(session.current_player().seat));
  if (session.current_player().computer) {
    title += " (computer)";
  }
  title = truncate_line(std::move(title), 52);
  draw_text_centered(ren, cx, L.card.y + 28.f, title.c_str(), k_title);

  float meta_y = L.card.y + 58.f;
  float const text_w = L.card.w - 48.f;
  for (std::string const& line : wrap_text_to_width(session.calendar_era_label(), text_w)) {
    draw_text_centered(ren, cx, meta_y, line.c_str(), k_meta);
    meta_y += 18.f;
  }
  meta_y += 4.f;

  std::string season_line =
      std::string(season_label(session.season())) + "  |  " + session.weather_hud_summary();
  for (std::string const& line : wrap_text_to_width(season_line, text_w)) {
    draw_text_centered(ren, cx, meta_y, line.c_str(), k_meta);
    meta_y += 18.f;
  }
  meta_y += 8.f;

  if (autosave_ok) {
    draw_text_centered(ren, cx, meta_y, "Autosave checkpoint OK", k_ok);
  } else {
    draw_text_centered(ren, cx, meta_y, "Autosave failed (session continues)", k_err);
  }
  meta_y += 28.f;

  if (cur != nullptr) {
    RGB pr = player_rgb(cur->seat);
    fill_rect(ren, {cx - 40.f, meta_y, 80.f, 18.f}, pr);
    stroke_rect(ren, {cx - 40.f, meta_y, 80.f, 18.f}, k_modal_line);
    meta_y += 34.f;
  }

  if (!session.is_over()) {
    int const seat = session.current_player().seat;
    std::string stats = std::to_string(session.city_count_for(seat)) + "/" +
                        std::to_string(GameSession::CITIES_TO_WIN) + " cities";
    stats += "   Gold " + std::to_string(session.gold_for(seat));
    stats += "   Units " + std::to_string(session.unit_count_for(seat));
    stats = truncate_line(std::move(stats), 58);
    draw_text_centered(ren, cx, meta_y, stats.c_str(), k_recap);
    meta_y += 24.f;
  } else {
    draw_text_centered(ren, cx, meta_y, "Game over - review the map when ready", k_accent);
    meta_y += 24.f;
  }

  draw_text_centered(ren, cx, L.begin_btn.y - 24.f,
                     "Press Enter when the seated player is ready (Space does nothing)", k_hint);

  draw_button(ren, L.begin_btn, "Begin turn", k_primary, k_primary_dark, k_title, true, true);
  if (!autosave_ok) {
    draw_button(ren, L.retry_btn, "Retry autosave (A)", k_secondary, k_secondary_line, k_ink, true);
  }
}

void begin_turn_from_handoff(GameSession& session, AppPhase* phase, View* view,
                             std::optional<int>* sel_unit, std::optional<int>* sel_city, int win_w,
                             int win_h, GuiState* gui) {
  gui->show_hotkeys = false;
  gui->show_game_menu = false;
  on_turn_began_sdl(session, gui, view, sel_unit, sel_city, win_w, win_h);
  if (!gui->tips_dismissed) {
    gui->show_intro_tips = true;
  }
  drain_cpu_turns(session);
  *phase = session.is_over() ? AppPhase::Victory : AppPhase::Playing;
}

void reset_to_setup_menu(std::unique_ptr<GameSession>* session_slot, AppPhase* phase,
                         std::optional<int>* sel_unit, std::optional<int>* sel_city,
                         std::optional<HexCoord>* hovered_hex, std::string* hover_line,
                         GuiState* gui, TurnTracker* tt, bool* handoff_autosave_ok) {
  session_slot->reset();
  *phase = AppPhase::SetupMenu;
  sel_unit->reset();
  sel_city->reset();
  hovered_hex->reset();
  hover_line->clear();
  *gui = GuiState{};
  load_gui_options(gui);
  *tt = TurnTracker{};
  if (handoff_autosave_ok != nullptr) {
    *handoff_autosave_ok = true;
  }
}

void launch_new_game_from_menu(StartMenuState const& menu, std::unique_ptr<GameSession>* session_slot,
                               View* view, AppPhase* phase, std::optional<int>* sel_unit,
                               std::optional<int>* sel_city, std::optional<HexCoord>* hovered_hex,
                               std::string* hover_line, GuiState* gui, TurnTracker* tt,
                               bool* handoff_autosave_ok, int win_w, int win_h) {
  std::vector<Player> players = build_players_from_menu(menu);
  *session_slot = std::make_unique<GameSession>(std::move(players), menu.world_seed, menu.map_size);
  recompute_world_bounds(**session_slot, view);
  fit_map_to_window(view, win_w, win_h);
  sel_unit->reset();
  sel_city->reset();
  hovered_hex->reset();
  hover_line->clear();
  *gui = GuiState{};
  load_gui_options(gui);
  *tt = TurnTracker{};
  if (handoff_autosave_ok != nullptr) {
    *handoff_autosave_ok = try_gui_autosave(**session_slot);
  }
  *phase = AppPhase::HandoffGate;
}

void render_victory_screen(SDL_Renderer* ren, GameSession const& session, int win_w, int win_h) {
  using namespace menu_ui;
  paint_vertical_background(ren, win_w, win_h);

  VictoryLayout const L = compute_victory_layout(win_w, win_h);
  draw_modal_card(ren, L.card);

  float const cx = L.card.x + L.card.w * 0.5f;
  draw_text_centered(ren, cx, L.card.y + 24.f, "Victory", k_victory_gold);

  std::string headline = "Game over";
  if (auto w = session.winner_seat()) {
    if (Player const* wp = player_by_seat(session, *w)) {
      headline = wp->name + " controls " + std::to_string(GameSession::CITIES_TO_WIN) + " cities!";
    } else {
      headline = "Seat " + std::to_string(*w) + " wins!";
    }
  }
  headline = truncate_line(std::move(headline), 52);
  draw_text_centered(ren, cx, L.card.y + 58.f, headline.c_str(), k_title);

  std::string recap = "Round " + std::to_string(session.round()) + "  |  ";
  recap += session.calendar_era_label();
  recap = truncate_line(std::move(recap), 58);
  draw_text_centered(ren, cx, L.card.y + 84.f, recap.c_str(), k_recap);

  if (auto w = session.winner_seat()) {
    RGB pr = player_rgb(*w);
    fill_rect(ren, {cx - 40.f, L.card.y + 108.f, 80.f, 18.f}, pr);
    stroke_rect(ren, {cx - 40.f, L.card.y + 108.f, 80.f, 18.f}, k_modal_line);
  }

  draw_text_centered(ren, cx, L.review_btn.y - 22.f, "Enter to review the final map", k_hint);

  draw_button(ren, L.review_btn, "Review map", rgb_u8(0x22, 0x38, 0x58), k_modal_line, k_title,
              true);
  draw_button(ren, L.menu_btn, "Main menu", k_secondary, k_secondary_line, k_ink, true);
  draw_button(ren, L.again_btn, "Play again", k_primary, k_primary_dark, k_title, true, true);

  draw_text_centered(ren, cx, L.card.y + L.card.h + 18.f,
                     "M or Esc - main menu   R - play again (same seats, new seed)", k_muted, 0.9f);
}

void handle_victory_event(SDL_Event const& e, bool* to_menu, bool* play_again, bool* review_map,
                          bool* running, float mx, float my, int win_w, int win_h) {
  menu_ui::VictoryLayout const L = menu_ui::compute_victory_layout(win_w, win_h);

  if (e.type == SDL_EVENT_MOUSE_BUTTON_DOWN && e.button.button == SDL_BUTTON_LEFT) {
    if (screen_point_in_rect(mx, my, L.review_btn)) {
      *review_map = true;
    } else if (screen_point_in_rect(mx, my, L.menu_btn)) {
      *to_menu = true;
    } else if (screen_point_in_rect(mx, my, L.again_btn)) {
      *play_again = true;
    }
    return;
  }
  if (e.type != SDL_EVENT_KEY_DOWN || e.key.repeat) {
    return;
  }
  SDL_Keymod const mods = SDL_GetModState();
  bool const ctrl = (mods & SDL_KMOD_CTRL) != 0;
  bool const gui_key = (mods & SDL_KMOD_GUI) != 0;
  if ((ctrl || gui_key) && e.key.key == SDLK_Q) {
    *running = false;
    return;
  }
  if (e.key.key == SDLK_ESCAPE || e.key.scancode == SDL_SCANCODE_M) {
    *to_menu = true;
    return;
  }
  if (e.key.scancode == SDL_SCANCODE_R) {
    *play_again = true;
    return;
  }
  if (e.key.key == SDLK_RETURN || e.key.key == SDLK_KP_ENTER) {
    *review_map = true;
  }
}

bool handle_setup_menu_key(StartMenuState* menu, GuiState* gui, SDL_Event const& e, bool* running,
                           bool* start_game, bool* continue_autosave, bool* load_named_save) {
  if (e.type != SDL_EVENT_KEY_DOWN || e.key.repeat) {
    return false;
  }
  SDL_Keycode const key = e.key.key;
  SDL_Keymod const mods = SDL_GetModState();
  bool const ctrl = (mods & SDL_KMOD_CTRL) != 0;
  bool const gui_key = (mods & SDL_KMOD_GUI) != 0;
  if ((ctrl || gui_key) && key == SDLK_Q) {
    *running = false;
    return true;
  }
  if (key == SDLK_ESCAPE) {
    *running = false;
    return true;
  }
  int const n = std::clamp(menu->player_count, 1, 4);
  if (key == SDLK_LEFTBRACKET) {
    menu->all_cpu_confirm_deadline = 0;
    menu->player_count = std::max(1, menu->player_count - 1);
    menu->edit_seat = std::clamp(menu->edit_seat, 0, std::max(0, menu->player_count - 1));
    return true;
  }
  if (key == SDLK_RIGHTBRACKET) {
    menu->all_cpu_confirm_deadline = 0;
    menu->player_count = std::min(4, menu->player_count + 1);
    menu->edit_seat = std::clamp(menu->edit_seat, 0, std::max(0, menu->player_count - 1));
    return true;
  }
  if (key == SDLK_UP) {
    menu->all_cpu_confirm_deadline = 0;
    if (menu->edit_seat > 0) {
      --menu->edit_seat;
    }
    return true;
  }
  if (key == SDLK_DOWN) {
    menu->all_cpu_confirm_deadline = 0;
    if (menu->edit_seat < n - 1) {
      ++menu->edit_seat;
    }
    return true;
  }
  if (key == SDLK_SPACE) {
    menu->all_cpu_confirm_deadline = 0;
    menu->edit_seat = std::clamp(menu->edit_seat, 0, n - 1);
    menu->seat_cpu[static_cast<std::size_t>(menu->edit_seat)] =
        !menu->seat_cpu[static_cast<std::size_t>(menu->edit_seat)];
    return true;
  }
  if (e.key.scancode == SDL_SCANCODE_R) {
    menu->all_cpu_confirm_deadline = 0;
    reroll_menu_world_seed(menu);
    return true;
  }
  if (e.key.scancode == SDL_SCANCODE_G) {
    menu->all_cpu_confirm_deadline = 0;
    cycle_menu_map_size(menu);
    return true;
  }
  if (e.key.scancode == SDL_SCANCODE_C && !ctrl && !gui_key) {
    if (gui_save_file_exists(k_gui_autosave_file)) {
      *continue_autosave = true;
    } else {
      set_toast(gui,
                "No autosave yet - finish a turn (handoff writes tack_strat_gui_autosave.json) or "
                "save in-game first.",
                6800);
    }
    return true;
  }
  if (e.key.scancode == SDL_SCANCODE_L && !ctrl && !gui_key) {
    if (gui_save_file_exists(k_gui_save_file)) {
      *load_named_save = true;
    } else {
      set_toast(gui,
                "No named save yet - press Ctrl/Cmd+S during a game to create "
                "tack_strat_gui_save.json.",
                6800);
    }
    return true;
  }
  if (key == SDLK_RETURN || key == SDLK_KP_ENTER) {
    if (menu_has_human_seat(*menu)) {
      menu->all_cpu_confirm_deadline = 0;
      *start_game = true;
    } else {
      Uint64 const now = SDL_GetTicks();
      if (menu->all_cpu_confirm_deadline != 0 && now < menu->all_cpu_confirm_deadline) {
        menu->all_cpu_confirm_deadline = 0;
        *start_game = true;
      } else {
        menu->all_cpu_confirm_deadline = now + 10'000;
        set_toast(gui, "Every seat is CPU. Press Enter again within 10s to start anyway.", 7200);
      }
    }
    return true;
  }
  return false;
}

bool handle_setup_menu_click(StartMenuState* menu, GuiState* gui, float x, float y, int win_w,
                             int win_h, bool* start_game, bool* continue_autosave,
                             bool* load_named_save) {
  int const n = std::clamp(menu->player_count, 1, 4);
  menu_ui::SetupMenuLayout const L = menu_ui::compute_setup_menu_layout(win_w, win_h, n);

  if (screen_point_in_rect(x, y, L.load_btn)) {
    if (gui_save_file_exists(k_gui_save_file)) {
      *load_named_save = true;
    } else {
      set_toast(gui,
                "No named save yet - press Ctrl/Cmd+S during a game to create "
                "tack_strat_gui_save.json.",
                6800);
    }
    return true;
  }
  if (screen_point_in_rect(x, y, L.continue_btn)) {
    if (gui_save_file_exists(k_gui_autosave_file)) {
      *continue_autosave = true;
    } else {
      set_toast(gui,
                "No autosave yet - finish a turn (handoff writes tack_strat_gui_autosave.json) or "
                "save in-game first.",
                6800);
    }
    return true;
  }
  if (screen_point_in_rect(x, y, L.minus_btn)) {
    menu->all_cpu_confirm_deadline = 0;
    menu->player_count = std::max(1, menu->player_count - 1);
    menu->edit_seat = std::clamp(menu->edit_seat, 0, std::max(0, menu->player_count - 1));
    return true;
  }
  if (screen_point_in_rect(x, y, L.plus_btn)) {
    menu->all_cpu_confirm_deadline = 0;
    menu->player_count = std::min(4, menu->player_count + 1);
    menu->edit_seat = std::clamp(menu->edit_seat, 0, std::max(0, menu->player_count - 1));
    return true;
  }
  for (int i = 0; i < n; ++i) {
    if (screen_point_in_rect(x, y, L.seat_rows[static_cast<std::size_t>(i)])) {
      menu->all_cpu_confirm_deadline = 0;
      menu->edit_seat = i;
      menu->seat_cpu[static_cast<std::size_t>(i)] = !menu->seat_cpu[static_cast<std::size_t>(i)];
      return true;
    }
  }
  if (screen_point_in_rect(x, y, L.start_btn)) {
    if (menu_has_human_seat(*menu)) {
      menu->all_cpu_confirm_deadline = 0;
      *start_game = true;
    } else {
      Uint64 const now = SDL_GetTicks();
      if (menu->all_cpu_confirm_deadline != 0 && now < menu->all_cpu_confirm_deadline) {
        menu->all_cpu_confirm_deadline = 0;
        *start_game = true;
      } else {
        menu->all_cpu_confirm_deadline = now + 10'000;
        set_toast(gui, "Every seat is CPU. Click Start again within 10s to start anyway.", 7200);
      }
    }
    return true;
  }
  return false;
}

void handle_handoff_event(GameSession& session, SDL_Event const& e, AppPhase* phase, View* view,
                          std::optional<int>* sel_unit, std::optional<int>* sel_city, int win_w,
                          int win_h, GuiState* gui, bool* running, bool* autosave_ok) {
  bool const show_retry = autosave_ok != nullptr && !*autosave_ok;
  menu_ui::HandoffLayout const L = menu_ui::compute_handoff_layout(win_w, win_h, show_retry);
  if (e.type == SDL_EVENT_MOUSE_BUTTON_DOWN && e.button.button == SDL_BUTTON_LEFT) {
    float const x = e.button.x;
    float const y = e.button.y;
    if (screen_point_in_rect(x, y, L.begin_btn)) {
      begin_turn_from_handoff(session, phase, view, sel_unit, sel_city, win_w, win_h, gui);
    } else if (show_retry && screen_point_in_rect(x, y, L.retry_btn)) {
      if (autosave_ok != nullptr) {
        *autosave_ok = try_gui_autosave(session);
      }
    }
    return;
  }
  if (e.type != SDL_EVENT_KEY_DOWN || e.key.repeat) {
    return;
  }
  SDL_Keymod const mods = SDL_GetModState();
  bool const ctrl = (mods & SDL_KMOD_CTRL) != 0;
  bool const gui_key = (mods & SDL_KMOD_GUI) != 0;
  if ((ctrl || gui_key) && e.key.key == SDLK_Q) {
    *running = false;
    return;
  }
  if (e.key.key == SDLK_A) {
    if (autosave_ok != nullptr) {
      *autosave_ok = try_gui_autosave(session);
    }
    return;
  }
  bool const enter = e.key.key == SDLK_RETURN || e.key.key == SDLK_KP_ENTER;
  if (enter) {
    begin_turn_from_handoff(session, phase, view, sel_unit, sel_city, win_w, win_h, gui);
  }
}

[[nodiscard]] bool gui_apply_json_save_file(
    char const* path, std::unique_ptr<GameSession>* session_slot, GuiState* gui, View* view,
    std::optional<int>* selected_unit_id, std::optional<int>* selected_city_id, TurnTracker* tt,
    std::optional<HexCoord>* hovered_hex, std::string* hover_line, int win_w, int win_h,
    AppPhase* out_phase, RestoreEntryMode entry_mode, char const* success_toast,
    Uint64 success_ms = 3400) {
  namespace fs = std::filesystem;
  fs::path const p(path);
  if (!fs::exists(p) || !fs::is_regular_file(p)) {
    bool const autosave = (std::strcmp(path, k_gui_autosave_file) == 0);
    if (autosave) {
      set_toast(gui,
                "No autosave yet - finish a turn (handoff writes tack_strat_gui_autosave.json) or "
                "save in-game first.",
                6800);
    } else {
      set_toast(gui,
                "No named save yet - press Ctrl/Cmd+S during a game to create "
                "tack_strat_gui_save.json.",
                6800);
    }
    return false;
  }

  auto loaded = tack::strat::snapshot_read_json_file(path);
  if (!loaded.has_value()) {
    set_toast(gui,
              std::string("Could not parse save JSON (file exists but is empty or invalid): ") + path,
              6200);
    return false;
  }
  tack::strat::SnapshotValidateResult const vr =
      tack::strat::validate_snapshot_for_engine(*loaded);
  if (!vr.ok) {
    set_toast(gui, std::string("Save invalid: ") + vr.message, 6200);
    return false;
  }
  try {
    *session_slot = std::make_unique<GameSession>(
        tack::strat::GameSession::restore(std::move(*loaded)));
    drain_cpu_turns(**session_slot);
    recompute_world_bounds(**session_slot, view);
    selected_city_id->reset();
    selected_unit_id->reset();
    gui->move_command_mode = false;
    gui->move_cursor.reset();
    gui->move_cursor_locked = false;
    gui->skipped_units.clear();
    gui->camera_seat = -1;
    gui->seat_cameras.clear();
    static_cast<void>(try_load_gui_camera_json(**session_slot, gui, view, win_w, win_h));
    *tt = TurnTracker{};
    hovered_hex->reset();
    hover_line->clear();

    AppPhase next = AppPhase::Playing;
    gui_set_phase_after_restore(**session_slot, &next, entry_mode);
    if (next == AppPhase::HandoffGate) {
      fit_map_to_window(view, win_w, win_h);
    } else {
      bootstrap_player_view(**session_slot, gui, view, selected_unit_id, selected_city_id, win_w,
                            win_h, true);
    }
    if (out_phase != nullptr) {
      *out_phase = next;
    }
    if (success_toast != nullptr) {
      if (next == AppPhase::Victory) {
        set_toast(gui, "Game loaded — victory screen (finished game).", success_ms);
      } else {
        set_toast(gui, std::string(success_toast), success_ms);
      }
    }
    return true;
  } catch (std::exception const& ex) {
    set_toast(gui, std::string("Restore failed: ") + ex.what(), 6200);
    return false;
  }
}

[[nodiscard]] std::optional<std::string> parse_play_style_args(int argc, char** argv,
                                                               std::int64_t* seed, int* human_players,
                                                               int* computer_players,
                                                               tack::strat::MapSizePreset* map_size) {
  *seed = 77'007;
  *human_players = 1;
  *computer_players = 0;
  *map_size = tack::strat::MapSizePreset::Tiny;
  try {
    if (argc >= 2) {
      *seed = std::stoll(argv[1]);
    }
    if (argc >= 3) {
      *human_players = std::stoi(argv[2]);
    }
    if (argc >= 4) {
      *computer_players = std::stoi(argv[3]);
    }
    if (argc >= 5) {
      auto parsed = tack::strat::try_parse_map_size_preset(argv[4]);
      if (!parsed.has_value()) {
        return std::string("Invalid map size \"") + argv[4] + "\" (use tiny, small, medium, large).";
      }
      *map_size = *parsed;
    }
  } catch (std::exception const&) {
    return std::string(
        "Invalid numeric argument(s).\n"
        "Usage: tack_strat_gui [world_seed] [human_players] [computer_players] "
        "[tiny|small|medium|large]\n");
  }
  int const total = *human_players + *computer_players;
  if (*human_players < 0 || *computer_players < 0 || *human_players > 4 || *computer_players > 4 ||
      total < 1 || total > 4) {
    return std::string("Need 1–4 players total; human_players and computer_players each 0–4.");
  }
  return std::nullopt;
}

}  // namespace

int main(int argc, char** argv) {
  bool const menu_launch = (argc <= 1);

  std::unique_ptr<GameSession> session;
  std::int64_t cli_seed = 77'007;
  int cli_humans = 1;
  int cli_computers = 0;
  tack::strat::MapSizePreset cli_map = tack::strat::MapSizePreset::Tiny;

  if (!menu_launch) {
    if (auto err =
            parse_play_style_args(argc, argv, &cli_seed, &cli_humans, &cli_computers, &cli_map)) {
      std::cerr << *err << '\n';
      return 2;
    }
    std::vector<Player> players;
    int const total = cli_humans + cli_computers;
    players.reserve(static_cast<std::size_t>(total));
    for (int i = 0; i < total; ++i) {
      bool const computer = i >= cli_humans;
      players.emplace_back(i, "P" + std::to_string(i), computer);
    }
    session = std::make_unique<GameSession>(std::move(players), cli_seed, cli_map);
  }

  if (!SDL_Init(SDL_INIT_VIDEO)) {
    std::cerr << "SDL_Init failed: " << SDL_GetError() << '\n';
    return 1;
  }

  SDL_SetHint(SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "1");

  SDL_Window* window = nullptr;
  SDL_Renderer* renderer = nullptr;
  if (!SDL_CreateWindowAndRenderer("tack_strat - New game (SDL3)", 1280, 720, SDL_WINDOW_RESIZABLE,
                                   &window, &renderer)) {
    std::cerr << "SDL_CreateWindowAndRenderer failed: " << SDL_GetError() << '\n';
    SDL_Quit();
    return 1;
  }
  SDL_RaiseWindow(window);
  // Enable alpha blending so translucent overlays (fog, weather washes, time-of-day
  // tint, toasts, panels) composite instead of painting opaque.
  SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
  init_ui_font();

  AppPhase phase = menu_launch ? AppPhase::SetupMenu : AppPhase::Playing;
  StartMenuState start_menu{};
  if (menu_launch) {
    reroll_menu_world_seed(&start_menu);
  }

  bool handoff_autosave_ok = true;

  SdlSessionFlow session_flow{};
  session_flow.handoff_enabled = menu_launch;
  session_flow.phase = menu_launch ? &phase : nullptr;
  session_flow.handoff_autosave_ok = menu_launch ? &handoff_autosave_ok : nullptr;

  View view{};
  int win_w = 1280;
  int win_h = 720;
  SDL_GetWindowSize(window, &win_w, &win_h);
  if (session) {
    recompute_world_bounds(*session, &view);
  }

  std::optional<int> selected_unit_id;
  std::optional<int> selected_city_id;
  std::optional<HexCoord> hovered_hex;
  std::string hover_line;
  GuiState gui{};
  load_gui_options(&gui);
  TurnTracker turn_tracker{};

  bool dragging_pan = false;
  float drag_last_x = 0.f;
  float drag_last_y = 0.f;
  float last_mouse_x = static_cast<float>(win_w) * 0.5f;
  float last_mouse_y = static_cast<float>(win_h) * 0.5f;

  if (session && !menu_launch) {
    bootstrap_player_view(*session, &gui, &view, &selected_unit_id, &selected_city_id, win_w, win_h,
                          true);
    drain_cpu_turns(*session);
    update_map_hover_from_screen(*session, view, last_mouse_x, last_mouse_y,
                                 session->current_player().seat, selected_unit_id, &gui,
                                 &hovered_hex, &hover_line);
  }

  bool running = true;
  while (running) {
    if (phase == AppPhase::Playing && session) {
      sync_skipped_for_new_turn(*session, &turn_tracker, &gui.skipped_units);
    }

    bool start_new_game = false;
    bool continue_autosave = false;
    bool load_named_save = false;
    bool victory_to_menu = false;
    bool victory_play_again = false;
    bool victory_review_map = false;
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
      SDL_ConvertEventToRenderCoordinates(renderer, &e);

      if (e.type == SDL_EVENT_QUIT) {
        running = false;
      }
      if (e.type == SDL_EVENT_WINDOW_RESIZED) {
        win_w = e.window.data1;
        win_h = e.window.data2;
        clamp_view(&view, win_w, win_h);
      }

      if (phase == AppPhase::SetupMenu) {
        if (e.type == SDL_EVENT_KEY_DOWN) {
          static_cast<void>(handle_setup_menu_key(&start_menu, &gui, e, &running, &start_new_game,
                                                  &continue_autosave, &load_named_save));
        }
        if (e.type == SDL_EVENT_MOUSE_BUTTON_DOWN && e.button.button == SDL_BUTTON_LEFT) {
          static_cast<void>(handle_setup_menu_click(&start_menu, &gui, e.button.x, e.button.y, win_w,
                                                    win_h, &start_new_game, &continue_autosave,
                                                    &load_named_save));
        }
        continue;
      }

      if (phase == AppPhase::HandoffGate && session) {
        handle_handoff_event(*session, e, &phase, &view, &selected_unit_id, &selected_city_id,
                             win_w, win_h, &gui, &running, &handoff_autosave_ok);
        continue;
      }

      if (phase == AppPhase::Victory && session) {
        float mx = 0.f;
        float my = 0.f;
        if (e.type == SDL_EVENT_MOUSE_BUTTON_DOWN) {
          mx = e.button.x;
          my = e.button.y;
        }
        handle_victory_event(e, &victory_to_menu, &victory_play_again, &victory_review_map, &running,
                             mx, my, win_w, win_h);
        continue;
      }

      if (menu_launch && phase == AppPhase::Playing && session && session->is_over()) {
        if (e.type == SDL_EVENT_KEY_DOWN && !e.key.repeat) {
          if (e.key.key == SDLK_ESCAPE || e.key.scancode == SDL_SCANCODE_M) {
            phase = AppPhase::Victory;
            continue;
          }
          if (e.key.scancode == SDL_SCANCODE_R) {
            victory_play_again = true;
            continue;
          }
        }
      }

      if (!session) {
        continue;
      }

      GameSession& g = *session;
      if (e.type == SDL_EVENT_KEY_DOWN) {
        static_cast<void>(handle_java_key_down(e, g, &gui, &view, &selected_unit_id,
                                                &selected_city_id, hovered_hex, win_w, win_h,
                                                &running, window, &session_flow));
      }
      if (e.type == SDL_EVENT_MOUSE_BUTTON_DOWN) {
        if (e.button.button == SDL_BUTTON_RIGHT) {
          bool const had_selection =
              selected_unit_id.has_value() || selected_city_id.has_value() ||
              gui.move_command_mode;
          selected_unit_id.reset();
          selected_city_id.reset();
          gui.move_command_mode = false;
          gui.move_cursor.reset();
          gui.move_cursor_locked = false;
          if (had_selection) {
            set_toast(&gui, "Selection cleared.", 1600);
          }
        } else if (e.button.button == SDL_BUTTON_MIDDLE ||
                   (e.button.button == SDL_BUTTON_LEFT &&
                    (SDL_GetModState() & SDL_KMOD_SHIFT))) {
          dragging_pan = true;
          drag_last_x = e.button.x;
          drag_last_y = e.button.y;
        } else if (e.button.button == SDL_BUTTON_LEFT) {
          bool const show_prod = production_drawer_visible(g, selected_city_id);
          ProductionDrawerLayout const prod_layout =
              compute_production_drawer_layout(win_w, win_h, show_prod);
          bool const shift_click = (SDL_GetModState() & SDL_KMOD_SHIFT) != 0;
          float const right_ui = play_screen_right_ui_margin(g, selected_city_id);
          float const max_panel_bottom = play_ui_max_panel_bottom(win_h, &gui);
          UnitInspectorLayout const unit_layout = compute_unit_inspector_layout(
              g, selected_unit_id, g.current_player().seat, win_h, max_panel_bottom);
          CityInspectorLayout const city_layout = compute_city_inspector_layout(
              g, selected_city_id, win_w, win_h, show_prod, max_panel_bottom);
          ProductionNeedsLayout needs_layout = compute_production_needs_layout(
              g, selected_unit_id, selected_city_id, win_h, max_panel_bottom);
          stack_production_needs_below_unit(unit_layout, &needs_layout);
          clamp_panel_max_bottom(max_panel_bottom, &needs_layout.panel);
          trim_production_needs_buttons(&needs_layout);
          EventLogLayout const event_log_layout = compute_event_log_layout(gui, win_w, win_h);
          GameMenuLayout const game_menu_layout = compute_game_menu_layout(win_w);
          BottomActionBarLayout const action_bar = compute_bottom_action_bar_layout(
              g, gui, selected_unit_id, selected_city_id, win_w, win_h);
          float const mx = e.button.x;
          float const my = e.button.y;
          if (gui.show_intro_tips) {
            dismiss_intro_tips(&gui);
          } else if (try_handle_game_menu_click(g, &gui, &view, &selected_unit_id,
                                                &selected_city_id, game_menu_layout, mx, my, win_w,
                                                win_h, &session_flow)) {
            // game menu consumed the click
          } else if (try_handle_event_log_click(&gui, event_log_layout, mx, my)) {
            // event log toggled
          } else if (show_prod && selected_city_id.has_value() &&
              try_handle_production_drawer_click(g, *selected_city_id, prod_layout, shift_click,
                                                 &gui, &view, &selected_unit_id,
                                                 &selected_city_id, win_w, win_h, mx, my)) {
            // production drawer consumed the click
          } else if (try_handle_city_inspector_click(g, &gui, city_layout, mx, my)) {
            // city inspector consumed the click
          } else if (try_handle_unit_inspector_click(g, &gui, &view, &selected_unit_id,
                                                     &selected_city_id, unit_layout, mx, my, win_w,
                                                     win_h, &session_flow)) {
            // unit inspector consumed the click
          } else if (try_handle_production_needs_click(g, &gui, &view, &selected_unit_id,
                                                       &selected_city_id, needs_layout, mx, my,
                                                       win_w, win_h)) {
            // production needs panel consumed the click
          } else if (try_handle_bottom_action_bar_click(g, &gui, &view, &selected_unit_id,
                                                        &selected_city_id, action_bar, mx, my,
                                                        hovered_hex, win_w, win_h, &session_flow)) {
            // bottom action bar consumed the click
          } else if (!handle_minimap_click(g, &view, mx, my, win_w, win_h, right_ui)) {
            handle_left_click(g, &view, &gui, selected_unit_id, selected_city_id, mx, my, win_w,
                              win_h, shift_click);
          }
        }
      }
      if (e.type == SDL_EVENT_MOUSE_BUTTON_UP) {
        if (e.button.button == SDL_BUTTON_MIDDLE || e.button.button == SDL_BUTTON_LEFT) {
          dragging_pan = false;
        }
      }
      if (e.type == SDL_EVENT_MOUSE_MOTION) {
        last_mouse_x = e.motion.x;
        last_mouse_y = e.motion.y;
        if (dragging_pan) {
          float dx = e.motion.x - drag_last_x;
          float dy = e.motion.y - drag_last_y;
          view.offset_x += dx;
          view.offset_y += dy;
          drag_last_x = e.motion.x;
          drag_last_y = e.motion.y;
          clamp_view(&view, win_w, win_h);
          touch_camera_for_current_seat(g, &gui, view);
        }
        update_map_hover_from_screen(g, view, e.motion.x, e.motion.y, g.current_player().seat,
                                     selected_unit_id, &gui, &hovered_hex, &hover_line);
      }
      if (e.type == SDL_EVENT_MOUSE_WHEEL) {
        float wx = e.wheel.x;
        float wy = e.wheel.y;
        if (wx == 0.f && wy == 0.f) {
          continue;
        }
        if (wy != 0.f) {
          EventLogLayout const event_log_layout = compute_event_log_layout(gui, win_w, win_h);
          if (try_handle_event_log_wheel(&gui, event_log_layout, e.wheel.mouse_x, e.wheel.mouse_y,
                                         wy)) {
            continue;
          }
        }
        bool const zoom_gesture =
            (SDL_GetModState() & (SDL_KMOD_GUI | SDL_KMOD_CTRL | SDL_KMOD_ALT)) != 0;
        constexpr float k_trackpad_pan = 38.f;
        if (zoom_gesture) {
          float const zoom_axis = wy != 0.f ? wy : wx;
          double const factor =
              std::pow(static_cast<double>(ZOOM_STEP), static_cast<double>(-zoom_axis));
          zoom_at(&view, static_cast<float>(factor), e.wheel.mouse_x, e.wheel.mouse_y, win_w, win_h);
        } else {
          if (wx != 0.f) {
            view.offset_x -= wx * k_trackpad_pan;
            clamp_view(&view, win_w, win_h);
          }
          if (wy != 0.f) {
            auto const near_integer_scroll = [](float v) {
              return std::abs(v - std::round(v)) < 0.05f;
            };
            bool const wheel_like_vertical =
                wx == 0.f && near_integer_scroll(wy) && std::abs(wy) >= 0.99f;
            double const exp =
                wheel_like_vertical ? -static_cast<double>(wy) : static_cast<double>(wy);
            double const factor = std::pow(static_cast<double>(ZOOM_STEP), exp);
            zoom_at(&view, static_cast<float>(factor), e.wheel.mouse_x, e.wheel.mouse_y, win_w, win_h);
          }
        }
      }
    }

    if (phase == AppPhase::SetupMenu && continue_autosave) {
      static_cast<void>(gui_apply_json_save_file(
          k_gui_autosave_file, &session, &gui, &view, &selected_unit_id, &selected_city_id,
          &turn_tracker, &hovered_hex, &hover_line, win_w, win_h, &phase, RestoreEntryMode::Menu,
          "Continued autosave — pass the device to the seated player."));
    }
    if (phase == AppPhase::SetupMenu && load_named_save) {
      static_cast<void>(gui_apply_json_save_file(
          k_gui_save_file, &session, &gui, &view, &selected_unit_id, &selected_city_id,
          &turn_tracker, &hovered_hex, &hover_line, win_w, win_h, &phase, RestoreEntryMode::Menu,
          "Loaded named save — pass the device to the seated player."));
    }

    if (phase == AppPhase::SetupMenu && start_new_game) {
      launch_new_game_from_menu(start_menu, &session, &view, &phase, &selected_unit_id,
                                &selected_city_id, &hovered_hex, &hover_line, &gui, &turn_tracker,
                                &handoff_autosave_ok, win_w, win_h);
    }

    if (phase == AppPhase::Victory && victory_to_menu) {
      reset_to_setup_menu(&session, &phase, &selected_unit_id, &selected_city_id, &hovered_hex,
                          &hover_line, &gui, &turn_tracker, &handoff_autosave_ok);
    }
    if (gui.request_main_menu) {
      gui.request_main_menu = false;
      if (session) {
        save_gui_camera_json(*session, &gui, view);
      }
      reset_to_setup_menu(&session, &phase, &selected_unit_id, &selected_city_id, &hovered_hex,
                          &hover_line, &gui, &turn_tracker, &handoff_autosave_ok);
    }
    if (phase == AppPhase::Victory && victory_play_again && session) {
      reroll_menu_world_seed(&start_menu);
      launch_new_game_from_menu(start_menu, &session, &view, &phase, &selected_unit_id,
                                &selected_city_id, &hovered_hex, &hover_line, &gui, &turn_tracker,
                                &handoff_autosave_ok, win_w, win_h);
    } else if (phase == AppPhase::Playing && session && session->is_over() && victory_play_again) {
      reroll_menu_world_seed(&start_menu);
      launch_new_game_from_menu(start_menu, &session, &view, &phase, &selected_unit_id,
                                &selected_city_id, &hovered_hex, &hover_line, &gui, &turn_tracker,
                                &handoff_autosave_ok, win_w, win_h);
    }
    if (phase == AppPhase::Victory && victory_review_map && session) {
      phase = AppPhase::Playing;
      gui.show_hotkeys = false;
    }

    if (session && phase == AppPhase::Playing) {
      if (gui.request_save) {
        gui.request_save = false;
        if (tack::strat::snapshot_write_json_file(session->capture(), k_gui_save_file)) {
          set_toast(&gui, std::string("Saved ") + k_gui_save_file, 3400);
        } else {
          set_toast(&gui, "Save failed (could not write file).", 4200);
        }
      }
      if (gui.request_load) {
        gui.request_load = false;
        if (gui_apply_json_save_file(k_gui_save_file, &session, &gui, &view, &selected_unit_id,
                                     &selected_city_id, &turn_tracker, &hovered_hex, &hover_line,
                                     win_w, win_h, nullptr, RestoreEntryMode::InGame, nullptr)) {
          set_toast(&gui, std::string("Loaded ") + k_gui_save_file, 3400);
        }
      }
    }

    if (!session) {
      SDL_SetWindowTitle(window, "tack_strat — New game");
    } else {
      std::string title = "tack_strat — Round " + std::to_string(session->round());
      title += " — ";
      title += season_label(session->season());
      std::string const era_snip = session->calendar_era_label();
      if (!era_snip.empty()) {
        title += " — ";
        title += era_snip.size() > 32 ? era_snip.substr(0, 29) + "..." : era_snip;
      }
      if (session->is_over()) {
        title += " — Game over";
      }
      if (phase == AppPhase::HandoffGate) {
        title = "tack_strat — Pass device (handoff)";
      } else if (phase == AppPhase::Victory) {
        title = "tack_strat — Victory";
        if (auto w = session->winner_seat()) {
          if (Player const* wp = player_by_seat(*session, *w)) {
            title += " — ";
            title += wp->name;
          }
        }
      }
      SDL_SetWindowTitle(window, title.c_str());
    }

    if (session && phase == AppPhase::Playing) {
      ensure_selection_for_current_seat(*session, &selected_unit_id, &selected_city_id);
      if (gui.move_command_mode) {
        bool stale = !selected_unit_id.has_value();
        if (!stale) {
          if (auto u = session->unit_by_id(*selected_unit_id);
              !u.has_value() || u->owner_seat() != session->current_player().seat) {
            stale = true;
          }
        }
        if (stale) {
          clear_move_command_mode(&gui);
        }
      }
      update_map_hover_from_screen(*session, view, last_mouse_x, last_mouse_y,
                                   session->current_player().seat, selected_unit_id, &gui,
                                   &hovered_hex, &hover_line);
      if (gui.move_command_mode && !session->is_over() && !session->current_player().computer) {
        static Uint64 last_edge_pan_ms = 0;
        Uint64 const pan_now = SDL_GetTicks();
        if (pan_now - last_edge_pan_ms >= 32) {
          maybe_edge_pan_view(&view, last_mouse_x, last_mouse_y, win_w, win_h);
          last_edge_pan_ms = pan_now;
        }
      }
      enforce_view_discovered_constraints(&view, *session, session->current_player().seat, win_w,
                                        win_h);
      ensure_view_shows_discovered(&view, *session, session->current_player().seat, win_w, win_h);
      touch_camera_for_current_seat(*session, &gui, view);
      Uint64 const tick = SDL_GetTicks();
      static Uint64 last_cam_write = 0;
      if (tick - last_cam_write > 2200) {
        last_cam_write = tick;
        save_gui_camera_json(*session, &gui, view);
      }
    }

    SDL_SetRenderDrawColorFloat(renderer, 0.f, 0.f, 0.f, 1.f);
    SDL_RenderClear(renderer);
    if (phase == AppPhase::SetupMenu) {
      render_start_menu(renderer, start_menu, gui, win_w, win_h);
    } else if (phase == AppPhase::HandoffGate && session) {
      render_handoff_gate(renderer, *session, win_w, win_h, handoff_autosave_ok);
    } else if (phase == AppPhase::Victory && session) {
      render_victory_screen(renderer, *session, win_w, win_h);
    } else if (session) {
      render_frame(renderer, *session, view, &gui, selected_unit_id, selected_city_id, hovered_hex,
                   hover_line, win_w, win_h, last_mouse_x, last_mouse_y);
    }
    SDL_RenderPresent(renderer);
  }

  if (session) {
    save_gui_camera_json(*session, &gui, view);
  }
  shutdown_ui_font();
  SDL_DestroyRenderer(renderer);
  SDL_DestroyWindow(window);
  SDL_Quit();
  return 0;
}

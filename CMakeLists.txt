cmake_minimum_required(VERSION 3.24)

project(tack_strat
  VERSION 0.1.0
  LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(TACK_STRAT_BUILD_TESTS "Build unit tests" ON)
option(TACK_STRAT_BUILD_BENCH "Build tack_strat_bench micro-benchmark binary" OFF)
option(TACK_STRAT_BUILD_SDL_GUI "Build SDL3 Civ I-style 2D viewer (tack_strat_gui)" ON)

include(CTest)

include(FetchContent)
FetchContent_Declare(
  nlohmann_json
  GIT_REPOSITORY https://github.com/nlohmann/json.git
  GIT_TAG v3.11.3
)
FetchContent_MakeAvailable(nlohmann_json)

add_library(tack_strat_core STATIC
  src/engine/java_random.cpp
  src/engine/hex.cpp
  src/engine/unit.cpp
  src/engine/game_map.cpp
  src/engine/wild_animal.cpp
  src/engine/city.cpp
  src/engine/city_name_pool.cpp
  src/engine/combat_rng.cpp
  src/engine/regional_weather.cpp
  src/engine/map_size.cpp
  src/engine/world_generator.cpp
  src/engine/game_session.cpp
  src/engine/game_session_rules.cpp
  src/engine/game_session_snapshot.cpp
  src/engine/seat_ai.cpp
  src/engine/snapshot_validate.cpp
  src/engine/snapshot_digest.cpp
  src/engine/snapshot_json_io.cpp
  src/engine/text_play.cpp
  src/engine/net/turn_sync.cpp
)
target_include_directories(tack_strat_core
  PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(tack_strat_core PRIVATE nlohmann_json::nlohmann_json)

add_executable(tack_strat
  src/main.cpp
)
target_link_libraries(tack_strat PRIVATE tack_strat_core)

if(TACK_STRAT_BUILD_SDL_GUI)
  include(FetchContent)
  set(SDL_TESTS OFF CACHE BOOL "" FORCE)
  set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(SDL_SHARED OFF CACHE BOOL "" FORCE)
  set(SDL_STATIC ON CACHE BOOL "" FORCE)

  # Neutralize any stale SDL3_DIR override from earlier configure attempts so OVERRIDE_FIND_PACKAGE
  # below is what redirects find_package(SDL3) to the in-tree build.
  unset(SDL3_DIR CACHE)

  # OVERRIDE_FIND_PACKAGE makes SDL3_ttf's find_package(SDL3) resolve to this in-tree build
  # instead of failing on the static-only generated config / sibling-scope target visibility.
  FetchContent_Declare(
    SDL3
    GIT_REPOSITORY https://github.com/libsdl-org/SDL.git
    GIT_TAG release-3.2.12
    EXCLUDE_FROM_ALL
    OVERRIDE_FIND_PACKAGE
  )
  FetchContent_MakeAvailable(SDL3)

  # SDL3_ttf for real TrueType text rendering. Vendored FreeType (no HarfBuzz/PlutoSVG
  # to keep the build light); static link. The release tarball bundles the vendored deps.
  set(SDLTTF_VENDORED ON CACHE BOOL "" FORCE)
  set(SDLTTF_HARFBUZZ OFF CACHE BOOL "" FORCE)
  set(SDLTTF_PLUTOSVG OFF CACHE BOOL "" FORCE)
  set(SDLTTF_SAMPLES OFF CACHE BOOL "" FORCE)
  set(SDLTTF_INSTALL OFF CACHE BOOL "" FORCE)
  set(SDLTTF_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  # SDL3_ttf derives its shared/static choice from BUILD_SHARED_LIBS; force static so it links
  # against our static SDL3 build.
  set(BUILD_SHARED_LIBS OFF)
  FetchContent_Declare(
    SDL3_ttf
    GIT_REPOSITORY https://github.com/libsdl-org/SDL_ttf.git
    GIT_TAG release-3.2.2
    GIT_SUBMODULES "external/freetype"
    GIT_SHALLOW TRUE
    EXCLUDE_FROM_ALL
  )
  FetchContent_MakeAvailable(SDL3_ttf)

  add_executable(tack_strat_gui
    src/gui/sdl_civ1_main.cpp
  )
  target_link_libraries(tack_strat_gui PRIVATE tack_strat_core SDL3::SDL3-static
                        SDL3_ttf::SDL3_ttf nlohmann_json::nlohmann_json)
endif()

if(TACK_STRAT_BUILD_BENCH)
  add_executable(tack_strat_bench
    benchmarks/tack_strat_bench.cpp
  )
  target_link_libraries(tack_strat_bench PRIVATE tack_strat_core)
endif()

# Smoke test: ensures the game executable was linked and runs (exit 0 + expected banner).
add_test(NAME tack_strat_build_ok COMMAND tack_strat)
set_tests_properties(tack_strat_build_ok PROPERTIES
  PASS_REGULAR_EXPRESSION "rules engine"
)

if(TACK_STRAT_BUILD_TESTS)
  FetchContent_Declare(
    doctest
    GIT_REPOSITORY https://github.com/doctest/doctest.git
    GIT_TAG v2.4.11
    EXCLUDE_FROM_ALL
  )

  cmake_policy(PUSH)
  if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
  endif()
  FetchContent_Populate(doctest)
  cmake_policy(POP)

  add_executable(tack_strat_tests
    tests/test_game.cpp
    tests/test_java_random.cpp
    tests/test_game_session.cpp
    tests/test_rules_scenarios.cpp
    tests/test_seat_ai.cpp
    tests/test_game_long_run.cpp
    tests/test_text_play.cpp
    tests/test_preparation.cpp
  )
  target_link_libraries(tack_strat_tests PRIVATE tack_strat_core)
  target_include_directories(tack_strat_tests PRIVATE "${doctest_SOURCE_DIR}")

  include("${doctest_SOURCE_DIR}/scripts/cmake/doctest.cmake")
  doctest_discover_tests(tack_strat_tests)
endif()

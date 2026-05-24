package com.tackstrat.ui;

import javax.swing.KeyStroke;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * User-remappable keybindings with Civ-like defaults.
 *
 * Override file (optional): ~/.tackstrat/keybinds.properties
 * Format: action=KEYSTROKE[,KEYSTROKE...]
 * Example: found_city=B,C
 */
final class Keybinds {

    static final String END_TURN = "end_turn";
    static final String SKIP_ACTION = "skip_action";
    static final String DESELECT = "deselect";
    static final String FOUND_CITY = "found_city";
    static final String NEXT_UNIT = "next_unit";
    static final String FORTIFY = "fortify";
    static final String MOVE_MODE = "move_mode";
    static final String FIT_VIEW = "fit_view";
    static final String ZOOM_IN = "zoom_in";
    static final String ZOOM_OUT = "zoom_out";
    static final String PAN_LEFT = "pan_left";
    static final String PAN_RIGHT = "pan_right";
    static final String PAN_UP = "pan_up";
    static final String PAN_DOWN = "pan_down";
    static final String SAVE_GAME = "save_game";
    static final String TOGGLE_WEATHER_LEGEND = "toggle_weather_legend";
    static final String TOGGLE_SETTLER_LENS = "toggle_settler_lens";
    static final String SHOW_HOTKEYS = "show_hotkeys";
    static final String TOGGLE_AUTO_EXPLORE = "toggle_auto_explore";

    private static final Path USER_FILE = Paths.get(System.getProperty("user.home"), ".tackstrat", "keybinds.properties");

    private final Map<String, List<String>> keysByAction;

    private Keybinds(Map<String, List<String>> keysByAction) {
        this.keysByAction = keysByAction;
    }

    static Keybinds load() {
        var out = defaults();
        if (!Files.isRegularFile(USER_FILE)) {
            return new Keybinds(out);
        }
        var p = new Properties();
        try (InputStream in = Files.newInputStream(USER_FILE)) {
            p.load(in);
        } catch (IOException ignored) {
            return new Keybinds(out);
        }
        for (String action : out.keySet()) {
            String raw = p.getProperty(action);
            if (raw == null || raw.isBlank()) continue;
            var parsed = parseList(raw);
            if (!parsed.isEmpty()) {
                out.put(action, parsed);
            }
        }
        return new Keybinds(out);
    }

    static Path userFilePath() {
        return USER_FILE;
    }

    static List<String> actionOrder() {
        return List.copyOf(defaults().keySet());
    }

    static String labelFor(String action) {
        return switch (action) {
            case END_TURN -> "End Turn";
            case SKIP_ACTION -> "Skip Current Action";
            case DESELECT -> "Deselect";
            case FOUND_CITY -> "Found City";
            case NEXT_UNIT -> "Next Unit";
            case FORTIFY -> "Fortify / Skip";
            case MOVE_MODE -> "Move Mode";
            case FIT_VIEW -> "Fit View";
            case ZOOM_IN -> "Zoom In";
            case ZOOM_OUT -> "Zoom Out";
            case PAN_LEFT -> "Pan Left";
            case PAN_RIGHT -> "Pan Right";
            case PAN_UP -> "Pan Up";
            case PAN_DOWN -> "Pan Down";
            case SAVE_GAME -> "Save Game";
            case TOGGLE_WEATHER_LEGEND -> "Toggle Weather Key";
            case TOGGLE_SETTLER_LENS -> "Toggle Settler Lens";
            case SHOW_HOTKEYS -> "Hotkeys Help";
            case TOGGLE_AUTO_EXPLORE -> "Toggle Auto-Explore";
            default -> action;
        };
    }

    List<KeyStroke> keyStrokesFor(String action) {
        var out = new ArrayList<KeyStroke>();
        for (var spec : keysByAction.getOrDefault(action, List.of())) {
            KeyStroke ks = parseKeyStroke(spec);
            if (ks != null) out.add(ks);
        }
        return out;
    }

    List<String> keySpecsFor(String action) {
        return List.copyOf(keysByAction.getOrDefault(action, List.of()));
    }

    static void saveBindings(Map<String, String> csvByAction) throws IOException {
        Files.createDirectories(USER_FILE.getParent());
        var p = new Properties();
        for (String action : actionOrder()) {
            String v = csvByAction.getOrDefault(action, "");
            if (!v.isBlank()) {
                p.setProperty(action, v.trim());
            }
        }
        try (OutputStream out = Files.newOutputStream(USER_FILE)) {
            p.store(out, "Tack & Strat keybindings");
        }
    }

    private static KeyStroke parseKeyStroke(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String s = spec.trim();
        if (s.length() == 1) {
            return KeyStroke.getKeyStroke(s.toUpperCase().charAt(0), 0);
        }
        if (s.matches("[A-Za-z]")) {
            return KeyStroke.getKeyStroke(s.toUpperCase().charAt(0), 0);
        }
        return KeyStroke.getKeyStroke(s);
    }

    private static List<String> parseList(String csv) {
        var out = new ArrayList<String>();
        for (var part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Map<String, List<String>> defaults() {
        var d = new LinkedHashMap<String, List<String>>();
        d.put(END_TURN, List.of("ENTER"));
        d.put(SKIP_ACTION, List.of("SPACE"));
        d.put(DESELECT, List.of("ESCAPE"));
        d.put(FOUND_CITY, List.of("B"));
        d.put(NEXT_UNIT, List.of("PERIOD", "N"));
        d.put(FORTIFY, List.of("F"));
        d.put(MOVE_MODE, List.of("M"));
        d.put(FIT_VIEW, List.of("shift F"));
        d.put(ZOOM_IN, List.of("EQUALS", "PLUS"));
        d.put(ZOOM_OUT, List.of("MINUS"));
        d.put(PAN_LEFT, List.of("LEFT"));
        d.put(PAN_RIGHT, List.of("RIGHT"));
        d.put(PAN_UP, List.of("UP"));
        d.put(PAN_DOWN, List.of("DOWN"));
        d.put(SAVE_GAME, List.of("meta S", "ctrl S"));
        d.put(TOGGLE_WEATHER_LEGEND, List.of("shift W"));
        d.put(TOGGLE_SETTLER_LENS, List.of("shift R"));
        d.put(SHOW_HOTKEYS, List.of("F1", "shift SLASH"));
        d.put(TOGGLE_AUTO_EXPLORE, List.of("shift E"));
        return d;
    }
}

package com.tackstrat.ui;

import com.tackstrat.model.Chronology;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** User gameplay options persisted under ~/.tackstrat/settings.properties. */
final class GameOptions {

    /** Default Messages API model when the settings file has no model yet (Claude Opus flagship line). */
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-4-7";

    private static final Path USER_DIR = Paths.get(System.getProperty("user.home"), ".tackstrat");
    private static final Path USER_FILE = USER_DIR.resolve("settings.properties");
    private static final Path GRAPHICS_AGENT_SYSTEM_FILE = USER_DIR.resolve("graphics_agent_system.txt");
    private static final String AUTO_FOCUS_NEXT_UNIT = "auto_focus_next_unit";
    private static final String MUSIC_ENABLED = "music_enabled";
    private static final String MUSIC_VOLUME = "music_volume";
    private static final String SFX_VOLUME = "sfx_volume";
    private static final String SFX_ENABLED = "sfx_enabled";
    private static final String MUSIC_API_BASE_URL = "music_api_base_url";
    private static final String MUSIC_API_SHARED_SECRET = "music_api_shared_secret";
    private static final String ANTHROPIC_API_KEY = "anthropic_api_key";
    private static final String ANTHROPIC_API_URL = "anthropic_api_url";
    private static final String ANTHROPIC_MODEL = "anthropic_model";
    private static final String ANTHROPIC_MAX_TOKENS = "anthropic_max_tokens";
    private static final String YEARS_PER_FULL_ROUND = "years_per_full_round";
    private static final String GRAPHICS_SET_FLAIR = "graphics_set_flair";
    private static final String GRAPHICS_GENERATION_API_URL = "graphics_generation_api_url";
    private static final String GRAPHICS_GENERATION_API_KEY = "graphics_generation_api_key";
    private static final String SHOW_WEATHER_LEGEND = "show_weather_legend";
    private static final String SHOW_SETTLER_RECOMMENDATIONS = "show_settler_recommendations";
    private static final String SHOW_CLAIM_LEGEND = "show_claim_legend";
    private static final String FIRST_RUN_TIPS_DISMISSED = "first_run_tips_dismissed";
    private static final String MINIMAP_TINT_OWN_CLAIMS_ONLY = "minimap_tint_own_claims_only";
    private static final String SETTLE_WEIGHT_FOOD = "settle_weight_food";
    private static final String SETTLE_WEIGHT_PRODUCTION = "settle_weight_production";
    private static final String SETTLE_WEIGHT_GOLD = "settle_weight_gold";
    private static final String SETTLE_WEIGHT_TRAVEL = "settle_weight_travel";
    private static final String SETTLE_WEIGHT_RIVAL_PRESSURE = "settle_weight_rival_pressure";
    private static final String WINDOW_LAUNCH_MODE = "window_launch_mode";

    private final boolean autoFocusNextUnit;
    private final boolean musicEnabled;
    private final int musicVolume;
    private final int sfxVolume;
    private final boolean sfxEnabled;
    private final String musicApiBaseUrl;
    private final String musicApiSharedSecret;
    private final String anthropicApiKey;
    private final String anthropicApiUrl;
    private final String anthropicModel;
    private final int anthropicMaxTokens;
    private final int yearsPerFullRound;
    private final String graphicsSetFlair;
    private final String graphicsGenerationApiUrl;
    private final String graphicsGenerationApiKey;
    private final boolean showWeatherLegend;
    private final boolean showSettlerRecommendations;
    private final boolean showClaimLegend;
    /** After the player acknowledges first-run tips once, we skip the dialog. */
    private final boolean tipsDismissed;
    /** Minimap: only blend player-color tint for your own claims (rival claims stay terrain-only). */
    private final boolean minimapTintOwnClaimsOnly;
    private final int settleWeightFood;
    private final int settleWeightProduction;
    private final int settleWeightGold;
    private final int settleWeightTravel;
    private final int settleWeightRivalPressure;
    private final WindowLaunchMode windowLaunchMode;

    private GameOptions(
            boolean autoFocusNextUnit,
            boolean musicEnabled,
            int musicVolume,
            int sfxVolume,
            boolean sfxEnabled,
            String musicApiBaseUrl,
            String musicApiSharedSecret,
            String anthropicApiKey,
            String anthropicApiUrl,
            String anthropicModel,
            int anthropicMaxTokens,
            int yearsPerFullRound,
            String graphicsSetFlair,
            String graphicsGenerationApiUrl,
            String graphicsGenerationApiKey,
            boolean showWeatherLegend,
            boolean showSettlerRecommendations,
            boolean showClaimLegend,
            boolean tipsDismissed,
            boolean minimapTintOwnClaimsOnly,
            int settleWeightFood,
            int settleWeightProduction,
            int settleWeightGold,
            int settleWeightTravel,
            int settleWeightRivalPressure,
            WindowLaunchMode windowLaunchMode) {
        this.autoFocusNextUnit = autoFocusNextUnit;
        this.musicEnabled = musicEnabled;
        this.musicVolume = musicVolume;
        this.sfxVolume = sfxVolume;
        this.sfxEnabled = sfxEnabled;
        this.musicApiBaseUrl = musicApiBaseUrl == null ? "" : musicApiBaseUrl;
        this.musicApiSharedSecret = musicApiSharedSecret == null ? "" : musicApiSharedSecret;
        this.anthropicApiKey = anthropicApiKey == null ? "" : anthropicApiKey;
        this.anthropicApiUrl = anthropicApiUrl == null ? "" : anthropicApiUrl;
        this.anthropicModel = anthropicModel == null ? "" : anthropicModel;
        this.anthropicMaxTokens = anthropicMaxTokens;
        this.yearsPerFullRound = yearsPerFullRound;
        this.graphicsSetFlair = graphicsSetFlair == null ? "" : graphicsSetFlair;
        this.graphicsGenerationApiUrl = graphicsGenerationApiUrl == null ? "" : graphicsGenerationApiUrl;
        this.graphicsGenerationApiKey = graphicsGenerationApiKey == null ? "" : graphicsGenerationApiKey;
        this.showWeatherLegend = showWeatherLegend;
        this.showSettlerRecommendations = showSettlerRecommendations;
        this.showClaimLegend = showClaimLegend;
        this.tipsDismissed = tipsDismissed;
        this.minimapTintOwnClaimsOnly = minimapTintOwnClaimsOnly;
        this.settleWeightFood = clampSettleWeight(settleWeightFood);
        this.settleWeightProduction = clampSettleWeight(settleWeightProduction);
        this.settleWeightGold = clampSettleWeight(settleWeightGold);
        this.settleWeightTravel = clampSettleWeight(settleWeightTravel);
        this.settleWeightRivalPressure = clampSettleWeight(settleWeightRivalPressure);
        this.windowLaunchMode = windowLaunchMode == null ? WindowLaunchMode.MAXIMIZED : windowLaunchMode;
    }

    static GameOptions load() {
        boolean val = false;
        boolean music = true;
        boolean sfx = true;
        int volume = 72;
        int sfxVol = 80;
        String apiBase = "";
        String apiSecret = "";
        String aKey = "";
        String aUrl = "";
        String aModel = "";
        int aMaxTok = 8192;
        int ypr = Chronology.DEFAULT_YEARS_PER_FULL_ROUND;
        String gFlair = "";
        String genUrl = "";
        String genKey = "";
        boolean showWxKey = true;
        boolean showSettleHints = true;
        boolean showClaimHudLegend = true;
        boolean tipsDismissedFlag = false;
        boolean minimapOwnClaimsOnly = false;
        int settleWFood = 3;
        int settleWProd = 3;
        int settleWGold = 2;
        int settleWTravel = 4;
        int settleWRival = 2;
        WindowLaunchMode launchMode = WindowLaunchMode.MAXIMIZED;
        if (Files.isRegularFile(USER_FILE)) {
            var p = new Properties();
            try (InputStream in = Files.newInputStream(USER_FILE)) {
                p.load(in);
                val = Boolean.parseBoolean(p.getProperty(AUTO_FOCUS_NEXT_UNIT, "false"));
                music = Boolean.parseBoolean(p.getProperty(MUSIC_ENABLED, "true"));
                sfx = Boolean.parseBoolean(p.getProperty(SFX_ENABLED, "true"));
                volume = clampVolume(parseInt(p.getProperty(MUSIC_VOLUME), 72));
                sfxVol = clampVolume(parseInt(p.getProperty(SFX_VOLUME), 80));
                apiBase = p.getProperty(MUSIC_API_BASE_URL, "").trim();
                apiSecret = p.getProperty(MUSIC_API_SHARED_SECRET, "").trim();
                aKey = p.getProperty(ANTHROPIC_API_KEY, "").trim();
                aUrl = p.getProperty(ANTHROPIC_API_URL, "").trim();
                aModel = p.getProperty(ANTHROPIC_MODEL, "").trim();
                aMaxTok = clampMaxTokens(parseInt(p.getProperty(ANTHROPIC_MAX_TOKENS), 8192));
                ypr = clampYearsPerRound(parseInt(p.getProperty(YEARS_PER_FULL_ROUND), Chronology.DEFAULT_YEARS_PER_FULL_ROUND));
                gFlair = p.getProperty(GRAPHICS_SET_FLAIR, "").trim();
                genUrl = p.getProperty(GRAPHICS_GENERATION_API_URL, "").trim();
                genKey = p.getProperty(GRAPHICS_GENERATION_API_KEY, "").trim();
                showWxKey = Boolean.parseBoolean(p.getProperty(SHOW_WEATHER_LEGEND, "true"));
                showSettleHints = Boolean.parseBoolean(p.getProperty(SHOW_SETTLER_RECOMMENDATIONS, "true"));
                showClaimHudLegend = Boolean.parseBoolean(p.getProperty(SHOW_CLAIM_LEGEND, "true"));
                tipsDismissedFlag = Boolean.parseBoolean(p.getProperty(FIRST_RUN_TIPS_DISMISSED, "false"));
                minimapOwnClaimsOnly = Boolean.parseBoolean(p.getProperty(MINIMAP_TINT_OWN_CLAIMS_ONLY, "false"));
                settleWFood = clampSettleWeight(parseInt(p.getProperty(SETTLE_WEIGHT_FOOD), 3));
                settleWProd = clampSettleWeight(parseInt(p.getProperty(SETTLE_WEIGHT_PRODUCTION), 3));
                settleWGold = clampSettleWeight(parseInt(p.getProperty(SETTLE_WEIGHT_GOLD), 2));
                settleWTravel = clampSettleWeight(parseInt(p.getProperty(SETTLE_WEIGHT_TRAVEL), 4));
                settleWRival = clampSettleWeight(parseInt(p.getProperty(SETTLE_WEIGHT_RIVAL_PRESSURE), 2));
                String lm = p.getProperty(WINDOW_LAUNCH_MODE, WindowLaunchMode.MAXIMIZED.name()).trim();
                try {
                    launchMode = WindowLaunchMode.valueOf(lm);
                } catch (IllegalArgumentException ignored) {
                    launchMode = WindowLaunchMode.MAXIMIZED;
                }
            } catch (IOException ignored) {
            }
        }
        return new GameOptions(
                val, music, volume, sfxVol, sfx, apiBase, apiSecret, aKey, aUrl, aModel, aMaxTok, ypr, gFlair, genUrl, genKey, showWxKey,
                showSettleHints, showClaimHudLegend, tipsDismissedFlag, minimapOwnClaimsOnly,
                settleWFood, settleWProd, settleWGold, settleWTravel, settleWRival, launchMode);
    }

    static void save(
            boolean autoFocusNextUnit,
            boolean musicEnabled,
            int musicVolume,
            int sfxVolume,
            boolean sfxEnabled,
            String musicApiBaseUrl,
            String musicApiSharedSecret,
            String anthropicApiKey,
            String anthropicApiUrl,
            String anthropicModel,
            int anthropicMaxTokens,
            int yearsPerFullRound,
            String graphicsSetFlair,
            String graphicsGenerationApiUrl,
            String graphicsGenerationApiKey,
            boolean showWeatherLegend,
            boolean showSettlerRecommendations,
            boolean showClaimLegend,
            boolean tipsDismissed,
            boolean minimapTintOwnClaimsOnly,
            int settleWeightFood,
            int settleWeightProduction,
            int settleWeightGold,
            int settleWeightTravel,
            int settleWeightRivalPressure,
            WindowLaunchMode windowLaunchMode) throws IOException {
        Files.createDirectories(USER_FILE.getParent());
        var p = new Properties();
        p.setProperty(AUTO_FOCUS_NEXT_UNIT, Boolean.toString(autoFocusNextUnit));
        p.setProperty(MUSIC_ENABLED, Boolean.toString(musicEnabled));
        p.setProperty(SFX_ENABLED, Boolean.toString(sfxEnabled));
        p.setProperty(MUSIC_VOLUME, Integer.toString(clampVolume(musicVolume)));
        p.setProperty(SFX_VOLUME, Integer.toString(clampVolume(sfxVolume)));
        p.setProperty(MUSIC_API_BASE_URL, musicApiBaseUrl == null ? "" : musicApiBaseUrl.trim());
        p.setProperty(MUSIC_API_SHARED_SECRET, musicApiSharedSecret == null ? "" : musicApiSharedSecret.trim());
        p.setProperty(ANTHROPIC_API_KEY, anthropicApiKey == null ? "" : anthropicApiKey.trim());
        p.setProperty(ANTHROPIC_API_URL, anthropicApiUrl == null ? "" : anthropicApiUrl.trim());
        p.setProperty(ANTHROPIC_MODEL, anthropicModel == null ? "" : anthropicModel.trim());
        p.setProperty(ANTHROPIC_MAX_TOKENS, Integer.toString(clampMaxTokens(anthropicMaxTokens)));
        p.setProperty(YEARS_PER_FULL_ROUND, Integer.toString(clampYearsPerRound(yearsPerFullRound)));
        p.setProperty(GRAPHICS_SET_FLAIR, graphicsSetFlair == null ? "" : graphicsSetFlair.trim());
        p.setProperty(GRAPHICS_GENERATION_API_URL, graphicsGenerationApiUrl == null ? "" : graphicsGenerationApiUrl.trim());
        p.setProperty(GRAPHICS_GENERATION_API_KEY, graphicsGenerationApiKey == null ? "" : graphicsGenerationApiKey.trim());
        p.setProperty(SHOW_WEATHER_LEGEND, Boolean.toString(showWeatherLegend));
        p.setProperty(SHOW_SETTLER_RECOMMENDATIONS, Boolean.toString(showSettlerRecommendations));
        p.setProperty(SHOW_CLAIM_LEGEND, Boolean.toString(showClaimLegend));
        p.setProperty(FIRST_RUN_TIPS_DISMISSED, Boolean.toString(tipsDismissed));
        p.setProperty(MINIMAP_TINT_OWN_CLAIMS_ONLY, Boolean.toString(minimapTintOwnClaimsOnly));
        p.setProperty(SETTLE_WEIGHT_FOOD, Integer.toString(clampSettleWeight(settleWeightFood)));
        p.setProperty(SETTLE_WEIGHT_PRODUCTION, Integer.toString(clampSettleWeight(settleWeightProduction)));
        p.setProperty(SETTLE_WEIGHT_GOLD, Integer.toString(clampSettleWeight(settleWeightGold)));
        p.setProperty(SETTLE_WEIGHT_TRAVEL, Integer.toString(clampSettleWeight(settleWeightTravel)));
        p.setProperty(SETTLE_WEIGHT_RIVAL_PRESSURE, Integer.toString(clampSettleWeight(settleWeightRivalPressure)));
        p.setProperty(WINDOW_LAUNCH_MODE, (windowLaunchMode == null ? WindowLaunchMode.MAXIMIZED : windowLaunchMode).name());
        try (OutputStream out = Files.newOutputStream(USER_FILE)) {
            p.store(out, "Tack & Strat settings");
        }
    }

    static Path userFilePath() {
        return USER_FILE;
    }

    boolean autoFocusNextUnit() {
        return autoFocusNextUnit;
    }

    boolean musicEnabled() {
        return musicEnabled;
    }

    int musicVolume() {
        return musicVolume;
    }

    int sfxVolume() {
        return sfxVolume;
    }

    boolean sfxEnabled() {
        return sfxEnabled;
    }

    String musicApiBaseUrl() {
        return musicApiBaseUrl;
    }

    String musicApiSharedSecret() {
        return musicApiSharedSecret;
    }

    String anthropicApiKey() {
        return anthropicApiKey;
    }

    String anthropicApiUrl() {
        return anthropicApiUrl;
    }

    String anthropicModel() {
        return anthropicModel;
    }

    int anthropicMaxTokens() {
        return anthropicMaxTokens;
    }

    /** Years added to the calendar each time every player finishes one full turn rotation. */
    int yearsPerFullRound() {
        return yearsPerFullRound;
    }

    /** Optional creative line appended to graphics AI requests (Graphics lab). */
    String graphicsSetFlair() {
        return graphicsSetFlair;
    }

    /**
     * Optional HTTP endpoint that accepts {@code tackstratGraphicsGenerateRequest} JSON and returns
     * {@code tackstratGraphicsImportBundle} JSON. Blank = use Anthropic Messages in-app instead.
     */
    String graphicsGenerationApiUrl() {
        return graphicsGenerationApiUrl;
    }

    /** Bearer / shared key for {@link #graphicsGenerationApiUrl()} (blank = reuse Anthropic API key). */
    String graphicsGenerationApiKey() {
        return graphicsGenerationApiKey;
    }

    /** Map HUD: color chips explaining regional weather tints (can be hidden in Settings or via keybind). */
    boolean showWeatherLegend() {
        return showWeatherLegend;
    }

    boolean showSettlerRecommendations() {
        return showSettlerRecommendations;
    }

    boolean showClaimLegend() {
        return showClaimLegend;
    }

    /** True after the player dismissed the first-run tips dialog (or turned tips off in Settings). */
    boolean tipsDismissed() {
        return tipsDismissed;
    }

    boolean minimapTintOwnClaimsOnly() {
        return minimapTintOwnClaimsOnly;
    }

    /** Persist {@code tipsDismissed=true} while keeping other settings unchanged. */
    static void markTipsDismissed() throws IOException {
        GameOptions o = load();
        save(
                o.autoFocusNextUnit(),
                o.musicEnabled(),
                o.musicVolume(),
                o.sfxVolume(),
                o.sfxEnabled(),
                o.musicApiBaseUrl(),
                o.musicApiSharedSecret(),
                o.anthropicApiKey(),
                o.anthropicApiUrl(),
                o.anthropicModel(),
                o.anthropicMaxTokens(),
                o.yearsPerFullRound(),
                o.graphicsSetFlair(),
                o.graphicsGenerationApiUrl(),
                o.graphicsGenerationApiKey(),
                o.showWeatherLegend(),
                o.showSettlerRecommendations(),
                o.showClaimLegend(),
                true,
                o.minimapTintOwnClaimsOnly(),
                o.settleWeightFood(),
                o.settleWeightProduction(),
                o.settleWeightGold(),
                o.settleWeightTravel(),
                o.settleWeightRivalPressure(),
                o.windowLaunchMode());
    }

    int settleWeightFood() {
        return settleWeightFood;
    }

    int settleWeightProduction() {
        return settleWeightProduction;
    }

    int settleWeightGold() {
        return settleWeightGold;
    }

    int settleWeightTravel() {
        return settleWeightTravel;
    }

    int settleWeightRivalPressure() {
        return settleWeightRivalPressure;
    }

    WindowLaunchMode windowLaunchMode() {
        return windowLaunchMode;
    }

    static Path graphicsAgentSystemPromptFile() {
        return GRAPHICS_AGENT_SYSTEM_FILE;
    }

    static String loadGraphicsAgentSystemPrompt() {
        try {
            if (!Files.isRegularFile(GRAPHICS_AGENT_SYSTEM_FILE)) return "";
            return Files.readString(GRAPHICS_AGENT_SYSTEM_FILE, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    static void saveGraphicsAgentSystemPrompt(String text) throws IOException {
        Files.createDirectories(GRAPHICS_AGENT_SYSTEM_FILE.getParent());
        Files.writeString(
                GRAPHICS_AGENT_SYSTEM_FILE,
                text == null ? "" : text,
                StandardCharsets.UTF_8);
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int clampVolume(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampMaxTokens(int value) {
        return Math.max(256, Math.min(128_000, value));
    }

    private static int clampYearsPerRound(int value) {
        return Math.max(1, Math.min(99, value));
    }

    private static int clampSettleWeight(int value) {
        return Math.max(0, Math.min(9, value));
    }
}

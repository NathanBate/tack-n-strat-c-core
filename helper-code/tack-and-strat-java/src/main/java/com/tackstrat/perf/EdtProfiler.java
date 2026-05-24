package com.tackstrat.perf;

import java.util.logging.Logger;

/**
 * Opt-in EDT timing logs. Enable with {@code -Dtackstrat.profile=true} to compare map paint, refresh, and AI tick cost.
 */
public final class EdtProfiler {

    private static final Logger LOG = Logger.getLogger(EdtProfiler.class.getName());

    private EdtProfiler() {}

    public static boolean enabled() {
        return Boolean.getBoolean("tackstrat.profile");
    }

    public static void recordMapPaintNanos(long nanos) {
        if (!enabled()) {
            return;
        }
        LOG.info(String.format("HexMapPanel.paint (session-synchronized body): %.3f ms", nanos / 1_000_000.0));
    }

    public static void recordRefreshNanos(long nanos) {
        if (!enabled()) {
            return;
        }
        LOG.info(String.format("PlayPanel.refresh: %.3f ms", nanos / 1_000_000.0));
    }

    public static void recordSeatAiTickNanos(long nanos) {
        if (!enabled()) {
            return;
        }
        LOG.info(String.format("SeatAi.tick (background AI thread): %.3f ms", nanos / 1_000_000.0));
    }

    /** Highlights for selected unit: {@link com.tackstrat.model.GameSession#legalMoves} / {@code legalAttacks}. */
    public static void recordLegalHighlightNanos(long nanos) {
        if (!enabled()) {
            return;
        }
        LOG.info(String.format("Map legalMoves+legalAttacks overlay: %.3f ms", nanos / 1_000_000.0));
    }
}

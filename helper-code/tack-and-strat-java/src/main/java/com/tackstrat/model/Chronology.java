package com.tackstrat.model;

/** In-game calendar from ~4000 BCE forward; each full round advances time. */
public final class Chronology {

    /** Year shown at game start (Before Common Era). */
    public static final int START_YEAR_BCE = 4000;

    /** Default years per full rotation when not overridden in settings / save. */
    public static final int DEFAULT_YEARS_PER_FULL_ROUND = 5;

    private Chronology() {}

    /**
     * @param yearsElapsed total years since session began (increments by the session's years-per-full-round setting)
     */
    public static String formatEra(int yearsElapsed) {
        if (yearsElapsed < 0) {
            yearsElapsed = 0;
        }
        int bcRemaining = START_YEAR_BCE - yearsElapsed;
        if (bcRemaining > 0) {
            return bcRemaining + " BCE";
        }
        int ce = yearsElapsed - START_YEAR_BCE + 1;
        return ce + " CE";
    }

    /** Long-form label for UI tooltips. */
    public static String formatEraLong(int yearsElapsed) {
        return "Circa " + formatEra(yearsElapsed);
    }

    /**
     * Calendar seasons from elapsed years and pacing: roughly four seasons span each block of
     * {@code yearsPerFullRound} (minimum one in-game year per season slice).
     */
    public static int seasonStrideYears(int yearsPerFullRound) {
        int ypr = Math.max(1, yearsPerFullRound);
        return Math.max(1, (ypr + 3) / 4);
    }

    public static int seasonIndexFromElapsedYears(int yearsElapsed, int yearsPerFullRound) {
        int stride = seasonStrideYears(yearsPerFullRound);
        int y = Math.max(0, yearsElapsed);
        return (y / stride) % 4;
    }

    public static Season seasonFromElapsedYears(int yearsElapsed, int yearsPerFullRound) {
        return Season.fromOrdinal(seasonIndexFromElapsedYears(yearsElapsed, yearsPerFullRound));
    }
}

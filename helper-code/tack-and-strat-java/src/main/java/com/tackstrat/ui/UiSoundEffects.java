package com.tackstrat.ui;

import com.tackstrat.audio.SfxPlayer;

final class UiSoundEffects {

    private static volatile boolean enabled = true;
    private static volatile long lastBeepAtMs = 0L;

    private UiSoundEffects() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static void click() {
        beepThrottled(SfxPlayer.Kind.CLICK, 70);
    }

    static void success() {
        playKind(SfxPlayer.Kind.SUCCESS, 0);
    }

    static void warning() {
        beepThrottled(SfxPlayer.Kind.WARNING, 140);
    }

    static void foundCity() {
        playKind(SfxPlayer.Kind.FOUND_CITY, 40);
    }

    static void illegalAction() {
        beepThrottled(SfxPlayer.Kind.ILLEGAL_ACTION, 120);
    }

    /** Soft cue when your turn begins (distinct from end-turn success). */
    static void turnAdvance() {
        beepThrottled(SfxPlayer.Kind.TURN_ADVANCE, 220);
    }

    /** Entering the pass-the-device gate — distinct from map clicks. */
    static void handoffGate() {
        if (!enabled) {
            return;
        }
        SfxPlayer.play(SfxPlayer.Kind.HANDOFF);
        javax.swing.Timer t = new javax.swing.Timer(52, e -> {
            ((javax.swing.Timer) e.getSource()).stop();
            if (enabled) {
                SfxPlayer.play(SfxPlayer.Kind.HANDOFF);
            }
        });
        t.setRepeats(false);
        t.start();
    }

    /** Returning from handoff to the tactical map. */
    static void mapResumeFromHandoff() {
        beepThrottled(SfxPlayer.Kind.MAP_RESUME, 85);
    }

    private static void beepThrottled(SfxPlayer.Kind kind, long minSpacingMs) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBeepAtMs < minSpacingMs) {
            return;
        }
        lastBeepAtMs = now;
        SfxPlayer.play(kind);
    }

    private static void playKind(SfxPlayer.Kind kind, long minSpacingMs) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBeepAtMs < minSpacingMs) {
            return;
        }
        lastBeepAtMs = now;
        SfxPlayer.play(kind);
    }
}

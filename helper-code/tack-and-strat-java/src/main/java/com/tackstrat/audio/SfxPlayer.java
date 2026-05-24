package com.tackstrat.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/** Short UI cues from classpath WAV resources (falls back to system beep if missing). */
public final class SfxPlayer {

    public enum Kind {
        CLICK,
        SUCCESS,
        WARNING,
        HANDOFF,
        MAP_RESUME,
        FOUND_CITY,
        ILLEGAL_ACTION,
        TURN_ADVANCE
    }

    private static final Map<Kind, byte[]> CACHE = new EnumMap<>(Kind.class);

    static {
        for (Kind k : Kind.values()) {
            String name =
                    switch (k) {
                        case MAP_RESUME -> "map_resume";
                        default -> k.name().toLowerCase();
                    };
            String path = "/com/tackstrat/sounds/" + name + ".wav";
            try (InputStream in = SfxPlayer.class.getResourceAsStream(path)) {
                if (in != null) {
                    CACHE.put(k, in.readAllBytes());
                }
            } catch (IOException ignored) {
            }
        }
        byte[] ok = CACHE.get(Kind.SUCCESS);
        byte[] warn = CACHE.get(Kind.WARNING);
        byte[] click = CACHE.get(Kind.CLICK);
        if (ok != null) {
            CACHE.put(Kind.FOUND_CITY, ok);
        }
        if (warn != null) {
            CACHE.put(Kind.ILLEGAL_ACTION, warn);
        }
        if (click != null) {
            CACHE.put(Kind.TURN_ADVANCE, click);
        }
    }

    private static volatile boolean enabled = true;
    private static volatile int volumePercent = 80;

    private SfxPlayer() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static void setVolumePercent(int v) {
        volumePercent = Math.max(0, Math.min(100, v));
    }

    public static void play(Kind kind) {
        if (!enabled) {
            return;
        }
        byte[] data = CACHE.get(kind);
        if (data == null || data.length == 0) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        try {
            Clip clip = AudioSystem.getClip();
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            clip.open(ais);
            ais.close();
            applyVolume(clip);
            clip.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.start();
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private static void applyVolume(Clip clip) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        var fc = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float min = fc.getMinimum();
        float max = fc.getMaximum();
        float t = volumePercent / 100f;
        fc.setValue(min + (max - min) * t);
    }
}

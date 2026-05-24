package com.tackstrat.ui;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.stream.Stream;

final class BackgroundMusicPlayer {

    private static final Path ENVATO_BUNDLES_DIR = Path.of("assets/music/envato-bundles");
    private static final Path ENVATO_BUNDLES_DIR_ALT = Path.of("tack-and-strat/assets/music/envato-bundles");
    private static final Path ENVATO_EXPANDED_DIR = Path.of("assets/music/envato-expanded");
    private static final Path ENVATO_EXPANDED_DIR_ALT = Path.of("tack-and-strat/assets/music/envato-expanded");
    private static final long POLL_MS = 220L;

    private final AtomicInteger skipSignal = new AtomicInteger();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int volume = 72;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile Clip currentClip;
    private volatile SourceDataLine currentLine;
    private volatile Process currentProcess;
    private volatile Path currentTempFile;
    private volatile Random runRandom;
    private volatile boolean warnedMissingBundleDir;
    private volatile boolean warnedNoTracks;

    void startLoop() {
        if (running) return;
        cleanupStaleAfplay();
        running = true;
        skipSignal.set(0);
        generation.incrementAndGet();
        runRandom = new Random(System.nanoTime());
        System.err.println("[music] startLoop enabled. cwd=" + Path.of("").toAbsolutePath());
        worker = new Thread(this::runPlaybackLoop, "tackstrat-music-worker");
        worker.setDaemon(true);
        worker.start();
    }

    void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        applyVolume(currentClip);
        applyVolume(currentLine);
    }

    void skipToNextTheme() {
        skipSignal.incrementAndGet();
        stopCurrentClip();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation.incrementAndGet();
        skipSignal.incrementAndGet();
        stopCurrentClip();
        var t = worker;
        worker = null;
        if (t != null) t.interrupt();
        cleanupTempFile();
    }

    private void runPlaybackLoop() {
        try {
            System.err.println("[music] playback worker started");
            while (running) {
                int extracted = materializeZipLibrary();
                List<Path> tracks = discoverWavTracks();
                System.err.println("[music] discovered bundles: " + discoverZipBundles().size()
                        + " · extracted: " + extracted + " · tracks: " + tracks.size());
                if (tracks.isEmpty()) {
                    if (!warnedNoTracks) {
                        warnedNoTracks = true;
                        System.err.println("[music] no playable wav tracks found. "
                                + "Expected extracted files under envato-expanded.");
                    }
                    sleepQuietly(1200);
                    continue;
                }
                warnedNoTracks = false;
                Collections.shuffle(tracks, runRandom == null ? new Random() : runRandom);
                int cycle = generation.get();
                for (Path wav : tracks) {
                    if (!running || cycle != generation.get()) break;
                    playWavTrack(wav);
                }
            }
        } catch (Exception ignored) {
        } finally {
            stopCurrentClip();
            cleanupTempFile();
        }
    }

    private List<Path> discoverZipBundles() {
        Path bundlesDir = resolveBundleDir();
        if (bundlesDir == null || !Files.isDirectory(bundlesDir)) {
            if (!warnedMissingBundleDir) {
                warnedMissingBundleDir = true;
                System.err.println("[music] envato bundle directory not found. looked for: "
                        + ENVATO_BUNDLES_DIR + " and " + ENVATO_BUNDLES_DIR_ALT);
            }
            return List.of();
        }
        warnedMissingBundleDir = false;
        try (var stream = Files.list(bundlesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".zip");
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private int materializeZipLibrary() {
        Path bundlesDir = resolveBundleDir();
        Path expandedDir = resolveExpandedDir();
        if (bundlesDir == null || expandedDir == null) return 0;
        int extracted = 0;
        try {
            Files.createDirectories(expandedDir);
        } catch (IOException ignored) {
            return 0;
        }
        for (Path zip : discoverZipBundles()) {
            extracted += extractWavsToExpanded(zip, expandedDir);
        }
        return extracted;
    }

    private static int extractWavsToExpanded(Path zipPath, Path expandedDir) {
        int extracted = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.toLowerCase().endsWith(".wav")) continue;
                String safeBase = zipPath.getFileName().toString()
                        .replaceAll("(?i)\\.zip$", "")
                        .replaceAll("[^a-zA-Z0-9._-]+", "_");
                String leaf = Path.of(name).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]+", "_");
                Path out = expandedDir.resolve(safeBase + "__" + leaf);
                if (Files.exists(out)) continue;
                try (var in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    extracted++;
                }
            }
        } catch (Exception ex) {
            System.err.println("[music] failed expanding zip " + zipPath.getFileName() + ": " + ex.getMessage());
        }
        return extracted;
    }

    private List<Path> discoverWavTracks() {
        Path expandedDir = resolveExpandedDir();
        if (expandedDir == null || !Files.isDirectory(expandedDir)) return List.of();
        try (Stream<Path> stream = Files.list(expandedDir)) {
            var out = new ArrayList<Path>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wav"))
                    .forEach(out::add);
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void playWavTrack(Path wav) {
        int skipAtStart = skipSignal.get();
        currentTempFile = null;
        // Prefer Java SourceDataLine: {@link FloatControl} gain updates live via
        // {@link #setVolume} without restarting playback (afplay fixes -v per process).
        if (tryPlayWithJavaStreaming(wav, skipAtStart)) {
            return;
        }
        if (playWithAfplayIfAvailable(wav, skipAtStart)) {
            return;
        }
    }

    /** @return true if Java audio played (or was interrupted by skip); false to try afplay fallback */
    private boolean tryPlayWithJavaStreaming(Path wav, int skipAtStart) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile());
             AudioInputStream decoded = decodeToPcm(in)) {
            AudioFormat fmt = decoded.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            currentLine = line;
            applyVolume(line);
            line.start();
            System.err.println("[music] now playing: " + wav.getFileName() + " (java)");
            byte[] buf = new byte[32 * 1024];
            int n;
            while (running && skipSignal.get() == skipAtStart && (n = decoded.read(buf, 0, buf.length)) != -1) {
                line.write(buf, 0, n);
            }
            line.drain();
            line.stop();
            line.close();
            return true;
        } catch (Exception ex) {
            System.err.println("[music] java decode/play failed for " + wav.getFileName()
                    + ": " + ex.getMessage() + " (trying afplay if available)");
            return false;
        } finally {
            currentClip = null;
            currentLine = null;
        }
    }

    /** Fallback when Java cannot decode/play; gain is fixed for this process (volume changes apply next track). */
    private boolean playWithAfplayIfAvailable(Path wav, int skipAtStart) {
        Path afplay = Path.of("/usr/bin/afplay");
        if (!Files.isExecutable(afplay)) return false;
        double vol = Math.max(0.0, Math.min(1.0, volume / 100.0));
        Process p = null;
        try {
            p = new ProcessBuilder(afplay.toString(), "-v", String.format("%.2f", vol), wav.toString())
                    .redirectErrorStream(true)
                    .start();
            currentProcess = p;
            System.err.println("[music] now playing: " + wav.getFileName() + " (afplay)");
            while (running) {
                if (skipSignal.get() != skipAtStart) {
                    p.destroy();
                    break;
                }
                if (!p.isAlive()) break;
                sleepQuietly(POLL_MS);
            }
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return true;
        } catch (IOException ex) {
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroy();
            currentProcess = null;
        }
    }

    private static AudioInputStream decodeToPcm(AudioInputStream input) {
        AudioFormat base = input.getFormat();
        AudioFormat pcm = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.getSampleRate(),
                16,
                base.getChannels(),
                Math.max(2, base.getChannels() * 2),
                base.getSampleRate(),
                false);
        return AudioSystem.getAudioInputStream(pcm, input);
    }

    private static Path resolveBundleDir() {
        if (Files.isDirectory(ENVATO_BUNDLES_DIR)) return ENVATO_BUNDLES_DIR;
        if (Files.isDirectory(ENVATO_BUNDLES_DIR_ALT)) return ENVATO_BUNDLES_DIR_ALT;
        return null;
    }

    private void applyVolume(Clip clip) {
        if (clip == null) return;
        try {
            var ctl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            if (volume <= 0) {
                ctl.setValue(ctl.getMinimum());
                return;
            }
            float dB = (float) (20.0 * Math.log10(Math.max(0.0001, volume / 100.0)));
            dB = Math.max(ctl.getMinimum(), Math.min(ctl.getMaximum(), dB));
            ctl.setValue(dB);
        } catch (Exception ignored) {
        }
    }

    private void applyVolume(SourceDataLine line) {
        if (line == null) return;
        try {
            var ctl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            if (volume <= 0) {
                ctl.setValue(ctl.getMinimum());
                return;
            }
            float dB = (float) (20.0 * Math.log10(Math.max(0.0001, volume / 100.0)));
            dB = Math.max(ctl.getMinimum(), Math.min(ctl.getMaximum(), dB));
            ctl.setValue(dB);
        } catch (Exception ignored) {
        }
    }

    private void stopCurrentClip() {
        Clip c = currentClip;
        try {
            Process p = currentProcess;
            if (p != null) {
                p.destroy();
                if (p.isAlive()) p.destroyForcibly();
                currentProcess = null;
            }
            if (c != null) {
                c.stop();
                c.close();
            }
            SourceDataLine line = currentLine;
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
            }
        } catch (Exception ignored) {
        } finally {
            currentClip = null;
            currentLine = null;
        }
    }

    private void cleanupTempFile() {
        Path tmp = currentTempFile;
        currentTempFile = null;
        if (tmp == null) return;
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path resolveExpandedDir() {
        if (Files.isDirectory(ENVATO_EXPANDED_DIR)) return ENVATO_EXPANDED_DIR;
        if (Files.isDirectory(ENVATO_EXPANDED_DIR_ALT)) return ENVATO_EXPANDED_DIR_ALT;
        // If neither exists yet, pick primary for creation.
        return ENVATO_EXPANDED_DIR;
    }

    private static void cleanupStaleAfplay() {
        String marker = "envato-expanded";
        try {
            Process p = new ProcessBuilder("ps", "-ax", "-o", "pid=,command=").start();
            String listing = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (String line : listing.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (!lower.contains("afplay") || !lower.contains(marker)) continue;
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 0) continue;
                try {
                    long pid = Long.parseLong(parts[0]);
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}

package com.tackstrat.ui;

import com.tackstrat.audio.SfxPlayer;
import com.tackstrat.model.GameSession;
import com.tackstrat.model.GameSnapshot;
import com.tackstrat.persistence.GamePersistence;
import com.tackstrat.persistence.GameSaveFiles;
import com.tackstrat.persistence.UiSaveState;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MainWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    private static final String CARD_SPLASH = "splash";
    private static final String CARD_SETUP = "setup";
    private static final String CARD_SETTINGS = "settings";
    private static final String CARD_LOAD = "load";
    private static final String CARD_SAVE = "save";
    private static final String CARD_HANDOFF = "handoff";
    private static final String CARD_PLAY = "play";
    private static final String CARD_VICTORY = "victory";

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final PlayPanel playPanel;
    private final HandoffPanel handoffPanel;
    private final VictoryPanel victoryPanel;
    private final LoadGamePanel loadGamePanel;
    private final SaveGamePanel saveGamePanel;
    private final SettingsPanel settingsPanel;
    private final SplashPanel splashPanel;
    private final BackgroundMusicPlayer musicPlayer = new BackgroundMusicPlayer();
    private boolean musicEnabled = true;
    private boolean sfxEnabled = true;
    private int musicVolume = 72;
    private int sfxVolume = 80;

    private GameSession session;
    /** Bumps when a new {@link GameSession} is bound so in-flight async autosaves skip stale writes. */
    private volatile int autosaveEpoch;
    private final ExecutorService autosaveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tackstrat-autosave");
        t.setDaemon(true);
        return t;
    });

    public MainWindow() {
        super("Tack & Strat");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        playPanel = new PlayPanel(
                this::showMenu,
                this::onTurnEnded,
                this::showSaveGameScreen,
                this::toggleMusic,
                this::nextMusicTheme,
                this::toggleSfx,
                () -> adjustMusicVolume(+8),
                () -> adjustMusicVolume(-8),
                () -> musicEnabled,
                () -> sfxEnabled,
                () -> musicVolume,
                () -> sfxVolume);
        handoffPanel = new HandoffPanel(this::beginCurrentTurn, this::retryAutosaveFromHandoff);
        victoryPanel = new VictoryPanel(this::showMenu, this::repeatLastSetup);
        loadGamePanel = new LoadGamePanel(this::showMenu, this::loadFromPath);
        saveGamePanel = new SaveGamePanel(this::returnToPlayFromSave, this::commitNamedSave);
        settingsPanel = new SettingsPanel(this::showMenu, this::onSettingsSaved, this::onMusicVolumeLiveChange, this::onSfxVolumeLiveChange);

        splashPanel = new SplashPanel(
                this::continueLastGame,
                this::showSetup,
                this::showLoadGameScreen,
                this::showSettings,
                this::bidirectionalSyncFromMenu);
        root.add(splashPanel, CARD_SPLASH);
        root.add(new SetupPanel(this::startGame, this::showMenu), CARD_SETUP);
        root.add(settingsPanel, CARD_SETTINGS);
        root.add(loadGamePanel, CARD_LOAD);
        root.add(saveGamePanel, CARD_SAVE);
        root.add(handoffPanel, CARD_HANDOFF);
        root.add(playPanel, CARD_PLAY);
        root.add(victoryPanel, CARD_VICTORY);

        setContentPane(root);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1320, 880);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                musicPlayer.stop();
                autosaveExecutor.shutdown();
                try {
                    if (!autosaveExecutor.awaitTermination(12, TimeUnit.SECONDS)) {
                        LOG.warning("Autosave executor did not finish within timeout on window close");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                musicPlayer.stop();
            }
        });
        applyAudioSettings(GameOptions.load());
        showMenu();
        setLocationRelativeTo(null);
        getRootPane().putClientProperty("apple.awt.fullscreenable", Boolean.TRUE);
        Runtime.getRuntime().addShutdownHook(new Thread(musicPlayer::stop, "tackstrat-audio-shutdown"));
    }

    /** Apply user-selected startup window mode. */
    public void applyWindowLaunchMode(WindowLaunchMode mode) {
        GraphicsDevice gd = null;
        try {
            gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (Exception ignored) {
        }
        if (gd != null && gd.getFullScreenWindow() == this) {
            gd.setFullScreenWindow(null);
        }
        WindowLaunchMode use = mode == null ? WindowLaunchMode.MAXIMIZED : mode;
        switch (use) {
            case WINDOWED -> {
                setExtendedState(JFrame.NORMAL);
                setSize(1320, 880);
                setLocationRelativeTo(null);
            }
            case MAXIMIZED -> setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
            case NATIVE_FULLSCREEN -> {
                setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
                if (gd != null && gd.isFullScreenSupported()) {
                    gd.setFullScreenWindow(this);
                }
            }
        }
    }

    public void applyWindowLaunchModeFromSettings() {
        applyWindowLaunchMode(GameOptions.load().windowLaunchMode());
    }

    private void showMenu() {
        cards.show(root, CARD_SPLASH);
        splashPanel.refreshContinueAvailability();
        splashPanel.refreshCallout();
        SwingUtilities.invokeLater(splashPanel::requestFocusInWindow);
    }

    private void showSetup() {
        cards.show(root, CARD_SETUP);
    }

    private void showSettings() {
        settingsPanel.refreshFromDisk();
        cards.show(root, CARD_SETTINGS);
    }

    private void onSettingsSaved() {
        playPanel.reloadKeyBindings();
        playPanel.reloadUserSettings();
        playPanel.reloadGraphicAssetsAfterSettings();
        var opts = GameOptions.load();
        applyAudioSettings(opts);
        applyWindowLaunchMode(opts.windowLaunchMode());
        splashPanel.refreshCallout();
    }

    private void bidirectionalSyncFromMenu() {
        var opts = GameOptions.load();
        var api = opts.musicApiBaseUrl();
        var secret = opts.musicApiSharedSecret();
        if (api.isBlank() || secret.isBlank()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Music API URL and shared secret aren't configured yet. Open Settings → Audio to set them.",
                    "Music sync",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        splashPanel.notifySyncStarted();
        new SwingWorker<MusicSyncClient.FullSyncResult, MusicSyncClient.FullProgress>() {
            @Override
            protected MusicSyncClient.FullSyncResult doInBackground() {
                return new MusicSyncClient().syncBidirectional(api, secret, this::publish);
            }

            @Override
            protected void process(java.util.List<MusicSyncClient.FullProgress> chunks) {
                if (chunks.isEmpty()) return;
                var last = chunks.get(chunks.size() - 1);
                splashPanel.notifySyncProgress(last);
            }

            @Override
            protected void done() {
                try {
                    var r = get();
                    String summary;
                    boolean ok;
                    if (r.errors().isEmpty()) {
                        summary = "Uploaded " + r.uploaded() + ", pulled " + r.downloaded() + " new, skipped "
                                + (r.uploadSkipped() + r.downloadSkipped()) + " unchanged.";
                        ok = true;
                    } else {
                        summary = "Uploaded " + r.uploaded() + ", pulled " + r.downloaded() + ". "
                                + r.errors().size() + " error(s).";
                        ok = false;
                        JOptionPane.showMessageDialog(
                                MainWindow.this,
                                String.join("\n", r.errors()),
                                "Music sync details",
                                JOptionPane.WARNING_MESSAGE);
                    }
                    splashPanel.notifySyncFinished(summary, ok);
                    if (musicEnabled) musicPlayer.skipToNextTheme();
                } catch (Exception ex) {
                    splashPanel.notifySyncFinished("Sync failed: " + ex.getMessage(), false);
                }
            }
        }.execute();
    }

    private void showLoadGameScreen() {
        loadGamePanel.refreshList();
        cards.show(root, CARD_LOAD);
    }

    private void showSaveGameScreen() {
        if (session == null) {
            return;
        }
        saveGamePanel.refreshList();
        cards.show(root, CARD_SAVE);
    }

    private void returnToPlayFromSave() {
        cards.show(root, CARD_PLAY);
        SwingUtilities.invokeLater(playPanel::requestFocusInWindow);
    }

    private void continueLastGame() {
        if (!GameSaveFiles.autosaveExists()) {
            return;
        }
        loadFromPath(GameSaveFiles.autosavePath());
    }

    private void loadFromPath(Path path) {
        try {
            var loaded = GamePersistence.readLoaded(path);
            startGame(GameSession.restore(loaded.snapshot()), loaded.uiSaveState());
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Load failed: " + path, ex);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not load this save:\n" + ex.getMessage(),
                    "Load failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void commitNamedSave(String displayLabel) {
        if (session == null) {
            return;
        }
        try {
            Path p = GameSaveFiles.pathForSaveLabel(displayLabel);
            if (Files.exists(p)) {
                int r = JOptionPane.showConfirmDialog(
                        this,
                        "Replace the save named \"" + displayLabel + "\"?",
                        "Overwrite save",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            GamePersistence.writeNamedSave(displayLabel, session.capture(), playPanel.exportUiSaveState());
            returnToPlayFromSave();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save:\n" + ex.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startGame(GameSession newSession) {
        startGame(newSession, UiSaveState.EMPTY);
    }

    private void startGame(GameSession newSession, UiSaveState uiSaveState) {
        autosaveEpoch++;
        this.session = newSession;
        playPanel.bind(session, uiSaveState);
        if (session.isOver()) {
            victoryPanel.show(session, session.winner().orElseThrow());
            cards.show(root, CARD_VICTORY);
        } else {
            boolean saved = tryAutosaveSession();
            showHandoff(saved);
        }
    }

    private void retryAutosaveFromHandoff() {
        if (session == null) {
            return;
        }
        boolean ok = tryAutosaveSession();
        handoffPanel.show(session, ok);
    }

    private void repeatLastSetup() {
        showSetup();
    }

    private void showHandoff(boolean autosaveOk) {
        if (session == null) {
            showMenu();
            return;
        }
        UiSoundEffects.handoffGate();
        handoffPanel.show(session, autosaveOk);
        cards.show(root, CARD_HANDOFF);
        SwingUtilities.invokeLater(() -> handoffPanel.beginButton().requestFocusInWindow());
    }

    private void beginCurrentTurn() {
        UiSoundEffects.mapResumeFromHandoff();
        cards.show(root, CARD_PLAY);
        playPanel.onTurnBegan();
        if (session != null && session.currentPlayer().computer()) {
            playPanel.runComputerTurn(this::advanceAfterTurn);
        } else {
            SwingUtilities.invokeLater(playPanel::requestFocusInWindow);
        }
    }

    private void onTurnEnded() {
        advanceAfterTurn();
    }

    /** After any {@link GameSession#endTurn()}, route either to the pass-device gate or chain AI seats. */
    private void advanceAfterTurn() {
        if (session == null) {
            showMenu();
            return;
        }
        if (session.isOver()) {
            victoryPanel.show(session, session.winner().orElseThrow());
            cards.show(root, CARD_VICTORY);
            return;
        }
        boolean saved = tryAutosaveSession();
        if (session.currentPlayer().computer()) {
            cards.show(root, CARD_PLAY);
            playPanel.onTurnBegan();
            playPanel.runComputerTurn(this::advanceAfterTurn);
        } else {
            showHandoff(saved);
        }
    }

    /** Silent checkpoint after each turn handoff so “Continue” can resume Civ-style. */
    private boolean tryAutosaveSession() {
        if (session == null || session.isOver()) {
            return true;
        }
        final int epoch = autosaveEpoch;
        final GameSnapshot snapshot;
        final UiSaveState uiState;
        try {
            snapshot = session.capture();
            uiState = playPanel.exportUiSaveState();
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Autosave capture failed", ex);
            playPanel.appendLogLine("Autosave failed — checkpoint not written. Try Retry on handoff or use Save game.");
            return false;
        }
        autosaveExecutor.execute(() -> {
            if (epoch != autosaveEpoch) {
                return;
            }
            try {
                GamePersistence.writeAutosave(snapshot, uiState);
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (epoch != autosaveEpoch) {
                        return;
                    }
                    LOG.log(Level.WARNING, "Autosave failed", ex);
                    playPanel.appendLogLine(
                            "Autosave failed — checkpoint not written. Try Retry on handoff or use Save game.");
                });
            }
        });
        return true;
    }

    private void applyAudioSettings(GameOptions opts) {
        musicEnabled = opts.musicEnabled();
        musicVolume = opts.musicVolume();
        sfxEnabled = opts.sfxEnabled();
        sfxVolume = opts.sfxVolume();
        UiSoundEffects.setEnabled(sfxEnabled);
        SfxPlayer.setVolumePercent(sfxVolume);
        musicPlayer.setVolume(musicVolume);
        if (musicEnabled) musicPlayer.startLoop();
        else musicPlayer.stop();
    }

    private void persistAudioFlags() {
        var opts = GameOptions.load();
        try {
            GameOptions.save(
                    opts.autoFocusNextUnit(),
                    musicEnabled,
                    musicVolume,
                    sfxVolume,
                    sfxEnabled,
                    opts.musicApiBaseUrl(),
                    opts.musicApiSharedSecret(),
                    opts.anthropicApiKey(),
                    opts.anthropicApiUrl(),
                    opts.anthropicModel(),
                    opts.anthropicMaxTokens(),
                    opts.yearsPerFullRound(),
                    opts.graphicsSetFlair(),
                    opts.graphicsGenerationApiUrl(),
                    opts.graphicsGenerationApiKey(),
                    opts.showWeatherLegend(),
                    opts.showSettlerRecommendations(),
                    opts.showClaimLegend(),
                    opts.tipsDismissed(),
                    opts.minimapTintOwnClaimsOnly(),
                    opts.settleWeightFood(),
                    opts.settleWeightProduction(),
                    opts.settleWeightGold(),
                    opts.settleWeightTravel(),
                    opts.settleWeightRivalPressure(),
                    opts.windowLaunchMode());
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Could not persist audio flags", ex);
        }
    }

    private void toggleMusic() {
        musicEnabled = !musicEnabled;
        if (musicEnabled) musicPlayer.startLoop();
        else musicPlayer.stop();
        persistAudioFlags();
    }

    private void nextMusicTheme() {
        musicPlayer.skipToNextTheme();
    }

    private void toggleSfx() {
        sfxEnabled = !sfxEnabled;
        UiSoundEffects.setEnabled(sfxEnabled);
        persistAudioFlags();
    }

    private void adjustMusicVolume(int delta) {
        musicVolume = Math.max(0, Math.min(100, musicVolume + delta));
        musicPlayer.setVolume(musicVolume);
        persistAudioFlags();
    }

    private void onMusicVolumeLiveChange(int value) {
        musicVolume = Math.max(0, Math.min(100, value));
        musicPlayer.setVolume(musicVolume);
        persistAudioFlags();
    }

    private void onSfxVolumeLiveChange(int value) {
        sfxVolume = Math.max(0, Math.min(100, value));
        SfxPlayer.setVolumePercent(sfxVolume);
        persistAudioFlags();
    }
}

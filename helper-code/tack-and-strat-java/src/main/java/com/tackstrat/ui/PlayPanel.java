package com.tackstrat.ui;

import com.tackstrat.model.City;
import com.tackstrat.model.CityBuilding;
import com.tackstrat.model.CityFocus;
import com.tackstrat.model.GameSession;
import com.tackstrat.model.HexCoord;
import com.tackstrat.model.Player;
import com.tackstrat.model.Unit;
import com.tackstrat.model.WildAnimal;
import com.tackstrat.model.UnitKind;
import com.tackstrat.audio.SfxPlayer;
import com.tackstrat.persistence.UiSaveState;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;
import javax.swing.JLayeredPane;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.tackstrat.ai.SeatAi;
import com.tackstrat.perf.EdtProfiler;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-game screen: map with bottom-left selection HUD and optional east production drawer. */
final class PlayPanel extends JPanel {

    private static final int MAP_HUD_W = 320;
    private static final int MAP_HUD_MIN_H = 200;
    private static final int MAP_HUD_MAX_H = 360;
    private static final int PRODUCTION_DRAWER_W = 292;

    private final Runnable onMainMenu;
    private final Runnable onTurnEnded;
    private final Runnable onSaveGame;
    private final Runnable onToggleMusic;
    private final Runnable onNextTrack;
    private final Runnable onToggleSfx;
    private final Runnable onMusicVolumeUp;
    private final Runnable onMusicVolumeDown;
    private final BooleanSupplier isMusicEnabled;
    private final BooleanSupplier isSfxEnabled;
    private final IntSupplier musicVolume;
    private final IntSupplier sfxVolume;

    private final JLabel turnTitle = new JLabel(" ");
    /** Per-turn empire yields + treasury (Civ-style top ribbon). */
    private final JLabel yieldRibbon = new JLabel(" ");
    private final JLabel scoreLabel = new JLabel(" ");
    private final JLabel weatherHudLabel = new JLabel(" ");
    private final JLabel tileInfoLabel = new JLabel(" ");
    private final JButton menuButton = new JButton("\u2630");
    private final JLayeredPane mapLayered = new JLayeredPane();
    /** Bottom-left: selection details + primary / end turn (replaces former west sidebar). */
    private final JPanel mapHudPanel = new JPanel(new BorderLayout(0, 6));
    private final JPanel infoArea = new JPanel();
    private final JButton endTurnBtn = new JButton("End turn  (Enter)");
    private final JButton primaryActionBtn = new JButton("Primary action");
    private final HexMapPanel mapPanel;
    /** Map + optional east production drawer. */
    private final JPanel mapCenterHost = new JPanel(new BorderLayout());
    private final JPanel productionDrawer = new JPanel(new BorderLayout());
    private final JPanel productionDrawerInner = new JPanel();
    private final JTextArea eventLog = new JTextArea();

    private GameSession session;
    /** Batches rapid {@link #refresh()} calls into one bounds pass (map HUD alignment). */
    private final Timer layoutMapLayersDebounce;
    /** CPU seats: one tick at a time off-EDT, synchronized on {@link #session} with map paint. */
    private final ExecutorService aiTurnExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tackstrat-ai-turn");
        t.setDaemon(true);
        return t;
    });
    private boolean autoFocusNextUnit = false;
    private boolean autoAdvanceInProgress;
    /** Unit ids intentionally skipped for this turn without spending movement. */
    private final Set<Integer> skippedUnitsThisTurn = new HashSet<>();
    private int lastWildlifeArrivalRoundLogged = -1;
    private String primaryActionIcon = "◎";
    private String primaryActionLabel = "Select Unit";
    private String primaryActionDetail = "Select a unit or city to get context actions";

    PlayPanel(
            Runnable onMainMenu,
            Runnable onTurnEnded,
            Runnable onSaveGame,
            Runnable onToggleMusic,
            Runnable onNextTrack,
            Runnable onToggleSfx,
            Runnable onMusicVolumeUp,
            Runnable onMusicVolumeDown,
            BooleanSupplier isMusicEnabled,
            BooleanSupplier isSfxEnabled,
            IntSupplier musicVolume,
            IntSupplier sfxVolume) {
        super(new BorderLayout());
        this.onMainMenu = onMainMenu;
        this.onTurnEnded = onTurnEnded;
        this.onSaveGame = onSaveGame;
        this.onToggleMusic = onToggleMusic;
        this.onNextTrack = onNextTrack;
        this.onToggleSfx = onToggleSfx;
        this.onMusicVolumeUp = onMusicVolumeUp;
        this.onMusicVolumeDown = onMusicVolumeDown;
        this.isMusicEnabled = isMusicEnabled;
        this.isSfxEnabled = isSfxEnabled;
        this.musicVolume = musicVolume;
        this.sfxVolume = sfxVolume;

        mapPanel = new HexMapPanel(this::refreshPanels, this::appendEventLog, this::setTileInfo);

        productionDrawerInner.setLayout(new BoxLayout(productionDrawerInner, BoxLayout.Y_AXIS));
        productionDrawerInner.setOpaque(false);
        productionDrawer.setBackground(new Color(0xf2_f4_f8));
        productionDrawer.setPreferredSize(new Dimension(0, 0));
        productionDrawer.setVisible(false);
        productionDrawer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, UiTheme.SIDEBAR_LINE),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        var productionScroll = new JScrollPane(productionDrawerInner);
        productionScroll.setBorder(BorderFactory.createEmptyBorder());
        productionScroll.setOpaque(true);
        productionScroll.getViewport().setBackground(productionDrawer.getBackground());
        productionScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        productionDrawer.add(productionScroll, BorderLayout.CENTER);

        mapLayered.setLayout(null);
        mapLayered.add(mapPanel);
        mapLayered.setLayer(mapPanel, JLayeredPane.DEFAULT_LAYER);
        mapLayered.add(mapHudPanel);
        mapLayered.setLayer(mapHudPanel, JLayeredPane.PALETTE_LAYER);
        mapLayered.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutMapLayers();
            }
        });
        layoutMapLayersDebounce = new Timer(48, e -> layoutMapLayers());
        layoutMapLayersDebounce.setRepeats(false);

        buildMapHudPanel();
        mapCenterHost.add(mapLayered, BorderLayout.CENTER);

        var top = buildTopBar();
        var bottom = buildBottomArea();

        add(top, BorderLayout.NORTH);
        add(mapCenterHost, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        reloadUserSettings();
        installKeyBindings();
    }

    private void layoutMapLayers() {
        int w = mapLayered.getWidth();
        int h = mapLayered.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mapPanel.setBounds(0, 0, w, h);
        int hudH = Math.min(MAP_HUD_MAX_H, Math.max(MAP_HUD_MIN_H, h - 28));
        int margin = 14;
        mapHudPanel.setBounds(margin, h - hudH - margin, MAP_HUD_W, hudH);
    }

    /** Coalesces layout work during bursts of refreshes (e.g. fast AI turns). */
    private void requestLayoutMapLayers() {
        layoutMapLayersDebounce.restart();
    }

    private void buildMapHudPanel() {
        mapHudPanel.setOpaque(true);
        mapHudPanel.setBackground(new Color(20, 26, 36));
        mapHudPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(8, 11, 16)),
                        BorderFactory.createMatteBorder(1, 1, 0, 0, new Color(52, 60, 74))),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)));

        infoArea.setLayout(new BoxLayout(infoArea, BoxLayout.Y_AXIS));
        infoArea.setOpaque(false);
        var infoScroll = new JScrollPane(infoArea);
        infoScroll.setBorder(BorderFactory.createEmptyBorder());
        infoScroll.setOpaque(false);
        infoScroll.getViewport().setOpaque(true);
        infoScroll.getViewport().setBackground(new Color(20, 26, 36));
        infoScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        var bottomBox = new JPanel();
        bottomBox.setOpaque(false);
        bottomBox.setLayout(new BoxLayout(bottomBox, BoxLayout.Y_AXIS));
        bottomBox.add(separator());
        bottomBox.add(hudSectionLabel("Primary"));
        primaryActionBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        primaryActionBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        primaryActionBtn.setFocusable(false);
        primaryActionBtn.addActionListener(e -> onPrimaryAction());
        bottomBox.add(primaryActionBtn);
        bottomBox.add(Box.createVerticalStrut(8));

        endTurnBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        endTurnBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        endTurnBtn.addActionListener(e -> doEndTurn());
        bottomBox.add(endTurnBtn);

        mapHudPanel.add(infoScroll, BorderLayout.CENTER);
        mapHudPanel.add(bottomBox, BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        var top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.SIDEBAR_LINE),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        top.setBackground(Color.WHITE);

        turnTitle.setFont(turnTitle.getFont().deriveFont(Font.BOLD, 17f));
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(13f));
        scoreLabel.setForeground(new Color(0x55_5b_66));
        scoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        menuButton.setFocusable(false);
        menuButton.setFont(menuButton.getFont().deriveFont(Font.BOLD, 14f));
        var menuIcon = loadUiIcon("assets/graphics/ui_menu.png", 15);
        if (menuIcon != null) {
            menuButton.setText("");
            menuButton.setIcon(menuIcon);
        }
        menuButton.addActionListener(e -> showGameMenu());

        weatherHudLabel.setFont(weatherHudLabel.getFont().deriveFont(Font.PLAIN, 12f));
        weatherHudLabel.setForeground(new Color(0x3d_6b_9e));

        yieldRibbon.setHorizontalAlignment(SwingConstants.CENTER);
        yieldRibbon.setVerticalAlignment(SwingConstants.CENTER);
        yieldRibbon.setFont(yieldRibbon.getFont().deriveFont(Font.PLAIN, 12f));

        tileInfoLabel.setFont(tileInfoLabel.getFont().deriveFont(Font.PLAIN, 12f));
        tileInfoLabel.setForeground(new Color(0x4f_56_63));
        tileInfoLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));

        var right = new JPanel(new BorderLayout(8, 0));
        right.setOpaque(false);
        right.add(scoreLabel, BorderLayout.CENTER);
        right.add(menuButton, BorderLayout.EAST);

        var southStack = new JPanel();
        southStack.setOpaque(false);
        southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));
        southStack.add(weatherHudLabel);
        southStack.add(tileInfoLabel);

        top.add(turnTitle, BorderLayout.WEST);
        top.add(yieldRibbon, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);
        top.add(southStack, BorderLayout.SOUTH);
        return top;
    }

    private JPanel buildBottomArea() {
        eventLog.setEditable(false);
        eventLog.setLineWrap(true);
        eventLog.setWrapStyleWord(true);
        eventLog.setRows(2);
        eventLog.setFont(eventLog.getFont().deriveFont(12f));
        eventLog.setBackground(new Color(0xf5_f6_f8));
        eventLog.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        var logScroll = new JScrollPane(eventLog);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setPreferredSize(new Dimension(200, 58));

        var bottom = new JPanel(new BorderLayout());
        bottom.add(logScroll, BorderLayout.CENTER);
        return bottom;
    }

    private void installKeyBindings() {
        var keybinds = Keybinds.load();
        var im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        var am = getActionMap();
        im.clear();
        am.clear();

        bindAction(im, am, keybinds, Keybinds.END_TURN, "endTurn", e -> {
            if (mapPanel.isMoveCommandMode() && mapPanel.commitProjectedMove()) {
                refresh();
                return;
            }
            doEndTurn();
        });
        bindAction(im, am, keybinds, Keybinds.SKIP_ACTION, "skipAction", e -> onSkipAction());
        bindAction(im, am, keybinds, Keybinds.DESELECT, "deselect", e -> onEscapePressed());
        bindAction(im, am, keybinds, Keybinds.FOUND_CITY, "foundCity", e -> tryFoundCity());
        bindAction(im, am, keybinds, Keybinds.NEXT_UNIT, "nextUnit", e -> cycleToNextUnitNeedingOrders());
        bindAction(im, am, keybinds, Keybinds.FORTIFY, "fortify", e -> fortifySelectedUnit());
        bindAction(im, am, keybinds, Keybinds.MOVE_MODE, "moveMode", e -> {
            mapPanel.toggleMoveCommandMode();
            refreshPanels();
        });
        bindAction(im, am, keybinds, Keybinds.FIT_VIEW, "fitView", e -> mapPanel.fitToView());
        bindAction(im, am, keybinds, Keybinds.ZOOM_IN, "zoomIn", e -> mapPanel.zoomBy(1.2));
        bindAction(im, am, keybinds, Keybinds.ZOOM_OUT, "zoomOut", e -> mapPanel.zoomBy(1 / 1.2));
        bindAction(im, am, keybinds, Keybinds.PAN_LEFT, "panLeft", e -> onDirectionKey(-1, 0, 80, 0));
        bindAction(im, am, keybinds, Keybinds.PAN_RIGHT, "panRight", e -> onDirectionKey(1, 0, -80, 0));
        bindAction(im, am, keybinds, Keybinds.PAN_UP, "panUp", e -> onDirectionKey(0, -1, 0, 80));
        bindAction(im, am, keybinds, Keybinds.PAN_DOWN, "panDown", e -> onDirectionKey(0, 1, 0, -80));
        bindAction(im, am, keybinds, Keybinds.SAVE_GAME, "saveGame", e -> onSaveGame.run());
        bindAction(im, am, keybinds, Keybinds.TOGGLE_WEATHER_LEGEND, "toggleWeatherLegend", e -> toggleWeatherLegend());
        bindAction(im, am, keybinds, Keybinds.TOGGLE_SETTLER_LENS, "toggleSettlerLens", e -> toggleSettlerLens());
        bindAction(im, am, keybinds, Keybinds.SHOW_HOTKEYS, "showHotkeys", e -> {
            mapPanel.toggleHotkeysHelpOverlay();
            refreshPanels();
        });
        bindAction(im, am, keybinds, Keybinds.TOGGLE_AUTO_EXPLORE, "toggleAutoExplore", e -> toggleAutoExploreForSelectedUnit());
    }

    private void onDirectionKey(int dq, int dr, int panX, int panY) {
        if (mapPanel.isMoveCommandMode() && mapPanel.nudgeMoveCursor(dq, dr)) {
            refreshPanels();
            return;
        }
        mapPanel.panBy(panX, panY);
    }

    void reloadKeyBindings() {
        installKeyBindings();
    }

    void reloadUserSettings() {
        var opts = GameOptions.load();
        autoFocusNextUnit = opts.autoFocusNextUnit();
        mapPanel.setWeatherLegendVisible(opts.showWeatherLegend());
        mapPanel.setSettlerHintsVisible(opts.showSettlerRecommendations());
        mapPanel.setClaimLegendVisible(opts.showClaimLegend());
        mapPanel.setSettlerHintWeights(
                opts.settleWeightFood(),
                opts.settleWeightProduction(),
                opts.settleWeightGold(),
                opts.settleWeightTravel(),
                opts.settleWeightRivalPressure());
        mapPanel.setMinimapTintOwnClaimsOnly(opts.minimapTintOwnClaimsOnly());
        UiSoundEffects.setEnabled(opts.sfxEnabled());
        SfxPlayer.setVolumePercent(opts.sfxVolume());
    }

    private void toggleWeatherLegend() {
        boolean next = !mapPanel.isWeatherLegendVisible();
        mapPanel.setWeatherLegendVisible(next);
        try {
            var o = GameOptions.load();
            GameOptions.save(
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
                    next,
                    o.showSettlerRecommendations(),
                    o.showClaimLegend(),
                    o.tipsDismissed(),
                    o.minimapTintOwnClaimsOnly(),
                    o.settleWeightFood(),
                    o.settleWeightProduction(),
                    o.settleWeightGold(),
                    o.settleWeightTravel(),
                    o.settleWeightRivalPressure(),
                    o.windowLaunchMode());
        } catch (java.io.IOException ex) {
            mapPanel.setWeatherLegendVisible(!next);
            return;
        }
        mapPanel.repaint();
    }

    private void toggleSettlerLens() {
        boolean next = !mapPanel.isSettlerHintsVisible();
        mapPanel.setSettlerHintsVisible(next);
        try {
            var o = GameOptions.load();
            GameOptions.save(
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
                    next,
                    o.showClaimLegend(),
                    o.tipsDismissed(),
                    o.minimapTintOwnClaimsOnly(),
                    o.settleWeightFood(),
                    o.settleWeightProduction(),
                    o.settleWeightGold(),
                    o.settleWeightTravel(),
                    o.settleWeightRivalPressure(),
                    o.windowLaunchMode());
        } catch (java.io.IOException ex) {
            mapPanel.setSettlerHintsVisible(!next);
            return;
        }
        mapPanel.repaint();
    }

    private void toggleAutoUnitCycling() {
        boolean next = !autoFocusNextUnit;
        autoFocusNextUnit = next;
        try {
            var o = GameOptions.load();
            GameOptions.save(
                    next,
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
                    o.tipsDismissed(),
                    o.minimapTintOwnClaimsOnly(),
                    o.settleWeightFood(),
                    o.settleWeightProduction(),
                    o.settleWeightGold(),
                    o.settleWeightTravel(),
                    o.settleWeightRivalPressure(),
                    o.windowLaunchMode());
        } catch (java.io.IOException ex) {
            autoFocusNextUnit = !next;
            return;
        }
        mapPanel.showToast("Auto unit cycling: " + (autoFocusNextUnit ? "ON" : "OFF"));
        refreshPanels();
    }

    private void onEscapePressed() {
        if (session == null) return;
        if (mapPanel.isHotkeysHelpVisible()) {
            mapPanel.setHotkeysHelpVisible(false);
            refreshPanels();
            return;
        }
        if (mapPanel.isMoveCommandMode()) {
            mapPanel.setMoveCommandMode(false);
            refreshPanels();
            return;
        }
        if (mapPanel.selectedUnitId() >= 0 || mapPanel.selectedCityId() >= 0) {
            mapPanel.clearSelection();
            refreshPanels();
            return;
        }
        showGameMenu();
    }

    /** After saving Settings graphics assignments; does not reread other toggles. */
    void reloadGraphicAssetsAfterSettings() {
        mapPanel.reloadGraphicAssetsFromDisk();
    }

    private static void bindAction(javax.swing.InputMap im, javax.swing.ActionMap am,
                                   Keybinds keybinds, String action, String name, Consumer<ActionEvent> handler) {
        for (var ks : keybinds.keyStrokesFor(action)) {
            im.put(ks, name);
        }
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.accept(e);
            }
        });
    }

    void bind(GameSession session) {
        bind(session, UiSaveState.EMPTY);
    }

    void bind(GameSession session, UiSaveState uiSaveState) {
        this.session = session;
        this.lastWildlifeArrivalRoundLogged = -1;
        this.skippedUnitsThisTurn.clear();
        reloadUserSettings();
        mapPanel.bind(session, uiSaveState);
        eventLog.setText("");
        appendEventLog("World seed " + session.worldSeed() + " — combat and founding appear here.");
        setTileInfo("Hover tile for details");
        SwingUtilities.invokeLater(() -> {
            if (mapPanel.consumePendingCenterAfterBind()) {
                centerOnCurrentPlayer();
            }
            refresh();
            maybeShowIntroductionTips();
        });
    }

    private void maybeShowIntroductionTips() {
        try {
            if (GameOptions.load().tipsDismissed()) {
                return;
            }
            JOptionPane.showMessageDialog(
                    this,
                    "<html><body style='width:380px'>"
                            + "<b>Quick controls</b><br><br>"
                            + "Pan and zoom the map with drag, wheel, and arrow keys.<br>"
                            + "<b>Enter</b> ends your turn when nothing is pending.<br>"
                            + "<b>B</b> founds a city with a settler; production opens on the right when you select a city.<br>"
                            + "<b>Shift+E</b> toggles <b>auto-explore</b> for the selected unit (moves toward fog each turn).<br>"
                            + "<b>F1</b> lists all hotkeys.<br><br>"
                            + "Turn on “Show introduction tips when entering a match” in Settings → Gameplay to see this again."
                            + "</body></html>",
                    "Welcome",
                    JOptionPane.INFORMATION_MESSAGE);
            GameOptions.markTipsDismissed();
        } catch (java.io.IOException ignored) {
        }
    }

    private void toggleAutoExploreForSelectedUnit() {
        if (session == null || session.isOver()) return;
        int sid = mapPanel.selectedUnitId();
        if (sid < 0) return;
        session.unitById(sid).ifPresent(u -> {
            if (u.ownerSeat() != session.currentPlayer().seat()) return;
            boolean next = !u.autoExplore();
            session.setUnitAutoExplore(u.id(), next);
            skippedUnitsThisTurn.remove(u.id());
            appendEventLog(next ? "Auto-explore on for " + u.kind().displayName() + "."
                    : "Auto-explore off for " + u.kind().displayName() + ".");
            refresh();
        });
    }

    private void toggleSleepForSelectedUnit() {
        if (session == null || session.isOver()) return;
        int sid = mapPanel.selectedUnitId();
        if (sid < 0) return;
        session.unitById(sid).ifPresent(u -> {
            if (u.ownerSeat() != session.currentPlayer().seat()) return;
            boolean next = !u.sleeping();
            session.setUnitSleeping(u.id(), next);
            skippedUnitsThisTurn.remove(u.id());
            appendEventLog(next ? "Unit sleeping until manually woken."
                    : "Unit awakened.");
            refresh();
        });
    }

    UiSaveState exportUiSaveState() {
        return new UiSaveState(mapPanel.exportSeatCameras());
    }

    void appendLogLine(String line) {
        appendEventLog(line);
    }

    void onTurnBegan() {
        SwingUtilities.invokeLater(() -> {
            skippedUnitsThisTurn.clear();
            UiSoundEffects.turnAdvance();
            boolean restoredCamera = mapPanel.syncCameraToCurrentPlayer();
            if (!restoredCamera) {
                centerOnCurrentPlayer();
            }
            int spawned = session.wildlifeSpawnedLastStep();
            int spawnedRound = session.wildlifeSpawnedLastStepRound();
            if (session.currentPlayerIndex() == 0 && spawned > 0 && spawnedRound != lastWildlifeArrivalRoundLogged) {
                appendEventLog("Wildlife activity: " + spawned + " new animal"
                        + (spawned == 1 ? "" : "s") + " arrived this round.");
                lastWildlifeArrivalRoundLogged = spawnedRound;
            }
            refresh();
            mapPanel.showToast(turnSummary());
            if (autoFocusNextUnit) {
                if (currentPlayerHasCityWithoutProduction()) {
                    selectFirstCityMissingProduction();
                } else {
                    cycleToNextUnitNeedingOrders();
                }
            }
        });
    }

    private void doEndTurn() {
        if (session == null || session.isOver()) return;
        if (hasPendingUnitOrders()) {
            String msg = "Units still need orders. Skipping to next unit.";
            appendEventLog(msg);
            mapPanel.showToast(msg);
            UiSoundEffects.warning();
            cycleToNextUnitNeedingOrders();
            return;
        }
        if (currentPlayerHasCityWithoutProduction()) {
            String msg = "Choose production for: " + citiesMissingProductionList() + " (see panel on the right).";
            appendEventLog(msg);
            mapPanel.showToast(msg);
            UiSoundEffects.warning();
            selectFirstCityMissingProduction();
            return;
        }
        mapPanel.clearSelection();
        UiSoundEffects.success();
        // Run auto-explore when ending the turn (not at turn start) so manual movement can unblock traffic first.
        session.runAutoExploreForCurrentPlayer();
        session.endTurn();
        onTurnEnded.run();
    }

    private void onSkipAction() {
        if (session == null || session.isOver()) return;
        int sid = mapPanel.selectedUnitId();
        if (sid >= 0) {
            session.unitById(sid).ifPresent(u -> {
                if (u.ownerSeat() != session.currentPlayer().seat()) return;
                if (skippedUnitsThisTurn.contains(u.id())) {
                    skippedUnitsThisTurn.remove(u.id());
                    refresh();
                    return;
                }
                if (u.movesRemaining() > 0) {
                    skippedUnitsThisTurn.add(u.id());
                    refresh();
                }
            });
            return;
        }
        if (hasPendingUnitOrders()) {
            cycleToNextUnitNeedingOrders();
            return;
        }
        doEndTurn();
    }

    private void fortifySelectedUnit() {
        if (session == null) return;
        int sid = mapPanel.selectedUnitId();
        if (sid < 0) return;
        session.unitById(sid).ifPresent(u -> {
            if (u.ownerSeat() == session.currentPlayer().seat()) {
                u.exhaustMoves();
                refresh();
            }
        });
    }

    private void tryFoundCity() {
        if (session == null) return;
        int sid = mapPanel.selectedUnitId();
        if (sid < 0) return;
        session.unitById(sid).ifPresent(u -> {
            if (session.canFoundCity(u)) {
                session.foundCity(u.id()).ifPresent(c -> {
                    appendEventLog("Founded " + c.name() + ".");
                    UiSoundEffects.foundCity();
                    mapPanel.selectCityById(c.id());
                    mapPanel.centerOn(c.coord());
                    refresh();
                });
            } else if (u.kind() == UnitKind.SETTLER && u.ownerSeat() == session.currentPlayer().seat()) {
                session.explainCannotFoundCity(u).ifPresent(msg -> {
                    mapPanel.showToast(msg);
                    appendEventLog(msg);
                    UiSoundEffects.illegalAction();
                });
            }
        });
    }

    private void centerOnCurrentPlayer() {
        if (session == null) return;
        int seat = session.currentPlayer().seat();
        for (var u : session.units()) {
            if (u.ownerSeat() == seat) {
                mapPanel.centerOn(u.coord());
                return;
            }
        }
        for (var c : session.cities()) {
            if (c.ownerSeat() == seat) {
                mapPanel.centerOn(c.coord());
                return;
            }
        }
    }

    private void refresh() {
        if (session == null) return;
        long refreshProfileStart = System.nanoTime();
        try {
            synchronized (session) {
                var cur = session.currentPlayer();
                String cpuTag = cur.computer() ? " (CPU)" : "";
                turnTitle.setText(session.calendarEraLabel() + "  ·  " + session.season().label() + "  ·  Round "
                        + session.round() + "  ·  " + cur.name() + cpuTag + "'s turn");

                int empireFood = 0;
                int empireProd = 0;
                int empireGoldPerTurn = 0;
                for (City c : session.cities()) {
                    if (c.ownerSeat() != cur.seat()) {
                        continue;
                    }
                    var cy = session.cityYield(c);
                    empireFood += cy.food();
                    empireProd += cy.production();
                    empireGoldPerTurn += cy.gold();
                }
                int treasury = session.goldFor(cur.seat());
                yieldRibbon.setText(
                        "<html><div style='text-align:center'>"
                                + "<span style='color:#2a7a4a;font-weight:bold'>" + empireFood + "</span>"
                                + "<span style='color:#5a6672'> food</span> &nbsp; "
                                + "<span style='color:#a65f1a;font-weight:bold'>" + empireProd + "</span>"
                                + "<span style='color:#5a6672'> prod</span> &nbsp; "
                                + "<span style='color:#c4922a;font-weight:bold'>" + empireGoldPerTurn + "</span>"
                                + "<span style='color:#5a6672'> gold/turn</span> &nbsp; "
                                + "<span style='color:#9aa3b2'>|</span> &nbsp; "
                                + "<span style='color:#b8860b;font-weight:bold'>" + treasury + "</span>"
                                + "<span style='color:#5a6672'> treasury</span>"
                                + "</div></html>");

                var sb = new StringBuilder("<html>");
                for (var p : session.players()) {
                    int cities = session.cityCountFor(p.seat());
                    int units = session.unitCountFor(p.seat());
                    int gold = session.goldFor(p.seat());
                    String hex = hexColor(UiTheme.PLAYER[p.seat() % UiTheme.PLAYER.length]);
                    sb.append("<span style='color:").append(hex).append("'>■</span> ")
                            .append(p.name()).append(" — ").append(cities).append("c / ")
                            .append(units).append("u / ")
                            .append(gold).append("g&nbsp;&nbsp;&nbsp;");
                }
                sb.append("· first to ").append(GameSession.CITIES_TO_WIN).append(" cities wins</html>");
                scoreLabel.setText(sb.toString());
                int seat = cur.seat();
                int visibleWildlife = 0;
                for (WildAnimal a : session.wildlife()) {
                    if (session.visibleFor(seat).contains(a.coord())) {
                        visibleWildlife++;
                    }
                }
                weatherHudLabel.setText(session.weatherHudSummary() + "  ·  Wildlife in sight: " + visibleWildlife
                        + "  ·  Animals render only in line of sight (they may exist off-screen).");

                refreshPanels();
            }
        } finally {
            if (EdtProfiler.enabled()) {
                EdtProfiler.recordRefreshNanos(System.nanoTime() - refreshProfileStart);
            }
        }
        mapPanel.repaint();
        requestLayoutMapLayers();
    }

    private void refreshPanels() {
        refreshInfoPanel();
        refreshPrimaryActionUi();
        updateEndTurnUi();
        maybeAutoAdvanceActionQueue();
    }

    private void updateEndTurnUi() {
        if (session == null || session.isOver()) {
            endTurnBtn.setEnabled(false);
            return;
        }
        boolean unitsPending = hasPendingManualUnitOrders();
        boolean prodPending = currentPlayerHasCityWithoutProduction();
        endTurnBtn.setEnabled(!unitsPending && !prodPending);
        if (unitsPending) {
            endTurnBtn.setText("Units need orders (Enter blocked)");
        } else if (prodPending) {
            endTurnBtn.setText("City needs production (Enter blocked)");
        } else {
            endTurnBtn.setText("End turn  (Enter)");
        }
    }

    /** Units that still need manual orders (excludes idle auto-explore; see {@link #hasPendingUnitOrders}). */
    private boolean hasPendingManualUnitOrders() {
        if (session == null) return false;
        int seat = session.currentPlayer().seat();
        for (var u : session.units()) {
            if (u.ownerSeat() != seat) continue;
            if (u.sleeping()) continue;
            if (u.autoExplore()) continue;
            if (skippedUnitsThisTurn.contains(u.id())) continue;
            if (u.movesRemaining() <= 0) continue;
            if (!session.plannedRouteFor(u.id()).isEmpty()) continue;
            return true;
        }
        return false;
    }

    private boolean hasPendingUnitOrders() {
        return session != null && hasPendingManualUnitOrders();
    }

    /** Cities must have a current build (new cities start with none until the player picks). */
    private boolean currentPlayerHasCityWithoutProduction() {
        if (session == null) {
            return false;
        }
        int seat = session.currentPlayer().seat();
        for (City c : session.cities()) {
            if (c.ownerSeat() == seat && c.currentBuild() == null) {
                return true;
            }
        }
        return false;
    }

    private String citiesMissingProductionList() {
        if (session == null) {
            return "";
        }
        int seat = session.currentPlayer().seat();
        var names = new ArrayList<String>();
        for (City c : session.cities()) {
            if (c.ownerSeat() == seat && c.currentBuild() == null) {
                names.add(c.name());
            }
        }
        return String.join(", ", names);
    }

    private void selectFirstCityMissingProduction() {
        if (session == null) return;
        int seat = session.currentPlayer().seat();
        City pick = null;
        for (City c : session.cities()) {
            if (c.ownerSeat() == seat && c.currentBuild() == null) {
                if (pick == null || c.name().compareToIgnoreCase(pick.name()) < 0) {
                    pick = c;
                }
            }
        }
        if (pick != null) {
            mapPanel.selectCityById(pick.id());
            mapPanel.centerOn(pick.coord());
            refreshPanels();
        }
    }

    private void maybeAutoAdvanceActionQueue() {
        if (autoAdvanceInProgress || !autoFocusNextUnit || session == null) return;
        int sid = mapPanel.selectedUnitId();
        if (sid < 0) return;
        var opt = session.unitById(sid);
        if (opt.isEmpty()) return;
        var u = opt.get();
        if (u.ownerSeat() != session.currentPlayer().seat()) return;
        if (u.movesRemaining() > 0) return;
        autoAdvanceInProgress = true;
        try {
            cycleToNextUnitNeedingOrders();
        } finally {
            autoAdvanceInProgress = false;
        }
    }

    private void refreshInfoPanel() {
        infoArea.removeAll();
        if (session == null) {
            infoArea.revalidate();
            infoArea.repaint();
            return;
        }

        int unitId = mapPanel.selectedUnitId();
        int cityId = mapPanel.selectedCityId();

        if (unitId >= 0) {
            session.unitById(unitId).ifPresent(this::renderUnitInfo);
        } else if (cityId >= 0) {
            session.cityById(cityId).ifPresent(this::renderCityInfo);
        } else {
            infoArea.add(hudSectionLabel("Selection"));
            infoArea.add(hudHtmlLabel("<html><i>Select a unit or city to view details.</i></html>"));
            addCityNeedsProductionQuickJump();
        }

        infoArea.add(Box.createVerticalGlue());
        infoArea.revalidate();
        infoArea.repaint();
        syncProductionDrawer();
    }

    private void syncProductionDrawer() {
        productionDrawerInner.removeAll();
        if (session == null) {
            applyProductionDrawerVisible(false);
            return;
        }
        int cityId = mapPanel.selectedCityId();
        if (cityId < 0) {
            applyProductionDrawerVisible(false);
            return;
        }
        var cOpt = session.cityById(cityId);
        if (cOpt.isEmpty()) {
            applyProductionDrawerVisible(false);
            return;
        }
        City c = cOpt.get();
        if (c.ownerSeat() != session.currentPlayer().seat()) {
            applyProductionDrawerVisible(false);
            return;
        }
        applyProductionDrawerVisible(true);

        productionDrawerInner.add(sectionLabel("Production"));
        var cityLine = new JLabel(c.name());
        cityLine.setFont(cityLine.getFont().deriveFont(Font.BOLD, 14f));
        cityLine.setForeground(new Color(0x1a_1f_28));
        cityLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        productionDrawerInner.add(cityLine);
        productionDrawerInner.add(Box.createVerticalStrut(6));
        var hint = new JLabel("<html><body style='width:240px;color:#5a6270'>"
                + "Pick a unit to build. Queue more after the current item completes.</body></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        productionDrawerInner.add(hint);
        productionDrawerInner.add(Box.createVerticalStrut(12));

        if (c.currentBuild() != null) {
            int curTurns = turnsToComplete(c.currentBuild(), c.productionStored(), session.cityYield(c).production());
            var eta = new JLabel("Current ETA: " + curTurns + " turn" + (curTurns == 1 ? "" : "s"));
            eta.setForeground(new Color(0x5a_6270));
            eta.setAlignmentX(Component.LEFT_ALIGNMENT);
            productionDrawerInner.add(eta);
            productionDrawerInner.add(Box.createVerticalStrut(8));
        }

        if (!c.queuedBuilds().isEmpty()) {
            productionDrawerInner.add(sectionLabel("Queue"));
            var q = c.queuedBuilds();
            int etaTurns = c.currentBuild() == null ? 0
                    : turnsToComplete(c.currentBuild(), c.productionStored(), session.cityYield(c).production());
            for (int i = 0; i < q.size(); i++) {
                int qTurns = turnsToComplete(q.get(i), 0, session.cityYield(c).production());
                etaTurns += qTurns;
                productionDrawerInner.add(queueRow(c, i, q.get(i), q.size(), etaTurns));
            }
            productionDrawerInner.add(Box.createVerticalStrut(10));
        }

        productionDrawerInner.add(sectionLabel("Build"));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.SETTLER, "Settler"));
        productionDrawerInner.add(Box.createVerticalStrut(4));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.SCOUT, "Scout"));
        productionDrawerInner.add(Box.createVerticalStrut(4));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.WARRIOR, "Warrior"));
        productionDrawerInner.add(Box.createVerticalStrut(4));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.FARMER, "Farmer"));
        productionDrawerInner.add(Box.createVerticalStrut(4));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.BUILDER, "Builder"));
        productionDrawerInner.add(Box.createVerticalStrut(4));
        productionDrawerInner.add(buildCityActionButton(c, UnitKind.HUNTING_PARTY, "Hunting Party (-1 pop on spawn)"));

        productionDrawerInner.revalidate();
        productionDrawerInner.repaint();
    }

    private void applyProductionDrawerVisible(boolean on) {
        if (on) {
            productionDrawer.setPreferredSize(new Dimension(PRODUCTION_DRAWER_W, 0));
            productionDrawer.setMinimumSize(new Dimension(PRODUCTION_DRAWER_W, 0));
            if (productionDrawer.getParent() != mapCenterHost) {
                mapCenterHost.add(productionDrawer, BorderLayout.EAST);
            }
            productionDrawer.setVisible(true);
        } else {
            mapCenterHost.remove(productionDrawer);
            productionDrawer.setVisible(false);
            productionDrawer.setPreferredSize(new Dimension(0, 0));
            productionDrawer.setMinimumSize(new Dimension(0, 0));
        }
        mapCenterHost.revalidate();
        mapCenterHost.repaint();
        requestLayoutMapLayers();
    }

    private void renderUnitInfo(Unit u) {
        Player owner = session.playerBySeat(u.ownerSeat());
        int routeSteps = session.plannedRouteFor(u.id()).size();
        infoArea.add(hudSectionLabel("Selected Unit"));
        infoArea.add(unitCard(u));
        infoArea.add(Box.createVerticalStrut(8));
        infoArea.add(hudHtmlLabel("<html>"
                + "<b>Owner:</b> " + owner.name() + "<br>"
                + "<b>HP:</b> " + u.hp() + " / " + u.maxHp() + "<br>"
                + "<b>Moves:</b> " + u.movesRemaining() + " / " + u.kind().movement() + "<br>"
                + "<b>Sight:</b> " + u.kind().sightRadius() + "<br>"
                + "<b>Planned route:</b> " + (routeSteps > 0 ? routeSteps + " tiles queued" : "none") + "<br>"
                + "<b>Auto-explore:</b> " + (u.autoExplore() ? "on" : "off") + "<br>"
                + "<b>Sleep:</b> " + (u.sleeping() ? "on" : "off") + "<br>"
                + "<b>Skipped this turn:</b> " + (skippedUnitsThisTurn.contains(u.id()) ? "yes" : "no")
                + "</html>"));
        if (u.ownerSeat() == session.currentPlayer().seat()) {
            infoArea.add(Box.createVerticalStrut(6));
            infoArea.add(contextActionButton(
                    skippedUnitsThisTurn.contains(u.id()) ? "Unskip turn" : "Skip turn (not required this turn)",
                    true,
                    e -> {
                if (skippedUnitsThisTurn.contains(u.id())) {
                    skippedUnitsThisTurn.remove(u.id());
                } else if (u.movesRemaining() > 0) {
                    skippedUnitsThisTurn.add(u.id());
                }
                refresh();
            }));
            infoArea.add(contextActionButton(
                    u.sleeping() ? "Wake unit" : "Sleep unit (until manually woken)",
                    true,
                    e -> toggleSleepForSelectedUnit()));
            infoArea.add(contextActionButton(
                    u.autoExplore() ? "Turn off auto-explore" : "Turn on auto-explore (Shift+E)",
                    true,
                    e -> toggleAutoExploreForSelectedUnit()));
            addUnitWorldActions(u);
            if (u.kind() == UnitKind.SETTLER) {
                session.explainCannotFoundCity(u).ifPresent(msg -> {
                    infoArea.add(Box.createVerticalStrut(6));
                    infoArea.add(hudHtmlLabel("<html><span style='color:#f0a090'><b>Cannot found:</b> "
                            + escapeForHud(msg) + "</span></html>"));
                });
                String preview = mapPanel.settlePreviewSummaryForHovered();
                if (!preview.isBlank()) {
                    infoArea.add(Box.createVerticalStrut(8));
                    infoArea.add(hudSectionLabel("Found city preview"));
                    infoArea.add(hudHtmlLabel(preview));
                }
            }
        }
    }

    private static String escapeForHud(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void renderCityInfo(City c) {
        infoArea.add(hudSectionLabel("Selected City"));
        infoArea.add(cityCard(c));
        infoArea.add(Box.createVerticalStrut(8));

        UnitKind cur = c.currentBuild();
        String curName = cur == null ? "None" : cur.displayName();
        int cost = cur == null ? 0 : cur.productionCost();
        var y = session.cityYield(c);
        int upkeep = 1 + c.population();
        int surplus = y.food() - upkeep;
        int turnsToGrow = surplus <= 0 ? -1 : (Math.max(0, c.growthThreshold() - c.foodStored()) + surplus - 1) / surplus;

        infoArea.add(hudHtmlLabel("<html>"
                + "<b>Current build:</b> " + curName + "<br>"
                + "<b>Focus:</b> " + c.focus().name().toLowerCase() + "<br>"
                + "<b>Population:</b> " + c.population()
                + (c.huntPartiesAway() > 0 ? " (" + c.huntPartiesAway() + " hunting)" : "") + "<br>"
                + "<b>Yields:</b> +" + y.food() + " food · +" + y.production() + " prod · +" + y.gold() + " gold<br>"
                + "<b>Food:</b> " + c.foodStored() + " / " + c.growthThreshold() + " to grow"
                + (turnsToGrow < 0 ? " (stagnant)" : " (" + turnsToGrow + "t)")
                + "</html>"));
        session.explainFoodStagnation(c).ifPresent(ex -> {
            infoArea.add(Box.createVerticalStrut(4));
            infoArea.add(hudHtmlLabel("<html><span style='color:#c9b28a;font-size:11px'>" + escapeForHud(ex) + "</span></html>"));
        });
        infoArea.add(Box.createVerticalStrut(6));

        var bar = new JProgressBar(0, Math.max(1, cost));
        bar.setValue(Math.min(c.productionStored(), Math.max(1, cost)));
        bar.setStringPainted(true);
        bar.setString(cur == null
                ? "Choose production"
                : cur.displayName() + ": " + c.productionStored() + " / " + cost);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        infoArea.add(bar);

        boolean isMine = c.ownerSeat() == session.currentPlayer().seat();
        if (isMine) {
            infoArea.add(Box.createVerticalStrut(8));
            infoArea.add(hudSectionLabel("City Focus"));
            infoArea.add(focusButton(c, CityFocus.BALANCED, "Balanced"));
            infoArea.add(focusButton(c, CityFocus.FOOD, "Food"));
            infoArea.add(focusButton(c, CityFocus.PRODUCTION, "Production"));
            infoArea.add(focusButton(c, CityFocus.GOLD, "Gold"));
            infoArea.add(Box.createVerticalStrut(8));
            infoArea.add(hudSectionLabel("Buildings"));
            infoArea.add(buildingButton(c, CityBuilding.GRANARY));
            infoArea.add(buildingButton(c, CityBuilding.WORKSHOP));
            infoArea.add(buildingButton(c, CityBuilding.MARKET));
            infoArea.add(Box.createVerticalStrut(8));
            infoArea.add(hudHtmlLabel("<html><span style='color:#aeb8c9'>Production choices are on the <b>right</b> when this city is selected.</span></html>"));
        }
        if (!isMine) return;
    }

    private void addUnitWorldActions(Unit u) {
        if (u.kind() == UnitKind.FARMER) {
            infoArea.add(Box.createVerticalStrut(6));
            infoArea.add(hudSectionLabel("Tile work"));
            infoArea.add(contextActionButton("Cultivate (+farming tier)", u.movesRemaining() > 0, e -> {
                session.tryCultivateTile(u.id()).ifPresent(msg -> {
                    presentActionFeedback(msg);
                    refresh();
                });
            }));
            infoArea.add(contextActionButton("Clear forest → grass", u.movesRemaining() > 0, e -> {
                session.tryClearForest(u.id()).ifPresent(msg -> {
                    presentActionFeedback(msg);
                    refresh();
                });
            }));
            infoArea.add(contextActionButton("Build farm", u.movesRemaining() > 0, e -> {
                session.tryBuildFarmImprovement(u.id()).ifPresent(msg -> {
                    presentActionFeedback(msg);
                    refresh();
                });
            }));
        } else if (u.kind() == UnitKind.BUILDER) {
            infoArea.add(Box.createVerticalStrut(6));
            infoArea.add(hudSectionLabel("Tile work"));
            infoArea.add(contextActionButton("Build mine (hill tile)", u.movesRemaining() > 0, e -> {
                session.tryBuildMineImprovement(u.id()).ifPresent(msg -> {
                    presentActionFeedback(msg);
                    refresh();
                });
            }));
        } else if (u.kind() == UnitKind.HUNTING_PARTY) {
            infoArea.add(Box.createVerticalStrut(6));
            infoArea.add(hudSectionLabel("Hunting Party"));
            infoArea.add(hudHtmlLabel("<html><span style='color:#cfd7e6'>Carried food: <b>"
                    + u.carriedFood() + "</b></span></html>"));
            infoArea.add(contextActionButton("Rebase at city (retire, +1 pop, unload food)", true, e -> {
                session.tryRebaseHuntingParty(u.id()).ifPresent(msg -> {
                    presentActionFeedback(msg);
                    refresh();
                });
            }));
            var beastOpt = session.wildAnimalAt(u.coord());
            if (beastOpt.isPresent()) {
                WildAnimal beast = beastOpt.get();
                infoArea.add(Box.createVerticalStrut(4));
                infoArea.add(contextActionButton(
                        "Hunt on tile: " + beast.kind().label() + " #" + beast.id(),
                        u.movesRemaining() > 0,
                        e -> session.tryHuntWildlife(u.id(), beast.id(), u.coord()).ifPresent(msg -> {
                            presentActionFeedback(msg);
                            refresh();
                        })));
            }
        }
    }

    private void addCityNeedsProductionQuickJump() {
        if (session == null) return;
        int seat = session.currentPlayer().seat();
        var lacking = new ArrayList<City>();
        for (City c : session.cities()) {
            if (c.ownerSeat() == seat && c.currentBuild() == null) {
                lacking.add(c);
            }
        }
        if (lacking.isEmpty()) return;
        lacking.sort(Comparator.comparing(City::name));
        infoArea.add(Box.createVerticalStrut(8));
        infoArea.add(hudSectionLabel("Cities need production"));
        for (City c : lacking) {
            var b = new JButton("Choose for " + c.name());
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            b.setFocusable(false);
            b.addActionListener(e -> {
                UiSoundEffects.click();
                mapPanel.selectCityById(c.id());
                mapPanel.centerOn(c.coord());
                refresh();
            });
            infoArea.add(b);
            infoArea.add(Box.createVerticalStrut(4));
        }
    }

    private JButton contextActionButton(String title, boolean enabled, Consumer<ActionEvent> onClick) {
        var b = new JButton(title);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        b.setFocusable(false);
        b.setEnabled(enabled && session != null && !session.isOver());
        b.addActionListener(e -> {
            UiSoundEffects.click();
            onClick.accept(e);
        });
        return b;
    }

    private void refreshPrimaryActionUi() {
        primaryActionIcon = "◎";
        primaryActionLabel = "Select unit";
        primaryActionDetail = "No selection — click a unit/city, or press N for next unit";

        if (session == null) {
            primaryActionBtn.setEnabled(false);
            primaryActionBtn.setText("Primary action");
            primaryActionBtn.setToolTipText(null);
            return;
        }
        if (mapPanel.isMoveCommandMode()) {
            primaryActionIcon = "➤";
            primaryActionLabel = "Commit move";
            primaryActionDetail = "Arrows adjust route, Enter confirms projected move";
        } else {
            int unitId = mapPanel.selectedUnitId();
            int cityId = mapPanel.selectedCityId();
            if (unitId >= 0) session.unitById(unitId).ifPresent(this::setPrimaryForUnit);
            else if (cityId >= 0) session.cityById(cityId).ifPresent(this::setPrimaryForCity);
        }
        primaryActionBtn.setText(primaryActionIcon + " " + primaryActionLabel);
        primaryActionBtn.setToolTipText(primaryActionDetail);
        primaryActionBtn.setEnabled(session != null && !session.isOver());
    }

    private void setPrimaryForUnit(Unit u) {
        if (u.ownerSeat() != session.currentPlayer().seat()) {
            primaryActionIcon = " ";
            primaryActionLabel = "Enemy unit";
            primaryActionDetail = "Enemy selected — inspect only";
            return;
        }
        if (u.kind() == UnitKind.SETTLER) {
            if (session.canFoundCity(u)) {
                primaryActionIcon = "🏛";
                primaryActionLabel = "Found city";
                primaryActionDetail = "This settler can found here (B)";
                return;
            }
            primaryActionIcon = "🏛";
            primaryActionLabel = "Found city";
            primaryActionDetail = session.explainCannotFoundCity(u).orElse("Cannot found here.");
            return;
        }
        if (!session.plannedRouteFor(u.id()).isEmpty()) {
            primaryActionIcon = "➤";
            primaryActionLabel = "Follow route";
            primaryActionDetail = "Route queued — press primary action to continue";
            return;
        }
        if (u.sleeping()) {
            primaryActionIcon = "☾";
            primaryActionLabel = "Wake unit";
            primaryActionDetail = "Sleeping units ignore orders until manually woken";
            return;
        }
        primaryActionIcon = "➤";
        primaryActionLabel = "Move mode";
        primaryActionDetail = "Press primary action (or M), then use arrows and Enter";
        if (u.autoExplore()) {
            primaryActionDetail += " · Auto-explore will move this unit when you end turn";
        }
    }

    private void setPrimaryForCity(City c) {
        if (c.ownerSeat() != session.currentPlayer().seat()) {
            primaryActionIcon = " ";
            primaryActionLabel = "Enemy city";
            primaryActionDetail = "Foreign city selected — inspect only";
            return;
        }
        primaryActionIcon = "⚒";
        primaryActionLabel = "Choose production";
        primaryActionDetail = "Use the production panel on the right";
    }

    private void onPrimaryAction() {
        if (session == null || session.isOver()) return;
        UiSoundEffects.click();
        if (mapPanel.isMoveCommandMode()) {
            if (mapPanel.commitProjectedMove()) {
                UiSoundEffects.success();
                refresh();
            }
            return;
        }
        int unitId = mapPanel.selectedUnitId();
        if (unitId >= 0) {
            session.unitById(unitId).ifPresent(u -> {
                if (u.ownerSeat() != session.currentPlayer().seat()) return;
                if (u.sleeping()) {
                    session.setUnitSleeping(u.id(), false);
                    skippedUnitsThisTurn.remove(u.id());
                    appendEventLog("Unit awakened.");
                    refresh();
                    return;
                }
                if (u.kind() == UnitKind.SETTLER && session.canFoundCity(u)) {
                    session.foundCity(u.id()).ifPresent(c -> {
                        appendEventLog("Founded " + c.name() + ".");
                        UiSoundEffects.foundCity();
                        mapPanel.selectCityById(c.id());
                        mapPanel.centerOn(c.coord());
                        refresh();
                    });
                    return;
                }
                if (u.kind() == UnitKind.SETTLER) {
                    session.explainCannotFoundCity(u).ifPresent(msg -> {
                        mapPanel.showToast(msg);
                        UiSoundEffects.illegalAction();
                    });
                    return;
                }
                if (!session.plannedRouteFor(u.id()).isEmpty()) {
                    session.followPlannedRoute(u.id());
                    UiSoundEffects.success();
                    refresh();
                    return;
                }
                mapPanel.setMoveCommandMode(true);
                refreshPanels();
            });
            return;
        }
        int cityId = mapPanel.selectedCityId();
        if (cityId >= 0
                && session.cityById(cityId).filter(c -> c.ownerSeat() == session.currentPlayer().seat()).isPresent()) {
            return;
        }
        cycleToNextUnitNeedingOrders();
    }

    private String turnSummary() {
        if (session == null) return "";
        int seat = session.currentPlayer().seat();
        int readyUnits = 0;
        int routedUnits = 0;
        int food = 0, prod = 0, gold = 0;
        int citiesOwned = 0;
        for (var u : session.units()) {
            if (u.ownerSeat() != seat) continue;
            if (u.movesRemaining() > 0) readyUnits++;
            if (!session.plannedRouteFor(u.id()).isEmpty()) routedUnits++;
        }
        for (var c : session.cities()) {
            if (c.ownerSeat() != seat) continue;
            citiesOwned++;
            var y = session.cityYield(c);
            food += y.food();
            prod += y.production();
            gold += y.gold();
        }
        String prodHint = "";
        for (var c : session.cities()) {
            if (c.ownerSeat() != seat) continue;
            var cur = c.currentBuild();
            if (cur != null) {
                prodHint = " · queue: " + cur.displayName()
                        + " (" + c.productionStored() + "/" + cur.productionCost() + ")";
                break;
            }
        }
        return "Turn start: " + readyUnits + " units ready"
                + (routedUnits > 0 ? " (" + routedUnits + " routed)" : "")
                + " · " + citiesOwned + " cities · yields +" + food + "f +" + prod + "p +" + gold + "g"
                + prodHint
                + " · " + session.calendarEraLabel()
                + " · " + session.season().label();
    }

    private void showGameMenu() {
        var menu = new JPopupMenu();
        var save = new JMenuItem("Save game...");
        save.addActionListener(e -> {
            UiSoundEffects.click();
            onSaveGame.run();
        });
        menu.add(save);

        var next = new JMenuItem("Next unit");
        next.addActionListener(e -> {
            UiSoundEffects.click();
            cycleToNextUnitNeedingOrders();
        });
        menu.add(next);

        var end = new JMenuItem("End turn");
        end.addActionListener(e -> {
            UiSoundEffects.click();
            doEndTurn();
        });
        menu.add(end);

        var autoCycle = new JMenuItem("Auto unit cycling: " + (autoFocusNextUnit ? "On (toggle)" : "Off (toggle)"));
        autoCycle.addActionListener(e -> {
            UiSoundEffects.click();
            toggleAutoUnitCycling();
        });
        menu.add(autoCycle);

        menu.addSeparator();
        var music = new JMenuItem(isMusicEnabled.getAsBoolean() ? "Music: On (toggle)" : "Music: Off (toggle)");
        music.addActionListener(e -> {
            onToggleMusic.run();
            refreshPanels();
        });
        menu.add(music);

        var volDown = new JMenuItem("Music volume -");
        volDown.addActionListener(e -> onMusicVolumeDown.run());
        menu.add(volDown);

        var volUp = new JMenuItem("Music volume +");
        volUp.addActionListener(e -> onMusicVolumeUp.run());
        menu.add(volUp);

        var volRead = new JMenuItem("Music volume: " + musicVolume.getAsInt() + "%");
        volRead.setEnabled(false);
        menu.add(volRead);

        var nextTrack = new JMenuItem("Next ambience");
        nextTrack.addActionListener(e -> onNextTrack.run());
        menu.add(nextTrack);

        var sfx = new JMenuItem(isSfxEnabled.getAsBoolean() ? "SFX: On (toggle)" : "SFX: Off (toggle)");
        sfx.addActionListener(e -> onToggleSfx.run());
        menu.add(sfx);

        var sfxVolRead = new JMenuItem("SFX volume: " + sfxVolume.getAsInt() + "%");
        sfxVolRead.setEnabled(false);
        menu.add(sfxVolRead);

        menu.addSeparator();
        var main = new JMenuItem("Main menu");
        main.addActionListener(e -> {
            UiSoundEffects.click();
            onMainMenu.run();
        });
        menu.add(main);

        menu.show(menuButton, 0, menuButton.getHeight());
    }

    private JButton buildCityActionButton(City c, UnitKind kind, String title) {
        int turns = turnsToComplete(kind, c.productionStored(), session.cityYield(c).production());
        boolean active = c.currentBuild() == kind;
        boolean canBuild = kind != UnitKind.HUNTING_PARTY || c.population() >= 2;
        var b = new JButton(title + " (" + turns + "t)");
        b.setFocusable(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        b.setEnabled(!active && canBuild);
        b.addActionListener(e -> {
            UiSoundEffects.click();
            if (!session.setCityProduction(c.id(), kind)) {
                presentActionFeedback("Need at least 2 population to train a Hunting Party.");
            }
            mapPanel.clearSelection();
            cycleToNextUnitNeedingOrders();
            refresh();
        });
        if (active) {
            b.setText(title + " (Active)");
        } else if (!canBuild) {
            b.setText(title + " (need 2 pop)");
        }
        return b;
    }

    private JPanel queueRow(City c, int idx, UnitKind kind, int size, int etaTurns) {
        var row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        var label = new JLabel((idx + 1) + ". " + kind.displayName() + "  (~t+" + etaTurns + ")");
        label.setFont(label.getFont().deriveFont(12f));

        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        controls.setOpaque(false);
        var up = miniQueueButton("↑", e -> {
            session.moveCityQueuedProduction(c.id(), idx, -1);
            refresh();
        });
        up.setEnabled(idx > 0);
        var down = miniQueueButton("↓", e -> {
            session.moveCityQueuedProduction(c.id(), idx, +1);
            refresh();
        });
        down.setEnabled(idx < size - 1);
        var rm = miniQueueButton("×", e -> {
            session.removeCityQueuedProduction(c.id(), idx);
            refresh();
        });

        controls.add(up);
        controls.add(down);
        controls.add(rm);

        row.add(label, BorderLayout.WEST);
        row.add(controls, BorderLayout.EAST);
        return row;
    }

    private JButton miniQueueButton(String text, Consumer<ActionEvent> action) {
        var b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(1, 6, 1, 6));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 10f));
        b.addActionListener(e -> {
            UiSoundEffects.click();
            action.accept(e);
        });
        return b;
    }

    private int turnsToComplete(UnitKind kind, int carriedProduction, int prodPerTurn) {
        int remain = Math.max(0, kind.productionCost() - Math.max(0, carriedProduction));
        int per = Math.max(1, prodPerTurn);
        return Math.max(1, (remain + per - 1) / per);
    }

    private JButton focusButton(City c, CityFocus focus, String label) {
        var b = new JButton("Focus: " + label + (c.focus() == focus ? " (Active)" : ""));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        b.setFocusable(false);
        b.setEnabled(c.focus() != focus);
        b.setToolTipText(switch (focus) {
            case BALANCED -> "Balanced tile weighting: no strong bias.";
            case FOOD -> "Prioritize food-heavy tiles for faster growth.";
            case PRODUCTION -> "Prioritize production-heavy tiles for faster builds.";
            case GOLD -> "Prioritize gold-heavy tiles for stronger economy.";
        });
        b.addActionListener(e -> session.setCityFocus(c.id(), focus).ifPresent(msg -> {
            presentActionFeedback(msg);
            refresh();
        }));
        return b;
    }

    private JButton buildingButton(City c, CityBuilding building) {
        boolean owned = c.hasBuilding(building);
        int seatGold = session.goldFor(session.currentPlayer().seat());
        boolean canBuy = !owned && seatGold >= building.goldCost();
        var b = new JButton(building.label() + (owned ? " (Built)" : " (" + building.goldCost() + "g)"));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        b.setFocusable(false);
        b.setEnabled(canBuy);
        String effect = switch (building) {
            case GRANARY -> "Effect: +1 food, and -2 growth threshold.";
            case WORKSHOP -> "Effect: +1 production.";
            case MARKET -> "Effect: +1 gold.";
        };
        if (owned) b.setToolTipText(effect + " Already built.");
        else if (!canBuy) b.setToolTipText(effect + " Need " + building.goldCost() + " gold.");
        else b.setToolTipText(effect + " Costs " + building.goldCost() + " gold.");
        b.addActionListener(e -> session.tryConstructCityBuilding(c.id(), building).ifPresent(msg -> {
            presentActionFeedback(msg);
            refresh();
        }));
        return b;
    }

    private void cycleToNextUnitNeedingOrders() {
        if (session == null) return;
        int seat = session.currentPlayer().seat();
        var mine = session.units().stream().filter(u -> u.ownerSeat() == seat).toList();
        if (mine.isEmpty()) return;

        int cur = mapPanel.selectedUnitId();
        int start = 0;
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).id() == cur) {
                start = (i + 1) % mine.size();
                break;
            }
        }

        Unit pick = null;
        for (int k = 0; k < mine.size(); k++) {
            var u = mine.get((start + k) % mine.size());
            if (u.sleeping()) continue;
            if (skippedUnitsThisTurn.contains(u.id())) continue;
            if (session.autoExploreBlockedWithMoves(u)) {
                pick = u;
                break;
            }
        }
        if (pick == null) {
            for (int k = 0; k < mine.size(); k++) {
                var u = mine.get((start + k) % mine.size());
                if (u.sleeping()) continue;
                if (u.autoExplore()) continue;
                if (skippedUnitsThisTurn.contains(u.id())) continue;
                boolean hasMoves = u.movesRemaining() > 0;
                boolean hasRoute = !session.plannedRouteFor(u.id()).isEmpty();
                if (hasMoves && !hasRoute) {
                    pick = u;
                    break;
                }
            }
        }
        if (pick == null) {
            for (int k = 0; k < mine.size(); k++) {
                var u = mine.get((start + k) % mine.size());
                if (u.sleeping()) continue;
                if (u.autoExplore()) continue;
                if (skippedUnitsThisTurn.contains(u.id())) continue;
                if (u.movesRemaining() > 0) {
                    pick = u;
                    break;
                }
            }
        }
        if (pick != null) {
            mapPanel.selectUnitById(pick.id());
            mapPanel.centerOn(pick.coord());
            refreshPanels();
        }
    }

    private void appendEventLog(String line) {
        eventLog.append(line + "\n");
        trimEventLog();
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    /** Context action feedback should be visible immediately on-map, not just in the log. */
    private void presentActionFeedback(String msg) {
        if (msg == null || msg.isBlank()) {
            return;
        }
        appendEventLog(msg);
        mapPanel.showToast(msg);
        if (isLikelyActionFailure(msg)) {
            UiSoundEffects.illegalAction();
        } else {
            UiSoundEffects.success();
        }
    }

    private static boolean isLikelyActionFailure(String msg) {
        String m = msg.toLowerCase();
        return m.contains("no moves")
                || m.contains("cannot")
                || m.contains("already")
                || m.contains("only")
                || m.contains("need ")
                || m.contains("too far")
                || m.contains("no longer")
                || m.contains("invalid");
    }

    private void setTileInfo(String line) {
        tileInfoLabel.setText(line == null || line.isBlank() ? "Hover tile for details" : line);
    }

    private static final int EVENT_LOG_MAX_LINES = 48;

    private void trimEventLog() {
        try {
            while (eventLog.getLineCount() > EVENT_LOG_MAX_LINES) {
                int end = eventLog.getLineEndOffset(0);
                eventLog.getDocument().remove(0, end);
            }
        } catch (BadLocationException ignored) {
        }
    }

    private static JComponent separator() {
        var line = new JPanel();
        line.setBackground(UiTheme.SIDEBAR_LINE);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setPreferredSize(new Dimension(10, 1));
        var wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        wrap.add(line, BorderLayout.CENTER);
        return wrap;
    }

    private static String hexColor(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static ImageIcon loadUiIcon(String path, int size) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) return null;
            var img = javax.imageio.ImageIO.read(p.toFile());
            if (img == null) return null;
            return new ImageIcon(img.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH));
        } catch (Exception ex) {
            return null;
        }
    }

    private JPanel unitCard(Unit u) {
        var card = new JPanel(new BorderLayout(8, 0));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setOpaque(true);
        card.setBackground(new Color(0xea_ed_f2));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xc9_cf_d8), 1),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        var badge = new JLabel(shortKind(u.kind()), SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(new Color(0x2f_3f_57));
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 12f));
        badge.setPreferredSize(new Dimension(38, 32));

        var txt = new JLabel("<html><b>" + u.kind().displayName() + "</b><br>"
                + "HP " + u.hp() + "/" + u.maxHp() + " · MV " + u.movesRemaining() + "</html>");
        txt.setForeground(new Color(0x21_26_31));

        card.add(badge, BorderLayout.WEST);
        card.add(txt, BorderLayout.CENTER);
        return card;
    }

    private JPanel cityCard(City c) {
        var card = new JPanel(new BorderLayout(8, 0));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setOpaque(true);
        card.setBackground(new Color(0xea_ed_f2));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xc9_cf_d8), 1),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        var badge = new JLabel("CTY", SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(new Color(0x5a_43_2e));
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 12f));
        badge.setPreferredSize(new Dimension(38, 32));

        UnitKind cur = c.currentBuild();
        String curTxt = cur == null ? "None" : cur.displayName();
        var txt = new JLabel("<html><b>" + c.name() + "</b><br>Build: " + curTxt + "</html>");
        txt.setForeground(new Color(0x21_26_31));

        card.add(badge, BorderLayout.WEST);
        card.add(txt, BorderLayout.CENTER);
        return card;
    }

    private static String shortKind(UnitKind k) {
        return switch (k) {
            case SETTLER -> "SET";
            case SCOUT -> "SCT";
            case WARRIOR -> "WAR";
            case FARMER -> "FRM";
            case BUILDER -> "BLD";
			case HUNTING_PARTY -> "HNT";
        };
    }

    private JLabel sectionLabel(String text) {
        var lab = new JLabel(text);
        lab.setFont(lab.getFont().deriveFont(Font.BOLD, 13f));
        lab.setForeground(new Color(0x38_3d_46));
        lab.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lab;
    }

    private JLabel hudSectionLabel(String text) {
        var lab = new JLabel(text);
        lab.setFont(lab.getFont().deriveFont(Font.BOLD, 13f));
        lab.setForeground(new Color(205, 212, 224));
        lab.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lab;
    }

    private JLabel htmlLabel(String html) {
        var l = new JLabel(html);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel hudHtmlLabel(String html) {
        var l = new JLabel(html);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setForeground(new Color(215, 220, 232));
        return l;
    }

    /**
     * Runs an automated turn for {@link Player#computer()} seats: production, combat, moves, then
     * {@link GameSession#endTurn()} when idle. Invokes {@code whenAdvanced} after each turn advance
     * (including handoff routing for the next human).
     *
     * <p>Work runs on a single background thread ({@link #aiTurnExecutor}) with {@code synchronized (session)} so
     * map painting and refresh stay consistent; results are applied on the EDT via {@link #refresh()}.
     */
    void runComputerTurn(Runnable whenAdvanced) {
        if (session == null || session.isOver()) {
            whenAdvanced.run();
            return;
        }
        if (!session.currentPlayer().computer()) {
            whenAdvanced.run();
            return;
        }
        long salt = session.worldSeed() ^ ((long) session.round() << 16) ^ ((long) session.currentPlayerIndex() << 24);
        var rng = new Random(salt);
        scheduleComputerTurnWork(rng, whenAdvanced);
    }

    private void scheduleComputerTurnWork(Random rng, Runnable whenAdvanced) {
        final GameSession tickSession = session;
        if (tickSession == null) {
            SwingUtilities.invokeLater(whenAdvanced);
            return;
        }
        aiTurnExecutor.execute(() -> {
            try {
                Thread.sleep(42);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(whenAdvanced);
                return;
            }
            boolean cont = false;
            boolean staleSession;
            synchronized (tickSession) {
                staleSession = this.session != tickSession || tickSession.isOver();
                if (!staleSession && !tickSession.currentPlayer().computer()) {
                    staleSession = true;
                }
                if (!staleSession) {
                    long t0 = System.nanoTime();
                    cont = SeatAi.tick(tickSession, rng);
                    if (EdtProfiler.enabled()) {
                        EdtProfiler.recordSeatAiTickNanos(System.nanoTime() - t0);
                    }
                    if (!cont) {
                        tickSession.endTurn();
                    }
                }
            }
            final boolean stale = staleSession;
            final boolean keepCpuTurn = cont;
            SwingUtilities.invokeLater(() -> {
                if (this.session != tickSession) {
                    whenAdvanced.run();
                    return;
                }
                if (stale || this.session == null || this.session.isOver()) {
                    whenAdvanced.run();
                    return;
                }
                refresh();
                if (this.session.isOver() || !this.session.currentPlayer().computer()) {
                    whenAdvanced.run();
                    return;
                }
                if (keepCpuTurn) {
                    scheduleComputerTurnWork(rng, whenAdvanced);
                } else {
                    whenAdvanced.run();
                }
            });
        });
    }
}

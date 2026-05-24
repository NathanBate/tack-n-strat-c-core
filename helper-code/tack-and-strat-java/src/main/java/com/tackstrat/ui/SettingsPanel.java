package com.tackstrat.ui;

import com.tackstrat.graphics.GraphicResolvedAsset;
import com.tackstrat.graphics.GraphicRuntime;
import com.tackstrat.graphics.GraphicSlotUploads;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JFileChooser;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.ScrollPaneConstants;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Settings screen with gameplay options, audio configuration, and keybinding remapping. */
final class SettingsPanel extends JPanel {

    private static final Color INK_PRIMARY = new Color(0xe1_e6_ef);
    private static final Color INK_MUTED = new Color(0x93_9a_a6);
    private static final Color INK_SECTION = new Color(0xa9_d4_ff);
    private static final Color FIELD_BG = new Color(0x16_1d_2a);
    private static final Color FIELD_BORDER = new Color(0x2f_38_46);
    /** Slightly lifted surface for element workspace card */
    private static final Color GRAPHIC_CARD_SURFACE = new Color(0x11_17_22);
    private static final Color GRAPHIC_TILE_HOVER_BORDER = new Color(0x45_6a_8a);
    private static final int GRAPHIC_PREVIEW_MAX_W = 240;
    private static final int GRAPHIC_PREVIEW_MAX_H = 150;
    private static final int GRAPHIC_LIBRARY_THUMB_MAX = 40;
    /** Main catalog grid: more columns = denser “Elements” list. */
    private static final int GRAPHIC_GRID_COLS = 4;
    /** Library cards use {@link FlowLayout} (not a fixed column count) so tile size follows the thumbnail, not the row. */
    private static final int GRAPHIC_LIB_CARD_W = 86;

    private final Runnable onBack;
    private final Runnable onSaved;
    private final IntConsumer onMusicVolumeLive;
    private final IntConsumer onSfxVolumeLive;
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final JLabel pathHint = new JLabel(" ");
    private final JCheckBox autoFocusNextUnit = new JCheckBox("Auto-focus next unit needing orders");
    private final JCheckBox showWeatherLegend = new JCheckBox("Show weather tint key on the map");
    private final JCheckBox showSettlerRecommendations = new JCheckBox("Show settler recommendation lens");
    private final JCheckBox showClaimLegend = new JCheckBox("Show territory ownership legend on map");
    private final JCheckBox showIntroductionTips = new JCheckBox("Show introduction tips when entering a match");
    private final JCheckBox minimapTintOwnClaimsOnly = new JCheckBox("Minimap: tint only my territory claims");
    private final JSpinner settleWeightFood = new JSpinner(new SpinnerNumberModel(3, 0, 9, 1));
    private final JSpinner settleWeightProduction = new JSpinner(new SpinnerNumberModel(3, 0, 9, 1));
    private final JSpinner settleWeightGold = new JSpinner(new SpinnerNumberModel(2, 0, 9, 1));
    private final JSpinner settleWeightTravel = new JSpinner(new SpinnerNumberModel(4, 0, 9, 1));
    private final JSpinner settleWeightRivalPressure = new JSpinner(new SpinnerNumberModel(2, 0, 9, 1));
    private final JComboBox<String> launchMode = new JComboBox<>(new String[] {
            "Maximized", "Windowed", "Native fullscreen"
    });
    private final JSpinner yearsPerFullRoundSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 99, 1));
    private final JCheckBox musicEnabled = new JCheckBox("Enable music");
    private final JCheckBox sfxEnabled = new JCheckBox("Enable sound effects");
    private final JSlider musicVolume = new JSlider(0, 100, 72);
    private final JLabel musicVolumeReadout = new JLabel("72");
    private final JSlider sfxVolume = new JSlider(0, 100, 80);
    private final JLabel sfxVolumeReadout = new JLabel("80");
    private final JTextField musicApiBaseUrl = new JTextField(36);
    private final JTextField musicApiSharedSecret = new JTextField(36);
    private final JLabel testStatus = new JLabel(" ");
    private final JLabel syncStatus = new JLabel(" ");
    private final JLabel cacheStatus = new JLabel(" ");
    private final JLabel graphicPathsHint = new JLabel(" ");
    private static final String CARD_GRAPHICS_GRID = "gl_grid";
    private static final String CARD_GRAPHICS_DETAIL = "gl_detail";
    private final CardLayout graphicLabCards = new CardLayout();
    private final JPanel graphicLabCenter = new JPanel(graphicLabCards);
    private final JPanel graphicGridInner = new JPanel(new GridLayout(0, GRAPHIC_GRID_COLS, 8, 8));
    private final Map<String, String> graphicAssignmentTokens = new LinkedHashMap<>();
    private GraphicRuntime.SlotDescriptor graphicDetailSlot;
    /** While an element detail is open, the picked assignment token for preview + flush. */
    private String graphicDetailSelectedSourceToken;
    private final JPanel graphicSourceRadioPanel = new JPanel();
    private final JPanel graphicLibraryPanel = new JPanel(new BorderLayout());
    private final JLabel graphicDetailPreview = new JLabel("", SwingConstants.CENTER);
    private final JButton graphicDetailBack = new JButton("\u2190  All elements");
    private final JLabel graphicDetailHeader = new JLabel(" ");
    private final JLabel graphicDetailSubtitle = new JLabel(" ");
    private final JButton graphicDetailUploadImage = new JButton("Upload image for this element");
    private final JButton graphicDetailRenameImage = new JButton("Rename selected image");
    private final JButton graphicDetailDeleteImage = new JButton("Delete selected image");
    private final JButton graphicDetailCopyPrompt = new JButton("Copy ChatGPT prompt");
    private final JLabel graphicUploadStatus = new JLabel(" ");
    private final Timer settingsAutoApplyTimer;
    private boolean suppressAutoApply;
    private final JComponent footer;

    SettingsPanel(Runnable onBack, Runnable onSaved, IntConsumer onMusicVolumeLive, IntConsumer onSfxVolumeLive) {
        super(new BorderLayout());
        this.onBack = onBack;
        this.onSaved = onSaved;
        this.onMusicVolumeLive = onMusicVolumeLive;
        this.onSfxVolumeLive = onSfxVolumeLive;
        this.settingsAutoApplyTimer = new Timer(220, e -> persistSettings(false));
        this.settingsAutoApplyTimer.setRepeats(false);
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(28, 36, 22, 36));

        styleCheckBox(autoFocusNextUnit);
        styleCheckBox(showWeatherLegend);
        styleCheckBox(showSettlerRecommendations);
        styleCheckBox(showClaimLegend);
        styleCheckBox(showIntroductionTips);
        styleCheckBox(minimapTintOwnClaimsOnly);
        launchMode.setMaximumSize(new Dimension(240, 28));
        ((JSpinner.DefaultEditor) yearsPerFullRoundSpinner.getEditor()).getTextField().setColumns(3);
        ((JSpinner.DefaultEditor) settleWeightFood.getEditor()).getTextField().setColumns(2);
        ((JSpinner.DefaultEditor) settleWeightProduction.getEditor()).getTextField().setColumns(2);
        ((JSpinner.DefaultEditor) settleWeightGold.getEditor()).getTextField().setColumns(2);
        ((JSpinner.DefaultEditor) settleWeightTravel.getEditor()).getTextField().setColumns(2);
        ((JSpinner.DefaultEditor) settleWeightRivalPressure.getEditor()).getTextField().setColumns(2);
        styleCheckBox(musicEnabled);
        styleCheckBox(sfxEnabled);
        configureVolumeSlider();
        configureSfxVolumeSlider();
        styleField(musicApiBaseUrl);
        styleField(musicApiSharedSecret);
        graphicGridInner.setOpaque(false);
        graphicLabCenter.setOpaque(false);
        graphicSourceRadioPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 8));
        graphicSourceRadioPanel.setOpaque(true);
        graphicSourceRadioPanel.setBackground(FIELD_BG);
        graphicSourceRadioPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleGhostBackButton(graphicDetailBack);
        graphicDetailHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

        var tabs = new JTabbedPane();
        tabs.setOpaque(false);
        tabs.setBackground(UiTheme.BG_DEEP);
        tabs.setForeground(INK_PRIMARY);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13f));
        tabs.addTab("Gameplay", buildGameplayPanel());
        tabs.addTab("Audio", buildAudioPanel());
        tabs.addTab("Graphics lab", wrapGraphicsLabTab(buildGraphicsLabPanel()));
        // Generation/chat flows are intentionally hidden; Graphics lab uses direct image upload.
        tabs.addTab("Controls", buildControlsPanel());
        footer = buildFooter();
        wireImmediateSettingsApply();
        add(buildHeader(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private void configureVolumeSlider() {
        musicVolume.setOpaque(false);
        musicVolume.setForeground(INK_PRIMARY);
        musicVolume.setMajorTickSpacing(50);
        musicVolume.setMinorTickSpacing(10);
        musicVolume.setPaintTicks(true);
        musicVolume.setPaintLabels(false);
        musicVolume.setMaximumSize(new Dimension(360, 28));
        musicVolume.setPreferredSize(new Dimension(360, 28));
        var liveVolumeDebounce = new Timer(180, e -> onMusicVolumeLive.accept(musicVolume.getValue()));
        liveVolumeDebounce.setRepeats(false);
        musicVolume.addChangeListener(e -> {
            musicVolumeReadout.setText(Integer.toString(musicVolume.getValue()));
            liveVolumeDebounce.restart();
        });
    }

    private void configureSfxVolumeSlider() {
        sfxVolume.setOpaque(false);
        sfxVolume.setForeground(INK_PRIMARY);
        sfxVolume.setMajorTickSpacing(50);
        sfxVolume.setMinorTickSpacing(10);
        sfxVolume.setPaintTicks(true);
        sfxVolume.setPaintLabels(false);
        sfxVolume.setMaximumSize(new Dimension(360, 28));
        sfxVolume.setPreferredSize(new Dimension(360, 28));
        var liveSfxDebounce = new Timer(180, e -> onSfxVolumeLive.accept(sfxVolume.getValue()));
        liveSfxDebounce.setRepeats(false);
        sfxVolume.addChangeListener(e -> {
            sfxVolumeReadout.setText(Integer.toString(sfxVolume.getValue()));
            liveSfxDebounce.restart();
        });
    }

    private JComponent buildHeader() {
        var title = new JLabel("Settings", SwingConstants.LEFT);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        var sub = new JLabel("Gameplay, audio, graphics lab, Claude API, and key bindings", SwingConstants.LEFT);
        sub.setForeground(INK_MUTED);
        sub.setFont(sub.getFont().deriveFont(13f));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        var backToMain = new JButton("\u2190 Back to Main Menu");
        styleTopBackButton(backToMain);
        backToMain.addActionListener(e -> {
            flushPendingSettingsPersist();
            onBack.run();
        });

        var titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleRow.add(title);
        titleRow.add(Box.createHorizontalGlue());
        titleRow.add(backToMain);

        var box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        box.add(titleRow);
        box.add(Box.createVerticalStrut(4));
        box.add(sub);
        return box;
    }

    private JPanel buildGameplayPanel() {
        var panel = makeTabPanel();
        panel.add(autoFocusNextUnit);
        panel.add(Box.createVerticalStrut(10));
        panel.add(showWeatherLegend);
        panel.add(Box.createVerticalStrut(6));
        panel.add(showSettlerRecommendations);
        panel.add(Box.createVerticalStrut(6));
        panel.add(showClaimLegend);
        panel.add(Box.createVerticalStrut(6));
        panel.add(showIntroductionTips);
        panel.add(Box.createVerticalStrut(6));
        panel.add(minimapTintOwnClaimsOnly);
        panel.add(Box.createVerticalStrut(6));
        panel.add(sectionHelp("You can also toggle the key with the binding in Settings → Controls "
                + "(default: Shift+W)."));
        panel.add(Box.createVerticalStrut(8));
        panel.add(sectionHelp("Settler lens scoring weights (0-9): higher travel/rival values make risky or far "
                + "sites less desirable."));
        panel.add(Box.createVerticalStrut(8));
        panel.add(compactSpinnerRow("Settle weight: food", settleWeightFood));
        panel.add(Box.createVerticalStrut(6));
        panel.add(compactSpinnerRow("Settle weight: production", settleWeightProduction));
        panel.add(Box.createVerticalStrut(6));
        panel.add(compactSpinnerRow("Settle weight: gold", settleWeightGold));
        panel.add(Box.createVerticalStrut(6));
        panel.add(compactSpinnerRow("Settle penalty: travel", settleWeightTravel));
        panel.add(Box.createVerticalStrut(6));
        panel.add(compactSpinnerRow("Settle penalty: rival pressure", settleWeightRivalPressure));
        panel.add(Box.createVerticalStrut(14));
        var launchRow = new JPanel();
        launchRow.setOpaque(false);
        launchRow.setLayout(new BoxLayout(launchRow, BoxLayout.X_AXIS));
        launchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        launchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        launchRow.add(fieldLabel("Launch window mode"));
        launchRow.add(Box.createHorizontalStrut(12));
        launchRow.add(launchMode);
        launchRow.add(Box.createHorizontalGlue());
        panel.add(launchRow);
        panel.add(Box.createVerticalStrut(10));
        panel.add(sectionHelp("Calendar: this many in-game years pass each time every player finishes one "
                + "full turn rotation (hot-seat round). New games use this value."));
        panel.add(Box.createVerticalStrut(8));
        var yRow = new JPanel();
        yRow.setOpaque(false);
        yRow.setLayout(new BoxLayout(yRow, BoxLayout.X_AXIS));
        yRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        yRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        yRow.add(fieldLabel("Years per full round"));
        yRow.add(Box.createHorizontalStrut(12));
        yRow.add(yearsPerFullRoundSpinner);
        yRow.add(Box.createHorizontalGlue());
        panel.add(yRow);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildAudioPanel() {
        var panel = makeTabPanel();

        panel.add(musicEnabled);
        panel.add(Box.createVerticalStrut(6));
        panel.add(sfxEnabled);
        panel.add(Box.createVerticalStrut(14));

        var volumeRow = new JPanel();
        volumeRow.setOpaque(false);
        volumeRow.setLayout(new BoxLayout(volumeRow, BoxLayout.X_AXIS));
        volumeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        volumeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        var volLabel = fieldLabel("Music volume");
        volumeRow.add(volLabel);
        volumeRow.add(Box.createHorizontalStrut(12));
        volumeRow.add(musicVolume);
        volumeRow.add(Box.createHorizontalStrut(10));
        musicVolumeReadout.setForeground(INK_MUTED);
        volumeRow.add(musicVolumeReadout);
        volumeRow.add(Box.createHorizontalGlue());
        panel.add(volumeRow);

        panel.add(Box.createVerticalStrut(12));

        var sfxRow = new JPanel();
        sfxRow.setOpaque(false);
        sfxRow.setLayout(new BoxLayout(sfxRow, BoxLayout.X_AXIS));
        sfxRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        sfxRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        var sfxLab = fieldLabel("Sound effects volume");
        sfxRow.add(sfxLab);
        sfxRow.add(Box.createHorizontalStrut(12));
        sfxRow.add(sfxVolume);
        sfxRow.add(Box.createHorizontalStrut(10));
        sfxVolumeReadout.setForeground(INK_MUTED);
        sfxRow.add(sfxVolumeReadout);
        sfxRow.add(Box.createHorizontalGlue());
        panel.add(sfxRow);

        panel.add(Box.createVerticalStrut(20));
        panel.add(sectionHeader("Music library server"));
        panel.add(Box.createVerticalStrut(4));
        panel.add(sectionHelp("Put purchased Envato zips in assets/music/envato-bundles. Use Sync all to push new "
                + "files up to the server and pull anything you don't have yet. Upload only skips pulling."));
        panel.add(Box.createVerticalStrut(12));

        panel.add(fieldLabel("Music API base URL"));
        musicApiBaseUrl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        panel.add(musicApiBaseUrl);
        panel.add(Box.createVerticalStrut(10));

        panel.add(fieldLabel("Shared secret"));
        musicApiSharedSecret.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        panel.add(musicApiSharedSecret);
        panel.add(Box.createVerticalStrut(14));

        var testRow = new JPanel();
        testRow.setOpaque(false);
        testRow.setLayout(new BoxLayout(testRow, BoxLayout.X_AXIS));
        testRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        testRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        var testBtn = new JButton("Test connection");
        testBtn.addActionListener(e -> testMusicConnection(testBtn));
        testRow.add(testBtn);
        testRow.add(Box.createHorizontalStrut(12));
        testStatus.setForeground(INK_MUTED);
        testRow.add(testStatus);
        testRow.add(Box.createHorizontalGlue());
        panel.add(testRow);

        panel.add(Box.createVerticalStrut(8));

        var fullSyncRow = new JPanel();
        fullSyncRow.setOpaque(false);
        fullSyncRow.setLayout(new BoxLayout(fullSyncRow, BoxLayout.X_AXIS));
        fullSyncRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        fullSyncRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        var fullSyncBtn = new JButton("Sync all (upload + download)");
        fullSyncBtn.setFont(fullSyncBtn.getFont().deriveFont(Font.BOLD));
        fullSyncBtn.addActionListener(e -> bidirectionalSync(fullSyncBtn));
        fullSyncRow.add(fullSyncBtn);
        fullSyncRow.add(Box.createHorizontalStrut(12));
        syncStatus.setForeground(INK_MUTED);
        fullSyncRow.add(syncStatus);
        fullSyncRow.add(Box.createHorizontalGlue());
        panel.add(fullSyncRow);

        panel.add(Box.createVerticalStrut(6));

        var uploadOnlyRow = new JPanel();
        uploadOnlyRow.setOpaque(false);
        uploadOnlyRow.setLayout(new BoxLayout(uploadOnlyRow, BoxLayout.X_AXIS));
        uploadOnlyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        uploadOnlyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        var syncBtn = new JButton("Upload only (push local zips)");
        syncBtn.addActionListener(e -> syncMusicBundles(syncBtn));
        uploadOnlyRow.add(syncBtn);
        uploadOnlyRow.add(Box.createHorizontalGlue());
        panel.add(uploadOnlyRow);

        panel.add(Box.createVerticalStrut(20));
        panel.add(sectionHeader("Local cache"));
        panel.add(Box.createVerticalStrut(4));
        panel.add(sectionHelp("Removes music downloaded to this computer. You can re-download from the server anytime."));
        panel.add(Box.createVerticalStrut(12));

        var cacheRow = new JPanel();
        cacheRow.setOpaque(false);
        cacheRow.setLayout(new BoxLayout(cacheRow, BoxLayout.X_AXIS));
        cacheRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        cacheRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        var cacheBtn = new JButton("Clear local music cache");
        cacheBtn.addActionListener(e -> clearLocalMusicCache(cacheBtn));
        cacheRow.add(cacheBtn);
        cacheRow.add(Box.createHorizontalStrut(12));
        cacheStatus.setForeground(INK_MUTED);
        cacheRow.add(cacheStatus);
        cacheRow.add(Box.createHorizontalGlue());
        panel.add(cacheRow);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildGraphicsLabPanel() {
        var panel = makeTabPanel();
        var glIntro = sectionHelp(
                "Each tile is one catalog entry. Click through to upload a PNG and assign it to that element.");
        glIntro.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(glIntro);
        panel.add(Box.createVerticalStrut(8));
        graphicPathsHint.setForeground(INK_MUTED);
        graphicPathsHint.setFont(graphicPathsHint.getFont().deriveFont(11f));
        graphicPathsHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(graphicPathsHint);
        panel.add(Box.createVerticalStrut(12));

        panel.add(sectionHeader("Elements"));
        panel.add(Box.createVerticalStrut(6));
        var gridBorderWrap = new JPanel(new BorderLayout());
        gridBorderWrap.setOpaque(false);
        gridBorderWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        gridBorderWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridBorderWrap.add(graphicGridInner, BorderLayout.CENTER);

        var gridCard = new JPanel();
        gridCard.setOpaque(false);
        gridCard.setLayout(new BoxLayout(gridCard, BoxLayout.Y_AXIS));
        gridCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridCard.add(gridBorderWrap);

        graphicLabCenter.add(gridCard, CARD_GRAPHICS_GRID);
        graphicLabCenter.add(buildGraphicDetailCard(), CARD_GRAPHICS_DETAIL);
        graphicLabCenter.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(graphicLabCenter);

        panel.add(Box.createVerticalGlue());
        applyGraphicPathsHint();
        return panel;
    }

    /** Full Graphics lab tab scrolls as one pane—avoids nested scroll areas inside Elements. */
    private JScrollPane wrapGraphicsLabTab(JPanel content) {
        var scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setBackground(UiTheme.BG_DEEP);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }


    private JPanel buildGraphicDetailCard() {
        var shell = new JPanel();
        shell.setOpaque(true);
        shell.setBackground(GRAPHIC_CARD_SURFACE);
        shell.setLayout(new BoxLayout(shell, BoxLayout.Y_AXIS));
        shell.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(14, 18, 18, 18)));
        shell.setAlignmentX(Component.LEFT_ALIGNMENT);

        graphicDetailBack.setAlignmentX(Component.LEFT_ALIGNMENT);
        graphicDetailBack.addActionListener(e -> closeGraphicElementDetail());
        shell.add(graphicDetailBack);
        shell.add(Box.createVerticalStrut(12));

        shell.add(graphicDetailHeader);
        graphicDetailSubtitle.setForeground(INK_MUTED);
        graphicDetailSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        shell.add(graphicDetailSubtitle);
        shell.add(Box.createVerticalStrut(12));

        var libLbl = fieldLabel("Library for this element");
        libLbl.setToolTipText("Pick bundled default art or one of your uploaded PNGs.");
        shell.add(libLbl);
        graphicLibraryPanel.removeAll();
        graphicLibraryPanel.add(graphicSourceRadioPanel, BorderLayout.CENTER);
        graphicLibraryPanel.setOpaque(true);
        graphicLibraryPanel.setBackground(FIELD_BG);
        graphicLibraryPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        graphicLibraryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        shell.add(graphicLibraryPanel);
        shell.add(Box.createVerticalStrut(12));

        shell.add(fieldLabel("Preview"));
        var previewWrap = new JPanel(new BorderLayout());
        previewWrap.setOpaque(true);
        previewWrap.setBackground(FIELD_BG);
        previewWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        previewWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewWrap.setPreferredSize(new Dimension(680, GRAPHIC_PREVIEW_MAX_H + 20));
        previewWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, GRAPHIC_PREVIEW_MAX_H + 32));
        graphicDetailPreview.setOpaque(false);
        graphicDetailPreview.setForeground(INK_MUTED);
        graphicDetailPreview.setFont(graphicDetailPreview.getFont().deriveFont(12f));
        graphicDetailPreview.setVerticalAlignment(SwingConstants.CENTER);
        graphicDetailPreview.setHorizontalAlignment(SwingConstants.CENTER);
        previewWrap.add(graphicDetailPreview, BorderLayout.CENTER);
        shell.add(previewWrap);
        shell.add(Box.createVerticalStrut(12));

        var actionsRow = new JPanel(new GridLayout(1, 0, 10, 0));
        actionsRow.setOpaque(false);
        actionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        styleLabActionButton(graphicDetailUploadImage, true);
        graphicDetailUploadImage.setToolTipText(
                "Pick a local PNG and assign it immediately to this element.");
        graphicDetailUploadImage.addActionListener(e -> uploadImageForCurrentElement());

        styleLabActionButton(graphicDetailRenameImage, false);
        graphicDetailRenameImage.setEnabled(false);
        graphicDetailRenameImage.addActionListener(e -> renameSelectedImageForCurrentElement());

        styleLabActionButton(graphicDetailDeleteImage, false);
        graphicDetailDeleteImage.setEnabled(false);
        graphicDetailDeleteImage.addActionListener(e -> deleteSelectedImageForCurrentElement());

        styleLabActionButton(graphicDetailCopyPrompt, false);
        graphicDetailCopyPrompt.setToolTipText("Copy a ready-to-use prompt for generating this element in ChatGPT.");
        graphicDetailCopyPrompt.addActionListener(e -> copyChatGptPromptForCurrentElement());

        actionsRow.add(graphicDetailUploadImage);
        actionsRow.add(graphicDetailRenameImage);
        actionsRow.add(graphicDetailDeleteImage);
        actionsRow.add(graphicDetailCopyPrompt);
        shell.add(actionsRow);
        shell.add(Box.createVerticalStrut(8));

        graphicUploadStatus.setForeground(INK_MUTED);
        graphicUploadStatus.setFont(graphicUploadStatus.getFont().deriveFont(12f));
        graphicUploadStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        shell.add(graphicUploadStatus);

        shell.add(Box.createVerticalGlue());
        return shell;
    }

    private void reloadGraphicStateFromDisk() {
        GraphicRuntime.reloadFromDisk();
        graphicAssignmentTokens.clear();
        for (GraphicRuntime.SlotDescriptor slot : GraphicRuntime.catalogSlotsInOrder()) {
            graphicAssignmentTokens.put(slot.id(), GraphicRuntime.assignmentToken(slot.id()));
        }
    }

    private void rebuildGraphicGrid() {
        graphicGridInner.removeAll();
        for (GraphicRuntime.SlotDescriptor slot : GraphicRuntime.catalogSlotsInOrder()) {
            var tile = new JButton(tileHtml(slot));
            tile.setOpaque(true);
            tile.setBackground(FIELD_BG);
            tile.setForeground(INK_PRIMARY);
            final var normalBorder = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(FIELD_BORDER, 1),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8));
            tile.setBorder(normalBorder);
            tile.setFocusPainted(false);
            tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tile.setToolTipText(slot.label() + " — " + slot.assetKind());
            tile.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    tile.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(GRAPHIC_TILE_HOVER_BORDER, 1),
                            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    tile.setBorder(normalBorder);
                }
            });
            tile.addActionListener(e -> openGraphicElementDetail(slot));
            graphicGridInner.add(tile);
        }
        graphicGridInner.revalidate();
        graphicGridInner.repaint();
    }

    private String tileHtml(GraphicRuntime.SlotDescriptor slot) {
        String sum = assignmentSummaryForSlot(slot.id());
        return "<html><center><b style='font-size:11px'>"
                + escapeHtml(slot.label())
                + "</b><br/><span style='color:#939aa6;font-size:9px'>"
                + escapeHtml(slot.id())
                + "</span><br/><span style='font-size:10px;color:#a9d4ff'>"
                + escapeHtml(sum)
                + "</span></center></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Readable dialog for long import/API errors (base64 issues, model dumps). */
    private static void showLongErrorDialog(Component parent, String title, String message) {
        String m = message == null ? "" : message;
        if (m.length() <= 520) {
            JOptionPane.showMessageDialog(parent, m, title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        var ta = new JTextArea(m);
        ta.setEditable(false);
        ta.setOpaque(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setColumns(64);
        ta.setRows(14);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        var sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(560, 300));
        JOptionPane.showMessageDialog(parent, sp, title, JOptionPane.ERROR_MESSAGE);
    }

    private void applyGraphicPathsHint() {
        graphicPathsHint.setText("Repo graphics path: assets/graphics/");
        String tip =
                "<html><body style='width:440px'>"
                        + "<b>Full paths</b><br>"
                        + "Assignments: "
                        + escapeHtml(GraphicRuntime.assignmentsPersistencePath().toString())
                        + "<br>System prompt: "
                        + escapeHtml(GameOptions.graphicsAgentSystemPromptFile().toString())
                        + "<br><br><b>In the repo</b><br>"
                        + "Catalog: assets/graphics/catalog/slots.json<br>"
                        + "Sets: assets/graphics/sets/[setId]/manifest.json"
                        + "</body></html>";
        graphicPathsHint.setToolTipText(tip);
    }

    private static String tildePath(Path p) {
        if (p == null) {
            return "";
        }
        Path abs = p.toAbsolutePath().normalize();
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path hp = Path.of(home).toAbsolutePath().normalize();
            if (abs.startsWith(hp)) {
                return "~/" + hp.relativize(abs).toString().replace('\\', '/');
            }
        }
        return abs.toString().replace('\\', '/');
    }

    private static void styleGhostBackButton(JButton b) {
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setForeground(INK_SECTION);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static void styleLabActionButton(JButton b, boolean primary) {
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? new Color(0x58_8d_b8) : FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        if (primary) {
            b.setBackground(new Color(0x1d_3c_58));
            b.setForeground(new Color(0xdf_f1_ff));
        } else {
            b.setBackground(FIELD_BG);
            b.setForeground(INK_PRIMARY);
        }
        b.setOpaque(true);
    }

    private static void styleTopBackButton(JButton b) {
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x58_8d_b8), 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        b.setBackground(new Color(0x1d_3c_58));
        b.setForeground(new Color(0xdf_f1_ff));
        b.setOpaque(true);
    }

    private String assignmentSummaryForSlot(String slotId) {
        String tok = graphicAssignmentTokens.getOrDefault(slotId, "default");
        if ("default".equalsIgnoreCase(tok)) {
            return "App default";
        }
        for (var lib : GraphicRuntime.librarySetsSupportingSlot(slotId)) {
            if (lib.id().equalsIgnoreCase(tok)) {
                return lib.label();
            }
        }
        return "App default";
    }

    private void openGraphicElementDetail(GraphicRuntime.SlotDescriptor slot) {
        flushGraphicDetailToMaps();
        graphicDetailSlot = slot;
        graphicDetailHeader.setText(
                "<html><span style='color:#939aa6;font-size:12px'>Graphics lab</span>"
                        + "&nbsp;<span style='color:#a9d4ff;font-size:13px'>&rsaquo;</span>&nbsp;"
                        + "<span style='color:#ffffff;font-size:18px;font-weight:bold'>"
                        + escapeHtml(slot.label())
                        + "</span></html>");
        graphicDetailSubtitle.setText(slot.id() + "  ·  " + slot.assetKind());
        graphicDetailSubtitle.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        populateGraphicSourceLibrary(slot);
        updateGraphicManageButtonsEnabled();
        graphicLabCards.show(graphicLabCenter, CARD_GRAPHICS_DETAIL);
        persistGraphicsAssignmentsOnly();
    }

    private void closeGraphicElementDetail() {
        flushGraphicDetailToMaps();
        graphicDetailSelectedSourceToken = null;
        graphicDetailSlot = null;
        rebuildGraphicGrid();
        graphicLabCards.show(graphicLabCenter, CARD_GRAPHICS_GRID);
        persistGraphicsAssignmentsOnly();
    }

    private void flushGraphicDetailToMaps() {
        if (graphicDetailSlot == null) {
            return;
        }
        graphicAssignmentTokens.put(graphicDetailSlot.id(), selectedArtSourceToken());
    }

    private String selectedArtSourceToken() {
        if (graphicDetailSlot != null && graphicDetailSelectedSourceToken != null) {
            return graphicDetailSelectedSourceToken;
        }
        if (graphicDetailSlot != null) {
            return graphicAssignmentTokens.getOrDefault(graphicDetailSlot.id(), "default");
        }
        return "default";
    }

    private void populateGraphicSourceLibrary(GraphicRuntime.SlotDescriptor slot) {
        graphicSourceRadioPanel.removeAll();
        graphicSourceRadioPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 8));
        String persisted = graphicAssignmentTokens.getOrDefault(slot.id(), "default");
        List<GraphicRuntime.LibrarySetDescriptor> libs =
                GraphicRuntime.librarySetsSupportingSlotNewestFirst(slot.id());

        graphicSourceRadioPanel.add(
                buildGraphicLibraryCard(slot.id(), "default", "App default", "Bundled", false, null));

        for (var lib : libs) {
            graphicSourceRadioPanel.add(
                    buildGraphicLibraryCard(slot.id(), lib.id(), lib.label(), "Uploaded PNG", false, null));
        }

        String effectiveToken = "default";
        if (pickerTokenMatches("default", persisted)) {
            effectiveToken = "default";
        } else {
            boolean matchedLib = false;
            for (var lib : libs) {
                if (pickerTokenMatches(lib.id(), persisted)) {
                    effectiveToken = lib.id();
                    matchedLib = true;
                    break;
                }
            }
            if (!matchedLib) {
                effectiveToken = "default";
            }
        }

        graphicDetailSelectedSourceToken = effectiveToken;
        graphicAssignmentTokens.put(slot.id(), effectiveToken);
        applyGraphicLibraryCardBorders(effectiveToken);
        updateGraphicManageButtonsEnabled();
        graphicSourceRadioPanel.revalidate();
        graphicSourceRadioPanel.repaint();
        graphicLibraryPanel.revalidate();
        graphicLibraryPanel.repaint();
        refreshGraphicDetailPreview();
    }

    private void selectGraphicLibraryToken(String token) {
        graphicDetailSelectedSourceToken = token;
        if (graphicDetailSlot != null) {
            graphicAssignmentTokens.put(graphicDetailSlot.id(), token);
        }
        applyGraphicLibraryCardBorders(token);
        updateGraphicManageButtonsEnabled();
        refreshGraphicDetailPreview();
        requestImmediateApply();
    }

    private void updateGraphicManageButtonsEnabled() {
        String tok = selectedArtSourceToken();
        boolean uploadSelected = GraphicRuntime.isUploadToken(tok);
        graphicDetailRenameImage.setEnabled(uploadSelected);
        graphicDetailDeleteImage.setEnabled(uploadSelected);
    }

    private void applyGraphicLibraryCardBorders(String selectedToken) {
        for (Component c : graphicSourceRadioPanel.getComponents()) {
            if (!(c instanceof JPanel panel)) {
                continue;
            }
            Object t = panel.getClientProperty("token");
            String tok = t != null ? t.toString() : "";
            boolean sel = pickerTokenMatches(tok, selectedToken);
            Color borderCol = sel ? GRAPHIC_TILE_HOVER_BORDER : FIELD_BORDER;
            int thick = sel ? 2 : 1;
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderCol, thick),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        }
    }

    private JPanel buildGraphicLibraryCard(
            String slotId,
            String token,
            String title,
            String subtitle,
            boolean orphanWarning,
            String tooltipOverride) {
        Dimension thumbSz = new Dimension(48, 48);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(GRAPHIC_CARD_SURFACE);
        card.putClientProperty("token", token);
        JLabel thumb = new JLabel();
        thumb.setHorizontalAlignment(SwingConstants.CENTER);
        thumb.setVerticalAlignment(SwingConstants.CENTER);
        thumb.setPreferredSize(thumbSz);
        thumb.setMinimumSize(thumbSz);
        thumb.setMaximumSize(thumbSz);
        thumb.setAlignmentX(Component.CENTER_ALIGNMENT);
        ImageIcon ic = thumbnailIconForSlotToken(slotId, token);
        if (ic != null) {
            thumb.setIcon(ic);
        } else {
            thumb.setText("\u2014");
            thumb.setForeground(INK_MUTED);
        }
        String titleColor = orphanWarning ? "#f0c06a" : "#e1e6ef";
        JLabel cap = new JLabel(
                "<html><div style='text-align:center;width:72px'><span style='color:"
                        + titleColor
                        + ";font-size:10px'>"
                        + escapeHtml(title)
                        + "</span><br/><span style='color:#939aa6;font-size:9px'>"
                        + escapeHtml(subtitle)
                        + "</span></div></html>");
        cap.setHorizontalAlignment(SwingConstants.CENTER);
        cap.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(thumb);
        card.add(Box.createVerticalStrut(4));
        card.add(cap);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        // Narrow fixed width; height follows caption (wrapped filenames). GridLayout used to stretch cells wide.
        Dimension natural = card.getPreferredSize();
        card.setPreferredSize(new Dimension(GRAPHIC_LIB_CARD_W, natural.height));
        card.setMaximumSize(new Dimension(GRAPHIC_LIB_CARD_W, 240));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectGraphicLibraryToken(token);
            }
        });
        String tip = tooltipOverride != null && !tooltipOverride.isBlank()
                ? tooltipOverride
                : title + " \u2014 " + subtitle;
        card.setToolTipText(tip);
        return card;
    }

    private ImageIcon thumbnailIconForSlotToken(String slotId, String token) {
        GraphicResolvedAsset resolved = GraphicRuntime.resolveSlotWithToken(slotId, token);
        BufferedImage img = bufferedImageForResolved(resolved);
        if (img == null) {
            return null;
        }
        return new ImageIcon(scaleGraphicThumbImage(img, GRAPHIC_LIBRARY_THUMB_MAX));
    }

    private BufferedImage bufferedImageForResolved(GraphicResolvedAsset resolved) {
        var png = resolved.png();
        if (png.isPresent()) {
            Path p = png.get();
            if (Files.isRegularFile(p)) {
                try {
                    BufferedImage img = ImageIO.read(p.toFile());
                    if (img != null) {
                        return img;
                    }
                } catch (IOException ignored) {
                    // fall through
                }
            }
        }
        var txt = resolved.txtSprite();
        if (txt.isPresent()) {
            Path p = txt.get();
            if (Files.isRegularFile(p)) {
                return renderTxtSpritePreviewImage(p);
            }
        }
        return null;
    }

    private void refreshGraphicDetailPreview() {
        if (graphicDetailSlot == null) {
            graphicDetailPreview.setIcon(null);
            graphicDetailPreview.setText("");
            return;
        }
        String token = selectedArtSourceToken();
        GraphicResolvedAsset resolved = GraphicRuntime.resolveSlotWithToken(graphicDetailSlot.id(), token);
        BufferedImage img = bufferedImageForResolved(resolved);
        if (img != null) {
            graphicDetailPreview.setIcon(new ImageIcon(scaleGraphicPreviewImage(img)));
            graphicDetailPreview.setText("");
            return;
        }

        graphicDetailPreview.setIcon(null);
        graphicDetailPreview.setForeground(INK_MUTED);
        graphicDetailPreview.setText("No preview (file not found)");
    }

    private static BufferedImage scaleGraphicPreviewImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min((double) GRAPHIC_PREVIEW_MAX_W / w, (double) GRAPHIC_PREVIEW_MAX_H / h);
        if (scale > 1.0) {
            scale = 1.0;
        }
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        if (nw == w && nh == h) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static BufferedImage scaleGraphicThumbImage(BufferedImage src, int maxPx) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min((double) maxPx / w, (double) maxPx / h);
        if (scale > 1.0) {
            scale = 1.0;
        }
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        if (nw == w && nh == h) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /** Renders legacy TXT sprite rows into a bitmap for the settings preview (matches hex-map parsing rules). */
    private static BufferedImage renderTxtSpritePreviewImage(Path path) {
        try {
            List<String> rows = Files.readAllLines(path);
            if (rows.isEmpty()) {
                return null;
            }
            int rowCount = rows.size();
            int charW = rows.stream().mapToInt(String::length).max().orElse(0);
            if (charW == 0) {
                return null;
            }
            int pixel = Math.max(2, Math.min(8, Math.min(GRAPHIC_PREVIEW_MAX_W / charW, GRAPHIC_PREVIEW_MAX_H / rowCount)));
            int imgW = charW * pixel + 2;
            int imgH = rowCount * pixel + 2;
            BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(FIELD_BG);
                g.fillRect(0, 0, imgW, imgH);
                g.setColor(INK_PRIMARY);
                for (int y = 0; y < rowCount; y++) {
                    String row = rows.get(y);
                    for (int x = 0; x < row.length(); x++) {
                        char ch = row.charAt(x);
                        if (ch == '#' || ch == 'X' || ch == '1' || ch == '@') {
                            g.fillRect(1 + x * pixel, 1 + y * pixel, pixel, pixel);
                        }
                    }
                }
            } finally {
                g.dispose();
            }
            return img;
        } catch (IOException e) {
            return null;
        }
    }

    /** Case-insensitive match of two assignment tokens. */
    private static boolean pickerTokenMatches(String optionToken, String persisted) {
        if (optionToken == null || persisted == null) {
            return false;
        }
        return optionToken.equalsIgnoreCase(persisted);
    }


    private JPanel buildControlsPanel() {
        var grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 8);
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        for (String action : Keybinds.actionOrder()) {
            gbc.gridx = 0;
            gbc.weightx = 0;
            var l = new JLabel(Keybinds.labelFor(action) + ":");
            l.setForeground(INK_PRIMARY);
            grid.add(l, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            var f = new JTextField(26);
            styleField(f);
            fields.put(action, f);
            grid.add(f, gbc);

            gbc.gridy++;
        }

        var scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UiTheme.BG_DEEP);
        scroll.setOpaque(false);

        var wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(14, 12, 10, 12));
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent buildFooter() {
        var footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));

        pathHint.setForeground(INK_MUTED);
        pathHint.setFont(pathHint.getFont().deriveFont(11f));
        pathHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(pathHint);
        return footer;
    }

    private static JPanel makeTabPanel() {
        var p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 16, 12, 16));
        return p;
    }

    private static JComponent sectionHeader(String text) {
        var label = new JLabel(text.toUpperCase());
        label.setForeground(INK_SECTION);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        var separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setForeground(FIELD_BORDER);
        separator.setBackground(FIELD_BORDER);
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        var wrapper = new JPanel();
        wrapper.setOpaque(false);
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        wrapper.add(label);
        wrapper.add(Box.createVerticalStrut(4));
        wrapper.add(separator);
        return wrapper;
    }

    private static JLabel sectionHelp(String text) {
        var l = new JLabel(text);
        l.setForeground(INK_MUTED);
        l.setFont(l.getFont().deriveFont(12f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static void styleCheckBox(JCheckBox cb) {
        cb.setOpaque(false);
        cb.setForeground(INK_PRIMARY);
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private static void styleField(JTextField f) {
        f.setBackground(FIELD_BG);
        f.setForeground(INK_PRIMARY);
        f.setCaretColor(INK_PRIMARY);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    private static void stylePasswordField(JPasswordField f) {
        f.setBackground(FIELD_BG);
        f.setForeground(INK_PRIMARY);
        f.setCaretColor(INK_PRIMARY);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER, 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    private static void styleTextArea(JTextArea a) {
        a.setBackground(FIELD_BG);
        a.setForeground(INK_PRIMARY);
        a.setCaretColor(INK_PRIMARY);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    private static JLabel fieldLabel(String text) {
        var l = new JLabel(text);
        l.setForeground(INK_PRIMARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent compactSpinnerRow(String label, JSpinner spinner) {
        var row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.add(fieldLabel(label));
        row.add(Box.createHorizontalStrut(12));
        row.add(spinner);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void wireImmediateSettingsApply() {
        autoFocusNextUnit.addActionListener(e -> requestImmediateApply());
        showWeatherLegend.addActionListener(e -> requestImmediateApply());
        showSettlerRecommendations.addActionListener(e -> requestImmediateApply());
        showClaimLegend.addActionListener(e -> requestImmediateApply());
        showIntroductionTips.addActionListener(e -> requestImmediateApply());
        minimapTintOwnClaimsOnly.addActionListener(e -> requestImmediateApply());
        launchMode.addActionListener(e -> requestImmediateApply());
        yearsPerFullRoundSpinner.addChangeListener(e -> requestImmediateApply());
        settleWeightFood.addChangeListener(e -> requestImmediateApply());
        settleWeightProduction.addChangeListener(e -> requestImmediateApply());
        settleWeightGold.addChangeListener(e -> requestImmediateApply());
        settleWeightTravel.addChangeListener(e -> requestImmediateApply());
        settleWeightRivalPressure.addChangeListener(e -> requestImmediateApply());
        musicEnabled.addActionListener(e -> requestImmediateApply());
        sfxEnabled.addActionListener(e -> requestImmediateApply());
        musicVolume.addChangeListener(e -> requestImmediateApply());
        sfxVolume.addChangeListener(e -> requestImmediateApply());
        addImmediateApplyListener(musicApiBaseUrl);
        addImmediateApplyListener(musicApiSharedSecret);
        for (JTextField f : fields.values()) {
            addImmediateApplyListener(f);
        }
    }

    private void addImmediateApplyListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                requestImmediateApply();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                requestImmediateApply();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                requestImmediateApply();
            }
        });
    }

    private void requestImmediateApply() {
        if (suppressAutoApply) {
            return;
        }
        settingsAutoApplyTimer.restart();
    }

    /** Writes settings + graphics assignments immediately (stops debounce). Use before leaving Settings. */
    private void flushPendingSettingsPersist() {
        if (suppressAutoApply) {
            return;
        }
        flushGraphicDetailToMaps();
        settingsAutoApplyTimer.stop();
        persistSettings(false);
    }

    /** Persists catalog graphic picks only; refreshes map (cheap vs full settings save). */
    private void persistGraphicsAssignmentsOnly() {
        if (suppressAutoApply) {
            return;
        }
        try {
            flushGraphicDetailToMaps();
            var assigns = new LinkedHashMap<String, String>();
            for (GraphicRuntime.SlotDescriptor slot : GraphicRuntime.catalogSlotsInOrder()) {
                assigns.put(slot.id(), graphicAssignmentTokens.getOrDefault(slot.id(), "default"));
            }
            if (!assigns.isEmpty()) {
                GraphicRuntime.persistAssignmentTokens(assigns);
            }
            GraphicRuntime.reloadFromDisk();
            onSaved.run();
        } catch (Exception ex) {
            pathHint.setForeground(new Color(0xf3_8b_8b));
            pathHint.setText("Could not save graphics: " + (ex.getMessage() == null ? "Unknown error" : ex.getMessage()));
        }
    }

    void refreshFromDisk() {
        suppressAutoApply = true;
        try {
        var kb = Keybinds.load();
        var opts = GameOptions.load();
        pathHint.setText("Saved to: " + tildePath(GameOptions.userFilePath().getParent()));
        pathHint.setToolTipText(GameOptions.userFilePath().getParent().toString());
        applyGraphicPathsHint();
        reloadGraphicStateFromDisk();
        rebuildGraphicGrid();
        graphicDetailSlot = null;
        graphicLabCards.show(graphicLabCenter, CARD_GRAPHICS_GRID);
        for (String action : Keybinds.actionOrder()) {
            String csv = String.join(",", kb.keySpecsFor(action));
            fields.get(action).setText(csv);
        }
        autoFocusNextUnit.setSelected(opts.autoFocusNextUnit());
        showWeatherLegend.setSelected(opts.showWeatherLegend());
        showSettlerRecommendations.setSelected(opts.showSettlerRecommendations());
        showClaimLegend.setSelected(opts.showClaimLegend());
        showIntroductionTips.setSelected(!opts.tipsDismissed());
        minimapTintOwnClaimsOnly.setSelected(opts.minimapTintOwnClaimsOnly());
        settleWeightFood.setValue(opts.settleWeightFood());
        settleWeightProduction.setValue(opts.settleWeightProduction());
        settleWeightGold.setValue(opts.settleWeightGold());
        settleWeightTravel.setValue(opts.settleWeightTravel());
        settleWeightRivalPressure.setValue(opts.settleWeightRivalPressure());
        launchMode.setSelectedItem(switch (opts.windowLaunchMode()) {
            case WINDOWED -> "Windowed";
            case MAXIMIZED -> "Maximized";
            case NATIVE_FULLSCREEN -> "Native fullscreen";
        });
        yearsPerFullRoundSpinner.setValue(opts.yearsPerFullRound());
        musicEnabled.setSelected(opts.musicEnabled());
        sfxEnabled.setSelected(opts.sfxEnabled());
        musicVolume.setValue(opts.musicVolume());
        musicVolumeReadout.setText(Integer.toString(opts.musicVolume()));
        sfxVolume.setValue(opts.sfxVolume());
        sfxVolumeReadout.setText(Integer.toString(opts.sfxVolume()));
        musicApiBaseUrl.setText(opts.musicApiBaseUrl());
        musicApiSharedSecret.setText(opts.musicApiSharedSecret());
        graphicUploadStatus.setText(" ");
        testStatus.setText(" ");
        syncStatus.setText(" ");
        cacheStatus.setText(" ");
        } finally {
            suppressAutoApply = false;
        }
    }

    private void loadDefaults() {
        suppressAutoApply = true;
        try {
        var kb = Keybinds.load();
        for (String action : Keybinds.actionOrder()) {
            String csv = switch (action) {
                case Keybinds.END_TURN -> "ENTER";
                case Keybinds.SKIP_ACTION -> "SPACE";
                case Keybinds.DESELECT -> "ESCAPE";
                case Keybinds.FOUND_CITY -> "B";
                case Keybinds.NEXT_UNIT -> "PERIOD,N";
                case Keybinds.FORTIFY -> "F";
                case Keybinds.MOVE_MODE -> "M";
                case Keybinds.FIT_VIEW -> "shift F";
                case Keybinds.ZOOM_IN -> "EQUALS,PLUS";
                case Keybinds.ZOOM_OUT -> "MINUS";
                case Keybinds.PAN_LEFT -> "LEFT";
                case Keybinds.PAN_RIGHT -> "RIGHT";
                case Keybinds.PAN_UP -> "UP";
                case Keybinds.PAN_DOWN -> "DOWN";
                case Keybinds.SAVE_GAME -> "meta S,ctrl S";
                case Keybinds.TOGGLE_WEATHER_LEGEND -> "shift W";
                case Keybinds.TOGGLE_SETTLER_LENS -> "shift R";
                case Keybinds.SHOW_HOTKEYS -> "F1,shift SLASH";
                case Keybinds.TOGGLE_AUTO_EXPLORE -> "shift E";
                default -> String.join(",", kb.keySpecsFor(action));
            };
            fields.get(action).setText(csv);
        }
        autoFocusNextUnit.setSelected(false);
        showWeatherLegend.setSelected(true);
        showSettlerRecommendations.setSelected(true);
        showClaimLegend.setSelected(true);
        showIntroductionTips.setSelected(true);
        minimapTintOwnClaimsOnly.setSelected(false);
        settleWeightFood.setValue(3);
        settleWeightProduction.setValue(3);
        settleWeightGold.setValue(2);
        settleWeightTravel.setValue(4);
        settleWeightRivalPressure.setValue(2);
        launchMode.setSelectedItem("Maximized");
        yearsPerFullRoundSpinner.setValue(5);
        musicEnabled.setSelected(true);
        sfxEnabled.setSelected(true);
        musicVolume.setValue(72);
        musicVolumeReadout.setText("72");
        musicApiBaseUrl.setText("");
        musicApiSharedSecret.setText("");
        GraphicRuntime.reloadFromDisk();
        graphicAssignmentTokens.clear();
        for (GraphicRuntime.SlotDescriptor slot : GraphicRuntime.catalogSlotsInOrder()) {
            graphicAssignmentTokens.put(slot.id(), "default");
        }
        graphicUploadStatus.setText(" ");
        rebuildGraphicGrid();
        graphicDetailSlot = null;
        graphicLabCards.show(graphicLabCenter, CARD_GRAPHICS_GRID);
        } finally {
            suppressAutoApply = false;
        }
    }

    private void persistSettings(boolean notifyOnSuccess) {
        var out = new LinkedHashMap<String, String>();
        for (var e : fields.entrySet()) {
            out.put(e.getKey(), e.getValue().getText().trim());
        }
        try {
            Keybinds.saveBindings(out);
            int ypr = ((Number) yearsPerFullRoundSpinner.getValue()).intValue();
            GameOptions.save(
                    autoFocusNextUnit.isSelected(),
                    musicEnabled.isSelected(),
                    musicVolume.getValue(),
                    sfxVolume.getValue(),
                    sfxEnabled.isSelected(),
                    musicApiBaseUrl.getText().trim(),
                    musicApiSharedSecret.getText().trim(),
                    "",
                    "",
                    "",
                    8192,
                    ypr,
                    "",
                    "",
                    "",
                    showWeatherLegend.isSelected(),
                    showSettlerRecommendations.isSelected(),
                    showClaimLegend.isSelected(),
                    !showIntroductionTips.isSelected(),
                    minimapTintOwnClaimsOnly.isSelected(),
                    ((Number) settleWeightFood.getValue()).intValue(),
                    ((Number) settleWeightProduction.getValue()).intValue(),
                    ((Number) settleWeightGold.getValue()).intValue(),
                    ((Number) settleWeightTravel.getValue()).intValue(),
                    ((Number) settleWeightRivalPressure.getValue()).intValue(),
                    selectedLaunchMode());

            flushGraphicDetailToMaps();
            var assigns = new LinkedHashMap<String, String>();
            for (GraphicRuntime.SlotDescriptor slot : GraphicRuntime.catalogSlotsInOrder()) {
                assigns.put(slot.id(), graphicAssignmentTokens.getOrDefault(slot.id(), "default"));
            }
            if (!assigns.isEmpty()) {
                GraphicRuntime.persistAssignmentTokens(assigns);
            }
            GraphicRuntime.reloadFromDisk();

            onSaved.run();
            pathHint.setForeground(INK_MUTED);
            pathHint.setText("Saved to: " + tildePath(GameOptions.userFilePath().getParent()));
            pathHint.setToolTipText(GameOptions.userFilePath().getParent().toString());
            if (notifyOnSuccess) {
                JOptionPane.showMessageDialog(this, "Settings saved.");
            }
        } catch (Exception ex) {
            if (notifyOnSuccess) {
                JOptionPane.showMessageDialog(this, "Could not save settings:\n" + ex.getMessage(),
                        "Settings error", JOptionPane.ERROR_MESSAGE);
            } else {
                pathHint.setForeground(new Color(0xf3_8b_8b));
                pathHint.setText("Auto-apply failed: " + (ex.getMessage() == null ? "Unknown error" : ex.getMessage()));
            }
        }
    }

    private WindowLaunchMode selectedLaunchMode() {
        Object v = launchMode.getSelectedItem();
        if ("Windowed".equals(v)) return WindowLaunchMode.WINDOWED;
        if ("Native fullscreen".equals(v)) return WindowLaunchMode.NATIVE_FULLSCREEN;
        return WindowLaunchMode.MAXIMIZED;
    }

    private void testMusicConnection(JButton button) {
        button.setEnabled(false);
        testStatus.setForeground(INK_MUTED);
        testStatus.setText("Testing...");
        var api = musicApiBaseUrl.getText().trim();
        var secret = musicApiSharedSecret.getText().trim();
        new SwingWorker<MusicSyncClient.TestResult, Void>() {
            @Override
            protected MusicSyncClient.TestResult doInBackground() {
                return new MusicSyncClient().testConnection(api, secret);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    var r = get();
                    testStatus.setForeground(r.ok() ? new Color(0x7c_d6_8c) : new Color(0xf3_8b_8b));
                    testStatus.setText(r.message());
                } catch (Exception ex) {
                    testStatus.setForeground(new Color(0xf3_8b_8b));
                    testStatus.setText("Test failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void clearLocalMusicCache(JButton button) {
        int existing = MusicSyncClient.countLocalBundles();
        String prompt = existing == 0
                ? "There are no local music bundles. Clear any leftover extracted files?"
                : "Delete " + existing + " downloaded music bundle"
                        + (existing == 1 ? "" : "s")
                        + " and any extracted files?\nYou can re-download from the server later.";
        int choice = JOptionPane.showConfirmDialog(
                this,
                prompt,
                "Clear local music cache",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        button.setEnabled(false);
        cacheStatus.setForeground(INK_MUTED);
        cacheStatus.setText("Clearing...");
        new SwingWorker<MusicSyncClient.CacheClearResult, Void>() {
            @Override
            protected MusicSyncClient.CacheClearResult doInBackground() {
                return MusicSyncClient.clearLocalCache();
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    var r = get();
                    if (r.errors().isEmpty()) {
                        cacheStatus.setForeground(new Color(0x7c_d6_8c));
                        cacheStatus.setText("Removed " + r.removedFiles()
                                + " file(s), freed " + formatBytes(r.freedBytes()) + ".");
                    } else {
                        cacheStatus.setForeground(new Color(0xf3_8b_8b));
                        cacheStatus.setText("Removed " + r.removedFiles() + " file(s) with "
                                + r.errors().size() + " error(s).");
                        JOptionPane.showMessageDialog(
                                SettingsPanel.this,
                                String.join("\n", r.errors()),
                                "Cache clear details",
                                JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    cacheStatus.setForeground(new Color(0xf3_8b_8b));
                    cacheStatus.setText("Clear failed: " + ex.getMessage());
                }
            }
        }.execute();
    }



    private void uploadImageForCurrentElement() {
        if (graphicDetailSlot == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose PNG for " + graphicDetailSlot.label());
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("PNG images (*.png)", "png"));
        int pick = chooser.showOpenDialog(this);
        if (pick != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!Files.isRegularFile(selected)) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Selected file not found.");
            return;
        }
        graphicUploadStatus.setForeground(INK_MUTED);
        graphicUploadStatus.setText("Importing\u2026");
        try {
            byte[] bytes = Files.readAllBytes(selected);
            if (!looksLikePng(bytes)) {
                graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
                graphicUploadStatus.setText("Not a valid PNG file.");
                return;
            }
            String slotId = graphicDetailSlot.id();
            String token = GraphicRuntime.saveUploadAndMakeToken(slotId, bytes);
            applyAssignmentToSlot(graphicDetailSlot, token);
            graphicUploadStatus.setForeground(new Color(0x7c_d6_8c));
            graphicUploadStatus.setText("\u2714 Installed \u2014 image appears in Library above.");
        } catch (Exception ex) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Upload failed \u2014 see details.");
            showLongErrorDialog(this, "Upload image", ex.getMessage() == null ? "Unknown error" : ex.getMessage());
        }
    }

    private void renameSelectedImageForCurrentElement() {
        if (graphicDetailSlot == null) {
            return;
        }
        String token = selectedArtSourceToken();
        if (!GraphicRuntime.isUploadToken(token)) {
            return;
        }
        String oldFile = GraphicRuntime.uploadFileNameFromToken(token);
        String base = oldFile.toLowerCase().endsWith(".png")
                ? oldFile.substring(0, oldFile.length() - 4)
                : oldFile;
        String entered = JOptionPane.showInputDialog(
                this,
                "Rename image file (no extension needed):",
                base);
        if (entered == null) {
            return;
        }
        String trimmed = entered.strip();
        if (trimmed.isEmpty()) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Rename cancelled: name is empty.");
            return;
        }
        try {
            Path renamed = GraphicSlotUploads.renameUpload(graphicDetailSlot.id(), oldFile, trimmed);
            String newToken = GraphicRuntime.makeUploadToken(renamed.getFileName().toString());
            applyAssignmentToSlot(graphicDetailSlot, newToken);
            graphicUploadStatus.setForeground(new Color(0x7c_d6_8c));
            graphicUploadStatus.setText("Renamed to " + renamed.getFileName() + ".");
        } catch (Exception ex) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Rename failed.");
            showLongErrorDialog(this, "Rename image", ex.getMessage() == null ? "Unknown error" : ex.getMessage());
        }
    }

    private void deleteSelectedImageForCurrentElement() {
        if (graphicDetailSlot == null) {
            return;
        }
        String token = selectedArtSourceToken();
        if (!GraphicRuntime.isUploadToken(token)) {
            return;
        }
        String fileName = GraphicRuntime.uploadFileNameFromToken(token);
        int r = JOptionPane.showConfirmDialog(
                this,
                "Delete selected image?\n" + fileName,
                "Delete uploaded image",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) {
            return;
        }
        boolean ok = GraphicSlotUploads.deleteUpload(graphicDetailSlot.id(), fileName);
        if (!ok) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Delete failed.");
            return;
        }
        try {
            applyAssignmentToSlot(graphicDetailSlot, "default");
            graphicUploadStatus.setForeground(new Color(0x7c_d6_8c));
            graphicUploadStatus.setText("Deleted " + fileName + ".");
        } catch (Exception ex) {
            graphicUploadStatus.setForeground(new Color(0xf3_8b_8b));
            graphicUploadStatus.setText("Deleted file, but could not refresh assignment.");
            showLongErrorDialog(this, "Delete uploaded image", ex.getMessage() == null ? "Unknown error" : ex.getMessage());
        }
    }

    private void copyChatGptPromptForCurrentElement() {
        if (graphicDetailSlot == null) {
            return;
        }
        String prompt = buildChatGptPrompt(graphicDetailSlot);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(prompt), null);
        graphicUploadStatus.setForeground(new Color(0x7c_d6_8c));
        graphicUploadStatus.setText("Copied ChatGPT prompt for " + graphicDetailSlot.label() + ".");
    }

    private static String buildChatGptPrompt(GraphicRuntime.SlotDescriptor slot) {
        String label = slot.label() == null ? "Unit" : slot.label();
        String id = slot.id() == null ? "" : slot.id();
        boolean terrain = id.startsWith("terrain.");
        String renderHint = slot.imageReturnSpec() == null ? "" : slot.imageReturnSpec().strip();
        String extra = renderHint.isEmpty() ? "" : ("\nReference rendering target: " + renderHint);
        if (terrain) {
            return "Create a game-ready terrain texture PNG for a hex strategy game.\n"
                    + "Subject: " + label + " (" + id + ").\n"
                    + "CRITICAL CAMERA: orthographic straight-on TOP-DOWN view only (90-degree overhead).\n"
                    + "Do NOT use isometric view, perspective view, angled camera, horizon, side walls, cliffs, or any visible tile thickness.\n"
                    + "Composition: full hex surface texture only, centered, fills most of frame, seamless-looking edges.\n"
                    + "Style: painterly fantasy RTS map texture, readable at small size, no text, no watermark.\n"
                    + "Background: transparent outside the terrain shape; no black backdrop.\n"
                    + "Output: PNG, square, 1024x1024." + extra + "\n"
                    + "Return only the final image.";
        }
        return "Create a game-ready transparent PNG sprite for a hex strategy game.\n"
                + "Subject: " + label + " (" + id + ").\n"
                + "Style: painterly fantasy RTS icon, clear silhouette, no text, no watermark.\n"
                + "Framing: centered character, full body visible, leave padding around edges.\n"
                + "Lighting/background: transparent background only, no scene background.\n"
                + "Output: PNG, square, 1024x1024." + extra + "\n"
                + "Return only the final image.";
    }

    /** Persists {@code token} for {@code slot}, reloads runtime + UI, and refreshes the map renderer. */
    private void applyAssignmentToSlot(GraphicRuntime.SlotDescriptor slot, String token) throws IOException {
        if (slot == null || token == null || token.isBlank()) {
            return;
        }
        flushGraphicDetailToMaps();
        graphicAssignmentTokens.put(slot.id(), token);
        GraphicRuntime.setSlotAssignment(slot.id(), token);
        GraphicRuntime.reloadFromDisk();
        reloadGraphicStateFromDisk();
        rebuildGraphicGrid();
        onSaved.run();
        if (graphicDetailSlot != null && graphicDetailSlot.id().equals(slot.id())) {
            graphicDetailSlot = GraphicRuntime.catalogSlotsInOrder().stream()
                    .filter(s -> s.id().equals(slot.id()))
                    .findFirst()
                    .orElse(slot);
            populateGraphicSourceLibrary(graphicDetailSlot);
            refreshGraphicDetailPreview();
        }
    }

    private static boolean looksLikePng(byte[] bytes) {
        return bytes != null
                && bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G';
    }


    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void bidirectionalSync(JButton button) {
        button.setEnabled(false);
        syncStatus.setForeground(INK_MUTED);
        syncStatus.setText("Syncing…");
        var api = musicApiBaseUrl.getText().trim();
        var secret = musicApiSharedSecret.getText().trim();
        new SwingWorker<MusicSyncClient.FullSyncResult, MusicSyncClient.FullProgress>() {
            @Override
            protected MusicSyncClient.FullSyncResult doInBackground() {
                return new MusicSyncClient().syncBidirectional(api, secret, this::publish);
            }

            @Override
            protected void process(java.util.List<MusicSyncClient.FullProgress> chunks) {
                if (chunks.isEmpty()) return;
                var last = chunks.get(chunks.size() - 1);
                String phase = last.phase() == MusicSyncClient.SyncPhase.UPLOAD ? "Upload" : "Download";
                if (last.phase() == MusicSyncClient.SyncPhase.UPLOAD) {
                    syncStatus.setText(phase + ": " + last.stepIndex() + "/" + last.stepTotal()
                            + " — " + truncate(last.filename(), 28));
                } else {
                    syncStatus.setText(phase + ": " + last.stepIndex() + "/" + last.stepTotal());
                }
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    var r = get();
                    if (r.errors().isEmpty()) {
                        syncStatus.setForeground(new Color(0x7c_d6_8c));
                        syncStatus.setText("Done. Uploaded " + r.uploaded() + ", pulled " + r.downloaded()
                                + " new (skipped " + (r.uploadSkipped() + r.downloadSkipped()) + " unchanged).");
                    } else {
                        syncStatus.setForeground(new Color(0xf3_8b_8b));
                        syncStatus.setText("Finished with " + r.errors().size() + " issue(s). See dialog.");
                        JOptionPane.showMessageDialog(
                                SettingsPanel.this,
                                String.join("\n", r.errors()),
                                "Music sync details",
                                JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    syncStatus.setForeground(new Color(0xf3_8b_8b));
                    syncStatus.setText("Sync failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void syncMusicBundles(JButton button) {
        button.setEnabled(false);
        syncStatus.setText("Syncing...");
        var api = musicApiBaseUrl.getText().trim();
        var secret = musicApiSharedSecret.getText().trim();
        new SwingWorker<MusicSyncClient.SyncResult, Void>() {
            @Override
            protected MusicSyncClient.SyncResult doInBackground() {
                return new MusicSyncClient().syncToServer(api, secret);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    var r = get();
                    if (r.errors().isEmpty()) {
                        syncStatus.setText("Sync complete: " + r.uploaded() + "/" + r.attempted() + " uploaded.");
                    } else {
                        syncStatus.setText("Sync finished with issues: " + r.uploaded() + "/" + r.attempted() + " uploaded.");
                        JOptionPane.showMessageDialog(
                                SettingsPanel.this,
                                String.join("\n", r.errors()),
                                "Music sync details",
                                JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    syncStatus.setText("Sync failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
}

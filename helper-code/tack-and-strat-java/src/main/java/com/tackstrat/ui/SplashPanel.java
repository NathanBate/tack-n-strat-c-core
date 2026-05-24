package com.tackstrat.ui;

import com.tackstrat.persistence.GameSaveFiles;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Path2D;

final class SplashPanel extends JPanel {

    private static final Color CALLOUT_BG = new Color(0x14_1f_31);
    private static final Color CALLOUT_BORDER = new Color(0xf5_a8_25);
    private static final Color CALLOUT_TITLE = new Color(0xf5_a8_25);
    private static final Color CALLOUT_BODY = new Color(0xc6_d2_e3);
    private static final Color CALLOUT_MUTED = new Color(0x8d_9a_ad);
    private static final Color CALLOUT_OK = new Color(0x7c_d6_8c);
    private static final Color CALLOUT_ERR = new Color(0xf3_8b_8b);

    private enum CalloutMode { HIDDEN, CONFIGURE, DOWNLOAD, BUSY, RESULT }

    private final Runnable onContinueLastGame;
    private final Runnable onSettings;
    private final Runnable onBidirectionalSync;

    private final Timer animTimer;
    private final JButton continueGame = new JButton("Continue last game");
    private final JPanel menuBox = new JPanel();
    private final JPanel calloutBox = new JPanel();
    private final JLabel calloutTitle = new JLabel(" ");
    private final JLabel calloutBody = new JLabel(" ");
    private final JButton calloutAction = new JButton(" ");
    private final JLabel calloutSecondary = new JLabel(" ");
    private CalloutMode calloutMode = CalloutMode.HIDDEN;
    private ActionListener calloutListener;

    private double phase;
    private float fade = 1f;
    private boolean introMode = true;
    private boolean exiting;

    SplashPanel(
            Runnable onContinueLastGame,
            Runnable onNewGame,
            Runnable onLoadGame,
            Runnable onSettings,
            Runnable onBidirectionalSync) {
        this.onContinueLastGame = onContinueLastGame;
        this.onSettings = onSettings;
        this.onBidirectionalSync = onBidirectionalSync;
        setOpaque(true);
        setBackground(new Color(0x08_14_24));
        setLayout(null);
        setFocusable(true);

        menuBox.setOpaque(false);
        menuBox.setLayout(new BoxLayout(menuBox, BoxLayout.Y_AXIS));
        menuBox.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));

        continueGame.setAlignmentX(Component.CENTER_ALIGNMENT);
        continueGame.setMaximumSize(new java.awt.Dimension(220, 40));
        continueGame.setFont(continueGame.getFont().deriveFont(Font.BOLD, 14f));
        continueGame.addActionListener(e -> {
            if (continueGame.isEnabled()) {
                onContinueLastGame.run();
            }
        });
        refreshContinueAvailability();

        var newGame = new JButton("New game");
        newGame.setAlignmentX(Component.CENTER_ALIGNMENT);
        newGame.setMaximumSize(new java.awt.Dimension(220, 40));
        newGame.setFont(newGame.getFont().deriveFont(Font.BOLD, 14f));
        newGame.addActionListener(e -> onNewGame.run());

        var loadGame = new JButton("Load saved game...");
        loadGame.setAlignmentX(Component.CENTER_ALIGNMENT);
        loadGame.setMaximumSize(new java.awt.Dimension(220, 36));
        loadGame.addActionListener(e -> onLoadGame.run());

        var settings = new JButton("Settings");
        settings.setAlignmentX(Component.CENTER_ALIGNMENT);
        settings.setMaximumSize(new java.awt.Dimension(220, 34));
        settings.addActionListener(e -> onSettings.run());

        menuBox.add(continueGame);
        menuBox.add(Box.createVerticalStrut(10));
        menuBox.add(newGame);
        menuBox.add(Box.createVerticalStrut(10));
        menuBox.add(loadGame);
        menuBox.add(Box.createVerticalStrut(10));
        menuBox.add(settings);
        menuBox.setVisible(false);
        add(menuBox);

        configureCalloutBox();
        add(calloutBox);

        animTimer = new Timer(32, e -> {
            phase += 0.06;
            if (introMode && exiting) {
                fade -= 0.06f;
                if (fade <= 0f) {
                    fade = 0f;
                    introMode = false;
                    menuBox.setVisible(true);
                    calloutBox.setVisible(calloutMode != CalloutMode.HIDDEN);
                }
            }
            repaint();
        });
        animTimer.start();
        var auto = new Timer(2200, e -> beginExit());
        auto.setRepeats(false);
        auto.start();
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                beginExit();
            }
        });
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                beginExit();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!introMode) {
                    if (e.getKeyCode() == KeyEvent.VK_C && continueGame.isEnabled()) {
                        onContinueLastGame.run();
                    }
                    if (e.getKeyCode() == KeyEvent.VK_N || e.getKeyCode() == KeyEvent.VK_ENTER) onNewGame.run();
                    if (e.getKeyCode() == KeyEvent.VK_L) onLoadGame.run();
                    if (e.getKeyCode() == KeyEvent.VK_S) onSettings.run();
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        var bg = new GradientPaint(
                0, 0, new Color(0x08_14_24),
                0, getHeight(), new Color(0x1b_30_4c));
        g2.setPaint(bg);
        g2.fillRect(0, 0, getWidth(), getHeight());

        paintBackdropHexes(g2);

        String title = "Tack & Strat";
        String subtitle = "A hex strategy skirmish";
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 58));
        var fm = g2.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(title)) / 2;
        int y = (int) (getHeight() * 0.50);

        g2.setColor(new Color(0, 0, 0, 130));
        g2.drawString(title, x + 3, y + 3);
        g2.setColor(new Color(0xee_f4_ff));
        g2.drawString(title, x, y);

        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
        var sm = g2.getFontMetrics();
        int sx = (getWidth() - sm.stringWidth(subtitle)) / 2;
        g2.setColor(new Color(0xcd_db_ef));
        g2.drawString(subtitle, sx, y + 34);

        int dots = ((int) (phase * 3.2)) % 4;
        if (introMode) {
            String loading = "Loading" + ".".repeat(dots);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            var lm = g2.getFontMetrics();
            int lx = (getWidth() - lm.stringWidth(loading)) / 2;
            g2.setColor(new Color(0xbf_ce_e6, true));
            g2.drawString(loading, lx, y + 72);
            g2.setColor(new Color(0xb4_c3_da));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            String skip = "Press any key or click to continue";
            int skx = (getWidth() - g2.getFontMetrics().stringWidth(skip)) / 2;
            g2.drawString(skip, skx, y + 96);
        } else {
            // Draw hints *below* the button stack — fixed offsets from the title used to overlap the menu.
            Rectangle mb = menuBox.getBounds();
            int hintBaseline = mb.y + mb.height + 22;
            String shortcuts = "C: Continue  ·  N: New  ·  L: Load  ·  S: Settings";
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            var shortcutFm = g2.getFontMetrics();
            int hx = (getWidth() - shortcutFm.stringWidth(shortcuts)) / 2;
            g2.setColor(new Color(0xbf_ce_e6));
            g2.drawString(shortcuts, hx, hintBaseline);
        }

        if (introMode && fade < 1f) {
            g2.setComposite(AlphaComposite.SrcOver.derive(1f - fade));
            g2.setColor(new Color(8, 20, 36));
            g2.fillRect(0, 0, getWidth(), getHeight());
        }
        g2.dispose();
    }

    void beginExit() {
        if (!introMode) return;
        exiting = true;
        requestFocusInWindow();
    }

    @Override
    public void doLayout() {
        super.doLayout();
        int w = 250;
        int h = 196;
        int x = (getWidth() - w) / 2;
        int y = (int) (getHeight() * 0.55);
        menuBox.setBounds(x, y, w, h);

        int cw = 380;
        int ch = 130;
        int cx = (getWidth() - cw) / 2;
        int cy = y + h + 18;
        calloutBox.setBounds(cx, cy, cw, ch);
    }

    private void configureCalloutBox() {
        calloutBox.setOpaque(true);
        calloutBox.setBackground(CALLOUT_BG);
        calloutBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CALLOUT_BORDER, 1),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        calloutBox.setLayout(new BoxLayout(calloutBox, BoxLayout.Y_AXIS));

        calloutTitle.setForeground(CALLOUT_TITLE);
        calloutTitle.setFont(calloutTitle.getFont().deriveFont(Font.BOLD, 13f));
        calloutTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        calloutBody.setForeground(CALLOUT_BODY);
        calloutBody.setFont(calloutBody.getFont().deriveFont(12f));
        calloutBody.setAlignmentX(Component.LEFT_ALIGNMENT);

        calloutAction.setAlignmentX(Component.LEFT_ALIGNMENT);
        calloutAction.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        calloutSecondary.setForeground(CALLOUT_MUTED);
        calloutSecondary.setFont(calloutSecondary.getFont().deriveFont(11f));
        calloutSecondary.setAlignmentX(Component.LEFT_ALIGNMENT);

        calloutBox.add(calloutTitle);
        calloutBox.add(Box.createVerticalStrut(4));
        calloutBox.add(calloutBody);
        calloutBox.add(Box.createVerticalStrut(8));
        calloutBox.add(calloutAction);
        calloutBox.add(Box.createVerticalStrut(4));
        calloutBox.add(calloutSecondary);
        calloutBox.setVisible(false);
    }

    void refreshContinueAvailability() {
        boolean ok = GameSaveFiles.autosaveExists();
        continueGame.setEnabled(ok);
        continueGame.setToolTipText(ok ? "Resume your last session (autosaved each turn)" : "No autosave yet");
    }

    /** Recomputes which callout (if any) should be shown, based on saved options + local bundles. */
    void refreshCallout() {
        if (calloutMode == CalloutMode.BUSY) return;
        var opts = GameOptions.load();
        boolean configured = !opts.musicApiBaseUrl().isBlank() && !opts.musicApiSharedSecret().isBlank();
        if (!configured) {
            setCalloutMode(CalloutMode.CONFIGURE,
                    "Music sync isn't set up",
                    "Add your music server URL and shared secret to download tracks.",
                    "Open Settings",
                    e -> onSettings.run(),
                    " ",
                    CALLOUT_MUTED);
        } else if (MusicSyncClient.countLocalBundles() == 0) {
            setCalloutMode(CalloutMode.DOWNLOAD,
                    "No music synced yet",
                    "Sync with your music server (upload any local zips, then pull missing bundles).",
                    "Sync music",
                    e -> {
                        if (onBidirectionalSync != null) onBidirectionalSync.run();
                    },
                    " ",
                    CALLOUT_MUTED);
        } else {
            setCalloutMode(CalloutMode.HIDDEN, null, null, null, null, null, null);
        }
    }

    /** Marks the callout as busy; called by parent before kicking off the worker. */
    void notifySyncStarted() {
        setCalloutMode(CalloutMode.BUSY,
                "Syncing music…",
                "Fetching manifest and comparing local files.",
                "Working…",
                null,
                " ",
                CALLOUT_MUTED);
        calloutAction.setEnabled(false);
    }

    void notifySyncProgress(MusicSyncClient.FullProgress p) {
        if (calloutMode != CalloutMode.BUSY || p == null) return;
        if (p.phase() == MusicSyncClient.SyncPhase.UPLOAD) {
            calloutBody.setText("Uploading " + truncate(p.filename(), 48));
            calloutSecondary.setText("Upload " + p.stepIndex() + "/" + p.stepTotal());
        } else {
            if (p.filename() != null) {
                calloutBody.setText("Downloading " + truncate(p.filename(), 48));
            }
            calloutSecondary.setText("Download " + p.stepIndex() + "/" + p.stepTotal());
        }
    }

    void notifySyncFinished(String summary, boolean ok) {
        setCalloutMode(CalloutMode.RESULT,
                ok ? "Music sync complete" : "Music sync had issues",
                summary,
                "Done",
                e -> refreshCallout(),
                " ",
                ok ? CALLOUT_OK : CALLOUT_ERR);
        calloutAction.setEnabled(true);
    }

    private void setCalloutMode(
            CalloutMode mode,
            String title,
            String body,
            String actionText,
            ActionListener listener,
            String secondary,
            Color secondaryColor) {
        calloutMode = mode;
        if (mode == CalloutMode.HIDDEN) {
            calloutBox.setVisible(false);
            return;
        }
        calloutTitle.setText(title);
        calloutBody.setText(body);
        calloutAction.setText(actionText);
        if (calloutListener != null) {
            calloutAction.removeActionListener(calloutListener);
            calloutListener = null;
        }
        if (listener != null) {
            calloutListener = listener;
            calloutAction.addActionListener(listener);
        }
        calloutAction.setEnabled(listener != null);
        calloutSecondary.setText(secondary == null || secondary.isEmpty() ? " " : secondary);
        if (secondaryColor != null) calloutSecondary.setForeground(secondaryColor);
        calloutBox.setVisible(!introMode);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private void paintBackdropHexes(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.2f));
        double r = 34;
        for (int row = 0; row < 7; row++) {
            for (int col = 0; col < 13; col++) {
                double cx = 90 + col * r * 1.7 + (row % 2 == 0 ? 0 : r * 0.85);
                double cy = 110 + row * r * 1.45;
                double glow = 0.5 + 0.5 * Math.sin(phase + row * 0.7 + col * 0.3);
                int a = 18 + (int) (glow * 42);
                g2.setColor(new Color(130, 180, 255, a));
                g2.fill(hexPath(cx, cy, r * 0.92));
                g2.setColor(new Color(180, 210, 255, a + 24));
                g2.draw(hexPath(cx, cy, r));
            }
        }
    }

    private static Path2D hexPath(double cx, double cy, double r) {
        var p = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 3;
            double x = cx + r * Math.cos(a);
            double y = cy + r * Math.sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }
}

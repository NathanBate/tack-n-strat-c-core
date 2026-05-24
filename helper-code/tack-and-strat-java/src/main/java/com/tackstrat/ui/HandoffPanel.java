package com.tackstrat.ui;

import com.tackstrat.model.GameSession;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.AbstractAction;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;

/** Full-screen "pass to next player" gate so the seated player gets a clean intro. */
final class HandoffPanel extends JPanel {

    private final JLabel titleLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel metaLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel hint = new JLabel("Press Enter (or click Begin Turn) when ready.", SwingConstants.CENTER);
    private final JPanel swatch = new JPanel();
    private final JButton begin = new JButton("Begin turn");
    private final JButton retryAutosave = new JButton("Retry autosave");

    HandoffPanel(Runnable onBegin, Runnable onRetryAutosave) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));

        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 28f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        metaLabel.setForeground(new Color(0xa8_b0_bc));
        metaLabel.setFont(metaLabel.getFont().deriveFont(13f));
        metaLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        swatch.setMaximumSize(new Dimension(80, 18));
        swatch.setPreferredSize(new Dimension(80, 18));
        swatch.setAlignmentX(Component.CENTER_ALIGNMENT);

        hint.setForeground(new Color(0xb8_c0_cc));
        hint.setFont(hint.getFont().deriveFont(14f));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        begin.setAlignmentX(Component.CENTER_ALIGNMENT);
        begin.addActionListener(e -> onBegin.run());

        retryAutosave.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryAutosave.addActionListener(e -> onRetryAutosave.run());
        retryAutosave.setVisible(false);

        var im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        var am = getActionMap();
        im.put(KeyStroke.getKeyStroke("ENTER"), "beginTurn");
        am.put("beginTurn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onBegin.run();
            }
        });
        // Prevent spacebar from acting like "begin turn" through focused button behavior.
        im.put(KeyStroke.getKeyStroke("SPACE"), "noopSpace");
        am.put("noopSpace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Intentionally no-op.
            }
        });

        add(Box.createVerticalGlue());
        add(titleLabel);
        add(Box.createVerticalStrut(10));
        add(metaLabel);
        add(Box.createVerticalStrut(14));
        add(swatch);
        add(Box.createVerticalStrut(20));
        add(hint);
        add(Box.createVerticalStrut(24));
        add(begin);
        add(Box.createVerticalStrut(12));
        add(retryAutosave);
        add(Box.createVerticalGlue());
    }

    void show(GameSession session, boolean autosaveOk) {
        var p = session.currentPlayer();
        int round = session.round();
        String role = p.computer() ? " (computer)" : "";
        titleLabel.setText("Round " + round + " — pass to " + p.name() + role);
        String saveLine = autosaveOk
                ? "<span style='color:#7dffc8'>Autosave checkpoint OK</span>"
                : "<span style='color:#ff9a8a'>Autosave failed (session continues)</span>";
        metaLabel.setText("<html><div style='width:520px;text-align:center'>"
                + escape(session.calendarEraLabel())
                + " · "
                + escape(session.season().label())
                + "<br>"
                + escape(session.weatherHudSummary())
                + "<br>"
                + saveLine
                + "</div></html>");
        swatch.setBackground(UiTheme.PLAYER[p.seat() % UiTheme.PLAYER.length]);
        retryAutosave.setVisible(!autosaveOk);
        revalidate();
        repaint();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    JButton beginButton() {
        return begin;
    }
}

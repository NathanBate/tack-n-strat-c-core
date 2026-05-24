package com.tackstrat.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

final class MenuPanel extends JPanel {

    MenuPanel(Runnable onNewGame, Runnable onLoadGame, Runnable onSettings) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(60, 40, 60, 40));

        var title = new JLabel("Tack & Strat", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 44f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        var subtitle = new JLabel("A tiny family 4X. Hot-seat. First to 3 cities wins.", SwingConstants.CENTER);
        subtitle.setForeground(new Color(0xb8_c0_cc));
        subtitle.setFont(subtitle.getFont().deriveFont(16f));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        var newGame = new JButton("New game");
        newGame.setFont(newGame.getFont().deriveFont(Font.BOLD, 14f));
        newGame.setAlignmentX(Component.CENTER_ALIGNMENT);
        newGame.setMaximumSize(new Dimension(200, 40));
        newGame.addActionListener(e -> onNewGame.run());

        var loadGame = new JButton("Load saved game…");
        loadGame.setAlignmentX(Component.CENTER_ALIGNMENT);
        loadGame.setMaximumSize(new Dimension(200, 36));
        loadGame.addActionListener(e -> onLoadGame.run());

        var settings = new JButton("Settings");
        settings.setAlignmentX(Component.CENTER_ALIGNMENT);
        settings.setMaximumSize(new Dimension(200, 34));
        settings.addActionListener(e -> onSettings.run());

        add(Box.createVerticalGlue());
        add(title);
        add(Box.createVerticalStrut(14));
        add(subtitle);
        add(Box.createVerticalStrut(34));
        add(newGame);
        add(Box.createVerticalStrut(10));
        add(loadGame);
        add(Box.createVerticalStrut(10));
        add(settings);
        add(Box.createVerticalGlue());
    }
}

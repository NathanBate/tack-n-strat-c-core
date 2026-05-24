package com.tackstrat.ui;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;

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
import java.awt.FlowLayout;

final class VictoryPanel extends JPanel {

    private final JLabel headline = new JLabel("", SwingConstants.CENTER);
    private final JLabel recap = new JLabel("", SwingConstants.CENTER);
    private final JPanel swatch = new JPanel();

    VictoryPanel(Runnable onMenu, Runnable onPlayAgain) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));

        var crown = new JLabel("Victory", SwingConstants.CENTER);
        crown.setForeground(new Color(0xff_d5_4a));
        crown.setFont(crown.getFont().deriveFont(Font.BOLD, 32f));
        crown.setAlignmentX(Component.CENTER_ALIGNMENT);

        headline.setForeground(Color.WHITE);
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 22f));
        headline.setAlignmentX(Component.CENTER_ALIGNMENT);

        recap.setForeground(new Color(0xc8_d0_dc));
        recap.setFont(recap.getFont().deriveFont(13f));
        recap.setAlignmentX(Component.CENTER_ALIGNMENT);

        swatch.setMaximumSize(new Dimension(80, 18));
        swatch.setPreferredSize(new Dimension(80, 18));
        swatch.setAlignmentX(Component.CENTER_ALIGNMENT);

        var actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        var menu = new JButton("Main menu");
        var again = new JButton("Play again");
        menu.addActionListener(e -> onMenu.run());
        again.addActionListener(e -> onPlayAgain.run());
        actions.add(menu);
        actions.add(again);

        add(Box.createVerticalGlue());
        add(crown);
        add(Box.createVerticalStrut(18));
        add(headline);
        add(Box.createVerticalStrut(12));
        add(recap);
        add(Box.createVerticalStrut(14));
        add(swatch);
        add(Box.createVerticalStrut(28));
        add(actions);
        add(Box.createVerticalGlue());
    }

    void show(GameSession session, Player winner) {
        headline.setText(winner.name() + " controls 3 cities!");
        recap.setText(session.victoryRecapHtml(winner));
        swatch.setBackground(UiTheme.PLAYER[winner.seat() % UiTheme.PLAYER.length]);
        revalidate();
        repaint();
    }
}

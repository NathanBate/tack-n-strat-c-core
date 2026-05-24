package com.tackstrat.ui;

import com.tackstrat.model.GameSession;
import com.tackstrat.model.Player;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

final class SetupPanel extends JPanel {

    private final Consumer<GameSession> onStart;
    private final JSpinner countSpinner;
    private final JPanel namesGrid;
    private final List<JTextField> nameFields = new ArrayList<>();
    private final List<JCheckBox> aiBoxes = new ArrayList<>();

    SetupPanel(Consumer<GameSession> onStart, Runnable onBack) {
        super(new BorderLayout());
        this.onStart = onStart;
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(60, 40, 40, 40));

        var center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        var title = new JLabel("New game", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        var hint = new JLabel(
                "1–4 seats. Pass the laptop between humans; tick CPU for automated opponents.", SwingConstants.CENTER);
        hint.setForeground(new Color(0xb8_c0_cc));
        hint.setFont(hint.getFont().deriveFont(14f));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        var card = new JPanel(new BorderLayout(8, 12));
        card.setBackground(UiTheme.SIDEBAR_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.SIDEBAR_LINE, 1),
                BorderFactory.createEmptyBorder(20, 24, 20, 24)));
        card.setMaximumSize(new Dimension(520, Integer.MAX_VALUE));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);

        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.setOpaque(false);
        top.add(new JLabel("Players:"));
        countSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        ((JSpinner.DefaultEditor) countSpinner.getEditor()).getTextField().setColumns(2);
        top.add(countSpinner);

        namesGrid = new JPanel(new GridBagLayout());
        namesGrid.setOpaque(false);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        var back = new JButton("Back");
        back.addActionListener(e -> onBack.run());
        var start = new JButton("Start game");
        start.setFont(start.getFont().deriveFont(Font.BOLD));
        start.addActionListener(e -> startGame());
        actions.add(back);
        actions.add(start);

        card.add(top, BorderLayout.NORTH);
        card.add(namesGrid, BorderLayout.CENTER);
        card.add(actions, BorderLayout.SOUTH);

        ChangeListener rebuild = e -> rebuildNameFields();
        countSpinner.addChangeListener(rebuild);
        rebuildNameFields();

        center.add(Box.createVerticalGlue());
        center.add(title);
        center.add(Box.createVerticalStrut(8));
        center.add(hint);
        center.add(Box.createVerticalStrut(28));
        center.add(card);
        center.add(Box.createVerticalGlue());
        add(center, BorderLayout.CENTER);
    }

    private void rebuildNameFields() {
        namesGrid.removeAll();
        nameFields.clear();
        aiBoxes.clear();
        int n = (Integer) countSpinner.getValue();

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        for (int i = 0; i < n; i++) {
            gbc.gridx = 0;
            gbc.weightx = 0;
            var swatch = new JPanel();
            swatch.setBackground(UiTheme.PLAYER[i % UiTheme.PLAYER.length]);
            swatch.setPreferredSize(new Dimension(14, 22));
            namesGrid.add(swatch, gbc);

            gbc.gridx = 1;
            gbc.weightx = 0;
            namesGrid.add(new JLabel("Player " + (i + 1) + ":"), gbc);

            gbc.gridx = 2;
            gbc.weightx = 1;
            var field = new JTextField(defaultName(i), 18);
            nameFields.add(field);
            namesGrid.add(field, gbc);

            gbc.gridx = 3;
            gbc.weightx = 0;
            var cpu = new JCheckBox("CPU");
            cpu.setOpaque(false);
            cpu.setForeground(new Color(0xb8_c0_cc));
            cpu.setToolTipText("Computer plays this seat automatically (skips pass-device for them).");
            aiBoxes.add(cpu);
            namesGrid.add(cpu, gbc);

            gbc.gridy++;
        }
        namesGrid.revalidate();
        namesGrid.repaint();
    }

    private static String defaultName(int seat) {
        return "Player " + (seat + 1);
    }

    private void startGame() {
        int n = nameFields.size();
        boolean everyCpu = n > 0;
        for (int i = 0; i < n; i++) {
            if (!aiBoxes.get(i).isSelected()) {
                everyCpu = false;
                break;
            }
        }
        if (everyCpu) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Every seat is set to CPU. No human will control any player.\nStill start this session?",
                    "All CPU seats",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        var list = new ArrayList<Player>(n);
        for (int i = 0; i < n; i++) {
            var raw = nameFields.get(i).getText().trim();
            var name = raw.isEmpty() ? defaultName(i) : raw;
            list.add(new Player(i, name, aiBoxes.get(i).isSelected()));
        }
        int years = GameOptions.load().yearsPerFullRound();
        onStart.accept(new GameSession(list, new Random().nextLong(), years));
    }
}

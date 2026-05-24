package com.tackstrat.ui;

import com.tackstrat.persistence.GamePersistence;
import com.tackstrat.persistence.SaveEntrySummary;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

/** In-game save screen — name your save; existing games listed for reference. */
final class SaveGamePanel extends JPanel {

    private final Runnable onBack;
    private final Consumer<String> onSaveWithLabel;

    private final JTextField nameField = new JTextField(24);
    private final DefaultListModel<SaveEntrySummary> model = new DefaultListModel<>();
    private final JList<SaveEntrySummary> list = new JList<>(model);

    SaveGamePanel(Runnable onBack, Consumer<String> onSaveWithLabel) {
        super(new BorderLayout());
        this.onBack = onBack;
        this.onSaveWithLabel = onSaveWithLabel;
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(40, 48, 40, 48));

        var north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        var title = new JLabel("Save game", SwingConstants.LEFT);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        var subtitle = new JLabel("Enter a name. Saving replaces an existing entry with the same name.", SwingConstants.LEFT);
        subtitle.setForeground(new Color(0xb8_c0_cc));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        nameField.setMaximumSize(new Dimension(480, 32));
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameField.setFont(nameField.getFont().deriveFont(15f));

        north.add(title);
        north.add(Box.createVerticalStrut(8));
        north.add(subtitle);
        north.add(Box.createVerticalStrut(20));
        north.add(new JLabel("Save name:", SwingConstants.LEFT) {{
            setForeground(new Color(0xd0_d4_d8));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }});
        north.add(Box.createVerticalStrut(6));
        north.add(nameField);

        var midLabel = new JLabel("Existing saves", SwingConstants.LEFT);
        midLabel.setForeground(new Color(0xd0_d4_d8));
        midLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 8, 0));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(new Color(0x12_18_24));
        list.setForeground(Color.WHITE);
        list.setSelectionBackground(new Color(0x2a_3f_6b));
        list.setSelectionForeground(Color.WHITE);
        list.setFixedCellHeight(56);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel lab = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof SaveEntrySummary s) {
                    lab.setText("<html><div style='width:480px'><b>" + escape(s.label()) + "</b><br>"
                            + "<span style='color:#8a9098'>" + escape(s.whenLine()) + "</span></div></html>");
                }
                lab.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                lab.setOpaque(true);
                lab.setBackground(sel ? new Color(0x2a_3f_6b) : new Color(0x12_18_24));
                return lab;
            }
        });
        list.addListSelectionListener(e -> {
            var sel = list.getSelectedValue();
            if (sel != null) {
                nameField.setText(sel.label());
            }
        });

        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(200, 220));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0x28_30_3c), 1));
        scroll.getViewport().setBackground(new Color(0x12_18_24));

        var center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(midLabel);
        center.add(scroll);

        var south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.X_AXIS));
        var back = new JButton("Cancel");
        back.addActionListener(e -> onBack.run());
        var save = new JButton("Save");
        save.setFont(save.getFont().deriveFont(Font.BOLD));
        save.addActionListener(e -> {
            String raw = nameField.getText().trim();
            if (raw.isEmpty()) {
                raw = "Save";
            }
            onSaveWithLabel.accept(raw);
        });
        south.add(back);
        south.add(Box.createHorizontalGlue());
        south.add(save);

        add(north, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    void refreshList() {
        model.clear();
        try {
            List<SaveEntrySummary> rows = GamePersistence.listSaveSummaries();
            for (var r : rows) {
                model.addElement(r);
            }
        } catch (Exception ignored) {
        }
    }
}

package com.tackstrat.ui;

import com.tackstrat.persistence.GamePersistence;
import com.tackstrat.persistence.GameSaveFiles;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Civ-style load list — no OS file browser. */
final class LoadGamePanel extends JPanel {

    private final Runnable onBack;
    private final Consumer<Path> onLoad;

    private final DefaultListModel<SaveEntrySummary> model = new DefaultListModel<>();
    private final JList<SaveEntrySummary> list = new JList<>(model);
    private final JButton loadBtn = new JButton("Load");
    private final JLabel emptyHint = new JLabel(" ", SwingConstants.CENTER);

    LoadGamePanel(Runnable onBack, Consumer<Path> onLoad) {
        super(new BorderLayout());
        this.onBack = onBack;
        this.onLoad = onLoad;
        setBackground(UiTheme.BG_DEEP);
        setBorder(BorderFactory.createEmptyBorder(40, 48, 40, 48));

        var north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        var title = new JLabel("Load game", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        var subtitle = new JLabel("Choose a game to continue.", SwingConstants.CENTER);
        subtitle.setForeground(new Color(0xb8_c0_cc));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        north.add(title);
        north.add(Box.createVerticalStrut(8));
        north.add(subtitle);
        north.add(Box.createVerticalStrut(8));
        emptyHint.setForeground(new Color(0x88_90_9a));
        emptyHint.setFont(emptyHint.getFont().deriveFont(13f));
        emptyHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        north.add(emptyHint);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(new Color(0x12_18_24));
        list.setForeground(Color.WHITE);
        list.setSelectionBackground(new Color(0x2a_3f_6b));
        list.setSelectionForeground(Color.WHITE);
        list.setFixedCellHeight(72);
        list.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel lab = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof SaveEntrySummary s) {
                    lab.setText("<html><div style='width:520px'>"
                            + "<b>" + escape(s.label()) + "</b><br>"
                            + "<span style='color:#aab0b8'>" + escape(s.statusLine()) + "</span><br>"
                            + "<span style='color:#7a828c'>" + escape(s.whenLine()) + "</span>"
                            + "</div></html>");
                }
                lab.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                lab.setOpaque(true);
                lab.setBackground(sel ? new Color(0x2a_3f_6b) : new Color(0x12_18_24));
                return lab;
            }
        });
        list.addListSelectionListener(e -> loadBtn.setEnabled(list.getSelectedIndex() >= 0));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    doLoad(list.getSelectedValue().path());
                }
            }
        });

        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0x28_30_3c), 1));
        scroll.getViewport().setBackground(new Color(0x12_18_24));

        var south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.X_AXIS));
        var back = new JButton("Back");
        back.addActionListener(e -> onBack.run());
        loadBtn.setFont(loadBtn.getFont().deriveFont(Font.BOLD));
        loadBtn.setEnabled(false);
        loadBtn.addActionListener(e -> {
            var sel = list.getSelectedValue();
            if (sel != null) {
                doLoad(sel.path());
            }
        });
        south.add(back);
        south.add(Box.createHorizontalGlue());
        south.add(loadBtn);

        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void doLoad(Path path) {
        onLoad.accept(path);
    }

    void refreshList() {
        model.clear();
        try {
            Path autosavePath = GameSaveFiles.autosavePath();
            SaveEntrySummary autosaveRow = GamePersistence.autosaveListEntry();
            List<SaveEntrySummary> rows = GamePersistence.listSaveSummaries();
            boolean any = autosaveRow != null || !rows.isEmpty();
            if (!any) {
                emptyHint.setText("No saved games yet. Start a match and use Save game from the sidebar.");
            } else {
                emptyHint.setText(" ");
                if (autosaveRow != null) {
                    model.addElement(autosaveRow);
                }
                for (var r : rows) {
                    if (r.path().equals(autosavePath)) {
                        continue;
                    }
                    model.addElement(r);
                }
            }
        } catch (Exception ex) {
            emptyHint.setText("Could not read saves: " + ex.getMessage());
        }
        loadBtn.setEnabled(false);
    }
}

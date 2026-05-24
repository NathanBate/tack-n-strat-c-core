package com.tackstrat;

import com.tackstrat.ui.MainWindow;

import javax.swing.SwingUtilities;

/**
 * Entry point for Tack & Strat.
 */
public final class TackStratApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var w = new MainWindow();
            w.setVisible(true);
            w.applyWindowLaunchModeFromSettings();
        });
    }
}

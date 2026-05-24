package com.tackstrat.ui;

import com.tackstrat.model.Terrain;

import java.awt.Color;

final class UiTheme {

    static final Color BG_DEEP = new Color(0x0c_12_1d);
    static final Color SIDEBAR_BG = new Color(0xf6_f7_f9);
    static final Color SIDEBAR_LINE = new Color(0xd2_d4_d8);
    static final Color INK = new Color(0x10_18_22);

    static final Color[] PLAYER = {
            new Color(0x3a_7c_ff),
            new Color(0xe2_3e_3e),
            new Color(0x35_b1_4f),
            new Color(0xf5_a8_25),
    };

    static final Color FOG_UNSEEN = new Color(0x06_0a_12);

    private UiTheme() {}

    static Color terrainFill(Terrain t) {
        return switch (t) {
            case WATER -> new Color(0x1f_4d_8b);
            case GRASS -> new Color(0x6e_c2_70);
            case PLAINS -> new Color(0xd5_dd_84);
            case DESERT -> new Color(0xe6_c8_84);
            case HILL -> new Color(0xb5_8e_5d);
            case FOREST -> new Color(0x39_84_5b);
            case MOUNTAIN -> new Color(0x6f_6c_70);
        };
    }

    static Color terrainShade(Terrain t) {
        // Slightly darker for shading/decoration
        var b = terrainFill(t);
        return new Color(
                Math.max(0, b.getRed() - 28),
                Math.max(0, b.getGreen() - 28),
                Math.max(0, b.getBlue() - 28));
    }

    static Color terrainHighlight(Terrain t) {
        var b = terrainFill(t);
        return new Color(
                Math.min(255, b.getRed() + 22),
                Math.min(255, b.getGreen() + 22),
                Math.min(255, b.getBlue() + 22));
    }
}

package com.pkmoba.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Hard-edged handheld-era Pokémon menu chrome. Coordinates stay on whole GUI pixels. */
public final class PixelUi {
    public static final int INK = 0xFF38382F;
    public static final int PAPER = 0xFFFFF8E2;
    public static final int CREAM = 0xFFECE4BF;
    public static final int SHADOW = 0xFFB7AE86;
    public static final int TEXT = 0xFF38382F;
    public static final int MUTED = 0xFF787859;
    public static final int RED = 0xFFC85043;
    public static final int GREEN = 0xFF456B3F;
    public static final int GRASS = 0xFFB7C78B;
    public static final int BLUE = 0xFF496C96;

    private PixelUi() {}

    /** Two-pixel stepped corners, inset keyline and flat cartridge-like drop shadow. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x + 4, y + 4, x + w + 4, y + h + 4, 0x70000000);
        frame(g, x, y, w, h, fill, INK);
        g.fill(x + 4, y + 4, x + w - 4, y + 5, PAPER);
        g.fill(x + 4, y + h - 6, x + w - 4, y + h - 4, SHADOW);
    }

    public static void frame(GuiGraphics g, int x, int y, int w, int h, int fill, int outline) {
        g.fill(x + 2, y, x + w - 2, y + h, outline);
        g.fill(x, y + 2, x + w, y + h - 2, outline);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, fill);
    }

    public static void button(GuiGraphics g, Font font, int x, int y, int w, int h,
                              String text, boolean hovered, boolean enabled) {
        frame(g, x, y, w, h, enabled && hovered ? GRASS : enabled ? PAPER : CREAM, INK);
        g.fill(x + 3, y + h - 4, x + w - 3, y + h - 2, SHADOW);
        if (enabled && hovered) arrow(g, x + 5, y + (h - 7) / 2, GREEN);
        String label = fit(font, text, w - (hovered && enabled ? 22 : 10));
        g.drawString(font, label, x + (w - font.width(label)) / 2 + (hovered && enabled ? 3 : 0),
                y + (h - 8) / 2, enabled ? TEXT : MUTED, false);
    }

    public static void centered(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    public static void centered(GuiGraphics g, Font font, net.minecraft.network.chat.Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    public static String fit(Font font, String text, int width) {
        return font.width(text) <= width ? text
                : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    public static void arrow(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 2, y + 7, color);
        g.fill(x + 2, y + 1, x + 4, y + 6, color);
        g.fill(x + 4, y + 2, x + 6, y + 5, color);
    }

    /** Twelve-pixel red/white ball, rasterized explicitly rather than a smooth vector circle. */
    public static void ball(GuiGraphics g, int x, int y, int scale) {
        String[] pixels = {
                "....KKKK....", "..KKRRRRKK..", ".KRRRRRRRRK.", ".KRRRRRRRRK.",
                "KRRRRKKRRRRK", "KKKKKWWKKKKK", "KWWWKWWKWWWK", "KWWWWKKWWWWK",
                ".KWWWWWWWWK.", ".KWWWWWWWWK.", "..KKWWWWKK..", "....KKKK...."};
        for (int row = 0; row < pixels.length; row++) {
            for (int col = 0; col < pixels[row].length(); col++) {
                char p = pixels[row].charAt(col);
                if (p == '.') continue;
                int color = p == 'K' ? INK : p == 'R' ? RED : PAPER;
                g.fill(x + col * scale, y + row * scale, x + (col + 1) * scale, y + (row + 1) * scale, color);
            }
        }
    }
}

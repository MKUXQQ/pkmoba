package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 对局结果横幅：一方获胜时在屏幕中央显示 4 秒，然后淡出。
 */
final class MatchResultOverlay {
    private static final int SHOW_TICKS = 80;
    private static final int FADE_TICKS = 20;

    private static String title = "";
    private static String detail = "";
    private static boolean win;
    private static int remaining;

    private MatchResultOverlay() {
    }

    static void init() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> render(graphics));
    }

    static void show(String winnerName, boolean youWin, String reason) {
        win = youWin;
        if (reason != null && !reason.isBlank()) {
            title = youWin ? "你获胜了！" : (winnerName == null || winnerName.isBlank()
                    ? "对局结束" : winnerName + " 获胜");
            detail = reason;
        } else {
            title = youWin ? "你获胜了！" : (winnerName == null || winnerName.isBlank()
                    ? "对局结束" : winnerName + " 获胜");
            detail = "";
        }
        remaining = SHOW_TICKS;
    }

    private static void render(GuiGraphics graphics) {
        if (remaining <= 0) return;
        remaining--;
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int alpha = remaining <= FADE_TICKS ? Math.max(0, remaining * 255 / FADE_TICKS) : 255;

        int w = 200;
        int h = detail.isEmpty() ? 40 : 54;
        int x = (sw - w) / 2;
        int y = (sh - h) / 2 - 30;

        int border = (alpha << 24) | 0x4A3A28;
        int panel = (alpha << 24) | 0xF2E2C2;
        graphics.fill(x, y, x + w, y + h, border);
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, panel);

        int color = (alpha << 24) | (win ? 0x2E7D32 : 0x8B2E1F);
        PixelUi.centered(graphics, mc.font, title, sw / 2, y + 10, color);
        if (!detail.isEmpty()) {
            PixelUi.centered(graphics, mc.font, detail, sw / 2, y + 28,
                    (alpha << 24) | 0x493522);
        }
    }
}

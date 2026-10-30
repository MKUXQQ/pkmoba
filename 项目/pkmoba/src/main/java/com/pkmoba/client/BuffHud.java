package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 0.87: a small timed-buff readout above the skill bar.
 *
 * <p>Swords Dance is a real +36.6% damage buff on every Lucario skill, but nothing on screen said
 * so: the floating swords are the flavour, this pill is the receipt. The server pushes
 * {@code pkmoba:buff/<label>/<ticks>} whenever a self buff starts.
 */
public final class BuffHud {
    private BuffHud() {
    }

    private static final long NONE = Long.MIN_VALUE;
    private static long swordsUntil = NONE;
    private static long bladesUntil = NONE;
    private static int swordsTicks = 0;
    /** 0.125 诡计: a second, independent pill so the two buffs can run at the same time. */
    private static long plotUntil = NONE;
    private static int plotTicks = 0;

    public static void init() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> render(graphics));
    }

    /** @return true when the payload belonged to this HUD. */
    public static boolean accept(net.minecraft.network.protocol.common.custom.CustomPacketPayload raw) {
        if (!(raw instanceof com.pkmoba.network.EffectPayload payload)) return false;
        String id = payload.effectId();
        if (id == null || !id.startsWith("pkmoba:buff/")) return false;
        String[] parts = id.substring("pkmoba:buff/".length()).split("/");
        if (parts.length < 2) return true;
        int ticks;
        try {
            ticks = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (parts[0].equals("swords")) {
            swordsUntil = now + ticks * 50L;
            swordsTicks = ticks;
            SwordAura.activate((int) payload.x(), ticks);
        } else if (parts[0].equals("blades")) {
            bladesUntil = now + ticks * 50L;
            BladeAura.activate((int) payload.x(), ticks);
        } else if (parts[0].equals("plot")) {
            plotUntil = now + ticks * 50L;
            plotTicks = ticks;
            NastyAura.activate((int) payload.x(), ticks);
        }
        return true;
    }

    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        int panelW = 4 * com.pkmoba.client.SkillPresentation.CELL_W + 3 * com.pkmoba.client.SkillPresentation.CELL_GAP + 12;
        float scale = Math.min(0.86F, Math.min(1.0F, (mc.getWindow().getGuiScaledWidth() - 12.0F) / panelW));
        int panelH = Math.round(98 * scale);
        int panelTop = Math.max(6, mc.getWindow().getGuiScaledHeight() - panelH - 8);
        int x = Math.max(4, mc.getWindow().getGuiScaledWidth() / 2 - 52);
        int y = Math.max(4, panelTop - 18);
        if (swordsUntil != NONE && now < swordsUntil) {
            pill(graphics, x, y, "剑舞  +36.6%", swordsUntil, now, 0xFF6FA8FF, 0xFFBFE0FF);
            y -= 18;
        }
        if (bladesUntil != NONE && now < bladesUntil) {
            pill(graphics, x, y, "剑舞  +25% 普攻 +20% 技能", bladesUntil, now, 0xFF4C7DFF, 0xFFBCD6FF);
            y -= 18;
        }
        if (plotUntil != NONE && now < plotUntil) {
            pill(graphics, x, y, "诡计  +30%", plotUntil, now, 0xFF8A4BD8, 0xFFD6B6FF);
        }
    }

    private static void pill(GuiGraphics graphics, int x, int y, String label, long until, long now,
                             int accent, int text) {
        Minecraft mc = Minecraft.getInstance();
        int h = 14;
        int w = Math.max(104, mc.font.width(label) + 12);
        graphics.fill(x, y, x + w, y + h, 0xD9182130);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, accent);
        graphics.drawString(mc.font, label, x + 6, y + 4, text, false);
        String time = String.format("%.1fs", (until - now) / 1000.0);
        graphics.drawString(mc.font, time, x + w - 6 - mc.font.width(time), y + 4, 0xFFFFE9A8, false);
    }
}

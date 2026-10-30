package com.pkmoba.client;

import com.pkmoba.pokemon.SpeciesRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Compact party strip rendered immediately above the vanilla experience bar. */
public final class PartyHud {
    private static boolean initialized;
    private PartyHud() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> render(graphics));
    }

    private static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || ClientState.getTeam().isEmpty()) return;
        var team = ClientState.getTeam();
        int count = Math.min(6, team.size());
        int gap = 2;
        int slot = 38;
        int width = slot;
        int x = 7;
        int height = count * 30 + Math.max(0, count - 1) * gap;
        int y = Math.max(10, (mc.getWindow().getGuiScaledHeight() - height) / 2);
        PixelUi.panel(g, x - 3, y - 3, width + 6, height + 6, PixelUi.CREAM);
        for (int i = 0; i < count; i++) {
            String species = team.get(i);
            int sx = x;
            int sy = y + i * (30 + gap);
            boolean active = species.equals(ClientState.getTeamActive());
            boolean fainted = ClientState.isTeamFainted(species);
            int fill = active ? PixelUi.GRASS : PixelUi.PAPER;
            PixelUi.frame(g, sx, sy, slot, 28, fill, active ? PixelUi.GREEN : PixelUi.SHADOW);
            ResourceLocation portrait = portrait(species);
            int px = sx + (slot - 20) / 2;
            if (portrait != null) {
                g.blit(portrait, px, sy + 1, 16, 16, 0, 0, 64, 64, 64, 64);
            } else {
                PixelUi.ball(g, sx + (slot - 14) / 2, sy + 5, 2);
            }
            if (fainted) g.fill(px, sy + 1, px + 16, sy + 17, 0x99000000);
            String label = PixelUi.fit(mc.font, SpeciesRegistry.displayName(SpeciesRegistry.parse(species)), slot - 4);
            g.drawString(mc.font, label, sx + (slot - mc.font.width(label)) / 2, sy + 18,
                    fainted ? PixelUi.MUTED : active ? PixelUi.GREEN : PixelUi.TEXT, false);
            g.drawString(mc.font, fainted ? "×" : Integer.toString(i + 1), sx + 2, sy + 2,
                    fainted ? PixelUi.RED : active ? PixelUi.GREEN : PixelUi.MUTED, false);
        }
        if (ClientState.getTeamPendingTicks() > 0) {
            int seconds = (ClientState.getTeamPendingTicks() + 19) / 20;
            g.drawString(mc.font, "" + seconds + "s", x + width - 18, y - 10, PixelUi.RED, false);
        }
    }

    private static ResourceLocation portrait(String raw) {
        var entry = SpeciesRegistry.byId(SpeciesRegistry.parse(raw));
        return entry == null ? null : ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }
}

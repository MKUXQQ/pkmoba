package com.pkmoba.client;

import com.pkmoba.network.StarterChoosePayload;
import com.pkmoba.pokemon.SpeciesRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** One sharp, foreground party layer; C only selects and deploys. */
public final class PartyScreen extends Screen {
    private int selected = -1;
    private String observedActive = "";
    private boolean observedSummoned, requestSent;
    private int requestTicks;

    private PartyScreen() { super(Component.literal("宝可梦队伍")); }
    public static void open() { Minecraft.getInstance().setScreen(new PartyScreen()); }

    @Override protected void init() {
        var team = ClientState.getTeam();
        selected = team.indexOf(ClientState.getTeamActive());
        if (selected < 0 && !team.isEmpty()) selected = 0;
        observedActive = ClientState.getTeamActive();
        observedSummoned = ClientState.isTeamSummoned();
        requestSent = false;
    }

    private int panelWidth() { return Math.min(286, Math.max(120, width - 16)); }
    private int rows() { return Math.max(1, (Math.min(6, ClientState.getTeam().size()) + 1) / 2); }
    private int cardHeight() { return Math.min(38, Math.max(28, (height - 94) / rows())); }
    private int panelHeight() { return 84 + rows() * cardHeight(); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return Math.max(2, (height - panelHeight()) / 2); }
    private int buttonTop() { return top() + 40 + rows() * cardHeight(); }

    @Override public void tick() {
        super.tick();
        if (requestSent) {
            if (!java.util.Objects.equals(observedActive, ClientState.getTeamActive())
                    || observedSummoned != ClientState.isTeamSummoned()) onClose();
            else if (++requestTicks >= 100) { requestSent = false; requestTicks = 0; }
        }
    }

    // Screen's normal background invokes a full-frame blur pass. Keep all party artwork crisp.
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB015241E);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int x = left(), y = top(), w = panelWidth(), ch = cardHeight();
        PixelUi.panel(g, x, y, w, panelHeight(), PixelUi.CREAM);
        g.drawString(font, "宝可梦队伍", x + 10, y + 9, PixelUi.TEXT, false);
        g.drawString(font, requestSent ? "等待服务端…" : "1–6 选择 · Enter 出战", x + 10, y + 24,
                requestSent ? PixelUi.RED : PixelUi.MUTED, false);
        var team = ClientState.getTeam();
        int cw = (w - 24) / 2;
        for (int i = 0; i < team.size() && i < 6; i++) {
            int sx = x + 8 + i % 2 * (cw + 8), sy = y + 38 + i / 2 * ch;
            String species = team.get(i);
            boolean active = species.equals(ClientState.getTeamActive());
            boolean fainted = ClientState.isTeamFainted(species);
            boolean hover = inside(mx, my, sx, sy, cw, ch - 4);
            PixelUi.frame(g, sx, sy, cw, ch - 4,
                    selected == i || hover ? PixelUi.GRASS : PixelUi.PAPER,
                    selected == i ? PixelUi.GREEN : PixelUi.SHADOW);
            int size = Math.min(32, ch - 10);
            ResourceLocation texture = portrait(species);
            if (texture != null) g.blit(texture, sx + 4, sy + 3, size, size, 0, 0, 64, 64, 64, 64);
            int tx = sx + size + 8;
            g.drawString(font, PixelUi.fit(font, (i + 1) + " " + SpeciesRegistry.displayName(
                    SpeciesRegistry.parse(species)), cw - size - 12), tx, sy + 5,
                    fainted ? PixelUi.MUTED : PixelUi.TEXT, false);
            if (ch >= 36) g.drawString(font, fainted ? "倒下" : active ?
                    (ClientState.isTeamSummoned() ? "出战中" : "已选择") : "待命",
                    tx, sy + 20, fainted ? PixelUi.RED : PixelUi.MUTED, false);
        }
        if (team.isEmpty()) g.drawString(font, "队伍为空，请先获取宝可梦", x + 12, y + 44, PixelUi.MUTED, false);
        int by = buttonTop(), bw = (w - 24) / 2;
        PixelUi.button(g, font, x + 8, by, bw, 22, "确认出战",
                inside(mx, my, x + 8, by, bw, 22), canSwitch());
        PixelUi.button(g, font, x + 16 + bw, by, bw, 22, "关闭",
                inside(mx, my, x + 16 + bw, by, bw, 22), true);
        g.drawString(font, "关闭后按 X：召唤 / 收回", x + 10, by + 26, PixelUi.MUTED, false);
        g.flush();
        g.pose().popPose();
    }

    private boolean canSwitch() {
        var team = ClientState.getTeam();
        return !requestSent && selected >= 0 && selected < team.size()
                && !ClientState.isTeamFainted(team.get(selected))
                && (!team.get(selected).equals(ClientState.getTeamActive()) || !ClientState.isTeamSummoned());
    }
    private void confirm() {
        if (!canSwitch()) { if (!requestSent) onClose(); return; }
        if (!ClientPlayNetworking.canSend(StarterChoosePayload.TYPE)) return;
        ClientPlayNetworking.send(new StarterChoosePayload("SWITCH", ClientState.getTeam().get(selected)));
        onClose();
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256 || key == 67) { onClose(); return true; }
        if (key >= 49 && key <= 54) {
            int index = key - 49;
            if (index < ClientState.getTeam().size()) selected = index;
            return true;
        }
        if (key == 257 || key == 335) { confirm(); return true; }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int x = left(), y = top(), w = panelWidth(), ch = cardHeight(), cw = (w - 24) / 2;
        for (int i = 0; i < Math.min(6, ClientState.getTeam().size()); i++) {
            if (inside(mx, my, x + 8 + i % 2 * (cw + 8), y + 38 + i / 2 * ch, cw, ch - 4)) {
                selected = i; return true;
            }
        }
        int by = buttonTop(), bw = (w - 24) / 2;
        if (inside(mx, my, x + 8, by, bw, 22)) { confirm(); return true; }
        if (inside(mx, my, x + 16 + bw, by, bw, 22)) { onClose(); return true; }
        return super.mouseClicked(mx, my, button);
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private static ResourceLocation portrait(String species) {
        var entry = SpeciesRegistry.byId(SpeciesRegistry.parse(species));
        return entry == null ? null : ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }
    @Override public boolean isPauseScreen() { return false; }
}

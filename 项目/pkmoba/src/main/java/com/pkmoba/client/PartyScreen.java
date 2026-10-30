package com.pkmoba.client;

import com.pkmoba.network.StarterChoosePayload;
import com.pkmoba.pokemon.SpeciesRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** Scrollable unlocked collection; highlighting is local until C/Enter confirms. */
public final class PartyScreen extends Screen {
    private String selected = "", observedActive = "", notice = "";
    private boolean observedSummoned, requestSent, dragging;
    private int requestTicks, scrollOffset;

    private PartyScreen() { super(Component.literal("宝可梦队伍")); }
    public static void open() { Minecraft.getInstance().setScreen(new PartyScreen()); }
    @Override protected void init() {
        // Selection is explicit and belongs to this screen instance; resize/init must not reset it.
        observedActive = ClientState.getTeamActive();
        observedSummoned = ClientState.isTeamSummoned();
        clamp();
    }
    private int panelWidth() { return Math.min(360, Math.max(120, width - 16)); }
    private int visibleRows() { return Math.max(1, Math.min(totalRows(), (height - 112) / 40)); }
    private int totalRows() { return Math.max(1, (ClientState.getTeam().size() + 1) / 2); }
    private int panelHeight() { return 102 + visibleRows() * 40; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return Math.max(2, (height - panelHeight()) / 2); }
    private int gridTop() { return top() + 38; }
    private int gridBottom() { return gridTop() + visibleRows() * 40; }
    private int maxScroll() { return Math.max(0, (totalRows() - visibleRows()) * 40); }
    private int cardWidth() { return (panelWidth() - 34) / 2; }
    private void clamp() { scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll())); }

    @Override public void tick() {
        super.tick();
        if (requestSent) {
            if (!java.util.Objects.equals(observedActive, ClientState.getTeamActive())
                    || observedSummoned != ClientState.isTeamSummoned()) onClose();
            else if (++requestTicks >= 100) {
                requestSent = false; requestTicks = 0; notice = "未收到状态变更，请重试或查看聊天提示";
            }
        }
    }
    // Never invoke vanilla full-frame blur over the foreground menu.
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB015241E);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.flush(); g.pose().pushPose(); g.pose().translate(0, 0, 400);
        int x = left(), y = top(), w = panelWidth(), cw = cardWidth();
        PixelUi.panel(g, x, y, w, panelHeight(), PixelUi.CREAM);
        g.drawString(font, "宝可梦队伍 · " + ClientState.getTeam().size() + " 只", x + 10, y + 9, PixelUi.TEXT, false);
        g.drawString(font, PixelUi.fit(font, requestSent ? "等待服务端确认…" : "左键选择 · 右键取消选择 · 滚轮浏览", w - 20), x + 10, y + 24, PixelUi.MUTED, false);
        clamp(); var team = ClientState.getTeam();
        g.enableScissor(x + 7, gridTop(), x + w - 17, gridBottom());
        for (int i = (scrollOffset / 40) * 2; i < team.size(); i++) {
            int sx = x + 8 + i % 2 * (cw + 6), sy = gridTop() + i / 2 * 40 - scrollOffset;
            if (sy >= gridBottom()) break;
            String species = team.get(i);
            boolean active = species.equals(ClientState.getTeamActive()), chosen = species.equals(selected);
            boolean fainted = ClientState.isTeamFainted(species);
            PixelUi.frame(g, sx, sy, cw, 36, chosen ? PixelUi.GRASS : PixelUi.PAPER, chosen ? PixelUi.GREEN : PixelUi.SHADOW);
            ResourceLocation texture = portrait(species);
            if (texture != null) g.blit(texture, sx + 3, sy + 3, 28, 28, 0, 0, 64, 64, 64, 64);
            int tx = sx + 35;
            g.drawString(font, PixelUi.fit(font, SpeciesRegistry.displayName(SpeciesRegistry.parse(species)), cw - 39), tx, sy + 5, fainted ? PixelUi.MUTED : PixelUi.TEXT, false);
            String status = fainted ? "已倒下" : active ? (ClientState.isTeamSummoned() ? "出战中 · 确认收回" : "当前待命") : chosen ? "已选 · 确认出战" : "待命";
            g.drawString(font, PixelUi.fit(font, status, cw - 39), tx, sy + 21, fainted ? PixelUi.RED : PixelUi.MUTED, false);
        }
        g.disableScissor();
        if (team.isEmpty()) g.drawString(font, "收藏为空，请先获取宝可梦", x + 12, gridTop() + 8, PixelUi.MUTED, false);
        int track = gridBottom() - gridTop();
        g.fill(x + w - 13, gridTop(), x + w - 8, gridBottom(), PixelUi.PAPER);
        if (maxScroll() > 0) {
            int thumb = Math.max(12, track * track / (track + maxScroll()));
            int ty = gridTop() + (track - thumb) * scrollOffset / maxScroll();
            g.fill(x + w - 13, ty, x + w - 8, ty + thumb, PixelUi.GREEN);
        }
        int by = gridBottom() + 4, bw = (w - 24) / 2;
        String action = selected.isEmpty() ? "请先点击选择" : selected.equals(ClientState.getTeamActive()) && ClientState.isTeamSummoned() ? "确认收回" : "确认出战";
        PixelUi.button(g, font, x + 8, by, bw, 22, action, inside(mx, my, x + 8, by, bw, 22), canConfirm());
        PixelUi.button(g, font, x + 16 + bw, by, bw, 22, "关闭", inside(mx, my, x + 16 + bw, by, bw, 22), true);
        g.drawString(font, PixelUi.fit(font, notice.isEmpty() ? "C / Enter 确认 · Esc 关闭（不更改收藏）" : notice, w - 20), x + 10, by + 28, PixelUi.MUTED, false);
        g.flush(); g.pose().popPose();
    }
    private boolean canConfirm() {
        return !requestSent && !selected.isEmpty() && ClientState.getTeam().contains(selected) && !ClientState.isTeamFainted(selected);
    }
    private void confirm() {
        if (!canConfirm()) return;
        if (!ClientPlayNetworking.canSend(StarterChoosePayload.TYPE)) { notice = "服务器暂不支持选择操作"; return; }
        observedActive = ClientState.getTeamActive(); observedSummoned = ClientState.isTeamSummoned();
        ClientPlayNetworking.send(new StarterChoosePayload("CONFIRM", selected));
        requestSent = true; requestTicks = 0; notice = "";
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { onClose(); return true; }
        if (key == 67 || key == 257 || key == 335) { confirm(); return true; }
        if (key == 264 || key == 265) { scrollOffset += key == 264 ? 40 : -40; clamp(); return true; }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        int x = left(), w = panelWidth(), cw = cardWidth(), by = gridBottom() + 4, bw = (w - 24) / 2;
        if (button == 0 && inside(mx, my, x + 8, by, bw, 22)) { confirm(); return true; }
        if (button == 0 && inside(mx, my, x + 16 + bw, by, bw, 22)) { onClose(); return true; }
        if (button == 0 && inside(mx, my, x + w - 15, gridTop(), 10, gridBottom() - gridTop())) {
            dragging = true; dragScroll(my); return true;
        }
        if ((button == 0 || button == 1) && !requestSent && inside(mx, my, x + 8, gridTop(), w - 26, gridBottom() - gridTop())) {
            var team = ClientState.getTeam();
            for (int i = (scrollOffset / 40) * 2; i < team.size(); i++) {
                int sy = gridTop() + i / 2 * 40 - scrollOffset;
                if (sy >= gridBottom()) break;
                if (inside(mx, my, x + 8 + i % 2 * (cw + 6), sy, cw, 36)) {
                    if (button == 0) selected = team.get(i);
                    else if (selected.equals(team.get(i))) selected = "";
                    notice = ""; return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
    private void dragScroll(double my) {
        scrollOffset = (int) ((my - gridTop()) / Math.max(1, gridBottom() - gridTop()) * maxScroll()); clamp();
    }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) { dragScroll(my); return true; } return super.mouseDragged(mx, my, button, dx, dy);
    }
    @Override public boolean mouseReleased(double mx, double my, int button) {
        dragging = false; return super.mouseReleased(mx, my, button);
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inside(mx, my, left(), gridTop(), panelWidth(), gridBottom() - gridTop())) {
            scrollOffset -= (int) (sy * 30); clamp(); return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private static ResourceLocation portrait(String species) {
        var entry = SpeciesRegistry.byId(SpeciesRegistry.parse(species));
        return entry == null ? null : ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }
    @Override public boolean isPauseScreen() { return false; }
}

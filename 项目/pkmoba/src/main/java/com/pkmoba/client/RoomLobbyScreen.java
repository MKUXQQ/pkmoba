package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Native Minecraft room browser. All joins wait for the authoritative server snapshot. */
public final class RoomLobbyScreen extends Screen {
    private static final int PANEL = PixelUi.CREAM;
    private static final int BORDER = PixelUi.INK;
    private static final int TEXT = PixelUi.TEXT;
    private static final int MUTED = PixelUi.MUTED;
    private static final int ACCENT = PixelUi.GREEN;
    private static final int GAP = 10;
    private List<RoomListPayload.RoomView> rooms = List.of();
    private int myRoomId;
    private int savedArenas;
    private boolean loaded;
    private int scroll;
    private int pendingRoom;
    private int waitTicks;

    public RoomLobbyScreen() {
        super(Component.literal("对战大厅"));
        RoomListPayload snapshot = ClientRooms.latest();
        if (snapshot != null) apply(snapshot);
    }

    private void apply(RoomListPayload payload) {
        rooms = payload.rooms();
        myRoomId = payload.myRoomId();
        savedArenas = payload.arenas().size();
        loaded = true;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    void update(RoomListPayload payload) {
        apply(payload);
        if ((pendingRoom != 0 && myRoomId == pendingRoom || ClientRooms.consumePendingJoin(myRoomId)) && minecraft != null) {
            for (RoomListPayload.RoomView room : rooms) {
                if (room.id() == myRoomId) {
                    minecraft.setScreen(new RoomDetailScreen(room));
                    return;
                }
            }
        }
        pendingRoom = 0;
    }

    /** Do not run the vanilla full-frame blur over custom menu artwork. */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}
    @Override
    public void tick() {
        waitTicks++;
        if (pendingRoom != 0 && waitTicks > 100) pendingRoom = 0;
    }

    private int panelWidth() { return Math.max(220, Math.min(680, width - 24)); }
    private int panelHeight() { return Math.max(180, Math.min(430, height - 20)); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int columns() { return panelWidth() >= 540 ? 2 : 1; }
    private int cardHeight() { return 112; }
    private int visibleRows() { return Math.max(1, (panelHeight() - 104 + GAP) / (cardHeight() + GAP)); }
    private int capacity() { return columns() * visibleRows(); }
    private int maxScroll() { return Math.max(0, (rooms.size() + columns() - 1) / columns() - visibleRows()); }
    private int cardWidth() { return (panelWidth() - 24 - GAP * (columns() - 1)) / columns(); }
    private int listY() { return top() + 62; }
    private int footerY() { return top() + panelHeight() - 30; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xB0252A20);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        PixelUi.panel(g, x, y, w, h, PANEL);
        PixelUi.frame(g, x + 8, y + 8, w - 16, 42, PixelUi.PAPER, BORDER);
        PixelUi.ball(g, x + 14, y + 14, 2);
        g.drawString(font, "对战大厅", x + 46, y + 14, TEXT, false);
        String count = rooms.size() + " 个场地房间";
        if (w >= 350) g.drawString(font, count, x + w - 14 - font.width(count), y + 15, ACCENT, false);
        g.drawString(font, PixelUi.fit(font, "加入房间后，可召唤人机单挑", w - 66), x + 46, y + 31, MUTED, false);
        if (rooms.isEmpty()) {
            int ey = listY() + 10;
            String heading = !loaded ? (waitTicks > 100 ? "未收到房间数据" : "正在同步房间…")
                    : savedArenas > 0 ? "场地已保存，尚未就绪" : "还没有可加入的场地";
            PixelUi.centered(g, font, heading, width / 2, ey, TEXT);
            if (loaded) {
                PixelUi.centered(g, font, "先用画笔围好 4 点边界", width / 2, ey + 18, MUTED);
                PixelUi.centered(g, font, fit("再放红蓝玩家点，房间自动出现", w - 28), width / 2, ey + 32, MUTED);
            } else if (waitTicks > 100) {
                PixelUi.centered(g, font, "检查服务端模组连接，然后点击刷新", width / 2, ey + 20, MUTED);
            }
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        for (int slot = 0; slot < capacity(); slot++) {
            int index = scroll * columns() + slot;
            if (index >= rooms.size()) break;
            int cx = x + 12 + (slot % columns()) * (cardWidth() + GAP);
            int cy = listY() + (slot / columns()) * (cardHeight() + GAP);
            drawCard(g, rooms.get(index), cx, cy, cardWidth(), cardHeight(), mouseX, mouseY);
        }
        if (maxScroll() > 0) {
            String page = "滚轮浏览 " + (scroll + 1) + "/" + (maxScroll() + 1);
            g.drawString(font, page, x + 14, footerY() + 7, MUTED, false);
        }
        button(g, x + w - 152, footerY(), 64, 20, "刷新", hit(mouseX, mouseY, x + w - 152, footerY(), 64, 20));
        button(g, x + w - 78, footerY(), 64, 20, "关闭", hit(mouseX, mouseY, x + w - 78, footerY(), 64, 20));
    }

    private void drawCard(GuiGraphics g, RoomListPayload.RoomView room, int x, int y, int w, int h, int mx, int my) {
        boolean mine = room.id() == myRoomId;
        boolean enabled = mine || (room.canJoin() && myRoomId == 0 && pendingRoom == 0);
        boolean hover = hit(mx, my, x, y, w, h);
        PixelUi.frame(g, x, y, w, h, hover && enabled ? 0xFFF5F2D4 : PixelUi.PAPER, BORDER);
        g.fill(x + 3, y + 3, x + 6, y + h - 3, mine ? ACCENT : PixelUi.SHADOW);
        g.fill(x + 7, y + 23, x + w - 7, y + 24, PixelUi.CREAM);
        if (hover && enabled) PixelUi.arrow(g, x + 10, y + h - 18, ACCENT);
        String status = status(room);
        g.drawString(font, fit("#" + room.id() + "  " + room.displayName(), w - font.width(status) - 34), x + 12, y + 10, TEXT, false);
        g.drawString(font, status, x + w - 12 - font.width(status), y + 10,
                room.state() == 3 ? PixelUi.RED : ACCENT, false);
        int sideW = (w - 30) / 2;
        drawSeat(g, room.red(), room.redId(), room.redTeam(), x + 12, y + 29, sideW, PixelUi.RED, "红方");
        drawSeat(g, room.blue(), room.blueId(), room.blueTeam(), x + 18 + sideW, y + 29, sideW, PixelUi.BLUE, "蓝方");
        String action = pendingRoom == room.id() ? "正在加入…" : mine ? "返回我的房间"
                : myRoomId != 0 ? "请先退出当前房间" : room.canJoin() ? (room.passwordProtected() ? "密码加入" : "公开加入") : "暂不可加入";
        g.drawString(font, fit(action, w - 48), x + (hover && enabled ? 22 : 12), y + h - 18, enabled ? ACCENT : MUTED, false);
        if (h >= 90) {
            int members = (room.redId().isEmpty() ? 0 : 1) + (room.blueId().isEmpty() ? 0 : 1);
            String occupancy = members + " / 2";
            g.drawString(font, occupancy, x + w - 12 - font.width(occupancy), y + h - 18, MUTED, false);
        }
    }

    private String status(RoomListPayload.RoomView room) {
        return switch (room.state()) {
            case 2 -> "倒计时 " + room.seconds() + "s";
            case 3 -> "对战中";
            case 4 -> "结算中";
            default -> !room.redId().isEmpty() && !room.blueId().isEmpty() ? "双方就绪" : "等待加入";
        };
    }

    private void drawSeat(GuiGraphics g, String name, String id, List<String> team, int x, int y, int w, int color, String side) {
        if (!name.isEmpty()) drawHead(g, name, id, x, y, 18);
        for (int i = 0; i < Math.min(6, team.size()); i++) {
            var species = net.minecraft.resources.ResourceLocation.tryParse(team.get(i));
            var entry = com.pkmoba.pokemon.SpeciesRegistry.byId(species);
            if (entry != null) g.blit(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait()),
                    x + i * 15, y + 26, 14, 14, 0.0F, 0.0F, 64, 64, 64, 64);
        }
        g.drawString(font, side, x + 24, y, color, false);
        g.drawString(font, fit(name.isEmpty() ? "空位" : name, w - 24), x + 24, y + 11, name.isEmpty() ? MUTED : TEXT, false);
    }

    private String fit(String value, int maxWidth) {
        return font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    static void drawHead(GuiGraphics g, String name, int x, int y, int size) {
        drawHead(g, name, "", x, y, size);
    }

    static void drawHead(GuiGraphics g, String name, String id, int x, int y, int size) {
        if (name != null && name.contains("人机")) {
            PixelUi.frame(g, x, y, size, size, 0xFFE5E4D0, PixelUi.INK);
            int unit = Math.max(1, size / 8);
            int eyeY = y + size / 3;
            g.fill(x + unit * 2, eyeY, x + unit * 3, eyeY + unit * 2, PixelUi.GREEN);
            g.fill(x + size - unit * 3, eyeY, x + size - unit * 2, eyeY + unit * 2, PixelUi.GREEN);
            g.fill(x + unit * 2, y + size - unit * 3, x + size - unit * 2, y + size - unit * 2, PixelUi.INK);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null || name == null || name.isEmpty() ? null : mc.getConnection().getPlayerInfo(name);
        if (mc.getConnection() != null && id != null && !id.isEmpty()) {
            try { info = mc.getConnection().getPlayerInfo(java.util.UUID.fromString(id)); }
            catch (IllegalArgumentException ignored) {}
        }
        var skin = info != null ? info.getSkin().texture()
                : mc.player != null && mc.player.getGameProfile().getName().equals(name) ? mc.player.getSkin().texture()
                : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
        g.blit(skin, x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64);
        g.blit(skin, x, y, size, size, 40.0F, 8.0F, 8, 8, 64, 64);
    }

    static void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean hovered) {
        PixelUi.button(g, Minecraft.getInstance().font, x, y, w, h, label, hovered, true);
    }

    static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int x = left(), w = panelWidth();
        if (hit(mx, my, x + w - 78, footerY(), 64, 20)) { onClose(); return true; }
        if (hit(mx, my, x + w - 152, footerY(), 64, 20)) {
            ClientRooms.send("OPEN", 0, "");
            waitTicks = 0;
            return true;
        }
        for (int slot = 0; slot < capacity(); slot++) {
            int index = scroll * columns() + slot;
            if (index >= rooms.size()) break;
            int cx = x + 12 + (slot % columns()) * (cardWidth() + GAP);
            int cy = listY() + (slot / columns()) * (cardHeight() + GAP);
            if (!hit(mx, my, cx, cy, cardWidth(), cardHeight())) continue;
            RoomListPayload.RoomView room = rooms.get(index);
            if (room.id() == myRoomId && minecraft != null) minecraft.setScreen(new RoomDetailScreen(room));
            else if (room.canJoin() && myRoomId == 0 && pendingRoom == 0) {
                pendingRoom = room.id();
                waitTicks = 0;
                if (room.passwordProtected()) {
                    pendingRoom = 0;
                    minecraft.setScreen(new RoomPasswordScreen(this, room));
                } else {
                    ClientRooms.send("JOIN", room.id(), "");
                }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy != 0 && maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (sy > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

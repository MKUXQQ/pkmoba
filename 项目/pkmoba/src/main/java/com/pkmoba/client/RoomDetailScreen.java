package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** 0.49：固定 1v1 房间，左右席位、真实本场阵容、准备倒计时及下角入口。 */
public final class RoomDetailScreen extends Screen {
    private final int roomId;
    private RoomListPayload.RoomView room;
    private boolean leaving;
    private int requestTicks;
    private int ownRosterScroll, opponentRosterScroll;

    public RoomDetailScreen(RoomListPayload.RoomView room) {
        super(Component.literal("房间"));
        this.roomId = room.id();
        this.room = room;
    }
    void update(RoomListPayload payload) {
        if (payload.myRoomId() != roomId) { minecraft.setScreen(new RoomLobbyScreen()); return; }
        RoomListPayload.RoomView updated = ClientRooms.currentRoom(roomId);
        if (updated == null) { minecraft.setScreen(new RoomLobbyScreen()); return; }
        room = updated;
        leaving = false;
        requestTicks = 0;
    }
    /** Do not run the vanilla full-frame blur over custom menu artwork. */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}
    @Override public void tick() { if (requestTicks > 0) requestTicks--; }
    private int panelWidth() { return Math.min(520, width - 16); }
    private int panelHeight() { return Math.min(292, height - 12); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int sideHeight() { return panelHeight() - 144; }
    private int readyY() { return top() + panelHeight() - 84; }
    private boolean editable() { return room.state() <= 1 && !leaving; }
    private boolean hit(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private List<String> ownRoster() { return ClientRooms.isBlue(room) ? room.blueRoster() : room.redRoster(); }
    private String opponentName() { return ClientRooms.isBlue(room) ? room.red() : room.blue(); }
    private boolean canRemoveBot() {
        return room.hasBot() && ClientRooms.isHost(room) && !leaving && requestTicks == 0
                && room.state() <= 2;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB0252A20);
        int x = left(), y = top(), w = panelWidth();
        PixelUi.panel(g, x, y, w, panelHeight(), PixelUi.CREAM);
        PixelUi.frame(g, x + 8, y + 7, w - 16, 20, PixelUi.PAPER, PixelUi.INK);
        PixelUi.ball(g, x + 12, y + 11, 1);
        String status = ClientRooms.status(room);
        g.drawString(font, PixelUi.fit(font, "#" + roomId + " " + room.displayName(), w - font.width(status) - 48), x + 30, y + 13, PixelUi.TEXT, false);
        g.drawString(font, status, x + w - 14 - font.width(status), y + 13, room.state() == 2 ? PixelUi.RED : PixelUi.GREEN, false);
        int colW = (w - 30) / 2;
        boolean ownBlue = ClientRooms.isBlue(room);
        drawSide(g, x + 10, y + 33, colW, sideHeight(), ownBlue, true, mx, my);
        drawSide(g, x + 20 + colW, y + 33, colW, sideHeight(), !ownBlue, false, mx, my);
        String rule = "固定 1v1 · 每人最多 " + room.capacity() + " 只 · " + (room.passwordProtected() ? "密码加入" : "公开加入");
        PixelUi.centered(g, font, PixelUi.fit(font, rule, w - 20), x + w / 2, readyY() - 22, PixelUi.TEXT);
        String hint = room.state() == 2 ? "已锁定阵容 · " + room.seconds() + " 秒后自动开战" : "入场后自行选择并召唤 · 设置变更取消准备";
        PixelUi.centered(g, font, PixelUi.fit(font, hint, w - 20), x + w / 2, readyY() - 10, PixelUi.MUTED);
        boolean canReady = (editable() || room.state() == 2) && !ownRoster().isEmpty() && ownRoster().size() <= room.capacity() && requestTicks == 0;
        boolean showStart = ClientRooms.isHost(room) && room.canStart() && editable();
        int readyW = showStart ? (w - 26) / 2 : w - 20;
        String readyLabel = room.state() == 2 ? "取消准备 / 中止倒计时" : room.state() == 3 ? "对战中" : room.state() == 4 ? "等待结算" : requestTicks > 0 ? "正在同步…" : room.ready() ? "取消准备" : "确认阵容并准备";
        PixelUi.button(g, font, x + 10, readyY(), readyW, 20, readyLabel, hit(mx, my, x + 10, readyY(), readyW, 20), canReady);
        if (showStart) PixelUi.button(g, font, x + 16 + readyW, readyY(), readyW, 20, "开始对战", hit(mx, my, x + 16 + readyW, readyY(), readyW, 20), requestTicks == 0);
        int bw = (w - 26) / 2;
        for (int i = 0; i < 4; i++) {
            int bx = x + 10 + (i % 2) * (bw + 6), by = readyY() + 26 + (i / 2) * 26;
            String text = switch (i) { case 0 -> "选择宝可梦"; case 1 -> "房间设置"; case 2 -> leaving ? "正在退出…" : "退出房间"; default -> "关闭"; };
            boolean enabled = i == 3 || i == 1 || editable();
            PixelUi.button(g, font, bx, by, bw, 20, text, hit(mx, my, bx, by, bw, 20), enabled);
        }
    }
    private void drawSide(GuiGraphics g, int x, int y, int w, int h, boolean blue, boolean mine, int mx, int my) {
        String name = blue ? room.blue() : room.red(), id = blue ? room.blueId() : room.redId();
        List<String> roster = blue ? room.blueRoster() : room.redRoster();
        PixelUi.frame(g, x, y, w, h, PixelUi.PAPER, PixelUi.INK);
        int color = blue ? PixelUi.BLUE : PixelUi.RED;
        g.fill(x + 3, y + 3, x + w - 3, y + 17, blue ? 0xFFDEE6EB : 0xFFF0DDD1);
        int opponentReady = Math.max(0, room.readyCount() - (room.ready() ? 1 : 0));
        boolean ready = mine ? room.ready() : opponentReady > 0;
        boolean botSeat = room.isBot(id);
        String sideLabel = (mine ? "我方" : "对方") + " · " + (blue ? "蓝" : "红") + (ready ? " · 已准备" : "");
        g.drawString(font, PixelUi.fit(font, sideLabel, w - (botSeat ? 62 : 14)), x + 7, y + 6, color, false);
        if (botSeat) PixelUi.button(g, font, x + w - 53, y + 3, 46, 14, "移出人机",
                hit(mx, my, x + w - 53, y + 3, 46, 14), canRemoveBot());
        if (name.isEmpty()) {
            g.drawString(font, PixelUi.fit(font, "等待训练家加入", w - 16), x + 8, y + 27, PixelUi.MUTED, false);
            boolean botEnabled = !mine && editable() && ClientRooms.isHost(room) && requestTicks == 0;
            if (!mine) PixelUi.button(g, font, x + 8, y + h - 27, w - 16, 20, "召唤人机", hit(mx, my, x + 8, y + h - 27, w - 16, 20), botEnabled);
            return;
        }
        RoomLobbyScreen.drawHead(g, name, id, x + 8, y + 22, 18);
        g.drawString(font, PixelUi.fit(font, name, w - 40), x + 32, y + 27, PixelUi.TEXT, false);
        if (roster.isEmpty()) g.drawString(font, "尚未配置本场阵容", x + 8, y + 48, PixelUi.MUTED, false);
        int count = roster.size(), size = Math.min(24, Math.max(12, (w - 16) / 3 - 8));
        int rowStep = size + 15, viewport = Math.max(1, h - 48);
        int maxScroll = Math.max(0, ((count + 2) / 3) * rowStep - viewport);
        int scroll = Math.max(0, Math.min(mine ? ownRosterScroll : opponentRosterScroll, maxScroll));
        if (mine) ownRosterScroll = scroll; else opponentRosterScroll = scroll;
        g.enableScissor(x + 4, y + 44, x + w - 4, y + h - 3);
        for (int i = 0; i < count; i++) {
            int px = x + 8 + (i % 3) * ((w - 16) / 3), py = y + 46 + (i / 3) * rowStep - scroll;
            ResourceLocation species = ResourceLocation.tryParse(roster.get(i));
            var entry = com.pkmoba.pokemon.SpeciesRegistry.byId(species);
            if (roster.get(i).equals("hidden")) {
                g.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball"))), px, py);
            }
            if (entry != null) g.blit(ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait()), px, py, size, size, 0F, 0F, 64, 64, 64, 64);
            if (h >= 115) g.drawString(font, PixelUi.fit(font, roster.get(i).equals("hidden") ? "未公开" : com.pkmoba.pokemon.SpeciesRegistry.displayName(species), (w - 16) / 3 - 2), px, py + size + 2, PixelUi.TEXT, false);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int thumb = Math.max(6, viewport * viewport / (viewport + maxScroll));
            int ty = y + 45 + (viewport - thumb) * scroll / maxScroll;
            g.fill(x + w - 5, ty, x + w - 3, ty + thumb, PixelUi.GREEN);
        }
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int colW = (panelWidth() - 30) / 2;
        if (hit(mx, my, left() + 10, top() + 77, colW, sideHeight() - 46)) {
            ownRosterScroll = Math.max(0, ownRosterScroll - (int) (sy * 24)); return true;
        }
        if (hit(mx, my, left() + 20 + colW, top() + 77, colW, sideHeight() - 46)) {
            opponentRosterScroll = Math.max(0, opponentRosterScroll - (int) (sy * 24)); return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int x = left(), w = panelWidth(), bw = (w - 26) / 2;
        boolean showStart = ClientRooms.isHost(room) && room.canStart() && editable();
        int readyW = showStart ? bw : w - 20;
        if (showStart && hit(mx, my, x + 16 + readyW, readyY(), readyW, 20)) {
            if (requestTicks == 0) { ClientRooms.send("START", roomId, ""); requestTicks = 40; }
            return true;
        }
        if (hit(mx, my, x + 10, readyY(), readyW, 20)) {
            if ((editable() || room.state() == 2) && requestTicks == 0 && !ownRoster().isEmpty() && ownRoster().size() <= room.capacity()) { ClientRooms.send("READY", roomId, ""); requestTicks = 40; }
            return true;
        }
        for (int i = 0; i < 4; i++) {
            int bx = x + 10 + (i % 2) * (bw + 6), by = readyY() + 26 + (i / 2) * 26;
            if (!hit(mx, my, bx, by, bw, 20)) continue;
            if (i == 3) onClose();
            else if (i == 1) minecraft.setScreen(new RoomSettingsScreen(room));
            else if (editable() && i == 0) minecraft.setScreen(new RoomTeamScreen(room));
            else if (editable() && i == 2) { leaving = true; ClientRooms.send("LEAVE", roomId, ""); }
            return true;
        }
        int colW = (w - 30) / 2;
        int opponentX = x + 20 + colW;
        if (canRemoveBot() && hit(mx, my, opponentX + colW - 53, top() + 36, 46, 14)) {
            ClientRooms.send("REMOVE_BOT", roomId, ""); requestTicks = 40; return true;
        }
        int bx = opponentX + 8, by = top() + 33 + sideHeight() - 27;
        if (opponentName().isEmpty() && editable() && ClientRooms.isHost(room) && requestTicks == 0 && hit(mx, my, bx, by, colW - 16, 20)) {
            ClientRooms.send("ADD_BOT", roomId, ""); requestTicks = 60; return true;
        }
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
}

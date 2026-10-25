package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * H 键房间大厅（0.41.8 重写）。
 *
 * <p>用户 m05076：<br>
 * ①「H 键是不是不在同一个图层，我需要换到同一个图层，并且关闭我也需要同画风」——
 * 旧版面板自绘米色底，按钮却是原版灰色 {@code Button}，看着像两层。现在<b>全部自绘</b>：
 * 面板、行、关闭按钮同一块调色板、同一个边框，界面里不再有一个原版控件。<br>
 * ②「H 键的红蓝删除，就显示房间多少，然后玩家的头像，然后点击加入」——
 * 每行只画：房间序号 + 房主头像 + 房主名 + 人数，点整行即加入。<br>
 * ③「然后进到一个新的UI，显示对方宝可梦和我方宝可梦，然后还有退出房间」——
 * 进房后由 {@link RoomDetailScreen} 承接。
 */
public final class RoomLobbyScreen extends Screen {
    private static final int PANEL_W_MAX = 340;
    private static final int PANEL_H = 236;
    private static final int PAD = 10;
    private static final int ROW_H = 26;
    private static final int MAX_ROWS = 6;
    private static final int HEAD = 18;

    private static final int PANEL = 0xFFF3E7CE;
    private static final int BORDER = 0xFF3B2B1E;
    private static final int INNER = 0xFFE7D7B8;
    private static final int TEXT = 0xFF3A2A1C;
    private static final int MUTED = 0xFF8A7256;
    private static final int GOLD = 0xFFD8402F;
    private static final int ROW_BG = 0xFFEADCC0;
    private static final int ROW_HOVER = 0xFFF7EDD8;
    private static final int ROW_MINE = 0xFFF6E0B8;
    private static final int BTN = 0xFFD8402F;
    private static final int BTN_HOVER = 0xFFE85A48;

    private List<RoomListPayload.RoomView> rooms = List.of();
    private int myRoomId;
    private boolean loaded;
    private int scroll;
    private boolean hoverClose;

    public RoomLobbyScreen() {
        super(Component.literal("房间大厅"));
        RoomListPayload snapshot = ClientRooms.latest();
        if (snapshot != null) {
            apply(snapshot);
        }
    }

    private void apply(RoomListPayload payload) {
        this.rooms = payload.rooms();
        this.myRoomId = payload.myRoomId();
        this.loaded = true;
        this.scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    void update(RoomListPayload payload) {
        apply(payload);
    }

    private int panelWidth() {
        return Math.min(PANEL_W_MAX, width - 16);
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
    }

    private int listX() {
        return left() + PAD;
    }

    private int listY() {
        return top() + 46;
    }

    private int listW() {
        return panelWidth() - PAD * 2;
    }

    private int maxScroll() {
        return Math.max(0, rooms.size() - MAX_ROWS);
    }

    private int closeX() {
        return left() + (panelWidth() - 88) / 2;
    }

    private int closeY() {
        return top() + PANEL_H - 30;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不调 renderBackground：1.21 会把世界模糊掉（用户反复反馈"界面一片糊"），用纯暗底。
        g.fill(0, 0, width, height, 0xC0101014);
        int x = left();
        int y = top();
        int w = panelWidth();

        g.fill(x, y, x + w, y + PANEL_H, PANEL);
        border(g, x, y, w, PANEL_H, BORDER);
        g.fill(x + 4, y + 4, x + w - 4, y + 22, INNER);
        g.drawString(font, "房间大厅", x + PAD, y + 9, GOLD, false);
        drawBall(g, x + w - PAD - 8, y + 9, 7);
        String sub = rooms.isEmpty()
                ? (loaded ? "" : "读取中…")
                : "共 " + rooms.size() + " 个 · 点击加入";
        g.drawString(font, sub, x + PAD + 52, y + 9, MUTED, false);

        int lx = listX();
        int ly = listY();
        int lw = listW();
        int listH = ROW_H * MAX_ROWS;
        g.fill(lx, ly, lx + lw, ly + listH, INNER);
        border(g, lx, ly, lw, listH, BORDER);
        for (int row = 0; row < MAX_ROWS; row++) {
            int index = scroll + row;
            if (index >= rooms.size()) {
                break;
            }
            RoomListPayload.RoomView room = rooms.get(index);
            int ry = ly + row * ROW_H;
            boolean hovered = mouseX >= lx + 1 && mouseX < lx + lw - 1 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean mine = room.id() == myRoomId;
            g.fill(lx + 2, ry + 1, lx + lw - 2, ry + ROW_H - 1,
                    mine ? ROW_MINE : (hovered ? ROW_HOVER : ROW_BG));
            if (mine) {
                g.fill(lx + 2, ry + 2, lx + 4, ry + ROW_H - 2, GOLD);
            }
            g.drawString(font, "房间 " + room.id(), lx + 9, ry + 9, GOLD, false);
            int headX = lx + 62;
            int headY = ry + (ROW_H - HEAD) / 2;
            drawHead(g, room.host(), headX, headY, HEAD);
            g.drawString(font, room.host(), headX + HEAD + 6, ry + 5, TEXT, false);
            g.drawString(font, memberText(room), headX + HEAD + 6, ry + 15, MUTED, false);
            String state = switch (room.state()) {
                case 2 -> "倒计时";
                case 3 -> "对战中";
                case 4 -> "已结束";
                default -> room.canJoin() || mine ? "可加入" : "已满";
            };
            g.drawString(font, state, lx + lw - 9 - font.width(state), ry + 9,
                    room.state() == 3 ? GOLD : MUTED, false);
        }
        if (rooms.size() > MAX_ROWS) {
            String hint = (scroll + 1) + "-" + Math.min(rooms.size(), scroll + MAX_ROWS) + " / " + rooms.size();
            g.drawString(font, hint, lx + lw - font.width(hint) - 6, ly - 11, MUTED, false);
        }

        hoverClose = mouseX >= closeX() && mouseX < closeX() + 88
                && mouseY >= closeY() && mouseY < closeY() + 20;
        button(g, closeX(), closeY(), 88, 20, "关闭", hoverClose);
    }

    private String memberText(RoomListPayload.RoomView room) {
        int count = 0;
        if (!room.red().isEmpty()) {
            count++;
        }
        if (!room.blue().isEmpty()) {
            count++;
        }
        // 用户要求：大厅行不再显示点位组名（原来尾部是 "· arena2"），只留人数。
        return count + "/2";
    }

    /** 画玩家头像（取皮肤贴图里 8x8 的脸，再叠第二层）。 */
    static void drawHead(GuiGraphics g, String name, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = null;
        if (mc.getConnection() != null && name != null && !name.isEmpty()) {
            info = mc.getConnection().getPlayerInfo(name);
        }
        if (info == null) {
            g.fill(x, y, x + size, y + size, 0xFF6B5B45);
            return;
        }
        g.blit(info.getSkin().texture(), x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64);
        // 用户要求"玩家的头像第二层显示"：皮肤第二层（帽子/外层，uv 40,8）叠在脸上，
        // 否则戴帽子/有外发的皮肤在这里只剩一张光脸。
        g.blit(info.getSkin().texture(), x, y, size, size, 40.0F, 8.0F, 8, 8, 64, 64);
    }

    static void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean hovered) {
        g.fill(x, y, x + w, y + h, hovered ? BTN_HOVER : BTN);
        border(g, x, y, w, h, BORDER);
        Minecraft mc = Minecraft.getInstance();
        g.drawString(mc.font, label, x + (w - mc.font.width(label)) / 2, y + (h - 8) / 2,
                0xFFFFFFFF, false);
    }

    static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (mouseX >= closeX() && mouseX < closeX() + 88 && mouseY >= closeY() && mouseY < closeY() + 20) {
            onClose();
            return true;
        }
        int lx = listX();
        int ly = listY();
        int lw = listW();
        for (int row = 0; row < MAX_ROWS; row++) {
            int index = scroll + row;
            if (index >= rooms.size()) {
                break;
            }
            int ry = ly + row * ROW_H;
            if (mouseX >= lx + 1 && mouseX < lx + lw - 1 && mouseY >= ry && mouseY < ry + ROW_H) {
                RoomListPayload.RoomView room = rooms.get(index);
                if (room.id() == myRoomId) {
                    if (minecraft != null) {
                        minecraft.setScreen(new RoomDetailScreen(room));
                    }
                } else if (room.canJoin()) {
                    ClientRooms.send("JOIN", room.id(), "");
                    if (minecraft != null) {
                        minecraft.setScreen(new RoomDetailScreen(room));
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rooms.size() > MAX_ROWS) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 顶栏的精灵球徽标（纯 fill 拼出来，不依赖贴图）。 */
    private static void drawBall(GuiGraphics g, int cx, int cy, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            int halfW = (int) Math.sqrt(Math.max(0.0, (double) radius * radius - (double) dy * dy));
            int color;
            if (dy < 0) {
                color = 0xFFE03A2E;
            } else if (dy > 0) {
                color = 0xFFF7F2E4;
            } else {
                color = 0xFF2B2118;
            }
            g.fill(cx - halfW, cy + dy, cx + halfW + 1, cy + dy + 1, color);
        }
        g.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xFF2B2118);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFF7F2E4);
    }
}

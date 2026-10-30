package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** 0.49：分类设置只提交服务端意图，成员可以查看但不能编辑。 */
public final class RoomSettingsScreen extends Screen {
    private RoomListPayload.RoomView room;
    private String roomName;
    private String password = "";
    private int capacity;
    private int tab;
    private int activeField;
    private String notice = "";
    private boolean nameDirty;
    private boolean capacityDirty;

    public RoomSettingsScreen(RoomListPayload.RoomView room) {
        super(Component.literal("房间设置"));
        this.room = room;
        roomName = room.displayName();
        capacity = room.capacity();
    }

    int roomId() { return room.id(); }
    void update(RoomListPayload.RoomView updated) {
        room = updated;
        if (!nameDirty) roomName = room.displayName();
        if (!capacityDirty) capacity = room.capacity();
        if (!canEdit()) { activeField = 0; password = ""; }
    }
    private boolean canEdit() { return ClientRooms.isHost(room) && room.state() <= 1; }
    private int panelWidth() { return Math.min(380, width - 16); }
    private int panelHeight() { return Math.min(Math.max(112, height - 12), 210); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int contentY() { return top() + 52; }
    private int compactShift() { return panelHeight() < 190 ? 32 : 0; }
    private int footerY() { return top() + panelHeight() - 29; }
    private boolean hit(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    /** Do not run the vanilla full-frame blur over custom menu artwork. */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB0252A20);
        int x = left(), y = top(), w = panelWidth(), cy = contentY();
        PixelUi.panel(g, x, y, w, panelHeight(), PixelUi.PAPER);
        PixelUi.ball(g, x + 12, y + 10, 1);
        g.drawString(font, "房间设置 · " + (canEdit() ? "房主" : "只读"), x + 32, y + 13, PixelUi.TEXT, false);
        String[] tabs = {"基础", "对战规则"};
        int tabW = (w - 24) / 2;
        for (int i = 0; i < tabs.length; i++) PixelUi.button(g, font, x + 12 + i * tabW, y + 31,
                tabW - 2, 18, tabs[i], tab == i, true);
        if (tab == 0) {
            boolean compact = compactShift() > 0;
            if (compact) {
                field(g, cy, roomName.isEmpty() ? "房间名称（最多32字符）" : roomName, activeField == 1);
                line(g, "加入方式：" + (room.passwordProtected() ? "密码保护" : "公开加入"), cy + 22);
                field(g, cy + 33, password.isEmpty() ? "新密码（≤32）" : "*".repeat(password.length()), activeField == 2);
                int bw = (w - 30) / 2;
                PixelUi.button(g, font, x + 12, cy + 55, bw, 18, "保存更改", hit(mx, my, x + 12, cy + 55, bw, 18), canEdit() && (nameDirty || !password.isEmpty()));
                PixelUi.button(g, font, x + 18 + bw, cy + 55, bw, 18, "改为公开", hit(mx, my, x + 18 + bw, cy + 55, bw, 18), canEdit() && room.passwordProtected());
            } else {
                line(g, "房间名称（最多32字符）", cy);
                field(g, cy + 13, roomName, activeField == 1);
                line(g, "加入方式：" + (room.passwordProtected() ? "密码保护" : "公开加入"), cy + 40);
                field(g, cy + 54, password.isEmpty() ? "新密码（最多32字符）" : "*".repeat(password.length()), activeField == 2);
                int bw = (w - 30) / 2;
                PixelUi.button(g, font, x + 12, cy + 80, bw, 20, "设置密码", hit(mx, my, x + 12, cy + 80, bw, 20), canEdit() && !password.isEmpty());
                PixelUi.button(g, font, x + 18 + bw, cy + 80, bw, 20, "改为公开", hit(mx, my, x + 18 + bw, cy + 80, bw, 20), canEdit() && room.passwordProtected());
                PixelUi.button(g, font, x + 12, cy + 106, w - 24, 20, "保存房间名称", hit(mx, my, x + 12, cy + 106, w - 24, 20), canEdit() && nameDirty && !roomName.isBlank());
            }
        } else if (tab == 1) {
            boolean compact = compactShift() > 0;
            line(g, "对战人数：固定 1v1（2 位训练家）", cy);
            line(g, "每人本场最多出战", cy + (compact ? 18 : 25));
            int controlY = cy + (compact ? 31 : 42);
            PixelUi.button(g, font, x + 12, controlY, 26, 20, "−", hit(mx, my, x + 12, controlY, 26, 20), canEdit() && capacity > 1);
            PixelUi.centered(g, font, capacity + " 只", x + w / 2, controlY + 6, PixelUi.TEXT);
            PixelUi.button(g, font, x + w - 38, controlY, 26, 20, "+", hit(mx, my, x + w - 38, controlY, 26, 20), canEdit() && capacity < com.pkmoba.pokemon.SpeciesRegistry.ids().size());
            int privacyY = cy + (compact ? 54 : 70);
            PixelUi.button(g, font, x + 12, privacyY, w - 24, 20,
                    "阵容：" + visibilityLabel(room.visibility()) + "（点击切换）",
                    hit(mx, my, x + 12, privacyY, w - 24, 20), canEdit());
            if (!compact) PixelUi.button(g, font, x + 12, cy + 106, w - 24, 20, "保存出战上限", hit(mx, my, x + 12, cy + 106, w - 24, 20), canEdit() && capacityDirty);
            else if (capacityDirty) g.drawString(font, "调整后自动提交", x + 12, cy + 78, PixelUi.GREEN, false);
        }
        PixelUi.button(g, font, x + 12, footerY(), w - 24, 20, "返回房间", hit(mx, my, x + 12, footerY(), w - 24, 20), true);
    }
    private void line(GuiGraphics g, String s, int y) { g.drawString(font, PixelUi.fit(font, s, panelWidth() - 24), left() + 12, y, PixelUi.MUTED, false); }
    private void field(GuiGraphics g, int y, String value, boolean focused) {
        PixelUi.frame(g, left() + 12, y, panelWidth() - 24, 20, focused && canEdit() ? PixelUi.GRASS : PixelUi.CREAM, PixelUi.INK);
        g.drawString(font, PixelUi.fit(font, value + (focused && canEdit() ? "_" : ""), panelWidth() - 36), left() + 18, y + 6, canEdit() ? PixelUi.TEXT : PixelUi.MUTED, false);
    }
    @Override public boolean charTyped(char c, int modifiers) {
        if (!canEdit() || Character.isISOControl(c)) return false;
        if (activeField == 1 && roomName.length() < 32) { roomName += c; nameDirty = true; return true; }
        if (activeField == 2 && password.length() < 32) { password += c; return true; }
        return false;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (canEdit() && key == GLFW.GLFW_KEY_BACKSPACE) {
            if (activeField == 1 && !roomName.isEmpty()) { roomName = roomName.substring(0, roomName.length() - 1); nameDirty = true; }
            if (activeField == 2 && !password.isEmpty()) password = password.substring(0, password.length() - 1);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int x = left(), w = panelWidth(), cy = contentY(), tabW = (w - 24) / 2;
        if (hit(mx, my, x + 12, footerY(), w - 24, 20)) { onClose(); return true; }
        if (hit(mx, my, x + 12, top() + 31, tabW * 2, 18)) { tab = Math.min(1, ((int) mx - x - 12) / tabW); activeField = 0; notice = ""; return true; }
        if (!canEdit()) return true;
        if (tab == 0) {
            boolean compact = compactShift() > 0;
            if (compact) {
                if (hit(mx, my, x + 12, cy, w - 24, 20)) { activeField = 1; return true; }
                if (hit(mx, my, x + 12, cy + 33, w - 24, 20)) { activeField = 2; return true; }
                int bw = (w - 30) / 2;
                if (hit(mx, my, x + 12, cy + 55, bw, 18)) {
                    if (nameDirty && !roomName.isBlank()) { ClientRooms.send("SET_NAME", room.id(), roomName.trim()); nameDirty = false; }
                    if (!password.isEmpty()) { ClientRooms.send("SET_PASSWORD", room.id(), password); password = ""; }
                    notice = "更改已提交，以服务器快照为准";
                } else if (hit(mx, my, x + 18 + bw, cy + 55, bw, 18) && room.passwordProtected()) {
                    ClientRooms.send("SET_PASSWORD", room.id(), ""); notice = "公开请求已提交，以服务器快照为准";
                }
            } else {
                if (hit(mx, my, x + 12, cy + 13, w - 24, 20)) { activeField = 1; return true; }
                if (hit(mx, my, x + 12, cy + 54, w - 24, 20)) { activeField = 2; return true; }
                int bw = (w - 30) / 2;
                if (hit(mx, my, x + 12, cy + 80, bw, 20) && !password.isEmpty()) {
                    ClientRooms.send("SET_PASSWORD", room.id(), password); password = ""; notice = "密码请求已提交，以服务器快照为准";
                } else if (hit(mx, my, x + 18 + bw, cy + 80, bw, 20) && room.passwordProtected()) {
                    ClientRooms.send("SET_PASSWORD", room.id(), ""); notice = "公开请求已提交，以服务器快照为准";
                } else if (hit(mx, my, x + 12, cy + 106, w - 24, 20) && nameDirty && !roomName.isBlank()) {
                    ClientRooms.send("SET_NAME", room.id(), roomName.trim()); nameDirty = false; notice = "改名请求已提交";
                }
            }
        } else if (tab == 1) {
            boolean compact = compactShift() > 0;
            int controlY = cy + (compact ? 31 : 42);
            if (hit(mx, my, x + 12, cy + (compact ? 54 : 70), w - 24, 20)) {
                ClientRooms.send("SET_VISIBILITY", room.id(), Integer.toString((room.visibility() + 1) % 3));
                return true;
            }
            if (hit(mx, my, x + 12, controlY, 26, 20)) { capacity = Math.max(1, capacity - 1); capacityDirty = true; }
            else if (hit(mx, my, x + w - 38, controlY, 26, 20)) { capacity = Math.min(com.pkmoba.pokemon.SpeciesRegistry.ids().size(), capacity + 1); capacityDirty = true; }
            if (compact && capacityDirty) {
                ClientRooms.send("SET_CAPACITY", room.id(), Integer.toString(capacity));
                capacityDirty = false;
                notice = "上限请求已提交";
            } else if (!compact && hit(mx, my, x + 12, cy + 106, w - 24, 20) && capacityDirty) {
                ClientRooms.send("SET_CAPACITY", room.id(), Integer.toString(capacity)); capacityDirty = false; notice = "上限请求已提交";
            }
        }
        return true;
    }
    static String visibilityLabel(int mode) {
        return switch (mode) { case 1 -> "开战公开"; case 2 -> "登场公开"; default -> "始终公开"; };
    }
    @Override public void onClose() { ClientRooms.returnToRoom(room.id()); }
    @Override public boolean isPauseScreen() { return false; }
}

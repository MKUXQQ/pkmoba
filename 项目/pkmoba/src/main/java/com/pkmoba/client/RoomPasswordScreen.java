package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Password prompt for joining a protected room. */
public final class RoomPasswordScreen extends Screen {
    private final Screen parent;
    private final int roomId;
    private final String roomName;
    private String password = "";
    private String error = "";
    private int attemptTicks;

    public RoomPasswordScreen(Screen parent, RoomListPayload.RoomView room) {
        super(Component.literal("加入密码房间"));
        this.parent = parent;
        this.roomId = room.id();
        this.roomName = room.displayName();
    }

    void update(RoomListPayload payload) {
        if (payload.myRoomId() == roomId) { ClientRooms.returnToRoom(roomId); return; }
        RoomListPayload.RoomView current = ClientRooms.currentRoom(roomId);
        if (attemptTicks > 0) { error = "密码错误或加入失败，请重试"; attemptTicks = 0; }
        else if (current == null || !current.canJoin()) error = "房间已满、已开战或不存在";
    }

    /** Do not run the vanilla full-frame blur over custom menu artwork. */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}
    @Override public void tick() { if (attemptTicks > 0) attemptTicks--; }

    private int panelWidth() { return Math.min(280, width - 16); }
    private int panelHeight() { return 126; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int inputX() { return left() + 12; }
    private int inputY() { return top() + 52; }
    private int inputW() { return panelWidth() - 24; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB0252A20);
        PixelUi.panel(g, left(), top(), panelWidth(), panelHeight(), PixelUi.PAPER);
        PixelUi.ball(g, left() + 12, top() + 10, 1);
        g.drawString(font, PixelUi.fit(font, "加入 " + roomName, panelWidth() - 42), left() + 32, top() + 14, PixelUi.TEXT, false);
        g.drawString(font, "房间密码", inputX(), top() + 38, PixelUi.MUTED, false);
        PixelUi.frame(g, inputX(), inputY(), inputW(), 20, PixelUi.CREAM, PixelUi.INK);
        String shown = password.isEmpty() ? "输入密码…" : "•".repeat(password.length());
        g.drawString(font, PixelUi.fit(font, shown, inputW() - 12), inputX() + 6, inputY() + 6,
                password.isEmpty() ? PixelUi.MUTED : PixelUi.TEXT, false);
        if (!error.isEmpty()) g.drawString(font, PixelUi.fit(font, error, panelWidth() - 24), inputX(), inputY() + 24, attemptTicks > 0 ? PixelUi.GREEN : PixelUi.RED, false);
        int buttonY = top() + panelHeight() - 28;
        int half = (panelWidth() - 30) / 2;
        PixelUi.button(g, font, left() + 10, buttonY, half, 20, "加入", mx >= left() + 10 && mx < left() + 10 + half && my >= buttonY && my < buttonY + 20, !password.isEmpty());
        int cancelX = left() + 20 + half;
        PixelUi.button(g, font, cancelX, buttonY, half, 20, "取消", mx >= cancelX && mx < cancelX + half && my >= buttonY && my < buttonY + 20, true);
        super.render(g, mx, my, delta);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (Character.isISOControl(c) || password.length() >= 32) return false;
        password += c;
        error = "";
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!password.isEmpty()) password = password.substring(0, password.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { join(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int y = top() + panelHeight() - 28;
        int half = (panelWidth() - 30) / 2;
        if (mx >= left() + 10 && mx < left() + 10 + half && my >= y && my < y + 20) { join(); return true; }
        int cancelX = left() + 20 + half;
        if (mx >= cancelX && mx < cancelX + half && my >= y && my < y + 20) { onClose(); return true; }
        return true;
    }

    private void join() {
        if (password.isEmpty()) { error = "请输入密码"; return; }
        if (attemptTicks > 0) return;
        ClientRooms.send("JOIN", roomId, password);
        ClientRooms.markPendingJoin(roomId);
        attemptTicks = 100;
        error = "正在验证…";
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
    @Override
    public boolean isPauseScreen() { return false; }
}

package com.pkmoba.client;

import com.pkmoba.network.ChallengeActionPayload;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 被挑战者的全屏挑战弹窗：米黄底 + 深棕描边，含接受/拒绝与 10 秒倒计时。
 *
 * <p>倒计时数字完全由服务端下发（每秒一帧）；这里只负责显示。
 * 服务端一旦判定过期/拒绝/断线，会下发 open=false，立即关闭弹窗。
 */
public final class ChallengePopupScreen extends Screen {
    private static ChallengePopupScreen active;

    private final UUID requestId;
    private final String challengerName;
    private int secondsLeft;

    private ChallengePopupScreen(UUID requestId, String challengerName, int secondsLeft) {
        super(Component.literal("1v1 挑战"));
        this.requestId = requestId;
        this.challengerName = challengerName == null ? "" : challengerName;
        this.secondsLeft = Math.max(0, secondsLeft);
    }

    /** 服务端下发弹窗状态：open=true 打开/刷新，false 关闭。 */
    public static void handle(boolean open, UUID requestId, String challengerName, int secondsLeft) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (!open) {
            if (active != null) {
                minecraft.execute(() -> {
                    if (minecraft.screen == active) minecraft.setScreen(null);
                    active = null;
                });
            }
            return;
        }
        minecraft.execute(() -> {
            if (active != null && active.requestId.equals(requestId)) {
                active.secondsLeft = Math.max(0, secondsLeft);
                return;
            }
            ChallengePopupScreen popup = new ChallengePopupScreen(requestId, challengerName, secondsLeft);
            active = popup;
            minecraft.setScreen(popup);
        });
    }

    public static void reset() {
        active = null;
    }

    private int panelWidth() { return Math.min(240, width - 24); }
    private int panelHeight() { return 124; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0x88000000);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        BattleMenuScreen.drawPanel(graphics, x, y, w, h);

        graphics.drawCenteredString(font, "收到 1v1 挑战", width / 2, y + 12, BattleMenuScreen.TEXT);
        graphics.drawCenteredString(font, "挑战者：" + challengerName, width / 2, y + 30, BattleMenuScreen.TEXT);
        graphics.drawCenteredString(font, "是否接受对方的宝可梦对战？",
                width / 2, y + 46, BattleMenuScreen.TEXT);

        int btnW = (w - 42) / 2;
        int btnY = y + h - 62;
        boolean acceptHover = BattleMenuScreen.inside(mouseX, mouseY, x + 12, btnY, btnW, 22);
        boolean denyHover = BattleMenuScreen.inside(mouseX, mouseY, x + w - 12 - btnW, btnY, btnW, 22);
        BattleMenuScreen.drawButton(graphics, font, "接受挑战", x + 12, btnY, btnW, 22, acceptHover, false, true);
        BattleMenuScreen.drawButton(graphics, font, "拒绝挑战", x + w - 12 - btnW, btnY, btnW, 22, denyHover, false, true);

        String countdown = "剩余时间：" + secondsLeft + " 秒";
        int color = secondsLeft <= 3 ? 0xFFC94F3D : BattleMenuScreen.TEXT;
        graphics.drawCenteredString(font, countdown, width / 2, y + h - 30, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        int btnW = (w - 42) / 2;
        int btnY = y + h - 62;
        if (BattleMenuScreen.inside(mouseX, mouseY, x + 12, btnY, btnW, 22)) {
            respond(true);
            return true;
        }
        if (BattleMenuScreen.inside(mouseX, mouseY, x + w - 12 - btnW, btnY, btnW, 22)) {
            respond(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void respond(boolean accept) {
        if (ClientPlayNetworking.canSend(ChallengeActionPayload.TYPE)) {
            ClientPlayNetworking.send(new ChallengeActionPayload(requestId, accept));
        }
        // 立刻关闭本地弹窗，最终状态仍以服务端结果为准（失败会再收到聊天提示）
        onClose();
    }

    @Override
    public void onClose() {
        if (active == this) active = null;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

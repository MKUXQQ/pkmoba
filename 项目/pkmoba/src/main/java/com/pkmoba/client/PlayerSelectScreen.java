package com.pkmoba.client;

import com.pkmoba.network.MenuActionPayload;
import com.pkmoba.network.MenuOpenPayload.MenuKind;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * 分页在线玩家选择界面。
 *
 * <p>BATTLE / CHALLENGE_ALL：点击某位玩家 = 立即发起 1v1 挑战（对方会收到 10 秒弹窗）。
 * ADMIN：管理员强制开战，依次点两名玩家。
 * 管理员挑战界面会列出自己，但自己的那一行不可点击。
 */
public final class PlayerSelectScreen extends Screen {
    private final MenuKind kind;
    private final List<String> players;
    private final List<String> arenas;
    /** 上一级菜单里选好的场地名（空串 = 不使用场地）。 */
    private final String arena;
    private int page;
    private String firstPlayer;

    public PlayerSelectScreen(MenuKind kind, List<String> players) {
        this(kind, players, List.of(), "");
    }

    public PlayerSelectScreen(MenuKind kind, List<String> players, List<String> arenas) {
        this(kind, players, arenas, "");
    }

    public PlayerSelectScreen(MenuKind kind, List<String> players, List<String> arenas, String arena) {
        super(Component.literal(switch (kind) {
            case ADMIN -> "管理员指定对战";
            case CHALLENGE_ALL -> "选择 1v1 挑战对象";
            case BATTLE -> "选择 1v1 挑战对象";
        }));
        this.kind = kind;
        this.players = players.stream().distinct().toList();
        this.arenas = arenas == null ? List.of() : List.copyOf(arenas);
        this.arena = arena == null ? "" : arena;
    }

    private boolean isChallenge() { return kind != MenuKind.ADMIN; }

    private int rows() { return Math.max(1, Math.min(10, (height - 116) / 22)); }
    private int panelWidth() { return Math.min(320, width - 16); }
    private int panelHeight() { return 102 + rows() * 22; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int pages() { return Math.max(1, (players.size() + rows() - 1) / rows()); }

    /** 自己那一行要置灰：不能挑战自己。 */
    private boolean isSelf(String name) {
        var connection = minecraft == null ? null : minecraft.getConnection();
        if (connection == null) return false;
        var info = connection.getPlayerInfo(name);
        return info != null && info.getProfile() != null
                && minecraft.player != null
                && info.getProfile().getId().equals(minecraft.player.getUUID());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 先画原版背景（含任何 widget/工具提示），再画自定义面板，避免面板被覆盖发糊。
        super.render(graphics, mouseX, mouseY, delta);
        page = Math.min(page, pages() - 1);
        graphics.fill(0, 0, width, height, 0xC0000000);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        BattleMenuScreen.drawPanel(graphics, x, y, w, h);
        PixelUi.centered(graphics, font, title, width / 2, y + 12, BattleMenuScreen.TEXT);
        String hint;
        if (isChallenge()) {
            hint = "点击玩家发送挑战（对方有 10 秒时间接受）";
            if (!arena.isEmpty()) {
                hint = "场地：" + arena + " · " + hint;
            }
        } else {
            hint = firstPlayer == null ? "先选择第一位玩家" : "已选：" + firstPlayer + "；再选择一位";
        }
        PixelUi.centered(graphics, font, hint, width / 2, y + 29, BattleMenuScreen.TEXT);
        if (players.isEmpty()) {
            PixelUi.centered(graphics, font, "暂无可选玩家", width / 2, y + 52, BattleMenuScreen.MUTED);
        }
        for (int row = 0; row < rows(); row++) {
            int index = page * rows() + row;
            if (index >= players.size()) break;
            String name = players.get(index);
            int rowY = y + 47 + row * 22;
            boolean self = isSelf(name);
            // 头像 + 名字 + 状态
            renderRow(graphics, name, x + 16, rowY, w - 32, 20,
                    BattleMenuScreen.inside(mouseX, mouseY, x + 16, rowY, w - 32, 20),
                    name.equals(firstPlayer), !self, self);
        }
        int navY = y + h - 45;
        BattleMenuScreen.drawButton(graphics, font, "上一页", x + 16, navY, 64, 20,
                BattleMenuScreen.inside(mouseX, mouseY, x + 16, navY, 64, 20), false, page > 0);
        PixelUi.centered(graphics, font, (page + 1) + " / " + pages(), width / 2, navY + 6, BattleMenuScreen.TEXT);
        BattleMenuScreen.drawButton(graphics, font, "下一页", x + w - 80, navY, 64, 20,
                BattleMenuScreen.inside(mouseX, mouseY, x + w - 80, navY, 64, 20), false, page + 1 < pages());
        BattleMenuScreen.drawButton(graphics, font, isChallenge() ? "关闭" : "关闭",
                x + w / 2 - 32, y + h - 22, 64, 18,
                BattleMenuScreen.inside(mouseX, mouseY, x + w / 2 - 32, y + h - 22, 64, 18), false, true);
    }

    /** 一行：皮肤头 + 玩家名 +（自己/在线）状态文字。 */
    private void renderRow(GuiGraphics graphics, String name, int x, int y, int w, int h,
                           boolean hovered, boolean selected, boolean enabled, boolean self) {
        int fill = selected ? 0xFFE4C77D : hovered && enabled ? 0xFFF7EED6 : 0xFFF4E7C5;
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, BattleMenuScreen.BORDER);
        graphics.fill(x, y, x + w, y + h, fill);
        graphics.fill(x, y, x + w, y + 1, 0xFFFFF8E8);

        var connection = minecraft == null ? null : minecraft.getConnection();
        int headSize = 12;
        int headX = x + 4;
        int headY = y + (h - headSize) / 2;
        boolean drewHead = false;
        if (connection != null) {
            var info = connection.getPlayerInfo(name);
            if (info != null) {
                graphics.fill(headX, headY, headX + headSize, headY + headSize, 0xFF8C7A60);
                try {
                    net.minecraft.client.gui.components.PlayerFaceRenderer.draw(graphics,
                            info.getSkin().texture(), headX, headY, headSize);
                    drewHead = true;
                } catch (Throwable ignored) {
                    // 皮肤未加载：保留灰底
                }
            }
        }
        if (!drewHead) graphics.fill(headX, headY, headX + headSize, headY + headSize, 0xFF8C7A60);

        int textX = headX + headSize + 6;
        int color = enabled ? BattleMenuScreen.TEXT : BattleMenuScreen.MUTED;
        graphics.drawString(font, font.plainSubstrByWidth(name, w - headSize - 80), textX, y + (h - 8) / 2,
                color, false);
        String status = self ? "你自己" : "在线";
        graphics.drawString(font, status, x + w - 6 - font.width(status), y + (h - 8) / 2,
                self ? BattleMenuScreen.MUTED : 0xFF2E7D32, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        for (int row = 0; row < rows(); row++) {
            int index = page * rows() + row;
            if (index >= players.size()) break;
            if (!BattleMenuScreen.inside(mouseX, mouseY, x + 16, y + 47 + row * 22, w - 32, 20)) continue;
            String selected = players.get(index);
            if (isSelf(selected)) {
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("不能挑战自己，请选择其他玩家。"), true);
                }
                return true;
            }
            if (isChallenge()) {
                ClientPlayNetworking.send(new MenuActionPayload(kind, "CHALLENGE", selected, arena));
                onClose();
            } else if (firstPlayer == null) {
                firstPlayer = selected;
            } else if (firstPlayer.equals(selected)) {
                firstPlayer = null;
            } else {
                ClientPlayNetworking.send(new MenuActionPayload(kind, "ADMIN", firstPlayer, selected));
                onClose();
            }
            return true;
        }
        int navY = y + h - 45;
        if (BattleMenuScreen.inside(mouseX, mouseY, x + 16, navY, 64, 20)) {
            page = Math.max(0, page - 1);
            return true;
        }
        if (BattleMenuScreen.inside(mouseX, mouseY, x + w - 80, navY, 64, 20)) {
            page = Math.min(pages() - 1, page + 1);
            return true;
        }
        if (BattleMenuScreen.inside(mouseX, mouseY, x + w / 2 - 32, y + h - 22, 64, 18)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

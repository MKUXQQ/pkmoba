package com.pkmoba.client;

import com.pkmoba.network.MenuActionPayload;
import com.pkmoba.network.MenuOpenPayload;
import com.pkmoba.network.MenuOpenPayload.MenuKind;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native, non-pausing entry screen for the battle item. */
public final class BattleMenuScreen extends Screen {
    static final int PANEL = 0xFFF2E2C2;
    static final int BORDER = 0xFF4A3A28;
    static final int HIGHLIGHT = 0xFFFFF6E2;
    static final int SHADOW = 0xFFC9B48F;
    static final int TEXT = 0xFF493522;
    static final int MUTED = 0xFF8C7A60;

    private final List<String> players;
    private final List<String> arenas;
    private final MenuKind kind;
    /** 0 表示"不使用场地"（无界自由对局），1..n 对应 arenas 列表。 */
    private int arenaIndex;

    public BattleMenuScreen(List<String> players) {
        this(players, List.of(), MenuKind.BATTLE);
    }

    public BattleMenuScreen(List<String> players, List<String> arenas, MenuKind kind) {
        super(Component.literal("宝可梦对战"));
        this.players = List.copyOf(players);
        this.arenas = arenas == null ? List.of() : List.copyOf(arenas);
        this.kind = kind == null ? MenuKind.BATTLE : kind;
    }

    /** Call from the S2C receiver; this method schedules all screen changes on the client thread. */
    public static void handleMenuOpen(MenuOpenPayload payload) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null || client.level == null) return;
            client.setScreen(payload.kind() == MenuKind.ADMIN
                    ? new PlayerSelectScreen(payload.kind(), payload.players(), payload.arenas())
                    : new BattleMenuScreen(payload.players(), payload.arenas(), payload.kind()));
        });
    }

    private int panelWidth() { return Math.min(320, width - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - 190) / 2; }

    /** 当前选中的场地名；空串表示不指定场地。 */
    private String arenaName() {
        if (arenaIndex <= 0 || arenaIndex > arenas.size()) return "";
        return arenas.get(arenaIndex - 1);
    }

    private String arenaLabel() {
        String name = arenaName();
        return name.isEmpty() ? "不使用场地（自由对局）" : name;
    }

    private int arenaRowY() { return top() + 83; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 先画原版背景（含任何 widget/工具提示），再画自定义面板，避免面板被覆盖发糊。
        super.render(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0xC0000000);
        int x = left(), y = top(), w = panelWidth();
        drawPanel(graphics, x, y, w, 190);
        graphics.drawCenteredString(font, title, width / 2, y + 17, TEXT);
        drawButton(graphics, font, "邀请玩家 1v1", x + 20, y + 42, w - 40, 32,
                inside(mouseX, mouseY, x + 20, y + 42, w - 40, 32), false, true);

        // 场地选择行：< 场地名 >
        int rowY = arenaRowY();
        drawButton(graphics, font, "<", x + 20, rowY, 22, 20,
                inside(mouseX, mouseY, x + 20, rowY, 22, 20), false, arenaIndex > 0);
        drawButton(graphics, font, ">", x + w - 42, rowY, 22, 20,
                inside(mouseX, mouseY, x + w - 42, rowY, 22, 20), false, arenaIndex < arenas.size());
        graphics.drawCenteredString(font, "场地：" + arenaLabel(), width / 2, rowY + 6, TEXT);

        graphics.drawCenteredString(font, "双方各控一只宝可梦，先倒下判负", width / 2, y + 118, TEXT);
        graphics.drawCenteredString(font,
                arenas.isEmpty() ? "还没有场地，用 /mo arena create <名字> 创建" : "也可直接输入 /mo duel <玩家名> [场地]",
                width / 2, y + 134, MUTED);
        drawButton(graphics, font, "关闭", x + w / 2 - 38, y + 154, 76, 23,
                inside(mouseX, mouseY, x + w / 2 - 38, y + 154, 76, 23), false, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = left(), y = top(), w = panelWidth();
        if (inside(mouseX, mouseY, x + 20, y + 42, w - 40, 32)) {
            minecraft.setScreen(new PlayerSelectScreen(kind, players, arenas, arenaName()));
            return true;
        }
        int rowY = arenaRowY();
        if (inside(mouseX, mouseY, x + 20, rowY, 22, 20)) {
            arenaIndex = Math.max(0, arenaIndex - 1);
            return true;
        }
        if (inside(mouseX, mouseY, x + w - 42, rowY, 22, 20)) {
            arenaIndex = Math.min(arenas.size(), arenaIndex + 1);
            return true;
        }
        if (inside(mouseX, mouseY, x + w / 2 - 38, y + 154, 76, 23)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    static void drawPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, BORDER);
        graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, PANEL);
        graphics.fill(x + 3, y + 3, x + w - 3, y + 5, HIGHLIGHT);
        graphics.fill(x + 3, y + h - 5, x + w - 3, y + h - 3, SHADOW);
    }

    static void drawButton(GuiGraphics graphics, net.minecraft.client.gui.Font font, String label,
            int x, int y, int w, int h, boolean hovered, boolean selected, boolean enabled) {
        graphics.fill(x, y, x + w, y + h, BORDER);
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2,
                selected ? 0xFFE4C77D : hovered && enabled ? HIGHLIGHT : PANEL);
        graphics.fill(x + 2, y + h - 4, x + w - 2, y + h - 2, SHADOW);
        graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, enabled ? TEXT : MUTED);
    }
}

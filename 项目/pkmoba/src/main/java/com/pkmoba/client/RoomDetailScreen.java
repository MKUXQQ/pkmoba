package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 房间内部界面（0.41.8 新增）。
 *
 * <p>用户 m05076：「进到一个新的 UI，这个 UI 你来设计，显示对方宝可梦和我方宝可梦的，
 * 然后还有退出房间」。左边是我方（头像 + 名字 + 出战队伍），右边是对方（头像 + 名字 +
 * 「等待玩家加入」/「已就位」）；底部两个自绘按钮：退出房间、关闭。画风与大厅完全一致。
 */
public final class RoomDetailScreen extends Screen {
    private static final int PANEL_W_MAX = 330;
    private static final int PANEL_H = 210;
    private static final int PAD = 10;
    /** 出战列表里宝可梦头像的边长（像素）。 */
    private static final int PORTRAIT = 14;

    private static final int PANEL = 0xFFF3E7CE;
    private static final int BORDER = 0xFF3B2B1E;
    private static final int INNER = 0xFFE7D7B8;
    private static final int TEXT = 0xFF3A2A1C;
    private static final int MUTED = 0xFF8A7256;
    private static final int GOLD = 0xFFD8402F;
    private static final int RED = 0xFFC4564E;
    private static final int BLUE = 0xFF5C7CC4;

    private final int roomId;
    private String hostName = "";
    private String redName = "";
    private String blueName = "";
    private int state;
    private int seconds;
    private boolean hoverLeave;
    private boolean hoverClose;

    public RoomDetailScreen(RoomListPayload.RoomView room) {
        super(Component.literal("房间"));
        this.roomId = room.id();
        apply(room);
    }

    private void apply(RoomListPayload.RoomView room) {
        this.hostName = room.host();
        this.redName = room.red();
        this.blueName = room.blue();
        this.state = room.state();
        this.seconds = room.seconds();
    }

    /** 房间快照刷新（大厅每收到一次快照都会调到这里）。 */
    void update(RoomListPayload payload) {
        for (RoomListPayload.RoomView room : payload.rooms()) {
            if (room.id() == roomId) {
                apply(room);
                return;
            }
        }
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

    private int leaveX() {
        return left() + panelWidth() / 2 - 94;
    }

    private int closeX() {
        return left() + panelWidth() / 2 + 6;
    }

    private int buttonY() {
        return top() + PANEL_H - 30;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101014);
        int x = left();
        int y = top();
        int w = panelWidth();

        g.fill(x, y, x + w, y + PANEL_H, PANEL);
        RoomLobbyScreen.border(g, x, y, w, PANEL_H, BORDER);
        g.fill(x + 4, y + 4, x + w - 4, y + 22, INNER);
        g.drawString(font, "房间 " + roomId, x + PAD, y + 9, GOLD, false);
        String status = switch (state) {
            case 2 -> "倒计时 " + Math.max(0, seconds) + "s";
            case 3 -> "对战中";
            case 4 -> "已结束";
            default -> "等待开始";
        };
        g.drawString(font, status, x + w - PAD - font.width(status), y + 9, MUTED, false);

        // 左右两栏：我方（左）/ 对方（右）
        int colW = (w - PAD * 3) / 2;
        int colY = y + 30;
        int colH = 118;
        drawSide(g, x + PAD, colY, colW, colH, "我方", myName(), mySideIsBlue(), true);
        drawSide(g, x + PAD * 2 + colW, colY, colW, colH, "对方", opponentName(), !mySideIsBlue(), false);

        // 底部按钮
        hoverLeave = mouseX >= leaveX() && mouseX < leaveX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20;
        hoverClose = mouseX >= closeX() && mouseX < closeX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20;
        RoomLobbyScreen.button(g, leaveX(), buttonY(), 88, 20, "退出房间", hoverLeave);
        RoomLobbyScreen.button(g, closeX(), buttonY(), 88, 20, "关闭", hoverClose);
    }

    private String myName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getGameProfile().getName();
    }

    /** 我在这一局是红方还是蓝方（用名字判断，服务端已经保证同一房间不重名）。 */
    private boolean mySideIsBlue() {
        String me = myName();
        if (me.isEmpty()) {
            return false;
        }
        if (blueName.equals(me)) {
            return true;
        }
        if (redName.equals(me)) {
            return false;
        }
        // 还没分边：房主默认红方，先进来的第二个是蓝方
        return !hostName.equals(me);
    }

    private String opponentName() {
        String me = myName();
        if (hostName.equals(me)) {
            return blueName.isEmpty() ? "" : blueName;
        }
        return redName.isEmpty() ? "" : redName;
    }

    /** 一栏：标题 / 头像+名字 / 三条宝可梦。 */
    private void drawSide(GuiGraphics g, int x, int y, int w, int h, String title, String name,
                          boolean blue, boolean mine) {
        g.fill(x, y, x + w, y + h, INNER);
        RoomLobbyScreen.border(g, x, y, w, h, BORDER);
        g.drawString(font, title, x + 6, y + 5, mine ? GOLD : MUTED, false);
        g.fill(x + 6, y + 15, x + w - 6, y + 16, blue ? BLUE : RED);

        if (name == null || name.isEmpty()) {
            g.drawString(font, "等待玩家加入…", x + 8, y + 34, MUTED, false);
            g.drawString(font, "（把 H 房间号告诉对方）", x + 8, y + 46, MUTED, false);
            return;
        }
        RoomLobbyScreen.drawHead(g, name, x + 8, y + 24, 20);
        g.drawString(font, name, x + 34, y + 29, TEXT, false);

        // 宝可梦：我方读本地队伍；对方不公开就不显示具体的
        int lineY = y + 54;
        if (mine) {
            java.util.List<String> team = ClientState.getTeam();
            g.drawString(font, "出战：", x + 8, lineY, MUTED, false);
            lineY += 12;
            for (int i = 0; i < Math.min(3, team.size()); i++) {
                String id = team.get(i);
                // 用户要求：名字前面带宝可梦头像（和左侧 HUD 同一套 64×64 头像贴图），
                // 名字用中文（SpeciesRegistry.displayName），不再显示 cobblemon 的英文 id。
                ResourceLocation portrait = portraitOf(id);
                int icon = PORTRAIT;
                if (portrait != null) {
                    g.blit(portrait, x + 12, lineY, icon, icon, 0.0F, 0.0F, 64, 64, 64, 64);
                } else {
                    g.fill(x + 12, lineY, x + 12 + icon, lineY + icon, 0xFFCFC0A0);
                }
                g.drawString(font, displayName(id), x + 12 + icon + 4, lineY + (icon - 8) / 2, TEXT, false);
                lineY += icon + 1;
            }
        } else {
            g.drawString(font, state >= 3 ? "对战中" : "已就位", x + 8, lineY, TEXT, false);
            g.drawString(font, "（对方的宝可梦开局后可见）", x + 8, lineY + 12, MUTED, false);
        }
    }

    /** 队伍里的物种 id → 中文名（cobblemon:blastoise → 水箭龟）。 */
    private static String displayName(String speciesId) {
        ResourceLocation id = speciesId == null || speciesId.isEmpty() ? null
                : ResourceLocation.tryParse(speciesId.contains(":") ? speciesId : "cobblemon:" + speciesId);
        return com.pkmoba.pokemon.SpeciesRegistry.displayName(id);
    }

    /** 队伍里的物种 id → 头像贴图（pkmoba:textures/gui/pokemon/<species>.png）。 */
    private static ResourceLocation portraitOf(String speciesId) {
        ResourceLocation id = speciesId == null || speciesId.isEmpty() ? null
                : ResourceLocation.tryParse(speciesId.contains(":") ? speciesId : "cobblemon:" + speciesId);
        var entry = com.pkmoba.pokemon.SpeciesRegistry.byId(id);
        return entry == null ? null : ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (mouseX >= leaveX() && mouseX < leaveX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20) {
            ClientRooms.send("LEAVE", roomId, "");
            onClose();
            return true;
        }
        if (mouseX >= closeX() && mouseX < closeX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

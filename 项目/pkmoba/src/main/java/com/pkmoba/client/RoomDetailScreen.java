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
    private static final int PANEL_W_MAX = 420;
    private static final int PANEL_H = 224;
    private static final int PAD = 10;
    /** 出战列表里宝可梦头像的边长（像素）。 */
    private static final int PORTRAIT = 14;

    private static final int PANEL = PixelUi.CREAM;
    private static final int BORDER = PixelUi.INK;
    private static final int INNER = PixelUi.PAPER;
    private static final int TEXT = PixelUi.TEXT;
    private static final int MUTED = PixelUi.MUTED;
    private static final int GOLD = PixelUi.GREEN;
    private static final int RED = PixelUi.RED;
    private static final int BLUE = PixelUi.BLUE;

    private final int roomId;
    private String hostName = "";
    private String redId = "", blueId = "";
    private java.util.List<String> redTeam = java.util.List.of(), blueTeam = java.util.List.of();
    private String redName = "";
    private String blueName = "";
    private int state;
    private int seconds;
    private String arenaName = "";
    private boolean canStart;
    private boolean leaving;
    private boolean hoverStart;
    private boolean hoverLeave;
    private boolean hoverClose;
    private boolean requestingBot;
    private int botRequestTicks;
    private int pointerX;
    private int pointerY;

    private boolean mouseInsideOpponentButton(int x, int y, int w) {
        return pointerX >= x + 8 && pointerX < x + w - 8
                && pointerY >= y + 78 && pointerY < y + 100;
    }

    private boolean canSummonBot() {
        return !requestingBot && state <= 1 && hostName.equals(myName())
                && opponentName().isEmpty();
    }

    private boolean botOpponent() {
        return opponentName().contains("人机");
    }

    @Override
    public void tick() {
        if (botRequestTicks > 0 && --botRequestTicks == 0) requestingBot = false;
    }

    public RoomDetailScreen(RoomListPayload.RoomView room) {
        super(Component.literal("房间"));
        this.roomId = room.id();
        apply(room);
    }

    private void apply(RoomListPayload.RoomView room) {
        this.hostName = room.host();
        this.redId = room.redId();
        this.blueId = room.blueId();
        this.redTeam = room.redTeam();
        this.blueTeam = room.blueTeam();
        this.redName = room.red();
        this.blueName = room.blue();
        this.state = room.state();
        this.seconds = room.seconds();
        this.arenaName = room.arena();
        this.canStart = room.canStart();
    }

    /** 房间快照刷新（大厅每收到一次快照都会调到这里）。 */
    void update(RoomListPayload payload) {
        if (payload.myRoomId() != roomId) {
            if (minecraft != null) minecraft.setScreen(new RoomLobbyScreen());
            return;
        }
        leaving = false;
        requestingBot = false;
        botRequestTicks = 0;
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
        pointerX = mouseX;
        pointerY = mouseY;
        g.fill(0, 0, width, height, 0xB0252A20);
        int x = left();
        int y = top();
        int w = panelWidth();

        PixelUi.panel(g, x, y, w, PANEL_H, PANEL);
        PixelUi.frame(g, x + 6, y + 6, w - 12, 20, INNER, BORDER);
        PixelUi.ball(g, x + 11, y + 10, 1);
        String heading = "房间 #" + roomId + " · " + arenaName;
        g.drawString(font, PixelUi.fit(font, heading, w - 116), x + 29, y + 12, TEXT, false);
        String status = switch (state) {
            case 2 -> "倒计时 " + Math.max(0, seconds) + "s";
            case 3 -> "对战中";
            case 4 -> "已结束";
            default -> "等待开始";
        };
        g.drawString(font, status, x + w - PAD - font.width(status), y + 12, state == 3 ? RED : GOLD, false);

        // 左右两栏：我方（左）/ 对方（右）
        int colW = (w - PAD * 3) / 2;
        int colY = y + 30;
        int colH = 118;
        drawSide(g, x + PAD, colY, colW, colH, "我方", myName(), mySideIsBlue(), true);
        drawSide(g, x + PAD * 2 + colW, colY, colW, colH, "对方", opponentName(), !mySideIsBlue(), false);

        String rule = "开始后自动收回 · 关闭界面倒计时 · X召唤";
        g.drawCenteredString(font, PixelUi.fit(font, rule, w - 20), x + w / 2, y + 151, MUTED);

        // 底部按钮
        hoverLeave = mouseX >= leaveX() && mouseX < leaveX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20;
        hoverClose = mouseX >= closeX() && mouseX < closeX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20;
        boolean locked = state == 2 || state == 3 || state == 4;
        PixelUi.button(g, font, leaveX(), buttonY(), 88, 20,
                locked ? "对局中不可退出" : leaving ? "正在退出…" : "退出房间", hoverLeave, !locked && !leaving);
        hoverStart = mouseX >= x + PAD && mouseX < x + w - PAD
                && mouseY >= y + 165 && mouseY < y + 185;
        String startLabel = (canStart || botOpponent() && state <= 1) ? "开始挑战"
                : state == 2 ? "倒计时结束后自动开战"
                : state == 3 ? "对战进行中" : redName.isEmpty() || blueName.isEmpty()
                ? "等待另一位训练家 / 召唤人机" : "确认队伍并准备";
        PixelUi.button(g, font, x + PAD, y + 165, w - PAD * 2, 20,
                PixelUi.fit(font, startLabel, w - PAD * 2 - 12), hoverStart, canStart || (state <= 1 && !leaving));
        PixelUi.button(g, font, closeX(), buttonY(), 88, 20, "关闭", hoverClose, true);
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
        return mySideIsBlue() ? redName : blueName;
    }

    /** 一栏：标题 / 头像+名字 / 三条宝可梦。 */
    private void drawSide(GuiGraphics g, int x, int y, int w, int h, String title, String name,
                          boolean blue, boolean mine) {
        PixelUi.frame(g, x, y, w, h, INNER, BORDER);
        g.fill(x + 3, y + 3, x + w - 3, y + 18, blue ? 0xFFDEE6EB : 0xFFF0DDD1);
        g.drawString(font, title + " · " + (blue ? "蓝方" : "红方"), x + 7, y + 6, blue ? BLUE : RED, false);
        g.fill(x + 4, y + 18, x + w - 4, y + 19, PixelUi.SHADOW);

        if (name == null || name.isEmpty()) {
            g.drawString(font, PixelUi.fit(font, "等待训练家加入…", w - 16), x + 8, y + 62, MUTED, false);
            if (!mine) {
                boolean hovered = mouseInsideOpponentButton(x, y, w);
                PixelUi.button(g, font, x + 8, y + 78, w - 16, 22,
                        requestingBot ? "正在召唤…" : "召唤人机", hovered, canSummonBot());
            }
            return;
        }
        RoomLobbyScreen.drawHead(g, name, blue ? blueId : redId, x + 8, y + 24, 20);
        g.drawString(font, PixelUi.fit(font, name, w - 42), x + 34, y + 29, TEXT, false);

        java.util.List<String> team = blue ? blueTeam : redTeam;
        int lineY = y + 53;
        g.drawString(font, team.isEmpty() ? "尚未选择宝可梦" : "出战宝可梦", x + 8, lineY, MUTED, false);
        for (int i = 0; i < Math.min(6, team.size()); i++) {
            int tx = x + 8 + (i % 2) * ((w - 16) / 2);
            int ty = lineY + 12 + (i / 2) * 16;
            ResourceLocation portrait = portraitOf(team.get(i));
            if (portrait != null) g.blit(portrait, tx, ty, PORTRAIT, PORTRAIT, 0.0F, 0.0F, 64, 64, 64, 64);
            g.drawString(font, PixelUi.fit(font, displayName(team.get(i)), (w - 16) / 2 - 18), tx + 17, ty + 3, TEXT, false);
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
        int colW = (panelWidth() - PAD * 3) / 2;
        int botX = left() + PAD * 2 + colW + 8;
        int botY = top() + 30 + 78;
        if (canSummonBot() && mouseX >= botX && mouseX < botX + colW - 16
                && mouseY >= botY && mouseY < botY + 22) {
            requestingBot = true;
            botRequestTicks = 60;
            ClientRooms.send("ADD_BOT", roomId, "");
            return true;
        }
        if (mouseX >= left() + PAD && mouseX < left() + panelWidth() - PAD
                && mouseY >= top() + 165 && mouseY < top() + 185) {
            if (canStart || (state <= 1 && !leaving && !redName.isEmpty() && !blueName.isEmpty())) {
                boolean starting = canStart || botOpponent();
                ClientRooms.send(starting ? "START" : "READY", roomId, "");
                if (starting) Minecraft.getInstance().setScreen(null);
            }
            return true;
        }
        if (mouseX >= leaveX() && mouseX < leaveX() + 88 && mouseY >= buttonY() && mouseY < buttonY() + 20) {
            if (state == 2 || state == 3 || state == 4 || leaving) return true;
            leaving = true;
            ClientRooms.send("LEAVE", roomId, "");
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

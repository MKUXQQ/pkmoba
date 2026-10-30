package com.pkmoba.client;

import com.pkmoba.network.StarterChoosePayload;
import com.pkmoba.network.StarterListPayload;
import com.pkmoba.network.RoomListPayload;
import com.pkmoba.pokemon.SpeciesRegistry;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * 开局选择（PICK）与「获取宝可梦」（ADD）共用的界面。
 *
 * <p>两次交互模型：
 * <ul>
 *   <li>PICK：点卡片即选定初始宝可梦，直接关界面；</li>
 *   <li>ADD：点卡片先「选中」，再点「获取」才真正请求服务端加入队伍；
 *       服务端会把关（是否已拥有、队伍是否已满、物种是否存在），失败给原因。</li>
 * </ul>
 *
 * <p>0.51 扩展物种：物种扩到 12 只后单排放不下，改为自适应网格——最多 4 列 × 3 行，
 * 屏幕窄时自动减列并翻页（每页容量 = 列数 × 行数），保证 12 张卡片互不重叠。
 */
public final class StarterScreen extends Screen {
    /** 每张卡片的最大尺寸（屏幕放不下时按列宽等比缩小）。 */
    private static final int CARD_W = 78;
    private static final int CARD_H = 82;
    /** 卡片最小宽度：低于它就不再挤列，改为翻页。 */
    private static final int MIN_CARD_W = 64;
    private static final int GAP = 10;
    /** 网格上限：12 只扩展物种按 4 列 × 3 行铺满。 */
    private static final int MAX_COLS = 4;
    private static final int MAX_ROWS = 3;
    /** 头像贴图是 64×64 的网络抓取图，最大显示 48×48。 */
    private static final float PORTRAIT_SIZE = 64.0F;

    private List<String> species;
    private List<String> team;
    private List<String> fainted;
    private String chosen;
    private boolean adding;

    /** ADD 模式下当前选中的物种 id（空串表示没选）。 */
    private String selected = "";
    private int page;
    /** 房间入口只是获取收藏；关闭回到阵容编辑，不能把已拥有卡片当删除按钮。 */
    private RoomTeamScreen roomParent;
    private static long roomPickerSuppressUntilNanos;

    public boolean isRoomPicker() { return roomParent != null; }

    public static void openAddForRoom(RoomTeamScreen parent) {
        RoomListPayload.RoomView room = ClientRooms.currentRoom(parent.roomId());
        if (room == null || ClientRooms.myRoomId() != room.id()) { ClientRooms.returnToRoom(parent.roomId()); return; }
        List<String> favorites = ClientRooms.isBlue(room) ? room.blueTeam() : room.redTeam();
        List<String> allSpecies = SpeciesRegistry.all().stream().map(entry -> entry.id().toString()).toList();
        StarterScreen screen = new StarterScreen(new StarterListPayload("ADD", "", allSpecies, favorites, List.of()));
        screen.roomParent = parent;
        roomPickerSuppressUntilNanos = 0;
        Minecraft.getInstance().setScreen(screen);
    }

    static void cancelRoomReturn() {
        roomPickerSuppressUntilNanos = System.nanoTime() + 5_000_000_000L;
    }

    void updateRoom(RoomListPayload snapshot) {
        if (roomParent == null) return;
        RoomListPayload.RoomView room = ClientRooms.currentRoom(roomParent.roomId());
        if (room == null || snapshot.myRoomId() != roomParent.roomId() || room.state() >= 2) {
            cancelRoomReturn();
            ClientRooms.returnToRoom(roomParent.roomId());
        } else {
            team = List.copyOf(ClientRooms.isBlue(room) ? room.blueTeam() : room.redTeam());
            roomParent.update(room);
        }
    }

    public StarterScreen(StarterListPayload payload) {
        super(Component.literal(payload.adding() ? "获取宝可梦" : "选择宝可梦"));
        this.species = List.copyOf(payload.species());
        this.team = List.copyOf(payload.team());
        this.fainted = List.copyOf(payload.fainted());
        this.chosen = payload.chosen() == null ? "" : payload.chosen();
        this.adding = payload.adding();
    }

    /** S2C 接收器入口：切到客户端线程再开界面。 */
    public static void open(StarterListPayload payload) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null || client.level == null) {
                return;
            }
            client.setScreen(new StarterScreen(payload));
        });
    }

    /** 队伍变动后服务端会重发列表：界面已经开着就原地刷新，不要重建（保留页码与选中项）。 */
    public static void refresh(StarterListPayload payload) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof StarterScreen open) {
                open.apply(payload);
            } else if (client.screen instanceof RoomTeamScreen teamScreen && payload.adding()) {
                teamScreen.refreshFromLatest();
            } else if (payload.adding() && System.nanoTime() < roomPickerSuppressUntilNanos) {
                // 退出房间取宠器后可能还有一个旧 ADD 响应在队列里，不能重新打开取宠 UI。
            } else {
                open(payload);
            }
        });
    }

    /** 原地更新数据，并把页码/选中项收敛到合法范围。 */
    private void apply(StarterListPayload payload) {
        this.species = List.copyOf(payload.species());
        this.team = List.copyOf(payload.team());
        this.fainted = List.copyOf(payload.fainted());
        this.chosen = payload.chosen() == null ? "" : payload.chosen();
        this.adding = payload.adding();
        this.page = Math.min(this.page, pageCount() - 1);
        if (!selected.isEmpty() && (team.contains(selected) || !species.contains(selected))) {
            selected = "";
        }
    }

    /** 当前屏幕宽度下能放下的列数（1–4）。 */
    private int columns() {
        int fit = (width - 48 + GAP) / (MIN_CARD_W + GAP);
        return Math.max(1, Math.min(MAX_COLS, fit));
    }

    /**
     * 网格行数：物种少时只占一行，最多 3 行；屏幕太矮（例如 GUI 高度 240）时按行高下限减行，
     * 宁可翻页也不让卡片压到面板底部按钮或溢出屏幕。
     */
    private int rows() {
        int cols = columns();
        int byCount = (species.size() + cols - 1) / cols;
        int byHeight = Math.max(1, (height - 134 + GAP) / (CARD_H + GAP));
        return Math.max(1, Math.min(MAX_ROWS, Math.min(byCount, byHeight)));
    }

    /** 每页容量 = 列数 × 行数。 */
    private int cardsPerPage() {
        return Math.max(1, columns() * rows());
    }

    private int cardWidth() {
        int cols = columns();
        int available = Math.max(1, width - 16 - 32 - (cols - 1) * GAP);
        return Math.min(CARD_W, Math.max(48, available / cols));
    }

    private int cardHeight() {
        return Math.min(CARD_H, Math.max(48, cardWidth()));
    }

    private int pageCount() {
        int perPage = cardsPerPage();
        return Math.max(1, (species.size() + perPage - 1) / perPage);
    }

    private int firstIndex() {
        return page * cardsPerPage();
    }

    private int pageCards() {
        return Math.min(cardsPerPage(), species.size() - firstIndex());
    }

    private int gridHeight() {
        return rows() * cardHeight() + (rows() - 1) * GAP;
    }

    private int panelWidth() {
        int cols = Math.max(1, columns());
        return Math.min(width - 16, cols * cardWidth() + (cols - 1) * GAP + 32);
    }

    private int panelHeight() {
        int extra = pageCount() > 1 ? 12 : 0;
        return Math.min(height - 16, gridHeight() + 118 + extra);
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int top() {
        return (height - panelHeight()) / 2;
    }

    /** 有分页时把分页条放在标题下方，卡片整体下移，避免压住提示行。 */
    private int gridTop() {
        return top() + (pageCount() > 1 ? 56 : 44);
    }

    private int navY() {
        return top() + 30;
    }

    private int cardX(int slot) {
        int cols = Math.max(1, columns());
        int row = slot / cols;
        int col = slot % cols;
        int rowCards = Math.max(1, Math.min(cols, pageCards() - row * cols));
        int total = rowCards * cardWidth() + (rowCards - 1) * GAP;
        int start = (width - total) / 2;
        return start + col * (cardWidth() + GAP);
    }

    private int cardY(int slot) {
        return gridTop() + (slot / Math.max(1, columns())) * (cardHeight() + GAP);
    }

    /** 提示行紧跟在网格下方；按钮固定在面板底部。 */
    private int hintY() {
        return gridTop() + gridHeight() + 4;
    }

    private int confirmY() {
        return top() + panelHeight() - 44;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 先画原版背景，再画自定义面板：顺序反了会让面板被背景渐变冲淡（发糊）。
        super.render(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0xC0000000);

        int x = left();
        int y = top();
        int w = panelWidth();
        int h = panelHeight();
        BattleMenuScreen.drawPanel(graphics, x, y, w, h);
        PixelUi.centered(graphics, font, adding ? "获取宝可梦" : "选择你的宝可梦",
                width / 2, y + 14, BattleMenuScreen.TEXT);

        for (int slot = 0; slot < pageCards(); slot++) {
            String id = species.get(firstIndex() + slot);
            ResourceLocation rl = ResourceLocation.tryParse(id);
            boolean owned = team.contains(id);
            boolean dead = fainted.contains(id);
            boolean isSelected = adding ? id.equals(selected) : id.equals(chosen);
            int cw = cardWidth(), ch = cardHeight();
            int cx = cardX(slot);
            int cy = cardY(slot);
            boolean hovered = BattleMenuScreen.inside(mouseX, mouseY, cx, cy, cw, ch);

            graphics.fill(cx, cy, cx + cw, cy + ch, BattleMenuScreen.BORDER);
            graphics.fill(cx + 2, cy + 2, cx + cw - 2, cy + ch - 2,
                    isSelected ? 0xFFE4C77D : hovered ? BattleMenuScreen.HIGHLIGHT : BattleMenuScreen.PANEL);
            graphics.fill(cx + 2, cy + ch - 5, cx + cw - 2, cy + ch - 2,
                    BattleMenuScreen.SHADOW);

            int portraitSize = Math.min(48, Math.max(32, ch - 32));
            ResourceLocation portrait = portraitOf(rl);
            int px = cx + (cw - portraitSize) / 2;
            if (portrait != null) {
                graphics.pose().pushPose();
                graphics.pose().translate(px, cy + 5, 0);
                graphics.pose().scale(portraitSize / PORTRAIT_SIZE, portraitSize / PORTRAIT_SIZE, 1.0F);
                graphics.blit(portrait, 0, 0, 0, 0, 64, 64, 64, 64);
                graphics.pose().popPose();
            } else {
                graphics.fill(px, cy + 5, px + portraitSize, cy + 5 + portraitSize, 0xFFB5A88F);
            }

            // 已拥有 / 已阵亡角标
            if (dead) {
                graphics.fill(cx + 2, cy + 2, cx + cw - 2, cy + ch - 2, 0x66000000);
                PixelUi.centered(graphics, font, "已倒下", cx + cw / 2, cy + ch / 2, 0xFFC94F3D);
            } else if (owned) {
                String badge = id.equals(chosen) ? "当前" : "已拥有";
                graphics.drawString(font, badge, cx + 5, cy + 5, 0xFF2E7D32, false);
            }

            PixelUi.centered(graphics, font, SpeciesRegistry.displayName(rl), cx + cw / 2,
                    cy + ch - 20, BattleMenuScreen.TEXT);
        }

        // 分页按钮（只有多于一页时才显示，放在标题下方）
        if (pageCount() > 1) {
            int nav = navY();
            boolean canPrev = page > 0;
            boolean canNext = page < pageCount() - 1;
            BattleMenuScreen.drawButton(graphics, font, "<", width / 2 - 74, nav, 20, 16,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 74, nav, 20, 16), false, canPrev);
            BattleMenuScreen.drawButton(graphics, font, ">", width / 2 + 54, nav, 20, 16,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 + 54, nav, 20, 16), false, canNext);
            PixelUi.centered(graphics, font, (page + 1) + " / " + pageCount(), width / 2, nav + 4,
                    BattleMenuScreen.MUTED);
        }

        String hint;
        if (adding) {
            hint = selected.isEmpty()
                    ? "点击卡片选中，再点「获取」加入队伍（最多 6 只）"
                    : "已选中：" + SpeciesRegistry.displayName(ResourceLocation.tryParse(selected));
        } else if (chosen.isEmpty()) {
            hint = "首次进入：选择后即为你的初始宝可梦";
        } else {
            hint = "当前上场：" + SpeciesRegistry.displayName(ResourceLocation.tryParse(chosen));
        }
        PixelUi.centered(graphics, font, hint, width / 2, hintY(), BattleMenuScreen.MUTED);

        int btnY = confirmY();
        if (adding) {
            boolean canConfirm = !selected.isEmpty();
            BattleMenuScreen.drawButton(graphics, font, "获取", width / 2 - 84, btnY, 76, 20,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 84, btnY, 76, 20), false, canConfirm);
            BattleMenuScreen.drawButton(graphics, font, "关闭", width / 2 + 8, btnY, 76, 20,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 + 8, btnY, 76, 20), false, true);
        } else {
            BattleMenuScreen.drawButton(graphics, font, "关闭", width / 2 - 38, btnY, 76, 20,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 38, btnY, 76, 20), false, true);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int btnY = confirmY();

        // 关闭
        int closeX = adding ? width / 2 + 8 : width / 2 - 38;
        if (BattleMenuScreen.inside(mouseX, mouseY, closeX, btnY, 76, 20)) {
            onClose();
            return true;
        }
        // 获取（ADD 模式）
        if (adding && BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 84, btnY, 76, 20)) {
            requestAdd();
            return true;
        }
        // 分页
        if (pageCount() > 1) {
            int nav = navY();
            if (BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 74, nav, 20, 16)) {
                if (page > 0) {
                    page--;
                }
                return true;
            }
            if (BattleMenuScreen.inside(mouseX, mouseY, width / 2 + 54, nav, 20, 16)) {
                if (page < pageCount() - 1) {
                    page++;
                }
                return true;
            }
        }

        for (int slot = 0; slot < pageCards(); slot++) {
            int cx = cardX(slot);
            int cy = cardY(slot);
            if (!BattleMenuScreen.inside(mouseX, mouseY, cx, cy, cardWidth(), cardHeight())) {
                continue;
            }
            String id = species.get(firstIndex() + slot);
            if (adding) {
                if (team.contains(id)) {
                    if (roomParent != null) return true;
                    // 非房间旧获取入口仍保留原收藏删除语义。
                    if (ClientPlayNetworking.canSend(StarterChoosePayload.TYPE)) {
                        ClientPlayNetworking.send(new StarterChoosePayload("DELETE", id));
                    }
                    return true;
                }
                selected = id.equals(selected) ? "" : id;
                return true;
            }
            if (ClientPlayNetworking.canSend(StarterChoosePayload.TYPE)) {
                ClientPlayNetworking.send(new StarterChoosePayload("PICK", id));
            }
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** ADD 模式提交：只发一次请求，重复点击由服务端拒绝（已拥有/已满）。 */
    private void requestAdd() {
        if (selected.isEmpty()) {
            return;
        }
        if (ClientPlayNetworking.canSend(StarterChoosePayload.TYPE)) {
            ClientPlayNetworking.send(new StarterChoosePayload("ADD", selected));
            selected = "";
        }
    }

    @Override
    public void onClose() {
        if (roomParent != null) {
            cancelRoomReturn();
            RoomListPayload.RoomView room = ClientRooms.currentRoom(roomParent.roomId());
            if (room != null && ClientRooms.myRoomId() == room.id() && room.state() <= 1) {
                roomParent.refreshFromLatest();
                minecraft.setScreen(roomParent);
                ClientRooms.send("OPEN", 0, "");
            } else ClientRooms.returnToRoom(roomParent.roomId());
        } else super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static ResourceLocation portraitOf(ResourceLocation speciesId) {
        if (speciesId == null) {
            return null;
        }
        SpeciesRegistry.Entry entry = SpeciesRegistry.byId(speciesId);
        if (entry == null) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }
}

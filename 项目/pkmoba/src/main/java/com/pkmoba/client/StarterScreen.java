package com.pkmoba.client;

import com.pkmoba.network.StarterChoosePayload;
import com.pkmoba.network.StarterListPayload;
import com.pkmoba.pokemon.SpeciesRegistry;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 开局选择（PICK）与「获取宝可梦」（ADD）共用的界面。
 *
 * <p>两次交互模型：
 * <ul>
 *   <li>PICK：点卡片即选定初始宝可梦，直接关界面；</li>
 *   <li>ADD：点卡片先「选中」，再点「获取」才真正请求服务端加入队伍；
 *       服务端会把关（是否已拥有、队伍是否已满、物种是否存在），失败给原因。</li>
 * </ul>
 */
public final class StarterScreen extends Screen {
    /** 每张卡片的尺寸。 */
    private static final int CARD_W = 96;
    private static final int CARD_H = 96;
    private static final int GAP = 12;
    /** 每页最多几张卡片（超过则分页，避免面板被撑出屏幕）。 */
    private static final int PAGE_SIZE = 5;
    /** 头像贴图是 64×64 的网络抓取图，显示成 48×48。 */
    private static final float PORTRAIT_SIZE = 64.0F;

    private List<String> species;
    private List<String> team;
    private List<String> fainted;
    private String chosen;
    private boolean adding;

    /** ADD 模式下当前选中的物种 id（空串表示没选）。 */
    private String selected = "";
    private int page;

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

    private int pageCount() {
        return Math.max(1, (species.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private int firstIndex() {
        return page * PAGE_SIZE;
    }

    private int pageCards() {
        return Math.min(PAGE_SIZE, species.size() - firstIndex());
    }

    private int panelWidth() {
        int cards = Math.max(1, pageCards());
        return Math.max(240, cards * CARD_W + (cards - 1) * GAP + 32);
    }

    private int panelHeight() {
        return CARD_H + 118;
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int top() {
        return (height - panelHeight()) / 2;
    }

    private int cardX(int slot) {
        int cards = pageCards();
        int total = cards * CARD_W + (cards - 1) * GAP;
        int start = (width - total) / 2;
        return start + slot * (CARD_W + GAP);
    }

    private int cardY() {
        return top() + 40;
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
        graphics.drawCenteredString(font, adding ? "获取宝可梦" : "选择你的宝可梦",
                width / 2, y + 14, BattleMenuScreen.TEXT);

        for (int slot = 0; slot < pageCards(); slot++) {
            String id = species.get(firstIndex() + slot);
            ResourceLocation rl = ResourceLocation.tryParse(id);
            boolean owned = team.contains(id);
            boolean dead = fainted.contains(id);
            boolean isSelected = adding ? id.equals(selected) : id.equals(chosen);
            int cx = cardX(slot);
            int cy = cardY();
            boolean hovered = BattleMenuScreen.inside(mouseX, mouseY, cx, cy, CARD_W, CARD_H);

            graphics.fill(cx, cy, cx + CARD_W, cy + CARD_H, BattleMenuScreen.BORDER);
            graphics.fill(cx + 2, cy + 2, cx + CARD_W - 2, cy + CARD_H - 2,
                    isSelected ? 0xFFE4C77D : hovered ? BattleMenuScreen.HIGHLIGHT : BattleMenuScreen.PANEL);
            graphics.fill(cx + 2, cy + CARD_H - 5, cx + CARD_W - 2, cy + CARD_H - 2,
                    BattleMenuScreen.SHADOW);

            // 头像：48×48，居中偏上
            ResourceLocation portrait = portraitOf(rl);
            int px = cx + (CARD_W - 48) / 2;
            if (portrait != null) {
                graphics.pose().pushPose();
                graphics.pose().translate(px, cy + 8, 0);
                graphics.pose().scale(48 / PORTRAIT_SIZE, 48 / PORTRAIT_SIZE, 1.0F);
                graphics.blit(portrait, 0, 0, 0, 0, 64, 64, 64, 64);
                graphics.pose().popPose();
            } else {
                graphics.fill(px, cy + 8, px + 48, cy + 56, 0xFFB5A88F);
            }

            // 已拥有 / 已阵亡角标
            if (dead) {
                graphics.fill(cx + 2, cy + 2, cx + CARD_W - 2, cy + CARD_H - 2, 0x66000000);
                graphics.drawCenteredString(font, "已倒下", cx + CARD_W / 2, cy + 30, 0xFFC94F3D);
            } else if (owned) {
                String badge = id.equals(chosen) ? "当前" : "已拥有";
                graphics.drawString(font, badge, cx + 5, cy + 5, 0xFF2E7D32, false);
            }

            graphics.drawCenteredString(font, SpeciesRegistry.displayName(rl), cx + CARD_W / 2,
                    cy + CARD_H - 20, BattleMenuScreen.TEXT);
        }

        // 分页按钮（只有多于一页时才显示）
        if (pageCount() > 1) {
            int navY = confirmY() - 24;
            boolean canPrev = page > 0;
            boolean canNext = page < pageCount() - 1;
            BattleMenuScreen.drawButton(graphics, font, "<", width / 2 - 74, navY, 20, 16,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 74, navY, 20, 16), false, canPrev);
            BattleMenuScreen.drawButton(graphics, font, ">", width / 2 + 54, navY, 20, 16,
                    BattleMenuScreen.inside(mouseX, mouseY, width / 2 + 54, navY, 20, 16), false, canNext);
            graphics.drawCenteredString(font, (page + 1) + " / " + pageCount(), width / 2, navY + 4,
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
        graphics.drawCenteredString(font, hint, width / 2, y + h - 66, BattleMenuScreen.MUTED);

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
        int y = top();
        int h = panelHeight();
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
            int navY = btnY - 24;
            if (BattleMenuScreen.inside(mouseX, mouseY, width / 2 - 74, navY, 20, 16)) {
                if (page > 0) {
                    page--;
                }
                return true;
            }
            if (BattleMenuScreen.inside(mouseX, mouseY, width / 2 + 54, navY, 20, 16)) {
                if (page < pageCount() - 1) {
                    page++;
                }
                return true;
            }
        }

        for (int slot = 0; slot < pageCards(); slot++) {
            int cx = cardX(slot);
            int cy = cardY();
            if (!BattleMenuScreen.inside(mouseX, mouseY, cx, cy, CARD_W, CARD_H)) {
                continue;
            }
            String id = species.get(firstIndex() + slot);
            if (adding) {
                if (team.contains(id)) {
                    // 已拥有：再点一次就是删除这只（服务端会先收回实体再删记录）
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

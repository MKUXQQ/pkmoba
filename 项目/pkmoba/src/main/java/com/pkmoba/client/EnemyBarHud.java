package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * 锁敌时屏幕上方那条"敌方粗血条"（0.39.5 新增）。
 *
 * <p>为什么不用世界空间绘制：世界空间里画 billboard 四边形在实机上被投影画成了一块歪的白板
 * （用户 m03603："敌方血条显示怎么是这么垃圾"）。HUD 层用 {@link GuiGraphics#fill} 画 2D 矩形
 * 完全不会有这个问题，而且粗细、颜色、位置都稳定可控。
 *
 * <p>显示条件：上帝视角里光标锁着一个<b>敌人</b>（{@link Targeting#hoveredTarget}）。
 * 数据直接读实体的 {@code getHealth()/getMaxHealth()} —— 血条上的数永远等于真实血量。
 */
public final class EnemyBarHud {
    private static final int BAR_WIDTH_MAX = 320;
    private static final int BAR_HEIGHT = 12;
    private static final int MARGIN_TOP = 24;

    private static boolean initialized;

    private EnemyBarHud() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        HudRenderCallback.EVENT.register(EnemyBarHud::render);
    }

    private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        LivingEntity target = Targeting.hoveredTarget(mc);
        if (target == null || !target.isAlive()) {
            return;
        }
        float maxHp = Math.max(1.0F, target.getMaxHealth());
        float hp = Math.max(0.0F, Math.min(maxHp, target.getHealth()));
        float ratio = hp / maxHp;

        int screenW = graphics.guiWidth();
        int width = Math.min(BAR_WIDTH_MAX, Math.max(160, screenW - 120));
        int x = (screenW - width) / 2;
        int y = MARGIN_TOP;

        // 外描边（近黑），再内衬一层深灰底，最后按百分比填颜色 —— 三层就够"粗"且干净。
        graphics.fill(x - 2, y - 2, x + width + 2, y + BAR_HEIGHT + 2, 0xC0101014);
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, 0xFF26262E);
        int fillColor;
        if (ratio > 0.5F) {
            fillColor = 0xFF49C15C;
        } else if (ratio > 0.25F) {
            fillColor = 0xFFE0B93C;
        } else {
            fillColor = 0xFFD94A3C;
        }
        int filled = Math.round(width * ratio);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BAR_HEIGHT, fillColor);
            // 顶部一条高光，让条看起来有厚度
            graphics.fill(x, y, x + filled, y + 2, 0x40FFFFFF);
        }

        // 名字 + 血量数字：数字放中间，不用阴影（0.38.x 的"字发糊"就是这么来的）
        String name = target.getName().getString();
        String hpText = (int) Math.ceil(hp) + " / " + (int) Math.ceil(maxHp);
        var font = mc.font;
        int nameWidth = font.width(name);
        graphics.drawString(font, Component.literal(name),
                (screenW - nameWidth) / 2, y - 12, 0xFFFFFFFF, false);
        int hpWidth = font.width(hpText);
        int textY = y + (BAR_HEIGHT - font.lineHeight) / 2 + 1;
        graphics.drawString(font, Component.literal(hpText),
                (screenW - hpWidth) / 2, textY, 0xFFF2F2F2, false);
    }
}

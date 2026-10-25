package com.pkmoba.client;

import com.pkmoba.combat.Skill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/** Client-side presentation metadata and texture rendering for the supported moveset. */
public final class SkillPresentation {
    public static final int COUNT = 4;

    /**
     * 技能格统一样式常量（HUD 绘制与鼠标悬停判定共用这一份）。
     *
     * <p>0.38.10：四个技能格改为「固定格子 + 居中图标 + 统一外框」——
     * 格子宽 {@link #CELL_W}、间距 {@link #CELL_GAP}，图标用原生 32×32 像素绘制
     * （{@link #ICON_SIZE}），不再每格按 30/32 非整数缩放，四格尺寸与位置严格一致。
     */
    public static final int CELL_W = 54;
    public static final int CELL_GAP = 5;
    public static final int CELL_STEP = CELL_W + CELL_GAP;
    /** 格子背景的上/下边界（相对面板顶）。 */
    public static final int CELL_TOP = 12;
    public static final int CELL_BOTTOM = 78;
    /** 图标边长：直接用贴图原生 32px，避免逐格非整数缩放造成的"大小/位置不一致"。 */
    public static final int ICON_SIZE = 32;
    /** 图标在格子内的水平内缩，使 32px 图标在 54px 格子里水平居中。 */
    public static final int ICON_INSET = (CELL_W - ICON_SIZE) / 2;
    /** 图标相对面板顶的纵坐标。 */
    public static final int ICON_Y = 14;

    private static final String[] ICONS = {"flamethrower", "fire_spin", "fly", "fire_blast"};
    private static final String[] BLASTOISE_ICONS = {"blastoise_0", "blastoise_1", "blastoise_2", "blastoise_3"};

    private SkillPresentation() { }

    public static int cost(int index) {
        return com.pkmoba.combat.Skill.byIndex(index).cost;
    }

    public static ResourceLocation icon(int index) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/gui/skills/" + ICONS[index] + ".png");
    }

    public static ResourceLocation icon(PokemonEntity pokemon, int index) {
        String[] icons = Skill.isBlastoise(pokemon) ? BLASTOISE_ICONS : ICONS;
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/gui/skills/" + icons[index] + ".png");
    }

    /** 按物种 id 取图标（宝可梦还没召唤出来时 HUD 也要能画对）。 */
    public static ResourceLocation icon(String speciesId, int index) {
        String[] icons = Skill.isBlastoise(speciesId) ? BLASTOISE_ICONS : ICONS;
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/gui/skills/" + icons[index] + ".png");
    }

    public static boolean supportsControlledPokemon(Minecraft mc) {
        if (mc.level == null || ClientState.getCharizardId() < 0 || !ClientState.isSkillStateReady()) return false;
        Entity entity = mc.level.getEntity(ClientState.getCharizardId());
        if (!(entity instanceof PokemonEntity pokemon) || !pokemon.isAlive()) return false;
        try {
            return "cobblemon:charizard".equals(pokemon.getPokemon().getSpecies().getResourceIdentifier().toString())
                    || Skill.isBlastoise(pokemon);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void drawIcon(GuiGraphics graphics, int index, int x, int y, int size, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(size / 32.0F, size / 32.0F, 1);
        graphics.blit(icon(index), 0, 0, 0, 0, 32, 32, 32, 32);
        graphics.pose().popPose();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static String name(int index) {
        return net.minecraft.network.chat.Component.translatable(Skill.values()[index].translationKey).getString();
    }

    /**
     * 技能图标悬停判定。面板在屏幕上按 {@code scale} 整体缩放绘制，所以这里也要换算，
     * 否则缩放后鼠标位置与图标对不上。
     *
     * <p>面板内部布局统一由 {@link #CELL_STEP}、{@link #ICON_INSET}、{@link #ICON_Y}、
     * {@link #ICON_SIZE} 决定，HUD 绘制和这里共用同一份常量，改布局不会两边对不上。
     */
    public static boolean isSkillIconHovered(Minecraft mc, int index, int panelX, int panelY,
                                             float scale, double cursorX, double cursorY) {
        int iconLocalX = 57 + index * CELL_STEP + ICON_INSET;
        int iconLocalY = ICON_Y;
        double iconX = panelX + iconLocalX * scale;
        double iconY = panelY + iconLocalY * scale;
        double size = ICON_SIZE * scale;
        return cursorX >= iconX && cursorX < iconX + size && cursorY >= iconY && cursorY < iconY + size;
    }
}

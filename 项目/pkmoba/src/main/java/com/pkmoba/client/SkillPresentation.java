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
     * <p>面板内部布局：技能格 {@code cellX = 57 + i*59}，图标 {@code iconX = cellX + 12}、
     * {@code iconY = 16}，尺寸 30。
     */
    public static boolean isSkillIconHovered(Minecraft mc, int index, int panelX, int panelY,
                                             float scale, double cursorX, double cursorY) {
        int iconLocalX = 57 + index * 59 + 12;
        int iconLocalY = 16;
        double iconX = panelX + iconLocalX * scale;
        double iconY = panelY + iconLocalY * scale;
        double size = 30 * scale;
        return cursorX >= iconX && cursorX < iconX + size && cursorY >= iconY && cursorY < iconY + size;
    }
}

package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Quaternionf;

/**
 * Unlabelled pixel health bar for every visible Pokemon.
 *
 * <p>Deliberately minimal: a one pixel dark outline, a dark empty track and a bright fill. No
 * name, level, numbers, secondary bars or gradients. Every layer has its own depth offset and its
 * own render type, so the layers never z-fight with each other or with the model.
 */
public final class PokemonOverheadBar {
    private static final double RANGE_SQR = 64.0 * 64.0;
    /** One bar pixel is this many world units, so the bar keeps a readable size at distance. */
    private static final float WORLD_SCALE = 0.0155F;
    private static final int BAR_WIDTH = 46;
    private static final int BAR_HEIGHT = 4;
    private static final double BAR_LIFT = 0.62;

    private static final int OUTLINE = 0xFF0A0E10;
    private static final int TRACK = 0xFF232A2D;
    private static final int HIGH = 0xFF46D27A;
    private static final int HIGH_LIGHT = 0xFF8DF2B0;
    private static final int MID = 0xFFF2C94C;
    private static final int MID_LIGHT = 0xFFFFE79A;
    private static final int LOW = 0xFFE8503F;
    private static final int LOW_LIGHT = 0xFFFF9A8C;

    private PokemonOverheadBar() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(PokemonOverheadBar::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui
                || context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        Quaternionf cameraRotation = context.camera().rotation();
        PoseStack pose = context.matrixStack();
        MultiBufferSource buffers = context.consumers();
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof PokemonEntity pokemon) || !pokemon.isAlive() || pokemon.isInvisible()) {
                continue;
            }
            double distanceSqr = camera.distanceToSqr(pokemon.position());
            if (distanceSqr > RANGE_SQR) {
                continue;
            }
            double x = Mth.lerp(partial, pokemon.xo, pokemon.getX());
            double y = Mth.lerp(partial, pokemon.yo, pokemon.getY())
                    + Math.max(pokemon.getBbHeight(), visualHeight(pokemon)) + BAR_LIFT;
            double z = Mth.lerp(partial, pokemon.zo, pokemon.getZ());
            float maxHp = Math.max(1.0F, pokemon.getMaxHealth());
            float ratio = Math.max(0.0F, Math.min(1.0F, pokemon.getHealth() / maxHp));
            // Nearly constant on screen: mild growth keeps far bars readable without huge close bars.
            float scale = (float) (0.9 + 0.10 * Math.min(1.0, Math.sqrt(distanceSqr) / 32.0));
            int fill = ratio > 0.5F ? HIGH : ratio > 0.25F ? MID : LOW;
            int shine = ratio > 0.5F ? HIGH_LIGHT : ratio > 0.25F ? MID_LIGHT : LOW_LIGHT;
            int filled = Math.round(BAR_WIDTH * ratio);

            pose.pushPose();
            pose.translate(x - camera.x, y - camera.y, z - camera.z);
            pose.mulPose(cameraRotation);
            // Negative Y so the +Y quad direction points up on screen.
            pose.scale(WORLD_SCALE * scale, -WORLD_SCALE * scale, WORLD_SCALE * scale);

            int left = -BAR_WIDTH / 2;
            // Single render type, single depth plane: paint order decides the stack. Tiny z offsets
            // between layers z-fought at steep camera angles and turned the bar into a black block.
            rect(buffers, pose, OverheadBarRenderType.BAR, left - 1, -1, BAR_WIDTH + 2, BAR_HEIGHT + 2,
                    OUTLINE, 0.0F);
            rect(buffers, pose, OverheadBarRenderType.BAR, left, 0, BAR_WIDTH, BAR_HEIGHT, TRACK, 0.0F);
            if (filled > 0) {
                rect(buffers, pose, OverheadBarRenderType.BAR, left, 0, filled, BAR_HEIGHT, fill, 0.0F);
                rect(buffers, pose, OverheadBarRenderType.BAR, left, 0, filled, 1, shine, 0.0F);
            }
            pose.popPose();
        }
    }

    private static void rect(MultiBufferSource buffers, PoseStack pose, RenderType renderType,
                             int x, int y, int width, int height, int argb, float z) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        var consumer = buffers.getBuffer(renderType);
        var matrix = pose.last();
        consumer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x, y + height, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x + width, y + height, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x + width, y, z).setColor(r, g, b, a);
    }

    /** Head height of the rendered model, in world units above the feet. */
    private static double visualHeight(PokemonEntity pokemon) {
        String species = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        double box = Math.max(pokemon.getBbHeight(), pokemon.getEyeHeight() + 0.35);
        // Pokedex heights are block-scaled by the model pack; these floors keep the bar clear of the
        // head (and any wings or horns that stick out beyond the collision box).
        if (species.contains("tyranitar")) {
            return Math.max(box, 2.95);
        }
        if (species.contains("charizard") || species.contains("venusaur") || species.contains("snorlax")
                || species.contains("dragonite")) {
            return Math.max(box, 2.70);
        }
        if (species.contains("blastoise") || species.contains("golem") || species.contains("blaziken")) {
            return Math.max(box, 2.45);
        }
        if (species.contains("ninetales")) {
            return Math.max(box, 1.75);
        }
        if (species.contains("pikachu") || species.contains("eevee") || species.contains("jigglypuff")) {
            return Math.min(box, 1.15);
        }
        return box;
    }
}

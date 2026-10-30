package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * Flame Mark badge over every victim branded by Blaziken's Blaze Kick.
 *
 * <p>The server stamps an invisible {@link MobEffects#HERO_OF_THE_VILLAGE} effect (no HUD icon, no
 * particles) for the life of the brand; the effect syncs on its own, so the badge follows any living
 * victim - player, Pokemon or mob - with no extra packets.
 *
 * <p>0.80: drawn through the overhead-bar overlay path (camera-facing pose, no depth test), which is
 * the only client path proven to be visible in game.
 */
public final class BlazeMarkIcon {
    private static final double RANGE_SQR = 64.0 * 64.0;

    private BlazeMarkIcon() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(BlazeMarkIcon::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) return;
        if (context.camera() == null || context.matrixStack() == null || context.consumers() == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        float time = (mc.level.getGameTime() + partial) / 20.0F;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!living.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) continue;
            if (camera.distanceToSqr(living.position()) > RANGE_SQR) continue;
            double x = net.minecraft.util.Mth.lerp(partial, living.xo, living.getX());
            double y = net.minecraft.util.Mth.lerp(partial, living.yo, living.getY())
                    + living.getBbHeight() + .80 + Math.sin(time * 3.2) * .05;
            double z = net.minecraft.util.Mth.lerp(partial, living.zo, living.getZ());
            float pulse = .94F + .06F * (float) Math.sin(time * 6.0);
            badge(context, x, y, z, .17F * pulse, .17F * pulse);
        }
    }

    private static void badge(WorldRenderContext context, double x, double y, double z,
                              float halfWidth, float halfHeight) {
        Vec3 camera = context.camera().getPosition();
        var pose = context.matrixStack();
        pose.pushPose();
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.mulPose(context.camera().rotation());
        pose.scale(halfWidth, -halfHeight, 1.0F);
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = context.consumers().getBuffer(OverheadBarRenderType.BADGE_MARK);
        consumer.addVertex(matrix, -1, -1, 0).setUv(0, 1);
        consumer.addVertex(matrix, -1, 1, 0).setUv(0, 0);
        consumer.addVertex(matrix, 1, 1, 0).setUv(1, 0);
        consumer.addVertex(matrix, 1, -1, 0).setUv(1, 1);
        pose.popPose();
    }
}

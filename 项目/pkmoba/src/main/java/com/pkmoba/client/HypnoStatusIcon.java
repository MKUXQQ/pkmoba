package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * "Hypnotised" badge and hypnosis pips over every victim of Gengar's hypnosis.
 *
 * <p>The server stamps an invisible {@link MobEffects#UNLUCK} effect on the victim: amplifier 9
 * means "asleep", 0..2 is the number of hypnosis marks collected. The effect synchronises to every
 * client on its own, so this works for any living entity with no extra packets.
 *
 * <p>0.80: drawn with the same camera-facing pose + no-depth overlay render type as the overhead
 * health bar. The old path wrote into the entity buffer after it had already been flushed, which is
 * why the badge never appeared in game.
 */
public final class HypnoStatusIcon {
    private static final double RANGE_SQR = 64.0 * 64.0;

    private HypnoStatusIcon() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(HypnoStatusIcon::render);
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
            boolean marked = living.hasEffect(MobEffects.UNLUCK);
            int clientMarks = HypnoClientMarks.marksOf(living);
            boolean clientAsleep = HypnoClientMarks.asleepOf(living);
            if (!marked && clientMarks == 0 && !clientAsleep) continue;
            if (camera.distanceToSqr(living.position()) > RANGE_SQR) continue;
            var effect = living.getEffect(MobEffects.UNLUCK);
            int amplifier = effect == null ? 0 : effect.getAmplifier();
            boolean asleep = clientAsleep || (marked && amplifier >= 9);
            int marks = Math.max(clientMarks, marked && amplifier < 9 ? Math.min(3, amplifier + 1) : 0);
            double x = Mth.lerp(partial, living.xo, living.getX());
            double y = Mth.lerp(partial, living.yo, living.getY()) + living.getBbHeight() + .92
                    + Math.sin(time * 2.4) * .05;
            double z = Mth.lerp(partial, living.zo, living.getZ());
            if (asleep) {
                badge(context, OverheadBarRenderType.BADGE_SLEEP, x, y, z, .22F, .18F);
            } else if (marks > 0) {
                double spacing = .34;
                double start = -(marks - 1) * spacing * .5;
                for (int i = 0; i < marks; i++) {
                    badge(context, OverheadBarRenderType.BADGE_PIP,
                            x + start + i * spacing, y + Math.sin(time * 3.0 + i) * .04, z,
                            .10F, .10F);
                }
            }
        }
    }

    private static void badge(WorldRenderContext context, RenderType type,
                              double x, double y, double z, float halfWidth, float halfHeight) {
        Vec3 camera = context.camera().getPosition();
        var pose = context.matrixStack();
        pose.pushPose();
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.mulPose(context.camera().rotation());
        pose.scale(halfWidth, -halfHeight, 1.0F);
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = context.consumers().getBuffer(type);
        consumer.addVertex(matrix, -1, -1, 0).setUv(0, 1);
        consumer.addVertex(matrix, -1, 1, 0).setUv(0, 0);
        consumer.addVertex(matrix, 1, 1, 0).setUv(1, 0);
        consumer.addVertex(matrix, 1, -1, 0).setUv(1, 1);
        pose.popPose();
    }
}

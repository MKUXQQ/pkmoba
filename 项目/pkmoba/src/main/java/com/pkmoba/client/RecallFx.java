package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client recall presentation: the player's red beam reaches the Pokémon,
 * the Pokémon is absorbed, and Cobblemon's original 3D Poké Ball item model
 * travels back to the player's chest. Beam/glow geometry is drawn separately
 * from the native item renderer so changing render types cannot invalidate it.
 */
public final class RecallFx {
    private static final float BEAM_WIDTH = 0.16F;
    private static final float CORE_WIDTH = 0.06F;
    private static final float BALL_SCALE = 0.34F;

    private static final class Flight {
        int entityId;
        int playerId;
        int durationTicks;
        boolean recall;
        long startNanos;
    }

    private static final List<Flight> FLIGHTS = new ArrayList<>();
    private static boolean initialized;

    private RecallFx() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RecallFx::render);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> prune(client));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> FLIGHTS.clear());
    }

    public static void start(int entityId, int playerId, int durationTicks, boolean recall) {
        Flight flight = new Flight();
        flight.entityId = entityId;
        flight.playerId = playerId;
        flight.durationTicks = Math.max(1, durationTicks);
        flight.recall = recall;
        flight.startNanos = System.nanoTime();
        FLIGHTS.add(flight);
    }

    public static boolean hidesEntity(int entityId) {
        long now = System.nanoTime();
        for (Flight flight : FLIGHTS) {
            if (!flight.recall || flight.entityId != entityId) continue;
            if (progress(flight, now) >= .40D) return true;
        }
        return false;
    }

    private static double progress(Flight flight, long now) {
        return (now - flight.startNanos) / (flight.durationTicks * 50_000_000.0D);
    }

    private static void prune(Minecraft client) {
        long now = System.nanoTime();
        FLIGHTS.removeIf(flight -> progress(flight, now) >= 1.0D
                || client.level == null || client.level.getEntity(flight.entityId) == null
                || client.level.getEntity(flight.playerId) == null);
    }

    private static void render(WorldRenderContext context) {
        if (FLIGHTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || context.matrixStack() == null || context.consumers() == null
                || context.camera() == null) {
            return;
        }
        if (mc.player == null) {
            FLIGHTS.clear();
            return;
        }
        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-context.camera().getPosition().x, -context.camera().getPosition().y,
                -context.camera().getPosition().z);
        PoseStack.Pose pose = poseStack.last();
        long now = System.nanoTime();
        Iterator<Flight> iterator = FLIGHTS.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            double t = progress(flight, now);
            if (t >= 1.0D) {
                iterator.remove();
                continue;
            }
            Entity mon = mc.level.getEntity(flight.entityId);
            Entity player = mc.level.getEntity(flight.playerId);
            if (mon == null || player == null) {
                iterator.remove();
                continue;
            }
            try {
                VertexConsumer buffer = context.consumers().getBuffer(RenderType.debugQuads());
                draw(mc, context, buffer, pose, flight, mon, player, t);
            } catch (Throwable ignored) {
                // 纯表现：出错只跳过这一帧
            }
        }
        poseStack.popPose();
    }

    private static void draw(Minecraft mc, WorldRenderContext context, VertexConsumer buffer, PoseStack.Pose pose,
                             Flight flight, Entity mon, Entity player, double t) {
        if (!flight.recall) return;
        Vec3 monCenter = mon.position().add(0, mon.getBbHeight() * 0.48, 0);
        Vec3 chest = player.position().add(0, player.getBbHeight() * 0.58, 0);
        // The recall beam travels outward first. Once the Pokémon is absorbed, the
        // actual Cobblemon Poké Ball item returns to the player on a rising arc.
        double collapse = smoothStep(Math.max(0.0D, Math.min(1.0D, (t - 0.24D) / 0.18D)));
        double returnT = smoothStep(Math.max(0.0D, (t - 0.42D) / 0.58D));
        Vec3 ballStart = monCenter;
        Vec3 ball = ballStart.lerp(chest, returnT);
        double arc = Math.sin(Math.PI * returnT) * Math.min(1.15D, mon.distanceTo(player) * 0.08D);
        ball = ball.add(0, arc, 0);
        float alpha = (float) (0.94D - 0.2D * t);

        if (t < 0.48D) {
            Vec3 beamStart = chest;
            Vec3 beamEnd = chest.lerp(monCenter, smoothStep(t / 0.16D));
            beam(buffer, pose, beamStart, beamEnd, alpha);
            double radius = 0.92D * (1.0D - 0.72D * collapse);
            ring(buffer, pose, monCenter.add(0, -0.08, 0), radius, alpha * 0.9F);
        }
        if (t >= 0.34D) {
            double glow = Math.max(0.0D, 1.0D - Math.abs(t - 0.40D) / 0.16D);
            if (glow > 0.0D) billboard(buffer, pose, ball, 0.3F, (float) glow, 1.0F, 0.26F, 0.18F);
        }
        if (t > 0.88D) {
            double burst = 1.0D - (t - 0.88D) / 0.12D;
            billboard(buffer, pose, chest, 0.18F + (float) (0.32F * (1.0D - burst)),
                    (float) burst, 1.0F, 0.34F, 0.25F);
        }
        // Item rendering can change the active buffer; finish all beam vertices first.
        if (t >= 0.34D) renderOriginalBall(mc, context, ball, t);
    }

    private static void beam(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to, float alpha) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-6D) return;
        Vec3 dir = axis.normalize();
        Vec3 up = Math.abs(dir.y) > 0.9D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 side = dir.cross(up).normalize();
        Vec3 side2 = dir.cross(side).normalize();
        ribbon(buffer, pose, from, to, side, BEAM_WIDTH, alpha * 0.6F, 1.0F, 0.12F, 0.10F);
        ribbon(buffer, pose, from, to, side2, BEAM_WIDTH, alpha * 0.6F, 1.0F, 0.12F, 0.10F);
        ribbon(buffer, pose, from, to, side, CORE_WIDTH, alpha, 1.0F, 0.82F, 0.72F);
        ribbon(buffer, pose, from, to, side2, CORE_WIDTH, alpha, 1.0F, 0.82F, 0.72F);
    }

    private static void renderOriginalBall(Minecraft mc, WorldRenderContext context, Vec3 position, double t) {
        Item ballItem = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball"));
        if (ballItem == Items.AIR) return;
        ItemStack stack = new ItemStack(ballItem);
        PoseStack stackPose = context.matrixStack();
        stackPose.pushPose();
        try {
            // The outer render pose already subtracts the camera position.
            stackPose.translate(position.x, position.y, position.z);
            stackPose.mulPose(Axis.YP.rotationDegrees((float) (t * 1080.0D)));
            stackPose.mulPose(Axis.XP.rotationDegrees(22.0F));
            stackPose.scale(BALL_SCALE, BALL_SCALE, BALL_SCALE);
            var model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getItemRenderer().render(stack, ItemDisplayContext.GROUND, false, stackPose,
                    context.consumers(), 0xF000F0, OverlayTexture.NO_OVERLAY, model);
        } finally {
            stackPose.popPose();
        }
    }

    private static void ring(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center, double radius, float alpha) {
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            Vec3 p0 = ringPoint(center, a0, radius), p1 = ringPoint(center, a1, radius);
            Vec3 p2 = ringPoint(center, a1, radius * .72), p3 = ringPoint(center, a0, radius * .72);
            quad(buffer, pose, p0, p1, p2, p3, 1, .12F, .08F, alpha);
        }
    }

    private static double smoothStep(double x) {
        x = Math.max(0.0D, Math.min(1.0D, x));
        return x * x * (3.0D - 2.0D * x);
    }

    private static Vec3 ringPoint(Vec3 center, double angle, double radius) {
        return new Vec3(center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius);
    }

    /** 沿 axis 方向、宽度 width 的一条四边形光带。 */
    private static void ribbon(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                               Vec3 side, float width, float alpha, float r, float g, float b) {
        Vec3 s = side.scale(width);
        quad(buffer, pose, from.subtract(s), from.add(s), to.add(s), to.subtract(s), r, g, b, alpha);
    }

    /** 面向镜头的方片（三片交叉，任何角度都有亮度）。 */
    private static void billboard(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center, float size,
                                  float alpha, float r, float g, float b) {
        Vec3 x = new Vec3(size, 0, 0);
        Vec3 y = new Vec3(0, size, 0);
        Vec3 z = new Vec3(0, 0, size);
        quad(buffer, pose, center.subtract(x).subtract(y), center.add(x).subtract(y),
                center.add(x).add(y), center.subtract(x).add(y), r, g, b, alpha);
        quad(buffer, pose, center.subtract(x).subtract(z), center.add(x).subtract(z),
                center.add(x).add(z), center.subtract(x).add(z), r, g, b, alpha);
        quad(buffer, pose, center.subtract(z).subtract(y), center.add(z).subtract(y),
                center.add(z).add(y), center.subtract(z).add(y), r, g, b, alpha);
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float r, float g, float bl, float alpha) {
        vertex(buffer, pose, a, r, g, bl, alpha);
        vertex(buffer, pose, b, r, g, bl, alpha);
        vertex(buffer, pose, c, r, g, bl, alpha);
        vertex(buffer, pose, d, r, g, bl, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 p,
                               float r, float g, float b, float alpha) {
        buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, alpha);
    }
}

package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 红光召回 / 放出的光束（0.41.8，客户端自绘）。
 *
 * <p>用户 m05076：「按 C 收回我需要一束红光，要你自己画的，然后收回到宝可梦球中
 * 并且融入玩家身体里面」。所以这里画的是三段：
 * <ol>
 *   <li>宝可梦身上一圈向内收的红环；</li>
 *   <li>从宝可梦中心到玩家胸口的一束红色光带（越靠中间越亮、边缘透明）；</li>
 *   <li>一个沿光带飞行的红色光球，最后在玩家身上爆开一小片红光。</li>
 * </ol>
 * 全部用 {@link RenderType#debugQuads()} 的纯色四边形，不依赖任何贴图/实体。
 */
public final class RecallFx {
    /** 总时长默认 14 tick（与服务端 RecallBeam 一致）。 */
    private static final float BEAM_WIDTH = 0.16F;
    private static final float CORE_WIDTH = 0.06F;

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

    private static void render(WorldRenderContext context) {
        if (FLIGHTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || context.matrixStack() == null || context.consumers() == null
                || context.camera() == null) {
            return;
        }
        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-context.camera().getPosition().x, -context.camera().getPosition().y,
                -context.camera().getPosition().z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.debugQuads());
        long now = System.nanoTime();
        Iterator<Flight> iterator = FLIGHTS.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            double t = (now - flight.startNanos) / 1.0E9 / (flight.durationTicks / 20.0D);
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
                draw(buffer, pose, flight, mon, player, t);
            } catch (Throwable ignored) {
                // 纯表现：出错只跳过这一帧
            }
        }
        poseStack.popPose();
    }

    private static void draw(VertexConsumer buffer, PoseStack.Pose pose, Flight flight,
                             Entity mon, Entity player, double t) {
        Vec3 monCenter = mon.position().add(0, mon.getBbHeight() * 0.45, 0);
        Vec3 chest = player.position().add(0, player.getBbHeight() * 0.55, 0);
        // 召回：宝可梦 → 玩家；放出：玩家 → 宝可梦。
        if (!flight.recall) {
            // m05326：放出宝可梦不画红光，只有收回（C 键/换宠）才有红色激光。
            return;
        }
        Vec3 from = flight.recall ? monCenter : chest;
        Vec3 to = flight.recall ? chest : monCenter;
        // 光带走完 70% 的时间，剩下的时间留给"融入身体"的爆闪。
        double beamT = Math.min(1.0D, t / 0.7D);
        Vec3 head = from.lerp(to, easeIn(beamT));
        float fade = (float) Math.max(0.0D, 1.0D - Math.max(0.0D, (t - 0.7D) / 0.3D));
        float pulse = 0.75F + 0.25F * (float) Math.sin(t * 22.0D);
        float alpha = Math.min(1.0F, fade * pulse);
        if (alpha <= 0.01F) {
            return;
        }

        // 1) 宝可梦身边向内收的红环（只在召回且前 60% 出现）
        if (flight.recall && t < 0.6D) {
            double shrink = 1.0D - t / 0.6D;
            double radius = 0.95D * shrink + 0.05D;
            int segments = 24;
            Vec3 center = mon.position().add(0, mon.getBbHeight() * 0.35, 0);
            for (int i = 0; i < segments; i++) {
                double a0 = Math.PI * 2.0 * i / segments;
                double a1 = Math.PI * 2.0 * (i + 1) / segments;
                Vec3 p0 = ringPoint(center, a0, radius);
                Vec3 p1 = ringPoint(center, a1, radius);
                Vec3 p2 = ringPoint(center, a1, radius * 0.55D);
                Vec3 p3 = ringPoint(center, a0, radius * 0.55D);
                quad(buffer, pose, p0, p1, p2, p3, 1.0F, 0.28F, 0.22F, alpha * 0.85F);
            }
        }

        // 2) 主光束（两条交叉四边形拼出一个有厚度的光带，从任何角度看都不会消失）
        Vec3 axis = head.subtract(from);
        if (axis.lengthSqr() > 1.0E-6D) {
            Vec3 dir = axis.normalize();
            Vec3 up = Math.abs(dir.y) > 0.9D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 side = dir.cross(up).normalize();
            Vec3 side2 = dir.cross(side).normalize();
            ribbon(buffer, pose, from, head, side, BEAM_WIDTH, alpha * 0.55F, 1.0F, 0.30F, 0.24F);
            ribbon(buffer, pose, from, head, side2, BEAM_WIDTH, alpha * 0.55F, 1.0F, 0.30F, 0.24F);
            ribbon(buffer, pose, from, head, side, CORE_WIDTH, alpha * 0.95F, 1.0F, 0.62F, 0.55F);
            ribbon(buffer, pose, from, head, side2, CORE_WIDTH, alpha * 0.95F, 1.0F, 0.62F, 0.55F);

            // 3) 沿光带飞行的光球（用三片互相垂直的方片近似一个亮球）
            float size = 0.20F + 0.10F * (1.0F - alpha);
            billboard(buffer, pose, head, size, alpha, 1.0F, 0.72F, 0.62F);
        }

        // 4) 收进身体那一刻的爆闪
        if (t > 0.62D) {
            double burst = (t - 0.62D) / 0.38D;
            float s = (float) (0.15D + 0.55D * easeIn(burst));
            float a = (float) Math.max(0.0D, 0.9D * (1.0D - burst));
            billboard(buffer, pose, to, s, a, 1.0F, 0.45F, 0.38F);
        }
    }

    private static double easeIn(double x) {
        return x * x;
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

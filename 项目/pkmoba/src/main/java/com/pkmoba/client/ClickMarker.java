package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 点地移动的落点标记（0.39.5 重写）。
 *
 * <p>用户 m03603："你还是直接画吧，不用 3D 了"。
 * 之前版本贴了一张精灵球贴图、做成朝向镜头立起来的牌子，实机又丑又挡视线。
 * 现在改成<b>直接在地面上画</b>：一圈青色圆环 + 中心小圆点，出现时由小放大、之后缓慢淡出，
 * 不贴任何贴图、不 billboard，纯几何 —— 就是 LoL 里右键地面的那种落点提示。
 *
 * <p>实现要点：全部用 {@link RenderType#debugQuads()}（POSITION_COLOR，无贴图）在地面平面上
 * 铺一圈四边形，Y 取落点地形高度 + {@link #GROUND_LIFT}，避免和地面 z-fighting。
 */
public final class ClickMarker {
    /** 标记存活时间（秒）。 */
    private static final double LIFETIME = 0.85;
    /** 圆环出现的初始外半径 / 最大外半径（格）。 */
    private static final double START_RADIUS = 0.28;
    private static final double PEAK_RADIUS = 0.78;
    /** 内半径占外半径的比例，决定环的粗细。 */
    private static final double INNER_RATIO = 0.80;
    /** 分段数：够圆滑又不至于太重。 */
    private static final int SEGMENTS = 40;
    /** 贴地抬升，防止 z-fighting。 */
    private static final double GROUND_LIFT = 0.06;
    /** 标记颜色（青白）。 */
    private static final float COLOR_R = 0.55F;
    private static final float COLOR_G = 1.0F;
    private static final float COLOR_B = 0.80F;

    private static Vec3 point;
    private static long startNanos;
    private static boolean initialized;

    private ClickMarker() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ClickMarker::render);
    }

    /** 记录一次点地指令的落点；重复点击直接覆盖（只保留最新那个点）。 */
    public static void mark(Vec3 pos) {
        point = pos;
        startNanos = System.nanoTime();
    }

    private static void render(WorldRenderContext context) {
        if (point == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        if (context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }
        double age = (System.nanoTime() - startNanos) / 1.0E9;
        if (age < 0 || age > LIFETIME) {
            point = null;
            return;
        }
        try {
            double t = age / LIFETIME;
            // 前 25% 快速放大，之后缓慢收起；透明度整体单调淡出。
            double grow = Math.min(1.0, t / 0.25);
            double radius = START_RADIUS + (PEAK_RADIUS - START_RADIUS) * easeOut(grow);
            float alpha = (float) Math.pow(1.0 - t, 1.35);
            if (alpha <= 0.01F) {
                return;
            }

            Vec3 camera = context.camera().getPosition();
            double groundY = groundY(mc, point) + GROUND_LIFT;

            PoseStack poseStack = context.matrixStack();
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            VertexConsumer buffer = context.consumers().getBuffer(RenderType.debugQuads());

            double inner = radius * INNER_RATIO;
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = (Math.PI * 2.0) * i / SEGMENTS;
                double a1 = (Math.PI * 2.0) * (i + 1) / SEGMENTS;
                quad(buffer, pose,
                        ringPoint(point, groundY, a0, inner), ringPoint(point, groundY, a1, inner),
                        ringPoint(point, groundY, a1, radius), ringPoint(point, groundY, a0, radius),
                        COLOR_R, COLOR_G, COLOR_B, alpha);
            }

            // 中心小圆点：让"落点"更明确
            double dot = Math.max(0.06, radius * 0.16);
            for (int i = 0; i < 12; i++) {
                double a0 = (Math.PI * 2.0) * i / 12;
                double a1 = (Math.PI * 2.0) * (i + 1) / 12;
                triangle(buffer, pose, new Vec3(point.x, groundY, point.z),
                        ringPoint(point, groundY, a0, dot), ringPoint(point, groundY, a1, dot),
                        COLOR_R, COLOR_G, COLOR_B, alpha);
            }

            poseStack.popPose();
        } catch (Throwable ignored) {
            // 纯表现：出错只跳过这一帧
        }
    }

    private static double easeOut(double x) {
        double k = 1.0 - x;
        return 1.0 - k * k;
    }

    private static Vec3 ringPoint(Vec3 center, double y, double angle, double radius) {
        return new Vec3(center.x + Math.cos(angle) * radius, y, center.z + Math.sin(angle) * radius);
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float r, float g, float bl, float alpha) {
        vertex(buffer, pose, a, r, g, bl, alpha);
        vertex(buffer, pose, b, r, g, bl, alpha);
        vertex(buffer, pose, c, r, g, bl, alpha);
        vertex(buffer, pose, d, r, g, bl, alpha);
    }

    private static void triangle(VertexConsumer buffer, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c,
                                 float r, float g, float bl, float alpha) {
        vertex(buffer, pose, a, r, g, bl, alpha);
        vertex(buffer, pose, b, r, g, bl, alpha);
        vertex(buffer, pose, c, r, g, bl, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 p,
                               float r, float g, float b, float alpha) {
        buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, alpha)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** 落点地面高度：从落点上方 4 格向下探 12 格。 */
    private static double groundY(Minecraft mc, Vec3 pos) {
        if (mc.player == null || mc.level == null) {
            return pos.y;
        }
        Vec3 from = new Vec3(pos.x, pos.y + 4.0, pos.z);
        Vec3 to = new Vec3(pos.x, pos.y - 12.0, pos.z);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : pos.y;
    }
}

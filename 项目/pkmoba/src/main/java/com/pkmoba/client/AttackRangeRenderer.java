package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 普攻判定范围圈（0.39.4 重做：一张贴图一个四边形，真正平滑的圆）。
 *
 * <p>历史：0.39.2 用 96 段线段拼圈 → 放大看是折线；0.39.3 改成"多圈同心线 + 填充圈" →
 * 用户看到的是一堆同心圆线（"我需要平滑的，而不是线条，就是你可以直接画一个"）。
 * 这一版直接画<b>一个圆盘贴图</b>（{@code textures/effect/range_ring.png}，512×512，
 * 由 {@code tools/gen_effect_textures.py} 离线生成）：边缘是高斯软边、内部是极淡的冷青填充，
 * 所以不管多近看都是平滑的弧线，且只有一次四边形绘制，性能比几十圈线段更好。
 *
 * <p>显示时机（用户要求"只在攻击的时候显示，走路/站着不显示"）：
 * <ul>
 *   <li>正在引导技能（{@code ClientState.getChannel() > 0}）；</li>
 *   <li>刚下达过普攻（{@link #notifyAttack()}，保留 {@link #ATTACK_LINGER_TICKS} tick）；</li>
 *   <li>光标正指着可攻击的敌人（上帝视角下才成立，{@link Targeting#hoveredTarget}）。</li>
 * </ul>
 */
public final class AttackRangeRenderer {
    private static final ResourceLocation RING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/range_ring.png");
    private static final Logger LOG = LoggerFactory.getLogger("pkmoba-range");

    /** 贴图里圆环实际占的半径比例（贴图从中心到边缘留了点空边）。 */
    private static final double TEXTURE_RADIUS_RATIO = 0.93;
    /** 贴地抬升：太大显浮空，太小 z-fighting。 */
    private static final double GROUND_LIFT = 0.05;
    /** 贴地采样：从圆心地面往上 3 格向下探 6 格（斜坡/台阶也能贴住）。 */
    private static final double PROBE_UP = 3.0;
    private static final double PROBE_DOWN = 6.0;
    /** 圆心与半径的平滑系数（越大越跟手）。 */
    private static final double CENTER_SMOOTH = 20.0;
    private static final double RADIUS_SMOOTH = 14.0;
    /** 圆心跳变超过这个距离视为"换了一只/传送"，直接吸附不插值。 */
    private static final double SNAP_DISTANCE_SQR = 36.0;
    /** 超过这个距离（格）的受控宝可梦不画。 */
    private static final double MAX_RENDER_DISTANCE_SQR = 96.0 * 96.0;
    /** 普攻后白圈保留多久（tick）。 */
    private static final long ATTACK_LINGER_TICKS = 40L;
    /** 圈面基础不透明度（贴图本身已经带 alpha，这里再乘一层，方便整体调淡）。 */
    private static final float DISC_ALPHA = 0.95F;

    private static boolean initialized;
    private static volatile long lastAttackTick = -100_000L;
    private static boolean crashLogged;

    private static Vec3 center = Vec3.ZERO;
    private static double radius = -1.0;
    private static long lastFrameNanos;

    private AttackRangeRenderer() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(AttackRangeRenderer::render);
    }

    /** 由 {@code MouseHandlerMixin} 在发出普攻指令时调用：让白圈在攻击后短时间可见。 */
    public static void notifyAttack() {
        lastAttackTick = currentTick();
    }

    /** 切宝可梦 / 退出世界时清掉平滑状态，避免下一次从旧位置插值过来。 */
    public static void reset() {
        center = Vec3.ZERO;
        radius = -1.0;
        lastFrameNanos = 0L;
    }

    private static long currentTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    private static boolean shouldShow(Minecraft mc) {
        if (ClientState.getChannel() > 0) {
            return true;
        }
        // 0.39.5（用户 m03603）："攻击敌人之后 40 tick 之内都显示"。
        // 不再把"鼠标附近有实体"当成显示条件 —— 那会让圈一直亮着。
        return currentTick() - lastAttackTick <= ATTACK_LINGER_TICKS;
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        if (context.matrixStack() == null || context.consumers() == null) {
            return;
        }
        if (!shouldShow(mc)) {
            return;
        }
        try {
            renderDisc(mc, context);
        } catch (Throwable t) {
            // 渲染线程抛异常会把整个客户端崩掉（0.38.6 就因为 ClipContext 传 null 实体崩过）。
            if (!crashLogged) {
                crashLogged = true;
                LOG.warn("[pkmoba] 普攻范围圈渲染失败，已跳过（后续不再刷日志）", t);
            }
        }
    }

    private static void renderDisc(Minecraft mc, WorldRenderContext context) {
        LivingEntity self = controlledPokemon(mc);
        if (self == null) {
            reset();
            return;
        }
        double targetRadius = Targeting.basicRangeRadius(self);
        if (targetRadius <= 0.0) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        if (self.position().distanceToSqr(camera) > MAX_RENDER_DISTANCE_SQR) {
            return;
        }

        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
        Vec3 targetCenter = self.getPosition(partialTick);
        // 平滑：圆心/半径都用指数插值，宝可梦走位时圈是"跟过去"而不是每帧跳
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.016 : Math.min(0.1, (now - lastFrameNanos) / 1.0E9);
        lastFrameNanos = now;
        if (radius < 0.0 || targetCenter.distanceToSqr(center) > SNAP_DISTANCE_SQR) {
            center = targetCenter;
            radius = targetRadius;
        } else {
            double centerT = 1.0 - Math.exp(-CENTER_SMOOTH * dt);
            double radiusT = 1.0 - Math.exp(-RADIUS_SMOOTH * dt);
            center = new Vec3(
                    center.x + (targetCenter.x - center.x) * centerT,
                    center.y + (targetCenter.y - center.y) * centerT,
                    center.z + (targetCenter.z - center.z) * centerT);
            radius += (targetRadius - radius) * radiusT;
        }

        double groundY = groundHeightBelow(mc, center.x, center.y + PROBE_UP, center.z, PROBE_DOWN)
                + GROUND_LIFT;
        // 贴图里圆环半径占贴图半径的 0.93，所以四边形要按这个比例放大回去
        double quadRadius = radius / TEXTURE_RADIUS_RATIO;

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.entityTranslucentCull(RING_TEXTURE));
        quad(buffer, pose, center.x, groundY, center.z, quadRadius, DISC_ALPHA);
        poseStack.popPose();
    }

    /** 画一个朝上的贴图四边形；正反两面都发一遍，省得纠结绕序被背面剔除。 */
    private static void quad(VertexConsumer buffer, PoseStack.Pose pose,
                            double cx, double y, double cz, double half, float alpha) {
        float x0 = (float) (cx - half);
        float x1 = (float) (cx + half);
        float z0 = (float) (cz - half);
        float z1 = (float) (cz + half);
        float v = (float) y;
        // 正面
        vertex(buffer, pose, x0, v, z0, 0.0F, 0.0F, alpha);
        vertex(buffer, pose, x0, v, z1, 0.0F, 1.0F, alpha);
        vertex(buffer, pose, x1, v, z1, 1.0F, 1.0F, alpha);
        vertex(buffer, pose, x1, v, z0, 1.0F, 0.0F, alpha);
        // 背面（绕序相反）
        vertex(buffer, pose, x1, v, z0, 1.0F, 0.0F, alpha);
        vertex(buffer, pose, x1, v, z1, 1.0F, 1.0F, alpha);
        vertex(buffer, pose, x0, v, z1, 0.0F, 1.0F, alpha);
        vertex(buffer, pose, x0, v, z0, 0.0F, 0.0F, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                              float x, float y, float z, float u, float w, float alpha) {
        buffer.addVertex(pose, x, y, z)
                .setColor(1.0F, 1.0F, 1.0F, alpha)
                .setUv(u, w)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** 受控宝可梦（客户端共享状态里记录的实体 id）。 */
    private static LivingEntity controlledPokemon(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        var found = mc.level.getEntity(ClientState.getCharizardId());
        return found instanceof LivingEntity living ? living : null;
    }

    /** 从 (x, fromY) 向下探到 fromY - depth，取第一块碰撞体的顶面高度；没有地面就用 fromY - depth。 */
    private static double groundHeightBelow(Minecraft mc, double x, double fromY, double z, double depth) {
        if (mc.level == null || mc.player == null) {
            return fromY - depth;
        }
        Vec3 start = new Vec3(x, fromY, z);
        Vec3 end = new Vec3(x, fromY - depth, z);
        BlockHitResult hit = mc.level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : fromY - depth;
    }
}

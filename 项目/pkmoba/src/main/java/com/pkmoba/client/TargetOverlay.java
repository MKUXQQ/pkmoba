package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 世界内目标可读性 UI（0.40）：头顶血条 + 选中标记 + 真实命中伤害数字。
 *
 * <p>为什么放在世界渲染里而不是 HUD：血条要长在目标头顶、伤害数字要长在命中点，
 * 两者都必须跟着世界坐标和相机走；而且 HUD 层没有相机矩阵，做世界→屏幕投影反而要自己算一遍。
 * 这里直接用 {@code WorldRenderEvents.AFTER_TRANSLUCENT} 的位姿矩阵，跟
 * {@link SkillRangeRenderer} 同一套坐标约定（先 {@code translate(-camera)} 再做相机相对坐标）。
 *
 * <p><b>伤害数字的真实性</b>：数字只来自服务端 {@code CombatDamage.apply} 在
 * {@code hurt()} 真正被接受之后发的 {@code pkmoba:hit/...} 包，包内 {@code /d<数值>} 段就是
 * 服务端实际扣掉的血量；客户端<b>不做任何推断</b>，没有数值的旧包直接不显示。
 *
 * <p><b>崩溃防护</b>（0.38.6 曾在渲染线程崩过两次）：整段渲染包在 try/catch 里，异常只记一次日志；
 * 不构造 {@code ClipContext}（本类从头到尾不需要做射线/取样），也不会把 null 实体传出去。
 */
public final class TargetOverlay {
    /** 敌方粗血条：高度（格）与离头顶的抬升。 */
    private static final double ENEMY_BAR_HEIGHT = 0.20;
    private static final double ENEMY_BAR_MIN_WIDTH = 1.5;
    private static final double ENEMY_BAR_LIFT = 0.55;

    private static final Logger LOG = LoggerFactory.getLogger("pkmoba");
    /** 渲染异常只报一次，避免每帧刷屏。 */
    private static boolean CRASH_LOGGED = false;

    /** 血条/数字用的白色贴图（与 WaterEffects 同一个 effect 贴图，避免新增资源）。 */
    private static final ResourceLocation SURFACE =
            ResourceLocation.parse("pkmoba:textures/effect/water_smooth.png");

    // ------------------------------------------------------------------ 血条尺寸

    /** 血条宽度（屏幕像素，按距离换算成世界尺寸 —— 恒定屏幕宽度，不会近处巨大远处消失）。 */
    private static final double BAR_WIDTH_PX = 78.0;
    /** 血条高度（屏幕像素）。 */
    private static final double BAR_HEIGHT_PX = 7.5;
    /** 边框厚度（屏幕像素）。 */
    private static final double BORDER_PX = 1.0;
    /** 血条离目标头顶的距离（格），避开 Cobblemon 自带名字牌。 */
    private static final double HEAD_LIFT = 0.62;
    /** 世界单位 / 像素 / 距离。78px 在 16 格处约 1.6 格宽，正好压住宝可梦身体宽度。 */
    private static final double PIXEL_SCALE = 0.00125;
    /** 距离换算的上下限：太近/太远时不再线性变化，避免尺寸跳变。 */
    private static final double MIN_PIXEL_SCALE = 0.0008;
    private static final double MAX_PIXEL_SCALE = 0.020;
    /** 超过这个距离不画（省渲染开销，也避免远处一根针）。 */
    private static final double BAR_RANGE_SQR = 64.0 * 64.0;

    // ------------------------------------------------------------------ 目标标记

    /** 脚下标记圆环分段（48 段在 0.9 格半径上肉眼无折线）。 */
    private static final int MARK_SEGMENTS = 48;
    /** 标记环的最小半径与随体宽放大的系数。 */
    private static final double MARK_MIN_RADIUS = 0.58;
    private static final double MARK_WIDTH_FACTOR = 0.78;
    /** 标记环抬离地面的高度，避免与地面 z-fighting。 */
    private static final double MARK_LIFT = 0.035;
    /** 标记颜色（暖琥珀色），与普攻白圈区分开。 */
    private static final int MARK_R = 255;
    private static final int MARK_G = 178;
    private static final int MARK_B = 68;

    // ------------------------------------------------------------------ 伤害数字

    /** 伤害数字寿命（tick）：16 tick ≈ 0.8s。 */
    private static final int NUMBER_LIFE_TICKS = 16;
    /** 同一目标这个窗口内的多段命中合并成一个数字（3 tick = 0.15s）。 */
    private static final int NUMBER_MERGE_TICKS = 3;
    /** 同时存在的数字上限，防刷屏。 */
    private static final int MAX_NUMBERS = 24;
    /** 只在此时距内生成数字（服务端广播半径是 96，显示半径收窄一半）。 */
    private static final double NUMBER_RANGE_SQR = 56.0 * 56.0;
    /** 数字上浮速度（格 / tick）。 */
    private static final double NUMBER_RISE = 0.05;
    /** 数字基准字号（像素）；重击/大招再乘系数。 */
    private static final double NUMBER_PX = 12.0;

    /** 当前客户端 tick（伤害数字计时用，客户端自己走，和服务端 tick 无关）。 */
    private static long ticks = 0L;

    /** 正在漂浮的伤害数字（数量受 {@link #MAX_NUMBERS} 限制）。 */
    private static final List<Number> NUMBERS = new ArrayList<>();

    private static boolean initialized = false;

    private TargetOverlay() {
    }

    /** 幂等注册：世界渲染（血条/标记/数字）+ 每 tick 计时 + 断线清理。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TargetOverlay::render);
        ClientTickEvents.END_CLIENT_TICK.register(TargetOverlay::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /** 断线/切世界时清空，避免残留数字。 */
    public static void reset() {
        NUMBERS.clear();
        ticks = 0L;
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 收到一条命中事件（由 {@code PkmobaClient} 在 {@code HitFeedback.handle} 返回 true 后转交）。
     *
     * <p>只有服务端确认过的真实命中才会走到这里；数值取自服务端实际扣血，
     * 旧格式包（没有 {@code /d} 段）不显示数字，绝不"编"一个伤害。
     */
    public static void captureHit(EffectPayload payload) {
        if (payload == null || !payload.isHit()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        float damage = payload.hitDamage();
        if (damage <= 0.0F) {
            // 0.39 的旧包没有伤害数值：宁可不出数字，也不显示假数字。
            return;
        }
        Vec3 point = payload.hitPoint();
        if (!finite(point)) {
            return;
        }
        if (mc.player.position().distanceToSqr(point) > NUMBER_RANGE_SQR) {
            return;
        }
        int targetId = payload.hitTargetId();
        EffectPayload.Power power = payload.hitPower();
        boolean lethal = payload.hitLethal();
        long now = ticks;

        // 同一目标 0.15s 内的多段命中合并：数值相加、档位取高的、寿命续一下。
        // 用服务端给的目标实体 id 精确合并；没有 id 的旧包才退回坐标邻近判断。
        for (Number number : NUMBERS) {
            if (number.expired(now)) {
                continue;
            }
            boolean sameTarget = targetId >= 0
                    ? number.targetId == targetId
                    : number.targetId < 0 && number.origin.distanceToSqr(point) <= 1.44;
            if (!sameTarget || now - number.born > NUMBER_MERGE_TICKS) {
                continue;
            }
            number.amount += damage;
            number.lethal |= lethal;
            if (power.ordinal() > number.power.ordinal()) {
                number.power = power;
            }
            number.born = now;
            return;
        }

        // 超出上限时先丢最旧的。
        if (NUMBERS.size() >= MAX_NUMBERS) {
            NUMBERS.remove(0);
        }
        NUMBERS.add(new Number(targetId, point, damage, power, lethal, now));
    }

    /** 每 tick：计时 + 清理过期数字（切宝可梦/目标消失时血条与标记本来就不存在，无需清理）。 */
    private static void tick(Minecraft mc) {
        ticks++;
        Iterator<Number> iterator = NUMBERS.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expired(ticks)) {
                iterator.remove();
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
        try {
            // 0.39：用户要求"伤害数字、血条就不要了，要简洁"——这里只保留选中目标脚下的标记环，
            // 血条与飘字整体停用（实现保留但不再调用，方便以后单独开关）。
            renderMarkerForHovered(context, partialTick);
        } catch (Throwable t) {
            // 渲染线程抛异常会把客户端整个崩掉：宁可这一帧什么都不画，也不能拖崩游戏。
            if (!CRASH_LOGGED) {
                CRASH_LOGGED = true;
                LOG.warn("[pkmoba] 目标血条/伤害数字渲染失败，已跳过（后续不再刷日志）", t);
            }
        }
    }

    /** 只画脚下目标标记（0.39 起 HUD 保持简洁：不做血条、不做伤害飘字）。 */
    private static void renderMarkerForHovered(WorldRenderContext context, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LivingEntity target = Targeting.hoveredTarget(mc);
        if (target == null || !target.isAlive() || target.level() != mc.level) {
            return;
        }
        Vec3 eye = context.camera().getPosition();
        if (eye.distanceToSqr(target.position()) > BAR_RANGE_SQR) {
            return;
        }
        renderMarker(context, target, eye, partialTick);
        // 0.39.5：世界空间的粗条几何在实机上被画成一块歪斜的白板（用户：太垃圾），
        // 已改到 HUD 层用 2D 绘制（client/EnemyBarHud.java），这里不再画。
    }

    /**
     * 敌方头顶的粗血条。
     *
     * <p>用户要求："敌方粗血条" —— 之前 HUD 上只有自己那只的细血条，打起来看不出对面还剩多少。
     * 这里在锁定的目标头顶画一条<b>粗</b>条：宽按体型、高 {@value #ENEMY_BAR_HEIGHT} 格，
     * 黑底 + 绿/黄/红三段变色 + 一条白色描边，跟随镜头朝向（billboard），远近都看得清。
     */
    private static void renderEnemyBar(WorldRenderContext context, LivingEntity target, Vec3 eye,
                                       float partialTick) {
        if (context.matrixStack() == null || context.consumers() == null) {
            return;
        }
        float maxHp = Math.max(1.0F, target.getMaxHealth());
        float ratio = Math.max(0.0F, Math.min(1.0F, target.getHealth() / maxHp));
        if (ratio <= 0.0F) {
            return;
        }
        double width = Math.max(ENEMY_BAR_MIN_WIDTH, target.getBbWidth() * 1.6);
        double height = ENEMY_BAR_HEIGHT;
        Vec3 center = new Vec3(
                Mth.lerp(partialTick, target.xo, target.getX()),
                Mth.lerp(partialTick, target.yo, target.getY()) + target.getBbHeight() + ENEMY_BAR_LIFT,
                Mth.lerp(partialTick, target.zo, target.getZ()));

        Vec3 toCamera = new Vec3(eye.x - center.x, 0.0, eye.z - center.z);
        if (toCamera.lengthSqr() < 1.0E-6) {
            return;
        }
        toCamera = toCamera.normalize();
        Vec3 right = new Vec3(toCamera.z, 0.0, -toCamera.x).normalize();

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-eye.x, -eye.y, -eye.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.debugQuads());
        Vec3 up = new Vec3(0.0, 1.0, 0.0);

        Vec3 halfW = right.scale(width * 0.5);
        Vec3 halfH = up.scale(height * 0.5);
        // 描边：整条稍微外扩 1px，纯白
        bar(buffer, pose, center, halfW.scale(1.06), halfH.scale(1.9), 1.0F, 1.0F, 1.0F, 0.85F);
        // 黑底
        bar(buffer, pose, center, halfW, halfH, 0.05F, 0.05F, 0.06F, 0.72F);
        // 血量填充：从左往右
        float r = ratio > 0.5F ? 0.32F : (ratio > 0.25F ? 0.94F : 0.86F);
        float g = ratio > 0.5F ? 0.86F : (ratio > 0.25F ? 0.78F : 0.28F);
        float b = ratio > 0.5F ? 0.36F : (ratio > 0.25F ? 0.22F : 0.24F);
        Vec3 fillCenter = center.add(right.scale(width * (ratio - 1.0) * 0.5));
        bar(buffer, pose, fillCenter, right.scale(width * ratio * 0.5), halfH.scale(0.72),
                r, g, b, 0.95F);
        poseStack.popPose();
    }

    /** 画一个朝向镜头的矩形（两个三角形，debugQuads 无贴图）。 */
    private static void bar(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center,
                            Vec3 halfW, Vec3 halfH, float r, float g, float b, float alpha) {
        Vec3 a = center.subtract(halfW).subtract(halfH);
        Vec3 c = center.subtract(halfW).add(halfH);
        Vec3 d = center.add(halfW).add(halfH);
        Vec3 e = center.add(halfW).subtract(halfH);
        barVertex(buffer, pose, a, r, g, b, alpha);
        barVertex(buffer, pose, c, r, g, b, alpha);
        barVertex(buffer, pose, d, r, g, b, alpha);
        barVertex(buffer, pose, e, r, g, b, alpha);
    }

    private static void barVertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 p,
                                  float r, float g, float b, float alpha) {
        buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, alpha)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** 血条 + 脚下标记；目标每帧重新取，目标死亡/切宝可梦/退出上帝视角时自然什么都不画。 */
    private static void renderTarget(WorldRenderContext context, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LivingEntity target = Targeting.hoveredTarget(mc);
        if (target == null || !target.isAlive() || target.level() != mc.level) {
            return;
        }
        Vec3 eye = context.camera().getPosition();
        if (eye.distanceToSqr(target.position()) > BAR_RANGE_SQR) {
            return;
        }
        Vec3 cameraSpace = eye; // 下面所有坐标都相对相机

        // ---- 血条：相机朝向的公告板，屏幕宽度恒定
        double distance = Math.sqrt(eye.distanceToSqr(target.position()));
        double pixelScale = Mth.clamp(distance * PIXEL_SCALE, MIN_PIXEL_SCALE, MAX_PIXEL_SCALE);
        double halfWidth = BAR_WIDTH_PX * pixelScale * 0.5;
        double halfHeight = BAR_HEIGHT_PX * pixelScale * 0.5;
        double border = BORDER_PX * pixelScale;

        Vec3 center = new Vec3(target.getX(), target.getBoundingBox().maxY + HEAD_LIFT, target.getZ());
        Vector3f cameraUp = context.camera().getUpVector();
        Vector3f cameraLeft = context.camera().getLeftVector();
        Vec3 up = new Vec3(cameraUp.x, cameraUp.y, cameraUp.z);
        // 相机左手方向取反 = 屏幕右方向。
        Vec3 right = new Vec3(-cameraLeft.x, -cameraLeft.y, -cameraLeft.z);

        float health = Math.max(0.0F, target.getHealth());
        float maxHealth = Math.max(1.0F, target.getMaxHealth());
        float ratio = Mth.clamp(health / maxHealth, 0.0F, 1.0F);
        int[] fill = lowHealthColor(ratio);

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-cameraSpace.x, -cameraSpace.y, -cameraSpace.z);
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.entityTranslucentEmissive(SURFACE));

        // 深色底（比填充大一圈，天然形成边框）
        Vec3 lowCenter = center.subtract(right.scale(0.0)).subtract(up.scale(0.0));
        Vec3 backgroundHalfUp = up.scale(halfHeight + border);
        Vec3 backgroundHalfRight = right.scale(halfWidth + border);
        quad(buffer, matrix,
                lowCenter.subtract(backgroundHalfRight).subtract(backgroundHalfUp),
                lowCenter.add(backgroundHalfRight).subtract(backgroundHalfUp),
                lowCenter.add(backgroundHalfRight).add(backgroundHalfUp),
                lowCenter.subtract(backgroundHalfRight).add(backgroundHalfUp),
                14, 16, 22, 200);

        // 血量填充：从左往右按比例
        double filledHalf = halfWidth * ratio;
        Vec3 fillCenter = center.subtract(right.scale(halfWidth - filledHalf));
        Vec3 fillHalfRight = right.scale(filledHalf);
        Vec3 fillHalfUp = up.scale(halfHeight);
        quad(buffer, matrix,
                fillCenter.subtract(fillHalfRight).subtract(fillHalfUp),
                fillCenter.add(fillHalfRight).subtract(fillHalfUp),
                fillCenter.add(fillHalfRight).add(fillHalfUp),
                fillCenter.subtract(fillHalfRight).add(fillHalfUp),
                fill[0], fill[1], fill[2], 225);

        // 顶部高光：一条细亮的线，让血条有一点体积感（不是每帧闪，静止的）
        Vec3 highlightCenter = center.add(up.scale(halfHeight * 0.55));
        Vec3 highlightHalfUp = up.scale(halfHeight * 0.28);
        Vec3 highlightHalfRight = right.scale(halfWidth * 0.92);
        quad(buffer, matrix,
                highlightCenter.subtract(highlightHalfRight).subtract(highlightHalfUp),
                highlightCenter.add(highlightHalfRight).subtract(highlightHalfUp),
                highlightCenter.add(highlightHalfRight).add(highlightHalfUp),
                highlightCenter.subtract(highlightHalfRight).add(highlightHalfUp),
                255, 255, 255, 34);

        // 低血时在血条左侧打一个红色小段，提示"危险"（静止，不闪）
        if (ratio <= 0.25F) {
            Vec3 dangerCenter = center.subtract(right.scale(halfWidth * 0.94));
            Vec3 dangerHalfRight = right.scale(halfWidth * 0.04);
            Vec3 dangerHalfUp = up.scale(halfHeight * 1.15);
            quad(buffer, matrix,
                    dangerCenter.subtract(dangerHalfRight).subtract(dangerHalfUp),
                    dangerCenter.add(dangerHalfRight).subtract(dangerHalfUp),
                    dangerCenter.add(dangerHalfRight).add(dangerHalfUp),
                    dangerCenter.subtract(dangerHalfRight).add(dangerHalfUp),
                    255, 90, 90, 220);
        }
        poseStack.popPose();

        // ---- 脚下标记：一圈暖色细环（debugLineStrip，与白圈同类手段，不新增资源）
        renderMarker(context, target, cameraSpace, partialTick);
    }

    /** 选中目标脚下的轻量标记：一圈细环 + 四个外侧刻度，暖琥珀色，与普攻白圈区分。 */
    private static void renderMarker(WorldRenderContext context, LivingEntity target, Vec3 camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        double radius = Math.max(MARK_MIN_RADIUS, target.getBbWidth() * MARK_WIDTH_FACTOR);
        double y = target.getBoundingBox().minY + MARK_LIFT;
        // 用实体位置插值，避免高速移动时标记拖影。
        double cx = Mth.lerp(partialTick, target.xo, target.getX());
        double cz = Mth.lerp(partialTick, target.zo, target.getZ());
        Vec3 center = new Vec3(cx, y, cz);

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer lines = context.consumers().getBuffer(RenderType.debugLineStrip(2.5));

        Vec3 previous = null;
        Vec3 first = null;
        for (int i = 0; i <= MARK_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * (i % MARK_SEGMENTS) / MARK_SEGMENTS;
            Vec3 point = center.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            if (previous == null) {
                first = point;
            } else {
                markerLine(lines, pose, previous, point, 0.85F);
            }
            previous = point;
        }
        if (previous != null && first != null) {
            markerLine(lines, pose, previous, first, 0.85F);
        }

        // 四个外侧刻度，方向感更强（只加 4 条短线，渲染开销可忽略）
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 0.5 * i + Math.PI * 0.25;
            Vec3 inner = center.add(Math.cos(angle) * radius * 1.18, 0.0, Math.sin(angle) * radius * 1.18);
            Vec3 outer = center.add(Math.cos(angle) * radius * 1.42, 0.0, Math.sin(angle) * radius * 1.42);
            markerLine(lines, pose, inner, outer, 0.75F);
        }
        poseStack.popPose();
    }

    /** 伤害数字：命中点上方上浮 + 淡出；暴击/大招字号更大、颜色更醒目。 */
    private static void renderNumbers(WorldRenderContext context, float partialTick) {
        if (NUMBERS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        Quaternionf cameraRotation = context.camera().rotation();
        PoseStack poseStack = context.matrixStack();
        for (Number number : NUMBERS) {
            float age = (float) (ticks - number.born) + partialTick;
            if (age < 0.0F || age > NUMBER_LIFE_TICKS + 1.0F) {
                continue;
            }
            if (camera.distanceToSqr(number.origin) > NUMBER_RANGE_SQR + 64.0) {
                continue;
            }
            float progress = Mth.clamp(age / NUMBER_LIFE_TICKS, 0.0F, 1.0F);
            int alpha = (int) (255.0F * (progress < 0.72F ? 1.0F : 1.0F - (progress - 0.72F) / 0.28F));
            alpha = Mth.clamp(alpha, 6, 255);
            String text = formatAmount(number.amount);
            int color = (alpha << 24) | number.color();

            double distance = Math.sqrt(camera.distanceToSqr(number.origin));
            double pixelScale = Mth.clamp(distance * PIXEL_SCALE, MIN_PIXEL_SCALE, MAX_PIXEL_SCALE);
            double scale = pixelScale * NUMBER_PX * number.sizeFactor() / 9.0;
            Vec3 position = number.origin
                    .add(number.jitterX, 0.35 + age * NUMBER_RISE, number.jitterZ)
                    .subtract(camera);

            poseStack.pushPose();
            poseStack.translate(position.x, position.y, position.z);
            poseStack.mulPose(cameraRotation);
            poseStack.scale((float) scale, (float) -scale, (float) scale);
            float width = mc.font.width(text);
            mc.font.drawInBatch(text, -width * 0.5F, -mc.font.lineHeight * 0.5F, color, true,
                    poseStack.last().pose(), context.consumers(), Font.DisplayMode.SEE_THROUGH,
                    0, LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 血条填充色：健康绿 → 半血黄 → 残血红。 */
    private static int[] lowHealthColor(float ratio) {
        if (ratio > 0.55F) {
            return new int[] {94, 222, 128};
        }
        if (ratio > 0.25F) {
            return new int[] {246, 204, 82};
        }
        return new int[] {238, 74, 74};
    }

    /** 伤害数字文本：整数显示，小数伤害保留一位（合并后会变成小数）。 */
    private static String formatAmount(float amount) {
        float rounded = Math.round(amount * 10.0F) / 10.0F;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05F) {
            return Integer.toString(Math.round(rounded));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", rounded);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    /** 实体格式的白色贴图四边形（与 WaterEffects 同一套 vertex 写法）。 */
    private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             int r, int g, int blue, int alpha) {
        vertex(buffer, matrix, a, 0, 1, r, g, blue, alpha);
        vertex(buffer, matrix, b, 1, 1, r, g, blue, alpha);
        vertex(buffer, matrix, c, 1, 0, r, g, blue, alpha);
        vertex(buffer, matrix, d, 0, 0, r, g, blue, alpha);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 point, float u, float v,
                               int r, int g, int blue, int alpha) {
        buffer.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .setColor(r, g, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** 线段（POSITION_COLOR_NORMAL 格式，对应 {@code RenderType.debugLineStrip}）。 */
    private static void markerLine(VertexConsumer lines, PoseStack.Pose pose, Vec3 from, Vec3 to, float alpha) {
        lines.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(MARK_R, MARK_G, MARK_B, (int) (alpha * 255.0F))
                .setNormal(0.0F, 1.0F, 0.0F);
        lines.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(MARK_R, MARK_G, MARK_B, (int) (alpha * 255.0F))
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** 一条漂浮中的伤害数字。 */
    private static final class Number {
        /** 服务端给的目标实体 id；-1 = 旧包没有 id。 */
        private final int targetId;
        /** 命中点（数字起点）。 */
        private final Vec3 origin;
        /** 横向抖动，避免同一点连续命中时数字完全重叠。 */
        private final double jitterX;
        private final double jitterZ;
        /** 累计伤害（0.15s 内的多段命中会加到这里）。 */
        private float amount;
        private EffectPayload.Power power;
        private boolean lethal;
        /** 生成它的客户端 tick（合并时会续期）。 */
        private long born;

        private Number(int targetId, Vec3 origin, float amount, EffectPayload.Power power, boolean lethal, long born) {
            this.targetId = targetId;
            this.origin = origin;
            this.amount = amount;
            this.power = power;
            this.lethal = lethal;
            this.born = born;
            long seed = born * 31L + targetId * 17L;
            this.jitterX = ((seed & 7L) - 3.5) * 0.035;
            this.jitterZ = (((seed >> 3) & 7L) - 3.5) * 0.035;
        }

        private boolean expired(long now) {
            return now - born > NUMBER_LIFE_TICKS + 8L;
        }

        /** 字号倍率：轻击 1.0 / 重击 1.3 / 大招 1.6，斩杀再多 0.15。 */
        private double sizeFactor() {
            double factor = switch (power) {
                case LIGHT -> 1.0;
                case HEAVY -> 1.3;
                case ULTIMATE -> 1.6;
            };
            return lethal ? factor + 0.15 : factor;
        }

        /** 颜色：越重越暖，斩杀偏红（只影响读数，不改任何数值）。 */
        private int color() {
            if (lethal) {
                return 0xFF5566;
            }
            return switch (power) {
                case LIGHT -> 0xFFFFFF;
                case HEAVY -> 0xFFD24A;
                case ULTIMATE -> 0xFF9A3C;
            };
        }
    }
}

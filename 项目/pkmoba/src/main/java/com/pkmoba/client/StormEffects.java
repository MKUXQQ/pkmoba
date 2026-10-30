package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 水箭龟 R「水炮风暴」的客户端表现层（0.43.0）。
 *
 * <p>由 {@link com.pkmoba.network.StormPayload} 驱动：收到包就开始在以施法者为圆心的
 * {@code radius} 范围内播一场 3.8 秒的水龙卷，{@code durationTicks = 0} 立刻收掉。
 * 整场效果全部自绘（{@code RenderType.debugQuads()}），不依赖任何贴图资源，
 * 所以不会出现"资源没打进去就什么都不显示"的情况。
 *
 * <p>五个阶段（与服务端 {@code BlastoiseCombat.Storm} 的时间轴一一对应）：
 * <ol>
 *   <li><b>蓄力 0–11t</b>：地面范围圈由内向外点亮、四道水纹外扩、脚下水面旋转；</li>
 *   <li><b>起浪 12–17t</b>：6 道水柱从圈上 6 个等分点螺旋升起；</li>
 *   <li><b>风暴 18–61t</b>：6 道水柱绕圈旋转（每 tick 3°），3 条螺旋水带同向缠绕，
 *       每 8 tick 一圈水刃从中心向外扩散，水花/白沫持续喷；</li>
 *   <li><b>收束 62–69t</b>：所有水环向内收拢，白浪爆发 + 冲击环；</li>
 *   <li><b>恢复 70–75t</b>：水面雾散。</li>
 * </ol>
 */
public final class StormEffects {
    private static final int CHARGE_TICKS = 12;
    private static final int RISE_TICKS = 6;
    private static final int STORM_TICKS = 44;
    private static final int COLLAPSE_TICKS = 8;
    private static final int RECOVER_TICKS = 6;
    private static final int STORM_START = CHARGE_TICKS + RISE_TICKS;
    private static final int COLLAPSE_START = STORM_START + STORM_TICKS;
    private static final int RECOVER_START = COLLAPSE_START + COLLAPSE_TICKS;
    private static final int TOTAL_TICKS = RECOVER_START + RECOVER_TICKS;

    /** 水柱数量。 */
    private static final int PILLARS = 6;
    /** 每条水柱的竖直分段数（越高越圆滑，也越费）。 */
    private static final int PILLAR_SEGMENTS = 8;
    /** 每个阶段的圈采样数。 */
    private static final int RING_SEGMENTS = 96;
    /** 水刃（向外扩散的环）数量＝风暴期间每 8 tick 一片。 */
    private static final int BLADES = STORM_TICKS / 8;

    /** 服务端每 8 tick 结算一次，客户端按同一节奏画扩散水刃。 */
    private static final int PULSE_INTERVAL = 8;

    private static final class Storm {
        final int entityId;
        final long startTick;
        final int duration;
        final float radius;
        final Random random;

        Storm(int entityId, long startTick, int duration, float radius, long seed) {
            this.entityId = entityId;
            this.startTick = startTick;
            this.duration = duration;
            this.radius = radius;
            this.random = new Random(seed);
        }
    }

    private static final Map<Integer, Storm> STORMS = new ConcurrentHashMap<>();

    private StormEffects() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.StormPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (payload.durationTicks() <= 0) {
                        STORMS.remove(payload.entityId());
                        return;
                    }
                    long now = context.client().level == null ? 0L
                            : context.client().level.getGameTime();
                    STORMS.put(payload.entityId(), new Storm(payload.entityId(), now,
                            payload.durationTicks(), payload.radius(), payload.seed()));
                }));
        ClientTickEvents.END_CLIENT_TICK.register(StormEffects::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> STORMS.clear());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(StormEffects::render);
    }

    // ---- 每 tick：清理 + 粒子 ----

    private static void tick(Minecraft mc) {
        if (mc.level == null) {
            STORMS.clear();
            return;
        }
        long now = mc.level.getGameTime();
        for (Iterator<Map.Entry<Integer, Storm>> it = STORMS.entrySet().iterator(); it.hasNext(); ) {
            Storm storm = it.next().getValue();
            Entity caster = mc.level.getEntity(storm.entityId);
            long age = now - storm.startTick;
            if (caster == null || !caster.isAlive() || age > storm.duration + 3L) {
                it.remove();
                continue;
            }
            if (age % 4L == 0L) spawnParticles(mc.level, caster, storm, (float) age);
        }
    }

    private static void spawnParticles(Level level, Entity caster, Storm storm, float t) {
        Vec3 c = caster.position();
        float radius = storm.radius;
        Random rnd = storm.random;
        if (t < CHARGE_TICKS) {
            // 蓄力：脚下水面旋转 + 四道水纹外扩
            float progress = t / (float) CHARGE_TICKS;
            for (int i = 0; i < 3; i++) {
                double a = t * 0.35 + i * Math.PI / 3.0;
                double d = 0.5 + 1.9 * progress;
                level.addParticle(ParticleTypes.SPLASH,
                        c.x + Math.cos(a) * d, c.y + 0.1, c.z + Math.sin(a) * d,
                        -Math.sin(a) * 0.05, 0.05, Math.cos(a) * 0.05);
            }
            for (int i = 0; i < 2; i++) {
                double a = t * 0.5 + i * Math.PI / 2.0;
                double d = 1.2 + radius * progress;
                level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP,
                        c.x + Math.cos(a) * d, c.y + 0.15, c.z + Math.sin(a) * d, 0, 0.02, 0);
            }
            return;
        }
        if (t > COLLAPSE_START + COLLAPSE_TICKS) {
            // 恢复：雾气散开
            for (int i = 0; i < 4; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double d = radius * (0.3 + 0.7 * rnd.nextDouble());
                level.addParticle(ParticleTypes.CLOUD,
                        c.x + Math.cos(a) * d, c.y + 0.1 + rnd.nextDouble() * 0.8,
                        c.z + Math.sin(a) * d, 0, 0.02, 0);
            }
            return;
        }
        if (t >= COLLAPSE_START) {
            // 收束：向心汇聚 + 白浪
            float p = (t - COLLAPSE_START) / (float) COLLAPSE_TICKS;
            for (int i = 0; i < 8; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double d = radius * (1.0 - p * 0.85) * (0.6 + 0.5 * rnd.nextDouble());
                level.addParticle(ParticleTypes.SPLASH,
                        c.x + Math.cos(a) * d, c.y + 0.2 + rnd.nextDouble() * 1.2,
                        c.z + Math.sin(a) * d,
                        -Math.cos(a) * 0.18, 0.12, -Math.sin(a) * 0.18);
            }
            for (int i = 0; i < 5; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double d = radius * (0.9 - p * 0.2);
                level.addParticle(ParticleTypes.CLOUD,
                        c.x + Math.cos(a) * d, c.y + 0.3, c.z + Math.sin(a) * d, 0, 0.08, 0);
            }
            return;
        }
        // 起浪 + 风暴：水柱根部水花、圈沿白沫、向外飞的水珠
        for (int i = 0; i < PILLARS; i++) {
            double a = pillarAngle(storm, t, i);
            double d = radius * 0.78;
            level.addParticle(ParticleTypes.SPLASH,
                    c.x + Math.cos(a) * d, c.y + 0.15, c.z + Math.sin(a) * d,
                    0, 0.22, 0);
        }
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            double d = radius * (0.55 + 0.45 * rnd.nextDouble());
            level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP,
                    c.x + Math.cos(a) * d, c.y + 0.2, c.z + Math.sin(a) * d, 0, 0.12, 0);
        }
        int pulse = (int) ((t - STORM_START) / PULSE_INTERVAL);
        if (pulse >= 0 && pulse < BLADES) {
            float p = ((t - STORM_START) - pulse * PULSE_INTERVAL) / (float) PULSE_INTERVAL;
            double d = radius * (0.25 + 0.75 * p);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2.0 * i / 8.0;
                level.addParticle(ParticleTypes.SPLASH,
                        c.x + Math.cos(a) * d, c.y + 0.12, c.z + Math.sin(a) * d,
                        Math.cos(a) * 0.16, 0.05, Math.sin(a) * 0.16);
            }
        }
        if (t >= CHARGE_TICKS && t < STORM_START) {
            // 起浪：水柱网格承担主体体积，少量水花强调破水瞬间。
            for (int i = 0; i < 8; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double d = radius * 0.78 * (0.6 + 0.4 * rnd.nextDouble());
                level.addParticle(ParticleTypes.SPLASH,
                        c.x + Math.cos(a) * d, c.y + rnd.nextDouble() * 1.6, c.z + Math.sin(a) * d,
                        0, 0.18, 0);
            }
        }
    }

    // ---- 渲染 ----

    private static void render(WorldRenderContext context) {
        if (STORMS.isEmpty() || context.matrixStack() == null || context.consumers() == null
                || context.camera() == null || context.tickCounter() == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        long now = mc.level.getGameTime();
        Vec3 cam = context.camera().getPosition();
        VertexConsumer vc = context.consumers().getBuffer(RenderType.debugQuads());
        Matrix4f mat = context.matrixStack().last().pose();
        for (Storm storm : STORMS.values()) {
            Entity caster = mc.level.getEntity(storm.entityId);
            if (!(caster instanceof PokemonEntity pokemon) || !pokemon.isAlive()) {
                continue;
            }
            float t = (now - storm.startTick) + partial;
            if (t < 0 || t > storm.duration + 2) {
                continue;
            }
            Vec3 base = pokemon.getPosition(partial).subtract(cam);
            try {
                drawStorm(vc, mat, base, storm, t);
            } catch (Throwable ignored) {
                // 渲染线程抛异常会崩客户端：这一帧画不出来就算了。
            }
        }
    }

    private static void drawStorm(VertexConsumer vc, Matrix4f mat, Vec3 base, Storm storm, float t) {
        float radius = storm.radius;
        if (t < CHARGE_TICKS) {
            float p = t / (float) CHARGE_TICKS;
            // 由内向外点亮的范围圈：外圈固定、内圈随进度收回 → 蓄力感
            drawRing(vc, mat, base, radius * (0.35 + 0.65 * p), 0.075f, 0.55f, 0.92f, 1.0f,
                    0.35f + 0.45f * p);
            drawRing(vc, mat, base, radius, 0.12f, 0.85f, 0.98f, 1.0f, 0.25f + 0.55f * p);
            return;
        }
        if (t > COLLAPSE_START + COLLAPSE_TICKS) {
            float p = (t - COLLAPSE_START - COLLAPSE_TICKS) / (float) RECOVER_TICKS;
            drawRing(vc, mat, base, radius * (1.0 - 0.2 * p), 0.09f, 0.6f, 0.85f, 1.0f, 0.35f * (1 - p));
            return;
        }
        if (t >= COLLAPSE_START) {
            float p = (t - COLLAPSE_START) / (float) COLLAPSE_TICKS;
            // 白浪爆发 + 向外冲击环
            drawRing(vc, mat, base, radius * (0.25 + 0.95 * p), 0.3f, 1.0f, 1.0f, 1.0f,
                    0.85f * (1 - p * 0.7f));
            drawRing(vc, mat, base, radius * (0.6 + 0.6 * p), 0.16f, 0.75f, 0.95f, 1.0f,
                    0.7f * (1 - p));
            return;
        }

        boolean rising = t < STORM_START;
        float rise = rising ? (t - CHARGE_TICKS) / (float) RISE_TICKS : 1.0f;
        float stormAge = Math.max(0.0f, t - STORM_START);

        // 地面水盘：一整片半透明水面（不是线条），风暴期间持续起伏
        drawWaterDisc(vc, mat, base, radius * (0.55 + 0.45 * rise), t, 0.30f * rise);
        float breathe = 0.75f + 0.25f * (float) Math.sin(t * 0.25);
        drawRing(vc, mat, base, radius, 0.14f, 0.62f, 0.93f, 1.0f, 0.62f * breathe);
        drawRing(vc, mat, base, radius * 0.985, 0.10f, 0.95f, 1.0f, 1.0f, 0.5f * breathe);

        // 向外扩散的水刃（每 8 tick 一片，与服务端伤害节奏同步）
        int pulse = (int) (stormAge / PULSE_INTERVAL);
        if (pulse >= 0 && pulse < BLADES) {
            float p = (stormAge - pulse * PULSE_INTERVAL) / (float) PULSE_INTERVAL;
            drawRing(vc, mat, base, radius * (0.2 + 0.85 * p), 0.10f, 0.90f, 1.0f, 1.0f,
                    0.65f * (1 - p));
        }

        // m05326：改成"水从地面往上喷"—— 整片区域里随机分布的喷泉，各自周期性地涌起/落下
        for (int i = 0; i < JETS; i++) {
            double a = jetAngle(i);
            double d = radius * jetRadius(i);
            Vec3 foot = base.add(Math.cos(a) * d, 0.03, Math.sin(a) * d);
            float cycle = (t * JET_SPEED + jetPhase(i)) % 1.0f;
            float grow = cycle < 0.25f ? cycle / 0.25f : (cycle > 0.72f ? (1.0f - cycle) / 0.28f : 1.0f);
            float height = (1.5f + 2.3f * jetScale(i)) * grow * rise;
            if (height <= 0.06f) continue;
            drawJet(vc, mat, foot, height, a, t, i);
            drawSplash(vc, mat, foot, 0.30f + 0.32f * jetScale(i), grow * rise, i);
        }
    }

    /** 一颗喷射水柱：两片正交的竖直四边形（从地面往上喷），底部亮、顶部淡。 */
    private static void drawJet(VertexConsumer vc, Matrix4f mat, Vec3 foot, float height,
                                double angle, float t, int index) {
        double wobble = Math.sin(t * 0.55 + index * 1.7) * 0.10;
        float halfW = 0.16f + 0.10f * jetScale(index);
        for (int plane = 0; plane < 2; plane++) {
            double dirX = plane == 0 ? Math.cos(angle) : Math.cos(angle + Math.PI / 2);
            double dirZ = plane == 0 ? Math.sin(angle) : Math.sin(angle + Math.PI / 2);
            double px = -dirZ * halfW;
            double pz = dirX * halfW;
            float r = 0.70f, g = 0.92f, b = 1.0f;
            int segments = JET_SEGMENTS;
            for (int s = 0; s < segments; s++) {
                float f0 = s / (float) segments;
                float f1 = (s + 1) / (float) segments;
                double off0 = wobble * f0 * f0;
                double off1 = wobble * f1 * f1;
                double w0 = 1.0 - 0.55 * f0;
                double w1 = 1.0 - 0.55 * f1;
                quad4(vc, mat,
                        foot.x + px * w0 + off0, foot.y + height * f0, foot.z + pz * w0 + off0,
                        foot.x - px * w0 + off0, foot.y + height * f0, foot.z - pz * w0 + off0,
                        foot.x - px * w1 + off1, foot.y + height * f1, foot.z - pz * w1 + off1,
                        foot.x + px * w1 + off1, foot.y + height * f1, foot.z + pz * w1 + off1,
                        r, g, b, 0.62f * (1.0f - f0 * 0.85f));
            }
        }
    }

    /** 水柱落回地面的水花：一圈小四边形向外翻。 */
    private static void drawSplash(VertexConsumer vc, Matrix4f mat, Vec3 foot, float radius,
                                   float strength, int index) {
        float alpha = 0.42f * strength;
        if (alpha <= 0.01f) return;
        int segments = 10;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            quad4(vc, mat,
                    foot.x + Math.cos(a0) * radius * 0.45, foot.y, foot.z + Math.sin(a0) * radius * 0.45,
                    foot.x + Math.cos(a1) * radius * 0.45, foot.y, foot.z + Math.sin(a1) * radius * 0.45,
                    foot.x + Math.cos(a1) * radius, foot.y + 0.16, foot.z + Math.sin(a1) * radius,
                    foot.x + Math.cos(a0) * radius, foot.y + 0.16, foot.z + Math.sin(a0) * radius,
                    0.92f, 1.0f, 1.0f, alpha);
        }
    }

    /** 地面水盘：从中心到边缘的一整片水面（同心扇形四边形，肉眼是连续的一片）。 */
    private static void drawWaterDisc(VertexConsumer vc, Matrix4f mat, Vec3 base, double radius,
                                      float t, float alpha) {
        if (alpha <= 0.01f || radius <= 0.05) return;
        int rings = 6;
        int segments = 28;
        for (int ring = 0; ring < rings; ring++) {
            double r0 = radius * ring / rings;
            double r1 = radius * (ring + 1) / rings;
            float ripple = 1.0f + 0.06f * (float) Math.sin(t * 0.4 + ring * 1.3);
            float ringAlpha = alpha * (1.0f - 0.55f * ring / (float) rings);
            for (int i = 0; i < segments; i++) {
                double a0 = Math.PI * 2.0 * i / segments;
                double a1 = Math.PI * 2.0 * (i + 1) / segments;
                quad4(vc, mat,
                        base.x + Math.cos(a0) * r0 * ripple, base.y + 0.02, base.z + Math.sin(a0) * r0 * ripple,
                        base.x + Math.cos(a1) * r0 * ripple, base.y + 0.02, base.z + Math.sin(a1) * r0 * ripple,
                        base.x + Math.cos(a1) * r1 * ripple, base.y + 0.02, base.z + Math.sin(a1) * r1 * ripple,
                        base.x + Math.cos(a0) * r1 * ripple, base.y + 0.02, base.z + Math.sin(a0) * r1 * ripple,
                        0.42f, 0.80f, 1.0f, ringAlpha);
            }
        }
    }

    private static final int JETS = 26;
    private static final int JET_SEGMENTS = 7;
    private static final float JET_SPEED = 0.055f;

    /** 每根水柱的位置/相位/规模：用 index 做确定性伪随机，避免每帧抖动。 */
    private static double jetAngle(int index) {
        double h = Math.sin(index * 12.9898) * 43758.5453;
        return (h - Math.floor(h)) * Math.PI * 2.0;
    }

    private static double jetRadius(int index) {
        double h = Math.sin(index * 78.233) * 12345.6789;
        double v = h - Math.floor(h);
        return 0.10 + 0.88 * Math.sqrt(v);
    }

    private static float jetPhase(int index) {
        double h = Math.sin(index * 39.425) * 9876.5432;
        return (float) (h - Math.floor(h));
    }

    private static float jetScale(int index) {
        double h = Math.sin(index * 5.398) * 3141.59;
        return (float) (h - Math.floor(h));
    }

    private static double pillarAngle(Storm storm, float t, int index) {
        return t * 0.11 + index * (Math.PI * 2.0 / PILLARS);
    }

    /** 单根水柱：竖直 ribbon + 左右摆动，两层不同透明度。 */
    private static void drawPillar(VertexConsumer vc, Matrix4f mat, Vec3 foot, float height,
                                   double angle, float t, int index) {
        double wavePhase = t * 0.35 + index;
        for (int layer = 0; layer < 2; layer++) {
            float width = layer == 0 ? 0.30f : 0.14f;
            float alpha = layer == 0 ? 0.42f : 0.75f;
            float r = layer == 0 ? 0.45f : 0.85f;
            float g = layer == 0 ? 0.78f : 0.96f;
            float b = 1.0f;
            double prevX = 0, prevZ = 0;
            for (int s = 0; s <= PILLAR_SEGMENTS; s++) {
                float f = s / (float) PILLAR_SEGMENTS;
                double sway = Math.sin(wavePhase + f * 3.1) * 0.22 * f;
                double x = foot.x + Math.cos(angle) * sway + Math.cos(angle + Math.PI / 2) * width;
                double z = foot.z + Math.sin(angle) * sway + Math.sin(angle + Math.PI / 2) * width;
                if (s > 0) {
                    stripedQuad(vc, mat,
                            prevX - Math.cos(angle + Math.PI / 2) * width, foot.y + height * ((s - 1) / (float) PILLAR_SEGMENTS),
                            prevZ - Math.sin(angle + Math.PI / 2) * width,
                            prevX + Math.cos(angle + Math.PI / 2) * width, foot.y + height * ((s - 1) / (float) PILLAR_SEGMENTS),
                            prevZ + Math.sin(angle + Math.PI / 2) * width,
                            x, foot.y + height * f, z,
                            r, g, b, alpha * (1.0f - f * 0.55f));
                }
                prevX = x;
                prevZ = z;
            }
        }
    }

    /** 螺旋水带：绕圆心的带状 ribbon。 */
    private static void drawHelix(VertexConsumer vc, Matrix4f mat, Vec3 base, float radius,
                                  float age, double phase, float rise) {
        int segments = 28;
        double turns = 1.6;
        float startY = 0.1f;
        float topY = 3.2f * rise;
        double prevX = 0, prevY = 0, prevZ = 0;
        for (int s = 0; s <= segments; s++) {
            float f = s / (float) segments;
            double a = phase + turns * Math.PI * 2.0 * f + age * 0.06;
            double r = radius * (0.30 + 0.55 * f);
            double x = base.x + Math.cos(a) * r;
            double z = base.z + Math.sin(a) * r;
            double y = base.y + startY + (topY - startY) * f;
            if (s > 0) {
                double ox = Math.cos(a) * 0.09;
                double oz = Math.sin(a) * 0.09;
                stripedQuad(vc, mat,
                        (float) (prevX - ox), (float) prevY, (float) (prevZ - oz),
                        (float) (prevX + ox), (float) prevY, (float) (prevZ + oz),
                        (float) (x + ox), (float) y, (float) (z + oz),
                        0.72f, 0.95f, 1.0f, 0.5f * (1.0f - f * 0.6f));
            }
            prevX = x;
            prevY = y;
            prevZ = z;
        }
    }

    /** 一圈贴地环带（用 quad 拼成，手绘风格，不是线段）。 */
    private static void drawRing(VertexConsumer vc, Matrix4f mat, Vec3 center, double radius,
                                 double width, float r, float g, float b, float alpha) {
        if (radius <= 0.02 || alpha <= 0.01f) {
            return;
        }
        double inner = Math.max(0.01, radius - width);
        double outer = radius + width;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double a0 = Math.PI * 2.0 * i / RING_SEGMENTS;
            double a1 = Math.PI * 2.0 * (i + 1) / RING_SEGMENTS;
            double x0i = center.x + Math.cos(a0) * inner;
            double z0i = center.z + Math.sin(a0) * inner;
            double x0o = center.x + Math.cos(a0) * outer;
            double z0o = center.z + Math.sin(a0) * outer;
            double x1i = center.x + Math.cos(a1) * inner;
            double z1i = center.z + Math.sin(a1) * inner;
            double x1o = center.x + Math.cos(a1) * outer;
            double z1o = center.z + Math.sin(a1) * outer;
            double y = center.y;
            stripedQuad(vc, mat, x0i, y, z0i, x0o, y, z0o, x1o, y, z1o, r, g, b, alpha);
            stripedQuad(vc, mat, x0i, y, z0i, x1o, y, z1o, x1i, y, z1i, r, g, b, alpha);
        }
    }


    /** 四顶点四边形（debugQuads 不做背面剔除，顶点绕序无所谓）。 */
    private static void quad4(VertexConsumer vc, Matrix4f mat,
                              double x1, double y1, double z1,
                              double x2, double y2, double z2,
                              double x3, double y3, double z3,
                              double x4, double y4, double z4,
                              float r, float g, float b, float a) {
        vc.addVertex(mat, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
    }

    /** 一个带颜色的四边形（两个三角形，顶点顺序无所谓：debugQuads 不做背面剔除）。 */
    private static void stripedQuad(VertexConsumer vc, Matrix4f mat,
                                    double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    double x3, double y3, double z3,
                                    float r, float g, float b, float a) {
        vc.addVertex(mat, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
    }
}

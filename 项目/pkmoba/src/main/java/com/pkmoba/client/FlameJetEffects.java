package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.FlameJetGeometry;
import com.pkmoba.combat.Skill;
import com.pkmoba.network.JetPayload;
import com.pkmoba.particle.PkmobaParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 喷火龙 Q「喷射火焰」的客户端表现层。
 *
 * <p>三段式：<b>前摇蓄力</b>（channel 1 开始、首个 {@link JetPayload} 到达之前）→
 * <b>喷发</b>（payload 驱动，服务端已经算好嘴部原点 / 方向 / 撞墙长度）→ <b>余焰</b>。
 *
 * <p>0.38.4 客户端修正（纯表现，不碰伤害/判定）：
 * <ul>
 *   <li>出火点唯一来源是 {@link MouthLocator#mouth}：优先 Cobblemon 活体骨骼定位器
 *       （跟着施法动画一起动），拿不到就退回 {@link com.pkmoba.combat.MouthAnchor}
 *       的静态嘴部偏移；彻底删掉 {@code getEyePosition()} 兜底和双重前推。</li>
 *   <li>服务端本轮把 {@link JetPayload} 的 origin 挪到嘴上（前推 0.15），客户端默认原样信任；
 *       只有 origin 落在嘴后（沿出火方向点积 &lt; 0）、离嘴超过
 *       {@link #ORIGIN_TRUST_DISTANCE} 格、或与嘴的高度差超过
 *       {@link #ORIGIN_VERTICAL_TOLERANCE} 格时才用嘴部锚点覆盖。</li>
 *   <li>生命周期只认 channel 1：{@code ClientState.getChannel() != 1} 的那个 tick 起
 *       不再生成任何新火柱 / 火星，只留 {@link #AFTERGLOW_TICKS} tick 收尾余焰再整段复位。</li>
 *   <li>方向用"模型当前朝向"做帧率无关缓动（{@link #SWEEP_RATE_PER_SECOND}，1-exp），
 *       火焰跟着模型慢慢扫过去，而不是硬跟着头的角度瞬移。</li>
 *   <li>蓄力阶段在嘴前聚火星、聚火光，喷发瞬间再放一圈火星爆点，
 *       保证"动画先出、火焰后上"的节奏肉眼可见。</li>
 * </ul>
 *
 * <p>入口 {@link #init()} 由 {@link TornadoEffects#init()} 串起来调用
 * （PkmobaClient 不在本次可改范围内，所以不在那里注册）。
 */
public final class FlameJetEffects {
    /**
     * 嘴部锚点再沿出火方向前推的一点点（格）。
     *
     * <p>服务端本轮已经把 {@link JetPayload} 的 origin 推到嘴上并前推 0.15，这里用同一个量，
     * 保证"蓄力 / 喷发 / 余焰 / 字形蓄力"用的是同一个锚点；同时避免粒子糊进嘴部几何里。
     */
    private static final double MOUTH_EGRESS = 0.15;
    /**
     * 服务端 origin 与客户端嘴部的距离超过这个值（格），就认为服务端发的还是旧原点
     * （眼睛 / 胸口 / 前推过头），改用 {@link MouthLocator} 的嘴部锚点覆盖。
     */
    private static final double ORIGIN_TRUST_DISTANCE = 4.0;
    /**
     * 服务端 origin 与客户端嘴部的<b>高度差</b>超过这个值（格），也认为不是嘴部原点。
     *
     * <p>0.38.4 保险：旧实现从 {@code getEyePosition()} 起算，眼睛和嘴是纯竖直偏移，
     * 水平点积永远是 0、距离也只有 1 格出头，前两条判据都抓不到；只有高度差能兜住
     * "服务端/客户端实体位置不同步导致火柱从眼睛出"这种偶发帧。
     */
    private static final double ORIGIN_VERTICAL_TOLERANCE = 0.8;
    /** 服务端 Q 前摇 tick 数（CharizardCombat.Q_WINDUP_TICKS），客户端只用来做蓄力进度。 */
    private static final int WINDUP_TICKS = 11;
    /**
     * 方向指数平滑速率（1/秒，帧率无关）。0.38.3 起从"每 tick 固定 lerp 0.16"改成每帧 exp 平滑：
     * {@code alpha = 1 - exp(-SWEEP_RATE_PER_SECOND * dtSeconds)}，12/s ≈ 83ms 追上 63%，
     * 比旧的 20Hz 阶梯快得多，同时 60fps 下每帧都推进，火柱方向没有台阶。
     */
    private static final double SWEEP_RATE_PER_SECOND = 12.0;
    /**
     * channel 1 结束之后再演几 tick 余焰（0.15 秒）。
     *
     * <p>0.38.4：8 tick 的"靠 payload 间隔猜结束"已经删掉 —— 那套逻辑在动画/引导停了以后
     * 还会继续喷将近半秒，正是用户说的"动画结束了还在喷射火焰"。
     */
    private static final int AFTERGLOW_TICKS = 3;
    /** 喷火龙 R「大字爆」的蓄力通道（服务端 CharizardCombat.R_CHARGE_TICKS = 20）。 */
    private static final int GLYPH_CHANNEL = 4;
    private static final int R_CHARGE_TICKS = 20;
    /** 蓄力累计到这个 tick 释放才算"真的放出去了"，否则当成被打断，不放爆点。 */
    private static final int GLYPH_RELEASE_TICKS = 8;
    /** 核心火柱射线数。 */
    private static final int CORE_RAYS = 46;
    /** 单次 payload 的火星 / 余烟数量。 */
    private static final int EMBERS = 10;
    private static final int SMOKE_PUFFS = 5;
    /** 同屏上限：粒子只在 128 格内生成。 */
    private static final double RENDER_DISTANCE_SQR = 128 * 128;

    private static boolean initialized;
    /** 本段 channel 1 已持续 tick（前摇进度）。 */
    private static int channelTicks;
    /** 本段 channel 1 是否已经出火（收到过 payload）。 */
    private static boolean firing;
    /** 最后一个 payload 距今的 tick（只用来给喷发期火星控制节奏）。 */
    private static int sincePayload;
    /** channel 1 结束之后已经演了几 tick 余焰（0 表示刚结束）。 */
    private static int afterglowTicks;
    /** 缓动后的喷火方向（世界空间，水平归一化）。 */
    private static Vec3 aimDir = Vec3.ZERO;
    /** 最近一次刷新的嘴部位置（世界空间，来自 {@link MouthLocator}），蓄力 / 余焰 / 字形蓄力共用。 */
    private static Vec3 mouth = Vec3.ZERO;
    /** 最近一帧服务端下发的出火点（已前推），喷发中的补间火星直接复用它。 */
    private static Vec3 lastOrigin = Vec3.ZERO;
    private static boolean hasOrigin;
    /** R「大字爆」已经蓄力的 tick 数（channel 4，按物种和加农水炮区分）。 */
    private static int glyphTicks;

    private FlameJetEffects() { }

    /** 幂等注册；由 {@link TornadoEffects#init()} 调用。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(FlameJetEffects::tick);
        WorldRenderEvents.START.register(FlameJetEffects::smoothAimPerFrame);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void reset() {
        channelTicks = 0;
        firing = false;
        sincePayload = 0;
        afterglowTicks = 0;
        aimDir = Vec3.ZERO;
        mouth = Vec3.ZERO;
        lastOrigin = Vec3.ZERO;
        hasOrigin = false;
        glyphTicks = 0;
    }

    private static PokemonEntity controlled(Minecraft mc) {
        if (mc.level == null) return null;
        int id = ClientState.getCharizardId();
        if (id < 0) return null;
        return mc.level.getEntity(id) instanceof PokemonEntity pokemon ? pokemon : null;
    }

    /**
     * 方向初始化 + 嘴部位置缓存（每 tick）。
     *
     * <p>0.38.4：嘴部位置唯一来源是 {@link MouthLocator#mouth}（活体骨骼定位器优先，
     * 拿不到退回静态嘴部偏移），<b>不再用 {@code getEyePosition()}</b> —— 眼睛高度大约
     * 比真嘴低半格还靠后，那就是"火焰从胸口飘出来"的根源。
     * 真正的方向平滑在 {@link #smoothAimPerFrame} 里按帧推进，保证 60fps 下方向连续。
     */
    private static void refreshAim(Minecraft mc, PokemonEntity entity) {
        Vec3 model = forward(entity.getYRot());
        if (aimDir.lengthSqr() < 1.0E-6) {
            aimDir = model;
        }
        Vec3 locator = MouthLocator.mouth(entity, 0);
        if (finite(locator)) {
            mouth = locator;
        }
    }

    /**
     * 每帧把 aimDir 朝模型当前朝向做一次帧率无关的指数平滑（方向唯一来源仍是模型 yRot）。
     * 用 realtime delta，而不是固定 0.16/tick：帧率越高推进越细，火柱扫过的轨迹连续无台阶。
     */
    private static void smoothAimPerFrame(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PokemonEntity entity = controlled(mc);
        if (entity == null || !entity.isAlive()) return;
        Vec3 model = forward(entity.getYRot());
        if (aimDir.lengthSqr() < 1.0E-6 || aimDir.dot(model) < -0.2) {
            // 模型转过身背对火焰时直接接管，避免缓动绕一大圈。
            aimDir = model;
            return;
        }
        float dtTicks = Mth.clamp(mc.getTimer().getRealtimeDeltaTicks(), 0.0F, 0.5F);
        if (dtTicks <= 1.0E-4F) return;
        double dtSeconds = dtTicks / 20.0;
        double alpha = 1.0 - Math.exp(-SWEEP_RATE_PER_SECOND * dtSeconds);
        Vec3 blended = aimDir.lerp(model, alpha);
        aimDir = blended.lengthSqr() < 1.0E-6 ? model : blended.normalize();
    }

    /** 每客户端 tick：蓄力 → 喷发 → 余焰 的状态机。生命周期只认 channel 1。 */
    private static void tick(Minecraft mc) {
        if (mc.level == null) {
            reset();
            return;
        }
        PokemonEntity entity = controlled(mc);
        if (entity == null || !entity.isAlive()) {
            if (channelTicks > 0 || firing || afterglowTicks > 0) reset();
            return;
        }
        refreshAim(mc, entity);
        boolean channelOne = ClientState.getChannel() == 1;

        // ---- R「大字爆」蓄力（channel 4）：水箭龟 Q 加农水炮也用 4，按物种排他 ----
        boolean glyphCharging = !channelOne && ClientState.getChannel() == GLYPH_CHANNEL
                && !Skill.isBlastoise(entity);
        if (glyphCharging) {
            glyphTicks++;
            spawnGlyphCharge(mc, entity);
        } else if (glyphTicks > 0) {
            if (glyphTicks >= GLYPH_RELEASE_TICKS) spawnGlyphBurst(mc, entity);
            glyphTicks = 0;
        }

        if (channelOne) {
            channelTicks++;
            if (!firing) {
                // ---- 前摇：服务端 11 tick 后才开始发 payload，这段只聚火星、绝不出火柱 ----
                double progress = Math.min(1.0, channelTicks / (double) WINDUP_TICKS);
                spawnCharge(mc, entity, progress);
            } else {
                sincePayload++;
                // 喷发中的持续火星：沿火柱随机撒，补足射线之间的空隙。
                if (sincePayload % 2 == 0) spawnFiringEmbers(mc, entity);
            }
        } else if (firing) {
            // ---- channel 1 已经结束：立刻不再生成任何新火柱，只补 AFTERGLOW_TICKS tick 余焰 ----
            if (afterglowTicks < AFTERGLOW_TICKS) {
                spawnAfterglow(mc, entity);
                afterglowTicks++;
            } else {
                reset();
            }
        } else if (channelTicks > 0) {
            // 前摇期间被中断（还没出过火）：直接收掉蓄力状态。
            reset();
        }
    }

    /**
     * R「大字爆」蓄力：火星螺旋吸进手心大小的一团火心，外圈越来越紧，
     * 地面再扫一圈热浪 —— 服务端这 20 tick 只发 4 颗 FLAME，之前几乎是"站着不动突然起火球"。
     */
    private static void spawnGlyphCharge(Minecraft mc, PokemonEntity entity) {
        if (mc.player == null || entity.distanceToSqr(mc.player) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        double server = Math.max(0.0, Math.min(1.0, ClientState.getCharge()));
        double progress = Math.max(glyphTicks / (double) R_CHARGE_TICKS, server);
        progress = Math.max(0.08, Math.min(1.0, progress));
        Vec3 center = mouthEgress().add(aimDir.scale(0.10 + progress * 0.35));
        // 1) 旋转吸入的火星：半径随蓄力收紧，速度指向中心。
        int sparks = 3 + (int) Math.round(progress * 8);
        double radius = 1.95 - progress * 1.30;
        for (int i = 0; i < sparks; i++) {
            double angle = random.nextDouble() * Math.PI * 2 + glyphTicks * 0.45;
            double r = radius * (0.55 + random.nextDouble() * 0.65);
            Vec3 from = center.add(Math.cos(angle) * r, (random.nextDouble() - 0.5) * r * 0.9,
                    Math.sin(angle) * r);
            if (!clear(mc, from)) continue;
            Vec3 velocity = center.subtract(from).scale(0.24 + progress * 0.22);
            glow(mc, random.nextInt(3) == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME,
                    from, velocity, 1.0F + (float) progress * 0.9F);
        }
        // 2) 中心越蓄越大的火心。
        if (glyphTicks % 2 == 0) {
            puff(mc, center, aimDir.scale(0.02), 0.7F + (float) progress * 1.2F,
                    5 + (int) (progress * 4));
        }
        // 3) 脚下一圈热浪，远处也能看出"在憋大招"。
        if (glyphTicks % 3 == 0) {
            for (int i = 0; i < 8; i++) {
                double angle = i / 8.0 * Math.PI * 2 + glyphTicks * 0.12;
                double r = 1.25 + 0.55 * Math.sin(glyphTicks * 0.2);
                Vec3 point = entity.position().add(Math.cos(angle) * r, 0.08, Math.sin(angle) * r);
                if (!clear(mc, point)) continue;
                glow(mc, ParticleTypes.FLAME, point, new Vec3(0, 0.02, 0), 0.8F);
            }
        }
        // 4) 蓄满前一刻的熔岩爆点，预告"马上要放了"。
        if (progress > 0.85 && random.nextInt(3) == 0) {
            glow(mc, ParticleTypes.LAVA, center, new Vec3(0, 0.05, 0), 1.2F);
        }
    }

    /** 蓄力释放瞬间：一圈火星 + 一大团高亮火心，服务端同时才发出火球。 */
    private static void spawnGlyphBurst(Minecraft mc, PokemonEntity entity) {
        if (mc.player == null) return;
        var random = mc.level.random;
        Vec3 center = mouthEgress().add(aimDir.scale(0.40));
        for (int i = 0; i < 16; i++) {
            Vec3 dir = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.35,
                    random.nextDouble() - 0.5);
            if (dir.lengthSqr() < 1.0E-6) continue;
            dir = dir.normalize();
            Vec3 point = center.add(dir.scale(0.3 + random.nextDouble() * 0.8));
            if (!clear(mc, point)) continue;
            glow(mc, ParticleTypes.FLAME, point, dir.scale(0.20 + random.nextDouble() * 0.12),
                    1.3F + random.nextFloat() * 0.7F);
        }
        var heart = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF_BIG,
                center.x, center.y, center.z, aimDir.x * 0.04, 0.01, aimDir.z * 0.04);
        if (heart != null) {
            heart.scale(1.7F);
            heart.setLifetime(10);
        }
        for (int i = 0; i < 5; i++) {
            tinted(mc, ParticleTypes.SMOKE, center.add(
                            (random.nextDouble() - 0.5) * 0.9, 0.3 + random.nextDouble() * 0.7,
                            (random.nextDouble() - 0.5) * 0.9),
                    new Vec3(0, 0.06, 0), 1.2F, 0.24F, 0.20F, 0.18F, 18 + random.nextInt(8));
        }
    }

    /** 前摇：火星从四周收进嘴里，火光随进度放大。 */
    private static void spawnCharge(Minecraft mc, PokemonEntity entity, double progress) {
        if (mc.player == null || entity.distanceToSqr(mc.player) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 center = mouthEgress().add(aimDir.scale(0.02));
        int sparks = 2 + (int) Math.round(progress * 3);
        for (int i = 0; i < sparks; i++) {
            // 在嘴前一个小球壳上取点，速度指向嘴 —— 读起来就是"把火吸进来"。
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = 0.45 + random.nextDouble() * (0.85 - 0.35 * progress);
            Vec3 offset = new Vec3(Math.cos(angle) * radius,
                    (random.nextDouble() - 0.35) * radius * 0.8, Math.sin(angle) * radius)
                    .add(aimDir.scale(0.15 + random.nextDouble() * 0.5));
            Vec3 from = center.add(offset);
            Vec3 velocity = center.subtract(from).scale(0.22 + progress * 0.16);
            glow(mc, ParticleTypes.SMALL_FLAME, from, velocity, 0.9F + random.nextFloat() * 0.5F);
        }
        // 嘴里越蓄越亮的一团火光。
        if (channelTicks % 2 == 0) {
            var puff = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF_SMALL,
                    center.x, center.y, center.z, aimDir.x * 0.02, 0.01, aimDir.z * 0.02);
            if (puff != null) {
                puff.scale(0.55F + (float) progress * 0.75F);
                puff.setLifetime(6 + (int) (progress * 4));
            }
        }
        // 蓄力末尾嘴前冒一点热浪（灰白烟），把"要喷了"的信号再放大一点。
        if (progress > 0.55 && channelTicks % 3 == 0) {
            Vec3 from = center.add(aimDir.scale(0.2)).add(
                    (random.nextDouble() - 0.5) * 0.3, (random.nextDouble() - 0.2) * 0.3,
                    (random.nextDouble() - 0.5) * 0.3);
            tinted(mc, ParticleTypes.CLOUD, from, aimDir.scale(0.02), 0.42F + (float) progress * 0.3F,
                    0.55F, 0.5F, 0.48F, 7);
        }
    }

    /** 喷发中每 2 tick 沿火柱撒火星，填补 46 条射线中间的空。 */
    private static void spawnFiringEmbers(Minecraft mc, PokemonEntity entity) {
        if (mc.player == null || entity.distanceToSqr(mc.player) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        double reach = 1.2 + random.nextDouble() * 6.0;
        Vec3 base = muzzleOrigin();
        Vec3 point = base.add(aimDir.scale(reach)).add(
                (random.nextDouble() - 0.5) * 1.6, (random.nextDouble() - 0.5) * 1.4,
                (random.nextDouble() - 0.5) * 1.6);
        if (!clear(mc, point)) return;
        glow(mc, ParticleTypes.FLAME, point, aimDir.scale(0.06 + random.nextDouble() * 0.05),
                1.1F + random.nextFloat() * 0.6F);
        if (random.nextInt(3) == 0) {
            tinted(mc, ParticleTypes.CLOUD, point.add(0, 0.15, 0), aimDir.scale(0.02),
                    1.0F, 0.32F, 0.30F, 0.30F, 12 + random.nextInt(8));
        }
    }

    /** 余焰：channel 1 结束后嘴前再拖 3 tick 火苗和烟，然后整段复位。 */
    private static void spawnAfterglow(Minecraft mc, PokemonEntity entity) {
        if (mc.player == null || entity.distanceToSqr(mc.player) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        double fade = 1.0 - afterglowTicks / (double) AFTERGLOW_TICKS;
        if (fade <= 0) return;
        Vec3 point = mouthEgress().add(aimDir.scale(0.20 + random.nextDouble() * 1.0));
        var puff = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF_SMALL,
                point.x, point.y, point.z, aimDir.x * 0.03, 0.02, aimDir.z * 0.03);
        if (puff != null) {
            puff.scale(0.5F + (float) fade * 0.6F);
            puff.setLifetime(6 + (int) (fade * 6));
        }
        if (random.nextInt(2) == 0) {
            tinted(mc, ParticleTypes.CLOUD, point.add(0, 0.1, 0), new Vec3(0, 0.04, 0),
                    1.1F + (float) fade, 0.28F, 0.26F, 0.26F, 14 + random.nextInt(10));
        }
    }

    /**
     * 服务端 {@link JetPayload} → 一帧火柱。
     *
     * <p>0.38.4：channel 1 已经结束就<b>直接丢弃迟到的 payload</b>（这就是"动画停了还在喷火"
     * 的第二条泄漏路径）；origin 默认信任服务端的嘴部原点，只有明显不对时才用
     * {@link #resolveOrigin} 的嘴部锚点覆盖；方向用缓动后的 {@link #aimDir}，
     * 所以火柱是"跟着模型慢慢扫"。
     */
    public static void spawn(Minecraft mc, JetPayload p) {
        var level = mc.level;
        if (level == null || !(level.getEntity(p.entityId()) instanceof PokemonEntity entity)) return;
        // 引导已经结束：任何迟到的 payload 都不再生成粒子，只允许 tick() 里那 3 tick 余焰。
        if (ClientState.getChannel() != 1) return;
        Vec3 serverOrigin = new Vec3(p.x(), p.y(), p.z());
        Vec3 serverDir = new Vec3(p.dx(), p.dy(), p.dz());
        if (!finite(serverOrigin) || !finite(serverDir) || !Double.isFinite(p.length())
                || serverDir.lengthSqr() < 1.0E-8) return;

        boolean firstFrame = !firing;
        firing = true;
        sincePayload = 0;
        afterglowTicks = 0;
        if (aimDir.lengthSqr() < 1.0E-6) aimDir = serverDir.normalize();

        Vec3 dir = aimDir;
        // 服务端方向与缓动方向差太多（模型刚甩头）时，这一帧退化成服务端方向，避免火柱歪到身体上。
        if (dir.dot(serverDir.normalize()) < 0.55) dir = serverDir.normalize();
        Vec3 origin = resolveOrigin(entity, serverOrigin, dir);
        lastOrigin = origin;
        hasOrigin = true;
        if (firstFrame) {
            // 本段第一次出火：先把蓄力状态清掉并放一圈火星爆点（爆点就放在真正的出火点上）。
            channelTicks = 0;
            spawnIgnition(mc, origin);
        }
        double length = Math.max(0.35, Math.min(FlameJetGeometry.RANGE, p.length()));

        Vec3 right = new Vec3(0, 1, 0).cross(dir).normalize();
        Vec3 up = dir.cross(right).normalize();

        // 1) 核心火柱：锥形射线 + 撞墙裁剪，保证和伤害扇面同形。
        for (int i = 0; i < CORE_RAYS; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double distance = 0.4 + Math.max(0, length - 0.9)
                    * ((i + level.random.nextDouble()) / (double) CORE_RAYS);
            double theta = Math.max(0, FlameJetGeometry.HALF_ANGLE - Math.asin(Math.min(1, 0.28 / distance)))
                    * Math.sqrt(level.random.nextDouble());
            Vec3 ray = dir.scale(Math.cos(theta)).add(right.scale(Math.sin(theta) * Math.cos(angle)))
                    .add(up.scale(Math.sin(theta) * Math.sin(angle)));
            Vec3 end = origin.add(ray.scale(length));
            HitResult hit = level.clip(new ClipContext(origin, end,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            double reach = hit.getType() == HitResult.Type.BLOCK ? origin.distanceTo(hit.getLocation()) : length;
            float scale = 0.75F + level.random.nextFloat() * 0.55F;
            double usable = Math.max(0, reach - (0.2 + scale * 0.4));
            if (distance > usable) continue;
            puff(mc, origin.add(ray.scale(distance)), ray.scale(0.05), scale, 5 + level.random.nextInt(4));
        }

        // 2) 火星：快速向外飞散的亮点，让火柱"有颗粒"。
        for (int i = 0; i < EMBERS; i++) {
            double distance = 0.5 + level.random.nextDouble() * Math.max(0.5, length * 0.92);
            double spread = 0.35 + distance * 0.11;
            Vec3 point = origin.add(dir.scale(distance)).add(
                    (level.random.nextDouble() - 0.5) * spread * 2,
                    (level.random.nextDouble() - 0.45) * spread * 1.7,
                    (level.random.nextDouble() - 0.5) * spread * 2);
            if (!clear(mc, point)) continue;
            Vec3 velocity = dir.scale(0.10 + level.random.nextDouble() * 0.08)
                    .add(0, 0.04 + level.random.nextDouble() * 0.05, 0);
            glow(mc, ParticleTypes.FLAME, point, velocity, 1.0F + level.random.nextFloat() * 0.8F);
        }

        // 3) 末端火舌：柱体尽头再补几团往前飞的火焰，拉出"喷很远"的轮廓。
        Vec3 tip = origin.add(dir.scale(length));
        for (int i = 0; i < 4; i++) {
            Vec3 point = tip.add(
                    (level.random.nextDouble() - 0.5) * 0.7,
                    (level.random.nextDouble() - 0.5) * 0.7,
                    (level.random.nextDouble() - 0.5) * 0.7);
            if (!clear(mc, point)) continue;
            puff(mc, point, dir.scale(0.12 + level.random.nextDouble() * 0.08),
                    0.7F + level.random.nextFloat() * 0.5F, 5 + level.random.nextInt(4));
        }

        // 4) 尾烟：柱体后段掺一点暗烟，火柱不会像纯色渐变块。
        for (int i = 0; i < SMOKE_PUFFS; i++) {
            double distance = length * (0.45 + level.random.nextDouble() * 0.55);
            Vec3 point = origin.add(dir.scale(distance)).add(
                    (level.random.nextDouble() - 0.5) * 1.1,
                    0.2 + level.random.nextDouble() * 0.5,
                    (level.random.nextDouble() - 0.5) * 1.1);
            if (!clear(mc, point)) continue;
            tinted(mc, ParticleTypes.CLOUD, point, dir.scale(0.02).add(0, 0.03, 0),
                    1.3F + level.random.nextFloat() * 0.7F, 0.22F, 0.20F, 0.20F,
                    14 + level.random.nextInt(10));
        }
    }

    /** 出火瞬间：真正的出火点上一圈火星爆点 + 一大团高亮火心。 */
    private static void spawnIgnition(Minecraft mc, Vec3 origin) {
        if (mc.player == null) return;
        var random = mc.level.random;
        for (int i = 0; i < 14; i++) {
            Vec3 dir = new Vec3(random.nextDouble() - 0.5,
                    random.nextDouble() - 0.35, random.nextDouble() - 0.5);
            if (dir.lengthSqr() < 1.0E-6) continue;
            dir = dir.normalize();
            Vec3 point = origin.add(dir.scale(0.25 + random.nextDouble() * 0.65));
            if (!clear(mc, point)) continue;
            glow(mc, ParticleTypes.FLAME, point, dir.scale(0.16 + random.nextDouble() * 0.12),
                    1.3F + random.nextFloat() * 0.7F);
        }
        var heart = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF,
                origin.x, origin.y, origin.z, aimDir.x * 0.05, 0.02, aimDir.z * 0.05);
        if (heart != null) {
            heart.scale(1.5F);
            heart.setLifetime(7);
        }
        for (int i = 0; i < 6; i++) {
            tinted(mc, ParticleTypes.SMOKE, origin.add(
                            (random.nextDouble() - 0.5) * 0.8, (random.nextDouble() - 0.3) * 0.6,
                            (random.nextDouble() - 0.5) * 0.8),
                    aimDir.scale(0.05).add(0, 0.05, 0), 1.1F + random.nextFloat() * 0.6F,
                    0.18F, 0.16F, 0.16F, 16 + random.nextInt(10));
        }
    }

    /** 出火点：优先用本帧 payload 解析出的 origin，没收到过 payload 时退回嘴部锚点。 */
    private static Vec3 muzzleOrigin() {
        return hasOrigin ? lastOrigin : mouthEgress();
    }

    /** 嘴部锚点 + 沿当前出火方向前推 {@link #MOUTH_EGRESS}（蓄力 / 余焰 / 字形蓄力共用）。 */
    private static Vec3 mouthEgress() {
        return mouthEgress(aimDir);
    }

    /** 嘴部锚点 + 沿给定方向前推 {@link #MOUTH_EGRESS}。 */
    private static Vec3 mouthEgress(Vec3 dir) {
        return mouth.add(dir.scale(MOUTH_EGRESS));
    }

    /**
     * 决定这一帧火柱从哪里出。
     *
     * <p>服务端本轮已经把 {@link JetPayload} 的 origin 挪到嘴上（前推 0.15），正常情况下
     * <b>原样信任</b>（火焰严格长在服务端判定的位置上，"看到的就是打到的"）。只有三种
     * 明显不对的情况才用客户端 {@link MouthLocator} 的嘴部锚点覆盖：
     * <ul>
     *   <li>origin 落在嘴<b>后面</b>：{@code (origin - mouth)·dir < 0}；</li>
     *   <li>origin 离嘴超过 {@link #ORIGIN_TRUST_DISTANCE} 格（服务端还没换成嘴部原点）；</li>
     *   <li>origin 与嘴的<b>高度差</b>超过 {@link #ORIGIN_VERTICAL_TOLERANCE} 格 —— 旧实现的
     *       eye 原点是纯竖直偏移，水平点积抓不到，只能靠高度差兜住。</li>
     * </ul>
     */
    private static Vec3 resolveOrigin(PokemonEntity entity, Vec3 serverOrigin, Vec3 dir) {
        Vec3 mouthNow = MouthLocator.mouth(entity, 0);
        if (!finite(mouthNow)) return serverOrigin;
        boolean behind = serverOrigin.subtract(mouthNow).dot(dir) < 0.0;
        boolean tooFar = serverOrigin.distanceTo(mouthNow) > ORIGIN_TRUST_DISTANCE;
        boolean verticalMismatch = Math.abs(serverOrigin.y - mouthNow.y) > ORIGIN_VERTICAL_TOLERANCE;
        return behind || tooFar || verticalMismatch
                ? mouthNow.add(dir.scale(MOUTH_EGRESS)) : serverOrigin;
    }

    private static void puff(Minecraft mc, Vec3 point, Vec3 velocity, float scale, int lifeTicks) {
        // Also reject grazing side-wall intersections (a ray-only margin is insufficient there).
        if (!clear(mc, point)) return;
        var particle = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF,
                point.x, point.y, point.z, velocity.x, velocity.y, velocity.z);
        if (particle != null) {
            particle.scale(scale);
            particle.setLifetime(lifeTicks);
        }
    }

    /** 原版火焰/火星：叠一层更亮的粒子，火柱中心才够"烫"。 */
    private static void glow(Minecraft mc, ParticleOptions type, Vec3 point, Vec3 velocity, float scale) {
        var particle = mc.particleEngine.createParticle(type,
                point.x, point.y, point.z, velocity.x, velocity.y, velocity.z);
        if (particle != null) particle.scale(scale);
    }

    /** 染色粒子的统一入口（CLOUD/SMOKE 默认是白色，需要压成烟灰色）。 */
    private static void tinted(Minecraft mc, ParticleOptions type, Vec3 point, Vec3 velocity,
                               float scale, float red, float green, float blue, int lifeTicks) {
        var particle = mc.particleEngine.createParticle(type,
                point.x, point.y, point.z, velocity.x, velocity.y, velocity.z);
        if (particle != null) {
            particle.setColor(red, green, blue);
            particle.scale(scale);
            particle.setLifetime(lifeTicks);
        }
    }

    private static boolean clear(Minecraft mc, Vec3 point) {
        if (mc.level == null || !finite(point)) return false;
        return !mc.level.getBlockCollisions(null,
                new net.minecraft.world.phys.AABB(point, point).inflate(0.25)).iterator().hasNext();
    }

    /** 模型正前方：MC 的 yaw=0 朝 +Z，所以前向量是 (-sin, 0, cos)。 */
    static Vec3 forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}

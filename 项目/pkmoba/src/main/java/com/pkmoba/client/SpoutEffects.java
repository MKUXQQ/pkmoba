package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.network.SpoutPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.38 水箭龟 R「喷水」的客户端降雨表现。
 *
 * <p>服务端在引导结束（t=10）时广播 {@link SpoutPayload}：锚点、水平朝向、扇形半角、射程、
 * 降雨时长和随机种子。客户端用同一个种子在本地生成 20 波 × 8 颗、共 160 颗水滴，
 * 这样每颗水滴都不需要单独走网络。纯表现层：不参与命中判定，也不修改任何服务端状态。
 *
 * <p>前摇水膜在客户端按 {@link ClientState#getChannel()} == 6 自行计时（约 10t 渐亮），
 * 因为 SpoutPayload 要等前摇结束才会到达。
 */
public final class SpoutEffects {
    /** 20 波 × 8 颗，共 160 颗水滴。 */
    private static final int WAVES = 20;
    private static final int PER_WAVE = 8;
    /** 同屏硬上限：超过这个数只渲染前 N 颗，避免大场面刷爆顶点缓冲（0.39 从 80 降到 44 减遮挡）。 */
    private static final int MAX_VISIBLE_DROPS = 44;
    /** 水滴出生高度（格）与下落速度（格/tick）：0.45 起步，加速到 0.75。0.39：降低最高点减少天空遮挡。 */
    private static final double MIN_HEIGHT = 4.5, MAX_HEIGHT = 7.0;
    /**
     * 水柱喷泉阵（0.39 新增）：大招落地后从扇形范围内窜起的竖直水柱。
     *
     * <p>为什么加：用户反馈"大招特效太单薄，像普通技能，要求水柱喷泉阵 + 水雾更有冲击力"。
     * 单纯加水滴只会更挡视野，所以这里用<b>低数量、高对比</b>的竖直水柱做出体积感，
     * 同时把总体雨滴数量砍半——冲击力靠形状和节奏，不靠堆粒子。
     */
    private static final int PILLARS = 7;
    private static final int PILLAR_LIFE = 26;
    private static final int PILLAR_GROW = 6;
    private static final int PILLAR_FADE = 8;
    private static final double PILLAR_HEIGHT = 3.8;
    private static final double PILLAR_RADIUS = 0.62;
    /** 释放瞬间从锚点向外扩散的地面冲击环时长（tick）。 */
    private static final int SHOCK_TICKS = 22;
    private static final double FALL_MIN = 0.45, FALL_MAX = 0.75;
    /** 落点离锚点最近 2.5 格，最远取 payload.length（设计值 14）。 */
    private static final double MIN_DISTANCE = 2.5;
    /** 同一波内落点最小间距与最多重采样次数（同设计文档 §5.4）。 */
    private static final double WAVE_MIN_SPACING = 1.2;
    private static final int WAVE_RESAMPLE = 8;
    /** 前摇 10t 渐亮、降雨结束后残留 10t 渐隐。 */
    private static final int WINDUP_TICKS = 24;
    private static final int LINGER_TICKS = 10;
    /** 距相机超过 96 格的水滴直接不渲染。 */
    private static final double RENDER_DISTANCE = 96.0;
    /** payload 参数异常时的兜底扇形：14 格 / ±30°。 */
    private static final double SPOUT_LENGTH = 14.0;
    private static final float SPOUT_HALF_ANGLE = 30.0F;
    /** 落地泡沫环半径（格）与存活时间（tick）。 */
    private static final double FOAM_RADIUS = 0.55;
    private static final int FOAM_TICKS = 8;

    private static final Map<Integer, SpoutState> ACTIVE = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long ticks;
    /** 客户端自行计时的前摇水膜（channel 6 期间存在）。 */
    private static WindupState windup;
    private static boolean initialized;

    private SpoutEffects() {
    }

    /** 在 {@code PkmobaClient.onInitializeClient()} 里调用一次即可：接收、计时、渲染全部自注册。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(SpoutPayload.TYPE, (p, context) ->
                context.client().execute(() -> onSpout(context.client(), p)));
        ClientTickEvents.END_CLIENT_TICK.register(SpoutEffects::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SpoutEffects::render);
    }

    /** 接收服务端广播的喷水范围；durationTicks &lt;= 0 表示立即取消该实体未结束的降雨。 */
    public static void onSpout(Minecraft mc, SpoutPayload p) {
        if (mc.level == null) return;
        if (currentLevel != mc.level) {
            clear();
            currentLevel = mc.level;
        }
        if (p.durationTicks() <= 0) {
            ACTIVE.remove(p.entityId());
            if (windup != null && windup.entityId == p.entityId()) windup = null;
            return;
        }
        if (!finite(p)) return;
        if (!(mc.level.getEntity(p.entityId()) instanceof PokemonEntity)) return;
        if (ACTIVE.size() >= 4) return;
        Vec3 origin = new Vec3(p.x(), p.y(), p.z());
        double length = p.length() > 0.5 ? p.length() : SPOUT_LENGTH;
        float halfAngle = p.halfAngleDeg() > 0.5F ? Math.min(80.0F, p.halfAngleDeg()) : SPOUT_HALF_ANGLE;
        SpoutState state = new SpoutState(p.entityId(), origin, p.yaw(), halfAngle, length,
                Math.max(1, p.durationTicks()));
        buildDrops(mc, state, RandomSource.create(p.seed()));
        // 水柱用独立种子：同一场雨每次看起来一样，但水柱位置不与雨滴落点重合。
        buildPillars(mc, state, RandomSource.create(p.seed() ^ 0x9E3779B97F4A7C15L));
        // 服务端已确认降雨开始：客户端前摇提示让位给正式水膜。
        windup = null;
        ACTIVE.put(p.entityId(), state);
    }

    private static void clear() {
        ACTIVE.clear();
        windup = null;
        currentLevel = null;
        ticks = 0;
    }

    private static boolean finite(SpoutPayload p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
                && Double.isFinite(p.yaw()) && Double.isFinite(p.halfAngleDeg())
                && Double.isFinite(p.length());
    }

    /** 用同一个 seed 生成 20 波落点：扇形内均匀采样、同波最小间距 1.2 格。 */
    private static void buildDrops(Minecraft mc, SpoutState state, RandomSource random) {
        int interval = Math.max(1, state.duration / WAVES);
        double sweep = Math.toRadians(state.halfAngle * 2.0);
        Vec3[] wavePoints = new Vec3[PER_WAVE];
        for (int wave = 0; wave < WAVES; wave++) {
            int placed = 0;
            for (int i = 0; i < PER_WAVE; i++) {
                Vec3 landing = null;
                for (int attempt = 0; attempt < WAVE_RESAMPLE; attempt++) {
                    double distance = MIN_DISTANCE + random.nextDouble() * (state.length - MIN_DISTANCE);
                    double angle = Math.toRadians(state.yaw) - Math.toRadians(state.halfAngle)
                            + random.nextDouble() * sweep;
                    double x = state.origin.x - Math.sin(angle) * distance;
                    double z = state.origin.z + Math.cos(angle) * distance;
                    Vec3 point = new Vec3(x, groundHeight(mc, x, state.origin.y, z), z);
                    boolean clear = true;
                    for (int k = 0; k < placed; k++) {
                        if (wavePoints[k].distanceToSqr(point) < WAVE_MIN_SPACING * WAVE_MIN_SPACING) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) {
                        landing = point;
                        break;
                    }
                }
                // 重采样 8 次仍挤在同一处时宁可重叠，也不跳过这一颗。
                if (landing == null) landing = new Vec3(state.origin.x, state.origin.y, state.origin.z);
                wavePoints[placed++] = landing;

                double height = MIN_HEIGHT + random.nextDouble() * (MAX_HEIGHT - MIN_HEIGHT);
                double radius = 0.09 + random.nextDouble() * 0.07;
                double accel = (FALL_MAX * FALL_MAX - FALL_MIN * FALL_MIN) / (2.0 * height);
                double fallTicks = Math.max(1.0, (FALL_MAX - FALL_MIN) / accel);
                double driftAngle = random.nextDouble() * Math.PI * 2.0;
                double drift = (random.nextDouble() - 0.5) * 0.7;
                double vx = Math.cos(driftAngle) * drift / fallTicks;
                double vz = Math.sin(driftAngle) * drift / fallTicks;
                int spawnTick = wave * interval + (interval > 1 ? random.nextInt(interval) : 0);
                state.drops.add(new Drop(landing, height, radius, accel, vx, vz, spawnTick));
            }
        }
    }

    /**
     * 生成扇形范围内的水柱喷泉位置（0.39）。
     *
     * <p>落点按"近/中/远"三档均匀取样，避免全挤在一条弧线上；出生时间错开，
     * 让水柱是一条一条窜起来的，而不是整片同时爆开（那样最挡视野）。
     */
    private static void buildPillars(Minecraft mc, SpoutState state, RandomSource random) {
        double sweep = Math.toRadians(state.halfAngle * 2.0);
        for (int i = 0; i < PILLARS; i++) {
            double distance;
            if (i < 3) {
                distance = MIN_DISTANCE + (state.length - MIN_DISTANCE) * (0.12 + 0.14 * i);
            } else {
                distance = MIN_DISTANCE + random.nextDouble() * (state.length - MIN_DISTANCE) * 0.95;
            }
            double angle = Math.toRadians(state.yaw) - Math.toRadians(state.halfAngle)
                    + random.nextDouble() * sweep;
            double x = state.origin.x - Math.sin(angle) * distance;
            double z = state.origin.z + Math.cos(angle) * distance;
            double y = groundHeight(mc, x, state.origin.y, z);
            int spawnTick = 1 + (int) Math.round(random.nextDouble() * Math.max(1, state.duration * 0.45));
            state.pillars.add(new Pillar(x, y, z, spawnTick));
        }
    }


    /** 从落点上方垂直射线找地面高度；没有方块时退回锚点高度。 */
    private static double groundHeight(Minecraft mc, double x, double originY, double z) {
        if (mc.level == null) return originY;
        Vec3 from = new Vec3(x, originY + MAX_HEIGHT + 2.0, z);
        var hit = mc.level.clip(new ClipContext(from, new Vec3(x, originY - 6.0, z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : originY;
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level == null) {
            clear();
            return;
        }
        if (currentLevel != mc.level) {
            clear();
            currentLevel = mc.level;
        }
        updateWindup(mc);
        spawnWindupDrops(mc);
        if (ACTIVE.isEmpty()) return;
        var iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            SpoutState state = iterator.next();
            state.age++;
            for (Drop drop : state.drops) {
                if (!drop.born) {
                    if (state.age < drop.spawnTick) continue;
                    drop.born = true;
                }
                if (drop.dead) continue;
                if (drop.landed) {
                    if (++drop.groundAge > FOAM_TICKS) drop.dead = true;
                    continue;
                }
                drop.vy = Math.min(FALL_MAX, drop.vy + drop.accel);
                drop.x += drop.vx;
                drop.z += drop.vz;
                drop.y -= drop.vy;
                if (drop.y <= drop.groundY) {
                    drop.y = drop.groundY;
                    drop.landed = true;
                    groundSplash(mc, drop);
                }
            }
            // 最后一波最多晚 duration 出生、再花约 15t 落地和 8t 泡沫，留 24t 余量后回收。
            if (state.age > state.duration + LINGER_TICKS + 24) iterator.remove();
            else if (state.age % 2 == 0) spawnRainMist(mc, state);
            spawnPillarMist(mc, state);
        }
    }

    /**
     * 前摇聚水（0.38.2 新增）：水滴从水球四周被吸进去，charge 越高吸得越密，
     * 给喷水术一个"憋住 → 炸开"的起手，而不是一进 channel 就已经在下雨。
     */
    private static void spawnWindupDrops(Minecraft mc) {
        WindupState state = windup;
        if (state == null || mc.level == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(state.origin) > RENDER_DISTANCE * RENDER_DISTANCE) return;
        var random = mc.level.random;
        double progress = Math.min(1.0, (state.ticks + 1) / (double) WINDUP_TICKS);
        Vec3 center = state.origin.add(0, 1.15, 0).add(direction(state.yaw).scale(0.85));
        int drops = 1 + (int) Math.round(progress * 3);
        for (int i = 0; i < drops; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = 0.40 + random.nextDouble() * (1.0 - 0.40 * progress);
            Vec3 from = center.add(Math.cos(angle) * radius,
                    (random.nextDouble() - 0.45) * radius * 0.9, Math.sin(angle) * radius);
            Vec3 velocity = center.subtract(from).scale(0.20 + progress * 0.12);
            mc.level.addParticle(ParticleTypes.SPLASH, from.x, from.y, from.z,
                    velocity.x, velocity.y, velocity.z);
        }
        if (state.ticks % 4 == 0) {
            var mist = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    center.x, center.y, center.z, 0.01, 0.02, 0.01);
            if (mist != null) {
                mist.setColor(0.80F, 0.94F, 1.0F);
                mist.scale(0.6F + (float) progress * 0.8F);
                mist.setLifetime(12 + random.nextInt(6));
            }
        }
    }

    /**
     * 水柱喷泉的伴随粒子（0.39）：柱脚炸开的水花 + 柱身周围的水雾。
     *
     * <p>水柱本体是几何面片（{@link #pillar}），它负责"形状"；粒子只负责边缘的破碎感，
     * 所以数量刻意压得很低（每根每 3 tick 一颗），不参与遮挡。
     */
    private static void spawnPillarMist(Minecraft mc, SpoutState state) {
        if (mc.level == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(state.origin) > RENDER_DISTANCE * RENDER_DISTANCE) return;
        var random = mc.level.random;
        for (Pillar pillar : state.pillars) {
            long age = state.age - pillar.spawnTick;
            if (age < 0 || age >= PILLAR_LIFE) continue;
            if (age == 0) {
                // 刚冒头：柱脚一圈水花。
                for (int i = 0; i < 6; i++) {
                    double angle = i * Math.PI / 3.0;
                    mc.level.addParticle(ParticleTypes.SPLASH, pillar.x, pillar.y + 0.08, pillar.z,
                            Math.cos(angle) * 0.42, 0.30, Math.sin(angle) * 0.42);
                }
            }
            if (age % 3 != 0) continue;
            double height = PILLAR_HEIGHT * Math.min(1.0, age / (double) PILLAR_GROW)
                    * (0.45 + random.nextDouble() * 0.5);
            var mist = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    pillar.x + (random.nextDouble() - 0.5) * 0.7, pillar.y + height,
                    pillar.z + (random.nextDouble() - 0.5) * 0.7, 0.0, 0.05, 0.0);
            if (mist != null) {
                mist.setColor(0.82F, 0.94F, 1.0F);
                mist.scale(0.55F + random.nextFloat() * 0.35F);
                mist.setLifetime(8 + random.nextInt(5));
            }
        }
    }

    /** 降雨过程中的水雾与贴地水花：把稀疏的水滴和地面扇形水膜在观感上连起来。 */
    private static void spawnRainMist(Minecraft mc, SpoutState state) {
        if (mc.level == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(state.origin) > RENDER_DISTANCE * RENDER_DISTANCE) return;
        var random = mc.level.random;
        double yawRange = Math.toRadians(state.halfAngle);
        double yaw = Math.toRadians(state.yaw) + (random.nextDouble() * 2 - 1) * yawRange;
        double distance = state.length * (0.25 + random.nextDouble() * 0.7);
        Vec3 dir = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 spot = state.origin.add(dir.scale(distance));
        // 空气里的细雾
        var mist = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                spot.x, spot.y + 1.0 + random.nextDouble() * 2.5, spot.z, 0.01, 0.03, 0.01);
        if (mist != null) {
            mist.setColor(0.78F, 0.93F, 1.0F);
            mist.scale(0.7F + random.nextFloat() * 0.5F);
            mist.setLifetime(10 + random.nextInt(6));
        }
        // 贴地溅起的水花
        Vec3 ground = state.origin.add(dir.scale(distance));
        mc.level.addParticle(ParticleTypes.SPLASH, ground.x, ground.y + 0.05, ground.z,
                (random.nextDouble() - 0.5) * 0.24, 0.26 + random.nextDouble() * 0.14,
                (random.nextDouble() - 0.5) * 0.24);
    }

    /** 落地小水花：4 颗 SPLASH 粒子；泡沫环在渲染阶段按 groundAge 渐隐。 */
    private static void groundSplash(Minecraft mc, Drop drop) {
        ClientLevel level = mc.level;
        if (level == null) return;
        for (int i = 0; i < 4; i++) {
            double angle = drop.x * 0.7 + i * Math.PI / 2.0;
            level.addParticle(ParticleTypes.SPLASH, drop.x, drop.y + 0.05, drop.z,
                    Math.cos(angle) * 0.3, 0.28, Math.sin(angle) * 0.3);
        }
    }

    /** channel 6 期间客户端自己计时前摇水膜；引导结束（广播到达）后由正式水膜接管。 */
    private static void updateWindup(Minecraft mc) {
        // 0.38.5：前摇扇形的朝向必须和服务端释放时用的 modelForward 一致，否则玩家会看到
        // "扇形指着 A、雨却下在 B"。所以这里读模型 yaw，不再读鼠标 aim。
        if (ClientState.getChannel() != 6) {
            windup = null;
            return;
        }
        PokemonEntity entity = controlledBlastoise(mc);
        if (entity == null || !entity.isAlive()) {
            // 引导期间目标丢失/死亡：立刻停掉前摇水膜，不留下"永远在憋水"的残留特效。
            windup = null;
            return;
        }
        if (windup != null) {
            // 服务端前摇只有 WINDUP_TICKS；钳住上限，避免结束包丢失时扇形亮度无限增长。
            if (windup.ticks < WINDUP_TICKS + 20) windup.ticks++;
            return;
        }
        windup = new WindupState(entity.getId(), entity.position(), entity.getYRot(),
                SPOUT_HALF_ANGLE, SPOUT_LENGTH);
    }

    private static PokemonEntity controlledBlastoise(Minecraft mc) {
        if (mc.level == null || ClientState.getCharizardId() < 0 || !ClientState.isSkillStateReady()) return null;
        return mc.level.getEntity(ClientState.getCharizardId()) instanceof PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isBlastoise(pokemon) ? pokemon : null;
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != currentLevel || ctx.matrixStack() == null
                || ctx.consumers() == null || (ACTIVE.isEmpty() && windup == null)) return;
        Matrix4f matrix = ctx.matrixStack().last().pose();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.entityTranslucentEmissive(WaterEffects.SURFACE));
        Vec3 camera = ctx.camera().getPosition();
        double time = mc.level.getGameTime() + ctx.tickCounter().getGameTimeDeltaPartialTick(false);

        if (windup != null) {
            // 前摇：扇形水膜 10t 渐亮，同时炮口聚起一颗水球。
            double progress = Math.min(1.0, (windup.ticks + 1) / (double) WINDUP_TICKS);
            // m05418：水炮的地面扇形水膜已删除，只保留炮口聚水。
            Vec3 muzzle = windup.origin.add(0, 1.2, 0)
                    .add(direction(windup.yaw).scale(0.9)).subtract(camera);
            orb(vc, matrix, muzzle, 0.18 + 0.28 * progress, (int) (150 + 105 * progress));
        }

        int visible = 0;
        for (SpoutState state : ACTIVE.values()) {
            // 降雨中水膜全亮，降雨结束后残留 10t 渐隐（同设计文档 §5.4）。
            // 0.39：整体不透明度从 110 降到 68 —— 用户反馈"特效范围过大遮挡主要战斗区域"。
            double linger = state.age <= state.duration ? 1.0
                    : Math.max(0.0, 1.0 - (state.age - state.duration) / (double) LINGER_TICKS);
            if (linger > 0) {
                fan(vc, matrix, state.origin.subtract(camera), state.yaw, state.halfAngle, state.length,
                        (int) (68 * linger), time);
            }
            if (state.age <= SHOCK_TICKS) {
                shock(vc, matrix, state.origin.subtract(camera), state.length, state.age);
            }
            for (Pillar pillar : state.pillars) {
                pillar(vc, matrix, pillar, camera, state.age);
            }
            for (Drop drop : state.drops) {
                if (!drop.born || drop.dead || visible >= MAX_VISIBLE_DROPS) continue;
                Vec3 position = new Vec3(drop.x, drop.y, drop.z);
                if (position.distanceToSqr(camera) > RENDER_DISTANCE * RENDER_DISTANCE) continue;
                visible++;
                if (drop.landed) {
                    double fade = 1.0 - drop.groundAge / (double) FOAM_TICKS;
                    foam(vc, matrix, position.subtract(camera), (int) (130 * fade));
                } else {
                    Vec3 velocity = new Vec3(drop.vx, -drop.vy, drop.vz);
                    WaterEffects.droplet(vc, matrix, position.subtract(camera), velocity, drop.radius, 165);
                }
            }
        }
    }

    /**
     * 释放瞬间从锚点向外扩散的地面冲击环（0.39 新增）。
     *
     * <p>它替代了"靠堆雨滴制造冲击感"的做法：一个清晰的扩散环就能把"这一下很重"
     * 传达出去，而它又几乎不遮挡视野（贴着地面、快速淡出）。
     */
    private static void shock(VertexConsumer vc, Matrix4f m, Vec3 origin, double length, long age) {
        double t = age / (double) SHOCK_TICKS;
        if (t >= 1.0) return;
        double eased = 1.0 - (1.0 - t) * (1.0 - t);
        double radius = 0.6 + (length * 0.95 - 0.6) * eased;
        double width = Math.max(0.12, 0.7 * (1.0 - t * 0.7));
        int alpha = (int) (165 * (1.0 - t) * (1.0 - t));
        if (alpha <= 0) return;
        double inner = Math.max(0.0, radius - width);
        Vec3 base = origin.add(0, 0.07, 0);
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0));
            Vec3 d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
            WaterEffects.quad(vc, m, base.add(d0.scale(inner)), base.add(d1.scale(inner)),
                    base.add(d1.scale(radius)), base.add(d0.scale(radius)), 170, 235, 255, alpha);
        }
    }

    /**
     * 一根竖直水柱喷泉（0.39 新增）。
     *
     * <p>形状比粒子更"有体积"：0.62 格底半径，向上收窄到 55%，顶端带一圈亮边（水花冠）。
     * 出生 6t 内快速窜起、末尾 8t 淡出，所以同一时刻最多几根柱子在动，不会糊成一面墙。
     */
    private static void pillar(VertexConsumer vc, Matrix4f m, Pillar p, Vec3 camera, long stateAge) {
        long age = stateAge - p.spawnTick;
        if (age < 0 || age >= PILLAR_LIFE) return;
        double grow = Math.min(1.0, age / (double) PILLAR_GROW);
        // 窜起时略微过冲，再回落，有"喷"的感觉。
        double overshoot = age < PILLAR_GROW ? 0.10 * Math.sin(Math.PI * grow) : 0.0;
        double height = PILLAR_HEIGHT * (grow + overshoot);
        int alpha = (int) (150 * (age > PILLAR_LIFE - PILLAR_FADE
                ? (PILLAR_LIFE - age) / (double) PILLAR_FADE : 1.0));
        if (alpha <= 0 || height <= 0.05) return;
        Vec3 base = new Vec3(p.x, p.y, p.z).subtract(camera);
        int segments = 12;
        int bands = 4;
        for (int band = 0; band < bands; band++) {
            double h0 = height * band / bands;
            double h1 = height * (band + 1) / bands;
            double r0 = PILLAR_RADIUS * (1.0 - 0.45 * (h0 / PILLAR_HEIGHT));
            double r1 = PILLAR_RADIUS * (1.0 - 0.45 * (h1 / PILLAR_HEIGHT));
            int bandAlpha = (int) (alpha * (band == bands - 1 ? 1.0 : 0.72));
            for (int i = 0; i < segments; i++) {
                double a0 = i * Math.PI * 2.0 / segments;
                double a1 = (i + 1) * Math.PI * 2.0 / segments;
                Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0));
                Vec3 d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
                Vec3 p0 = base.add(d0.scale(r0)).add(0, h0, 0);
                Vec3 p1 = base.add(d1.scale(r0)).add(0, h0, 0);
                Vec3 p2 = base.add(d1.scale(r1)).add(0, h1, 0);
                Vec3 p3 = base.add(d0.scale(r1)).add(0, h1, 0);
                WaterEffects.quad(vc, m, p0, p1, p2, p3, 160, 225, 255, bandAlpha);
            }
        }
        // 顶端水花冠：一圈水平亮片，让水柱有"喷到头炸开"的收尾。
        double topY = height;
        double crown = PILLAR_RADIUS * 0.62;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0));
            Vec3 d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
            Vec3 c = base.add(0, topY, 0);
            WaterEffects.quad(vc, m, c, c.add(d0.scale(crown)), c.add(d1.scale(crown)),
                    c.add(0, 0.02, 0), 220, 245, 255, Math.min(240, (int) (alpha * 1.3)));
        }
        if (alpha > 60) {
            foam(vc, m, new Vec3(p.x, p.y + 0.02, p.z).subtract(camera), (int) (alpha * 0.8));
        }
    }


    /** 地面扇形水膜：24 段 × 3 圈，让外沿略亮；alpha 控制前摇/残留的淡入淡出。 */
    private static void fan(VertexConsumer vc, Matrix4f m, Vec3 origin, float yaw, float halfAngle,
                            double length, int alpha, double time) {
        if (alpha <= 0 || length <= 0.1) return;
        Vec3 base = origin.add(0, 0.06, 0);
        int segments = 24;
        double start = Math.toRadians(yaw - halfAngle);
        double sweep = Math.toRadians(halfAngle * 2.0);
        for (int i = 0; i < segments; i++) {
            double a0 = start + sweep * i / segments;
            double a1 = start + sweep * (i + 1) / segments;
            Vec3 d0 = new Vec3(-Math.sin(a0), 0, Math.cos(a0));
            Vec3 d1 = new Vec3(-Math.sin(a1), 0, Math.cos(a1));
            for (int band = 0; band < 3; band++) {
                double r0 = length * band / 3.0;
                double r1 = length * (band + 1) / 3.0;
                // 一点低频波纹，避免整片水膜像一块死板的贴图。
                double ripple = 0.012 * Math.sin(time * 0.35 + i * 0.8 + band);
                Vec3 p0 = base.add(d0.scale(r0)).add(0, ripple, 0);
                Vec3 p1 = base.add(d1.scale(r0)).add(0, ripple, 0);
                Vec3 p2 = base.add(d1.scale(r1)).add(0, ripple, 0);
                Vec3 p3 = base.add(d0.scale(r1)).add(0, ripple, 0);
                int bandAlpha = band == 2 ? alpha : (int) (alpha * (0.5 + 0.18 * band));
                WaterEffects.quad(vc, m, p0, p1, p2, p3, band == 2 ? 150 : 100, 225, 255, bandAlpha);
            }
        }
    }

    /** 落地泡沫环：半径 0.8 格，8t 内随 groundAge 渐隐。 */
    private static void foam(VertexConsumer vc, Matrix4f m, Vec3 center, int alpha) {
        if (alpha <= 0) return;
        int segments = 20;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0));
            Vec3 d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
            WaterEffects.quad(vc, m, center.add(d0.scale(FOAM_RADIUS * 0.75)),
                    center.add(d1.scale(FOAM_RADIUS * 0.75)),
                    center.add(d1.scale(FOAM_RADIUS)), center.add(d0.scale(FOAM_RADIUS)),
                    235, 250, 255, alpha);
        }
    }

    /** 炮口聚水用的小球（8 纬 × 8 经）。 */
    private static void orb(VertexConsumer vc, Matrix4f m, Vec3 center, double radius, int alpha) {
        for (int row = 0; row < 8; row++) {
            double a = -Math.PI / 2 + row * Math.PI / 8;
            double b = -Math.PI / 2 + (row + 1) * Math.PI / 8;
            for (int col = 0; col < 8; col++) {
                double u = col * Math.PI / 4;
                double v = (col + 1) * Math.PI / 4;
                WaterEffects.quad(vc, m, orbPoint(center, radius, a, u), orbPoint(center, radius, a, v),
                        orbPoint(center, radius, b, v), orbPoint(center, radius, b, u), 160, 235, 255, alpha);
            }
        }
    }

    private static Vec3 orbPoint(Vec3 center, double r, double latitude, double longitude) {
        return center.add(Math.cos(latitude) * Math.cos(longitude) * r, Math.sin(latitude) * r,
                Math.cos(latitude) * Math.sin(longitude) * r);
    }

    /** Minecraft 水平朝向 → 单位向量（yaw 0 指向 +Z）。 */
    private static Vec3 direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static final class SpoutState {
        final int entityId;
        final Vec3 origin;
        final float yaw;
        final float halfAngle;
        final double length;
        final int duration;
        final List<Drop> drops = new ArrayList<>(WAVES * PER_WAVE);
        /** 水柱喷泉阵（0.39）。 */
        final List<Pillar> pillars = new ArrayList<>(PILLARS);
        long age;

        SpoutState(int entityId, Vec3 origin, float yaw, float halfAngle, double length, int duration) {
            this.entityId = entityId;
            this.origin = origin;
            this.yaw = yaw;
            this.halfAngle = halfAngle;
            this.length = length;
            this.duration = duration;
        }
    }

    private static final class WindupState {
        final int entityId;
        final Vec3 origin;
        final float yaw;
        final float halfAngle;
        final double length;
        int ticks;

        WindupState(int entityId, Vec3 origin, float yaw, float halfAngle, double length) {
            this.entityId = entityId;
            this.origin = origin;
            this.yaw = yaw;
            this.halfAngle = halfAngle;
            this.length = length;
        }
    }

    /** 一根水柱喷泉：固定落点 + 出生 tick，之后靠 {@code age} 决定高度与透明度。 */
    private static final class Pillar {
        final double x, y, z;
        final int spawnTick;

        Pillar(double x, double y, double z, int spawnTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnTick = spawnTick;
        }
    }

    private static final class Drop {        /** 当前坐标。 */
        double x, y, z;
        /** 落点高度。 */
        final double groundY;
        final int spawnTick;
        final double radius;
        /** 水平漂移速度与竖直加速度。 */
        final double vx, vz;
        final double accel;
        double vy = FALL_MIN;
        boolean born;
        boolean landed;
        boolean dead;
        int groundAge;

        Drop(Vec3 landing, double height, double radius, double accel, double vx, double vz, int spawnTick) {
            this.groundY = landing.y;
            this.spawnTick = spawnTick;
            this.radius = radius;
            this.accel = accel;
            this.vx = vx;
            this.vz = vz;
            // 出生点逆推：保证水平漂移刚好落在 landing 上。
            double fallTicks = Math.max(1.0, (FALL_MAX - FALL_MIN) / accel);
            this.x = landing.x - vx * fallTicks;
            this.z = landing.z - vz * fallTicks;
            this.y = landing.y + height;
        }
    }
}

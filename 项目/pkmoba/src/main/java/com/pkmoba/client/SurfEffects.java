package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 水箭龟 E「冲浪术」（服务端 channel 5）的客户端表现层。
 *
 * <p>服务端只负责位移和伤害，客户端在这里按 channel 状态自己画：脚下/身前一道半透明水墙，
 * 两侧和身后持续冒水花。整块效果完全由 channel 驱动，退出 channel 5（或实体消失）就淡出清理，
 * 不依赖任何 payload，所以不会出现"服务端没广播、水墙永远留着"的情况。
 *
 * <p>0.38.2 排查结论 + 加固（用户连着两轮投诉"冲浪完全没特效"）：
 * <ol>
 *   <li><b>实体解析加固</b>：原版只认 {@code ClientState.getCharizardId()} 且要求物种是水箭龟，
 *       一旦 {@code CharizardSyncPayload} 没跟上（换宠、重新召唤、-1 分支）整块效果就静默消失。
 *       现在依次尝试：受控 id → 上一帧缓存 → 相机 48 格内最近的水箭龟，
 *       命中后缓存 10 tick，任何一条路径通了效果就能出来。</li>
 *   <li><b>节奏加固</b>：alpha 淡入 0.55/tick（2 tick 就可见），channel 5 哪怕只来了 1 tick
 *       也会保持 {@link #MIN_HOLD_TICKS} 的可见时长再淡出，不会"闪一下什么都没有"。</li>
 *   <li><b>不依赖网格的兜底</b>：除了水墙 mesh，每 tick 还撒 SPLASH/BUBBLE/CLOUD/POOF 水花，
 *       网格万一被某个渲染后端吞掉，玩家仍然能明显看到冲浪。</li>
 * </ol>
 *
 * <p>朝向按模型当前 {@code getYRot()} 推算 —— 服务端 FacingService 把 YRot/YHeadRot/YBodyRot
 * 写成同一个值，模型正前方就是 {@code (-sin(yaw), 0, cos(yaw))}。
 */
public final class SurfEffects {
    /** 服务端 BlastoiseCombat.CHANNEL_SURF。 */
    private static final int SURF_CHANNEL = 5;

    /** 淡入 / 淡出每 tick 的透明度步进。 */
    private static final float FADE_IN = 0.55F;
    private static final float FADE_OUT = 0.12F;
    /** channel 5 消失后至少再保持可见的 tick 数（防止服务端 1~2 tick 就 stopSurf）。 */
    private static final int MIN_HOLD_TICKS = 14;
    /** 服务端冲浪总时长（BlastoiseCombat.SURF_TICKS = 蓄水 6 + 冲刺 14 + 爆发 6）。 */
    private static final int SURF_TOTAL_TICKS = 26;
    /** 蓄水起手 + 浪头形成阶段长度。 */
    private static final int SURF_WINDUP_TICKS = 6;
    /** 乘浪冲刺阶段长度。 */
    private static final int SURF_DASH_TICKS = 14;
    /** 水墙整体宽度的一半（格）。 */
    private static final double HALF_WIDTH = 1.35;
    /** 水墙最高处（格）。 */
    private static final double WALL_HEIGHT = 1.25;
    /** 墙体前缘离实体脚底的距离（格）。 */
    private static final double WALL_FORWARD = 0.72;
    /** 浪头向前卷曲的距离（格）。 */
    private static final double WALL_CURL = 0.55;
    /** 横向分段 / 纵向分段：单层 10×3，三层一共 90 个 quad。 */
    private static final int LATERAL_SEGMENTS = 10;
    private static final int HEIGHT_SEGMENTS = 3;
    private static final int LAYERS = 3;
    /** 超过这个距离就不渲染/不喷粒子。 */
    private static final double RENDER_DISTANCE_SQR = 128 * 128;
    /** 找备用实体时扫描的半径（格）与缓存时间（tick）。 */
    private static final double SEARCH_RADIUS = 48.0;
    private static final int SEARCH_CACHE_TICKS = 10;

    private static boolean initialized;
    /** 当前不透明度 0~1；channel 结束掉到 0 即停止渲染。 */
    private static float alpha;
    /** 浪面滚动的相位，每 tick 推进一点。 */
    private static double phase;
    /** channel 5 最后一次出现距今的 tick；到 {@link #MIN_HOLD_TICKS} 之前不允许彻底淡出。 */
    private static int sinceSurf;
    /** 解析到的实体 id（0 = 未解析），以及它的短暂缓存。 */
    private static int resolvedId = -1;
    private static int resolveCacheTicks;
    /** 浪墙显隐系数：蓄水阶段 0（只有水膜和浪头），冲刺淡入到 1，终点爆发后迅速归零。 */
    private static float wall;
    /** 蓄水阶段进度 0~1。 */
    private static float windupProgress;
    /** 本轮是否已经播过终点爆发（每轮只播一次）。 */
    private static boolean burstPlayed;

    private SurfEffects() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(SurfEffects::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SurfEffects::render);
    }

    private static void reset() {
        alpha = 0.0F;
        phase = 0.0;
        sinceSurf = 0;
        resolvedId = -1;
        resolveCacheTicks = 0;
        wall = 0.0F;
        windupProgress = 0.0F;
        burstPlayed = false;
    }

    /**
     * 受控且存活的水箭龟。
     *
     * <p>解析顺序：受控 id（且物种对）→ 缓存 → 相机附近最近的水箭龟 → 受控 id（任何宝可梦，
     * channel 5 只可能是冲浪，所以在 channel 已经确认的情况下物种校验不再是硬门槛）。
     */
    private static PokemonEntity controlledBlastoise(Minecraft mc) {
        if (mc.level == null) return null;
        int id = ClientState.getCharizardId();
        if (resolvedId >= 0 && resolveCacheTicks > 0) {
            resolveCacheTicks--;
            if (mc.level.getEntity(resolvedId) instanceof PokemonEntity cached
                    && cached.isAlive() && ShellAnimator.appliesTo(cached)) {
                return cached;
            }
            resolvedId = -1;
        }
        PokemonEntity direct = id >= 0 && mc.level.getEntity(id) instanceof PokemonEntity pokemon
                ? pokemon : null;
        if (direct != null && direct.isAlive() && ShellAnimator.appliesTo(direct)) {
            cache(direct);
            return direct;
        }
        PokemonEntity nearby = nearestBlastoise(mc);
        if (nearby != null) {
            cache(nearby);
            return nearby;
        }
        // 兜底：channel 5 已经确认时，即使物种/同步有偏差也让效果落在受控实体上。
        if (direct != null && direct.isAlive() && ClientState.getChannel() == SURF_CHANNEL) {
            cache(direct);
            return direct;
        }
        return null;
    }

    private static void cache(PokemonEntity entity) {
        resolvedId = entity.getId();
        resolveCacheTicks = SEARCH_CACHE_TICKS;
    }

    /** 相机 48 格内最近的水箭龟；只在直接解析失败时才会走到。 */
    private static PokemonEntity nearestBlastoise(Minecraft mc) {
        if (mc.level == null) return null;
        Vec3 center = cameraPosition(mc);
        AABB box = new AABB(center, center).inflate(SEARCH_RADIUS);
        List<Entity> found = mc.level.getEntities((Entity) null, box,
                e -> e instanceof PokemonEntity pokemon && pokemon.isAlive() && ShellAnimator.appliesTo(pokemon));
        PokemonEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : found) {
            double distance = entity.position().distanceToSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (PokemonEntity) entity;
            }
        }
        return best;
    }

    /** 上帝视角下相机和玩家不一定同位置，统一用相机做距离判断。 */
    private static Vec3 cameraPosition(Minecraft mc) {
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            return mc.gameRenderer.getMainCamera().getPosition();
        }
        return mc.player != null ? mc.player.position() : Vec3.ZERO;
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null) {
            reset();
            return;
        }
        PokemonEntity rider = controlledBlastoise(mc);
        boolean surfing = rider != null && ClientState.getChannel() == SURF_CHANNEL;
        if (surfing) {
            boolean start = sinceSurf > MIN_HOLD_TICKS || alpha <= 0.01F;
            sinceSurf = 0;
            alpha = Math.min(1.0F, Math.max(alpha, 0.35F) + FADE_IN);
            if (start) {
                burstPlayed = false;
                surfIgnition(mc, rider);
            }
            // 服务端把 skillTicks / 26 写进 charge，客户端据此切阶段。
            int ticks = Math.round(Mth.clamp(ClientState.getCharge(), 0.0F, 1.0F) * SURF_TOTAL_TICKS);
            // 阶段 1/2：蓄水起手 + 浪头形成 —— 只出水纹、气泡和前方长起来的浪头。
            if (ticks < SURF_WINDUP_TICKS) {
                wall = 0.0F;
                windupProgress = Mth.clamp(ticks / (float) SURF_WINDUP_TICKS, 0.0F, 1.0F);
                phase += 0.12;
                spawnChargeMist(mc, rider, windupProgress);
                return;
            }
            // 阶段 5：终点浪花爆发。
            boolean burst = ticks >= SURF_WINDUP_TICKS + SURF_DASH_TICKS;
            if (burst) {
                windupProgress = 1.0F;
                if (!burstPlayed) {
                    burstPlayed = true;
                    surfBurst(mc, rider);
                }
                wall = Math.max(0.0F, wall - 0.35F);
                phase += 0.10;
                spawnBurstSpray(mc, rider);
                return;
            }
            // 阶段 3：乘浪冲刺。
            wall = Math.min(1.0F, wall + 0.5F);
            windupProgress = 1.0F;
            phase += 0.32;
            spawnWake(mc, rider);
            return;
        }
        // 阶段 6：退出引导后先保持 MIN_HOLD_TICKS 的可见时长，再淡出。
        sinceSurf++;
        windupProgress = 1.0F;
        if (sinceSurf <= MIN_HOLD_TICKS) {
            phase += 0.18;
            wall = Math.max(0.0F, wall - 0.18F);
            if (alpha > 0.05F) spawnTrailingWake(mc, rider);
            return;
        }
        wall = Math.max(0.0F, wall - 0.25F);
        alpha = Math.max(0.0F, alpha - FADE_OUT);
        if (alpha <= 0.0F) {
            alpha = 0.0F;
            phase = 0.0;
        }
    }

    /** 起手瞬间：脚下一圈白浪炸开 + 两团水雾，让"冲出去"的第一帧就有戏。 */
    private static void surfIgnition(Minecraft mc, PokemonEntity rider) {
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        for (int i = 0; i < 22; i++) {
            double angle = i / 22.0 * Math.PI * 2;
            Vec3 outward = right.scale(Math.cos(angle)).add(dir.scale(Math.sin(angle)));
            Vec3 pos = base.add(outward.scale(0.35 + random.nextDouble() * 0.9)).add(0, 0.08, 0);
            Vec3 velocity = outward.scale(0.16 + random.nextDouble() * 0.22)
                    .add(0, 0.18 + random.nextDouble() * 0.22, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    velocity.x, velocity.y, velocity.z);
        }
        for (int i = 0; i < 2; i++) {
            Vec3 pos = base.add(dir.scale(0.3 + i * 0.6)).add(0, 0.5 + i * 0.35, 0);
            var cloud = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    pos.x, pos.y, pos.z, dir.x * 0.12, 0.05, dir.z * 0.12);
            if (cloud != null) {
                cloud.setColor(0.88F, 0.97F, 1.0F);
                cloud.scale(1.6F + random.nextFloat() * 0.6F);
                cloud.setLifetime(12 + random.nextInt(6));
            }
        }
    }

    /**
     * 阶段 1/2：脚下水面聚拢 + 前方浪头从 0 长到约 1.2 格。
     *
     * @param progress 蓄水进度 0~1
     */
    private static void spawnChargeMist(Minecraft mc, PokemonEntity rider, float progress) {
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        // 1) 脚下外扩的水纹：半径 0.5 → 1.85。
        double radius = 0.5 + 1.35 * progress;
        for (int i = 0; i < 10; i++) {
            double angle = i / 10.0 * Math.PI * 2.0;
            Vec3 outward = right.scale(Math.cos(angle)).add(dir.scale(Math.sin(angle)));
            Vec3 pos = base.add(outward.scale(radius)).add(0, 0.07, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    outward.x * 0.07, 0.03 + random.nextDouble() * 0.03, outward.z * 0.07);
        }
        // 2) 细小气泡从水面往上冒，越到后面越多。
        int bubbles = progress < 0.5 ? 2 : 4;
        for (int i = 0; i < bubbles; i++) {
            double side = (random.nextDouble() - 0.5) * (1.4 + 1.4 * progress);
            Vec3 pos = base.add(right.scale(side)).add(dir.scale((random.nextDouble() - 0.3) * 1.6))
                    .add(0, 0.05, 0);
            mc.level.addParticle(ParticleTypes.BUBBLE, pos.x, pos.y, pos.z, 0.0, 0.06, 0.0);
        }
        // 3) 前方浪头：贴地一条横向 SPLASH，顶上再压一团白色水雾，随进度升高。
        for (int i = 0; i < 6; i++) {
            double side = (i / 5.0 - 0.5) * (1.2 + 1.9 * progress);
            Vec3 pos = base.add(dir.scale(0.9 + 0.5 * progress)).add(right.scale(side))
                    .add(0, 0.10 + 0.85 * progress, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    0.0, 0.04 + random.nextDouble() * 0.06, 0.0);
        }
        var crest = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                base.x + dir.x * (1.1 + 0.45 * progress),
                base.y + 0.35 + 1.05 * progress,
                base.z + dir.z * (1.1 + 0.45 * progress),
                dir.x * 0.06, 0.03, dir.z * 0.06);
        if (crest != null) {
            crest.setColor(0.82F, 0.94F, 1.0F);
            crest.scale(1.4F + 0.8F * progress);
            crest.setLifetime(12 + random.nextInt(6));
        }
    }

    /** 阶段 5：终点浪花向左右两侧炸开 —— 扇形水花 + 泡沫，纯客户端表现。 */
    private static void surfBurst(Minecraft mc, PokemonEntity rider) {
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 14; i++) {
                double spread = i / 13.0;
                Vec3 pos = base.add(dir.scale(0.5 + 1.1 * spread))
                        .add(right.scale(side * (0.3 + 1.9 * spread)))
                        .add(0, 0.25 + 0.6 * spread, 0);
                mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                        side * (0.14 + 0.2 * spread), 0.18 + random.nextDouble() * 0.16,
                        side * (0.10 + 0.12 * spread));
            }
            var foam = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    base.x + right.x * side * 1.1, base.y + 0.75, base.z + right.z * side * 1.1,
                    right.x * side * 0.18, 0.06, right.z * side * 0.18);
            if (foam != null) {
                foam.setColor(0.90F, 0.97F, 1.0F);
                foam.scale(2.2F + random.nextFloat() * 0.8F);
                foam.setLifetime(14 + random.nextInt(6));
            }
        }
        // 地面一圈快速扩散的浅蓝水纹。
        for (int i = 0; i < 20; i++) {
            double angle = i / 20.0 * Math.PI * 2.0;
            Vec3 outward = right.scale(Math.cos(angle)).add(dir.scale(Math.sin(angle)));
            Vec3 pos = base.add(outward.scale(1.1)).add(0, 0.08, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    outward.x * 0.26, 0.10, outward.z * 0.26);
        }
    }

    /** 终点爆发的余波：两侧水面还在翻，持续 6 tick。 */
    private static void spawnBurstSpray(Minecraft mc, PokemonEntity rider) {
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 3; i++) {
                double spread = random.nextDouble();
                Vec3 pos = base.add(dir.scale(0.4 + spread))
                        .add(right.scale(side * (0.6 + 1.6 * spread)))
                        .add(0, 0.12 + random.nextDouble() * 0.5, 0);
                mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                        side * 0.12, 0.10 + random.nextDouble() * 0.12, side * 0.08);
            }
        }
    }

    /** 两侧 + 身前 + 身后的水花；每 tick 定量，40 tick 的冲浪总量可控。 */
    private static void spawnWake(Minecraft mc, PokemonEntity rider) {
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        // 1) 浪头水花：在身前横向铺开，向前上方飞。
        int splashes = 5 + random.nextInt(3);
        for (int i = 0; i < splashes; i++) {
            double side = (random.nextDouble() - 0.5) * 3.0;
            Vec3 pos = base.add(right.scale(side))
                    .add(dir.scale(0.45 + random.nextDouble() * 1.15))
                    .add(0, 0.10 + random.nextDouble() * 0.55, 0);
            Vec3 velocity = dir.scale(0.16 + random.nextDouble() * 0.18)
                    .add(right.scale(side * 0.20))
                    .add(0, 0.14 + random.nextDouble() * 0.16, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    velocity.x, velocity.y, velocity.z);
        }
        // 2) 尾迹气泡：身后拖出"刚划过去"的痕迹。
        for (int i = 0; i < 3; i++) {
            double side = (random.nextDouble() - 0.5) * 2.2;
            Vec3 pos = base.add(right.scale(side)).add(dir.scale(-0.5 - random.nextDouble() * 1.6))
                    .add(0, 0.05 + random.nextDouble() * 0.25, 0);
            mc.level.addParticle(ParticleTypes.BUBBLE, pos.x, pos.y, pos.z,
                    dir.x * 0.05, 0.07, dir.z * 0.05);
        }
        // 3) 浪尖白色水雾，让水墙顶部和粒子之间不脱节。
        if (random.nextInt(2) == 0) {
            Vec3 pos = base.add(dir.scale(0.8)).add(0, 0.85 + random.nextDouble() * 0.4, 0)
                    .add(right.scale((random.nextDouble() - 0.5) * 1.6));
            var cloud = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    pos.x, pos.y, pos.z, dir.x * 0.10, 0.02, dir.z * 0.10);
            if (cloud != null) {
                cloud.setColor(0.86F, 0.96F, 1.0F);
                cloud.scale(1.1F + random.nextFloat() * 0.6F);
                cloud.setLifetime(10 + random.nextInt(6));
            }
        }
        // 4) 两侧下压的白浪（POOF），冲浪"破开水面"的观感就靠它。
        if (random.nextInt(2) == 0) {
            double side = random.nextBoolean() ? 1 : -1;
            Vec3 pos = base.add(right.scale(side * (1.0 + random.nextDouble() * 0.5)))
                    .add(dir.scale(0.1 + random.nextDouble() * 0.6)).add(0, 0.12, 0);
            var poof = mc.particleEngine.createParticle(ParticleTypes.POOF,
                    pos.x, pos.y, pos.z, right.x * side * 0.12, 0.06, right.z * side * 0.12);
            if (poof != null) {
                poof.scale(0.9F + random.nextFloat() * 0.5F);
                poof.setLifetime(9 + random.nextInt(5));
            }
        }
    }

    /** channel 已经结束但还在保持期：只留一点逐渐变淡的水花，不再推进水墙。 */
    private static void spawnTrailingWake(Minecraft mc, PokemonEntity rider) {
        if (rider == null) return;
        Vec3 camera = cameraPosition(mc);
        if (rider.position().distanceToSqr(camera) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        if (random.nextInt(2) != 0) return;
        Vec3 base = rider.position();
        Vec3 dir = forward(rider.getYRot());
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        for (int i = 0; i < 2; i++) {
            double side = (random.nextDouble() - 0.5) * 2.0;
            Vec3 pos = base.add(right.scale(side)).add(dir.scale(-0.3 - random.nextDouble() * 1.2))
                    .add(0, 0.08 + random.nextDouble() * 0.3, 0);
            mc.level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z,
                    0.03, 0.10, 0.03);
        }
    }

    private static void render(WorldRenderContext ctx) {
        if (alpha <= 0.01F) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.matrixStack() == null || ctx.consumers() == null) return;
        PokemonEntity rider = controlledBlastoise(mc);
        if (rider == null) return;

        double partial = mc.isPaused() ? 1.0 : ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        Vec3 feet = new Vec3(Mth.lerp(partial, rider.xo, rider.getX()),
                Mth.lerp(partial, rider.yo, rider.getY()),
                Mth.lerp(partial, rider.zo, rider.getZ()));
        if (feet.distanceToSqr(ctx.camera().getPosition()) > RENDER_DISTANCE_SQR) return;

        Matrix4f matrix = ctx.matrixStack().last().pose();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.entityTranslucentEmissive(WaterEffects.SURFACE));
        Vec3 camera = ctx.camera().getPosition();
        Vec3 base = feet.subtract(camera);
        double yaw = Mth.rotLerp((float) partial, rider.yRotO, rider.getYRot());
        Vec3 dir = forward((float) yaw);
        Vec3 right = new Vec3(-dir.z, 0, dir.x);
        double time = phase + partial * 0.32;

        groundFilm(vc, matrix, base, dir, right, time);
        // 蓄水阶段还立不起浪墙：只有水膜和浪头，冲刺开始后才淡入浪墙。
        if (wall > 0.02F) {
            waterWall(vc, matrix, base, dir, right, time, wall);
            crestFoam(vc, matrix, base, dir, right, time, wall);
            bowSpray(vc, matrix, base, dir, right, time, wall);
        }
    }

    /** 模型正前方：MC 的 yaw=0 朝 +Z，所以前向量是 (-sin, 0, cos)。 */
    private static Vec3 forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** 脚下铺开的一层水膜，从身后 2.0 格一直延伸到浪头，带一点流动的亮度起伏。 */
    private static void groundFilm(VertexConsumer vc, Matrix4f matrix, Vec3 base, Vec3 dir, Vec3 right,
                                  double time) {
        // 蓄水阶段水膜随进度长出来（0.35 → 1.0），冲刺阶段满透明度。
        float film = 0.35F + 0.65F * Mth.clamp(windupProgress, 0.0F, 1.0F);
        int a = alphaByte(140 * alpha * film);
        // 中心水膜
        Vec3 p0 = base.add(right.scale(-1.55)).add(dir.scale(-2.0)).add(0, 0.04, 0);
        Vec3 p1 = base.add(right.scale(-1.30)).add(dir.scale(1.05)).add(0, 0.04, 0);
        Vec3 p2 = base.add(right.scale(1.30)).add(dir.scale(1.05)).add(0, 0.04, 0);
        Vec3 p3 = base.add(right.scale(1.55)).add(dir.scale(-2.0)).add(0, 0.04, 0);
        WaterEffects.quad(vc, matrix, p0, p1, p2, p3, 110, 190, 235, a);
        // 外圈更亮的一层，让水膜边缘有厚度。
        double ripple = 0.06 * Math.sin(time * 1.4);
        Vec3 q0 = base.add(right.scale(-1.85)).add(dir.scale(-2.2 + ripple)).add(0, 0.03, 0);
        Vec3 q1 = base.add(right.scale(-1.45)).add(dir.scale(1.20)).add(0, 0.03, 0);
        Vec3 q2 = base.add(right.scale(1.45)).add(dir.scale(1.20)).add(0, 0.03, 0);
        Vec3 q3 = base.add(right.scale(1.85)).add(dir.scale(-2.2 - ripple)).add(0, 0.03, 0);
        WaterEffects.quad(vc, matrix, q0, q1, q2, q3, 165, 220, 250, alphaByte(70 * alpha));
    }

    /** 三层浪面：内层亮蓝、外层更淡更高，轮廓不会像一块平贴图。 */
    private static void waterWall(VertexConsumer vc, Matrix4f matrix, Vec3 base, Vec3 dir, Vec3 right,
                                 double time, float amount) {
        for (int layer = 0; layer < LAYERS; layer++) {
            Vec3 layerOffset = dir.scale(layer * -0.18);
            double extraHeight = layer * 0.12;
            int red = layer == 0 ? 120 : (layer == 1 ? 190 : 214);
            int green = layer == 0 ? 200 : (layer == 1 ? 236 : 246);
            for (int i = 0; i < LATERAL_SEGMENTS; i++) {
                double t0 = -HALF_WIDTH + 2 * HALF_WIDTH * i / LATERAL_SEGMENTS;
                double t1 = -HALF_WIDTH + 2 * HALF_WIDTH * (i + 1) / LATERAL_SEGMENTS;
                double h0 = crestHeight(t0, time) + extraHeight;
                double h1 = crestHeight(t1, time) + extraHeight;
                for (int j = 0; j < HEIGHT_SEGMENTS; j++) {
                    double u0 = (double) j / HEIGHT_SEGMENTS;
                    double u1 = (double) (j + 1) / HEIGHT_SEGMENTS;
                    Vec3 a = wavePoint(base, dir, right, t0, u0, h0, time).add(layerOffset);
                    Vec3 b = wavePoint(base, dir, right, t1, u0, h1, time).add(layerOffset);
                    Vec3 c = wavePoint(base, dir, right, t1, u1, h1, time).add(layerOffset);
                    Vec3 d = wavePoint(base, dir, right, t0, u1, h0, time).add(layerOffset);
                    // 越往上越淡，浪根处更实，读起来像一堵立起来的水。
                    double layerAlpha = layer == 0 ? 205 : (layer == 1 ? 120 : 80);
                    int a0 = alphaByte(layerAlpha * alpha * amount * (1.0 - 0.45 * u0));
                    WaterEffects.quad(vc, matrix, a, b, c, d, red, green, 255, a0);
                }
            }
        }
    }

    /** 浪尖的白色泡沫带；比墙略高一点，让顶部有厚实的卷边。 */
    private static void crestFoam(VertexConsumer vc, Matrix4f matrix, Vec3 base, Vec3 dir, Vec3 right,
                                  double time, float amount) {
        int a = alphaByte(230 * alpha * amount);
        for (int i = 0; i < LATERAL_SEGMENTS; i++) {
            double t0 = -HALF_WIDTH + 2 * HALF_WIDTH * i / LATERAL_SEGMENTS;
            double t1 = -HALF_WIDTH + 2 * HALF_WIDTH * (i + 1) / LATERAL_SEGMENTS;
            double h0 = crestHeight(t0, time);
            double h1 = crestHeight(t1, time);
            Vec3 a0 = wavePoint(base, dir, right, t0, 1.0, h0, time);
            Vec3 b0 = wavePoint(base, dir, right, t1, 1.0, h1, time);
            Vec3 c0 = wavePoint(base, dir, right, t1, 1.0, h1, time).add(dir.scale(-0.20)).add(0, 0.16, 0);
            Vec3 d0 = wavePoint(base, dir, right, t0, 1.0, h0, time).add(dir.scale(-0.20)).add(0, 0.16, 0);
            WaterEffects.quad(vc, matrix, a0, b0, c0, d0, 235, 250, 255, a);
        }
    }

    /** 浪头两侧向外翻的白色水翼，从正前方看能明显感到"在水里冲"。 */
    private static void bowSpray(VertexConsumer vc, Matrix4f matrix, Vec3 base, Vec3 dir, Vec3 right,
                                 double time, float amount) {
        int a = alphaByte(150 * alpha * amount);
        for (int sideSign = -1; sideSign <= 1; sideSign += 2) {
            double flutter = 0.10 * Math.sin(time * 2.1 + sideSign);
            Vec3 root = base.add(dir.scale(WALL_FORWARD + 0.25))
                    .add(right.scale(sideSign * (HALF_WIDTH - 0.15)))
                    .add(0, 0.12, 0);
            Vec3 tip = root.add(right.scale(sideSign * (0.75 + flutter)))
                    .add(dir.scale(0.55)).add(0, 0.55 + flutter, 0);
            Vec3 inner = root.add(dir.scale(0.9)).add(0, 0.05, 0);
            WaterEffects.quad(vc, matrix, root, tip, tip.add(0, 0.22, 0), inner, 215, 245, 255, a);
            WaterEffects.quad(vc, matrix, tip, tip.add(0, 0.30, 0),
                    tip.add(0, -0.05, 0).add(dir.scale(-0.25)),
                    tip.add(0, 0.12, 0).add(dir.scale(-0.25)), 240, 252, 255, a);
        }
    }

    /** 浪面参数化：u=0 在脚边、u=1 在浪尖，越往上越向前卷（breaking wave 的形状）。 */
    private static Vec3 wavePoint(Vec3 base, Vec3 dir, Vec3 right, double lateral, double u, double height,
                                  double time) {
        double forward = WALL_FORWARD + WALL_CURL * u * u + 0.07 * Math.sin(time + lateral * 1.7);
        return base.add(right.scale(lateral)).add(dir.scale(forward)).add(0, height * u, 0);
    }

    /** 中间高、两边低的浪顶轮廓，再叠一点随相位滚动的起伏。 */
    private static double crestHeight(double lateral, double time) {
        double profile = 0.68 + 0.32 * Math.cos(lateral / HALF_WIDTH * Math.PI * 0.5);
        return WALL_HEIGHT * profile + 0.06 * Math.sin(time * 1.7 + lateral * 2.4);
    }

    private static int alphaByte(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}

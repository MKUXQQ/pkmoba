package com.pkmoba.client;

import com.cobblemon.mod.common.client.particle.BedrockParticleOptionsRepository;
import com.cobblemon.mod.common.client.particle.ParticleStorm;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.Skill;
import com.pkmoba.network.TornadoPayload;
import com.pkmoba.particle.PkmobaParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 喷火龙 W「火焰旋涡」：复用 Cobblemon 的 firespin 火焰材质/参数动画，
 * 再叠一层原版火星、灰烬和底部炽热烟。
 *
 * <p>0.38.4 起服务端给 W 加了前摇，客户端表现按三段走：
 * <ol>
 *   <li><b>前摇</b>：{@link ClientState#getChannel()} == 2 且物种是喷火龙时，在锁定落点播放
 *       "聚火球 + 地面法阵渐显 + 火星螺旋吸入"，12 tick（0.6s）内不出现完整旋涡；</li>
 *   <li><b>成形</b>：收到 {@link TornadoPayload} 后 0.35s（7 tick）内贴地环由 0.4R 弹到 1.15R、
 *       亮度拉到峰值、外围火星外扩；</li>
 *   <li><b>持续</b>：转入原有的层状粒子循环，直到 payload.durationTicks 结束。</li>
 * </ol>
 *
 * <p>成形后的半径一律读 {@link TornadoPayload#radius()}（0.38.4 服务端由 7.0 减小到 4.5），
 * 客户端只做 0.5~16 的合理钳制，并按半径减层/减粒子，避免"小半径铺 3 层 × 2 臂"的虚胖。
 */
public final class TornadoEffects {
    private static final ResourceLocation FIRE = ResourceLocation.parse("cobblemon:firespin_target");
    private static final ResourceLocation CINDER = ResourceLocation.parse("cobblemon:firespin_actorcinder");
    /** 同时存在的旋涡上限。 */
    private static final int MAX_ACTIVE = 8;
    /** payload 半径钳制区间：0.38.4 服务端默认 4.5，留出头给后续调参。 */
    private static final double MIN_RADIUS = 0.5;
    private static final double MAX_RADIUS = 16.0;
    /** Cobblemon 火焰层：满配 3 层 × 2 臂，半径变小按 {@link #FULL_LAYER_RADIUS} 减层，避免"虚胖"。 */
    private static final int MAX_TIERS = 3;
    private static final int MAX_ARMS = 2;
    /** 铺满 3 层 × 2 臂的参考半径：0.38.2 的 7 格；4.5 格只铺 2 层 × 2 臂。 */
    private static final double FULL_LAYER_RADIUS = 7.0;
    /** 成形爆发时长：0.35s = 7 tick（payload 到达后先弹环 + 过曝，再转入稳定循环）。 */
    private static final int IMPACT_TICKS = 7;
    private static final List<Vortex> ACTIVE = new ArrayList<>();
    /** 喷火龙 E 飞行（服务端复用 channel 3 表示"正在飞"）的尾迹状态。 */
    private static final int FLIGHT_CHANNEL = 3;
    private static int flightEntityId = -1;
    private static int flightTicks;
    private static Vec3 flightPrev = Vec3.ZERO;

    /** 喷火龙 W 的前摇通道与时长（对齐服务端 CharizardCombat.W_WINDUP_TICKS = 12）。 */
    private static final int WINDUP_CHANNEL = 2;
    private static final int WINDUP_TICKS = 12;
    /**
     * 前摇没收到 radius 预告时的兜底半径，对齐服务端 W_RADIUS
     * （0.38.4 由 7.0 减小到 4.5）；收到预告后按预告半径的绝对值走。
     */
    private static final double WINDUP_RADIUS = 4.5;
    /** 拿不到瞄准点 / 预告丢包时，沿实体正前方的兜底落点距离（格）。 */
    private static final double WINDUP_FALLBACK_DIST = 4.5;
    /** 前摇走完后等正半径 payload 的宽限 tick（payload 通常晚于 channel 0 一两帧），超时才当被打断。 */
    private static final int RELEASE_GRACE_TICKS = 4;
    /** 负半径预告的有效期：太久没用掉就丢弃，避免上个技能的落点串到下一次前摇。 */
    private static final int PREVIEW_TTL_TICKS = 20;
    /** 粒子只在距离本机玩家 128 格内生成。 */
    private static final double RENDER_DISTANCE_SQR = 128 * 128;

    // ---- W 前摇（channel 2）状态：同一时刻本地只有一个受控喷火龙，单例即可 ----
    private static boolean windupActive;
    private static int windupTicks;
    private static int windupEntityId = -1;
    /** 前摇起手瞬间锁定的落点：前摇过程中不追鼠标，地面法阵要稳。 */
    private static Vec3 windupCenter = Vec3.ZERO;
    /** 落点是否来自服务端负半径预告（而不是本地兜底）。 */
    private static boolean windupCenterFromPayload;
    /** 前摇半径：来自预告 payload 的绝对值，兜底用 {@link #WINDUP_RADIUS}。 */
    private static double windupRadius = WINDUP_RADIUS;
    /** 前摇结束后等待正半径 payload 的剩余宽限 tick。 */
    private static int releaseGrace;

    /** 负半径预告缓存：payload 可能比 channel 2 观测到得早，先存着等前摇取用。 */
    private static boolean previewValid;
    private static Vec3 previewCenter = Vec3.ZERO;
    private static double previewRadius = WINDUP_RADIUS;
    private static int previewEntityId = -1;
    private static int previewAge;

    private TornadoEffects() { }

    public static void init() {
        // PkmobaClient 不在本次可改范围，Q 火焰的状态机从火焰家族这里串起来注册。
        FlameJetEffects.init();
        ClientPlayNetworking.registerGlobalReceiver(TornadoPayload.TYPE, (p, context) ->
                context.client().execute(() -> {
                    ClientLevel level = context.client().level;
                    if (level == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y())
                            || !Double.isFinite(p.z()) || !Double.isFinite(p.radius())) return;
                    // 0.38.4 协议：radius < 0（durationTicks = 0）= W 前摇落点预告。
                    // 只记录落点和半径，绝不生成旋涡、不做成形爆发，也不能当成 duration 0 的旋涡立刻清掉。
                    if (p.radius() < 0.0) {
                        previewValid = true;
                        previewEntityId = p.entityId();
                        previewCenter = new Vec3(p.x(), p.y(), p.z());
                        previewRadius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, -p.radius()));
                        previewAge = 0;
                        return;
                    }
                    if (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0).clear();
                    ACTIVE.add(new Vortex(level, p.entityId(), new Vec3(p.x(), p.y(), p.z()),
                            Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, Math.abs(p.radius()))),
                            Math.max(1, Math.min(100, p.durationTicks()))));
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> ACTIVE.removeIf(v -> !v.tick(client)));
        ClientTickEvents.END_CLIENT_TICK.register(TornadoEffects::tickFlight);
        ClientTickEvents.END_CLIENT_TICK.register(TornadoEffects::tickWindup);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ACTIVE.forEach(Vortex::clear);
            ACTIVE.clear();
            flightEntityId = -1;
            flightTicks = 0;
            resetWindup();
            clearPreview();
        });
    }

    /**
     * 喷火龙 E「冲刺 / 飞行」的尾迹（服务端 {@code stateChannel = dashTicks > 0 ? 3 : channel}）。
     *
     * <p>channel 3 在两只宝可梦之间是复用的：水箭龟那边是 W 旋转（payload 驱动，已经有效果），
     * 所以这里先按物种把水箭龟排除掉。喷火龙飞行只靠服务端位移、本身没有任何客户端表现，
     * 加一条火焰翼尾迹 + 起飞火星，让 E 看得出来"甩出去了"。
     */
    private static void tickFlight(Minecraft mc) {
        if (mc.level == null) {
            flightEntityId = -1;
            flightTicks = 0;
            return;
        }
        if (ClientState.getChannel() != FLIGHT_CHANNEL) {
            flightEntityId = -1;
            flightTicks = 0;
            return;
        }
        int id = ClientState.getCharizardId();
        PokemonEntity entity = id >= 0 && mc.level.getEntity(id) instanceof PokemonEntity pokemon
                && pokemon.isAlive() ? pokemon : null;
        if (entity == null || Skill.isBlastoise(entity)) {
            flightEntityId = -1;
            flightTicks = 0;
            return;
        }
        if (flightEntityId != id) {
            flightEntityId = id;
            flightTicks = 0;
            flightPrev = entity.position();
            ignition(mc, entity);
        }
        flightTicks++;
        Vec3 position = entity.position();
        Vec3 velocity = position.subtract(flightPrev);
        flightPrev = position;
        double speed = velocity.length();
        if (speed < 0.02) return;
        if (mc.player == null || entity.distanceToSqr(mc.player) > 128 * 128) return;
        Vec3 back = velocity.scale(-1.0 / speed);
        double yaw = Math.toRadians(entity.getYRot());
        Vec3 modelRight = new Vec3(Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 modelUp = new Vec3(0, 1, 0);
        var random = mc.level.random;
        // 两侧翼尖各拖一条火线（每 tick 2 颗，量级可控）。
        for (int side = -1; side <= 1; side += 2) {
            Vec3 wing = position.add(modelRight.scale(side * 0.62)).add(modelUp.scale(0.95))
                    .add(back.scale(0.25));
            Vec3 velocityOut = back.scale(0.10 + random.nextDouble() * 0.08)
                    .add(modelRight.scale(side * 0.02))
                    .add(0, 0.02 + random.nextDouble() * 0.03, 0);
            mc.level.addParticle(ParticleTypes.SMALL_FLAME,
                    wing.x + (random.nextDouble() - 0.5) * 0.2,
                    wing.y + (random.nextDouble() - 0.5) * 0.2,
                    wing.z + (random.nextDouble() - 0.5) * 0.2,
                    velocityOut.x, velocityOut.y, velocityOut.z);
        }
        if (flightTicks % 3 == 0) {
            Vec3 core = position.add(0, 0.55, 0).add(back.scale(0.35));
            var puff = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF_SMALL,
                    core.x, core.y, core.z, back.x * 0.05, 0.03, back.z * 0.05);
            if (puff != null) {
                puff.scale(0.7F + random.nextFloat() * 0.5F);
                puff.setLifetime(7 + random.nextInt(4));
            }
        }
        if (flightTicks % 4 == 0) {
            Vec3 trail = position.add(back.scale(0.6)).add(0, 0.9, 0);
            var smoke = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    trail.x, trail.y, trail.z, back.x * 0.04, 0.03, back.z * 0.04);
            if (smoke != null) {
                smoke.setColor(0.30F, 0.26F, 0.26F);
                smoke.scale(1.0F + random.nextFloat() * 0.5F);
                smoke.setLifetime(12 + random.nextInt(8));
            }
        }
        if (flightTicks % 6 == 0) {
            // 偶尔一撮亮火星，风切面质感。
            Vec3 spark = position.add(back.scale(0.4)).add(
                    (random.nextDouble() - 0.5) * 1.1, 0.4 + random.nextDouble() * 0.9,
                    (random.nextDouble() - 0.5) * 1.1);
            mc.level.addParticle(ParticleTypes.FLAME, spark.x, spark.y, spark.z,
                    back.x * 0.06, 0.05, back.z * 0.06);
        }
    }

    /** 起飞瞬间：脚下与身后一圈火星 + 一团高亮火焰。 */
    private static void ignition(Minecraft mc, PokemonEntity entity) {
        if (mc.level == null) return;
        Vec3 base = entity.position();
        var random = mc.level.random;
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = 0.4 + random.nextDouble() * 0.9;
            mc.level.addParticle(ParticleTypes.FLAME,
                    base.x + Math.cos(angle) * radius, base.y + 0.1 + random.nextDouble() * 0.5,
                    base.z + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.05, 0.12, Math.sin(angle) * 0.05);
        }
        var heart = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF,
                base.x, base.y + 0.6, base.z, 0, 0.04, 0);
        if (heart != null) {
            heart.scale(1.3F);
            heart.setLifetime(9);
        }
    }

    // ============================================================ W 前摇 / 成形
    //
    // 0.38.4 服务端把 W 改成"按键 -> channel 2 蓄力 12t(0.6s) -> 落区并广播正半径 payload"，
    // 客户端因此拆三段：
    //   前摇（channel 2，t=0..11）：落点处聚火球 + 地面法阵渐显 + 火星螺旋吸入，不出现完整旋涡；
    //   成形（正半径 payload 后 t=0..6）：7t(0.35s) 内贴地环由 0.4R 弹到 1.0R、亮度拉峰值、火星外扩；
    //   持续（t=7 起）：交给 {@link Vortex} 原有的层状粒子循环，直到 durationTicks 结束。
    //
    // 落点来源（按优先级）：服务端负半径预告 payload > 实体正前方 WINDUP_FALLBACK_DIST 格兜底。

    /** 每客户端 tick 推进 W 前摇状态机；channel 2 结束 / payload 到达 / 断线都会安全复位。 */
    private static void tickWindup(Minecraft mc) {
        if (mc.level == null) {
            resetWindup();
            return;
        }
        // 预告只在"还没被前摇取用"时计时，太久没用掉就丢掉。
        if (previewValid && ++previewAge > PREVIEW_TTL_TICKS) {
            clearPreview();
        }
        PokemonEntity entity = controlled(mc);
        boolean channeling = entity != null && entity.isAlive()
                && ClientState.getChannel() == WINDUP_CHANNEL && !Skill.isBlastoise(entity);
        if (!channeling) {
            stopWindup(mc);
            // 正半径 payload 常比 channel 0 晚一两帧到：宽限期内等它，否则会把正常成形当成"被打断"。
            if (releaseGrace > 0) {
                if (freshVortex(ClientState.getCharizardId())) {
                    releaseGrace = 0;
                } else if (--releaseGrace <= 0) {
                    resetWindup();
                }
            }
            return;
        }
        int id = ClientState.getCharizardId();
        if (!windupActive || windupEntityId != id) {
            resetWindup();
            windupActive = true;
            windupEntityId = id;
            windupTicks = 0;
            if (previewValid && previewEntityId == id) {
                windupCenter = previewCenter;
                windupRadius = previewRadius;
                windupCenterFromPayload = true;
                clearPreview();
            } else {
                windupCenter = fallbackCenter(entity);
                windupRadius = WINDUP_RADIUS;
                windupCenterFromPayload = false;
            }
        } else if (!windupCenterFromPayload && previewValid && previewEntityId == id) {
            // 预告比 channel 2 观测晚到：前摇已经用兜底点起手，这里吸附到真正的落点（只吸一次）。
            windupCenter = previewCenter;
            windupRadius = previewRadius;
            windupCenterFromPayload = true;
            clearPreview();
        }
        windupTicks++;
        // 进度以本地 tick 数为主；服务端若也给 channel 2 下发 charge，就取较大值，两端不打架。
        double progress = Math.min(1.0, Math.max(windupTicks / (double) WINDUP_TICKS, ClientState.getCharge()));
        spawnWindup(mc, progress);
    }

    /** channel 2 结束：走满前摇的进宽限期等 payload；没走满的当被打断，原地小范围熄灭。 */
    private static void stopWindup(Minecraft mc) {
        if (!windupActive) return;
        int ticks = windupTicks;
        Vec3 center = windupCenter;
        double radius = windupRadius;
        windupActive = false;
        windupTicks = 0;
        windupEntityId = -1;
        if (ticks >= WINDUP_TICKS - 4) {
            releaseGrace = RELEASE_GRACE_TICKS;
        } else {
            fizzle(mc, center, radius);
            resetWindup();
        }
    }

    private static void resetWindup() {
        windupActive = false;
        windupTicks = 0;
        windupEntityId = -1;
        windupCenter = Vec3.ZERO;
        windupCenterFromPayload = false;
        windupRadius = WINDUP_RADIUS;
        releaseGrace = 0;
    }

    private static void clearPreview() {
        previewValid = false;
        previewCenter = Vec3.ZERO;
        previewRadius = WINDUP_RADIUS;
        previewEntityId = -1;
        previewAge = 0;
    }

    /** 当前受控宝可梦（喷火龙 / 水箭龟共用同一套状态同步），没有则 null。 */
    private static PokemonEntity controlled(Minecraft mc) {
        if (mc.level == null) return null;
        int id = ClientState.getCharizardId();
        if (id < 0) return null;
        return mc.level.getEntity(id) instanceof PokemonEntity pokemon ? pokemon : null;
    }

    /** 兜底落点：实体正前方 4.5 格（预告丢包 / 老服务端时用，行为不崩）。 */
    private static Vec3 fallbackCenter(PokemonEntity entity) {
        return entity.position().add(horizontalForward(entity.getYRot()).scale(WINDUP_FALLBACK_DIST));
    }

    /** 模型水平正前方（与 CharizardCombat.modelForward 同一套 yaw 约定）。 */
    private static Vec3 horizontalForward(float yRot) {
        double yaw = Math.toRadians(yRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        return forward.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : forward.normalize();
    }

    /** 还处在成形阶段（age <= 2）的本体旋涡：用来区分"正常释放"和"前摇被打断"。 */
    private static boolean freshVortex(int entityId) {
        for (Vortex vortex : ACTIVE) {
            if (vortex.entityId == entityId && vortex.age <= 2) return true;
        }
        return false;
    }

    /** 前摇蓄力：中心火球由小到大 + 地面法阵渐显 + 外圈火星螺旋吸入，进度越接近 1 越亮越密。 */
    private static void spawnWindup(Minecraft mc, double progress) {
        if (mc.player == null || mc.player.distanceToSqr(windupCenter) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        Vec3 center = windupCenter;
        double radius = windupRadius;
        // 1) 中心火球：从小火星团长成火核，随蓄力慢慢抬离地面。
        Vec3 core = center.add(0, 0.30 + progress * 0.70, 0);
        if (windupTicks % 2 == 0) {
            var puff = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF,
                    core.x, core.y, core.z, 0, 0.012, 0);
            if (puff != null) {
                puff.scale(0.35F + (float) progress * 1.0F);
                puff.setLifetime(6 + (int) (progress * 5));
            }
        }
        // 2) 地面法阵：环随蓄力从 0.35R 撑到 1.0R，点数 / 亮度同步增加。
        int ringPoints = 8 + (int) Math.round(progress * 10);
        double ringRadius = radius * (0.35 + 0.65 * progress);
        for (int i = 0; i < ringPoints; i++) {
            double angle = i / (double) ringPoints * Math.PI * 2 + windupTicks * 0.10;
            Vec3 point = center.add(Math.cos(angle) * ringRadius, 0.05, Math.sin(angle) * ringRadius);
            glow(mc, ParticleTypes.SMALL_FLAME, point, new Vec3(0, 0.012 + progress * 0.02, 0),
                    0.7F + (float) progress * 0.6F);
        }
        // 3) 吸入火星：从外圈螺旋收缩向中心，读起来才是"把火吸进来"而不是原地冒烟。
        int sparks = 2 + (int) Math.round(progress * 6);
        for (int i = 0; i < sparks; i++) {
            double angle = random.nextDouble() * Math.PI * 2 + windupTicks * 0.35;
            double shell = radius * (1.10 - 0.30 * progress) * (0.60 + random.nextDouble() * 0.70);
            Vec3 from = center.add(Math.cos(angle) * shell,
                    0.15 + random.nextDouble() * (0.60 + progress * 1.20),
                    Math.sin(angle) * shell);
            Vec3 toCore = core.subtract(from);
            double len = toCore.length();
            if (len < 0.05) continue;
            // 切向分量做出螺旋，不是直线砸进中心。
            Vec3 tangent = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(0.06);
            Vec3 velocity = toCore.scale((0.18 + progress * 0.20) / len).add(tangent);
            glow(mc, random.nextInt(3) == 0 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME,
                    from, velocity, 0.8F + (float) progress * 0.7F);
        }
        // 4) 法阵边缘暗红烟：远处也能一眼看出"这里在聚能"。
        if (windupTicks % 3 == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            Vec3 point = center.add(Math.cos(angle) * ringRadius, 0.06, Math.sin(angle) * ringRadius);
            var smoke = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                    point.x, point.y, point.z, Math.cos(angle) * 0.02, 0.03, Math.sin(angle) * 0.02);
            if (smoke != null) {
                smoke.setColor(0.40F, 0.16F, 0.10F);
                smoke.scale(1.2F + (float) progress * 0.8F);
                smoke.setLifetime(12 + random.nextInt(8));
            }
        }
        // 5) 蓄满前一刻的熔岩亮斑，预告"马上要落下来"。
        if (progress > 0.82 && random.nextInt(3) == 0) {
            glow(mc, ParticleTypes.LAVA, core, new Vec3(0, 0.03, 0), 1.1F);
        }
    }

    /** 前摇被打断：小范围熄灭（不炸），让"取消"和"放出去"手感可分。 */
    private static void fizzle(Minecraft mc, Vec3 center, double radius) {
        if (mc.level == null || mc.player == null
                || mc.player.distanceToSqr(center) > RENDER_DISTANCE_SQR) return;
        var random = mc.level.random;
        for (int i = 0; i < 8; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 0.4 + random.nextDouble() * radius * 0.5;
            Vec3 from = center.add(Math.cos(angle) * r, 0.2 + random.nextDouble() * 0.6, Math.sin(angle) * r);
            glow(mc, ParticleTypes.SMOKE, from, new Vec3(0, 0.04, 0), 1.0F + random.nextFloat() * 0.4F);
        }
    }

    /** 生成一颗带尺寸的原版粒子；粒子引擎在远处会丢弃，返回 null 时安全跳过。 */
    private static void glow(Minecraft mc, ParticleOptions type, Vec3 pos, Vec3 velocity, float scale) {
        var particle = mc.particleEngine.createParticle(type, pos.x, pos.y, pos.z,
                velocity.x, velocity.y, velocity.z);
        if (particle != null) particle.scale(scale);
    }

    /** 1 - (1-t)^3：环弹出先快后慢，看起来是"弹"出去而不是匀速撑开。 */
    private static double easeOutCubic(double t) {
        double inv = 1.0 - Math.max(0.0, Math.min(1.0, t));
        return 1.0 - inv * inv * inv;
    }

    private static final class Vortex {
        final ClientLevel level;
        final int entityId;
        final Vec3 center;
        final double radius;
        final int duration;
        /** 按半径决定的层数 / 臂数：4.5 格只铺 2 层 × 2 臂，7 格才铺满 3 层。 */
        final int tiers;
        final int arms;
        final List<Layer> layers = new ArrayList<>();
        int age;

        Vortex(ClientLevel level, int entityId, Vec3 center, double radius, int duration) {
            this.level = level; this.entityId = entityId; this.center = center;
            this.radius = radius; this.duration = duration;
            this.tiers = radius >= FULL_LAYER_RADIUS * 0.85 ? MAX_TIERS : radius >= 3.2 ? 2 : 1;
            this.arms = radius >= 2.4 ? MAX_ARMS : 1;
        }

        boolean tick(Minecraft client) {
            if (client.level != level || ClientState.getCharizardId() != entityId
                    || level.getEntity(entityId) == null || age >= duration) {
                clear();
                return false;
            }
            // 复用效果本身1.1秒发射/0.6秒粒子寿命，每22tick补一批，不无限积累。
            layers.removeIf(layer -> !layer.storm().isAlive());
            if (age % 22 == 0 && age < duration - 12) {
                for (int tier = 0; tier < tiers; tier++) {
                    for (int arm = 0; arm < arms; arm++) {
                        ParticleStorm storm = spawn(FIRE, center);
                        if (storm != null) layers.add(new Layer(storm, tier, arm));
                    }
                }
            }
            // 成形阶段（payload 后 0.35s）：环由 0.4R 弹到 1.0R，亮度 2t 内拉到峰值。
            double form = age < IMPACT_TICKS
                    ? 0.40 + 0.60 * easeOutCubic(age / (double) (IMPACT_TICKS - 1))
                    : 1.0;
            double ramp = age < IMPACT_TICKS ? 2.0 : 8.0;
            double envelope = Math.min(1, (age + 1) / ramp) * Math.min(1, (duration - age) / 12.0);
            for (Layer layer : layers) {
                double t = tiers > 1 ? layer.tier() / (double) (tiers - 1) : 1.0;
                // 底部收束、上层展开；层高按半径缩放，7 格旋涡才会是"柱"而不是"平摊"。
                double r = (0.15 + t * Math.max(0, radius - 1.5)) * envelope * form;
                double angle = age * 0.16 + layer.arm() * Math.PI + t * 2.1;
                double lift = t * (1.2 + radius * 0.55);
                // 成形时柱子从地面"长"起来，不是整根瞬移出现。
                Vec3 pos = center.add(Math.cos(angle) * r, 0.1 + lift * (0.55 + 0.45 * form),
                        Math.sin(angle) * r);
                PoseStack pose = new PoseStack();
                pose.translate(pos.x, pos.y, pos.z);
                layer.storm().getEmitterSpaceMatrix().updateMatrix(pose.last().pose());
            }
            if (age < IMPACT_TICKS) {
                impact(client, form);
            }
            if (age % 4 == 0 && age < duration - 5) {
                double angle = age * 0.3;
                spawn(CINDER, center.add(Math.cos(angle) * radius * 0.55 * form, 0.5,
                        Math.sin(angle) * radius * 0.55 * form));
            }
            // 原版粒子补强：只对 128 格内的玩家生成，数量按半径缩放，量级可控。
            sprinkle(client, envelope);
            age++;
            return true;
        }

        /**
         * 成形爆发（age 0..6，共 0.35s）：贴地冲击环 0.4R -> 1.15R、中心过曝、外围火星外扩、地面烟环。
         * 只在这 7 tick 里放，之后完全交给上面的稳定循环，不额外堆积粒子。
         */
        private void impact(Minecraft client, double form) {
            if (client.player == null || client.player.distanceToSqr(center) > RENDER_DISTANCE_SQR) return;
            var random = level.random;
            // 1) 贴地冲击环：半径按 form 弹出，粒子数随之增加。
            int ring = 14 + (int) Math.round(10 * form);
            double ringRadius = radius * form * 1.15;
            for (int i = 0; i < ring; i++) {
                double angle = i / (double) ring * Math.PI * 2 + age * 0.20;
                Vec3 pos = center.add(Math.cos(angle) * ringRadius, 0.06, Math.sin(angle) * ringRadius);
                Vec3 outward = new Vec3(Math.cos(angle), 0.10, Math.sin(angle))
                        .normalize().scale(0.05 + 0.12 * form);
                glow(client, ParticleTypes.FLAME, pos, outward, 1.0F + (float) form * 0.9F);
            }
            // 2) 中心过曝：头两 tick 的熔岩亮斑，把"砰"的那一下打亮。
            if (age < 2) {
                for (int i = 0; i < 6; i++) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double r = radius * 0.30 * random.nextDouble();
                    Vec3 pos = center.add(Math.cos(angle) * r, 0.15 + random.nextDouble() * 0.70,
                            Math.sin(angle) * r);
                    glow(client, random.nextInt(2) == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME,
                            pos, new Vec3(0, 0.05, 0), 1.2F);
                }
            }
            // 3) 外围火星外扩：从环上甩出去，速度随 form 加大。
            int sparks = 6 + (int) Math.round(8 * form);
            for (int i = 0; i < sparks; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double r = radius * (0.30 + random.nextDouble() * 0.35) * form;
                Vec3 pos = center.add(Math.cos(angle) * r, 0.10 + random.nextDouble() * 0.40,
                        Math.sin(angle) * r);
                Vec3 outward = new Vec3(Math.cos(angle), 0.16 + random.nextDouble() * 0.22, Math.sin(angle))
                        .normalize().scale(0.10 + random.nextDouble() * 0.14);
                glow(client, random.nextInt(3) == 0 ? ParticleTypes.LAVA : ParticleTypes.SMALL_FLAME,
                        pos, outward, 0.9F + random.nextFloat() * 0.6F);
            }
            // 4) 地面烟环：每 2 tick 一圈向外推的暗红烟，撑出"半径真的这么大"的体积感。
            if (age % 2 == 0) {
                for (int i = 0; i < 10; i++) {
                    double angle = i / 10.0 * Math.PI * 2 + age * 0.10;
                    Vec3 pos = center.add(Math.cos(angle) * ringRadius, 0.05, Math.sin(angle) * ringRadius);
                    var smoke = client.particleEngine.createParticle(ParticleTypes.CLOUD,
                            pos.x, pos.y, pos.z, Math.cos(angle) * 0.10, 0.02, Math.sin(angle) * 0.10);
                    if (smoke != null) {
                        smoke.setColor(0.42F, 0.16F, 0.09F);
                        smoke.scale(1.8F + (float) form * 0.9F);
                        smoke.setLifetime(12 + random.nextInt(6));
                    }
                }
            }
            // 5) 成形末段补一团余烬，和 22t 一次的常规批次错开。
            if (age == IMPACT_TICKS - 2) {
                spawn(CINDER, center.add(0, 0.6, 0));
            }
        }

        /** 火星 / 灰烬 / 底部热烟：让旋涡的边缘和地面接触处不空。 */
        private void sprinkle(Minecraft client, double envelope) {
            if (client.player == null || envelope <= 0.02) return;
            if (client.player.distanceToSqr(center) > RENDER_DISTANCE_SQR) return;
            var random = level.random;
            // 数量按半径缩放：4.5 格约 4 颗、7 格 6 颗，小半径不会比大半径还"虚胖"。
            int count = 2 + (int) Math.round(envelope * radius * 0.55);
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double ring = radius * (0.35 + random.nextDouble() * 0.62);
                double height = random.nextDouble() * (1.0 + radius * 0.60);
                Vec3 pos = center.add(Math.cos(angle) * ring, 0.08 + height, Math.sin(angle) * ring);
                // 切向速度：粒子沿旋涡绕圈，而不是直上直下。
                Vec3 tangent = new Vec3(-Math.sin(angle), 0.55 + random.nextDouble() * 0.5, Math.cos(angle))
                        .normalize().scale(0.10 + random.nextDouble() * 0.10);
                var particle = client.particleEngine.createParticle(ParticleTypes.FLAME,
                        pos.x, pos.y, pos.z, tangent.x, tangent.y, tangent.z);
                if (particle != null) particle.scale(1.1F + random.nextFloat() * 0.9F);
            }
            // 地面灼烧圈：贴着中心向外扩散的一圈暗红烟。
            if (age % 2 == 0) {
                double angle = random.nextDouble() * Math.PI * 2;
                Vec3 pos = center.add(Math.cos(angle) * radius * 0.92, 0.06, Math.sin(angle) * radius * 0.92);
                var smoke = client.particleEngine.createParticle(ParticleTypes.CLOUD,
                        pos.x, pos.y, pos.z, -Math.sin(angle) * 0.06, 0.05, Math.cos(angle) * 0.06);
                if (smoke != null) {
                    smoke.setColor(0.42F, 0.18F, 0.10F);
                    smoke.scale(1.6F + random.nextFloat() * 0.8F);
                    smoke.setLifetime(16 + random.nextInt(10));
                }
            }
            // 零星灰烬往上飘，读起来有"烧完的碎屑"。
            if (age % 3 == 0) {
                double angle = random.nextDouble() * Math.PI * 2;
                double ring = radius * random.nextDouble();
                Vec3 pos = center.add(Math.cos(angle) * ring, 0.3 + random.nextDouble() * 1.6,
                        Math.sin(angle) * ring);
                client.level.addParticle(ParticleTypes.SMOKE,
                        pos.x, pos.y, pos.z, 0.01, 0.07, 0.01);
            }
        }

        ParticleStorm spawn(ResourceLocation id, Vec3 pos) {
            var effect = BedrockParticleOptionsRepository.INSTANCE.getEffect(id);
            if (effect == null) return null;
            ParticleStorm storm = ParticleStorm.Companion.createAtPosition(level, effect, pos);
            storm.spawn();
            return storm;
        }

        void clear() {
            for (Layer layer : layers) {
                layer.storm().setInvisible();
                layer.storm().remove();
            }
            layers.clear();
        }
    }

    private record Layer(ParticleStorm storm, int tier, int arm) { }
}

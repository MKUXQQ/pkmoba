package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.AimPayload;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.SkillCastPayload;
import com.pkmoba.network.SkillStatePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import com.pkmoba.network.TornadoPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 喷火龙技能系统（服务端权威）。
 *
 * <p>状态归属：所有可变战斗状态（血量、技能点、冷却、引导、场地龙卷、飞行、弹道）
 * 都放在 {@code Map<UUID, State>} 里，按宝可梦实体 UUID 隔离。
 * 1v1 时两名玩家各操控一只，互不串状态；场地（arena）仍然是全局的，因为同一局共用同一个圈。
 */
public final class CharizardCombat {
    // ---- 0.37 平衡：分物种血量 ----
    // 原实现两只宝可梦统一 100 血，而四技能一轮合计 94~97 点，先手方 5~8 秒就能打完，
    // TTK 过短、看不出拉扯。改成按物种给血量，并把单次命中压到对方血量的约 10%。
    /** 喷火龙最大血量：脆皮高机动，靠位移与爆发换输出。 */
    public static final float CHARIZARD_MAX_HP = 300.0F;
    /** 水箭龟坚韧耐久，配合较慢的技能节奏。 */
    public static final float BLASTOISE_MAX_HP = 340.0F;
    /** 妙蛙花中等偏高耐久，补偿较短射程与低机动。 */
    public static final float VENUSAUR_MAX_HP = 320.0F;
    public static final float PIKACHU_MAX_HP = 280.0F;
    /** 兼容旧调用（无实体上下文时按喷火龙血量）。 */
    public static final float MAX_HP = CHARIZARD_MAX_HP;
    private static final float MAX_POINTS = 10.0F;
    private static final int CD_TICKS = 40;

    /** 按实体取最大血量：喷火龙 280 / 水箭龟 340。 */
    public static float maxHpFor(PokemonEntity entity) {
        var expansion = ExpansionCombat.species(entity);
        if (expansion != null) return expansion.maxHp();
        return CharizardManager.isPikachu(entity) ? PIKACHU_MAX_HP
                : CharizardManager.isBlastoise(entity) ? BLASTOISE_MAX_HP
                : CharizardManager.isVenusaur(entity) ? VENUSAUR_MAX_HP : CHARIZARD_MAX_HP;
    }

    /** 按物种 id 取最大血量（实体已被移除、只剩物种名时用）。 */
    public static float maxHpFor(String speciesId) {
        var expansion = ExpansionRoster.find(speciesId);
        if (expansion != null) return expansion.maxHp();
        return Skill.isPikachu(speciesId) ? PIKACHU_MAX_HP
                : Skill.isBlastoise(speciesId) ? BLASTOISE_MAX_HP
                : Skill.isVenusaur(speciesId) ? VENUSAUR_MAX_HP : CHARIZARD_MAX_HP;
    }

    // ---- 0.37 技能伤害（单次命中 ≤ 对方血量约 10%，四技能一轮 ≤ 45%）----
    /** Q 每跳伤害（严格 5 跳合计 35）。 */
    private static final float Q_TICK_DAMAGE = 7.0F;
    /** W 每跳伤害（10 跳合计 30）。 */
    private static final float W_TICK_DAMAGE = 3.0F;
    /** R 中心命中伤害。 */
    private static final float R_DAMAGE_CENTER = 44.0F;
    /** R 边缘最低伤害（线性衰减）。 */
    private static final float R_DAMAGE_EDGE = 30.0F;

    private static final double Q_RANGE = 11.0;
    private static final double Q_HALF_ANGLE = 20.0;
    /**
     * 引导类技能转向速度：0.38.1 起引导中也只做小幅修正（模型主权在 {@link AimTracker} 的
     * 6°/tick），这里保留常量给"模型被强制定向"的场合使用。
     */
    private static final float Q_TURN_PER_TICK = 30.0F;
    /**
     * Q 前摇：先播动作、再出火。用户反馈"怎么不是动画先出、特效才上"，
     * 所以引导开始后先空转 {@code Q_WINDUP_TICKS}（0.55 秒，嘴部只冒预热火星），
     * 之后才发 JetPayload 播火焰、并开始结算伤害。
     *
     * <p>0.38.3：10 → 11，与客户端 {@code CharizardSkillAnimator.Q_WINDUP_TICKS}
     * （自制动画 q_windup 的时长 0.55s）严格对齐 —— 模型"嘴前伸到位"的那一刻，
     * 服务端同一 tick 开始出火，不会出现"模型还没前倾火已经喷出来"。
     */
    private static final int Q_WINDUP_TICKS = 11;
    /** Q 火焰从嘴部喷出：眼睛锚点沿朝向前移的距离。 */
    private static final double MOUTH_FORWARD = 0.55;
    /**
     * Q 火焰出火点再往前推的距离：用户要求"火焰再往前一点"，让火柱明显离开身体。
     *
     * <p>0.38.2：0.95 → 1.5。0.38.3：1.5 → 1.0。抓火龙 Q 现在有自己的施法动画
     * （q_windup 前伸 + q_sustain 保持），头部骨骼本身已经前探约半格；出火点再推 1.5 格
     * 会和嘴之间露出一段空隙，看起来像"火悬在脸前面"。留 1.0 格正好接在动画的嘴尖上。
     */
    private static final double Q_JET_FORWARD = 0.15;
    /** Q 火焰从嘴部喷出：相对眼睛锚点下移的距离。 */
    private static final double MOUTH_DROP = 0.22;
    /**
     * Q 引导总时长 = 前摇 11 tick + 引导喷吐 20 tick（自制动画 q_sustain 正好 1.0s）。
     *
     * <p>0.38.4：原来写死 60 tick（3 秒），而动画只有 1.55 秒 —— 动画播完后服务端还在
     * 持续发 JetPayload，用户看到的就是"动画结束了还在喷火"。现在服务端节奏与动画严格同长。
     */
    private static final int Q_CHANNEL_TICKS = Q_WINDUP_TICKS + 20;
    /**
     * Q 喷吐期间每多少 tick 结算一次伤害。
     *
     * <p>0.38.4：12 → 4。同样 20 tick 的喷吐窗口里，原来只够结算 2 跳（总伤害从 30 掉到 12），
     * 改成 4 tick 一跳正好 5 跳 × 6 = 30，与调整前总伤害一致，平衡不变。
     */
    private static final int Q_DAMAGE_INTERVAL = 4;

    /**
     * W 火焰旋涡前摇：先蓄力、再落区。用户 m01881："火焰漩涡怎么都没有前摇特效的立马施法了"。
     *
     * <p>12 tick（0.6 秒）：客户端 TornadoEffects 在这段时间播"聚集"特效，第 12 tick 才发
     * {@code TornadoPayload} 播"成形"爆发，与服务端落区/首段伤害同 tick。
     */
    private static final int W_WINDUP_TICKS = 12;

    /**
     * W 火焰旋涡半径。用户 m01030 要求"范围改大"，m01881 又反馈"太大了小一点点"。
     *
     * <p>4.0 → 7.0（0.38.2）→ 4.5（0.38.4）：直径 9 格，仍明显大于 Q 的锥形覆盖，
     * 但不会再"站在圈里几乎无处可逃"。
     */
    private static final double W_RADIUS = 4.5;
    /** W 释放距离：半径变大后同步放宽，否则原地放会喷不到人。 */
    private static final double W_CAST_RANGE = 12.0;
    private static final int W_DURATION_TICKS = 100;

    private static final double E_SPEED_PER_TICK = 0.6; // 12格/秒
    /** 飞翔的水平总路程：明显拉长（15 → 30 格），飞行途中一直造成伤害。 */
    private static final double E_MAX_DISTANCE = 30.0;
    private static final double E_FINISH_DISTANCE = E_MAX_DISTANCE * 0.15;
    private static final int E_ACCEL_TICKS = 4;
    private static final int E_LANDING_TICKS = 6;
    /** 飞翔途中每次接触造成的伤害（同一趟飞行每个目标只算一次）。 */
    /** 飞翔撞击伤害（0.37：16 -> 14，约占对方血量 5%）。 */
    private static final float E_CONTACT_DAMAGE = 20.0F;
    /** 飞翔途中接触判定的额外半径（贴合实体碰撞箱）。 */
    private static final double E_CONTACT_INFLATE = 1.5;

    private static final double R_SPEED_PER_TICK = 0.6;
    private static final double R_MAX_DISTANCE = 20.0;
    /**
     * 弹道<b>碰撞</b>半径（0.39.3）。
     *
     * <p>之前飞行中的命中判定直接复用了爆炸半径 {@link #R_RADIUS_START}（3 格），
     * 于是"离得近的敌人"在火球刚出生的第一 tick 就被判定命中 —— 玩家的观感就是
     * "大字爆在原地炸开"。碰撞用一个小的固定值（0.9 格，接近字面本身的厚度），
     * 爆炸伤害半径仍然从 3 收到 1，两者解耦。
     */
    private static final double PROJECTILE_HIT_RADIUS = 0.9;
    /** 起飞后至少飞这么远才允许命中/撞墙爆，避免出膛瞬间被身边的东西"吸"爆。 */
    private static final double PROJECTILE_MIN_TRAVEL = 1.2;
    private static final double R_RADIUS_START = 3.0;
    private static final double R_RADIUS_END = 1.0;
    private static final int R_CHARGE_TICKS = 20;

    /** 喷火龙离玩家的最大距离（实体追踪范围默认约 80 格，留足余量）。 */
    public static final double MAX_DISTANCE_FROM_PLAYER = 48.0;

    // ---- 圆形场地（全局：一局共用一个圈）----
    private static Vec3 arenaCenter = Vec3.ZERO;
    private static boolean arenaSet = false;
    private static double arenaRadius = MAX_DISTANCE_FROM_PLAYER;

    /**
     * 场地是否"强制执行"（位置钳制）。0.38 起默认关闭。
     *
     * <p>用户要求"宝可梦无论多远都能放、上帝视角可以走多远都行"，所以自由/训练模式下
     * 场地只作为信息存在（圆心/半径照常上报 HUD），但不再把实体拉回圈内：
     * {@link #enforceRangeLimit}、{@link #clampToArena}、{@link #distanceToBorder} 全部放行。
     * 只有 1v1 竞技场真正需要边界时才调用 {@link #setArenaEnforced(boolean)} 打开。
     */
    private static boolean arenaEnforced = false;

    public static boolean isArenaEnforced() {
        return arenaEnforced;
    }

    public static void setArenaEnforced(boolean enforced) {
        arenaEnforced = enforced;
    }

    /** 每个受控实体一份战斗状态。 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    public static void setArena(Vec3 center, double radius) {
        arenaCenter = center;
        arenaRadius = Math.max(8.0, radius);
        arenaSet = true;
    }

    /** 取消圆形场地限制（对局场地暂定无限大，只用点位置位）。 */
    public static void clearArena() {
        arenaSet = false;
        arenaCenter = Vec3.ZERO;
        arenaRadius = MAX_DISTANCE_FROM_PLAYER;
    }

    public static double getArenaRadius() {
        return arenaRadius;
    }

    /** 供各战斗状态同步 HUD：当前圆形场地圆心。 */
    public static Vec3 arenaCenter() {
        return arenaCenter;
    }

    /** 供各战斗状态同步 HUD：当前圆形场地半径。 */
    public static double arenaRadius() {
        return arenaRadius;
    }

    /** 把任意点截断到场地内（移动指令用）。自由模式下直接放行。 */
    /**
     * 实体版本的边界截断：先按旧的圆形竞技场截断，再按房间点位组的多边形边界往里推。
     *
     * <p>多边形边界不是凸的也能用：以"多边形质心"为目标点，沿直线每次缩 0.35 格，
     * 直到落点多边形内部为止（找不到就退回质心），保证宝可梦永远不会跑出地图。
     */
    public static Vec3 clampToArena(net.minecraft.world.entity.Entity entity, Vec3 pos) {
        Vec3 inner = clampToArena(pos);
        var arena = com.pkmoba.duel.DuelManager.arenaForEntity(entity);
        if (arena == null || !arena.hasBoundary() || arena.insideBoundary(inner)) {
            return inner;
        }
        double cx = 0, cz = 0;
        for (Vec3 p : arena.boundary) {
            cx += p.x;
            cz += p.z;
        }
        cx /= arena.boundary.size();
        cz /= arena.boundary.size();
        Vec3 centroid = new Vec3(cx, inner.y, cz);
        Vec3 candidate = inner;
        for (int step = 0; step < 120; step++) {
            candidate = candidate.lerp(centroid, 0.08);
            if (arena.insideBoundary(candidate)) {
                return candidate;
            }
        }
        return centroid;
    }

    public static Vec3 clampToArena(Vec3 pos) {
        if (!arenaSet || !arenaEnforced) {
            return pos;
        }
        double dx = pos.x - arenaCenter.x;
        double dz = pos.z - arenaCenter.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double limit = arenaRadius - 1.0;
        if (d <= limit || d < 1.0E-4) {
            return pos;
        }
        double k = limit / d;
        return new Vec3(arenaCenter.x + dx * k, pos.y, arenaCenter.z + dz * k);
    }

    /** 供 UI：距离场地边界的距离（负数表示已出界）。自由模式下恒为 999（等于"无限大"）。 */
    public static float distanceToBorder(PokemonEntity entity) {
        if (!arenaSet || !arenaEnforced || entity == null) {
            return 999.0F;
        }
        double dx = entity.getX() - arenaCenter.x;
        double dz = entity.getZ() - arenaCenter.z;
        return (float) (arenaRadius - Math.sqrt(dx * dx + dz * dz));
    }

    /**
     * 模型当前正前方（水平单位向量）。
     *
     * <p>这是"技能跟随模型"的唯一方向来源：所有技能的射线、扇形、弹道都用它，
     * 而不是用鼠标命中的世界点。鼠标只负责把模型转向（aimDirection → 逐步转身），
     * 因此模型转到哪、火焰就扫到哪，视觉上火焰是长在嘴上的，不是钉在光标上的。
     */
    public static Vec3 modelForward(PokemonEntity entity) {
        if (entity == null) {
            return new Vec3(0, 0, 1);
        }
        double yaw = Math.toRadians(entity.getYRot());
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        return f.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : f.normalize();
    }

    /**
     * 技能方向 = 鼠标瞄准方向（0.38 用户要求"技能就直接看我鼠标点击的方向"）。
     *
     * <p>与 {@link #modelForward} 的区别：这是**期望**方向，取自 {@code channelAim}/施法瞄准点；
     * {@code modelForward} 是模型**当前**朝向。技能射线用前者（跟手、零延迟），
     * 模型用 {@link FacingService} 限速追向同一个方向（看起来像"转身开火"）。
     * 两者都从宝可梦自身位置出发，原点仍是嘴部 —— 所以既跟手又不像钉在光标上的射线。
     */
    public static Vec3 aimDirection(PokemonEntity entity, Vec3 aim) {
        if (entity == null || aim == null) {
            return new Vec3(0, 0, 1);
        }
        Vec3 flat = new Vec3(aim.x - entity.getX(), 0, aim.z - entity.getZ());
        if (flat.lengthSqr() < 1.0E-6) {
            return modelForward(entity);
        }
        return flat.normalize();
    }

    /**
     * 嘴巴（喷口）位置：脚底 + 真实嘴高 + 沿给定方向前移一小段。
     *
     * <p>0.38.4：不再用 {@code getEyePosition()} 起算。眼睛高度是碰撞箱算出来的
     * （喷火龙约 2.2 格），而 Cobblemon 模型里 {@code locator_mouth} 在 2.859 格、
     * 水箭龟在 2.563 格，用眼睛当嘴就会看到"火焰从胸口/下巴下面飘出来"。
     * 现在统一走 {@link MouthAnchor}（从 geo 文件量出来的偏移），客户端能拿到
     * 活体骨骼定位器时再以活体为准（见 {@code client.MouthLocator}）。
     *
     * @param forward       期望朝向（水平），传 null 则用模型当前朝向
     * @param forwardOffset 额外前推距离（格）
     * @param drop          相对嘴部的额外下落（格），负值等于抬高
     */
    /**
     * 嘴部"聚火"粒子（0.39.3）：喷火龙 Q/W 前摇与 R 蓄力共用。
     *
     * <p>实现要点：用 {@code count = 0} 的 {@code sendParticles} 把 xd/yd/zd 当作<b>速度</b>，
     * 于是可以精确地让每颗火星从外围朝嘴心收拢 —— 这正是"吸气聚能"的观感。
     * 只发原版粒子，不新增资源；数量随 {@code progress} 增加，喷出的前一拍最亮。
     *
     * @param progress 0..1 蓄力进度
     * @param big      true 用于 R 大招（更大量、更大范围）
     */
    private static void chargeMouthFx(ServerLevel level, Vec3 mouth, double progress, boolean big) {
        java.util.Random random = new java.util.Random();
        int converge = (big ? 6 : 3) + (int) Math.round(progress * (big ? 8 : 5));
        double radius = (big ? 1.9 : 1.15) * (1.0 - 0.65 * progress);
        for (int i = 0; i < converge; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double height = (random.nextDouble() - 0.5) * (big ? 1.6 : 0.9);
            double x = mouth.x + Math.cos(angle) * radius;
            double y = mouth.y + height;
            double z = mouth.z + Math.sin(angle) * radius;
            Vec3 toMouth = mouth.subtract(new Vec3(x, y, z)).normalize().scale(0.55);
            level.sendParticles(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                    x, y, z, 0, toMouth.x, toMouth.y, toMouth.z, 0.12);
        }
        // 嘴心越来越亮的火核
        int core = 1 + (int) Math.round(progress * (big ? 4 : 2));
        level.sendParticles(ParticleTypes.SMALL_FLAME,
                mouth.x, mouth.y, mouth.z, core, 0.05 + 0.12 * progress, 0.05 + 0.12 * progress,
                0.05 + 0.12 * progress, 0.01);
        // 蓄满前两拍补一圈火星，作为"要喷了"的预告
        if (progress > 0.7) {
            level.sendParticles(ParticleTypes.LAVA, mouth.x, mouth.y, mouth.z,
                    big ? 4 : 2, 0.22, 0.18, 0.22, 0.0);
        }
    }

    public static Vec3 mouthPosition(PokemonEntity entity, Vec3 forward, double forwardOffset, double drop) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        Vec3 dir = forward != null && forward.lengthSqr() > 1.0E-8
                ? new Vec3(forward.x, 0.0, forward.z).normalize()
                : MouthAnchor.forward(entity.getYRot());
        MouthAnchor.Offset offset = MouthAnchor.offsetOf(entity);
        return entity.position()
                .add(dir.scale(offset.forward() + forwardOffset))
                .add(0.0, offset.up() - drop, 0.0);
    }

    /**
     * 嘴/炮口世界坐标（默认前移 0.55、下移 0.22）。
     *
     * <p>技能出源头统一绑模型：方向可以是鼠标方向（跟手），但原点永远是这张嘴/这根炮管。
     */
    public static Vec3 muzzle(PokemonEntity entity) {
        return mouthPosition(entity, modelForward(entity), MOUTH_FORWARD, MOUTH_DROP);
    }

    private CharizardCombat() {
    }

    public static void init() {
        BlastoiseCombat.init();
        VenusaurCombat.init();
        PikachuCombat.init();
        ServerPlayNetworking.registerGlobalReceiver(SkillCastPayload.TYPE, (payload, context) ->
                context.server().execute(() -> cast(context.player(), Skill.byIndex(payload.skill()),
                        new Vec3(payload.x(), payload.y(), payload.z()))));

        ServerPlayNetworking.registerGlobalReceiver(AimPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    PokemonEntity entity = CharizardManager.getControlled(context.player());
                    Vec3 aim = new Vec3(payload.x(), payload.y(), payload.z());
                    if (!canControl(context.player(), entity, aim)) {
                        return;
                    }
                    // 无论是否在引导，都记下最新瞄准点：模型要"时时刻刻"朝鼠标慢慢转。
                    AimTracker.set(entity, aim);
                    // 水炮定身不等于锁住瞄准：由各通道自己决定是否接收新的鼠标位置。
                    if (ExpansionCombat.supports(entity)) {
                        ExpansionCombat.updateAim(entity, aim);
                    } else if (CharizardManager.isBlastoise(entity)) {
                        BlastoiseCombat.updateAim(entity, aim);
                    } else if (CharizardManager.isVenusaur(entity)) {
                        VenusaurCombat.updateAim(entity, aim);
                    } else if (CharizardManager.isPikachu(entity)) {
                        PikachuCombat.updateAim(entity, aim);
                    } else {
                        State st = s(entity);
                        if (st.channel == 0 && st.dashTicks == 0) {
                            st.channelAim = aim;
                        }
                    }
                }));

        ServerTickEvents.END_SERVER_TICK.register(CharizardCombat::tick);
    }

    private static State s(PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity));
    }

    private static State peek(PokemonEntity entity) {
        return entity == null ? null : STATES.get(entity.getUUID());
    }

    /** 训练场兼容：当前全局受控实体（单实体模式）。 */
    private static PokemonEntity active(MinecraftServer server) {
        return CharizardManager.getActive(server);
    }

    public static float getHp() {
        return hp(active(null));
    }

    private static float hp(PokemonEntity entity) {
        return entity == null ? CHARIZARD_MAX_HP : entity.getHealth();
    }

    public static boolean isBasicAttackBlocked() {
        return isBasicAttackBlocked(active(null));
    }

    public static boolean isBasicAttackBlocked(PokemonEntity entity) {
        State st = peek(entity);
        boolean busy = st != null && (st.channel != 0 || st.dashTicks > 0);
        return busy || BlastoiseCombat.blocksBasicAttack(entity) || VenusaurCombat.blocksBasicAttack(entity)
                || PikachuCombat.isBusy(entity) || ExpansionCombat.isBusy(entity)
                || (entity != null && ExpansionControl.sleeping(entity));
    }

    /** Moving casts (Q) never own locomotion; E and R explicitly do. */
    public static boolean blocksMovement() {
        return blocksMovement(active(null));
    }

    public static boolean blocksMovement(PokemonEntity entity) {
        State st = peek(entity);
        boolean busy = st != null && (st.channel == 4 || st.dashTicks > 0);
        return busy || BlastoiseCombat.blocksMovement(entity) || VenusaurCombat.blocksMovement(entity)
                || PikachuCombat.blocksMovement(entity)
                || (entity != null && ExpansionCombat.blocksMovement(entity));
    }

    private static boolean authorizedActor(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null
                || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))) return false;
        if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
            return player == com.pkmoba.duel.BotDuelService.actor(player.getUUID());
        }
        return ServerGodMode.isActive(player);
    }

    private static boolean canControl(ServerPlayer player, PokemonEntity entity, Vec3 aim) {
        return entity != null && authorizedActor(player, entity)
                && player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                && player.level().dimension().equals(entity.level().dimension())
                && Double.isFinite(aim.x) && Double.isFinite(aim.y) && Double.isFinite(aim.z)
                && entity.distanceToSqr(aim) <= 512.0 * 512.0
                // 方案 §三.4：宝可梦刚登场（投球落地）的 2 秒里禁止移动/攻击/放技能
                && !com.pkmoba.pokemon.BallThrow.locked(entity);
    }

    /**
     * 向玩家下发一份"已阵亡"的技能状态：血量 0 / 上限 MAX_HP / 豆与 CD 清零。
     *
     * <p>实体被移除后 {@link #sendState} 就不会再发，客户端 HUD 会停在最后一帧
     * （例如 13/100）。这里补发一帧 0 血量，保证"击杀后血量显示为 0"。
     */
    public static void sendFaintedState(ServerPlayer player) {
        sendFaintedState(player, CHARIZARD_MAX_HP);
    }

    /** 带上限版本：补帧时按这只宝可梦的真实上限显示 0/280 或 0/340。 */
    public static void sendFaintedState(ServerPlayer player, float maxHp) {
        if (player == null || !ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) return;
        ServerPlayNetworking.send(player, new SkillStatePayload(0.0F, maxHp, 0.0F,
                0, 0, 0, 0, 0, 0.0F, 999.0F,
                (float) arenaCenter.x, (float) arenaCenter.y, (float) arenaCenter.z, (float) arenaRadius));
    }

    /** 清空全部实体的战斗状态（/mo remove、对局结束、服务器停机）。 */
    public static void resetAll() {
        BlastoiseCombat.resetAll();
        VenusaurCombat.resetAll();
        PikachuCombat.resetAll();
        ExpansionCombat.resetAll();
        for (State st : new ArrayList<>(STATES.values())) {
            st.abort();
        }
        STATES.clear();
        // 0.48.5：施法锁 / 朝向仲裁 / 预瞄缓存一起清（换局时不能把上一局的朝向带进来）。
        CastLock.clearAll();
        AimTracker.clearAll();
    }

    /** 只清一只实体的状态。 */
    public static void reset(PokemonEntity entity) {
        if (entity == null) return;
        BlastoiseCombat.reset(entity);
        VenusaurCombat.reset(entity);
        PikachuCombat.reset(entity);
        ExpansionCombat.reset(entity);
        State st = STATES.remove(entity.getUUID());
        if (st != null) st.abort();
        // 0.48.5：死亡 / 收回 / 取消也走这里，顺手释放施法锁并清预瞄缓存。
        CastLock.release(entity);
        AimTracker.clear(entity);
    }

    // ------------------------------------------------------------ 主循环

    private static void tick(MinecraftServer server) {
        // 清理已死亡 / 已移除 / 换局的实体状态
        STATES.entrySet().removeIf(entry -> {
            PokemonEntity entity = byId(server, entry.getKey());
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                if (entity != null) {
                    // 0.48.5：死亡 / 被移除时释放施法锁，避免 UUID 残留在朝向表里。
                    CastLock.release(entity);
                }
                entry.getValue().abort();
                return true;
            }
            return false;
        });

        java.util.List<PokemonEntity> controlled = CharizardManager.getControlledEntities(server);
        for (PokemonEntity entity : controlled) {
            enforceArena(entity);
            tickEntity(server, entity);
            // 常驻朝向：低速转向鼠标瞄准点（引导中会被 CHANNEL 优先级压过）。
            AimTracker.track(entity);
        }
        // 0.48.5：收回/换宝可梦后实体还活着但已不受控，清了残留锁定（否则下次召唤会被旧锁钉住）。
        CastLock.retain(controlled);
        tickArenaRing(server);
        syncAll(server);
    }

    /**
     * 硬边界：宝可梦无论如何都不能离开点位组的边界。
     *
     * <p>移动寻路、技能位移（冲浪冲刺 / 高速旋转）都会改坐标，单靠
     * {@code MoveOrderService} 里的"贴边停"漏得掉，所以在每 tick 统一收口：
     * 一旦发现实体已经在边界外，直接放回最近的边界内点，并把速度反向衰减，
     * 模拟"撞到方块一样弹回"，而不是继续往里挤。
     */
    public static boolean enforceArena(PokemonEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        var arena = com.pkmoba.duel.DuelManager.arenaForEntity(entity);
        if (arena == null || !arena.hasBoundary()) {
            return false;
        }
        Vec3 pos = entity.position();
        if (arena.insideBoundary(pos)) {
            return false;
        }
        Vec3 back = clampToArena((net.minecraft.world.entity.Entity) entity, pos);
        entity.setPos(back.x, back.y, back.z);
        Vec3 v = entity.getDeltaMovement();
        entity.setDeltaMovement(v.x * -0.25, Math.max(0.0, v.y), v.z * -0.25);
        entity.getNavigation().stop();
        entity.hurtMarked = true;
        return true;
    }

    private static PokemonEntity byId(MinecraftServer server, UUID id) {
        for (PokemonEntity entity : CharizardManager.getControlledEntities(server)) {
            if (entity.getUUID().equals(id)) return entity;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof PokemonEntity pokemon) return pokemon;
        }
        return null;
    }

    private static void tickEntity(MinecraftServer server, PokemonEntity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return;
        }
        // Correct attribute recalculation while preserving damage and saved recall health.
        float maxHp = maxHpFor(entity);
        var maxHpAttr = entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (maxHpAttr != null && Math.abs(maxHpAttr.getBaseValue() - maxHp) > 0.01) {
            float currentHealth = entity.getHealth();
            maxHpAttr.setBaseValue(maxHp);
            entity.setHealth(Math.min(currentHealth, maxHp));
        }
        // 旧版本召唤出来的实体可能还带着无敌，这里统一解除
        entity.setInvulnerable(false);
        // 把设计上限写进 Cobblemon 的 Pokemon 对象：让实体属性、Pokemon.getMaxHealth()、
        // 宝可梦界面（P 键）三处显示同一个血量上限（用户 m01030："显示的是300实际就90"）。
        PokemonHp.apply(entity, maxHp);
        State st = s(entity);
        st.hp = entity.getHealth();

        if (com.pkmoba.duel.DuelManager.isPreparing(entity)) {
            st.abort();
            BlastoiseCombat.clearTransient(entity);
            VenusaurCombat.clearTransient(entity);
            PikachuCombat.clearTransient(entity);
            ExpansionCombat.clearTransient(entity);
            entity.getNavigation().stop();
            entity.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (ExpansionCombat.supports(entity)) {
            enforceRangeLimit(entity, st);
            ExpansionCombat.tick((ServerLevel) entity.level(), entity);
            return;
        }
        if (CharizardManager.isPikachu(entity)) {
            enforceRangeLimit(entity, st);
            PikachuCombat.tick((ServerLevel) entity.level(), entity);
            return;
        }
        if (CharizardManager.isVenusaur(entity)) {
            enforceRangeLimit(entity, st);
            VenusaurCombat.tick((ServerLevel) entity.level(), entity);
            return;
        }
        if (CharizardManager.isBlastoise(entity)) {
            enforceRangeLimit(entity, st);
            BlastoiseCombat.tick((ServerLevel) entity.level(), entity);
            return;
        }
        if (server.getTickCount() % 20 == 0 && st.points < MAX_POINTS) {
            st.points = Math.min(MAX_POINTS, st.points + 1.0F);
        }
        if (st.cdQ > 0) st.cdQ--;
        if (st.cdW > 0) st.cdW--;
        if (st.cdE > 0) st.cdE--;
        if (st.cdR > 0) st.cdR--;

        enforceRangeLimit(entity, st);
        tickChannel(server, entity, st);
        tickZones(server, entity, st);
        tickDash(server, entity, st);
        tickProjectile(entity, st);
    }

    /**
     * 场地边界处理（B-3）：位置钳制，而不是清空指令。
     *
     * <p>上一版在超距时调用了 {@code MoveOrderService.clear()}，导致玩家已经站在圈外时
     * 每点一次地指令就被立刻清掉，表现为"完全操作不了"。现在改成只把位置拉回边界内侧，
     * 指令照常接受，玩家随时能把它叫回来。
     *
     * <p>0.38：自由/训练模式 {@link #arenaEnforced} 默认关闭 —— 这里直接放行，宝可梦可以走到
     * 任意远（用户要求"无论多远都能走"）。只有对局需要边界时才会打开这个开关。
     */
    private static void enforceRangeLimit(PokemonEntity entity, State st) {
        if (!arenaSet || !arenaEnforced) {
            return;
        }
        Vec3 p = entity.position();
        double dx = p.x - arenaCenter.x;
        double dz = p.z - arenaCenter.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= arenaRadius) {
            return;
        }
        double k = Math.max(0.0, arenaRadius - 0.15) / d;
        entity.setPos(arenaCenter.x + dx * k, p.y, arenaCenter.z + dz * k);
        entity.getNavigation().stop();
        if (st.dashTicks > 0) {
            stopDash(entity, st);
        }
    }

    // ------------------------------------------------------------ Q / R 引导

    private static void tickChannel(MinecraftServer server, PokemonEntity entity, State st) {
        if (st.channel == 0) {
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();
        st.channelTicks++;

        if (st.channel == 1) {
            // 0.38.1：火焰方向 = 模型正前方；0.48.5 改为优先读施法锁定方向，
            // 喷火期间鼠标/移动/AI 都改不了它（射线与锥形伤害共用同一个方向）。
            Vec3 dir = CastLock.dirOr(entity, modelForward(entity));
            int jetTicks = st.channelTicks - Q_WINDUP_TICKS;
            if (jetTicks < 0) {
                // 前摇：动作已经在施法瞬间起手（客户端 q_windup），这里补<b>看得见的聚火</b>，
                // 不出火、不结算伤害 —— 用户要求"动画先出、特效后上"。
                //
                // 0.39.3（用户："喷射火焰怎么没有蓄力的动画"）：原来每 2 tick 只在嘴边丢 2 颗
                // SMALL_FLAME，肉眼几乎看不出在蓄力。现在改成"向嘴心收拢的火球 + 随蓄力变大的核心
                // + 螺旋火星"，11 tick 内能明显读出"吸气—聚火—喷出"的节奏。
                Vec3 mouth = mouthPosition(entity, dir, Q_JET_FORWARD, MOUTH_DROP);
                double p = Math.min(1.0, st.channelTicks / (double) Math.max(1, Q_WINDUP_TICKS));
                chargeMouthFx(level, mouth, p, false);
            } else {
                // Q：服务端统一原点(嘴部)/方向/墙截断长度；客户端嘴部仅作短接驳。
                sendJet(level, entity, dir);
                // 喷吐窗口 20 tick，每 4 tick 一跳伤害（5 跳 × 6 = 30，与调整前一致）
                if (jetTicks < Q_CHANNEL_TICKS - Q_WINDUP_TICKS
                        && jetTicks % Q_DAMAGE_INTERVAL == 0) {
                    coneDamage(level, entity, dir, Q_TICK_DAMAGE);
                }
            }
            if (st.channelTicks >= Q_CHANNEL_TICKS) {
                st.channel = 0;
                st.cdQ = Skill.cooldownTicks(false, 0);
                // 0.48.5：收招再锁 3 tick 再交还预瞄，避免"松手瞬间模型被鼠标甩走"。
                CastLock.finish(entity, 3);
            }
        } else if (st.channel == 2) {
            // W 火焰旋涡前摇：只蓄力、不出区。方向与落点都读施法锁定值（0.48.5）。
            Vec3 dir = CastLock.dirOr(entity, aimDirection(entity, st.channelAim));
            Vec3 mouth = mouthPosition(entity, dir, Q_JET_FORWARD, MOUTH_DROP);
            chargeMouthFx(level, mouth, Math.min(1.0, st.channelTicks / (double) Math.max(1, W_WINDUP_TICKS)), false);
            if (st.channelTicks >= W_WINDUP_TICKS) {
                // 前摇结束：落区 + 播成形特效 + 开始冷却（冷却从"技能真正出手"算起）
                st.channel = 0;
                // 0.48.5：中心一律读锁定落点（施法那一帧算好的合法坐标），保证预告圈 = 实际落区。
                Vec3 center = CastLock.pointOr(entity, tornadoCenter(level, entity, st.channelAim));
                FacingService.snap(entity, FacingService.Priority.CHANNEL, dir);
                st.zones.add(new Zone(level.dimension(), center, W_DURATION_TICKS));
                sendTornado(level, entity, center);
                if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
                    PkmobaMod.LOGGER.info("[pkmoba-bot] release skill=W source={} center={} aim={} tick={} duration={}",
                            entity.getId(), center, st.channelAim, entity.tickCount, W_DURATION_TICKS);
                }
                sound(level, center, "cobblemon:move.flamethrower.actor");
                // Client channel transition owns the release pose; a second full special would replay the windup.
                st.cdW = Skill.cooldownTicks(false, 1);
                // 0.48.5：出区后收招 3 tick 内仍保持面向，然后交还预瞄。
                CastLock.finish(entity, 3);
            }
        } else if (st.channel == 4) {
            // 火球蓄力：模型朝向 = 施法锁定的出手方向（0.48.5），蓄满沿它发射。
            Vec3 dir = CastLock.dirOr(entity, aimDirection(entity, st.channelAim));
            chargeMouthFx(level, mouthPosition(entity, dir, MOUTH_FORWARD, MOUTH_DROP),
                    Math.min(1.0, st.channelTicks / (double) Math.max(1, R_CHARGE_TICKS)), true);
            if (st.channelTicks >= R_CHARGE_TICKS) {
                st.channel = 0;
                st.cdR = Skill.cooldownTicks(false, 3);
                launchProjectile(entity, st, dir);
                // 0.48.5：出手瞬间到收招结束（4 tick）仍锁方向，弹道已固定，朝向不提前甩走。
                CastLock.finish(entity, 4);
            }
        }
    }

    /**
     * 把模型转向瞄准点（限速）。
     *
     * <p>0.38 起不再直接写 {@code setYRot/setYHeadRot/setYBodyRot/hurtMarked}：
     * 那些直写就是"模型头乱晃"的来源（多处系统同 tick 抢写同一个角度）。
     * 现在统一走 {@link FacingService}，由它做单写者仲裁 + 限速 + 节流同步。
     */
    private static void turnToward(PokemonEntity entity, Vec3 target, float maxDeg) {
        FacingService.request(entity, FacingService.Priority.CHANNEL,
                aimDirection(entity, target), maxDeg);
    }

    /** Q uses the exact same finite server ray for visuals and damage. */
    private static void sendJet(ServerLevel level, PokemonEntity entity, Vec3 forward) {
        JetRay ray = jetRay(level, entity, forward);
        com.pkmoba.network.JetPayload payload = new com.pkmoba.network.JetPayload(entity.getId(),
                ray.origin.x, ray.origin.y, ray.origin.z, ray.dir.x, ray.dir.y, ray.dir.z, ray.length);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(entity.position()) < 128 * 128
                    && ServerPlayNetworking.canSend(p, com.pkmoba.network.JetPayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    // ------------------------------------------------------------ W 龙卷

    private static void tickZones(MinecraftServer server, PokemonEntity owner, State st) {
        if (st.zones.isEmpty()) {
            return;
        }
        st.zones.removeIf(zone -> {
            ServerLevel level = server.getLevel(zone.dimension);
            if (level == null) {
                return true;
            }
            zone.ticksLeft--;
            zone.phase++;

            // 动画由客户端单个出生事件驱动；服务端只计算每10tick的伤害。
            if (zone.ticksLeft % 10 == 0) {
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(zone.pos, zone.pos).inflate(W_RADIUS), e -> validTarget(e, owner))) {
                    Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), zone.pos.add(0, .5, 0));
                    double d = contact.subtract(zone.pos).horizontalDistance();
                    if (d <= W_RADIUS && target.getBoundingBox().maxY >= zone.pos.y - .5
                            && target.getBoundingBox().minY <= zone.pos.y + 4.0) {
                        float hpBefore = target.getHealth();
                        hurt(level, owner, target, W_TICK_DAMAGE);
                        if (com.pkmoba.duel.BotDuelService.isBot(owner)) {
                            PkmobaMod.LOGGER.info("[pkmoba-bot] hit skill=W source={} target={} hp={}->{} zone={}",
                                    owner.getId(), target.getId(), hpBefore, target.getHealth(), zone.pos);
                        }
                        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 25,
                                d < W_RADIUS * 0.5 ? 3 : 1, false, false));
                    }
                }
            }
            return zone.ticksLeft <= 0;
        });
    }

    /** Ground targeted W uses one placement function for warning, release, visuals and damage. */
    private static Vec3 tornadoCenter(ServerLevel level, PokemonEntity entity, Vec3 aim) {
        Vec3 dir = aimDirection(entity, aim);
        double distance = Math.min(new Vec3(aim.x - entity.getX(), 0, aim.z - entity.getZ()).length(), W_CAST_RANGE);
        Vec3 point = entity.position().add(dir.scale(distance));
        // Search from the target's elevation, not a flying caster's Y, so the vortex lands.
        // Bot aims at the opponent's torso, which can be well above a four-block
        // downward probe after Fly. A ground skill must reach the arena floor.
        BlockHitResult floor = level.clip(new ClipContext(new Vec3(point.x, aim.y + .5, point.z),
                new Vec3(point.x, Math.min(entity.getY(), aim.y) - 24, point.z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        double ground = floor.getType() == HitResult.Type.BLOCK ? floor.getLocation().y : Double.NaN;
        return new Vec3(point.x, Double.isFinite(ground) ? ground + .04 : entity.getY(), point.z);
    }

    private static void sendTornado(ServerLevel level, PokemonEntity entity, Vec3 center) {
        sendTornadoRadius(level, entity, center, W_RADIUS);
    }

    /**
     * 前摇预告：把落点先告诉客户端，让它在 channel==2 期间就画"聚集"特效。
     *
     * <p>约定：<b>radius 为负</b>表示这只是落点预告（duration 传 0），不是真旋涡；
     * 客户端收到负半径时只记落点、不放旋涡、不结算任何东西。真旋涡仍走
     * {@link #sendTornado}（半径为正）。
     */
    private static void sendTornadoPreview(ServerLevel level, PokemonEntity entity, Vec3 center) {
        sendTornadoRadius(level, entity, center, -W_RADIUS);
    }

    private static void sendTornadoRadius(ServerLevel level, PokemonEntity entity, Vec3 center,
            double radius) {
        int duration = radius < 0 ? 0 : W_DURATION_TICKS;
        TornadoPayload payload = new TornadoPayload(entity.getId(), center.x, center.y, center.z,
                radius, duration);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= 128 * 128
                    && ServerPlayNetworking.canSend(player, TornadoPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static List<Vec3> ringPoints(Vec3 center, double radius) {
        int points = 12;
        List<Vec3> list = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            list.add(center.add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius));
        }
        return list;
    }

    // ------------------------------------------------------------ E 冲刺

    private static void setFlyingFlag(PokemonEntity entity, boolean flying, State st) {
        try {
            entity.setBehaviourFlag(PokemonBehaviourFlag.FLYING, flying);
            // Cobblemon先用isPokemonFlying更新MOVING，再由MOVING+FLYING选姿态。
            // move(SELF)驱动的短程滑翔不保证flyDist增量，必须同步移动语义而非冻结动画。
            entity.setPokemonFlying(flying);
            entity.setPokemonWalking(false);
            entity.getEntityData().set(PokemonEntity.getMOVING(), flying);
            entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), flying ? PoseType.FLY : PoseType.STAND);
        } catch (Throwable error) {
            if (!st.flightPoseWarningLogged) {
                st.flightPoseWarningLogged = true;
                PkmobaMod.LOGGER.warn("[pkmoba] 飞行姿态同步失败（仅记录一次，移动仍继续）", error);
            }
        }
    }

    private static void tickDash(MinecraftServer server, PokemonEntity entity, State st) {
        if (st.dashTicks <= 0) {
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();
        st.dashTicks--;
        st.dashAge++;
        setFlyingFlag(entity, true, st);
        entity.getNavigation().stop();
        // Preserve right-click orders received during flight; execute them after landing.
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setNoGravity(true);
        Vec3 before = entity.position();
        // 0.48.5：位移终点不只在施法帧"记一笔" —— 真的按 CastLock.DASH 的 point 飞：
        // 剩余距离取"当前位置 → 锁定终点"的水平距离（与固定里程取小），接近终点就进入落地阶段。
        // 锁定被硬清（死亡/收回/传送）时才退回固定 E_MAX_DISTANCE 兜底。
        Vec3 lockEnd = CastLock.point(entity);
        double toEnd = lockEnd == null ? E_MAX_DISTANCE - st.dashTravelled
                : Math.sqrt((lockEnd.x - before.x) * (lockEnd.x - before.x)
                        + (lockEnd.z - before.z) * (lockEnd.z - before.z));
        boolean landing = st.dashLandingTicks > 0;
        if (!landing) {
            // 0.48.5：飞行方向 = 施法锁定的冲刺方向（旧的 8°/tick 追鼠标会让轨迹画弧、终点漂移）。
            double yaw = Math.toRadians(entity.getYRot());
            st.dashDir = CastLock.dirOr(entity, new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)));
            if (toEnd <= E_FINISH_DISTANCE || st.dashTicks <= E_LANDING_TICKS) {
                st.dashLandingTicks = E_LANDING_TICKS;
                st.dashAutoLanding = true;
                landing = true;
            }
        }
        double remaining = Math.min(Math.max(0, E_MAX_DISTANCE - st.dashTravelled), Math.max(0, toEnd));
        double factor = landing
                ? (st.dashAutoLanding ? Mth.clamp(remaining / E_FINISH_DISTANCE, 0.25, 1.0)
                : st.dashLandingTicks / (double) E_LANDING_TICKS)
                : Math.min(1.0, st.dashAge / (double) E_ACCEL_TICKS);
        // 0.48.5：速度再夹一层 remaining，保证不会越过锁定终点（终点由 CastLock.DASH 决定）。
        double speed = Math.min(E_SPEED_PER_TICK * factor,
                Math.min(E_MAX_DISTANCE - st.dashTravelled, remaining));
        Vec3 horizontal = st.dashDir.scale(Math.max(0, speed));
        Vec3 next = before.add(horizontal);
        double ground = groundBelow(level, entity, next);
        // 不飞过无落脚点的深坑，也不把完整碰撞箱推入墙体。
        if (!Double.isFinite(ground) || !level.noCollision(entity, entity.getBoundingBox().move(horizontal))) {
            horizontal = Vec3.ZERO;
            if (!landing) st.dashLandingTicks = E_LANDING_TICKS;
            st.dashAutoLanding = false;
            landing = true;
            ground = groundBelow(level, entity, before);
        }
        double height = landing
                ? (st.dashAutoLanding ? 0.8 * Math.min(1.0, remaining / E_FINISH_DISTANCE) : 0)
                : 0.8 * Math.min(1.0, st.dashAge / (double) E_ACCEL_TICKS);
        double dy = Double.isFinite(ground) ? Mth.clamp(ground + height - before.y, -0.18, 0.12) : -0.18;
        entity.move(MoverType.SELF, horizontal.add(0, dy, 0));
        entity.hurtMarked = true;
        Vec3 after = entity.position();
        double dx = after.x - before.x, dz = after.z - before.z;
        st.dashTravelled += Math.sqrt(dx * dx + dz * dz);
        if (landing) {
            st.dashLandingTicks = Math.max(1, st.dashLandingTicks - 1);
            if (entity.onGround() || (Double.isFinite(ground) && after.y <= ground + 0.06)) {
                stopDash(entity, st);
                return;
            }
        }

        // 飞行途中实时造成伤害：命中记录 dashHit 保证同一趟飞行对每个目标只结算一次，
        // 因此不会出现"来回抖动无限重复命中"。
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(E_CONTACT_INFLATE), e -> validTarget(e, entity))) {
            if (st.dashHit.add(target.getId())) {
                CombatDamage.apply(level, entity, target, E_CONTACT_DAMAGE, TypeEffectiveness.MoveType.FLYING);
                particleBurst(level, target.position().add(0, 1, 0), ParticleTypes.CRIT, 6);
                sound(level, target.position(), "cobblemon:impact.flying");
            }
        }
        // 白色风线拖尾
        particleBurst(level, entity.position().add(0, 0.8, 0), ParticleTypes.CLOUD, 3);

        if (st.dashTicks <= 0) {
            stopDash(entity, st);
        }
    }

    /** 脚下最近的可站立面高度；无地面时返回 NaN。MoveOrderService 的直线行走也用它抬台阶。 */
    public static double groundBelow(ServerLevel level, PokemonEntity entity, Vec3 pos) {
        BlockHitResult hit = level.clip(new ClipContext(pos.add(0, 0.5, 0), pos.add(0, -4, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : Double.NaN;
    }

    private static void stopDash(PokemonEntity entity, State st) {
        st.dashTicks = 0;
        st.dashTravelled = 0;
        st.dashLandingTicks = 0;
        st.dashAutoLanding = false;
        setFlyingFlag(entity, false, st);
        entity.setNoGravity(false);
        // End manual locomotion on supported ground, not hovering a fraction above a path node.
        if (entity.level() instanceof ServerLevel level) {
            double ground = groundBelow(level, entity, entity.position());
            if (Double.isFinite(ground) && entity.getY() >= ground && entity.getY() - ground <= 1.1) {
                entity.move(MoverType.SELF, new Vec3(0, ground - entity.getY() - 0.02, 0));
            }
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0;
        entity.hurtMarked = true;
        entity.getNavigation().stop();
        st.cdE = Skill.cooldownTicks(false, 2);
        // 0.48.5：落地后 2 tick 释放冲刺锁，然后把朝向交还预瞄（不在落地瞬间被鼠标猛地甩走）。
        CastLock.finish(entity, 2);
    }

    // ------------------------------------------------------------ R 弹道

    private static void launchProjectile(PokemonEntity entity, State st, Vec3 dir) {
        // 0.39.2（用户 m03117）："大字爆直接往锁敌的方向飞，但不要锁定"。
        // 出手方向 = 出手瞬间"从嘴到瞄准点"的水平方向；飞行过程中<b>完全直飞</b>不追踪，
        // 目标跑开就打空——这就是"锁方向不锁人"。之前用 modelForward，
        // 模型还没转到位时火球会朝旧朝向飞（甚至朝后打到身边的地面），用户看到的就是"原地炸开"。
        ServerLevel level = (ServerLevel) entity.level();
        Vec3 launchDir = dir;
        if (st.channelAim != null && Double.isFinite(st.channelAim.x)) {
            Vec3 toAim = st.channelAim.subtract(entity.position());
            Vec3 flat = new Vec3(toAim.x, 0.0, toAim.z);
            if (flat.lengthSqr() > 1.0E-4) {
                launchDir = flat.normalize();
            }
        }
        // 出火点贴嘴，但要保证"第一帧不会立刻撞墙"——否则火球刚生成就在脚下炸开。
        // 依次尝试：嘴里 -> 抬高 0.4/0.8 -> 沿方向前移 1.2 格，取第一个前方 1.6 格无阻挡的点。
        Vec3 start = mouthPosition(entity, launchDir, MOUTH_FORWARD, MOUTH_DROP);
        Vec3[] candidates = {
                start,
                start.add(0.0, 0.4, 0.0),
                start.add(0.0, 0.8, 0.0),
                start.add(launchDir.x * 1.2, 0.0, launchDir.z * 1.2),
                start.add(launchDir.x * 1.2, 0.5, launchDir.z * 1.2),
        };
        for (Vec3 candidate : candidates) {
            Vec3 probe = candidate.add(launchDir.scale(1.6));
            BlockHitResult blocked = level.clip(new ClipContext(candidate, probe, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, entity));
            if (blocked.getType() != HitResult.Type.BLOCK) {
                start = candidate;
                break;
            }
        }
        // 出手瞬间把模型掰到发射方向：否则客户端看到"身体朝左、火球朝右"，
        // 加上转身动画就会像"从原地炸出去"。
        FacingService.snap(entity, FacingService.Priority.CHANNEL, launchDir);
        // Players aim at the ground; bots already supply the target's body center.
        // Both must retain pitch: a horizontal mouth-height ray passes over Pikachu.
        // Lift a ground cursor into a small opponent's body, then freeze this ray.
        if (st.channelAim != null) {
            Vec3 impactAim = com.pkmoba.duel.BotDuelService.isBot(entity)
                    ? st.channelAim : st.channelAim.add(0.0, 0.45, 0.0);
            Vec3 toTarget = impactAim.subtract(start);
            if (toTarget.lengthSqr() > 1.0E-6) launchDir = toTarget.normalize();
        }
        st.rDir = launchDir;
        st.rNudged = false;
        st.rPos = start;
        if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
            PkmobaMod.LOGGER.info("[pkmoba-bot] release skill=R source={} start={} aim={} direction={} tick={}",
                    entity.getId(), start, st.channelAim, launchDir, entity.tickCount);
        }
        st.rTravelled = 0;
        st.rTick = 0;
        st.rFlying = true;

        sound(level, st.rPos, "cobblemon:move.fireblast.actor");
        // 0.38.3：不再播 Cobblemon 的 special —— 出手动作由客户端 CharizardSkillAnimator
        // 的 r_blast 负责，否则会出现用户说的“喷了之后还播放一个”（special 的收势段）。
    }

    private static void tickProjectile(PokemonEntity entity, State st) {
        if (!st.rFlying) {
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();

        Vec3 next = st.rPos.add(st.rDir.scale(R_SPEED_PER_TICK));
        BlockHitResult wall = level.clip(new ClipContext(st.rPos, next, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity));
        Vec3 segmentEnd = wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : next;
        Vec3 firstContact = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(st.rPos, segmentEnd).inflate(PROJECTILE_HIT_RADIUS + 1.5), e -> validTarget(e, entity))) {
            AABB hitBox = target.getBoundingBox().inflate(PROJECTILE_HIT_RADIUS);
            Vec3 contact = hitBox.contains(st.rPos) ? st.rPos : hitBox.clip(st.rPos, segmentEnd).orElse(null);
            if (contact != null && contact.distanceToSqr(st.rPos) < nearest) {
                nearest = contact.distanceToSqr(st.rPos);
                firstContact = contact;
            }
        }
        if (firstContact != null) {
            st.rPos = firstContact;
            double radius = R_RADIUS_START - (R_RADIUS_START - R_RADIUS_END)
                    * Math.min(1.0, st.rTravelled / R_MAX_DISTANCE);
            explode(level, entity, st.rPos, radius);
            sendGlyph(level, false, Vec3.ZERO, 0, Vec3.ZERO);
            st.rFlying = false;
            return;
        }
        if (wall.getType() == HitResult.Type.BLOCK) {
            // 出膛就被地形挡住（绝大多数是脚下斜坡/台阶）：先抬高一点再继续飞，
            // 而不是立刻在嘴边炸开。只给一次机会，第二次真撞上才爆。
            if (st.rTravelled < PROJECTILE_MIN_TRAVEL && !st.rNudged) {
                st.rNudged = true;
                st.rPos = st.rPos.add(0.0, 0.9, 0.0);
                Vec3 ahead = st.rPos.add(st.rDir.scale(R_SPEED_PER_TICK * 2.0));
                BlockHitResult retry = level.clip(new ClipContext(st.rPos, ahead,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
                if (retry.getType() != HitResult.Type.BLOCK) {
                    st.rPos = ahead;
                    st.rTravelled += R_SPEED_PER_TICK * 2.0;
                    return;
                }
            }
            st.rPos = wall.getLocation().subtract(st.rDir.scale(0.02));
            double radius = R_RADIUS_START - (R_RADIUS_START - R_RADIUS_END)
                    * Math.min(1.0, st.rTravelled / R_MAX_DISTANCE);
            explode(level, entity, st.rPos, radius);
            sendGlyph(level, false, Vec3.ZERO, 0, Vec3.ZERO);
            st.rFlying = false;
            return;
        }
        st.rPos = next;
        st.rTravelled += R_SPEED_PER_TICK;

        // 弹道本体：客户端用贴图渲染这个"大"字（朝向固定，不会边飞边转）
        // 字面冒火和拖尾都由客户端根据字形几何生成（服务端不知道镜头朝向）
        sendGlyph(level, true, st.rPos, 2.5, st.rDir);

        if (st.rTravelled >= R_MAX_DISTANCE) {
            vfx(level, "smoke", List.of(st.rPos));
            sendGlyph(level, false, Vec3.ZERO, 0, Vec3.ZERO);
            st.rFlying = false;
        }
    }

    // ------------------------------------------------------------ 释放入口

    private static int cooldownFor(PokemonEntity entity, Skill skill) {
        if (ExpansionCombat.supports(entity)) return ExpansionCombat.cooldown(entity, skill.ordinal());
        if (CharizardManager.isVenusaur(entity)) return VenusaurCombat.cooldown(entity, skill.ordinal());
        if (CharizardManager.isBlastoise(entity)) return BlastoiseCombat.cooldown(entity, skill.ordinal());
        if (CharizardManager.isPikachu(entity)) return PikachuCombat.cooldown(entity, skill.ordinal());
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null ? 0 : switch (skill) {
            case Q -> state.cdQ; case W -> state.cdW; case E -> state.cdE; case R -> state.cdR;
        };
    }

    public static int channelFor(PokemonEntity entity) {
        if (ExpansionCombat.supports(entity)) return ExpansionCombat.channel(entity);
        if (CharizardManager.isVenusaur(entity)) return VenusaurCombat.channel(entity);
        if (CharizardManager.isBlastoise(entity)) return BlastoiseCombat.channel(entity);
        if (CharizardManager.isPikachu(entity)) return PikachuCombat.channel(entity);
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null ? 0 : state.channel;
    }

    /** Keep bot windups/charged skills aimed without re-casting or cancelling their channels. */
    public static void updateAimForBot(PokemonEntity entity, Vec3 aim) {
        if (entity == null || aim == null || !com.pkmoba.duel.BotDuelService.isBot(entity)
                || !Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z)) return;
        AimTracker.set(entity, aim);
        if (CharizardManager.isBlastoise(entity)) BlastoiseCombat.updateAim(entity, aim);
        else if (CharizardManager.isVenusaur(entity)) VenusaurCombat.updateAim(entity, aim);
        else if (CharizardManager.isPikachu(entity)) PikachuCombat.updateAim(entity, aim);
        else {
            State state = s(entity);
            state.channelAim = aim;
            FacingService.request(entity, FacingService.Priority.CHANNEL, aimDirection(entity, aim), 90);
        }
    }

    /** Ready excluding beans: bots may reserve resources instead of spending every regen tick. */
    public static boolean readyForBot(PokemonEntity entity, Skill skill) {
        return entity != null && entity.isAlive() && !entity.isRemoved() && skill != null
                && com.pkmoba.duel.BotDuelService.isBot(entity)
                && channelFor(entity) == 0 && !blocksMovement(entity)
                && cooldownFor(entity, skill) <= 0;
    }

    public static boolean canCastForBot(PokemonEntity entity, Skill skill) {
        if (entity == null || skill == null || !com.pkmoba.duel.BotDuelService.isBot(entity)
                || ExpansionControl.sleeping(entity)) return false;
        if (CharizardManager.isVenusaur(entity)) return VenusaurCombat.canCastForBot(entity, skill);
        if (CharizardManager.isBlastoise(entity)) return BlastoiseCombat.canCastForBot(entity, skill);
        if (ExpansionCombat.supports(entity)) return ExpansionCombat.canCastForBot(entity, skill);
        if (CharizardManager.isPikachu(entity)) return PikachuCombat.canCastForBot(entity, skill);
        State state = STATES.get(entity.getUUID());
        int cooldown = state == null ? 0 : switch (skill) {
            case Q -> state.cdQ;
            case W -> state.cdW;
            case E -> state.cdE;
            case R -> state.cdR;
        };
        float points = state == null ? MAX_POINTS : state.points;
        return (state == null || state.channel == 0 && state.dashTicks == 0)
                && cooldown <= 0 && points >= Skill.cost(entity, skill.ordinal());
    }

    public static boolean tryCastForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity == null || skill == null || aim == null || !canCastForBot(entity, skill)
                || com.pkmoba.pokemon.BallThrow.locked(entity)
                || com.pkmoba.duel.DuelManager.isPreparing(entity)
                || !(entity.level() instanceof ServerLevel)) return false;
        ServerPlayer actor = com.pkmoba.duel.BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        if (actor == null || !actor.isAlive() || actor.level() != entity.level()
                || !Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z)
                || entity.distanceToSqr(aim) > 512.0 * 512.0) return false;
        int oldCooldown = cooldownFor(entity, skill);
        int oldChannel = channelFor(entity);
        castForBot(entity, skill, aim);
        boolean cast = cooldownFor(entity, skill) > oldCooldown
                || oldChannel == 0 && channelFor(entity) != 0;
        if (cast) {
            sendBotCastCue((ServerLevel) entity.level(), entity, skill);
            PkmobaMod.LOGGER.info("[pkmoba-bot] cast source={} species={} skill={} oldChannel={} newChannel={} cooldown={} aim={} tick={}",
                    entity.getId(), entity.getPokemon().getSpecies().getResourceIdentifier(), skill,
                    oldChannel, channelFor(entity), cooldownFor(entity, skill), aim, entity.tickCount);
        }
        return cast;
    }

    private static void sendBotCastCue(ServerLevel level, PokemonEntity entity, Skill skill) {
        String id = "pkmoba:cast/" + entity.getId() + "/" + skill.ordinal();
        Vec3 pos = entity.position();
        EffectPayload cue = new EffectPayload(id, pos.x, pos.y, pos.z, 0, 0, 0);
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(pos) < 128 * 128 && ServerPlayNetworking.canSend(viewer, EffectPayload.TYPE)) {
                ServerPlayNetworking.send(viewer, cue);
            }
        }
    }

    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity == null || aim == null || !com.pkmoba.duel.BotDuelService.isBot(entity)
                || !(entity.level() instanceof ServerLevel)) return;
        ServerPlayer actor = com.pkmoba.duel.BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        if (actor == null || !actor.isAlive() || actor.level() != entity.level()
                || com.pkmoba.pokemon.BallThrow.locked(entity)
                || !Double.isFinite(aim.x) || !Double.isFinite(aim.y)
                || !Double.isFinite(aim.z) || entity.distanceToSqr(aim) > 512.0 * 512.0) return;
        if (ExpansionCombat.supports(entity)) {
            ExpansionCombat.castForBot(entity, skill, aim);
        } else if (CharizardManager.isVenusaur(entity)) {
            VenusaurCombat.castForBot(entity, skill, aim);
        } else if (CharizardManager.isBlastoise(entity)) {
            BlastoiseCombat.castForBot(entity, skill, aim);
        } else if (CharizardManager.isPikachu(entity)) {
            PikachuCombat.castForBot(entity, skill, aim);
        } else {
            cast(actor, entity, skill, aim);
        }
    }

    private static void cast(ServerPlayer player, Skill skill, Vec3 aim) {
        cast(player, CharizardManager.getControlled(player), skill, aim);
    }

    private static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (skill == null || !authorizedActor(player, entity)
                || entity == null || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                || player.level().dimension() != entity.level().dimension()
                || !Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z)
                || entity.distanceToSqr(aim) > 512.0 * 512.0
                || com.pkmoba.pokemon.BallThrow.locked(entity)) return;
        if (entity != null && ExpansionControl.sleeping(entity)) return;
        if (ExpansionCombat.supports(entity)) {
            ExpansionCombat.cast(player, entity, skill, aim);
            return;
        }
        if (CharizardManager.isVenusaur(entity)) {
            VenusaurCombat.cast(player, entity, skill, aim);
            return;
        }
        if (CharizardManager.isPikachu(entity)) {
            PikachuCombat.cast(player, entity, skill, aim);
            return;
        }
        State st = s(entity);
        if (CharizardManager.isBlastoise(entity)) {
            BlastoiseCombat.cast(player, entity, skill, aim);
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();

        // 飞行中再按 E = 中断飞行（不是重新起飞）
        if (st.dashTicks > 0) {
            if (skill == Skill.E) {
                if (st.dashLandingTicks == 0) st.dashLandingTicks = E_LANDING_TICKS;
                st.dashAutoLanding = false;
                PkmobaMod.LOGGER.info("[pkmoba] 飞翔进入主动降落");
                return;
            }
            // E（含降落过程）期间禁止R；不扣点、不启动冷却、不改变飞行。
            if (skill == Skill.R) return;
        }

        // 上一发飞翔的落地攻击还没到点：拒绝再次起飞，避免同一次技能被重复结算

        if (st.channel != 0) {
            // 0.38.4：引导中再按同一个技能不再"取消并立刻进冷却"。
            // 原来这一分支会把 st.channel 清零并写 cdQ/cdR，而客户端长按产生的重复
            // SkillCastPayload 正好每次都命中它 —— 用户看到的就是"长按会执行然后取消"。
            // 现在引导中收到任何施法请求都直接忽略，引导自然走完后才进冷却。
            return;
        }

        int cost = Skill.cost(entity, skill.ordinal());
        if (st.points < cost) {
            return;
        }
        int cd = switch (skill) {
            case Q -> st.cdQ;
            case W -> st.cdW;
            case E -> st.cdE;
            case R -> st.cdR;
        };
        if (cd > 0) {
            return;
        }

        st.points -= cost;
        PkmobaMod.LOGGER.info("[pkmoba] 释放 {} 剩余点数 {}", skill, st.points);

        switch (skill) {
            case Q -> {
                st.channel = 1;
                st.channelTicks = 0;
                st.channelAim = aim;
                // 0.48.5（取代 0.38.1 的"不 snap、火焰跟模型"）：确认施法这一帧就把<b>喷火方向</b>钉死
                // （用户："确认施法锁方向"）：前摇/喷吐期间鼠标再动、AI 或旧移动指令都改不了它，
                // 喷火射线与锥形伤害只读锁定值；CastLock 顺手做了一次起手 snap，模型第一帧就朝锁定方向。
                CastLock.begin(entity, CastLock.Shape.LINE, aimDirection(entity, aim), null, Q_CHANNEL_TICKS);
                sound(level, entity.position(), "cobblemon:move.flamethrower.actor");
                // 0.38.3：动作交给客户端 CharizardSkillAnimator（q_windup -> q_sustain），
                // 服务端只控制节奏（Q_WINDUP_TICKS 后出火），所以不再播 Cobblemon 的 special。
            }
            case W -> {
                // 0.38.4：先进入前摇（channel 2）。落区、成形特效、冷却全部等前摇走完
                // 在 tickChannel 里触发 —— 用户要求"有前摇、有形成特效"，不能按下就落地。
                Vec3 center = tornadoCenter(level, entity, aim);
                if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
                    entity.getNavigation().stop();
                    com.pkmoba.move.MoveOrderService.clear(entity);
                }
                st.channel = 2;
                st.channelTicks = 0;
                st.channelAim = aim;
                // 0.48.5：落点（合法坐标，tornadoCenter 已夹到场地内/地面上）与面向一起钉死；
                // 前摇结束直接用锁定落点成区，不再重读鼠标（"落点锁合法坐标"）。
                CastLock.begin(entity, CastLock.Shape.POINT, aimDirection(entity, aim), center, W_WINDUP_TICKS);
                // 先把落点告诉客户端（负半径 = 预告），前摇期间它在那里画"聚集"
                sendTornadoPreview(level, entity, center);
                sound(level, entity.position(), "cobblemon:move.flamethrower.actor");
            }
            case E -> {
                // 冲刺方向 = 鼠标方向（不再等模型转身）。
                Vec3 dir = aimDirection(entity, aim);
                FacingService.snap(entity, FacingService.Priority.CHANNEL, dir);
                st.dashDir = dir;
                // 0.48.5：位移锁方向 + 锁终点。终点取射程末端、夹回场地内并贴地，
                // 飞行过程中既不转向也不再吃新鼠标（旧的 8°/tick 追鼠标会让轨迹画弧）。
                Vec3 travelEnd = entity.position().add(dir.scale(E_MAX_DISTANCE));
                double endGround = groundBelow(level, entity, travelEnd);
                Vec3 dashEnd = clampToArena(entity, new Vec3(travelEnd.x,
                        Double.isFinite(endGround) ? endGround : entity.getY(), travelEnd.z));
                CastLock.begin(entity, CastLock.Shape.DASH, dir, dashEnd,
                        70 + E_LANDING_TICKS);
                st.dashTicks = 70;
                st.dashAge = 0;
                st.dashLandingTicks = 0;
                st.dashAutoLanding = false;
                st.dashWasNoGravity = entity.isNoGravity();
                st.dashEntity = entity;
                st.dashTravelled = 0;
                st.channelAim = aim;
                com.pkmoba.move.MoveOrderService.clear(entity);
                entity.getNavigation().stop();
                st.dashHit.clear();
                setFlyingFlag(entity, true, st);
                sound(level, entity.position(), "cobblemon:impact.flying");
            }
            case R -> {
                st.channel = 4;
                st.channelTicks = 0;
                st.channelAim = aim;
                // 0.48.5：出手方向在确认施法时锁定（"弹道锁方向"），蓄力期间模型朝向 = 锁定朝向，
                // 蓄满直接沿它发射 —— 不会再出现"伤害方向和自己看到的方向不一致"。
                CastLock.begin(entity, CastLock.Shape.LINE, aimDirection(entity, aim), null, R_CHARGE_TICKS);
                // 0.39：定身型大招起手清掉排队中的地面指令，避免"蓄力结束朝旧点位自己走"。
                com.pkmoba.move.MoveOrderService.clear(entity);
                entity.getNavigation().stop();
                // 0.38.1 的"蓄力期间模型慢慢转向光标、出手沿出手瞬间模型朝向"已被 0.48.5 取代：
                // 方向在确认施法时锁定（CastLock 里已经 snap），蓄力期间模型朝向 = 锁定朝向，
                // 弹道与模型看到的方向严格一致，不会出现"头朝一边、火球飞另一边"。
                sound(level, entity.position(), "cobblemon:move.fireblast.actor");
                // 0.38.3：蓄力/出手动作由客户端 CharizardSkillAnimator（r_charge -> r_blast）负责，
                // 这里不再播 special，避免"喷完又播一个动作"。
            }
        }
    }

    // ------------------------------------------------------------ 伤害与特效

    private record JetRay(Vec3 origin, Vec3 dir, double length) { }

    /**
     * 喷射射线：原点=嘴巴，方向=模型当前正前方。
     *
     * <p>旧签名接的是"鼠标瞄准点"，方向由 eye→光标算出来，所以喷火龙还没转身，
     * 火焰就已经指向光标了，看起来像"火焰跟着鼠标跑"。改成接模型正前方后，
     * 火焰严格长在嘴上、随模型转身扫动。
     */
    private static JetRay jetRay(ServerLevel level, PokemonEntity entity, Vec3 forward) {
        Vec3 dir = forward.lengthSqr() > 1.0E-8 ? forward.normalize() : Vec3.directionFromRotation(0, entity.getYRot());
        if (dir.y != 0) {
            dir = new Vec3(dir.x, 0, dir.z).normalize();
        }
        // 从嘴部出火：眼睛原点前移一小段并略微下移，避免火焰从头顶/眼睛冒出。
        // 0.38.3：前移量回到 1.0 格（配合自制施法动画的前伸幅度，火柱根部正好贴住嘴）。
        Vec3 origin = mouthPosition(entity, dir, Q_JET_FORWARD, MOUTH_DROP);
        Vec3 end = origin.add(dir.scale(Q_RANGE));
        HitResult hit = level.clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        double length = hit.getType() == HitResult.Type.BLOCK
                ? Math.max(0, origin.distanceTo(hit.getLocation()) - 0.02) : Q_RANGE;
        return new JetRay(origin, dir, length);
    }

    private static void coneDamage(ServerLevel level, PokemonEntity entity, Vec3 forward, float amount) {
        JetRay ray = jetRay(level, entity, forward);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(ray.origin, ray.origin).inflate(ray.length), e -> validTarget(e, entity))) {
            Vec3 contact = FlameJetGeometry.contact(target.getBoundingBox(), ray.origin, ray.dir, ray.length);
            if (contact == null) continue;
            // Check the actual AABB contact, not the target's feet or an infinite-height fan.
            HitResult wall = level.clip(new ClipContext(ray.origin, contact,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (wall.getType() == HitResult.Type.BLOCK) continue;
            hurt(level, entity, target, amount);
            fx(level, contact, "cobblemon:flamethrower_targetburst");
        }
    }

    /** 把"大"字弹道的位置和方向发给客户端渲染。 */
    private static void sendGlyph(ServerLevel level, boolean active, Vec3 pos, double size, Vec3 dir) {
        com.pkmoba.network.GlyphPayload payload = new com.pkmoba.network.GlyphPayload(
                active, pos.x, pos.y, pos.z, size, dir.x, dir.y, dir.z);
        Vec3 ref = active ? pos : level.players().isEmpty() ? Vec3.ZERO : level.players().get(0).position();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(ref) < 160 * 160
                    && ServerPlayNetworking.canSend(p, com.pkmoba.network.GlyphPayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    private static void explode(ServerLevel level, PokemonEntity entity, Vec3 center, double radius) {
        vfx(level, "lava", ringPoints(center, radius));
        flameVfx(level, "glyph", Vec3.ZERO, Vec3.ZERO, ringPoints(center, radius * 0.7), "big");
        // 0.39：补"火柱 / 冲击波 / 地面裂纹"三件套，解决用户反馈的"大招特效太单薄，像普通技能"。
        // 全部用原版粒子，不新增资源；数量按半径缩放，远处也不至于刷屏。
        eruption(level, center, radius);
        sound(level, center, "cobblemon:move.fireblast.target");
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius), e -> validTarget(e, entity))) {
            if (level.clip(new ClipContext(center, target.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.BLOCK) {
                continue;
            }
            double d = BasicAttack.closestPoint(target.getBoundingBox(), center).distanceTo(center);
            if (d > radius) continue;
            double t = radius <= 0 ? 0 : d / radius;
            float amount = (float) (R_DAMAGE_CENTER - (R_DAMAGE_CENTER - R_DAMAGE_EDGE) * t);
            // 0.39：R 大字爆是终极大招，命中反馈按 ULTIMATE 走（最强顿帧/震屏/推镜）。
            float hpBefore = target.getHealth();
            hurt(level, entity, target, amount,
                    EffectPayload.Element.FIRE, EffectPayload.Power.ULTIMATE);
            if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
                PkmobaMod.LOGGER.info("[pkmoba-bot] hit skill=R source={} target={} hp={}->{} center={}",
                        entity.getId(), target.getId(), hpBefore, target.getHealth(), center);
            }
        }
    }

    /**
     * R 大字爆炸开时的"火柱喷泉 + 冲击波 + 地面裂纹"（0.39）。
     *
     * <p>为什么要单独做：大字弹道本身是一个发光字面，命中瞬间只有一圈 lava 粒子，
     * 看起来和普通技能差不多。这里在命中点补三层表现：
     * <ol>
     *   <li><b>冲击波</b>：EXPLOSION_EMITTER + 两圈向外扩散的 CLOUD，负责"轰"的一下；</li>
     *   <li><b>火柱</b>：6 根从地面往上窜的 FLAME/SOUL_FIRE_FLAME 柱，负责体积感；</li>
     *   <li><b>地面裂纹</b>：贴地的一圈 LAVA + CAMPFIRE_COSY_SMOKE，负责"地面被烧开"。</li>
     * </ol>
     * 全部按半径缩放，半径越小粒子越少，避免把小范围爆炸画成大场面。
     */
    private static void eruption(ServerLevel level, Vec3 center, double radius) {
        int pillars = Math.max(3, Math.min(8, (int) Math.round(radius * 1.6)));
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                center.x, center.y + 0.4, center.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,
                center.x, center.y + 0.3, center.z, 14 + pillars * 3, radius * 0.45, 0.25, radius * 0.45, 0.06);
        for (int i = 0; i < pillars; i++) {
            double angle = Math.PI * 2.0 * i / pillars + 0.35;
            double distance = radius * (0.35 + 0.55 * ((i % 3) / 2.0));
            Vec3 base = center.add(Math.cos(angle) * distance, 0.05, Math.sin(angle) * distance);
            // 每根火柱：自下而上 3 段，越往上越细、扩散越小。
            for (int step = 0; step < 3; step++) {
                double height = 0.35 + step * 0.75;
                double spread = 0.32 - step * 0.09;
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                        base.x, base.y + height, base.z, 6 - step * 2, spread, 0.12, spread, 0.02);
                if (step == 0) {
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                            base.x, base.y + 0.15, base.z, 3, spread * 0.5, 0.05, spread * 0.5, 0.01);
                }
            }
        }
        // 地面裂纹：贴地一圈熔岩 + 慢速浓烟，形成"地还在烧"的残留。
        int cracks = Math.max(8, pillars * 3);
        for (int i = 0; i < cracks; i++) {
            double angle = Math.PI * 2.0 * i / cracks;
            double distance = radius * (0.55 + 0.35 * ((i % 2 == 0) ? 0.35 : 0.8));
            Vec3 p = center.add(Math.cos(angle) * distance, 0.06, Math.sin(angle) * distance);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA,
                    p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                center.x, center.y + 0.6, center.z, 10, radius * 0.4, 0.3, radius * 0.4, 0.015);
    }

    private static void ringParticles(ServerLevel level, Vec3 center, double radius, String effectId) {
        int points = 12;
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Vec3 p = center.add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius);
            fx(level, p, effectId);
        }
    }

    private static boolean validTarget(LivingEntity e, PokemonEntity self) {
        return BasicAttack.validTarget(e, self);
    }

    private static void hurt(ServerLevel level, PokemonEntity source, LivingEntity target, float amount) {
        if (source == null) return;
        CombatDamage.apply(level, source, target, amount);
    }

    /**
     * 带命中反馈语义的伤害入口（元素/强度由调用方指定）。
     *
     * <p>{@link CombatDamage} 只在 {@code hurt} 真正被接受后才给客户端发一次命中事件，
     * 所以这里只是把"这一下是什么元素、多重"告诉它，不影响任何伤害数值/判定。
     */
    private static void hurt(ServerLevel level, PokemonEntity source, LivingEntity target, float amount,
                             EffectPayload.Element element, EffectPayload.Power power) {
        if (source == null) return;
        CombatDamage.apply(level, source, target, amount, element, power);
    }

    /** 批量发自定义火焰团粒子（一次网络包带多个点）。 */
    private static void flameVfx(ServerLevel level, String mode, Vec3 dir, Vec3 center,
                                 List<Vec3> points, String tier) {
        if (points.isEmpty()) {
            return;
        }
        double[] coords = new double[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            coords[i * 3] = p.x;
            coords[i * 3 + 1] = p.y;
            coords[i * 3 + 2] = p.z;
        }
        com.pkmoba.network.FlamePayload payload = new com.pkmoba.network.FlamePayload(
                mode, dir.x, dir.y, dir.z, center.x, center.y, center.z, tier, coords);
        Vec3 ref = points.get(0);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(ref) < 128 * 128
                    && ServerPlayNetworking.canSend(p, com.pkmoba.network.FlamePayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** 批量发原版粒子（一次网络包带多个点）。 */
    private static void vfx(ServerLevel level, String kind, List<Vec3> points) {
        if (points.isEmpty()) {
            return;
        }
        double[] coords = new double[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            coords[i * 3] = p.x;
            coords[i * 3 + 1] = p.y;
            coords[i * 3 + 2] = p.z;
        }
        com.pkmoba.network.VanillaParticlePayload payload =
                new com.pkmoba.network.VanillaParticlePayload(kind, coords);
        Vec3 ref = points.get(0);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(ref) < 128 * 128
                    && ServerPlayNetworking.canSend(p, com.pkmoba.network.VanillaParticlePayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** 场地边界：沿圆周逐点找地面高度，撒一圈贴地粒子（每 3 tick 一次）。 */
    private static void tickArenaRing(MinecraftServer server) {
        if (!arenaSet || server.getTickCount() % 3 != 0) {
            return;
        }
        PokemonEntity reference = null;
        for (PokemonEntity entity : CharizardManager.getControlledEntities(server)) {
            if (distanceToBorder(entity) <= 12.0F) {
                reference = entity;
                break;
            }
        }
        if (reference == null) {
            return;
        }
        ServerLevel level = (ServerLevel) reference.level();
        int points = 40;
        List<Vec3> pts = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double a = 2.0 * Math.PI * i / points;
            double x = arenaCenter.x + Math.cos(a) * arenaRadius;
            double z = arenaCenter.z + Math.sin(a) * arenaRadius;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(x), (int) Math.floor(z));
            pts.add(new Vec3(x, y + 0.15, z));
        }
        vfx(level, "end_rod", pts);
    }

    private static void fx(ServerLevel level, Vec3 pos, String effectId) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos) < 128 * 128 && ServerPlayNetworking.canSend(p, EffectPayload.TYPE)) {
                ServerPlayNetworking.send(p, new EffectPayload(effectId, pos.x, pos.y, pos.z, 0, 0, 0));
            }
        }
    }

    /** 批量生成同一特效的多个实例：一次网络包带多个点，避免单 tick 几十个包。 */
    private static void fxMany(ServerLevel level, String effectId, List<Vec3> points) {
        if (points.isEmpty()) {
            return;
        }
        double[] coords = new double[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            coords[i * 3] = p.x;
            coords[i * 3 + 1] = p.y;
            coords[i * 3 + 2] = p.z;
        }
        com.pkmoba.network.MultiEffectPayload payload =
                new com.pkmoba.network.MultiEffectPayload(effectId, coords);
        Vec3 ref = points.get(0);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(ref) < 128 * 128
                    && ServerPlayNetworking.canSend(p, com.pkmoba.network.MultiEffectPayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    private static void particleBurst(ServerLevel level, Vec3 pos, net.minecraft.core.particles.SimpleParticleType type, int count) {
        level.sendParticles(type, pos.x, pos.y, pos.z, count, 0.2, 0.2, 0.2, 0.01);
    }

    private static void sound(ServerLevel level, Vec3 pos, String id) {
        SoundEvent event = SoundEvent.createVariableRangeEvent(ResourceLocation.parse(id));
        level.playSound(null, pos.x, pos.y, pos.z, event, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static void playAnim(PokemonEntity entity, String animation) {
        try {
            ((PosableEntity) entity).playAnimation(animation, List.of());
        } catch (Throwable ignored) {
        }
    }

    /** 每个玩家只收到自己那只宝可梦的 HUD 状态。 */
    private static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 0.38.2：不再要求"上帝视角激活"才下发状态。
            // 之前这里 `if (!ServerGodMode.isActive(player)) continue;` 导致非上帝视角玩家
            // 客户端 channel 恒为 0，而冲浪/旋风等"纯 channel 驱动、没有 payload 兜底"的特效
            // 全靠这个通道，于是完全看不到特效。只要有受控宝可梦就下发即可。
            PokemonEntity entity = CharizardManager.getControlled(player);
            // 0.39.4：自由模式（不在对局里）宝可梦也会被打死/摔死，但以前只有对局流程会调 markDead，
            // 于是野外死掉的那只既没记阵亡、也没自动切到下一只 —— 玩家看到的就是
            // "血条停在最后一帧、按 C 也切不动"。这里补上同样的收尾（markDead 内部只生效一次）。
            if (entity != null && !entity.isAlive()) {
                com.pkmoba.pokemon.TeamService.markDead(player, entity);
                entity = CharizardManager.getControlled(player);
            }
            if (entity == null) {
                // 0.39.4：这里以前每 tick 都补一帧 0 血，结果按 C 选中下一只后血条又被压回 0，
                // 看起来就是"HUD 不同步"。阵亡帧已由 markDead 按真实上限精确补过，不再重复下发。
                continue;
            }
            if (ExpansionCombat.supports(entity)) {
                ExpansionCombat.syncHud(player, entity);
            } else if (CharizardManager.isBlastoise(entity)) {
                BlastoiseCombat.syncHud(player, entity);
            } else if (CharizardManager.isVenusaur(entity)) {
                VenusaurCombat.syncHud(player, entity);
            } else if (CharizardManager.isPikachu(entity)) {
                PikachuCombat.syncHud(player, entity);
            } else {
                sendState(player, entity);
            }
        }
    }

    /** Push actual HP immediately after commands, without waiting for the periodic HUD tick. */
    public static void syncPlayer(ServerPlayer player) {
        PokemonEntity entity = CharizardManager.getControlled(player);
        if (entity == null) return;
        if (ExpansionCombat.supports(entity)) ExpansionCombat.syncHud(player, entity);
        else if (CharizardManager.isBlastoise(entity)) BlastoiseCombat.syncHud(player, entity);
        else if (CharizardManager.isVenusaur(entity)) VenusaurCombat.syncHud(player, entity);
        else if (CharizardManager.isPikachu(entity)) PikachuCombat.syncHud(player, entity);
        else sendState(player, entity);
    }

    private static void sendState(ServerPlayer player, PokemonEntity entity) {
        if (player == null || player.connection == null || !ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) {
            return;
        }
        State st = s(entity);
        // channel 用 3 表示"飞行中"，供 UI 显示状态（避免和 Q=1 / R=4 冲突）
        int stateChannel = st.dashTicks > 0 ? 3 : st.channel;
        float charge = st.channel == 4 ? Math.min(1.0F, st.channelTicks / (float) R_CHARGE_TICKS) : 0.0F;
        SkillStatePayload payload = new SkillStatePayload(entity.getHealth(), entity.getMaxHealth(), st.points,
                st.cdQ, st.cdW, st.cdE, st.cdR, stateChannel, charge,
                distanceToBorder(entity),
                (float) arenaCenter.x, (float) arenaCenter.y, (float) arenaCenter.z, (float) arenaRadius);
        ServerPlayNetworking.send(player, payload);
    }

    private static final class Zone {
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final Vec3 pos;
        int ticksLeft;
        int phase;

        Zone(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, Vec3 pos, int ticksLeft) {
            this.dimension = dimension;
            this.pos = pos;
            this.ticksLeft = ticksLeft;
        }
    }

    /** 一只宝可梦的全部战斗状态。 */
    private static final class State {
        float hp;
        float points = MAX_POINTS;
        int cdQ, cdW, cdE, cdR;
        int channel;
        int channelTicks;
        Vec3 channelAim = Vec3.ZERO;
        final List<Zone> zones = new ArrayList<>();

        int dashTicks;
        double dashTravelled;
        Vec3 dashDir = Vec3.ZERO;
        int dashAge;
        int dashLandingTicks;
        boolean dashAutoLanding;
        boolean dashWasNoGravity;
        PokemonEntity dashEntity;
        boolean flightPoseWarningLogged;
        final Set<Integer> dashHit = new HashSet<>();
        /** 飞翔落地攻击的剩余延迟 tick；0 表示没有待结算的落地攻击。 */


        boolean rFlying;
        /** 出膛后被地形挡过一次、已抬高过的标记（只救一次，防止一直往上飘）。 */
        boolean rNudged;
        Vec3 rPos = Vec3.ZERO;
        Vec3 rDir = Vec3.ZERO;
        double rTravelled;
        int rTick;

        State(PokemonEntity entity) {
            this.hp = entity == null ? CHARIZARD_MAX_HP : entity.getHealth();
        }

        /** 实体消失时把还挂着的飞行状态收干净，避免重力/姿态残留在别的实体上。 */
        void abort() {
            if (dashEntity != null) {
                try {
                    setFlyingFlag(dashEntity, false, this);
                    dashEntity.setNoGravity(dashWasNoGravity);
                    dashEntity.getNavigation().stop();
                } catch (Throwable ignored) {
                }
                dashEntity = null;
            }
            dashTicks = 0;
            dashTravelled = 0;
            dashLandingTicks = 0;
            dashAutoLanding = false;
            dashHit.clear();

            zones.clear();
            channel = 0;
            channelTicks = 0;
            rFlying = false;
            rNudged = false;
        }
    }
}

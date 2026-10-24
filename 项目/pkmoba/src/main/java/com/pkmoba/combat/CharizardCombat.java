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
    public static final float CHARIZARD_MAX_HP = 280.0F;
    /** 水箭龟最大血量：重装坦克，靠持续输出与受压反打。 */
    public static final float BLASTOISE_MAX_HP = 340.0F;
    /** 兼容旧调用（无实体上下文时按喷火龙血量）。 */
    public static final float MAX_HP = CHARIZARD_MAX_HP;
    private static final float MAX_POINTS = 10.0F;
    private static final int CD_TICKS = 40;

    /** 按实体取最大血量：喷火龙 280 / 水箭龟 340。 */
    public static float maxHpFor(PokemonEntity entity) {
        return CharizardManager.isBlastoise(entity) ? BLASTOISE_MAX_HP : CHARIZARD_MAX_HP;
    }

    /** 按物种 id 取最大血量（实体已被移除、只剩物种名时用）。 */
    public static float maxHpFor(String speciesId) {
        return Skill.isBlastoise(speciesId) ? BLASTOISE_MAX_HP : CHARIZARD_MAX_HP;
    }

    // ---- 0.37 技能伤害（单次命中 ≤ 对方血量约 10%，四技能一轮 ≤ 45%）----
    /** Q 每跳伤害（5 跳合计 30）。 */
    private static final float Q_TICK_DAMAGE = 6.0F;
    /** W 每跳伤害（10 跳合计 20）。 */
    private static final float W_TICK_DAMAGE = 2.0F;
    /** R 中心命中伤害。 */
    private static final float R_DAMAGE_CENTER = 30.0F;
    /** R 边缘最低伤害（线性衰减）。 */
    private static final float R_DAMAGE_EDGE = 22.0F;

    private static final double Q_RANGE = 10.0;
    private static final double Q_HALF_ANGLE = 20.0;
    private static final float Q_TURN_PER_TICK = 30.0F;
    /** Q 火焰从嘴部喷出：眼睛锚点沿朝向前移的距离。 */
    private static final double MOUTH_FORWARD = 0.55;
    /** Q 火焰从嘴部喷出：相对眼睛锚点下移的距离。 */
    private static final double MOUTH_DROP = 0.22;

    private static final double W_RADIUS = 4.0;
    private static final double W_CAST_RANGE = 8.0;
    private static final int W_DURATION_TICKS = 100;

    private static final double E_SPEED_PER_TICK = 0.6; // 12格/秒
    /** 飞翔的水平总路程：明显拉长（15 → 30 格），飞行途中一直造成伤害。 */
    private static final double E_MAX_DISTANCE = 30.0;
    private static final double E_FINISH_DISTANCE = E_MAX_DISTANCE * 0.15;
    private static final int E_ACCEL_TICKS = 4;
    private static final int E_LANDING_TICKS = 6;
    /** 飞翔途中每次接触造成的伤害（同一趟飞行每个目标只算一次）。 */
    /** 飞翔撞击伤害（0.37：16 -> 14，约占对方血量 5%）。 */
    private static final float E_CONTACT_DAMAGE = 14.0F;
    /** 飞翔途中接触判定的额外半径（贴合实体碰撞箱）。 */
    private static final double E_CONTACT_INFLATE = 1.5;

    private static final double R_SPEED_PER_TICK = 0.6;
    private static final double R_MAX_DISTANCE = 20.0;
    private static final double R_RADIUS_START = 3.0;
    private static final double R_RADIUS_END = 1.0;
    private static final int R_CHARGE_TICKS = 20;

    /** 喷火龙离玩家的最大距离（实体追踪范围默认约 80 格，留足余量）。 */
    public static final double MAX_DISTANCE_FROM_PLAYER = 48.0;

    // ---- 圆形场地（全局：一局共用一个圈）----
    private static Vec3 arenaCenter = Vec3.ZERO;
    private static boolean arenaSet = false;
    private static double arenaRadius = MAX_DISTANCE_FROM_PLAYER;

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

    /** 把任意点截断到场地内（移动指令用）。 */
    public static Vec3 clampToArena(Vec3 pos) {
        if (!arenaSet) {
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

    /** 供 UI：距离场地边界的距离（负数表示已出界）。 */
    public static float distanceToBorder(PokemonEntity entity) {
        if (!arenaSet || entity == null) {
            return 999.0F;
        }
        double dx = entity.getX() - arenaCenter.x;
        double dz = entity.getZ() - arenaCenter.z;
        return (float) (arenaRadius - Math.sqrt(dx * dx + dz * dz));
    }

    private CharizardCombat() {
    }

    public static void init() {
        BlastoiseCombat.init();
        ServerPlayNetworking.registerGlobalReceiver(SkillCastPayload.TYPE, (payload, context) ->
                context.server().execute(() -> cast(context.player(), Skill.byIndex(payload.skill()),
                        new Vec3(payload.x(), payload.y(), payload.z()))));

        ServerPlayNetworking.registerGlobalReceiver(AimPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    PokemonEntity entity = CharizardManager.getControlled(context.player());
                    Vec3 aim = new Vec3(payload.x(), payload.y(), payload.z());
                    if (canControl(context.player(), entity, aim) && CharizardManager.isBlastoise(entity)) {
                        BlastoiseCombat.updateAim(entity, aim);
                    } else if (canControl(context.player(), entity, aim)) {
                        State st = s(entity);
                        if (st.channel == 1 || st.channel == 4 || st.dashTicks > 0) {
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
        return busy || BlastoiseCombat.blocksBasicAttack(entity);
    }

    /** Moving casts (Q) never own locomotion; E and R explicitly do. */
    public static boolean blocksMovement() {
        return blocksMovement(active(null));
    }

    public static boolean blocksMovement(PokemonEntity entity) {
        State st = peek(entity);
        boolean busy = st != null && (st.channel == 4 || st.dashTicks > 0);
        return busy || BlastoiseCombat.blocksMovement(entity);
    }

    private static boolean canControl(ServerPlayer player, PokemonEntity entity, Vec3 aim) {
        return entity != null && ServerGodMode.isActive(player)
                && player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                && player.level().dimension().equals(entity.level().dimension())
                && Double.isFinite(aim.x) && Double.isFinite(aim.y) && Double.isFinite(aim.z)
                && entity.distanceToSqr(aim) <= 128.0 * 128.0;
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
    public static void resetAll() {        BlastoiseCombat.resetAll();
        for (State st : new ArrayList<>(STATES.values())) {
            st.abort();
        }
        STATES.clear();
    }

    /** 只清一只实体的状态。 */
    public static void reset(PokemonEntity entity) {
        if (entity == null) return;
        State st = STATES.remove(entity.getUUID());
        if (st != null) st.abort();
    }

    // ------------------------------------------------------------ 主循环

    private static void tick(MinecraftServer server) {
        // 清理已死亡 / 已移除 / 换局的实体状态
        STATES.entrySet().removeIf(entry -> {
            PokemonEntity entity = byId(server, entry.getKey());
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                entry.getValue().abort();
                return true;
            }
            return false;
        });

        for (PokemonEntity entity : CharizardManager.getControlledEntities(server)) {
            tickEntity(server, entity);
        }
        tickArenaRing(server);
        syncAll(server);
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
        // Cobblemon 会在首 tick 用等级/种族血量曲线重置 MAX_HEALTH（约 32 心），
        // 覆盖对局血量，导致"打一下就死"。这里每 tick 强制回 360，
        // 仅在属性被重置的那一帧回满血，之后正常掉血不再自动回复。
        float maxHp = maxHpFor(entity);
        var maxHpAttr = entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (maxHpAttr != null && Math.abs(maxHpAttr.getBaseValue() - maxHp) > 0.01) {
            maxHpAttr.setBaseValue(maxHp);
            // 只在"活着且被重置成低血量"的那一帧回满；已经 0 血（正在播战败动画）绝不复活。
            if (entity.getHealth() > 0.0F && entity.getHealth() < maxHp) {
                entity.setHealth(maxHp);
            }
        }
        // 旧版本召唤出来的实体可能还带着无敌，这里统一解除
        entity.setInvulnerable(false);
        State st = s(entity);
        st.hp = entity.getHealth();

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
     */
    private static void enforceRangeLimit(PokemonEntity entity, State st) {
        if (!arenaSet) {
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
            turnToward(entity, st.channelAim, Q_TURN_PER_TICK);

            // Q：服务端统一原点/方向/墙截断长度；客户端嘴部仅作短接驳。
            sendJet(level, entity, st.channelAim);
            // 每 12 tick 一跳伤害（5 跳）；命中帧才放爆点，保证"看到的就是打到的"
            if (st.channelTicks == 1 || st.channelTicks % 12 == 0) {
                coneDamage(level, entity, st.channelAim, Q_TICK_DAMAGE);
            }
            if (st.channelTicks >= 60) {
                st.channel = 0;
                st.cdQ = CD_TICKS;
            }
        } else if (st.channel == 4) {
            turnToward(entity, st.channelAim, Q_TURN_PER_TICK);
            particleBurst(level, entity.position().add(0, 1.2, 0), ParticleTypes.FLAME, 4);
            if (st.channelTicks >= R_CHARGE_TICKS) {
                st.channel = 0;
                st.cdR = CD_TICKS;
                launchProjectile(entity, st);
            }
        }
    }

    private static void turnToward(PokemonEntity entity, Vec3 target, float maxDeg) {
        double dx = target.x - entity.getX();
        double dz = target.z - entity.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float desired = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float current = entity.getYRot();
        float delta = Mth.clamp(Mth.wrapDegrees(desired - current), -maxDeg, maxDeg);
        float next = current + delta;
        entity.setYRot(next);
        entity.setYHeadRot(next);
        entity.setYBodyRot(next);
        entity.hurtMarked = true;
    }

    /** Q uses the exact same finite server ray for visuals and damage. */
    private static void sendJet(ServerLevel level, PokemonEntity entity, Vec3 aim) {
        JetRay ray = jetRay(level, entity, aim);
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
                    double d = Math.sqrt(target.distanceToSqr(zone.pos));
                    if (d <= W_RADIUS) {
                        hurt(level, owner, target, W_TICK_DAMAGE);
                        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 25,
                                d < W_RADIUS * 0.5 ? 3 : 1, false, false));
                    }
                }
            }
            return zone.ticksLeft <= 0;
        });
    }

    private static void sendTornado(ServerLevel level, PokemonEntity entity, Vec3 center) {
        TornadoPayload payload = new TornadoPayload(entity.getId(), center.x, center.y, center.z,
                W_RADIUS, W_DURATION_TICKS);
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
        boolean landing = st.dashLandingTicks > 0;
        if (!landing) {
            turnToward(entity, st.channelAim, 8.0F);
            double yaw = Math.toRadians(entity.getYRot());
            st.dashDir = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            if (E_MAX_DISTANCE - st.dashTravelled <= E_FINISH_DISTANCE || st.dashTicks <= E_LANDING_TICKS) {
                st.dashLandingTicks = E_LANDING_TICKS;
                st.dashAutoLanding = true;
                landing = true;
            }
        }
        double remaining = Math.max(0, E_MAX_DISTANCE - st.dashTravelled);
        double factor = landing
                ? (st.dashAutoLanding ? Mth.clamp(remaining / E_FINISH_DISTANCE, 0.25, 1.0)
                : st.dashLandingTicks / (double) E_LANDING_TICKS)
                : Math.min(1.0, st.dashAge / (double) E_ACCEL_TICKS);
        double speed = Math.min(E_SPEED_PER_TICK * factor, E_MAX_DISTANCE - st.dashTravelled);
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
                hurt(level, entity, target, E_CONTACT_DAMAGE);
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

    private static double groundBelow(ServerLevel level, PokemonEntity entity, Vec3 pos) {
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
        st.cdE = CD_TICKS;
    }

    // ------------------------------------------------------------ R 弹道

    private static void launchProjectile(PokemonEntity entity, State st) {
        Vec3 origin = entity.getEyePosition();
        Vec3 dir = st.channelAim.subtract(origin);
        Vec3 flat = new Vec3(dir.x, dir.y * 0.25, dir.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(0, 0, 1);
        }
        st.rDir = flat.normalize();
        st.rPos = origin.add(st.rDir.scale(1.0));
        st.rTravelled = 0;
        st.rTick = 0;
        st.rFlying = true;

        ServerLevel level = (ServerLevel) entity.level();
        sound(level, st.rPos, "cobblemon:move.fireblast.actor");
        playAnim(entity, "special");
    }

    private static void tickProjectile(PokemonEntity entity, State st) {
        if (!st.rFlying) {
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();

        Vec3 next = st.rPos.add(st.rDir.scale(R_SPEED_PER_TICK));
        BlockHitResult wall = level.clip(new ClipContext(st.rPos, next, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity));
        if (wall.getType() == HitResult.Type.BLOCK) {
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

        double progress = Math.min(1.0, st.rTravelled / R_MAX_DISTANCE);
        double radius = R_RADIUS_START - (R_RADIUS_START - R_RADIUS_END) * progress;

        // 弹道本体：客户端用贴图渲染这个"大"字（朝向固定，不会边飞边转）
        // 字面冒火和拖尾都由客户端根据字形几何生成（服务端不知道镜头朝向）
        sendGlyph(level, true, st.rPos, 3.5, st.rDir);

        // 命中判定：用"包围盒 + 半径"而不是到脚点的距离。
        // 到脚点的距离会把身高算进去（弹道在 1.5 格高处，半径缩到 1 格时永远打不中）。
        boolean hit = false;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(st.rPos, st.rPos).inflate(radius + 1.0), e -> validTarget(e, entity))) {
            if (target.getBoundingBox().inflate(radius).contains(st.rPos)) {
                hit = true;
                break;
            }
        }

        if (hit) {
            explode(level, entity, st.rPos, radius);
            sendGlyph(level, false, Vec3.ZERO, 0, Vec3.ZERO);
            st.rFlying = false;
            return;
        }

        if (st.rTravelled >= R_MAX_DISTANCE) {
            vfx(level, "smoke", List.of(st.rPos));
            sendGlyph(level, false, Vec3.ZERO, 0, Vec3.ZERO);
            st.rFlying = false;
        }
    }

    // ------------------------------------------------------------ 释放入口

    private static void cast(ServerPlayer player, Skill skill, Vec3 aim) {
        if (skill == null || !ServerGodMode.isActive(player)) {
            return;
        }
        PokemonEntity entity = CharizardManager.getControlled(player);
        if (!canControl(player, entity, aim)) {
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
            if ((skill == Skill.Q && st.channel == 1) || (skill == Skill.R && st.channel == 4)) {
                st.channel = 0;
                if (skill == Skill.Q) st.cdQ = CD_TICKS;
                else st.cdR = CD_TICKS;
            }
            return;
        }

        if (st.points < skill.cost) {
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

        BasicAttack.interruptSwing(entity);
        st.points -= skill.cost;
        PkmobaMod.LOGGER.info("[pkmoba] 释放 {} 剩余点数 {}", skill, st.points);

        switch (skill) {
            case Q -> {
                st.channel = 1;
                st.channelTicks = 0;
                st.channelAim = aim;
                sound(level, entity.position(), "cobblemon:move.flamethrower.actor");
                playAnim(entity, "special");
            }
            case W -> {
                Vec3 origin = entity.position();
                Vec3 delta = aim.subtract(origin);
                if (delta.length() > W_CAST_RANGE) {
                    aim = origin.add(delta.normalize().scale(W_CAST_RANGE));
                }
                st.zones.add(new Zone(level.dimension(), aim, W_DURATION_TICKS));
                sendTornado(level, entity, aim);
                sound(level, aim, "cobblemon:move.flamethrower.actor");
                playAnim(entity, "special");
                st.cdW = CD_TICKS;
            }
            case E -> {
                Vec3 d = aim.subtract(entity.position());
                Vec3 flat = new Vec3(d.x, 0, d.z);
                if (flat.lengthSqr() < 1.0E-4) {
                    flat = new Vec3(0, 0, 1);
                }
                st.dashDir = flat.normalize();
                st.dashTicks = 70;
                st.dashAge = 0;
                st.dashLandingTicks = 0;
                st.dashAutoLanding = false;
                st.dashWasNoGravity = entity.isNoGravity();
                st.dashEntity = entity;
                st.dashTravelled = 0;
                st.channelAim = aim;
                turnToward(entity, aim, 180);
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
                sound(level, entity.position(), "cobblemon:move.fireblast.actor");
                playAnim(entity, "special");
            }
        }
    }

    // ------------------------------------------------------------ 伤害与特效

    private record JetRay(Vec3 origin, Vec3 dir, double length) { }

    private static JetRay jetRay(ServerLevel level, PokemonEntity entity, Vec3 aim) {
        Vec3 eye = entity.getEyePosition();
        Vec3 flat = new Vec3(aim.x - eye.x, 0, aim.z - eye.z);
        Vec3 dir = flat.lengthSqr() > 1.0E-8 ? flat.normalize()
                : Vec3.directionFromRotation(0, entity.getYRot());
        // 从嘴部出火：眼睛原点前移一小段并略微下移，避免火焰从头顶/眼睛冒出。
        Vec3 origin = eye.add(dir.scale(MOUTH_FORWARD)).add(0, -MOUTH_DROP, 0);
        Vec3 end = origin.add(dir.scale(Q_RANGE));
        HitResult hit = level.clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        double length = hit.getType() == HitResult.Type.BLOCK
                ? Math.max(0, origin.distanceTo(hit.getLocation()) - 0.02) : Q_RANGE;
        return new JetRay(origin, dir, length);
    }

    private static void coneDamage(ServerLevel level, PokemonEntity entity, Vec3 aim, float amount) {
        JetRay ray = jetRay(level, entity, aim);
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
        sound(level, center, "cobblemon:move.fireblast.target");
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius), e -> validTarget(e, entity))) {
            if (level.clip(new ClipContext(center, target.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.BLOCK) {
                continue;
            }
            double d = Math.min(radius, Math.sqrt(target.distanceToSqr(center)));
            double t = radius <= 0 ? 0 : d / radius;
            float amount = (float) (R_DAMAGE_CENTER - (R_DAMAGE_CENTER - R_DAMAGE_EDGE) * t);
            hurt(level, entity, target, amount);
        }
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
            if (!ServerGodMode.isActive(player)) continue;
            PokemonEntity entity = CharizardManager.getControlled(player);
            if (entity == null) {
                // 0.35：实体已移除（阵亡后清场）时，若这只确实阵亡就明确下发 0 血，
                // 否则 HUD 会停在最后一帧的旧血量（例如 13/100）。
                if (com.pkmoba.pokemon.TeamService.isActiveFainted(player)) {
                    sendFaintedState(player);
                }
                continue;
            }
            if (CharizardManager.isBlastoise(entity)) {
                BlastoiseCombat.syncHud(player, entity);
            } else {
                sendState(player, entity);
            }
        }
    }

    private static void sendState(ServerPlayer player, PokemonEntity entity) {
        if (!ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) {
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
        }
    }
}

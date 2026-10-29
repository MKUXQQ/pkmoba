package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.SkillStatePayload;
import com.pkmoba.network.SpoutPayload;
import com.pkmoba.network.StormPayload;
import com.pkmoba.network.WaterEffectPayload;
import com.pkmoba.world.HazardService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 水箭龟四槽（0.38 重做）：Q 加农水炮 / W 高速旋转 / E 冲浪术 / R 喷水。
 *
 * <p>方向规则（用户已批准，0.38.1 起细化）：<b>鼠标 aim 只驱动模型转向</b>
 * （{@link CharizardCombat#aimDirection} + {@link FacingService} 限速），
 * <b>真正的射线/扇形方向与出流起点都绑模型</b>（{@link CharizardCombat#modelForward}
 * 与背部炮口 {@link MouthAnchor#cannonPosition}）。模型朝向只允许 {@link FacingService} 写：
 * 本类不直接写朝向字段，也不置任何强制同步标记。
 *
 * <p>引导通道号与客户端约定，不要改：3=高速旋转、4=加农水炮、5=冲浪术、6=喷水。
 * 通道号通过 {@link SkillStatePayload} 下发，客户端据此播放对应的持续视觉。
 *
 * <p>0.38 删除：水之刃（旧的刀刃列表 / 内部类 / 推进 tick / 伤害常量）与 visual kind 1；
 * 旧 W 的"冰山坠落"领域也一并删除。
 *
 * <p>0.38.5（用户实战反馈）：加农水炮的伤害射线与客户端束流/炮口雾/发射音效都改绑
 * 背部两个真实炮口（geo 定位器 {@code locator_cannon_left/right}，服务端换算见
 * {@link MouthAnchor#cannonPosition}）；W 高速旋转带真实位移，速度对齐喷火龙飞翔
 * （{@code CharizardCombat.E_SPEED_PER_TICK = 0.6} 格/tick）；所有引导结束/中断路径
 * 统一经 {@code endChannel} 清理移动、朝向仲裁与引导状态，避免 E 之后卡死。
 */
public final class BlastoiseCombat {

    // ============================================================ 通道号
    /** 高速旋转（W）的引导通道。 */
    public static final int CHANNEL_SPIN = 3;
    /** 加农水炮（Q）的引导通道。 */
    public static final int CHANNEL_CANNON = 4;
    /** 冲浪术（E）的引导通道。 */
    public static final int CHANNEL_SURF = 5;
    /** 喷水（R）的降雨通道。 */
    public static final int CHANNEL_SPOUT = 6;

    // ============================================================ Q 加农水炮
    /** 射线最大长度（格）。 */
    private static final double CANNON_RANGE = 16.0;
    /** 射线判定半径（格）。 */
    private static final double CANNON_RADIUS = 0.9;
    /** 起手蓄力（前摇）tick 数，期间定身可转向、不放伤害。 */
    private static final int CANNON_CHARGE_TICKS = 14;
    /** 每一跳的伤害（5 跳 × 5 = 25）。 */
    private static final float CANNON_TICK_DAMAGE = 7.0F;
    /** 整段引导时长（蓄力 + 输出 + 收尾）。 */
    private static final int CANNON_TICKS = 54;
    /** 两跳之间的间隔（tick 14/22/30/38/46 各结算一次）。 */
    private static final int CANNON_PULSE_INTERVAL = 8;
    /** 输出段跳数（总伤害 25）。 */
    private static final int CANNON_PULSES = 5;
    /**
     * 束流方向不再读鼠标，而是读模型正前方；本常量只用于"让模型去追鼠标"的转向请求。
     * 6°/tick 让射线随模型缓慢扫过，而不是瞬间甩到鼠标位置。
     */
    private static final float CANNON_TURN_DEG_PER_TICK = 40.0F;
    /** 锁定到敌人时的转向速度：要"正对着出去"，比自由瞄准快一档。 */
    private static final float CANNON_FOCUS_TURN_DEG_PER_TICK = 90.0F;
    /**
     * 左右炮口的轻微外扩：方向 = 模型正前方 + 右向量 × ±该值。
     * 0.06 在 16 格射程上只产生约 1 格的张开，两束看起来是"双管齐射"而不是交叉。
     */
    private static final double CANNON_SPREAD_SIDE = 0.06;
    /** 朝向与释放方向的最大夹角：超过就补正朝向（"永远正对着打"）。 */
    private static final float CANNON_MAX_ANGLE_DEG = 160.0F;

    // ============================================================ W 高速旋转
    /** 旋转总时长。 */
    private static final int SPIN_TICKS = 50;
    /** 伤害跳间隔：每 10 tick 1 点，共 8 点。 */
    private static final int SPIN_DAMAGE_INTERVAL = 10;
    /** 每跳伤害（每目标 5 跳 × 2 = 10）。 */
    private static final float SPIN_TICK_DAMAGE = 4.0F;
    /** 旋转期间受到的伤害减免：只减伤，不免控、不无敌、不位移。 */
    public static final float SPIN_DAMAGE_REDUCTION = 0.20F;
    /** 旋转判定体积（水平 1.3 / 垂直 0.6 / 水平 1.3）。 */
    private static final double SPIN_RADIUS_XZ = 2.0;
    private static final double SPIN_RADIUS_Y = 1.5;
    /** 清钉间隔（起手 + 每 20 tick）。 */
    private static final int SPIN_CLEAR_INTERVAL = 20;
    /** 清钉半径。 */
    private static final double SPIN_CLEAR_RADIUS = 2.5;
    /**
     * 旋转期间的位移速度：与喷火龙飞翔（{@code combat/CharizardCombat.java} 的
     * {@code E_SPEED_PER_TICK = 0.6}，注释"12格/秒"）完全对齐 —— 用户要求"旋转动作与真实位移同步、
     * 速度与飞翔一致"，所以这里直接抄同一个数值，不要单独调手感。
     */
    private static final double SPIN_SPEED_PER_TICK = 0.34;
    /** 位移加速段：对齐飞翔的 {@code E_ACCEL_TICKS = 4}，前 4 tick 线性加速到全速。 */
    private static final int SPIN_ACCEL_TICKS = 4;
    /** 单次旋转的位移上限：对齐飞翔的 {@code E_MAX_DISTANCE = 30.0}，超出后原地转完剩余时长。 */
    private static final double SPIN_MAX_DISTANCE = 30.0;
    /**
     * 旋转期间 MoveOrderService 同一 tick 仍会直线行走（旋转不封锁移动指令），
     * 行走速度抄自 {@code move/MoveOrderService.java}：{@code WALK_SPEED = 0.23}、
     * 目标 >12 格时 {@code SPRINT_SPEED = 0.28}（两者都是"格/tick"的步长）。
     * 自驱位移要扣掉这一份，合计才严格等于飞翔的 0.6 格/tick。
     */
    private static final double MOVE_ORDER_WALK_SHARE = 0.23;
    private static final double MOVE_ORDER_SPRINT_SHARE = 0.28;

    // ============================================================ E 冲浪术
    /** 冲浪分阶段：蓄水起手 + 浪头形成 6 tick（0.3s）、乘浪冲刺 14 tick（0.7s，约 6 格）、终点爆发 6 tick。 */
    private static final int SURF_WINDUP_TICKS = 6;
    private static final int SURF_DASH_TICKS = 14;
    private static final int SURF_BURST_TICKS = 6;
    /** 冲浪总时长。 */
    private static final int SURF_TICKS = SURF_WINDUP_TICKS + SURF_DASH_TICKS + SURF_BURST_TICKS;
    /** 每 tick 前进速度（格/tick）。 */
    private static final double SURF_STEP = 0.44;
    /** 前方撞墙探针长度。 */
    private static final double SURF_WALL_PROBE = 0.3;
    /** 浪体单目标整趟伤害。 */
    private static final float SURF_DAMAGE = 20.0F;
    /** 击退强度（0 = 设计上不击退）。 */
    private static final double SURF_KNOCKBACK = 0.0;
    /** 浪体判定体积（水平 1.2 / 垂直 0.8 / 水平 1.2）。 */
    private static final double SURF_HIT_XZ = 1.2;
    private static final double SURF_HIT_Y = 0.8;
    /** 服务端水花：模型前方范围（6 格长、宽 4 格 → 半宽 2 格）。 */
    private static final double SURF_SPRAY_LENGTH = 6.0;
    private static final double SURF_SPRAY_HALF_WIDTH = 2.0;
    /** 每次水花的 SPLASH 采样点 / CLOUD 采样点（每 2 tick 一次，别每 tick 撒）。 */
    private static final int SURF_SPRAY_SPLASH_POINTS = 14;
    private static final int SURF_SPRAY_CLOUD_POINTS = 3;
    /** 水花广播半径（格）。 */
    private static final double SURF_SPRAY_RADIUS = 64.0;
    /** E 的有限转向：每 tick 最多把锁定方向掰多少度（不是瞬间甩头）。 */
    private static final float SURF_STEER_DEG_PER_TICK = 8.0F;
    /** 结束溅射：两侧小幅伤害 + 轻微击退。 */
    private static final float SURF_SPLASH_DAMAGE = 8.0F;
    private static final double SURF_SPLASH_RADIUS = 2.4;
    private static final double SURF_SPLASH_KNOCKBACK = 0.35;
    /** 终点爆发的扇形半角：60° → 全角 120°（已批准方案 §二-2）。 */
    private static final float SURF_SPLASH_HALF_ANGLE_DEG = 60.0F;

    // ============================================================ R 喷水
    /** R 的模型锁定时长：蓄力、破水和短暂收势。 */
    private static final int SPOUT_WINDUP_TICKS = BlastoiseCombat.STORM_CHARGE_TICKS
            + BlastoiseCombat.STORM_RISE_TICKS + 6;
    /** 降雨持续时间（前摇结束后的独立结算段）。 */
    private static final int SPOUT_DURATION_TICKS = 24;
    /** 阶段 2/6：地下水流传导（0.2s），只出水纹、不结算伤害。 */
    private static final int SPOUT_CONDUCT_TICKS = 4;
    /** 阶段 6/6：消散（0.4s），水柱顶部破碎、水纹由远至近淡出，无判定。 */
    private static final int SPOUT_FADE_TICKS = 8;
    /** 整轮总时长 = 蓄力 0.6s + 传导 0.2s + 水柱 1.2s + 消散 0.4s。 */
    private static final int SPOUT_TOTAL_TICKS =
            SPOUT_WINDUP_TICKS + SPOUT_CONDUCT_TICKS + SPOUT_DURATION_TICKS + SPOUT_FADE_TICKS;
    /** 波间隔。 */
    private static final int SPOUT_WAVE_INTERVAL = 2;
    /** 总波数（40 / 2）。 */
    private static final int SPOUT_WAVE_COUNT = 20;
    /** 每波水滴数。 */
    private static final int SPOUT_DROPS_PER_WAVE = 8;
    /** 落点距离下限（格）。 */
    private static final double SPOUT_MIN_DISTANCE = 2.5;
    /** 扇形半径（格）。 */
    private static final double SPOUT_LENGTH = 8.0;
    /** 三段距离分界：8→5.2 远段、5.2→2.8 中段、2.8→2.5 近段。 */
    private static final double SPOUT_MID_DISTANCE = 5.2;
    private static final double SPOUT_NEAR_DISTANCE = 2.8;
    /** 每段 8 tick，共 3 段 24 tick。 */
    private static final int SPOUT_PHASE_TICKS = 8;
    /** 三段伤害：远 5 / 中 7 / 近 9（逐段提高，同一目标每段最多一次）。 */
    private static final float[] SPOUT_PHASE_DAMAGE = {5.0F, 7.0F, 9.0F};
    /** 扇形半角（±30°）。 */
    private static final float SPOUT_HALF_ANGLE_DEG = 30.0F;
    /** 同波落点最小间距。 */
    private static final double SPOUT_DROP_SPACING = 1.2;
    /** 同波落点重采样次数上限。 */
    private static final int SPOUT_DROP_RESAMPLE = 8;
    /** 水滴初始高度范围。 */
    private static final double SPOUT_DROP_HEIGHT_MIN = 6.0;
    private static final double SPOUT_DROP_HEIGHT_MAX = 9.0;
    /** 水滴下落：初速 0.45，每 tick 加速 0.04，上限 0.75 格/tick。 */
    private static final double SPOUT_DROP_START_SPEED = 0.45;
    private static final double SPOUT_DROP_ACCEL = 0.04;
    private static final double SPOUT_DROP_MAX_SPEED = 0.75;
    /** 落地判定半径。 */
    private static final double SPOUT_DROP_RADIUS = 0.8;
    /** 每颗水滴伤害。 */
    private static final float SPOUT_DROP_DAMAGE = 1.0F;
    /** 每目标整轮命中上限。 */
    private static final int SPOUT_HIT_CAP = 20;
    /** 首次命中减速等级 II、时长 60t；后续只刷新时长不叠等级。 */
    private static final int SPOUT_SLOW_TICKS = 60;
    private static final int SPOUT_SLOW_AMPLIFIER = 1;

    // ---- R water storm (0.43.0): rotating vortex centred on the caster ----
    /** Radius in blocks; the damage ring and the client effect ring share it. */
    private static final double STORM_RADIUS = 6.0;
    private static final int STORM_CHARGE_TICKS = 12;
    private static final int STORM_RISE_TICKS = 6;
    private static final int STORM_BODY_TICKS = 44;
    private static final int STORM_COLLAPSE_TICKS = 8;
    private static final int STORM_RECOVER_TICKS = 6;
    private static final int STORM_TOTAL_TICKS = STORM_CHARGE_TICKS + STORM_RISE_TICKS
            + STORM_BODY_TICKS + STORM_COLLAPSE_TICKS + STORM_RECOVER_TICKS;
    private static final int STORM_PULSE_INTERVAL = 8;
    private static final float STORM_PULSE_DAMAGE = 5.0F;
    private static final float STORM_BURST_DAMAGE = 18.0F;
    private static final double STORM_KNOCKBACK = 0.45;

    // ============================================================ 资源 / 冷却
    /** 满技能点。 */
    private static final float MAX_POINTS = 10.0F;
    /** 每 20 tick 回复 1 点。 */
    private static final int POINT_REGEN_TICKS = 20;
    /** Q/W/E 冷却（R 见 {@link #cooldownTicks(int)}）。 */
    private static final int CD_QWE = 40;
    /** R 冷却。 */
    private static final int CD_R = 60;
    /** 引导超时看门狗宽限：超过通道硬上限这么多 tick 还没结束就强制收尾。 */
    private static final int WATCHDOG_GRACE_TICKS = 20;

    /** 炮口相对眼睛的偏移：沿用旧版嘴部常量（前移 0.9、下移 0.25）。 */
    private static final double MUZZLE_FORWARD = 0.9;
    private static final double MUZZLE_DROP = 0.25;

    /** 施法时播放的模型动画名（Cobblemon 通用特殊动作）。 */
    private static final String ANIM_SPECIAL = "special";

    /** 每只受控水箭龟的独立状态；不要把冷却/点数挂到全局。 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    private BlastoiseCombat() {
    }

    public static void init() {
        resetAll();
    }

    private static State state(PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity));
    }

    // ================================================================ 对外入口

    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (!authorized(player, entity)) return;
        castCore(entity, skill, aim);
    }

    public static boolean canCastForBot(PokemonEntity entity, Skill skill) {
        if (entity == null || skill == null || !entity.isAlive() || entity.isRemoved()
                || !com.pkmoba.duel.BotDuelService.isBot(entity)) return false;
        // Spawn/reset intentionally drops cached states; eligibility must initialize the same
        // state castCore/tick use instead of permanently rejecting a fresh bot.
        State state = state(entity);
        int slot = skill.ordinal();
        return (state.channel == 0 || state.channel == CHANNEL_SPIN && skill != Skill.W)
                && state.cooldowns[slot] <= 0 && state.points >= Skill.cost(entity, slot);
    }

    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity == null || aim == null || !com.pkmoba.duel.BotDuelService.isBot(entity)) return;
        ServerPlayer actor = com.pkmoba.duel.BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        if (!authorized(actor, entity)) return;
        castCore(entity, skill, aim);
    }

    private static boolean authorizedActor(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null
                || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))) return false;
        if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
            return player == com.pkmoba.duel.BotDuelService.actor(player.getUUID());
        }
        return ServerGodMode.isActive(player);
    }

    private static boolean authorized(ServerPlayer player, PokemonEntity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved() && player != null
                && player.isAlive() && !player.isSpectator() && authorizedActor(player, entity)
                && player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                && player.level().dimension().equals(entity.level().dimension());
    }

    private static void castCore(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity.level() instanceof ServerLevel level) state(entity).castCore(entity, level, skill, aim);
    }

    /** 鼠标瞄准点更新：只有 Q 的引导期会跟随（E/R 起手锁向，W 不需要方向）。 */
    public static void updateAim(PokemonEntity entity, Vec3 aim) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        if (state != null) state.updateAim(entity, aim);
    }

    public static void tick(ServerLevel level, PokemonEntity entity) {
        // 即使调用方不再 tick 已移除实体，也回收其降雨与状态。
        for (Iterator<State> it = STATES.values().iterator(); it.hasNext(); ) {
            State state = it.next();
            if (!state.entity.isAlive() || state.entity.isRemoved()) {
                state.reset();
                it.remove();
            }
        }
        if (entity == null || !entity.isAlive() || entity.isRemoved() || entity.level() != level) return;
        state(entity).tick(level, entity);
    }

    public static void syncHud(ServerPlayer player, PokemonEntity entity) {
        if (player != null && player.connection != null && entity != null
                && ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) {
            state(entity).syncHud(player);
        }
    }

    public static void reset(PokemonEntity entity) {
        State state = entity == null ? null : STATES.remove(entity.getUUID());
        if (state != null) state.reset();
        // 0.48.5：状态表里已经取不到实体（或本来就没有状态）时，锁也要清。
        CastLock.release(entity);
    }

    public static int cooldown(PokemonEntity entity, int slot) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null || slot < 0 || slot >= state.cooldowns.length ? 0 : state.cooldowns[slot];
    }

    public static int channel(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null ? 0 : state.channel;
    }

    public static void clearTransient(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        if (state != null) state.clearTransient();
    }

    public static void resetAll() {
        for (State state : STATES.values()) state.reset();
        STATES.clear();
        HazardService.clearAll();
    }

    /** 换人 / 对局结束 / 收回时统一中断（文档 §9.3；与 {@link #resetAll()} 等价）。 */
    public static void interruptAll() {
        resetAll();
    }

    /**
     * 站桩输出：引导中的技能锁住地面移动指令（用户 m05844：不许大部分技能边跑边打）。
     *
     * <p>例外只有 W 高速旋转 —— 它的设计本来就是"变球滚着走"，不锁。
     * 冲浪（E）虽然是自驱位移，地面指令照样要锁，位移由技能 tick 自己写。
     */
    public static boolean blocksMovement(PokemonEntity entity) {
        int c = channel(entity);
        return c != 0 && c != CHANNEL_SPIN;
    }

    public static boolean blocksBasicAttack(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state != null && state.blocksBasicAttack();
    }

    /** 该实体是否正在高速旋转（供伤害减免判定用）。 */
    public static boolean isSpinningPokemon(LivingEntity entity) {
        if (!(entity instanceof PokemonEntity pokemon)) return false;
        State state = STATES.get(pokemon.getUUID());
        return state != null && state.channel == CHANNEL_SPIN;
    }

    // ================================================================ 单只状态

    /** 单只水箭龟的冷却、点数、引导段与降雨实例。 */
    private static final class State {
        private final PokemonEntity entity;
        private final int[] cooldowns = new int[4];
        private float points = MAX_POINTS;
        private int age;
        private int channel;
        private int skillTicks;
        /** Q 引导期跟随的鼠标瞄准点；null 表示退化为模型朝向。 */
        private Vec3 aimPoint;
        /** E/R 起手锁定的水平方向（途中不可转）。 */
        private Vec3 lockedDir = Vec3.ZERO;
        /** R 的扇形锚点（施法瞬间位置，整轮固定）。 */
        private Vec3 spoutOrigin = Vec3.ZERO;
        /** 冲浪整趟已命中的目标，保证每目标只吃一次 18。 */
        private final Set<UUID> surfHits = new HashSet<>();
        /** 终点爆发的独立命中集合：撞击（路径）与终点爆发各自最多结算一次（§二-2）。 */
        private final Set<UUID> surfBurstHits = new HashSet<>();
        /** W 每个目标的下一跳可命中 tick（各目标独立，10t 一跳）。 */
        private final Map<UUID, Integer> spinHitAt = new HashMap<>();
        /** 当前存活的降雨实例（R 结束后继续独立结算，最多持续 40t）。 */
        private final List<Spout> spouts = new ArrayList<>();
        /** Running R storms (normally one at a time; a list makes cleanup uniform). */
        private final List<Storm> storms = new ArrayList<>();
        /** 加农水炮锁定的敌人：两条炮口束流都朝它汇聚，模型也一直正对它。 */
        private LivingEntity cannonFocus;

        private State(PokemonEntity entity) {
            this.entity = entity;
        }

        // ---- 状态查询 ----

        private boolean isBusy() {
            return channel != 0;
        }

        /** 旋转期间允许移动（零自驱位移，但玩家可以走）；其余引导定身。 */
        private boolean blocksMovement() {
            return channel == CHANNEL_CANNON || channel == CHANNEL_SURF || channel == CHANNEL_SPOUT;
        }

        private boolean blocksBasicAttack() {
            return channel != 0;
        }

        /** 每个通道的硬时长上限（看门狗用），与各自 tick 里的结束条件一致。 */
        private int channelHardLimit() {
            return switch (channel) {
                case CHANNEL_SPIN -> SPIN_TICKS;
                case CHANNEL_CANNON -> CANNON_TICKS;
                case CHANNEL_SURF -> SURF_TICKS;
                case CHANNEL_SPOUT -> SPOUT_WINDUP_TICKS;
                default -> 0;
            };
        }

        private void reset() {
            clearTransient();
            java.util.Arrays.fill(cooldowns, 0);
            points = MAX_POINTS;
            age = 0;
        }

        private void clearTransient() {
            if (entity.level() instanceof ServerLevel level) {
                visual(level, entity, 0, entity.position(), Vec3.ZERO, 0, 0);
                // durationTicks = 0：通知客户端立即结束这轮降雨（死亡/换人/对局结束）。
                for (Spout spout : spouts) {
                    broadcastSpout(level, spout.origin, new SpoutPayload(entity.getId(),
                            spout.origin.x, spout.origin.y, spout.origin.z, spout.yaw(),
                            SPOUT_HALF_ANGLE_DEG, (float) SPOUT_LENGTH, 0, spout.seed));
                }
                broadcastStorm(level, entity, 0, 0L);
                if (channel != 0) {
                    entity.getNavigation().stop();
                    entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
                }
            }
            spouts.clear();
            storms.clear();
            surfHits.clear();
            surfBurstHits.clear();
            channel = 0;
            skillTicks = 0;
            aimPoint = null;
            lockedDir = Vec3.ZERO;
            spoutOrigin = Vec3.ZERO;
            spinHitAt.clear();
            // 0.48.5：死亡 / 换人 / 对局结束统一释放施法锁（release 内部会清 FacingService）。
            CastLock.release(entity);
        }

        // ---- 每 tick ----

        private void tick(ServerLevel level, PokemonEntity entity) {
            age++;
            if (age % POINT_REGEN_TICKS == 0) points = Math.min(MAX_POINTS, points + 1.0F);
            for (int i = 0; i < cooldowns.length; i++) {
                if (cooldowns[i] > 0) cooldowns[i]--;
            }
            // 兜底看门狗：通道超过自身硬上限 + 宽限仍非 0，说明某条 tick 路径提前 return/异常了；
            // 强制 endChannel，避免"channel 永远收不掉 → 放不出技能、也动不了"的卡死回归。
            if (channel != 0 && skillTicks > channelHardLimit() + WATCHDOG_GRACE_TICKS) {
                endChannel(level, entity);
            }
            switch (channel) {
                case CHANNEL_SPIN -> tickSpin(level, entity);
                case CHANNEL_CANNON -> tickCannon(level, entity);
                case CHANNEL_SURF -> tickSurf(level, entity);
                case CHANNEL_SPOUT -> tickSpoutWindup(level, entity);
                default -> {
                    // 空闲：R 的降雨仍可能在下，统一在末尾 tickSpouts 结算。
                }
            }
            tickSpouts(level, entity);
            tickStorms(level, entity);
        }

        private void updateAim(PokemonEntity entity, Vec3 aim) {
            // 0.48.5：施法锁定期间不再接受新鼠标 —— 方向/落点在确认施法那一帧已定，
            // 否则"引导中鼠标一转，炮口和伤害就跟着漂"（用户："确认施法锁方向，别被新鼠标覆盖"）。
            if (channel != CHANNEL_CANNON && CastLock.active(entity)) return;
            if (channel != CHANNEL_CANNON && channel != CHANNEL_SPIN && channel != CHANNEL_SPOUT) return;
            if (!validAim(entity, aim)) return;
            aimPoint = aim;
        }

        // ---- 施法仲裁 ----

        private void castCore(PokemonEntity entity, ServerLevel level, Skill skill, Vec3 aim) {
            if (skill == null || level == null) return;
            if (channel == CHANNEL_SPIN) {
                // 旋转期间再按 W：立即结束，不扣点、不进 CD。
                if (skill == Skill.W) {
                    endChannel(level, entity);
                    return;
                }
                // 其它技能：先中断旋转，再走正常施法流程（验收 §12-4「按 E 立即转冲浪」）。
                endChannel(level, entity);
            } else if (isBusy()) {
                return;
            }
            int slot = skill.ordinal();
            int cost = Skill.cost(entity, slot);
            if (cooldowns[slot] > 0 || points < cost) return;
            Vec3 dir = direction(entity, aim);
            points -= cost;
            cooldowns[slot] = cooldownTicks(slot);
            aimPoint = validAim(entity, aim) ? aim : null;
            lockedDir = dir;
            spoutOrigin = entity.position();
            skillTicks = 0;
            surfHits.clear();
            FacingService.snap(entity, FacingService.Priority.CHANNEL, dir);
            // 0.48.5：确认施法这一帧钉死本技能的几何量（见 CastLock）：
            //   Q 水炮 = 直线（落点 = 起手时的合法瞄准点）；
            //   W 旋转 / R 喷水 = 自身中心 AOE（只锁面向，落点跟着自己走）；
            //   E 冲浪 = 位移（方向 + 终点都锁，途中不再 8°/tick 追鼠标画弧）。
            switch (skill) {
                case Q -> CastLock.release(entity); // Q alone continuously steers toward the mouse.
                case W -> CastLock.begin(entity, CastLock.Shape.SELF, dir, null, -1);
                case E -> {
                    Vec3 travelEnd = entity.position().add(dir.scale(SURF_STEP * SURF_DASH_TICKS));
                    double ground = CharizardCombat.groundBelow(level, entity, travelEnd);
                    // 0.48.5：按"这只宝可梦所属场地"裁剪（多房间并发时不能拿全局边界裁别人的终点）。
                    Vec3 surfEnd = CharizardCombat.clampToArena(entity, new Vec3(travelEnd.x,
                            Double.isFinite(ground) ? ground : entity.getY(), travelEnd.z));
                    CastLock.begin(entity, CastLock.Shape.DASH, dir, surfEnd, -1);
                }
                case R -> CastLock.begin(entity, CastLock.Shape.SELF, dir, null, -1);
            }
            switch (skill) {
                case Q -> {
                    channel = CHANNEL_CANNON;
                    // 水炮不自动锁敌，也不播放会扭动炮口的一次性 special 动作。
                    MoveOrderService.cancelPending(entity);
                    holdPosition(entity);
                }
                case W -> {
                    channel = CHANNEL_SPIN;
                    spinHitAt.clear();
                }
                case E -> {
                    channel = CHANNEL_SURF;
                    holdPosition(entity);
                    animate(entity, ANIM_SPECIAL);
                }
                case R -> {
                    channel = CHANNEL_SPOUT;
                    // R keeps its cast channel through the storm's charge and rise; this keeps
                    // the model braced until the actual water columns break the surface.
                    com.pkmoba.move.MoveOrderService.cancelPending(entity);
                    holdPosition(entity);
                    animate(entity, ANIM_SPECIAL);
                    long seed = entity.getRandom().nextLong();
                    storms.add(new Storm(seed));
                    broadcastStorm(level, entity, STORM_TOTAL_TICKS, seed);
                    splashSound(level, entity.position());
                }
            }
            // 音效起点与出流起点同源：加农水炮的发射声从两个炮口之间发出，
            // 客户端束流/炮口雾/命中音也用同一对炮口锚点，不会"听起来在脚下、看着在背上"。
            if (channel == CHANNEL_CANNON) {
                splashSound(level, cannonMuzzle(entity, true).lerp(cannonMuzzle(entity, false), 0.5));
            } else {
                splashSound(level, entity.position());
            }
        }

        private void endChannel(ServerLevel level, PokemonEntity entity) {
            visual(level, entity, 0, entity.position(), Vec3.ZERO, 0, 0);
            channel = 0;
            skillTicks = 0;
            lockedDir = Vec3.ZERO;
            spinHitAt.clear();
            cannonFocus = null;
            // 引导结束/被打断时把施法锁定与残余速度一起清掉：保证角色立刻能移动、能普攻、能放别的技能。
            entity.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
            // 0.48.5：收招再锁 2 tick 再交还预瞄（不要一结束就被鼠标猛地甩走）；
            // 施法锁本身由 AimTracker 每 tick 重申，到期自动释放。
            CastLock.finish(entity, 2);
            FacingService.clear(entity);
        }

        // ---- Q 加农水炮 ----

        private void tickCannon(ServerLevel level, PokemonEntity entity) {
            holdPosition(entity);
            // Q is a steerable channel: model, barrel anchors, visuals and damage use the same latest aim.
            Vec3 steer = aimPoint == null ? CharizardCombat.modelForward(entity)
                    : CharizardCombat.aimDirection(entity, aimPoint);
            FacingService.force(entity, steer);
            Vec3 lockedPoint = aimPoint;
            Vec3 mid = cannonMuzzle(entity, true).lerp(cannonMuzzle(entity, false), 0.5);
            // 炮口到锁定落点的三维方向直接用于伤害和视觉，不等待模型插值追上。
            Vec3 toAim = lockedPoint == null ? steer : lockedPoint.subtract(mid);
            Vec3 forward = toAim.lengthSqr() < 0.04 ? steer : toAim.normalize();
            if (skillTicks < CANNON_CHARGE_TICKS) {
                if (skillTicks % 2 == 0) {
                    visual(level, entity, 5, cannonMuzzle(entity, true), forward, 0, 0.6);
                    visual(level, entity, 5, cannonMuzzle(entity, false), forward, 0, 0.6);
                }
            } else {
                int outputTick = skillTicks - CANNON_CHARGE_TICKS;
                boolean pulse = outputTick % CANNON_PULSE_INTERVAL == 0
                        && outputTick / CANNON_PULSE_INTERVAL < CANNON_PULSES;
                float damage = pulse ? CANNON_TICK_DAMAGE : 0.0F;
                // 两束共用 claimed：同一次脉冲里同一个目标只结算一次，保持"5 跳 × 5 点 = 25"的原预算。
                Set<UUID> claimed = new HashSet<>();
                // Both barrels converge on one mouse-directed point. Never extend a barrel ray
                // past that point, otherwise the two jets cross and separate again.
                double reach = lockedPoint == null ? CANNON_RANGE
                        : Math.max(0.75, Math.min(CANNON_RANGE, toAim.length()));
                Vec3 end = clip(level, entity, mid, mid.add(forward.scale(reach)));
                // Stop the entire paired stream at the nearest obstruction seen by either barrel.
                double fraction = 1.0;
                for (boolean left : new boolean[]{true, false}) {
                    Vec3 origin = cannonMuzzle(entity, left);
                    double full = origin.distanceTo(end);
                    if (full > 0.001) fraction = Math.min(fraction,
                            origin.distanceTo(clip(level, entity, origin, end)) / full);
                }
                end = mid.lerp(end, fraction);
                cannonBeam(level, entity, end, true, damage, claimed);
                cannonBeam(level, entity, end, false, damage, claimed);
                visual(level, entity, 4, mid, forward, mid.distanceTo(end), CANNON_RADIUS);
            }
            skillTicks++;
            if (skillTicks >= CANNON_TICKS) endChannel(level, entity);
        }

        /**
         * 解析这一炮要锁定的敌人：取瞄准点附近最近的合法目标。
         *
         * <p>用户 m03997 要求水炮"永远朝敌人正着出去"、"两束最后集中到敌人身上"，
         * 所以起手时把瞄准点附近的人记住，整个引导期都用他做方向基准。
         */
        private LivingEntity findCannonFocus(ServerLevel level, PokemonEntity entity) {
            Vec3 aim = aimPoint;
            if (aim == null) aim = entity.position().add(CharizardCombat.modelForward(entity).scale(6.0));
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(aim, aim).inflate(3.5), e -> BasicAttack.validTarget(e, entity))) {
                double d = candidate.getBoundingBox().getCenter().distanceToSqr(aim);
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
            return best;
        }

        /** 背部炮口世界坐标：左/右语义与客户端 {@code locator_cannon_left/right} 一致。 */
        private Vec3 cannonMuzzle(PokemonEntity entity, boolean left) {
            return MouthAnchor.cannonPosition(entity, left);
        }

        /**
         * 单条炮口束流：起点是该炮口，终点是两束共用的鼠标落点（最多 16 格）。
         * 每束独立裁剪墙体，命中体积按半径膨胀；客户端从活体炮口连接同一个终点。
         *
         * @return 这一束实际打到的长度（被墙截断时 &lt; {@link #CANNON_RANGE}），供调用方合并视觉长度
         */
        private double cannonBeam(ServerLevel level, PokemonEntity entity, Vec3 sharedEnd, boolean left,
                                  float damage, Set<UUID> claimed) {
            Vec3 from = cannonMuzzle(entity, left);
            Vec3 unit = sharedEnd.subtract(from).normalize();
            Vec3 end = clip(level, entity, from, sharedEnd);
            double reach = from.distanceTo(end);
            if (damage <= 0) return reach;
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(from, end).inflate(CANNON_RADIUS), e -> BasicAttack.validTarget(e, entity))) {
                if (claimed.contains(target.getUUID())) continue;
                AABB box = target.getBoundingBox().inflate(CANNON_RADIUS);
                if (!box.contains(from) && box.clip(from, end).isEmpty()) continue;
                Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), from);
                if (!clear(level, entity, from, contact)) continue;
                if (CombatDamage.apply(level, entity, target, damage)) {
                    claimed.add(target.getUUID());
                    fx(level, contact, unit, "cobblemon:hydropump_targetburst");
                }
            }
            return reach;
        }

        /**
         * 模型想转去的方向：鼠标 aim 优先，没有有效 aim 时退回模型正前方。
         *
         * <p>0.38.1 起这个值<b>只用于驱动模型转向</b>，不再直接作为束流方向 —— 束流读
         * {@link CharizardCombat#modelForward}，所以"转多快"完全由 FacingService 的限速决定。
         */
        /** 把 from 水平方向朝 to 方向最多转 maxDeg 度（有限转向的通用实现）。 */
        private static Vec3 rotateTowards(Vec3 from, Vec3 to, float maxDeg) {
            Vec3 a = horizontal(from);
            Vec3 b = horizontal(to);
            if (a.lengthSqr() < 1.0E-8 || b.lengthSqr() < 1.0E-8) return a.lengthSqr() < 1.0E-8 ? b : a;
            a = a.normalize();
            b = b.normalize();
            double dot = Mth.clamp(a.dot(b), -1.0, 1.0);
            double angle = Math.toDegrees(Math.acos(dot));
            if (angle <= maxDeg || angle < 1.0E-3) return b.lengthSqr() < 1.0E-8 ? a : b;
            double t = maxDeg / angle;
            Vec3 mixed = a.scale(1.0 - t).add(b.scale(t));
            return mixed.lengthSqr() < 1.0E-8 ? b : mixed.normalize();
        }

        private Vec3 steerDirection(PokemonEntity entity) {
            Vec3 aim = aimPoint;
            if (aim != null && validAim(entity, aim)) {
                Vec3 dir = CharizardCombat.aimDirection(entity, aim);
                if (dir.lengthSqr() > 1.0E-8) return horizontal(dir);
            }
            return CharizardCombat.modelForward(entity);
        }

        // ---- W 高速旋转 ----

        private void tickSpin(ServerLevel level, PokemonEntity entity) {
            // W 允许缓慢移动（MoveOrderService 指令照常生效），但绝不自己造位移冲刺：
            // 用户明确要求"旋转不能变成位移冲刺"，所以这里没有任何 entity.move。
            damageSpin(level, entity);
            if (skillTicks % 2 == 0) {
                visual(level, entity, 3, entity.position(), CharizardCombat.modelForward(entity), 0, 1.0);
                // 旋转特效加强：脚下水涡 + 外抛水滴，让"转起来"这件事看得见。
                double a = skillTicks * 0.55;
                double rr = 1.6 + 0.35 * Math.sin(skillTicks * 0.3);
                Vec3 swirl = entity.position().add(Math.cos(a) * rr, 0.25, Math.sin(a) * rr);
                water(level, swirl, 4, 0.24);
                water(level, entity.position().add(-Math.cos(a) * rr, 0.5, -Math.sin(a) * rr), 3, 0.18);
            }
            skillTicks++;
            if (skillTicks >= SPIN_TICKS) {
                // 结束时清除范围内符合规则的敌方场地陷阱（只在收尾清一次）。
                int cleared = HazardService.clearNear(entity.position(), SPIN_CLEAR_RADIUS);
                if (cleared > 0) {
                    water(level, entity.position(), 10, 0.5);
                    splashSound(level, entity.position());
                }
                endChannel(level, entity);
            }
        }

        private void damageSpin(ServerLevel level, PokemonEntity entity) {
            Vec3 center = entity.getBoundingBox().getCenter();
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    entity.getBoundingBox().inflate(SPIN_RADIUS_XZ, SPIN_RADIUS_Y, SPIN_RADIUS_XZ),
                    e -> BasicAttack.validTarget(e, entity))) {
                int next = spinHitAt.getOrDefault(target.getUUID(), 0);
                if (skillTicks < next) continue;
                Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), center);
                if (!clear(level, entity, center, contact)) continue;
                if (CombatDamage.apply(level, entity, target, SPIN_TICK_DAMAGE)) {
                    spinHitAt.put(target.getUUID(), skillTicks + SPIN_DAMAGE_INTERVAL);
                    water(level, contact, 6, 0.3);
                }
            }
        }

        // ---- E 冲浪术 ----

        private void tickSurf(ServerLevel level, PokemonEntity entity) {
            entity.getNavigation().stop();
            if (lockedDir.lengthSqr() < 1.0E-8) lockedDir = CharizardCombat.modelForward(entity);
            // 阶段 1/2：蓄水起手 + 浪头形成 —— 原地不动，只出水纹、气泡和前方向上涌的浪头。
            if (skillTicks < SURF_WINDUP_TICKS) {
                float progress = skillTicks / (float) SURF_WINDUP_TICKS;
                FacingService.request(entity, FacingService.Priority.CHANNEL, lockedDir, 90.0F);
                entity.setDeltaMovement(0.0, entity.getDeltaMovement().y, 0.0);
                if (skillTicks % 2 == 0) surfChargeFx(level, entity, progress);
                skillTicks++;
                return;
            }
            // 阶段 5/6：终点浪花爆发 + 恢复待机 —— 停在原地收尾。
            if (skillTicks >= SURF_WINDUP_TICKS + SURF_DASH_TICKS) {
                entity.setDeltaMovement(0.0, entity.getDeltaMovement().y, 0.0);
                if (skillTicks == SURF_WINDUP_TICKS + SURF_DASH_TICKS) {
                    surfBurst(level, entity);
                }
                skillTicks++;
                if (skillTicks >= SURF_TICKS) stopSurf(level, entity);
                return;
            }
            // 阶段 3：乘浪冲刺（14 tick × 0.44 ≈ 6 格）。
            // 0.48.5：位移锁方向 —— 方向取自施法那一帧（CastLock），途中鼠标/AI 都改不了，
            // 轨迹是直线、终点就是起手时算好的合法落点。
            Vec3 dir = CastLock.dirOr(entity, lockedDir.lengthSqr() > 1.0E-8
                    ? lockedDir : CharizardCombat.modelForward(entity));
            lockedDir = dir;
            FacingService.request(entity, FacingService.Priority.CHANNEL, dir, 60.0F);
            Vec3 before = entity.position();
            // 前方 0.3 格撞墙：提前结束（已结算的伤害保留）。
            Vec3 probeFrom = before.add(0, entity.getBbHeight() * 0.5, 0);
            HitResult wall = level.clip(new ClipContext(probeFrom, probeFrom.add(dir.scale(SURF_WALL_PROBE)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (wall.getType() == HitResult.Type.BLOCK) {
                stopSurf(level, entity);
                return;
            }
            Vec3 next = before.add(dir.scale(SURF_STEP));
            // 0.48.5：终点真的按施法锁定值（CastLock.DASH 的 point）走 —— 最后一步直接落到锁定终点，
            // 不是"只记了一笔然后按固定 tick 数乱飘"。锁定被硬清时退回按步长前进。
            Vec3 lockEnd = CastLock.point(entity);
            boolean reachedEnd = false;
            if (lockEnd != null) {
                double toEnd = Math.sqrt((lockEnd.x - before.x) * (lockEnd.x - before.x)
                        + (lockEnd.z - before.z) * (lockEnd.z - before.z));
                if (toEnd <= SURF_STEP) {
                    next = new Vec3(lockEnd.x, before.y, lockEnd.z);
                    reachedEnd = true;
                }
            }
            double ground = CharizardCombat.groundBelow(level, entity, next);
            double dy = Double.isFinite(ground) ? Mth.clamp(ground - before.y, -0.2, 0.15) : 0.0;
            Vec3 wanted = new Vec3(next.x, before.y + dy, next.z);
            // 0.48.5：按本实体所属场地裁剪（多房间并发时不能用全局边界）。
            Vec3 clamped = CharizardCombat.clampToArena(entity, wanted);
            // 出界：贴边结束。
            boolean outOfArena = clamped.distanceToSqr(wanted) > 1.0E-6;
            Vec3 delta = new Vec3(clamped.x - before.x, clamped.y - before.y, clamped.z - before.z);
            if (!level.noCollision(entity, entity.getBoundingBox().move(delta))) {
                stopSurf(level, entity);
                return;
            }
            entity.move(MoverType.SELF, delta);
            entity.fallDistance = 0.0F;
            damageSurf(level, entity);
            // 0.38.1：服务端也要出水花（第三人称/没装客户端特效的玩家都能看到），每 2 tick 一次。
            if (skillTicks % 2 == 0) surfSpray(level, entity);
            skillTicks++;
            if (reachedEnd || outOfArena || skillTicks >= SURF_TICKS) stopSurf(level, entity);
        }

        /**
         * 冲浪服务端水花：沿模型正前方、以实体为中心铺 6 格长 × 4 格宽的一层贴地 SPLASH，
         * 再补少量 CLOUD 作为浪头白沫；只广播给 64 格内的玩家，避免整服发包。
         */
        private void surfSpray(ServerLevel level, PokemonEntity entity) {
            Vec3 dir = CharizardCombat.modelForward(entity);
            Vec3 right = right(dir);
            Vec3 base = entity.position();
            for (int i = 0; i < SURF_SPRAY_SPLASH_POINTS; i++) {
                Vec3 point = sprayPoint(entity, base, dir, right, i);
                double ground = CharizardCombat.groundBelow(level, entity, point);
                double y = Double.isFinite(ground) ? ground + 0.12 : base.y + 0.1;
                broadcastParticles(level, ParticleTypes.SPLASH, point.x, y, point.z, 0.4, 0.15, 0.4, 0.01);
            }
            for (int i = 0; i < SURF_SPRAY_CLOUD_POINTS; i++) {
                Vec3 point = sprayPoint(entity, base, dir, right, i + SURF_SPRAY_SPLASH_POINTS);
                double ground = CharizardCombat.groundBelow(level, entity, point);
                double y = Double.isFinite(ground) ? ground + 0.25 : base.y + 0.25;
                broadcastParticles(level, ParticleTypes.CLOUD, point.x, y, point.z, 0.3, 0.05, 0.3, 0.01);
            }
        }

        /** 冲浪用的朝向：优先锁定的施法方向，没有就用模型正前方。 */
        private Vec3 surfDir(PokemonEntity entity) {
            return lockedDir.lengthSqr() > 1.0E-8 ? lockedDir : CharizardCombat.modelForward(entity);
        }

        /** 阶段 1/2：脚下水纹向外扩散 + 气泡 + 前方浪头从 0 长到约 1.2 格高。 */
        private void surfChargeFx(ServerLevel level, PokemonEntity entity, float progress) {
            Vec3 base = entity.position();
            Vec3 dir = surfDir(entity);
            Vec3 right = right(dir);
            double radius = 0.5 + 1.35 * progress;
            for (int i = 0; i < 10; i++) {
                double angle = i / 10.0 * Math.PI * 2.0;
                Vec3 outward = right.scale(Math.cos(angle)).add(dir.scale(Math.sin(angle)));
                Vec3 point = base.add(outward.scale(radius)).add(0, 0.06, 0);
                broadcastParticles(level, ParticleTypes.SPLASH, point.x, point.y, point.z,
                        outward.x * 0.06, 0.02, outward.z * 0.06, 0.02);
            }
            for (int i = 0; i < 6; i++) {
                double side = (i / 5.0 - 0.5) * (1.2 + 1.8 * progress);
                Vec3 point = base.add(dir.scale(0.9 + 0.5 * progress)).add(right.scale(side));
                double ground = CharizardCombat.groundBelow(level, entity, point);
                double y = (Double.isFinite(ground) ? ground : base.y) + 0.10 + 0.85 * progress;
                broadcastParticles(level, ParticleTypes.SPLASH, point.x, y, point.z, 0.02, 0.06, 0.02, 0.03);
                if (progress > 0.45 && i % 2 == 0) {
                    broadcastParticles(level, ParticleTypes.BUBBLE, point.x, y - 0.35, point.z, 0.1, 0.1, 0.1, 0.02);
                }
            }
            Vec3 crest = base.add(dir.scale(1.1 + 0.45 * progress)).add(0, 0.35 + 1.05 * progress, 0);
            broadcastParticles(level, ParticleTypes.CLOUD, crest.x, crest.y, crest.z, 0.55, 0.12, 0.55, 0.01);
        }

        /** 阶段 5：终点浪花向左右两侧炸开 + 地面水纹扩散 + 溅射判定。 */
        private void surfBurst(ServerLevel level, PokemonEntity entity) {
            Vec3 base = entity.position();
            Vec3 dir = surfDir(entity);
            Vec3 right = right(dir);
            surfEndSplash(level, entity);
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < 8; i++) {
                    double spread = i / 7.0;
                    Vec3 point = base.add(dir.scale(0.5 + 1.0 * spread))
                            .add(right.scale(side * (0.35 + 1.7 * spread)))
                            .add(0, 0.28 + 0.55 * spread, 0);
                    broadcastParticles(level, ParticleTypes.SPLASH, point.x, point.y, point.z,
                            side * 0.16, 0.22, side * 0.16, 0.14);
                    broadcastParticles(level, ParticleTypes.CLOUD, point.x, point.y + 0.2, point.z,
                            0.1, 0.05, 0.1, 0.02);
                }
            }
            for (int i = 0; i < 16; i++) {
                double angle = i / 16.0 * Math.PI * 2.0;
                Vec3 outward = right.scale(Math.cos(angle)).add(dir.scale(Math.sin(angle)));
                Vec3 point = base.add(outward.scale(1.15)).add(0, 0.08, 0);
                broadcastParticles(level, ParticleTypes.SPLASH, point.x, point.y, point.z,
                        outward.x * 0.3, 0.14, outward.z * 0.3, 0.22);
            }
            water(level, base.add(dir.scale(0.8)), 24, 1.0);
            splashSound(level, base);
        }

        /** 水花采样点：沿前方按 i 铺开，左右在 ±2 格内随机抖动。 */
        private Vec3 sprayPoint(PokemonEntity entity, Vec3 base, Vec3 dir, Vec3 right, int i) {
            double along = (i + 1.0) / (SURF_SPRAY_SPLASH_POINTS + SURF_SPRAY_CLOUD_POINTS)
                    * SURF_SPRAY_LENGTH;
            double side = (entity.getRandom().nextDouble() * 2.0 - 1.0) * SURF_SPRAY_HALF_WIDTH;
            return base.add(dir.scale(along)).add(right.scale(side));
        }

        /** 逐玩家发送粒子包（半径 64 内），等价于"附近玩家都看得到"，但不惊动远处玩家。 */
        private void broadcastParticles(ServerLevel level, ParticleOptions particle, double x, double y,
                                        double z, double spreadXZ, double spreadY, double spreadZ,
                                        double speed) {
            double radiusSqr = SURF_SPRAY_RADIUS * SURF_SPRAY_RADIUS;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(x, y, z) <= radiusSqr) {
                    level.sendParticles(player, particle, true, x, y, z, 1,
                            spreadXZ, spreadY, spreadZ, speed);
                }
            }
        }

        private void damageSurf(ServerLevel level, PokemonEntity entity) {
            AABB body = entity.getBoundingBox().inflate(SURF_HIT_XZ, SURF_HIT_Y, SURF_HIT_XZ);
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, body,
                    e -> BasicAttack.validTarget(e, entity))) {
                if (surfHits.contains(target.getUUID())) continue;
                Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(),
                        entity.getBoundingBox().getCenter());
                if (!clear(level, entity, entity.getBoundingBox().getCenter(), contact)) continue;
                if (!CombatDamage.apply(level, entity, target, SURF_DAMAGE)) continue;
                surfHits.add(target.getUUID());
                if (SURF_KNOCKBACK > 0) {
                    target.push(lockedDir.x * SURF_KNOCKBACK, 0.25, lockedDir.z * SURF_KNOCKBACK);
                }
                water(level, contact, 18, 0.6);
                splashSound(level, contact);
                fx(level, contact, lockedDir, "cobblemon:hydropump_targetburst");
            }
        }

        private void stopSurf(ServerLevel level, PokemonEntity entity) {
            // 走完六阶段时终点爆发已经结算过溅射，这里只在"提前终止"（撞墙/出界）时补一次。
            if (skillTicks < SURF_WINDUP_TICKS + SURF_DASH_TICKS) {
                surfEndSplash(level, entity);
            }
            water(level, entity.position(), 16, 0.8);
            splashSound(level, entity.position());
            endChannel(level, entity);
        }

        /** 冲浪结束的溅射：两侧小幅伤害 + 轻微击退（不是大击退，也不是全屏）。 */
        private void surfEndSplash(ServerLevel level, PokemonEntity entity) {
            Vec3 center = entity.position();
            Vec3 dir = lockedDir.lengthSqr() > 1.0E-8 ? lockedDir : CharizardCombat.modelForward(entity);
            Vec3 side = right(dir);
            double cosLimit = Math.cos(Math.toRadians(SURF_SPLASH_HALF_ANGLE_DEG));
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    entity.getBoundingBox().inflate(SURF_SPLASH_RADIUS, 1.2, SURF_SPLASH_RADIUS),
                    e -> BasicAttack.validTarget(e, entity))) {
                // 终点只炸前方 120° 扇形，与客户端水花同向（§二-2）。
                Vec3 toTarget = horizontal(target.position().subtract(center));
                if (toTarget.lengthSqr() < 1.0E-8 || toTarget.normalize().dot(dir) < cosLimit) continue;
                if (surfBurstHits.contains(target.getUUID())) continue;
                Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), center);
                if (!clear(level, entity, center, contact)) continue;
                if (!CombatDamage.apply(level, entity, target, SURF_SPLASH_DAMAGE)) continue;
                surfBurstHits.add(target.getUUID());
                double sign = target.position().subtract(center).dot(side) >= 0 ? 1.0 : -1.0;
                target.push(side.x * SURF_SPLASH_KNOCKBACK * sign, 0.2,
                        side.z * SURF_SPLASH_KNOCKBACK * sign);
                water(level, contact, 12, 0.5);
            }
        }

        // ---- R 喷水 ----

        private void tickSpoutWindup(ServerLevel level, PokemonEntity entity) {
            holdPosition(entity);
            // 0.48.5：喷水从确认施法起就锁面向（不再"蓄力期间还能用鼠标调朝向"），
            // 蓄力、喷发、收招看到的都是同一个方向，扇形伤害也与它一致。
            Vec3 dir = CastLock.dirOr(entity, CharizardCombat.modelForward(entity));
            FacingService.request(entity, FacingService.Priority.CHANNEL, dir, 40.0F);
            Vec3 from = mouth(entity, dir);
            if (skillTicks % 2 == 0) visual(level, entity, 5, from, dir, 0, 0.55);
            if (skillTicks % 4 == 0) spoutTelegraph(level, entity, dir);
            skillTicks++;
            if (skillTicks >= SPOUT_WINDUP_TICKS) releaseStorm(level, entity);
        }

        /** 前摇结束：恢复行动、广播降雨区域，雨滴由服务端独立结算。 */
        private void releaseSpout(ServerLevel level, PokemonEntity entity) {
            // 0.38.1：扇形中心 = 释放瞬间的模型正前方（之后整轮锁定，不逐 tick 变）。
            // 0.48.5：方向改读施法锁定值 —— 与蓄力 telegraph、模型朝向完全一致。
            Vec3 dir = CastLock.dirOr(entity, CharizardCombat.modelForward(entity));
            lockedDir = dir;
            Vec3 origin = spoutOrigin.lengthSqr() > 1.0E-8 ? spoutOrigin : entity.position();
            long seed = entity.getRandom().nextLong();
            Spout spout = new Spout(origin, dir, seed);
            spouts.add(spout);
            broadcastSpout(level, origin, new SpoutPayload(entity.getId(),
                    origin.x, origin.y, origin.z, spout.yaw(), SPOUT_HALF_ANGLE_DEG,
                    (float) SPOUT_LENGTH, SPOUT_TOTAL_TICKS, seed));
            splashSound(level, entity.position());
            water(level, mouth(entity, dir), 20, 0.6);
            endChannel(level, entity);
        }

        /** 地面扇形 telegraph：3 圈弧线，前摇期间每 4 tick 补一次。 */
        private void spoutTelegraph(ServerLevel level, PokemonEntity entity, Vec3 dir) {
            Vec3 origin = spoutOrigin.lengthSqr() > 1.0E-8 ? spoutOrigin : entity.position();
            Vec3 right = right(dir);
            for (int ring = 1; ring <= 3; ring++) {
                double distance = SPOUT_LENGTH * ring / 3.0;
                int points = 4 + ring * 4;
                for (int i = 0; i <= points; i++) {
                    double theta = Math.toRadians(-SPOUT_HALF_ANGLE_DEG
                            + 2.0 * SPOUT_HALF_ANGLE_DEG * i / points);
                    Vec3 flat = dir.scale(Math.cos(theta)).add(right.scale(Math.sin(theta)));
                    Vec3 point = origin.add(flat.scale(distance));
                    double ground = CharizardCombat.groundBelow(level, entity, point);
                    if (!Double.isFinite(ground)) continue;
                    level.sendParticles(ParticleTypes.SPLASH, point.x, ground + 0.15, point.z,
                            1, 0.0, 0.05, 0.0, 0.0);
                }
            }
        }

        /** Windup finished: start a 3.8s vortex; the pacing lives inside {@link Storm}. */
        private void releaseStorm(ServerLevel level, PokemonEntity entity) {
            // The storm has been running visually during charge/rise; this is the release impact.
            water(level, entity.position(), 26, 0.7);
            splashSound(level, entity.position());
            if (com.pkmoba.duel.BotDuelService.isBot(entity))
                com.pkmoba.PkmobaMod.LOGGER.info("[pkmoba-bot] release skill=waterR source={} tick={} activeStorms={}",
                        entity.getId(), entity.tickCount, storms.size());
            endChannel(level, entity);
        }

        private void tickStorms(ServerLevel level, PokemonEntity entity) {
            for (Iterator<Storm> it = storms.iterator(); it.hasNext(); ) {
                if (!it.next().tick(level, entity)) it.remove();
            }
        }

        private void broadcastStorm(ServerLevel level, PokemonEntity entity, int durationTicks, long seed) {
            StormPayload payload = new StormPayload(entity.getId(), durationTicks,
                    (float) STORM_RADIUS, seed);
            for (var player : level.players()) {
                if (player.distanceToSqr(entity) <= 128.0 * 128.0) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
                }
            }
        }

        /**
         * One water storm. It follows the caster (so the turtle can still walk slowly) and
         * mirrors the client timeline: charge 12 -> rise 6 -> body 44 (a 4-damage ring every
         * 8 ticks) -> collapse 8 (one 10-damage burst + knockback) -> recover 6.
         */
        private final class Storm {
            private final Random random;
            private final Set<UUID> pulseHits = new HashSet<>();
            private int ticks;
            private int lastPulse = -1;
            private boolean burstDone;

            private Storm(double seed) {
                this.random = new Random((long) (seed * 1_000_000L));
            }

            private boolean tick(ServerLevel level, PokemonEntity caster) {
                ticks++;
                if (ticks > STORM_TOTAL_TICKS) return false;
                Vec3 center = caster.position();
                int bodyStart = STORM_CHARGE_TICKS + STORM_RISE_TICKS;
                int bodyEnd = bodyStart + STORM_BODY_TICKS;
                if (ticks > STORM_CHARGE_TICKS && ticks < bodyEnd) {
                    caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 4, 3, false, false, false));
                }
                if (ticks >= bodyStart && ticks < bodyEnd) {
                    int pulse = (ticks - bodyStart) / STORM_PULSE_INTERVAL;
                    if (pulse != lastPulse) {
                        lastPulse = pulse;
                        pulseHits.clear();
                        ringPulse(level, caster, center);
                    }
                }
                if (ticks >= bodyEnd && ticks < bodyEnd + STORM_COLLAPSE_TICKS && !burstDone) {
                    burstDone = true;
                    burst(level, caster, center);
                }
                return ticks <= STORM_TOTAL_TICKS;
            }

            /** One ring of ticks: every enemy inside the radius takes 4 water damage. */
            private void ringPulse(ServerLevel level, PokemonEntity caster, Vec3 center) {
                double r2 = STORM_RADIUS * STORM_RADIUS;
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                        caster.getBoundingBox().inflate(STORM_RADIUS + 1.0),
                        e -> BasicAttack.validTarget(e, caster))) {
                    Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), center.add(0, .8, 0));
                    if (contact.subtract(center).horizontalDistanceSqr() > r2
                            || target.getBoundingBox().minY > center.y + 5
                            || target.getBoundingBox().maxY < center.y - 1) continue;
                    if (pulseHits.contains(target.getUUID())) continue;
                    if (!clear(level, caster, center.add(0, .8, 0), target.getBoundingBox().getCenter())) continue;
                    float before = target.getHealth();
                    if (CombatDamage.apply(level, caster, target, STORM_PULSE_DAMAGE)) {
                        pulseHits.add(target.getUUID());
                    }
                    if (com.pkmoba.duel.BotDuelService.isBot(caster))
                        com.pkmoba.PkmobaMod.LOGGER.info("[pkmoba-bot] hit skill=waterR source={} target={} hp={}->{} pulse={}",
                                caster.getId(), target.getId(), before, target.getHealth(), lastPulse);
                }
            }

            /** Collapse: one 10-damage hit (counts as the ultimate hit, so it shakes) + knockback. */
            private void burst(ServerLevel level, PokemonEntity caster, Vec3 center) {
                double limit = STORM_RADIUS + 0.6;
                double r2 = limit * limit;
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                        caster.getBoundingBox().inflate(STORM_RADIUS + 2.0),
                        e -> BasicAttack.validTarget(e, caster))) {
                    Vec3 delta = target.position().subtract(center);
                    Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), center.add(0, .8, 0));
                    if (contact.subtract(center).horizontalDistanceSqr() > r2
                            || target.getBoundingBox().minY > center.y + 5
                            || target.getBoundingBox().maxY < center.y - 1) continue;
                    if (!clear(level, caster, center.add(0, .8, 0), target.getBoundingBox().getCenter())) continue;
                    boolean hit = CombatDamage.apply(level, caster, target, STORM_BURST_DAMAGE,
                            com.pkmoba.network.EffectPayload.Element.WATER,
                            com.pkmoba.network.EffectPayload.Power.HEAVY);
                    if (!hit) continue;
                    Vec3 flat = new Vec3(delta.x, 0.0, delta.z);
                    if (flat.lengthSqr() > 1.0E-6) {
                        flat = flat.normalize().scale(STORM_KNOCKBACK);
                        target.push(flat.x, 0.28, flat.z);
                    }
                    water(level, target.position(), 14, 0.55);
                }
                splashSound(level, center);
                water(level, center, 40, 0.9);
            }
        }

        private void tickSpouts(ServerLevel level, PokemonEntity entity) {
            for (Iterator<Spout> it = spouts.iterator(); it.hasNext(); ) {
                if (!it.next().tick(level, entity)) it.remove();
            }
        }

        /** 一轮喷水降雨：锚点/方向/种子固定，结束条件只依赖时间与还在下落的水滴。 */
        private final class Spout {
            private final Vec3 origin;
            private final Vec3 dir;
            private final Vec3 right;
            private final long seed;
            private final Random random;
            /** 每目标整轮命中数（新施法 new Spout 时自然清空）。 */
            private final Map<UUID, Integer> hits = new HashMap<>();
            private final List<RainDrop> drops = new ArrayList<>();
            private int ticks;
            private int waves;
            /** 当前阶段：0 远段 / 1 中段 / 2 近段（每段 SPOUT_PHASE_TICKS tick）。 */
            private int phase;

            private Spout(Vec3 origin, Vec3 dir, long seed) {
                this.origin = origin;
                this.dir = dir;
                this.right = right(dir);
                this.seed = seed;
                this.random = new Random(seed);
            }

            private float yaw() {
                return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            }

            /** @return false 表示整轮降雨结束，可以移除 */
            private boolean tick(ServerLevel level, PokemonEntity caster) {
                int body = ticks - SPOUT_CONDUCT_TICKS;
                if (body < 0) {
                    // 阶段 2/6：地下水流传导 —— 水纹由近至远流动，纯视觉、不结算伤害。
                    conductRipple(level, caster);
                } else if (body < SPOUT_DURATION_TICKS) {
                    // 阶段 3~5/6：近 / 中 / 远三段水柱，每段 8 tick，伤害逐段提高。
                    this.phase = Math.min(2, body / SPOUT_PHASE_TICKS);
                    if (waves < SPOUT_WAVE_COUNT && body % SPOUT_WAVE_INTERVAL == 0) {
                        spawnWave(level, caster);
                        waves++;
                    }
                } else if (ticks == SPOUT_CONDUCT_TICKS + SPOUT_DURATION_TICKS) {
                    // 阶段 6/6：消散 —— 顶部破碎 + 远端余波，此后不再有任何判定。
                    fadeBurst(level, caster);
                }
                tickDrops(level, caster, body < SPOUT_DURATION_TICKS);
                ticks++;
                return body < SPOUT_DURATION_TICKS || !drops.isEmpty();
            }

            /** 阶段 2/6：水纹由近至远铺开，读数靠"传导距离"，与最终扇形完全同源。 */
            private void conductRipple(ServerLevel level, PokemonEntity caster) {
                double progress = (ticks + 1) / (double) Math.max(1, SPOUT_CONDUCT_TICKS);
                double distance = SPOUT_MIN_DISTANCE
                        + (SPOUT_LENGTH - SPOUT_MIN_DISTANCE) * progress;
                int points = 12;
                for (int i = 0; i <= points; i++) {
                    double theta = Math.toRadians(-SPOUT_HALF_ANGLE_DEG
                            + 2.0 * SPOUT_HALF_ANGLE_DEG * i / points);
                    Vec3 flat = dir.scale(Math.cos(theta)).add(right.scale(Math.sin(theta)));
                    Vec3 point = origin.add(flat.scale(distance));
                    double ground = CharizardCombat.groundBelow(level, caster, point);
                    if (!Double.isFinite(ground)) continue;
                    level.sendParticles(ParticleTypes.SPLASH, point.x, ground + 0.12, point.z,
                            1, 0.12, 0.04, 0.12, 0.01);
                }
            }

            /** 阶段 6/6：由远至近的三圈余波，只出视觉，不结算伤害。 */
            private void fadeBurst(ServerLevel level, PokemonEntity caster) {
                for (int ring = 3; ring >= 1; ring--) {
                    double distance = SPOUT_LENGTH * ring / 3.0;
                    Vec3 point = origin.add(dir.scale(distance));
                    double ground = CharizardCombat.groundBelow(level, caster, point);
                    if (!Double.isFinite(ground)) continue;
                    water(level, new Vec3(point.x, ground + 0.25, point.z), 8, 0.35);
                }
            }

            private void spawnWave(ServerLevel level, PokemonEntity caster) {
                Vec3 from = origin.add(0, 1.5, 0);
                List<Vec3> landings = new ArrayList<>(SPOUT_DROPS_PER_WAVE);
                for (int i = 0; i < SPOUT_DROPS_PER_WAVE; i++) {
                    Vec3 landing = pickLanding(level, caster, from, landings);
                    if (landing == null) continue;
                    landings.add(landing);
                    double height = SPOUT_DROP_HEIGHT_MIN + random.nextDouble()
                            * (SPOUT_DROP_HEIGHT_MAX - SPOUT_DROP_HEIGHT_MIN);
                    drops.add(new RainDrop(landing.add(0, height, 0), landing.y, phase));
                }
            }

            private Vec3 pickLanding(ServerLevel level, PokemonEntity caster, Vec3 from, List<Vec3> placed) {
                for (int attempt = 0; attempt < SPOUT_DROP_RESAMPLE; attempt++) {
                    // 三段由远到近：远段 8→5.2、中段 5.2→2.8、近段 2.8→2.5。
                    double lo = phase == 0 ? SPOUT_MID_DISTANCE
                            : (phase == 1 ? SPOUT_NEAR_DISTANCE : SPOUT_MIN_DISTANCE);
                    double hi = phase == 0 ? SPOUT_LENGTH
                            : (phase == 1 ? SPOUT_MID_DISTANCE : SPOUT_NEAR_DISTANCE);
                    double distance = lo + random.nextDouble() * Math.max(0.05, hi - lo);
                    double theta = Math.toRadians((random.nextDouble() * 2.0 - 1.0)
                            * SPOUT_HALF_ANGLE_DEG);
                    Vec3 flat = dir.scale(Math.cos(theta)).add(right.scale(Math.sin(theta)));
                    Vec3 point = origin.add(flat.scale(distance));
                    double ground = CharizardCombat.groundBelow(level, caster, point);
                    if (!Double.isFinite(ground)) continue;
                    Vec3 landing = new Vec3(point.x, ground, point.z);
                    boolean tooClose = false;
                    for (Vec3 other : placed) {
                        if (other.distanceToSqr(landing) < SPOUT_DROP_SPACING * SPOUT_DROP_SPACING) {
                            tooClose = true;
                            break;
                        }
                    }
                    if (tooClose) continue;
                    // 落点必须与施法者之间 clearLine，否则取消这颗水滴（不穿墙）。
                    if (!clear(level, caster, from, landing)) continue;
                    return landing;
                }
                return null;
            }

            private void tickDrops(ServerLevel level, PokemonEntity caster, boolean damageAllowed) {
                for (Iterator<RainDrop> it = drops.iterator(); it.hasNext(); ) {
                    RainDrop drop = it.next();
                    drop.speed = Math.min(SPOUT_DROP_MAX_SPEED, drop.speed + SPOUT_DROP_ACCEL);
                    double nextY = drop.pos.y - drop.speed;
                    if (nextY <= drop.targetY) {
                        if (damageAllowed) {
                            impact(level, caster, new Vec3(drop.pos.x, drop.targetY, drop.pos.z), drop.band);
                        } else {
                            // 消散阶段：水柱余波只是视觉，绝不再补伤害。
                            water(level, new Vec3(drop.pos.x, drop.targetY, drop.pos.z), 4, 0.25);
                        }
                        it.remove();
                    } else {
                        drop.pos = new Vec3(drop.pos.x, nextY, drop.pos.z);
                    }
                }
            }

            private void impact(ServerLevel level, PokemonEntity caster, Vec3 at, int band) {
                water(level, at, 6, 0.35);
                fx(level, at, new Vec3(0, 1, 0), "cobblemon:watergun_targetfoam");
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(at, at).inflate(SPOUT_DROP_RADIUS),
                        e -> BasicAttack.validTarget(e, caster))) {
                    Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), at);
                    if (contact.distanceToSqr(at) > SPOUT_DROP_RADIUS * SPOUT_DROP_RADIUS) continue;
                    // 同一目标同一段（远/中/近）最多命中一次；三段伤害逐段提高。
                    if (hits.getOrDefault(target.getUUID(), -1) >= band) continue;
                    // 普通 R：三段总伤害 21，不触发终极技震屏或顿帧。
                    if (!CombatDamage.apply(level, caster, target, SPOUT_PHASE_DAMAGE[band],
                            com.pkmoba.network.EffectPayload.Element.WATER,
                            com.pkmoba.network.EffectPayload.Power.HEAVY)) continue;
                    hits.put(target.getUUID(), band);
                    // 固定等级 II：同等级 addEffect 只会刷新时长，不会叠成 III。
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                            SPOUT_SLOW_TICKS, SPOUT_SLOW_AMPLIFIER), caster);
                }
            }
        }

        /** 一颗正在下落的水滴：只做垂直加速，不做墙体 clip（落点已预先校验 clearLine）。 */
        private static final class RainDrop {
            private Vec3 pos;
            private final double targetY;
            private final int band;
            private double speed = SPOUT_DROP_START_SPEED;

            private RainDrop(Vec3 pos, double targetY, int band) {
                this.pos = pos;
                this.targetY = targetY;
                this.band = band;
            }
        }

        // ---- HUD ----

        private void syncHud(ServerPlayer player) {
            if (player == null || player.connection == null) return;
            float distance = CharizardCombat.distanceToBorder(entity);
            Vec3 center = CharizardCombat.arenaCenter();
            float charge = 0.0F;
            if (channel == CHANNEL_CANNON) {
                charge = Math.min(1.0F, skillTicks / (float) CANNON_CHARGE_TICKS);
            } else if (channel == CHANNEL_SPOUT) {
                charge = Math.min(1.0F, skillTicks / (float) SPOUT_WINDUP_TICKS);
            } else if (channel == CHANNEL_SURF) {
                // 客户端按这个进度切六阶段（蓄水 / 冲刺 / 终点爆发）。
                charge = Math.min(1.0F, skillTicks / (float) SURF_TICKS);
            }
            SkillStatePayload payload = new SkillStatePayload(entity.getHealth(), entity.getMaxHealth(),
                    points, cooldowns[0], cooldowns[1], cooldowns[2], cooldowns[3], channel, charge,
                    distance, (float) center.x, (float) center.y, (float) center.z,
                    (float) CharizardCombat.arenaRadius());
            ServerPlayNetworking.send(player, payload);
        }

        // ---- 通用工具 ----

        /** 施法方向：优先鼠标 aim，退化到模型正前方；永远水平归一化。 */
        private static Vec3 direction(PokemonEntity entity, Vec3 aim) {
            if (validAim(entity, aim)) {
                Vec3 dir = CharizardCombat.aimDirection(entity, aim);
                if (dir.lengthSqr() > 1.0E-8) return horizontal(dir);
            }
            return CharizardCombat.modelForward(entity);
        }

        private static boolean validAim(PokemonEntity entity, Vec3 aim) {
            return aim != null && Double.isFinite(aim.x) && Double.isFinite(aim.y) && Double.isFinite(aim.z)
                    && entity.distanceToSqr(aim) <= 512.0 * 512.0;
        }

        private static Vec3 horizontal(Vec3 dir) {
            Vec3 flat = new Vec3(dir.x, 0, dir.z);
            return flat.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : flat.normalize();
        }

        private static Vec3 right(Vec3 dir) {
            Vec3 r = new Vec3(-dir.z, 0, dir.x);
            return r.lengthSqr() < 1.0E-8 ? new Vec3(1, 0, 0) : r.normalize();
        }

        private static Vec3 mouth(PokemonEntity entity, Vec3 dir) {
            return CharizardCombat.mouthPosition(entity, dir, MUZZLE_FORWARD, MUZZLE_DROP);
        }

        private static void holdPosition(PokemonEntity entity) {
            entity.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
        }

        private static int cooldownTicks(int slot) {
            // 0.38.4：冷却统一走 Skill 的分物种表（水箭龟 {80,60,90,140}），
            // 和客户端 HUD 的冷却遮罩用同一数据源，避免"图标转完了技能还没好"。
            return Skill.cooldownTicks(true, slot);
        }

        private Vec3 clip(ServerLevel level, PokemonEntity entity, Vec3 from, Vec3 to) {
            HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, entity));
            return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : to;
        }

        private boolean clear(ServerLevel level, PokemonEntity entity, Vec3 from, Vec3 to) {
            return BasicAttack.clearLine(level, entity, from, to);
        }

        private void visual(ServerLevel level, PokemonEntity entity, int kind, Vec3 p, Vec3 dir,
                            double length, double radius) {
            WaterEffectPayload payload = new WaterEffectPayload(entity.getId(), kind, p.x, p.y, p.z,
                    dir.x, dir.y, dir.z, length, radius);
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(p) < 128 * 128
                        && ServerPlayNetworking.canSend(player, WaterEffectPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }

        private void broadcastSpout(ServerLevel level, Vec3 at, SpoutPayload payload) {
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(at) < 128 * 128
                        && ServerPlayNetworking.canSend(player, SpoutPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }

        private void fx(ServerLevel level, Vec3 p, Vec3 dir, String id) {
            EffectPayload payload = new EffectPayload(id, p.x, p.y, p.z, dir.x, dir.y, dir.z);
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(p) < 128 * 128
                        && ServerPlayNetworking.canSend(player, EffectPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }

        private void water(ServerLevel level, Vec3 p, int count, double spread) {
            level.sendParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, count,
                    spread, spread * 0.5, spread, 0.04);
        }

        private void splashSound(ServerLevel level, Vec3 p) {
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.8F, 1.1F);
        }

        private void animate(PokemonEntity entity, String name) {
            ((PosableEntity) entity).playAnimation(name, List.of());
        }
    }
}

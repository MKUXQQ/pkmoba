package com.pkmoba.client;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;

/**
 * 客户端走路动画兜底（用户选 A1-A：客户端按实际速度自己切 walk/idle）。
 *
 * <p>为什么需要这一层：服务端 {@code MoveOrderService.straightWalk} 用
 * {@code entity.move(MoverType.SELF, new Vec3(dx, 0, dz))} 直接推位置，且只在
 * {@code END_SERVER_TICK}（所有实体 tick 之后）执行。Cobblemon 的走路判定在
 * {@code PokemonEntity.tick()} 里用 {@code walkDist - walkDistO > 0.005F} 算，
 * 而 walkDistO 在基类 tick 开头就被快照成 walkDist —— 我们推位置时这个比较早就跑完了，
 * 差值恒为 0，于是服务端 {@code isPokemonWalking} 永远 false、MOVING/POSE_TYPE 永远不切 WALK。
 *
 * <p>更隐蔽的是第二个坑：{@code entity.move} 末尾是
 * {@code setOnGroundWithMovement(verticalCollisionBelow, movement)}，而
 * {@code verticalCollisionBelow} 要求 {@code movement.y < 0}。我们的 motion.y 恒为 0，
 * 所以每移动一 tick 服务端都把 onGround 清成 false（站着不动时反而保留 true）。
 * 这会让客户端 molang {@code q.in_air} 为真，Cobblemon 的 "walking"/"running" 姿势条件
 * {@code !q.in_air} 不成立，姿势会退化成 "jump"（ride_jump 静止动画）—— 腿依旧不动。
 *
 * <p>所以这里每 tick 检查实体的实际位移：动了就补
 * {@code setOnGround(true)} + MOVING + POSE_TYPE(WALK)，并把动画状态机切到走路姿势。
 * 时机必须是本 tick 的实体 tick **之后**（END_CLIENT_TICK），渲染发生在 tick 之后。
 */
public final class WalkAnimator {
    /** 位移平方阈值：约 0.02 格，足以区分"站着"与"在走"，又能滤掉同步抖动。 */
    private static final double MOVE_EPSILON_SQR = 4.0E-4;
    /**
     * 走路状态保持的 tick 数。位置包不是每 tick 都有，单 tick 没位移就立刻回待机会让腿一抽一停；
     * 5 tick（250ms）既能吃掉同步空档，又能在真正停下时及时回待机。
     */
    private static final int WALK_HOLD_TICKS = 5;

    private static Vec3 lastPos;
    private static java.util.UUID lastEntityId;
    private static boolean wasSkillFlying;
    private static boolean wasBlastoiseShellCurl;
    private static PokemonEntity shellEntity;
    private static PoseType lastSkillPose;
    /** 上一次接管的站立/走路姿势；0.38 起需要自己写 POSE_TYPE，只在姿势变化时重播动画。 */
    private static PoseType lastWalkPose;
    /**
     * 上一次写入的冲刺标记。
     *
     * <p>Cobblemon 的 poser 把 WALK 拆成两个姿势：{@code walking}（条件 {@code !q.is_sprinting}）和
     * {@code running}（条件 {@code q.is_sprinting}）。我们直接推位置时冲刺位永远是 false，所以哪怕
     * 姿势是 WALK 也只会播 ground_walk —— 用户要的“所有宝可梦都跑步”就是把这个位补成 true。
     */
    private static boolean lastSprint;
    /**
     * 场上其它宝可梦（野生、NPC、对战外的队伍成员）的走/跑接管状态。
     *
     * <p>2.2（m00766）用户要求“所有宝可梦的移动都变成跑步”，所以把同一套姿势接管推广到视野内
     * 每一个 {@link PokemonEntity}：位置在动就切 running 姿势，停下 5 tick 后回 standing。
     */
    private static final java.util.Map<java.util.UUID, Vec3> otherPos = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> otherHold = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, String> otherPose = new java.util.HashMap<>();
    /** 首次把“非出战宝可梦”切到 running 时打一行日志，方便实机确认全量接管真的跑了。 */
    private static boolean loggedOtherRunOnce;
    /** 剩余多少 tick 仍算"在走"（见 {@link #WALK_HOLD_TICKS}）。 */
    private static int walkHoldTicks;
    /** 一旦出错就永久停用，避免每 tick 刷异常。 */
    private static boolean disabled = false;
    /** 首次切入 WALK / 回 STAND 各打印一次，便于下一次实测直接从 latest.log 判断接管是否生效。 */
    private static boolean loggedWalkOnce;
    private static boolean loggedIdleOnce;

    private WalkAnimator() {
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger("pkmoba");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(WalkAnimator::tick);
    }

    private static void tick(Minecraft mc) {
        if (disabled) {
            return;
        }
        try {
            pkmoba$tickInner(mc);
            tickOthers(mc);
        } catch (Throwable t) {
            // 走路动画只是表现层兜底，绝不能因为它让游戏崩掉
            disabled = true;
            org.slf4j.LoggerFactory.getLogger("pkmoba")
                    .warn("[pkmoba] 走路动画兜底已停用：{}", t.toString());
        }
    }

    private static void pkmoba$tickInner(Minecraft mc) {
        int id = ClientState.getCharizardId();
        if (id < 0 || mc.level == null) {
            reset();
            clearShellAnimation();
            return;
        }
        Entity entity = mc.level.getEntity(id);
        if (!(entity instanceof PokemonEntity pokemonEntity)) {
            reset();
            clearShellAnimation();
            return;
        }
        boolean shellCurl = ShellAnimator.appliesTo(pokemonEntity) && ClientState.getChannel() == 3;
        if (shellEntity != pokemonEntity) {
            clearShellAnimation();
            shellEntity = pokemonEntity;
        }
        ShellAnimator.tick(pokemonEntity, shellCurl);
        wasBlastoiseShellCurl = shellCurl;

        Vec3 pos = pokemonEntity.position();
        if (!pokemonEntity.getUUID().equals(lastEntityId)) {
            reset();
            lastEntityId = pokemonEntity.getUUID();
        }
        boolean skillFlying = ClientState.getChannel() == 3
                && "cobblemon:charizard".equals(pokemonEntity.getPokemon().getSpecies().getResourceIdentifier().toString());
        Vec3 displacement = lastPos == null ? Vec3.ZERO : pos.subtract(lastPos);
        boolean moved = displacement.horizontalDistanceSqr() > MOVE_EPSILON_SQR;
        // Entity.tick recalculates flying/walking from distances that client interpolation
        // does not advance reliably. Apply the skill pose AFTER that tick, before rendering.
        if (skillFlying || wasSkillFlying) {
            // 插值包间隙不代表停止飞翔：整段技能保持同一个扑翼循环。
            boolean translating = skillFlying;
            PoseType pose = skillFlying ? PoseType.FLY
                    : (moved ? PoseType.WALK : PoseType.STAND);
            if (!skillFlying && moved) {
                // 落回地面的这一帧同样要修 onGround，理由见下方地面分支注释。
                pokemonEntity.setOnGround(true);
            }
            pokemonEntity.setBehaviourFlag(PokemonBehaviourFlag.FLYING, skillFlying);
            pokemonEntity.setPokemonFlying(translating);
            pokemonEntity.setPokemonWalking(!skillFlying && moved);
            pokemonEntity.getEntityData().set(PokemonEntity.getMOVING(), skillFlying ? translating : moved);
            pokemonEntity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), pose);
            if (pokemonEntity.getDelegate() instanceof com.cobblemon.mod.common.client.render.models.blockbench.PosableState state) {
                // An old melee/special primary animation otherwise masks the wing cycle.
                if (skillFlying && !wasSkillFlying) state.setPrimaryAnimation(null);
                if (!wasSkillFlying || pose != lastSkillPose) state.setPoseToFirstSuitable(pose);
            }
            lastSkillPose = pose;
            // 从飞行姿态落回地面时这里已经播过一次 WALK/STAND，同步记录，避免下一 tick 重复重置。
            if (!skillFlying) lastWalkPose = pose;
        } else {
            boolean flying = pokemonEntity.getBehaviourFlag(PokemonBehaviourFlag.FLYING)
                    || pokemonEntity.getCurrentPoseType() == PoseType.FLY
                    || pokemonEntity.getCurrentPoseType() == PoseType.HOVER;
            if (flying) {
                pokemonEntity.setSprinting(false);
                lastSprint = false;
                pokemonEntity.setPokemonWalking(false);
                lastWalkPose = null;
                walkHoldTicks = 0;
            } else if (!shellCurl) {
                // 0.38 起移动改成服务端 entity.move() 直接推位置，走路判定全断，这里自己在客户端补。
                //
                // 真正的坑不是 MOVING/POSE_TYPE 写不进去（飞行分支用的就是同一套写法且实测有效），
                // 而是 onGround：MoveOrderService.straightWalk 用
                // entity.move(MoverType.SELF, new Vec3(dx, 0, dz)) 推位移（竖直分量恒为 0），
                // 原版 Entity.move 末尾是 setOnGroundWithMovement(verticalCollisionBelow, movement)，
                // 而 verticalCollisionBelow = (竖直碰撞 && movement.y < 0) —— dy 恒 0 所以每移动一 tick
                // onGround 都被清成 false（站着不动时没有 move() 调用，onGround 反而是 true）。
                // 由此两个后果：
                //   1) 旧代码 `moved && onGround()` 永远不进 WALK，姿势卡在 STAND = 用户看到的"滑行"；
                //   2) 就算强切 WALK，molang q.in_air 为真会让 "walking"/"running" 姿势的 condition
                //      （!q.in_air ...）不成立，getFirstSuitablePose(WALK) 退到 "jump" 姿势（ride_jump
                //      静止动画），腿还是不动。所以移动时必须把 onGround 拉回 true。
                walkHoldTicks = moved ? WALK_HOLD_TICKS : Math.max(0, walkHoldTicks - 1);
                boolean walking = walkHoldTicks > 0;
                // 强写 onGround 之前的原值，仅用于首次接管的诊断日志。
                boolean onGroundBefore = pokemonEntity.onGround();
                // 停下后的 1 tick 也要保持 onGround，避免切回 STAND 时被 q.in_air 顶到 jump 姿势。
                if (walking || lastWalkPose == PoseType.WALK) {
                    pokemonEntity.setOnGround(true);
                }
                // 只在当前是地面姿势时接管，避免覆盖睡眠/受击/下场等他人姿势。
                // NONE 一并接受：Cobblemon 的 "standing" 姿势同时声明了 STAND/NONE，有时初始值为 NONE。
                PoseType current = pokemonEntity.getCurrentPoseType();
                if (current == PoseType.STAND || current == PoseType.WALK || current == PoseType.NONE) {
                    PoseType pose = walking ? PoseType.WALK : PoseType.STAND;
                    // 2.2（m00766）：移动时置冲刺位，poser 才会选 running 而不是 walking。
                    boolean sprint = walking;
                    pokemonEntity.setSprinting(sprint);
                    pokemonEntity.setPokemonWalking(walking);
                    pokemonEntity.getEntityData().set(PokemonEntity.getMOVING(), walking);
                    pokemonEntity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), pose);
                    // 姿势真的变了才重播，否则每 tick 重置会把走路的循环动画卡在第一帧。
                    PosableState state = pokemonEntity.getDelegate() instanceof PosableState s ? s : null;
                    if (state != null && (pose != lastWalkPose || sprint != lastSprint)) {
                        // 2.2（m00766）：molang 的 q.is_sprinting 在客户端实体上不生效，直接点名切换
                        // running 姿势；没有这个姿势的物种（名字不同或只有 walking）再退回按类型挑。
                        if (pose == PoseType.WALK) {
                            try {
                                state.setPose("running");
                            } catch (Throwable ignored) {
                                // setPose 对不存在的姿势名会抛异常，交给下面的兜底
                            }
                            if (!"running".equals(state.getCurrentPose())) {
                                state.setPoseToFirstSuitable(pose);
                            }
                        } else {
                            state.setPoseToFirstSuitable(pose);
                        }
                    }
                    lastSprint = sprint;
                    if (pose == PoseType.WALK && !loggedWalkOnce) {
                        loggedWalkOnce = true;
                        log().info("[pkmoba] walk-anim 接管 WALK（moved={}, onGround(修前)={}, 原POSE_TYPE={}, 接管后姿势={}）",
                                moved, onGroundBefore, current,
                                state == null ? "?" : state.getCurrentPose());
                    } else if (pose == PoseType.STAND && loggedWalkOnce && !loggedIdleOnce) {
                        loggedIdleOnce = true;
                        log().info("[pkmoba] walk-anim 回退 STAND（moved={}, 接管后姿势={}）",
                                moved, state == null ? "?" : state.getCurrentPose());
                    }
                    lastWalkPose = pose;
                }
            } else {
                // 蜷缩（高速旋转）由 ShellAnimator / 服务端 render-tail 姿态负责，这里完全让位；
                // 清空记录，退出蜷缩后再重新播一次站立/走路。
                lastWalkPose = null;
                walkHoldTicks = 0;
            }
        }
        wasSkillFlying = skillFlying;
        lastPos = pos;
    }

    /**
     * 视野内其它宝可梦的“全部跑步”接管。
     *
     * <p>只在它们是地面姿势（STAND/WALK/NONE）时动手：睡眠、对战、游泳、飞行、下场等姿势
     * 一概不碰，避免抢 Cobblemon 自己的表现。姿势名直接用 {@code running}，没有这个姿势的
     * 物种回落到它自己的 WALK 姿势。
     */
    private static void tickOthers(Minecraft mc) {
        if (mc.level == null) {
            otherPos.clear();
            otherHold.clear();
            otherPose.clear();
            return;
        }
        int controlled = ClientState.getCharizardId();
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof PokemonEntity pokemon) || pokemon.getId() == controlled) continue;
            PoseType current = pokemon.getCurrentPoseType();
            if (current == PoseType.FLY || current == PoseType.HOVER || current == PoseType.SWIM
                    || current == PoseType.GLIDE || current == PoseType.SLEEP) {
                otherPos.remove(pokemon.getUUID());
                otherHold.remove(pokemon.getUUID());
                continue;
            }
            if (current != PoseType.STAND && current != PoseType.WALK && current != PoseType.NONE) continue;
            seen.add(pokemon.getUUID());
            Vec3 now = pokemon.position();
            Vec3 before = otherPos.put(pokemon.getUUID(), now);
            boolean moved = before != null && now.subtract(before).horizontalDistanceSqr() > MOVE_EPSILON_SQR;
            int hold = otherHold.getOrDefault(pokemon.getUUID(), 0);
            hold = moved ? WALK_HOLD_TICKS : Math.max(0, hold - 1);
            otherHold.put(pokemon.getUUID(), hold);
            boolean walking = hold > 0;
            pokemon.setOnGround(true);
            pokemon.setSprinting(walking);
            pokemon.setPokemonWalking(walking);
            pokemon.getEntityData().set(PokemonEntity.getMOVING(), walking);
            pokemon.getEntityData().set(PokemonEntity.getPOSE_TYPE(),
                    walking ? PoseType.WALK : PoseType.STAND);
            String want = walking ? "running" : "standing";
            if (!want.equals(otherPose.get(pokemon.getUUID()))
                    && pokemon.getDelegate() instanceof PosableState state) {
                boolean done = false;
                if (walking) {
                    try {
                        state.setPose("running");
                        done = "running".equals(state.getCurrentPose());
                    } catch (Throwable ignored) {
                        // 该物种没有 running 姿势，下面按类型挑
                    }
                }
                if (!done) state.setPoseToFirstSuitable(walking ? PoseType.WALK : PoseType.STAND);
                if (walking && !loggedOtherRunOnce) {
                    loggedOtherRunOnce = true;
                    log().info("[pkmoba] walk-anim 全量跑步接管（species={}, 姿势={}）",
                            pokemon.getPokemon().getSpecies().getName(),
                            state.getCurrentPose() == null ? "?" : state.getCurrentPose());
                }
                otherPose.put(pokemon.getUUID(), state.getCurrentPose() == null ? want : state.getCurrentPose());
            }
        }
        otherPos.keySet().retainAll(seen);
        otherHold.keySet().retainAll(seen);
        otherPose.keySet().retainAll(seen);
    }

    /** 目标宝可梦丢失/切换时清空所有逐实体状态。 */
    private static void reset() {
        lastPos = null;
        lastSprint = false;
        lastEntityId = null;
        wasSkillFlying = false;
        lastSkillPose = null;
        lastWalkPose = null;
        walkHoldTicks = 0;
    }

    private static void clearShellAnimation() {
        if (shellEntity != null && wasBlastoiseShellCurl) {
            ShellAnimator.clear(shellEntity);
        }
        shellEntity = null;
        wasBlastoiseShellCurl = false;
    }
}

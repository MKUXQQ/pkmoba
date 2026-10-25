package com.pkmoba.combat;

import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.BasicAttackPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 权威的持续普攻指令（左键选定目标后自动追击）。
 *
 * <p><b>1v1 改造</b>：所有普攻状态（追击目标、挥击结算计时、重寻路/停滞计数、水弹列表）
 * 从"全局唯一一份"改成按受控宝可梦 UUID 保存，两只宝可梦各自追击自己的目标，互不清除。
 *
 * <p><b>0.38.5 动作时间轴</b>：一次普攻不再"到距离立刻结算伤害"，而是走
 * {@link Phase#IDLE} → {@link Phase#WINDUP}（准备/前摇，只摆姿势）→ {@link Phase#HIT}
 * （命中帧，结算一次）→ {@link Phase#RECOVERY}（后摇，可被移动/技能取消）→ {@link Phase#IDLE}。
 * 喷火龙的命中帧对齐动画接触关键帧（{@link #WINDUP_TICKS}）；水箭龟在聚水后发射水弹，
 * 水弹自身碰到目标的那一 tick 才结算。前摇期间目标改用 {@code swingTarget} 锁定，
 * 命中帧用 {@code generation} 校验，保证"打中的一定是动画打的那个人"。
 */
public final class BasicAttack {
    public static final double RANGE = 2.0;
    public static final double WATER_RANGE = 4.0;
    private static final double WATER_VISUAL_RADIUS = 0.23;
    /** 普通攻击伤害：0.37 定在 5（0.36 是 4，更早是 10）。 */
    public static final float DAMAGE = 5.0F;
    public static final int INTERVAL_TICKS = 16;

    /**
     * 前摇：挥击开始到<b>命中判定帧</b>的 tick 数。
     *
     * <p>13 tick = 0.65s，对齐 {@code pkmoba_melee.animation.json} 里右臂/手部聚拢的接触关键帧
     * （原动画 1.25s、被 PKMOBA 按时长 ÷2 重排后为 0.625s，见同目录 NOTICE）。所以动画打到人的
     * 那一帧，就是伤害结算的那一帧；前摇期间一个伤害数字都不会跳。
     */
    public static final int WINDUP_TICKS = 13;
    /** 后摇：命中帧之后到恢复的 tick 数。这段时间允许被移动指令 / 技能打断（见 interruptSwing/cancel）。 */
    public static final int RECOVERY_TICKS = 6;
    /**
     * 水箭龟的水弹前摇：炮口聚水 tick 数，结束后才发射水弹。
     *
     * <p>水弹的命中帧不是固定 tick，而是<b>水弹真正碰到目标的那一 tick</b>（{@link #tickWaterShots}），
     * 伤害只在那里结算，天然与弹道位置一致。缺一套"聚水"动画：目前前摇用姿态计时，
     * 发射瞬间播现有 {@code special} 动画组。
     */
    public static final int WATER_WINDUP_TICKS = 8;
    private static final int REPATH_TICKS = 10;
    private static final int STALL_TIMEOUT = 120;
    private static final int PURSUIT_TIMEOUT = 600;

    /** 动作阶段：前摇绝不结算伤害，命中帧只结算一次，后摇可被打断。 */
    private enum Phase {
        IDLE,
        /** 准备/前摇：摆姿势 + 锁定目标。 */
        WINDUP,
        /** 命中判定帧：本 tick 结算一次伤害（水箭龟则是发射水弹）。 */
        HIT,
        /** 后摇：收招窗口，可被移动/技能取消。 */
        RECOVERY
    }

    /** 每只受控宝可梦一份普攻状态。key = 实体 UUID。 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    private BasicAttack() { }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(BasicAttackPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handle(context.player(), payload.targetId())));
    }

    private static final class State {
        UUID ownerId;
        LivingEntity attackTarget;
        /** 挥击/水弹<b>锁定的那个目标</b>：命中帧只结算它，绝不会因为期间换了目标而打错人。 */
        LivingEntity swingTarget;
        /** 每次攻击自增一次。命中帧校验它，迟到的旧回调打不到新攻击身上。 */
        int generation;
        Phase phase = Phase.IDLE;
        /** 命中帧（{@link Phase#HIT} 在这一 tick 结算伤害）。 */
        long hitTick = Long.MIN_VALUE;
        /** 后摇结束 tick。 */
        long recoveryEndTick = Long.MIN_VALUE;
        UUID lastAttacker;
        long lastAttackTick = Long.MIN_VALUE;
        int repathTicks;
        int stalledTicks;
        int pursuitTicks;
        Vec3 progressPosition;
        double bestDistance;
        boolean paused;
        boolean rightCannon;
        /** 左键原地挥击模式：不追目标、不锁敌，只朝模型正前方打一下。 */
        boolean forwardSwing;
        final List<WaterShot> shots = new ArrayList<>();

        boolean hasTarget() { return attackTarget != null; }

        void clearAttack() {
            attackTarget = null;
            swingTarget = null;
            generation++;
            phase = Phase.IDLE;
            hitTick = Long.MIN_VALUE;
            recoveryEndTick = Long.MIN_VALUE;
            ownerId = null;
            progressPosition = null;
            paused = false;
            forwardSwing = false;
            repathTicks = stalledTicks = pursuitTicks = 0;
        }
    }

    private static State state(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), id -> new State());
    }

    private static State existing(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return entity == null ? null : STATES.get(entity.getUUID());
    }

    public static void handle(ServerPlayer player, int targetId) {
        if (targetId <= 0) {
            forwardSwing(player);
            return;
        }
        // 方案 §三.4：刚登场的 2 秒硬直里不许普攻
        if (com.pkmoba.pokemon.BallThrow.locked(com.pkmoba.charizard.CharizardManager.getControlled(player))) {
            return;
        }
        if (!player.isAlive() || player.isSpectator() || !ServerGodMode.isActive(player)) return;
        var self = CharizardManager.getControlled(player);
        if (self == null || self.level() != player.level()) return;
        // 只允许操作自己那只：owner 必须真的是这个玩家。
        if (!player.getUUID().equals(CharizardManager.getControlledOwner(self))) return;
        Entity found = player.serverLevel().getEntity(targetId);
        if (!(found instanceof LivingEntity target) || !validTarget(target, self)) return;
        if (!insideArena(self.position()) || !insideArena(target.position())) {
            notice(player, "目标超出场地，无法持续攻击");
            return;
        }
        State st = state(self);
        // Clicking the same target must not reset timeout or bypass attack cadence.
        if (target == st.attackTarget && player.getUUID().equals(st.ownerId)) return;
        com.pkmoba.move.MoveOrderService.clear(self);
        if (!CharizardCombat.isBasicAttackBlocked(self)) self.getNavigation().stop();
        st.ownerId = player.getUUID();
        st.attackTarget = target;
        st.repathTicks = st.stalledTicks = st.pursuitTicks = 0;
        st.progressPosition = self.position();
        st.bestDistance = boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox());
        st.paused = false;
        // 新指令：作废上一发还没落地的挥击，从干净的状态开始
        st.swingTarget = null;
        st.generation++;
        st.phase = Phase.IDLE;
        st.hitTick = Long.MIN_VALUE;
        st.recoveryEndTick = Long.MIN_VALUE;
    }

    /**
     * 左键：原地朝模型正前方挥一下（用户 m03997「普通攻击不再自动寻敌，点击左键就在模型前面攻击」）。
     *
     * <p>不追、不锁目标、不改变移动指令；命中帧只结算正前方扇形里最近的一个合法目标。
     */
    private static void forwardSwing(ServerPlayer player) {
        if (com.pkmoba.pokemon.BallThrow.locked(com.pkmoba.charizard.CharizardManager.getControlled(player))) {
            return;
        }
        if (!player.isAlive() || player.isSpectator() || !ServerGodMode.isActive(player)) return;
        var self = CharizardManager.getControlled(player);
        if (self == null || self.level() != player.level()) return;
        if (!player.getUUID().equals(CharizardManager.getControlledOwner(self))) return;
        if (CharizardCombat.isBasicAttackBlocked(self)) return;
        if (!insideArena(self.position())) return;
        State st = state(self);
        if (st.phase != Phase.IDLE) return;
        long now = self.level().getGameTime();
        if (st.lastAttacker != null && now - st.lastAttackTick < INTERVAL_TICKS) return;
        // 原地挥击同时终止旧的锁敌追击：左键是"自己打一下"，不接管移动。
        st.attackTarget = null;
        st.ownerId = player.getUUID();
        st.forwardSwing = true;
        st.lastAttacker = self.getUUID();
        st.lastAttackTick = now;
        st.generation++;
        st.swingTarget = null;
        st.phase = Phase.WINDUP;
        st.hitTick = now + (CharizardManager.isBlastoise(self) ? WATER_WINDUP_TICKS : WINDUP_TICKS);
        if (!CharizardManager.isBlastoise(self)) beginSwing(self);
        self.getNavigation().stop();
    }

    /** 正前方扇形命中：找最近的一个合法目标结算，找不到就空挥（不打远处的人）。 */
    private static void resolveForwardHit(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        var level = (net.minecraft.server.level.ServerLevel) self.level();
        Vec3 forward = CharizardCombat.modelForward(self);
        double range = rangeFor(self) + 0.35;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                self.getBoundingBox().inflate(range + 1.0), e -> validTarget(e, self))) {
            Vec3 to = candidate.getBoundingBox().getCenter().subtract(self.position());
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (flat > range) continue;
            if (flat < 1.0E-4) continue;
            Vec3 dir = new Vec3(to.x, 0.0, to.z).normalize();
            // 正前方 120° 扇形：只看模型面前，不再自动转向身后的人
            if (dir.dot(forward) < 0.5) continue;
            if (!clearMeleeLine(self, candidate)) continue;
            if (flat < bestDist) {
                bestDist = flat;
                best = candidate;
            }
        }
        if (best == null) return;
        st.swingTarget = best;
        if (CharizardManager.isBlastoise(self)) {
            releaseWater(self, st);
        } else {
            ServerPlayer owner = st.ownerId == null ? null : level.getServer().getPlayerList().getPlayer(st.ownerId);
            strike(owner, self, best);
        }
    }

    /** 左键原地挥击的 tick 推进（不追路、不改移动）。 */
    private static void tickForwardSwing(net.minecraft.server.MinecraftServer server,
                                         com.cobblemon.mod.common.entity.pokemon.PokemonEntity self,
                                         State st) {
        if (CharizardCombat.isBasicAttackBlocked(self)) {
            st.forwardSwing = false;
            st.phase = Phase.IDLE;
            st.swingTarget = null;
            st.hitTick = Long.MIN_VALUE;
            return;
        }
        long now = self.level().getGameTime();
        if (st.phase == Phase.WINDUP) {
            self.getNavigation().stop();
            if (now < st.hitTick) return;
            st.phase = Phase.HIT;
        }
        if (st.phase == Phase.HIT) {
            resolveForwardHit(self, st);
            st.phase = Phase.RECOVERY;
            st.recoveryEndTick = now + RECOVERY_TICKS;
            return;
        }
        if (now >= st.recoveryEndTick) {
            st.phase = Phase.IDLE;
            st.forwardSwing = false;
            st.swingTarget = null;
            st.hitTick = Long.MIN_VALUE;
            st.recoveryEndTick = Long.MIN_VALUE;
        }
    }

    /** 技能/位移可以打断普攻：前摇未命中直接作废，后摇直接结束，但保留追击目标。 */
    public static void interruptSwing(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        State st = existing(entity);
        if (st == null) return;
        st.swingTarget = null;
        st.generation++;
        st.phase = Phase.IDLE;
        st.hitTick = Long.MIN_VALUE;
        st.recoveryEndTick = Long.MIN_VALUE;
    }

    public static boolean hasTarget(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        State st = existing(entity);
        return st != null && st.hasTarget();
    }

    /** Clear only this Pokemon's attack state. Never interrupts a skill's navigation or velocity. */
    public static void cancel(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        State st = existing(entity);
        if (st == null || !st.hasTarget()) return;
        if (!CharizardCombat.isBasicAttackBlocked(entity)) entity.getNavigation().stop();
        st.clearAttack();
    }

    /** 全局清理（服务器停止 / 移除所有受控实体时使用）。 */
    public static void cancelAll(net.minecraft.server.MinecraftServer server) {
        for (var entity : CharizardManager.getControlledEntities(server)) cancel(entity);
        STATES.clear();
    }

    /** Called only by the movement arbiter, never a second navigation event listener. */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        var entities = CharizardManager.getControlledEntities(server);
        // 清掉已经没有实体的状态，避免 UUID 表无限增长
        STATES.keySet().removeIf(id -> entities.stream().noneMatch(e -> e.getUUID().equals(id)));
        for (var entity : entities) {
            State st = state(entity);
            tickWaterShots(entity, st);
            if (st.forwardSwing) {
                tickForwardSwing(server, entity, st);
            } else {
                tickPursuit(server, entity, st);
            }
        }
    }

    private static void tickPursuit(net.minecraft.server.MinecraftServer server,
                                    com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        if (!st.hasTarget()) return;
        LivingEntity target = st.attackTarget;
        ServerPlayer owner = st.ownerId == null ? null : server.getPlayerList().getPlayer(st.ownerId);
        if (owner == null || !owner.isAlive() || owner.isSpectator()
                || !ServerGodMode.isActive(owner)
                || !st.ownerId.equals(CharizardManager.getControlledOwner(self))
                || owner.level() != self.level()) {
            cancel(self);
            return;
        }
        // Keep the actual entity identity, never recycle a numeric network id.
        boolean stillThere = owner.serverLevel().getEntity(target.getUUID()) == target;
        if (target.isRemoved() || !validTarget(target, self) || target.level() != self.level() || !stillThere) {
            cancel(self);
            return;
        }
        if (!insideArena(self.position()) || !insideArena(target.position())) {
            cancel(self);
            return;
        }
        if (CharizardCombat.isBasicAttackBlocked(self)) {
            // Stop our pre-existing path once; subsequent ticks leave the skill alone.
            if (!st.paused && CharizardCombat.blocksMovement(self)) self.getNavigation().stop();
            st.paused = true;
            // 技能打断了还没命中的挥击：直接作废，别等技能结束再补一刀。
            st.swingTarget = null;
            st.generation++;
            st.phase = Phase.IDLE;
            st.hitTick = Long.MIN_VALUE;
            st.recoveryEndTick = Long.MIN_VALUE;
            return;
        }
        if (st.paused) {
            st.paused = false;
            st.repathTicks = 0;
            st.progressPosition = self.position();
            st.bestDistance = boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox());
        }
        long tick = self.level().getGameTime();
        // 动作进行中：前摇/命中帧/后摇全部交给 tickAttack 独占，追迹主循环不再结算任何伤害。
        if (st.phase != Phase.IDLE) {
            tickAttack(server, owner, self, st, tick);
            return;
        }
        double range = rangeFor(self);
        double distance = boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox());
        boolean inRange = distance <= range * range && clearMeleeLine(self, target);
        if (inRange) {
            self.getNavigation().stop();
            st.stalledTicks = st.pursuitTicks = st.repathTicks = 0;
            st.progressPosition = self.position();
            st.bestDistance = distance;
            self.getLookControl().setLookAt(target, 30.0F, 30.0F);
            long now = self.level().getGameTime();
            if (self.getUUID().equals(st.lastAttacker) && now >= st.lastAttackTick
                    && now - st.lastAttackTick < INTERVAL_TICKS) return;
            // Charge the attempt even if vanilla invulnerability frames reject damage.
            st.lastAttacker = self.getUUID();
            st.lastAttackTick = now;
            // 只进入前摇摆姿势；伤害/水弹要等命中帧才结算。
            beginAttack(self, st, target);
            return;
        }
        st.pursuitTicks++;
        st.stalledTicks++;
        if (distance + 0.25 < st.bestDistance || st.progressPosition == null
                || self.position().distanceToSqr(st.progressPosition) >= 0.25) {
            st.bestDistance = Math.min(st.bestDistance, distance);
            st.progressPosition = self.position();
            st.stalledTicks = 0;
        }
        // Movement alone is not proof of reachability: circling also has a hard bound.
        if (st.stalledTicks >= STALL_TIMEOUT || st.pursuitTicks >= PURSUIT_TIMEOUT) {
            cancel(self);
            return;
        }
        if (st.repathTicks > 0) st.repathTicks--;
        if (st.repathTicks == 0) {
            self.getNavigation().moveTo(target, 1.2);
            st.repathTicks = REPATH_TICKS;
        }
    }

    /**
     * 进入一次攻击：锁定目标、记录攻击序号、设定命中帧。
     *
     * <p>这个方法<b>不结算任何伤害</b>，只负责摆姿势。喷火龙在前摇末端的命中帧
     * （{@link #WINDUP_TICKS}）才判定；水箭龟在聚水（{@link #WATER_WINDUP_TICKS}）后发射水弹，
     * 水弹自己碰人时结算。
     */
    private static void beginAttack(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st, LivingEntity target) {
        long now = self.level().getGameTime();
        st.generation++;
        st.swingTarget = target;
        st.phase = Phase.WINDUP;
        if (CharizardManager.isBlastoise(self)) {
            st.hitTick = now + WATER_WINDUP_TICKS;
        } else {
            st.hitTick = now + WINDUP_TICKS;
            beginSwing(self);
        }
    }

    /**
     * 推进一步当前攻击。三个阶段：
     * <ul>
     *   <li>{@link Phase#WINDUP}：站定、面向目标；tick 到 {@code hitTick} 前一个伤害都不跳；</li>
     *   <li>{@link Phase#HIT}：本 tick 结算一次（喷火龙挥击伤害 / 水箭龟发射水弹）；</li>
     *   <li>{@link Phase#RECOVERY}：后摇，走完回 IDLE；期间被移动/技能取消（{@link #interruptSwing}）。</li>
     * </ul>
     */
    private static void tickAttack(net.minecraft.server.MinecraftServer server, ServerPlayer owner,
                                   com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st, long now) {
        LivingEntity target = st.swingTarget != null ? st.swingTarget : st.attackTarget;
        if (!targetStillValid(self, target)) {
            cancel(self);
            return;
        }
        if (st.phase == Phase.WINDUP) {
            self.getNavigation().stop();
            self.getLookControl().setLookAt(target, 30.0F, 30.0F);
            faceToward(self, target);
            if (now < st.hitTick) return;
            st.phase = Phase.HIT;
        }
        if (st.phase == Phase.HIT) {
            if (CharizardManager.isBlastoise(self)) {
                releaseWater(self, st);
            } else {
                resolveHit(owner, self, st);
            }
            st.phase = Phase.RECOVERY;
            st.recoveryEndTick = now + RECOVERY_TICKS;
            return;
        }
        // RECOVERY：收招窗口，走完才允许下一次攻击
        if (now >= st.recoveryEndTick) {
            st.swingTarget = null;
            st.phase = Phase.IDLE;
            st.hitTick = Long.MIN_VALUE;
            st.recoveryEndTick = Long.MIN_VALUE;
        }
    }

    /** 喷火龙的命中帧：只结算前摇锁定的那个目标，且要求仍在判定范围内、视线通畅。 */
    private static void resolveHit(ServerPlayer owner, com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        int generation = st.generation;
        LivingEntity intended = st.swingTarget;
        if (intended == null) return;
        // 迟到的旧攻击回调不能打在新攻击（或新目标）身上
        if (generation != st.generation) return;
        if (intended != st.attackTarget) return;
        if (!targetStillValid(self, intended)) return;
        double range = rangeFor(self);
        if (boxDistanceSquared(self.getBoundingBox(), intended.getBoundingBox()) > range * range) return;
        // 命中帧再校验一次视线：目标绕柱子跑开就不该吃这一下
        if (!clearMeleeLine(self, intended)) return;
        strike(owner, self, intended);
    }

    /** 水箭龟的出手帧：从炮口/嘴部锚点发射一发水弹（命中由水弹自身接触判定）。 */
    private static void releaseWater(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        LivingEntity target = st.swingTarget;
        if (target == null || target != st.attackTarget || !targetStillValid(self, target)) return;
        // 炮口/嘴部锚点：与技能共用 MouthAnchor（骨骼 locator 优先，取不到再退回固定偏移）
        Vec3 origin = MouthAnchor.position(self, 0.35);
        Vec3 center = target.getBoundingBox().getCenter();
        Vec3 aim = center.subtract(origin).normalize();
        Vec3 side = new Vec3(-aim.z, 0, aim.x).normalize().scale(st.rightCannon ? 0.55 : -0.55);
        st.rightCannon = !st.rightCannon;
        origin = origin.add(side);
        Vec3 dir = center.subtract(origin).normalize();
        st.shots.add(new WaterShot(self, origin, dir));
        bubbleVisual(self, origin, dir, 1.0);
        // 出手瞬间正对目标：ATTACK 优先级，避免与移动/引导技能同 tick 抢写 YRot
        faceToward(self, target);
        ((com.cobblemon.mod.common.entity.PosableEntity) self).playAnimation("special", java.util.List.of());
    }

    /** 让宝可梦水平朝向目标（只写一次请求，实际角度由 FacingService 限速推进）。 */
    private static void faceToward(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, LivingEntity target) {
        Vec3 horizontal = new Vec3(target.getX() - self.getX(), 0.0, target.getZ() - self.getZ());
        if (horizontal.lengthSqr() > 1.0E-6) {
            FacingService.request(self, FacingService.Priority.ATTACK, horizontal.normalize(), 180.0F);
        }
    }

    /** 目标是否还值得出手（服务端还活着、同维度、合法目标、没跑出场外）。 */
    private static boolean targetStillValid(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, LivingEntity target) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;
        if (target.level() != self.level()) return false;
        if (!validTarget(target, self)) return false;
        return insideArena(target.position());
    }

    /**
     * 普攻判定半径（格）：喷火龙 2、水箭龟 4。
     *
     * <p>客户端攻击范围白圈（{@link com.pkmoba.client.AttackRangeRenderer}）通过
     * {@link com.pkmoba.client.Targeting#basicRangeRadius} 调用这里，所以「圈的大小」与
     * 「服务端能否打到」用的是同一个数，不存在圈大判定小的错位。
     */
    public static double rangeFor(com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) {
        return CharizardManager.isBlastoise(pokemon) ? WATER_RANGE : RANGE;
    }

    /** 同上，供只有 {@link LivingEntity} 引用的客户端渲染/瞄准代码调用。 */
    public static double rangeFor(LivingEntity entity) {
        if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) return rangeFor(pokemon);
        return RANGE;
    }

    private static void tickWaterShots(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        if (st.shots.isEmpty()) return;
        if (!self.isAlive() || self.isRemoved()) { st.shots.clear(); return; }
        var level = (net.minecraft.server.level.ServerLevel) self.level();
        for (var it = st.shots.iterator(); it.hasNext();) {
            WaterShot shot = it.next();
            Vec3 end = shot.pos.add(shot.dir.scale(0.7));
            // 0.39：普攻水弹不许飞出白圈。白圈半径就是 rangeFor(self)（水箭龟 4、喷火龙 2），
            // 超出这个距离的推进直接截断在圈上并立刻结束——特效与伤害都不会出现在圈外。
            double maxRange = rangeFor(shot.self);
            boolean outOfRange = end.distanceToSqr(shot.origin) >= maxRange * maxRange;
            if (outOfRange) {
                Vec3 offset = end.subtract(shot.origin);
                end = offset.lengthSqr() < 1.0E-6 ? end : shot.origin.add(offset.normalize().scale(maxRange));
            }
            var wall = level.clip(new ClipContext(shot.pos, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shot.self));
            boolean blocked = wall.getType() == HitResult.Type.BLOCK;
            if (blocked) end = wall.getLocation();
            LivingEntity nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, new AABB(shot.pos, end).inflate(0.25), e -> validTarget(e, shot.self))) {
                var box = candidate.getBoundingBox().inflate(0.25);
                var hit = box.clip(shot.pos, end);
                if (!box.contains(shot.pos) && hit.isEmpty()) continue;
                double distance = box.contains(shot.pos) ? 0 : shot.pos.distanceToSqr(hit.get());
                if (distance < nearestDistance) { nearestDistance = distance; nearest = candidate; }
            }
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, end.x, end.y, end.z, 5, 0.08, 0.08, 0.08, 0.01);
            bubbleVisual(shot.self, end, shot.dir, 1.0);
            if (nearest != null) {
                CombatDamage.apply(level, shot.self, nearest, DAMAGE);
                bubbleVisual(shot.self, end, shot.dir, 0);
                it.remove();
            } else if (blocked || outOfRange || ++shot.age >= 10) {
                bubbleVisual(shot.self, end, shot.dir, 0);
                it.remove();
            } else shot.pos = end;
        }
    }

    private static void bubbleVisual(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, Vec3 pos, Vec3 dir, double active) {
        if (!(self.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var payload = new com.pkmoba.network.WaterEffectPayload(self.getId(), 6, pos.x, pos.y, pos.z,
                dir.x, dir.y, dir.z, active, WATER_VISUAL_RADIUS);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) < 128 * 128
                    && ServerPlayNetworking.canSend(player, com.pkmoba.network.WaterEffectPayload.TYPE))
                ServerPlayNetworking.send(player, payload);
        }
    }

    private static final class WaterShot {
        final com.cobblemon.mod.common.entity.pokemon.PokemonEntity self;
        final Vec3 dir;
        /** 发射点（0.39）：用来把水弹严格限制在普攻白圈半径内。 */
        final Vec3 origin;
        Vec3 pos;
        int age;
        WaterShot(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, Vec3 pos, Vec3 dir) {
            this.self = self; this.pos = pos; this.dir = dir; this.origin = pos;
        }
    }

    private static boolean insideArena(Vec3 pos) {
        return Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
                && CharizardCombat.clampToArena(pos).distanceToSqr(pos) <= 1.0E-6;
    }

    private static UUID lastNoticeOwner;
    private static long lastNoticeTick = Long.MIN_VALUE;

    private static void notice(ServerPlayer player, String text) {
        long now = player.serverLevel().getGameTime();
        if (player.getUUID().equals(lastNoticeOwner) && now >= lastNoticeTick
                && now - lastNoticeTick < 20) return;
        lastNoticeOwner = player.getUUID();
        lastNoticeTick = now;
        // Keep diagnostics out of actionbar and chat; combat must not obscure the HUD.
        com.pkmoba.PkmobaMod.LOGGER.debug("[pkmoba] {}: {}", player.getUUID(), text);
    }

    private static void beginSwing(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self) {
        try {
            ((com.cobblemon.mod.common.entity.PosableEntity) self)
                    .playAnimation("q.bedrock_primary('pkmoba_melee', 'physical', q.curve('symmetrical_wide'))", java.util.List.of());
        } catch (RuntimeException error) {
            com.pkmoba.PkmobaMod.LOGGER.debug("[pkmoba] 普攻动画不可用", error);
        }
    }

    private static void strike(ServerPlayer player,
                               com.cobblemon.mod.common.entity.pokemon.PokemonEntity self,
                               LivingEntity target) {
        if (CombatDamage.apply((net.minecraft.server.level.ServerLevel) self.level(), self, target, DAMAGE)) {
            var level = (net.minecraft.server.level.ServerLevel) self.level();
            Vec3 impact = target.getBoundingBox().getCenter();
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                    impact.x, impact.y, impact.z, 6, 0.15, 0.15, 0.15, 0.02);
            level.playSound(null, impact.x, impact.y, impact.z,
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.0F);
        } else if (player != null) {
            notice(player, "目标未受到伤害");
        }
    }

    public static boolean validTarget(LivingEntity target, Entity self) {
        return target != self && target.isAlive() && !target.isRemoved();
    }

    public static double boxDistanceSquared(AABB a, AABB b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    public static Vec3 closestPoint(AABB box, Vec3 point) {
        return new Vec3(Math.max(box.minX, Math.min(box.maxX, point.x)),
                Math.max(box.minY, Math.min(box.maxY, point.y)),
                Math.max(box.minZ, Math.min(box.maxZ, point.z)));
    }

    public static boolean clearLine(Level level, Entity source, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
    }

    public static boolean clearMeleeLine(LivingEntity self, LivingEntity target) {
        Vec3 end = closestPoint(target.getBoundingBox(), self.getEyePosition());
        return clearLine(self.level(), self, self.getEyePosition(), end);
    }
}

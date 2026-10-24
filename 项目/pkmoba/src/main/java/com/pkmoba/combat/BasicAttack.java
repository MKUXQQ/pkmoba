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
 */
public final class BasicAttack {
    public static final double RANGE = 2.0;
    public static final double WATER_RANGE = 4.0;
    private static final double WATER_VISUAL_RADIUS = 0.23;
    /** 普通攻击伤害：0.37 定在 5（0.36 是 4，更早是 10）。 */
    public static final float DAMAGE = 5.0F;
    public static final int INTERVAL_TICKS = 16;

    public static final int WINDUP_TICKS = 13;
    private static final int REPATH_TICKS = 10;
    private static final int STALL_TIMEOUT = 120;
    private static final int PURSUIT_TIMEOUT = 600;

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
        LivingEntity pendingTarget;
        long hitAt = Long.MIN_VALUE;
        UUID lastAttacker;
        long lastAttackTick = Long.MIN_VALUE;
        int repathTicks;
        int stalledTicks;
        int pursuitTicks;
        Vec3 progressPosition;
        double bestDistance;
        boolean paused;
        boolean rightCannon;
        final List<WaterShot> shots = new ArrayList<>();

        boolean hasTarget() { return attackTarget != null; }

        void clearAttack() {
            attackTarget = null;
            pendingTarget = null;
            hitAt = Long.MIN_VALUE;
            ownerId = null;
            progressPosition = null;
            paused = false;
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
        st.pendingTarget = null;
        st.hitAt = Long.MIN_VALUE;
    }

    /** Any accepted skill invalidates an unlanded melee swing without dropping pursuit. */
    public static void interruptSwing(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        State st = existing(entity);
        if (st == null) return;
        st.pendingTarget = null;
        st.hitAt = Long.MIN_VALUE;
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
            tickPursuit(server, entity, st);
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
            st.pendingTarget = null;
            st.hitAt = Long.MIN_VALUE;
            return;
        }
        if (st.paused) {
            st.paused = false;
            st.repathTicks = 0;
            st.progressPosition = self.position();
            st.bestDistance = boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox());
        }
        long tick = self.level().getGameTime();
        if (st.pendingTarget != null) {
            if (tick < st.hitAt) {
                self.getNavigation().stop();
                self.getLookControl().setLookAt(target, 30.0F, 30.0F);
                return;
            }
            LivingEntity intended = st.pendingTarget;
            st.pendingTarget = null;
            st.hitAt = Long.MIN_VALUE;
            if (intended == target && boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox()) <= RANGE * RANGE
                    && clearMeleeLine(self, target)) strike(owner, self, target);
            return;
        }
        double range = CharizardManager.isBlastoise(self) ? WATER_RANGE : RANGE;
        double distance = boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox());
        boolean melee = distance <= range * range && clearMeleeLine(self, target);
        if (melee) {
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
            if (CharizardManager.isBlastoise(self)) {
                launchWater(self, st, target);
            } else {
                beginSwing(self);
                st.pendingTarget = target;
                st.hitAt = now + WINDUP_TICKS;
            }
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

    private static void launchWater(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st, LivingEntity target) {
        Vec3 aim = target.getBoundingBox().getCenter().subtract(self.getEyePosition()).normalize();
        Vec3 side = new Vec3(-aim.z, 0, aim.x).normalize().scale(st.rightCannon ? 0.55 : -0.55);
        st.rightCannon = !st.rightCannon;
        Vec3 origin = self.position().add(0, self.getBbHeight() * 0.8, 0).add(side).add(aim.scale(0.425));
        Vec3 dir = target.getBoundingBox().getCenter().subtract(origin).normalize();
        st.shots.add(new WaterShot(self, origin, dir));
        bubbleVisual(self, origin, dir, 1.0);
        self.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        self.setYHeadRot(self.getYRot());
        ((com.cobblemon.mod.common.entity.PosableEntity) self).playAnimation("special", java.util.List.of());
    }

    private static void tickWaterShots(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, State st) {
        if (st.shots.isEmpty()) return;
        if (!self.isAlive() || self.isRemoved()) { st.shots.clear(); return; }
        var level = (net.minecraft.server.level.ServerLevel) self.level();
        for (var it = st.shots.iterator(); it.hasNext();) {
            WaterShot shot = it.next();
            Vec3 end = shot.pos.add(shot.dir.scale(0.7));
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
            } else if (blocked || ++shot.age >= 10) {
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
        Vec3 pos;
        int age;
        WaterShot(com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, Vec3 pos, Vec3 dir) {
            this.self = self; this.pos = pos; this.dir = dir;
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

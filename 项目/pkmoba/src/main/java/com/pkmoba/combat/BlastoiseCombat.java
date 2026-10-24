package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.WaterEffectPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import com.pkmoba.network.SkillStatePayload;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/** Server authoritative skills; parent owns receivers, tick registration and HUD synchronization. */
public final class BlastoiseCombat {
    /** 冰山坠落水平射程；所有可变战斗状态均按受控实体 UUID 隔离。 */
    private static final double W_RANGE = 12.0;

    // ---- 0.37 数值：水箭龟偏重装，单次伤害略高于喷火龙，但机动差 ----
    /** Q 水刃伤害。 */
    private static final float Q_DAMAGE = 14.0F;
    /** W 冰山坠落伤害。 */
    private static final float W_DAMAGE = 16.0F;
    /** E 旋转每 10 tick 一跳的伤害（10 跳合计 20）。 */
    private static final float E_TICK_DAMAGE = 2.0F;
    /** R 水炮每 8 tick 一跳的伤害（5 跳合计 35）。 */
    private static final float R_TICK_DAMAGE = 7.0F;

    private static final Map<UUID, State> STATES = new HashMap<>();
    private BlastoiseCombat() { }

    public static void init() { resetAll(); }

    private static State state(PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity));
    }

    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) return;
        if (player == null || !player.isAlive() || player.isSpectator() || !ServerGodMode.isActive(player)
                || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                || !player.level().dimension().equals(entity.level().dimension())) return;
        state(entity).castCore(entity, (ServerLevel) entity.level(), skill, aim);
    }

    public static void updateAim(PokemonEntity entity, Vec3 aim) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        if (state != null) state.updateAim(entity, aim);
    }

    public static void tick(ServerLevel level, PokemonEntity entity) {
        // 即使调用方不再 tick 已移除实体，也回收其飞行物与状态。
        for (Iterator<State> it = STATES.values().iterator(); it.hasNext();) {
            State state = it.next();
            if (!state.entity.isAlive() || state.entity.isRemoved()) { state.reset(); it.remove(); }
        }
        if (entity == null || !entity.isAlive() || entity.isRemoved() || entity.level() != level) return;
        state(entity).tick(level, entity);
    }

    public static void syncHud(ServerPlayer player, PokemonEntity entity) {
        if (entity != null && ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) state(entity).syncHud(player);
    }

    public static void reset(PokemonEntity entity) {
        State state = entity == null ? null : STATES.remove(entity.getUUID());
        if (state != null) state.reset();
    }

    public static void resetAll() {
        for (State state : STATES.values()) state.reset();
        STATES.clear();
    }

    public static boolean blocksMovement(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state != null && state.blocksMovement();
    }

    public static boolean blocksBasicAttack(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state != null && state.blocksBasicAttack();
    }

    /** 单只水箭龟的冷却、资源、引导及自主弹道，绝不绑定到全局 active。 */
    private static final class State {
        private final PokemonEntity entity;
        private final int[] COOLDOWNS = new int[4];
        private final List<Drop> DROPS = new ArrayList<>();
        private final List<Blade> BLADES = new ArrayList<>();
        private float points = 10;
        private int age, channel, skillTicks;
        private Vec3 direction = new Vec3(0, 0, 1), aimDirection = direction;
        private State(PokemonEntity entity) { this.entity = entity; }
    /** Cast arbitration only. Do not use this as a movement gate. */
    private boolean isBusy() { return channel != 0; }
    private boolean blocksMovement() { return channel == 3 || channel == 4; }
    private boolean blocksBasicAttack() { return channel != 0; }

    private void reset() {
        if (entity.level() instanceof ServerLevel level) {
            visual(level, entity, 0, entity.position(), direction, 0, 0);
            for (Blade blade : BLADES) visual(level, entity, 1, blade.pos, blade.dir, 0, 1);
            if (channel == 3) {
                entity.getNavigation().stop();
                entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
            }
        }
        DROPS.clear();
        BLADES.clear();
        java.util.Arrays.fill(COOLDOWNS, 0);
        points = 10;
        age = channel = skillTicks = 0;
        direction = aimDirection = new Vec3(0, 0, 1);
    }

    private void tick(ServerLevel level, PokemonEntity entity) {
        age++;
        if (age % 20 == 0) points = Math.min(10, points + 1);
        for (int i = 0; i < 4; i++) if (COOLDOWNS[i] > 0) COOLDOWNS[i]--;
        if (channel == 3) {
            tickSpin(level, entity);
            if (++skillTicks >= 100) {
                entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
                endChannel(level, entity);
            }
        } else if (channel == 4) {
            turn(entity, 8);
            entity.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
            if (skillTicks < 16) {
                if (skillTicks % 4 == 0) visual(level, entity, 5, entity.getEyePosition(), direction, 0, 0.5);
            } else beam(level, entity, 24, 2, (skillTicks - 16) % 8 == 0 ? R_TICK_DAMAGE : 0, true);
            if (++skillTicks >= 56) endChannel(level, entity);
        }
        tickDrops(level, entity);
        tickBlades(level, entity);
    }

    private void updateAim(PokemonEntity entity, Vec3 aim) {
        if (channel != 4 || !validAim(entity, aim)) return;
        Vec3 flat = flatDirection(entity, aim);
        if (flat.lengthSqr() > 1.0E-6) aimDirection = flat;
    }

    private void castCore(PokemonEntity entity, ServerLevel level, Skill skill, Vec3 aim) {
        if (skill == null || entity == null || level == null || !validAim(entity, aim)) return;
        if (channel == 3 && skill == Skill.R) return; // E期间禁止大招，且不消耗资源。
        boolean castWhileSpinning = channel == 3 && (skill == Skill.Q || skill == Skill.W);
        if ((isBusy() && !castWhileSpinning) || COOLDOWNS[skill.ordinal()] > 0 || points < skill.cost) return;
        Vec3 dir = flatDirection(entity, aim);
        if (dir.lengthSqr() < 1.0E-6) {
            if (skill != Skill.W) return;
            dir = direction;
        }
        BasicAttack.interruptSwing(entity);
        points -= skill.cost;
        COOLDOWNS[skill.ordinal()] = 40;
        if (!castWhileSpinning) {
            direction = aimDirection = dir;
            skillTicks = 0;
            face(entity);
        }
        switch (skill) {
            case Q -> {
                Vec3 origin = entity.position().add(0, entity.getBbHeight() * 0.55, 0);
                BLADES.add(new Blade(origin, dir));
                visual(level, entity, 1, origin, dir, 18, 1);
                if (!castWhileSpinning) animate(entity, "special");
            }
            case W -> {
                // 落点 = 鼠标指的地面点；只在水平方向限距，超出射程就沿鼠标方向截断到射程边缘。
                Vec3 ground = new Vec3(aim.x, aim.y, aim.z);
                Vec3 flatOffset = new Vec3(aim.x - entity.getX(), 0, aim.z - entity.getZ());
                boolean outOfRange = flatOffset.length() > W_RANGE;
                Vec3 target = outOfRange
                        ? new Vec3(entity.getX(), 0, entity.getZ()).add(flatOffset.normalize().scale(W_RANGE))
                        : ground;
                double surfaceY = outOfRange ? groundY(level, target, entity.getY()) : ground.y;
                target = CharizardCombat.clampToArena(new Vec3(target.x, surfaceY, target.z));
                Vec3 start = target.add(0, 6, 0);
                DROPS.add(new Drop(start));
                visual(level, entity, 2, start, new Vec3(0, -1, 0), 6, 1);
                if (!castWhileSpinning) animate(entity, "special");
            }
            case E -> {
                channel = 3;
                // Shell rendering is client-only. Do not rotate the standing model or disable gravity.
                entity.getNavigation().stop();
            }
            case R -> {
                channel = 4;
                entity.getNavigation().stop();
                if (!castWhileSpinning) animate(entity, "special");
            }
        }
        splashSound(level, entity.position());
    }

    private boolean validAim(PokemonEntity entity, Vec3 aim) {
        return aim != null && Double.isFinite(aim.x) && Double.isFinite(aim.y) && Double.isFinite(aim.z)
                && entity.distanceToSqr(aim) <= 128 * 128;
    }

    /** 限距截断后取该 XZ 的地面高度，让冰山真正落在鼠标指的那一格，而不是悬空或穿地。 */
    private double groundY(ServerLevel level, Vec3 xz, double fallbackY) {
        Vec3 from = new Vec3(xz.x, fallbackY + 24, xz.z);
        Vec3 to = new Vec3(xz.x, fallbackY - 32, xz.z);
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, (net.minecraft.world.entity.Entity) null));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : fallbackY;
    }
    private Vec3 flatDirection(PokemonEntity entity, Vec3 aim) {
        return new Vec3(aim.x - entity.getX(), 0, aim.z - entity.getZ()).normalize();
    }
    private void endChannel(ServerLevel level, PokemonEntity entity) {
        visual(level, entity, 0, entity.position(), direction, 0, 0);
        channel = skillTicks = 0;
    }
    private void turn(PokemonEntity entity, double degrees) {
        double current = Math.atan2(direction.z, direction.x), wanted = Math.atan2(aimDirection.z, aimDirection.x);
        double delta = Math.atan2(Math.sin(wanted - current), Math.cos(wanted - current));
        double step = Math.max(-Math.toRadians(degrees), Math.min(Math.toRadians(degrees), delta));
        direction = new Vec3(Math.cos(current + step), 0, Math.sin(current + step));
        face(entity);
    }
    private void face(PokemonEntity entity) {
        entity.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        entity.setYHeadRot(entity.getYRot());
    }

    private void tickSpin(ServerLevel level, PokemonEntity entity) {
        Vec3 target = MoveOrderService.getTarget(entity);
        entity.getNavigation().stop();
        if (target != null && validAim(entity, target)) {
            Vec3 flat = new Vec3(target.x - entity.getX(), 0, target.z - entity.getZ());
            if (flat.lengthSqr() > 0.04) {
                aimDirection = flat.normalize();
                turn(entity, 18);
                Vec3 before = entity.position();
                Vec3 next = CharizardCombat.clampToArena(before.add(direction.scale(Math.min(0.6, flat.length()))));
                entity.move(MoverType.SELF, next.subtract(before));
            }
        }
        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
        if (skillTicks % 4 == 0) visual(level, entity, 3, entity.position(), direction, 0, 1);
        if (skillTicks % 10 == 0) for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(0.4), e -> BasicAttack.validTarget(e, entity))) {
            Vec3 contact = BasicAttack.closestPoint(other.getBoundingBox(), entity.getBoundingBox().getCenter());
            if (clear(level, entity, entity.getBoundingBox().getCenter(), contact)
                    && CombatDamage.apply(level, entity, other, E_TICK_DAMAGE)) water(level, contact, 6, 0.3);
        }
    }

    /** Clip before testing inflated entity volumes. Five damage ticks at ages 0,8,16,24,32. */
    private void beam(ServerLevel level, PokemonEntity entity, double range, double radius, float damage, boolean cannon) {
        // Mid-body origin and >=.9 vertical radius also cover small ground targets.
        Vec3 from = entity.position().add(0, entity.getBbHeight() * 0.55, 0);
        Vec3 end = clip(level, entity, from, from.add(direction.scale(range)));
        if (skillTicks % 4 == 0) visual(level, entity, cannon ? 4 : 1, from, direction, from.distanceTo(end), radius);
        if (damage <= 0) return;
        // Directional emitters require battle target_delta variables. Beam visuals use WaterEffectPayload only.
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(radius),
                e -> BasicAttack.validTarget(e, entity))) {
            AABB box = target.getBoundingBox().inflate(radius);
            if (!box.contains(from) && box.clip(from, end).isEmpty()) continue;
            Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), from);
            if (!clear(level, entity, from, contact)) continue;
            if (CombatDamage.apply(level, entity, target, damage)) {
                // Each successful Q pulse refreshes a short slow; never apply status to protected/rejected hits.
                if (!cannon) target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 0), entity);
                fx(level, contact, direction, cannon ? "cobblemon:hydropump_targetburst" : "cobblemon:watergun_targetfoam");
            }
        }
    }

    /** Autonomous piercing water blade: swept entity volumes, a collider-clipped step and one hit per UUID. */
    private void tickBlades(ServerLevel level, PokemonEntity entity) {
        for (Iterator<Blade> it = BLADES.iterator(); it.hasNext();) {
            Blade blade = it.next();
            Vec3 from = blade.pos;
            Vec3 intended = from.add(blade.dir.scale(Math.min(1, 18 - blade.travelled)));
            HitResult hit = level.clip(new ClipContext(from, intended, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, entity));
            boolean blocked = hit.getType() == HitResult.Type.BLOCK;
            Vec3 to = blocked ? hit.getLocation() : intended;
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(from, to).inflate(1), e -> BasicAttack.validTarget(e, entity))) {
                if (blade.hits.contains(target.getUUID())) continue;
                AABB volume = target.getBoundingBox().inflate(1);
                if (!volume.contains(from) && volume.clip(from, to).isEmpty()) continue;
                Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), from);
                if (!clear(level, entity, from, contact)) continue;
                blade.hits.add(target.getUUID());
                if (CombatDamage.apply(level, entity, target, Q_DAMAGE)) {
                    fx(level, contact, blade.dir, "cobblemon:watergun_targetfoam");
                }
            }
            blade.travelled += to.distanceTo(from);
            blade.pos = to;
            boolean ended = blocked || blade.travelled >= 17.999;
            visual(level, entity, 1, to, blade.dir, ended ? 0 : 18 - blade.travelled, 1);
            if (ended) it.remove();
        }
    }

    private void tickDrops(ServerLevel level, PokemonEntity entity) {
        for (Iterator<Drop> it = DROPS.iterator(); it.hasNext();) {
            Drop drop = it.next();
            Vec3 from = drop.pos;
            Vec3 intended = from.add(0, -Math.min(1.0, 0.35 + drop.ticks * 0.08), 0);
            HitResult hit = level.clip(new ClipContext(from, intended, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, entity));
            boolean collision = hit.getType() == HitResult.Type.BLOCK;
            drop.pos = collision ? hit.getLocation().add(0, 0.08, 0) : intended;
            drop.ticks++;
            if (collision || drop.ticks >= 32 || drop.pos.y <= drop.targetY
                    || drop.pos.y <= level.getMinBuildHeight()) {
                explodeDrop(level, entity, drop.pos);
                it.remove();
            } else {
                visual(level, entity, 2, drop.pos, new Vec3(0, -1, 0),
                        Math.max(0.01, drop.pos.y - drop.targetY), 1);
            }
        }
    }

    private void explodeDrop(ServerLevel level, PokemonEntity entity, Vec3 center) {
        visual(level, entity, 2, center, Vec3.ZERO, 0, 3);
        splashSound(level, center);
        water(level, center, 35, 1.1);
        fx(level, center, new Vec3(0, 1, 0), "cobblemon:hydropump_targetburst");
        // The drop is removed immediately after this call: each target can only take one 40-damage hit.
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(3), e -> BasicAttack.validTarget(e, entity))) {
            Vec3 contact = BasicAttack.closestPoint(target.getBoundingBox(), center);
            if (contact.distanceToSqr(center) > 9 || !clear(level, entity, center, contact)) continue;
            if (CombatDamage.apply(level, entity, target, W_DAMAGE)) water(level, contact, 10, 0.4);
        }
    }
    private Vec3 clip(ServerLevel level, PokemonEntity entity, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : to;
    }
    private boolean clear(ServerLevel level, PokemonEntity entity, Vec3 from, Vec3 to) {
        return BasicAttack.clearLine(level, entity, from, to);
    }
    private void visual(ServerLevel level, PokemonEntity entity, int kind, Vec3 p, Vec3 dir, double length, double radius) {
        WaterEffectPayload payload = new WaterEffectPayload(entity.getId(), kind, p.x, p.y, p.z, dir.x, dir.y, dir.z, length, radius);
        for (ServerPlayer player : level.players()) if (player.distanceToSqr(p) < 128 * 128
                && ServerPlayNetworking.canSend(player, WaterEffectPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
    private void fx(ServerLevel level, Vec3 p, Vec3 dir, String id) {
        EffectPayload payload = new EffectPayload(id, p.x, p.y, p.z, dir.x, dir.y, dir.z);
        for (ServerPlayer player : level.players()) if (player.distanceToSqr(p) < 128 * 128
                && ServerPlayNetworking.canSend(player, EffectPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
    private void water(ServerLevel level, Vec3 p, int count, double spread) {
        level.sendParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, count, spread, spread * 0.5, spread, 0.04);
    }
    private void splashSound(ServerLevel level, Vec3 p) {
        level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.8F, 1.1F);
    }
    private void animate(PokemonEntity entity, String name) { ((PosableEntity) entity).playAnimation(name, List.of()); }
    private final class Blade {
        Vec3 pos;
        final Vec3 dir;
        double travelled;
        final Set<UUID> hits = new HashSet<>();
        Blade(Vec3 pos, Vec3 dir) { this.pos = pos; this.dir = dir; }
    }
        private void syncHud(ServerPlayer player) {
            float distance = CharizardCombat.distanceToBorder(entity);
            Vec3 center = CharizardCombat.arenaCenter();
            SkillStatePayload payload = new SkillStatePayload(entity.getHealth(), entity.getMaxHealth(), points,
                    COOLDOWNS[0], COOLDOWNS[1], COOLDOWNS[2], COOLDOWNS[3], channel,
                    channel == 4 ? Math.min(1F, skillTicks / 16F) : 0F, distance,
                    (float) center.x, (float) center.y, (float) center.z,
                    (float) CharizardCombat.arenaRadius());
            ServerPlayNetworking.send(player, payload);
        }

    private final class Drop {
        Vec3 pos;
        final double targetY;
        int ticks;
        Drop(Vec3 start) { pos = start; targetY = start.y - 6; }
    }
}
}

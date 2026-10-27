package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.duel.BotDuelService;
import com.pkmoba.duel.DuelManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.SkillStatePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative Pikachu skills. Every damage event goes through CombatDamage. */
public final class PikachuCombat {
    public static final double Q_RANGE = 18.0, W_RANGE = 16.0, E_RANGE = 10.0, R_RANGE = 20.0;
    public static final double W_RADIUS = 4.0, R_RADIUS = 4.5;
    public static final int R_CHARGE_TICKS = 16;
    public static double wTravelDistance(double requested) {
        return Math.min(W_RANGE, Math.max(0.0, requested));
    }
    public static Vec3 aimedPoint(PokemonEntity entity, Vec3 aim, double range) {
        Vec3 delta = aim.subtract(entity.position()).multiply(1, 0, 1);
        double length = delta.length();
        if (length > range) delta = delta.scale(range / length);
        return ground((ServerLevel) entity.level(), entity, entity.position().add(delta));
    }
    private static final int Q_TELEGRAPH_TICKS = 4;
    private static final int W_TELEGRAPH_TICKS = 5;
    private static final int E_DASH_TICKS = 10;
    private static final int R_FIELD_TICKS = 16;
    private static final int W_TRAVEL_TICKS = 24;
    private static final double W_PROJECTILE_RADIUS = 0.7;
    private static final double W_SPEED = W_RANGE / W_TRAVEL_TICKS;
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> SLOW_SOURCES = new HashMap<>();
    private static boolean initialized;

    private PikachuCombat() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> resetAll());
    }

    private static State state(PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), ignored -> new State());
    }

    public static void updateAim(PokemonEntity entity, Vec3 aim) {
        if (entity != null && finite(aim) && state(entity).skillIndex < 0) state(entity).aim = aim;
    }

    public static boolean isChanneling(PokemonEntity entity) { return channel(entity) != 0; }
    public static boolean isBusy(PokemonEntity entity) { return isChanneling(entity); }
    public static boolean blocksBasicAttack(PokemonEntity entity) { return isChanneling(entity); }
    public static boolean basicBlocked(PokemonEntity entity) { return blocksBasicAttack(entity); }
    public static boolean blocksMovement(PokemonEntity entity) {
        int channel = channel(entity);
        return channel == 13 || channel == 14;
    }

    /** HUD/animation wire channels are 11..14; the internal state uses skill indices 1..4. */
    public static int channel(PokemonEntity entity) {
        State current = entity == null ? null : STATES.get(entity.getUUID());
        return current == null || current.skillIndex < 0 ? 0 : current.skillIndex + 11;
    }

    public static float charge(PokemonEntity entity) {
        State current = entity == null ? null : STATES.get(entity.getUUID());
        if (current == null || current.skillIndex < 0) return 0.0F;
        int total = switch (current.skillIndex) {
            case 1 -> W_TELEGRAPH_TICKS;
            case 3 -> R_CHARGE_TICKS;
            default -> Q_TELEGRAPH_TICKS;
        };
        return Math.min(1.0F, current.elapsed / (float) total);
    }

    public static int cooldown(PokemonEntity entity, int index) {
        State current = entity == null ? null : STATES.get(entity.getUUID());
        return current == null || index < 0 || index >= current.cooldowns.length ? 0 : current.cooldowns[index];
    }

    public static float points(PokemonEntity entity) {
        return entity == null ? 0 : state(entity).points;
    }

    public static boolean canCastForBot(PokemonEntity entity, Skill skill) {
        if (entity == null || skill == null || !BotDuelService.isBot(entity)) return false;
        ServerPlayer actor = BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        State current = state(entity);
        int index = skill.ordinal();
        return authorized(actor, entity) && !DuelManager.isPreparing(entity)
                && !com.pkmoba.pokemon.BallThrow.locked(entity)
                && current.skillIndex < 0 && current.cooldowns[index] == 0
                && current.points >= Skill.cost(entity, index);
    }

    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (canCastForBot(entity, skill)) {
            ServerPlayer actor = BotDuelService.actor(CharizardManager.getControlledOwner(entity));
            cast(actor, entity, skill, aim);
        }
    }

    /** Adapter signature for ordinary player casts; uses the manager's currently controlled Pikachu. */
    public static void cast(ServerPlayer player, Skill skill, Vec3 aim) {
        if (player != null) cast(player, CharizardManager.getControlled(player), skill, aim);
    }

    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (!authorized(player, entity) || skill == null || !finite(aim)
                || DuelManager.isPreparing(entity) || com.pkmoba.pokemon.BallThrow.locked(entity)) return;
        State current = state(entity);
        int index = skill.ordinal();
        if (current.skillIndex >= 0 || current.cooldowns[index] > 0
                || current.points < Skill.cost(entity, index)) return;

        double range = switch (index) { case 0 -> Q_RANGE; case 1 -> W_RANGE; case 2 -> E_RANGE; default -> R_RANGE; };
        Vec3 delta = aim.subtract(entity.position());
        double horizontalLength = delta.multiply(1, 0, 1).length();
        if (horizontalLength > range) delta = delta.scale(range / horizontalLength);
        Vec3 direction = delta.multiply(1, 0, 1);
        if (direction.lengthSqr() < 1.0e-4) direction = entity.getLookAngle().multiply(1, 0, 1);
        if (direction.lengthSqr() < 1.0e-4) direction = new Vec3(0, 0, 1);
        current.skillIndex = index;
        current.elapsed = 0;
        current.points -= Skill.cost(entity, index);
        current.regenTicks = 0;
        current.cooldowns[index] = Skill.cooldownTicks(entity, index);
        current.start = entity.position().add(0, entity.getBbHeight() * 0.55, 0);
        current.direction = direction.normalize();
        current.aim = index == 1 || index == 3
                ? aimedPoint(entity, aim, range) : entity.position().add(delta);
        current.hit.clear();
        if (index == 0) current.aim = current.start.add(current.direction.scale(Q_RANGE));
        if (index == 1) {
            current.webPosition = current.start;
            current.detonated = false;
        }
        if (index == 3) current.field = aimedPoint(entity, current.aim, R_RANGE);
        if (index == 1) current.field = aimedPoint(entity, current.aim, W_RANGE);
        if (index == 1) {
            Vec3 flight = current.field.subtract(current.start).multiply(1, 0, 1);
            if (flight.length() > W_RANGE) flight = flight.normalize().scale(W_RANGE);
            current.field = current.start.add(flight);
            current.webDistance = flight.length();
            current.projectileDirection = flight.lengthSqr() < 1.0e-4 ? current.direction : flight.normalize();
        }
        if (index == 2) current.direction = current.direction.normalize();
        MoveOrderService.cancelPending(entity);
        entity.getNavigation().stop();
        if (index == 2 || index == 3) entity.setDeltaMovement(Vec3.ZERO);
        ServerLevel level = (ServerLevel) entity.level();
        level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 0.55F, index == 3 ? 1.65F : 1.9F);
        if (index == 1) sendCue(level, entity, "w_charge", current.start, new Vec3(0, 0, 0));
        else if (index == 2) sendCue(level, entity, "e_start", entity.position(), current.direction);
        else if (index == 3) {
            sendCue(level, entity, "r_charge", current.start, new Vec3(0, 0, 0));
            sendCue(level, entity, "r_warn", current.field, new Vec3(R_RADIUS, 0, 0));
        }
        syncHud(player, entity);
    }

    public static void tick(ServerLevel level, PokemonEntity entity) {
        if (!Skill.isPikachu(entity)) return;
        State current = state(entity);
        for (int i = 0; i < current.cooldowns.length; i++) {
            if (current.cooldowns[i] > 0) current.cooldowns[i]--;
        }
        if (++current.regenTicks >= 80) {
            current.regenTicks = 0;
            current.points = Math.min(10.0F, current.points + 1.0F);
        }
        expireSlowSources(level);
        if (DuelManager.isPreparing(entity)) {
            clearTransient(entity);
            return;
        }
        if (current.webTicks > 0) tickCatch(level, entity, current);
        if (current.rFieldTicks > 0) tickThunderField(level, entity, current);
        if (current.skillIndex < 0) return;

        current.elapsed++;
        if (current.skillIndex == 1 && current.elapsed < W_TELEGRAPH_TICKS) {
            sendCue(level, entity, "w_charge", current.start,
                    new Vec3(current.elapsed / (double) W_TELEGRAPH_TICKS, 0, 0));
        }
        if (current.skillIndex == 3) {
            sendCue(level, entity, "r_charge", current.start,
                    new Vec3(Math.min(1.0, current.elapsed / (double) R_CHARGE_TICKS), 0, 0));
            if (current.elapsed % 2 == 0) sendCue(level, entity, "r_warn", current.field,
                    new Vec3(R_RADIUS, current.elapsed / (double) R_CHARGE_TICKS, 0));
        }
        FacingService.request(entity, FacingService.Priority.CHANNEL, current.direction, 360.0F);
        if (current.skillIndex == 3 && current.elapsed < R_CHARGE_TICKS)
            sparkCloud(level, entity.position().add(0, entity.getBbHeight() * 0.65, 0), 6, 0.42);
        switch (current.skillIndex) {
            case 0 -> tickThunderShock(level, entity, current);
            case 1 -> tickElectroweb(level, entity, current);
            case 2 -> tickQuickAttack(level, entity, current);
            case 3 -> tickThunder(level, entity, current);
            default -> { }
        }
        int duration = switch (current.skillIndex) {
            case 0 -> Q_TELEGRAPH_TICKS;
            case 1 -> W_TELEGRAPH_TICKS + W_TRAVEL_TICKS;
            case 2 -> E_DASH_TICKS;
            case 3 -> R_CHARGE_TICKS + 12;
            default -> 1;
        };
        if (current.elapsed >= duration && current.skillIndex >= 0) finish(level, entity, current);
    }

    private static void tickThunderShock(ServerLevel level, PokemonEntity entity, State current) {
        if (current.elapsed != Q_TELEGRAPH_TICKS) return;
        Vec3 end = clippedEnd(level, entity, current.start, current.aim);
        LivingEntity hit = firstAlong(level, entity, current.start, end, 0.5);
        if (hit != null) end = hit.getBoundingBox().getCenter();
        sendCue(level, entity, "q_bolt", current.start, end.subtract(current.start));
        level.playSound(null, entity.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT,
                SoundSource.PLAYERS, .65F, 1.7F);
        if (hit != null && current.hit.add(hit.getUUID())) {
            damage(level, entity, hit, 18.0F, TypeEffectiveness.MoveType.ELECTRIC);
        }
    }

    private static void tickElectroweb(ServerLevel level, PokemonEntity entity, State current) {
        if (current.detonated) return;
        if (current.elapsed < W_TELEGRAPH_TICKS) {
            return;
        }
        Vec3 from = current.webPosition;
        Vec3 remaining = current.field.subtract(from);
        double step = Math.min(W_SPEED, remaining.length());
        if (step <= 0.02) {
            detonatePulse(level, entity, current, current.field);
            return;
        }
        Vec3 intended = from.add(remaining.normalize().scale(step));
        Vec3 next = clippedEnd(level, entity, from, intended);
        LivingEntity target = firstAlong(level, entity, from, next, W_PROJECTILE_RADIUS);
        boolean blocked = next.distanceToSqr(intended) > .001;
        current.webPosition = target == null ? next : target.getBoundingBox().getCenter();
        sendCue(level, entity, "w_orb_fly", current.webPosition,
                current.field.subtract(current.webPosition));
        if (target != null || blocked || current.webPosition.distanceToSqr(current.field) < .15) {
            Vec3 impact = target == null ? current.webPosition : target.getBoundingBox().getCenter();
            detonatePulse(level, entity, current, impact);
        }
    }

    private static void detonatePulse(ServerLevel level, PokemonEntity source, State current, Vec3 impact) {
        if (current.detonated) return;
        current.detonated = true;
        current.webPosition = impact;
        sendCue(level, source, "w_orb_burst", impact, new Vec3(W_RADIUS, 0, 0));
        level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
                SoundSource.PLAYERS, .8F, 1.25F);
        sparkCloud(level, impact, 32, .9);
        for (LivingEntity target : targets(level, source, impact, W_RADIUS)) {
            if (!clearLine(level, source, impact.add(0, .25, 0), target.getBoundingBox().getCenter())) continue;
            damage(level, source, target, 22.0F, TypeEffectiveness.MoveType.ELECTRIC);
            applySlow(source, target, level.getGameTime() + 35);
        }
        finish(level, source, current);
    }

    private static void tickCatch(ServerLevel level, PokemonEntity source, State current) {
        current.webTicks = 0;
        current.caught = null;
    }

    private static void tickQuickAttack(ServerLevel level, PokemonEntity entity, State current) {
        Vec3 step = current.direction.scale(E_RANGE / E_DASH_TICKS);
        if (level.noCollision(entity, entity.getBoundingBox().move(step))) {
            Vec3 next = entity.position().add(step);
            entity.setPos(next.x, next.y, next.z);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        if (current.elapsed % 2 == 0) sendCue(level, entity, "e_trail", entity.position(), current.direction);
        if (current.hit.isEmpty()) {
            LivingEntity target = targets(level, entity,
                    entity.position().add(0, entity.getBbHeight() * 0.4, 0), 1.45).stream()
                    .filter(candidate -> clearLine(level, entity, entity.getEyePosition(), candidate.getBoundingBox().getCenter()))
                    .findFirst().orElse(null);
            if (target != null) {
                current.hit.add(target.getUUID());
                damage(level, entity, target, 14.0F, TypeEffectiveness.MoveType.PHYSICAL);
                sendCue(level, entity, "e_hit", target.getBoundingBox().getCenter(), current.direction);
            }
        }
    }

    private static void tickThunder(ServerLevel level, PokemonEntity entity, State current) {
        entity.setDeltaMovement(Vec3.ZERO);
        if (current.elapsed < R_CHARGE_TICKS) {
            if (current.elapsed % 5 == 0) level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_POWER_SELECT,
                    SoundSource.PLAYERS, .45F, .7F + current.elapsed*.035F);
            return;
        }
        int strikeAge = current.elapsed - R_CHARGE_TICKS;
        if (strikeAge == 1 || strikeAge == 5 || strikeAge == 9) {
            sendCue(level, entity, "r_strike", current.field.add(0, 18, 0), new Vec3(0, -18, 0));
            level.playSound(null, current.field.x, current.field.y, current.field.z,
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.8F, 1.4F);
            sparkCloud(level, current.field.add(0, 0.4, 0), 44, 1.15);
            for (LivingEntity target : targets(level, entity, current.field, R_RADIUS)) {
                if (clearLine(level, entity, entity.getEyePosition(), target.getBoundingBox().getCenter())) {
                    CombatDamage.apply(level, entity, target, 12.0F,
                            com.pkmoba.network.EffectPayload.Element.PHYSICAL,
                            com.pkmoba.network.EffectPayload.Power.ULTIMATE, TypeEffectiveness.MoveType.ELECTRIC);
                }
            }
            current.rFieldTicks = R_FIELD_TICKS;
        }
        if (current.rFieldTicks > 0) sendCue(level, entity, "r_field", current.field, new Vec3(R_RADIUS, 0, 0));
    }

    private static void tickThunderField(ServerLevel level, PokemonEntity entity, State current) {
        int remaining = --current.rFieldTicks;
        Vec3 center = current.field;
        if (remaining % 4 == 0) sendCue(level, entity, "r_field", center, new Vec3(R_RADIUS, 0, 0));
        if (remaining % 10 != 0) return;
        for (LivingEntity target : targets(level, entity, center, R_RADIUS)) {
            applySlow(entity, target, level.getGameTime() + 12);
            damage(level, entity, target, 2.0F, TypeEffectiveness.MoveType.ELECTRIC);
        }
    }

    private static List<LivingEntity> targets(ServerLevel level, PokemonEntity source, Vec3 center, double radius) {
        AABB bounds = new AABB(center, center).inflate(radius, 3.0, radius);
        return level.getEntitiesOfClass(LivingEntity.class, bounds,
                target -> BasicAttack.validTarget(target, source)
                        && !target.getUUID().equals(CharizardManager.getControlledOwner(source))
                        && target.position().distanceToSqr(center) <= radius * radius + 4.0);
    }

    private static LivingEntity firstAlong(ServerLevel level, PokemonEntity source, Vec3 start, Vec3 end, double radius) {
        AABB bounds = new AABB(start, end).inflate(radius);
        LivingEntity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, bounds,
                candidate -> BasicAttack.validTarget(candidate, source)
                        && !candidate.getUUID().equals(CharizardManager.getControlledOwner(source)))) {
            AABB box = target.getBoundingBox().inflate(radius);
            Vec3 contact = box.contains(start) ? start : box.clip(start, end).orElse(null);
            if (contact == null || !clearLine(level, source, start, target.getBoundingBox().getCenter())) continue;
            double distance = start.distanceToSqr(contact);
            if (distance < closestDistance) { closest = target; closestDistance = distance; }
        }
        return closest;
    }

    private static void damage(ServerLevel level, PokemonEntity source, LivingEntity target,
                               float amount, TypeEffectiveness.MoveType moveType) {
        if (clearLine(level, source, source.getEyePosition(), target.getBoundingBox().getCenter())) {
            CombatDamage.apply(level, source, target, amount, moveType);
        }
    }

    private static Vec3 clippedEnd(ServerLevel level, PokemonEntity source, Vec3 start, Vec3 intended) {
        HitResult hit = level.clip(new ClipContext(start, intended, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source));
        return hit.getType() == HitResult.Type.MISS ? intended : hit.getLocation();
    }

    private static Vec3 ground(ServerLevel level, PokemonEntity source, Vec3 point) {
        Vec3 bounded = CharizardCombat.clampToArena(source, point);
        HitResult hit = level.clip(new ClipContext(bounded.add(0, 3, 0), bounded.add(0, -6, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source));
        return hit.getType() == HitResult.Type.MISS ? bounded : hit.getLocation().add(0, 0.04, 0);
    }

    public static Vec3 previewGround(LivingEntity source, Vec3 point) {
        if (source == null) return point;
        Vec3 bounded = source instanceof PokemonEntity pokemon && source.level() instanceof ServerLevel
                ? CharizardCombat.clampToArena(pokemon, point) : point;
        HitResult hit = source.level().clip(new ClipContext(bounded.add(0, 3, 0), bounded.add(0, -6, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source));
        return hit.getType() == HitResult.Type.MISS ? bounded : hit.getLocation().add(0, 0.04, 0);
    }

    private static boolean clearLine(ServerLevel level, PokemonEntity source, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
    }

    private static void applySlow(PokemonEntity source, LivingEntity target, long until) {
        SLOW_SOURCES.computeIfAbsent(target.getUUID(), ignored -> new HashMap<>())
                .merge(source.getUUID(), until, Math::max);
    }

    private static void expireSlowSources(ServerLevel level) {
        SLOW_SOURCES.values().forEach(sources -> sources.values().removeIf(until -> until <= level.getGameTime()));
        SLOW_SOURCES.values().removeIf(Map::isEmpty);
    }

    public static double movementSpeedMultiplier(PokemonEntity entity) {
        if (entity == null) return 1.0;
        Map<UUID, Long> sources = SLOW_SOURCES.get(entity.getUUID());
        if (sources == null) return 1.0;
        long now = entity.level().getGameTime();
        sources.values().removeIf(until -> until <= now);
        if (sources.isEmpty()) SLOW_SOURCES.remove(entity.getUUID());
        return sources.isEmpty() ? 1.0 : 0.55;
    }

    private static void finish(ServerLevel level, PokemonEntity entity, State current) {
        // Only actual lightning strikes start/refresh the field; ending a cast preserves its remaining lifetime.
        current.skillIndex = -1;
        current.elapsed = 0;
        current.hit.clear();
        current.detonated = false;
        FacingService.clear(entity);
        // Let impact textures finish their short client lifetime. Only cancellation sends clear.
    }

    public static void clearTransient(PokemonEntity entity) {
        if (entity == null) return;
        State current = STATES.get(entity.getUUID());
        if (current == null) return;
        boolean active = current.skillIndex >= 0 || current.webTicks > 0 || current.rFieldTicks > 0;
        current.skillIndex = -1;
        current.webTicks = 0;
        current.caught = null;
        current.rFieldTicks = 0;
        current.detonated = false;
        current.hit.clear();
        clearSlowFrom(entity.getUUID());
        FacingService.clear(entity);
        if (active && entity.level() instanceof ServerLevel level) sendCue(level, entity, "clear", entity.position(), Vec3.ZERO);
    }

    private static void clearSlowFrom(UUID source) {
        SLOW_SOURCES.values().forEach(sources -> sources.remove(source));
        SLOW_SOURCES.values().removeIf(Map::isEmpty);
    }

    public static void reset(PokemonEntity entity) {
        if (entity == null) return;
        clearTransient(entity);
        STATES.remove(entity.getUUID());
    }

    public static void resetAll() {
        for (State current : new ArrayList<>(STATES.values())) current.skillIndex = -1;
        STATES.clear();
        SLOW_SOURCES.clear();
    }

    public static void syncHud(ServerPlayer player, PokemonEntity entity) {
        if (player == null || player.connection == null || entity == null
                || !ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) return;
        State current = state(entity);
        Vec3 center = CharizardCombat.arenaCenter();
        ServerPlayNetworking.send(player, new SkillStatePayload(entity.getHealth(), entity.getMaxHealth(),
                current.points, current.cooldowns[0], current.cooldowns[1], current.cooldowns[2], current.cooldowns[3],
                channel(entity), charge(entity), CharizardCombat.distanceToBorder(entity),
                (float) center.x, (float) center.y, (float) center.z, (float) CharizardCombat.arenaRadius()));
    }

    private static boolean authorized(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null || !entity.isAlive() || !Skill.isPikachu(entity)
                || player.level() != entity.level()
                || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))) return false;
        return BotDuelService.isBot(entity) ? player == BotDuelService.actor(player.getUUID())
                : ServerGodMode.isActive(player);
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void sendCue(ServerLevel level, PokemonEntity entity, String kind, Vec3 position, Vec3 vector) {
        if (!finite(position) || !finite(vector)) return;
        String id = "pkmoba:pikachu/" + kind + "/" + entity.getId();
        EffectPayload payload = new EffectPayload(id, position.x, position.y, position.z,
                vector.x, vector.y, vector.z);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(position) <= 128.0 * 128.0
                    && ServerPlayNetworking.canSend(player, EffectPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static void sparkCloud(ServerLevel level, Vec3 position, int count, double spread) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y, position.z,
                count, spread, spread * 0.6, spread, 0.025);
    }

    private static final class State {
        final int[] cooldowns = new int[4];
        final Set<UUID> hit = new HashSet<>();
        int skillIndex = -1;
        int elapsed;
        int regenTicks;
        int webTicks;
        boolean detonated;
        Vec3 projectileDirection = new Vec3(0, 0, 1);
        int rFieldTicks;
        float points = 10.0F;
        Vec3 start = Vec3.ZERO;
        Vec3 aim = Vec3.ZERO;
        Vec3 direction = new Vec3(0, 0, 1);
        Vec3 field = Vec3.ZERO;
        Vec3 webPosition = Vec3.ZERO;
        double webDistance;
        LivingEntity caught;
    }
}

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
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
    public static final int R_CHARGE_TICKS = 34;
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
    private static final int W_TELEGRAPH_TICKS = 6;
    private static final int E_DASH_TICKS = 10;
    private static final int R_FIELD_TICKS = 16;
    /** v2: 十万伏特 is a sustained beam, not a thrown pulse ball. */
    private static final int W_TRAVEL_TICKS = 12;
    private static final int W_HIT_EVERY = 4;
    private static final float W_BEAM_DAMAGE = 7.0F;
    private static final double W_BEAM_RADIUS = 0.9;
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
    // 站桩输出：引导中的技能锁地面移动（E/R 原本就以 setDeltaMovement(ZERO) 定身）。
    public static boolean blocksMovement(PokemonEntity entity) { return isChanneling(entity); }

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
            current.detonated = false;
        current.dashRoute = null;
        }
        if (index == 3) current.field = aimedPoint(entity, current.aim, R_RANGE);
        if (index == 1) {
            // The beam runs from the chest to the aimed point (clipped by terrain later), so the
            // aiming HUD and the server damage use the same endpoint.
            current.field = aimedPoint(entity, aim, W_RANGE);
            Vec3 flatten = current.field.subtract(current.start).multiply(1, 0, 1);
            if (flatten.lengthSqr() < 1.0e-4) flatten = current.direction;
            current.direction = flatten.normalize();
            current.aim = current.field;
        }
        if (index == 2) current.direction = current.direction.normalize();
        MoveOrderService.cancelPending(entity);
        entity.getNavigation().stop();
        if (index == 2 || index == 3) entity.setDeltaMovement(Vec3.ZERO);
        ServerLevel level = (ServerLevel) entity.level();
        level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 0.55F, index == 3 ? 1.65F : 1.9F);
        if (index == 1) sendCue(level, entity, "w_charge", current.start, new Vec3(0, 0, 0));
        else if (index == 2) {
            // v3：电光一闪先画出一条完整的闪电路径，皮卡丘再沿着它冲过去。所以这里送的是
            // “起点 -> 落点”的真实位移向量（按地形裁短），而不是单位朝向。
            Vec3 chest = entity.position().add(0, 0.45, 0);
            Vec3 intended = chest.add(current.direction.scale(E_RANGE));
            Vec3 dashEnd = clippedEnd(level, entity, chest, intended);
            Vec3 path = dashEnd.subtract(chest).multiply(1, 0, 1);
            if (path.lengthSqr() < 1.0e-3) path = current.direction.scale(E_RANGE).multiply(1, 0, 1);
            Vec3 flat = path.multiply(1, 0, 1);
            Vec3 pathDir = flat.lengthSqr() < 1.0e-4 ? current.direction.multiply(1, 0, 1).normalize()
                    : flat.normalize();
            Vec3 side = new Vec3(-pathDir.z, 0, pathDir.x);
            double span = flat.length();
            Vec3 base = entity.position();
            current.dashRoute = new Vec3[]{
                    base,
                    base.add(pathDir.scale(span * .32)).add(side.scale(1.5)),
                    base.add(pathDir.scale(span * .66)).subtract(side.scale(1.5)),
                    new Vec3(base.x + flat.x, dashEnd.y, base.z + flat.z)
            };
            sendCue(level, entity, "e_start", chest, path);
        }
        else if (index == 3) {
            sendCue(level, entity, "r_charge", current.start, new Vec3(0, 0, 0));
            sendCue(level, entity, "r_warn", current.field, new Vec3(R_RADIUS, 0, 0));
        }
        // 0.48.5：确认施法这一帧把方向/落点钉死（CastLock）。之后 tick 里每 tick 重申的就是这份值，
        // 鼠标再动、AI 或旧移动指令都改不了；伤害/特效/模型朝向读的是同一份，不会再对不上。
        switch (index) {
            case 0 -> CastLock.begin(entity, CastLock.Shape.LINE, current.direction, current.aim, Q_TELEGRAPH_TICKS);
            case 1 -> CastLock.begin(entity, CastLock.Shape.LINE, current.direction, current.field,
                    W_TELEGRAPH_TICKS + W_TRAVEL_TICKS);
            case 2 -> {
                Vec3 travelEnd = entity.position().add(current.direction.scale(E_RANGE));
                double ground = CharizardCombat.groundBelow(level, entity, travelEnd);
                // 0.48.5：按"这只宝可梦所属场地"裁剪（多房间并发时不能拿全局边界裁别人的终点）。
                Vec3 dashEnd = CharizardCombat.clampToArena(entity, new Vec3(travelEnd.x,
                        Double.isFinite(ground) ? ground : entity.getY(), travelEnd.z));
                CastLock.begin(entity, CastLock.Shape.DASH, current.direction, dashEnd, E_DASH_TICKS);
            }
            default -> CastLock.begin(entity, CastLock.Shape.POINT, current.direction, current.field,
                    R_CHARGE_TICKS + 12);
        }
        syncHud(player, entity);
    }

    public static void tick(ServerLevel level, PokemonEntity entity) {
        if (!Skill.isPikachu(entity)) return;
        State current = state(entity);
        for (int i = 0; i < current.cooldowns.length; i++) {
            if (current.cooldowns[i] > 0) current.cooldowns[i]--;
        }
        if (++current.regenTicks >= 20) {
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
        // 0.48.5：方向读施法锁定值（起手那一帧已定），每 tick 重申同一份，鼠标不能中途改向。
        FacingService.request(entity, FacingService.Priority.CHANNEL,
                CastLock.dirOr(entity, current.direction), 360.0F);
        if (current.skillIndex == 3 && current.elapsed < R_CHARGE_TICKS)
            sparkCloud(level, entity.position().add(0, entity.getBbHeight() * 0.65, 0), 6, 0.42);
        switch (current.skillIndex) {
            case 0 -> tickThunderShock(level, entity, current);
            case 1 -> tickThunderbolt(level, entity, current);
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
            // v2 电击: the bolt arcs on to up to two more targets inside 6 blocks.
            Vec3 arcFrom = hit.getBoundingBox().getCenter();
            float arcDamage = 11.0F;
            for (int link = 0; link < 2; link++) {
                LivingEntity next = chainTarget(level, entity, arcFrom, 6.0, current.hit);
                if (next == null) break;
                current.hit.add(next.getUUID());
                Vec3 arcTo = next.getBoundingBox().getCenter();
                sendCue(level, entity, "q_chain", arcFrom, arcTo.subtract(arcFrom));
                damage(level, entity, next, arcDamage, TypeEffectiveness.MoveType.ELECTRIC);
                sparkCloud(level, arcTo, 8, .35);
                arcFrom = arcTo;
                arcDamage *= 0.7F;
            }
        }
    }

    /** Nearest extra target for a chain arc, excluding everything already struck this cast. */
    private static LivingEntity chainTarget(ServerLevel level, PokemonEntity source, Vec3 center,
                                            double radius, java.util.Set<UUID> exclude) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : targets(level, source, center, radius)) {
            if (exclude.contains(candidate.getUUID())) continue;
            if (!clearLine(level, source, center, candidate.getBoundingBox().getCenter())) continue;
            double distance = candidate.position().distanceToSqr(center);
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }

    /** v2 十万伏特: a 12 tick sustained beam that ticks damage four times along its length. */
    private static void tickThunderbolt(ServerLevel level, PokemonEntity entity, State current) {
        if (current.elapsed <= W_TELEGRAPH_TICKS) return;
        int beamAge = current.elapsed - W_TELEGRAPH_TICKS;
        if (beamAge > W_TRAVEL_TICKS) return;
        Vec3 from = current.start;
        Vec3 end = clippedEnd(level, entity, from, current.field);
        LivingEntity target = firstAlong(level, entity, from, end, 0.55);
        if (target != null) end = target.getBoundingBox().getCenter();
        if (beamAge == 1 || beamAge % 2 == 0) {
            sendCue(level, entity, "w_beam", from, end.subtract(from));
        }
        if (beamAge == 1) {
            level.playSound(null, entity.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT,
                    SoundSource.PLAYERS, .75F, 1.45F);
        }
        if (beamAge == 1 || beamAge % W_HIT_EVERY == 0) {
            for (LivingEntity hit : targets(level, entity, end, W_BEAM_RADIUS)) {
                if (!clearLine(level, entity, from, hit.getBoundingBox().getCenter())) continue;
                current.hit.add(hit.getUUID());
                damage(level, entity, hit, W_BEAM_DAMAGE, TypeEffectiveness.MoveType.ELECTRIC);
            }
            sparkCloud(level, end, 10, .45);
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
        if (current.dashRoute != null) {
            // 0.48.5：电光一闪沿折线跑，模型贴着客户端画出的那条闪电走。
            Vec3 target = pointAlong(current.dashRoute, Math.min(1.0, current.elapsed / (double) E_DASH_TICKS));
            Vec3 move = target.subtract(entity.position());
            if (move.horizontalDistanceSqr() > 1.0e-4
                    && level.noCollision(entity, entity.getBoundingBox().move(move.x, 0, move.z))) {
                entity.setPos(target.x, entity.getY(), target.z);
            }
            entity.setDeltaMovement(Vec3.ZERO);
            if (current.elapsed % 2 == 0) sendCue(level, entity, "e_trail", entity.position(), current.direction);
            tickQuickAttackHit(level, entity, current);
            return;
        }
        Vec3 step = current.direction.scale(E_RANGE / E_DASH_TICKS);
        // 0.48.5：E 冲刺的真实路径也按施法锁定终点收（CastLock.DASH 的 point）——
        // 起手时终点可能被场地边界夹短（多房间各裁各的），后续 tick 不能还按 E_RANGE 一路冲过头。
        // 锁定被硬清（死亡/收回）时退回原来的固定步长。
        Vec3 lockEnd = CastLock.point(entity);
        if (lockEnd != null) {
            double dx = lockEnd.x - entity.getX();
            double dz = lockEnd.z - entity.getZ();
            double toEnd = Math.sqrt(dx * dx + dz * dz);
            double stepLen = Math.sqrt(step.x * step.x + step.z * step.z);
            if (toEnd <= stepLen) {
                step = new Vec3(dx, 0.0, dz);
            }
        }
        if (level.noCollision(entity, entity.getBoundingBox().move(step))) {
            Vec3 next = entity.position().add(step);
            entity.setPos(next.x, next.y, next.z);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        if (current.elapsed % 2 == 0) sendCue(level, entity, "e_trail", entity.position(), current.direction);
        tickQuickAttackHit(level, entity, current);
    }

    /** Samples the E dash polyline by fractional arc length. */
    private static Vec3 pointAlong(Vec3[] route, double t) {
        double total = 0;
        for (int i = 0; i < route.length - 1; i++) total += route[i].distanceTo(route[i + 1]);
        if (total < 1.0e-4) return route[route.length - 1];
        double want = Math.max(0, Math.min(1, t)) * total;
        for (int i = 0; i < route.length - 1; i++) {
            double len = route[i].distanceTo(route[i + 1]);
            if (want <= len) return route[i].lerp(route[i + 1], want / Math.max(len, 1.0e-4));
            want -= len;
        }
        return route[route.length - 1];
    }

    private static void tickQuickAttackHit(ServerLevel level, PokemonEntity entity, State current) {
        if (current.hit.isEmpty()) {
            LivingEntity target = targets(level, entity,
                    entity.position().add(0, entity.getBbHeight() * 0.4, 0), 1.45).stream()
                    .filter(candidate -> clearLine(level, entity, entity.getEyePosition(), candidate.getBoundingBox().getCenter()))
                    .findFirst().orElse(null);
            if (target != null) {
                current.hit.add(target.getUUID());
                damage(level, entity, target, 14.0F, TypeEffectiveness.MoveType.PHYSICAL);
                Vec3 impact = target.getBoundingBox().getCenter();
                sendCue(level, entity, "e_hit", impact, current.direction);
                // v2 电光一闪: the impact discharges into anything else standing next to it.
                for (LivingEntity extra : targets(level, entity, impact, 3.0)) {
                    if (!current.hit.add(extra.getUUID())) continue;
                    Vec3 spot = extra.getBoundingBox().getCenter();
                    sendCue(level, entity, "e_chain", impact, spot.subtract(impact));
                    damage(level, entity, extra, 6.0F, TypeEffectiveness.MoveType.ELECTRIC);
                }
            }
        }
    }

    private static void tickThunder(ServerLevel level, PokemonEntity entity, State current) {
        entity.setDeltaMovement(Vec3.ZERO);
        if (current.elapsed < R_CHARGE_TICKS) {
            if (current.elapsed % 5 == 0) level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_POWER_SELECT,
                    SoundSource.PLAYERS, .45F, .7F + current.elapsed*.035F);
            if (current.elapsed % 4 == 0) {
                sendCue(level, entity, "r_cloud", current.field, new Vec3(R_RADIUS, 0, 0));
            }
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
                    CombatDamage.apply(level, entity, target, 9.0F,
                            com.pkmoba.network.EffectPayload.Element.PHYSICAL,
                            com.pkmoba.network.EffectPayload.Power.HEAVY, TypeEffectiveness.MoveType.ELECTRIC);
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
        // 0.48.5：收招再锁 2 tick 再交还预瞄（避免释放瞬间模型被鼠标甩走）。
        CastLock.finish(entity, 2);
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
        // 0.48.5：取消/死亡/换人时直接释放施法锁（不是 finish，不能留 2 tick）。
        CastLock.release(entity);
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
        // 0.48.5：对局结束/全员收回时把施法锁与朝向仲裁一起清掉。
        CastLock.clearAll();
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
        /** E 电光一闪：模型沿这条折线（起点→左→右→落点）走，不是直线冲过去。 */
        Vec3[] dashRoute;
    }
}

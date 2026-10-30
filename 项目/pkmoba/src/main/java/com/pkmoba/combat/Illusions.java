package com.pkmoba.combat;

import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 0.133 Hisuian Zoroark: the three 幻象分身 of the ultimate.
 *
 * <p>They are real Pokémon entities so they render with the real Zoroark model and its own
 * animation state, but they are strictly cosmetic: tagged {@link #TAG}, invulnerable, no AI, no
 * loot, and every damage path skips them through {@link BasicAttack#validTarget}. They live for the
 * duration of the ultimate and are removed the moment it ends, when the owner dies or leaves, and
 * when the server stops.
 */
public final class Illusions {
    public static final String TAG = "pkmoba_illusion";
    private static final int LIFETIME = 80;
    private static final double SPEED = 0.16;
    /** Illusions fight: one Shadow Claw every 1.5s, at 35% of the body's 40 damage. */
    private static final int ATTACK_PERIOD = 30;
    private static final float CLAW_DAMAGE = 14F;
    private static final double ATTACK_REACH = 5.0;

    private record Ghost(PokemonEntity entity, Vec3 dir, long until, double travelled, long nextAttack) {
    }

    private static final Map<UUID, List<Ghost>> ACTIVE = new HashMap<>();

    private Illusions() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Illusions::tick);
    }

    public static boolean isIllusion(net.minecraft.world.entity.Entity entity) {
        return entity != null && entity.getTags().contains(TAG);
    }

    /** Spawns {@code count} copies that fan out away from the caster, one per direction in the ring. */
    public static void spawn(ServerLevel level, PokemonEntity source, int count) {
        if (level == null || source == null || source.getPokemon() == null) return;
        clear(source);
        List<Ghost> ghosts = new ArrayList<>();
        Vec3 forward = new Vec3(source.getLookAngle().x, 0, source.getLookAngle().z);
        if (forward.lengthSqr() < 1E-4) forward = new Vec3(0, 0, 1);
        forward = forward.normalize();
        long until = level.getGameTime() + LIFETIME;
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2 / count) * i + 0.6;
            Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 at = source.position().add(dir.scale(1.4)).add(0, 0, 0);
            PokemonEntity ghost;
            try {
                ghost = new PokemonEntity(level, source.getPokemon().clone(true, level.registryAccess()),
                        CobblemonEntities.POKEMON);
            } catch (Throwable error) {
                com.pkmoba.PkmobaMod.LOGGER.warn("illusion spawn failed: {}", error.toString());
                return;
            }
            ghost.moveTo(at.x, at.y, at.z, source.getYRot(), 0);
            ghost.setNoAi(true);
            ghost.setInvulnerable(true);
            ghost.setSilent(true);
            ghost.setPersistenceRequired();
            ghost.addTag(TAG);
            level.addFreshEntity(ghost);
            ghosts.add(new Ghost(ghost, dir, until, 0.0, level.getGameTime() + 10 + i * 6L));
        }
        ACTIVE.put(source.getUUID(), ghosts);
    }

    /** Removes every illusion belonging to that caster. */
    public static void clear(PokemonEntity source) {
        if (source == null) return;
        List<Ghost> ghosts = ACTIVE.remove(source.getUUID());
        if (ghosts == null) return;
        for (Ghost ghost : ghosts) ghost.entity().discard();
    }

    public static void clearAll() {
        for (List<Ghost> ghosts : ACTIVE.values()) {
            for (Ghost ghost : ghosts) ghost.entity().discard();
        }
        ACTIVE.clear();
    }

    private static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Map.Entry<UUID, List<Ghost>>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Ghost>> entry = it.next();
            PokemonEntity caster = serverLevel(server, entry.getKey());
            List<Ghost> ghosts = entry.getValue();
            boolean dead = false;
            for (int index = 0; index < ghosts.size(); index++) {
                Ghost ghost = ghosts.get(index);
                PokemonEntity entity = ghost.entity();
                long now = entity.level().getGameTime();
                if (entity.isRemoved() || now > ghost.until()) {
                    entity.discard();
                    dead = true;
                    continue;
                }
                LivingEntity victim = nearestVictim(entity, caster);
                if (victim != null) {
                    Vec3 towards = victim.position().subtract(entity.position());
                    Vec3 flat = new Vec3(towards.x, 0, towards.z);
                    if (flat.lengthSqr() > 1E-4) faceTowards(entity, flat.normalize());
                    // Close the gap, then hold at arm's length instead of drifting past.
                    if (flat.length() > 1.9) {
                        Vec3 step = flat.normalize().scale(SPEED);
                        entity.move(net.minecraft.world.entity.MoverType.SELF, step);
                        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
                        entity.setPos(entity.position().x, entity.getY(), entity.position().z);
                    }
                    if (now >= ghost.nextAttack()) {
                        strike(entity, victim, flat.lengthSqr() > 1E-4 ? flat.normalize() : ghost.dir());
                        ghost = new Ghost(entity, ghost.dir(), ghost.until(), ghost.travelled(), now + ATTACK_PERIOD);
                    }
                } else {
                    double travelled = ghost.travelled() + SPEED;
                    if (travelled <= 3.4) {
                        Vec3 next = entity.position().add(ghost.dir().scale(SPEED));
                        entity.move(net.minecraft.world.entity.MoverType.SELF, ghost.dir().scale(SPEED));
                        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
                        entity.setPos(next.x, entity.getY(), next.z);
                        faceTowards(entity, ghost.dir());
                        ghost = new Ghost(entity, ghost.dir(), ghost.until(), travelled, ghost.nextAttack());
                    }
                }
                ghosts.set(index, ghost);
            }
            if (dead) {
                for (Ghost ghost : ghosts) ghost.entity().discard();
                it.remove();
            } else if (caster == null) {
                // The summoner logged out or died: take the clones with them.
                for (Ghost ghost : ghosts) ghost.entity().discard();
                it.remove();
            }
        }
    }

    /** One Shadow Claw from a clone: server-authoritative damage plus the claw cue on that body. */
    private static void strike(PokemonEntity entity, LivingEntity victim, Vec3 dir) {
        if (entity.level() instanceof ServerLevel level) {
            com.pkmoba.combat.CombatDamage.apply(level, entity, victim, CLAW_DAMAGE,
                    com.pkmoba.combat.TypeEffectiveness.MoveType.GHOST);
            ExpansionCombat.illusionCue(entity, 0, "attack",
                    entity.position().add(0, .8, 0), dir);
        }
    }

    @Nullable
    private static LivingEntity nearestVictim(PokemonEntity entity, @Nullable PokemonEntity caster) {
        LivingEntity best = null;
        double bestDistance = ATTACK_REACH * ATTACK_REACH;
        for (net.minecraft.world.entity.Entity candidate : entity.level().getEntities(entity,
                entity.getBoundingBox().inflate(ATTACK_REACH))) {
            if (!(candidate instanceof LivingEntity living)) continue;
            if (!BasicAttack.validTarget(living, entity)) continue;
            // 0.133.7: validTarget only rejects the dead, removed entities and other illusions,
            // so a clone used to happily claw its own summoner or the player behind it.
            // Clones now only ever strike creatures that belong to someone else.
            if (!isHostile(caster, living, entity)) continue;
            double distance = entity.distanceToSqr(living);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /** True when {@code candidate} is a legitimate enemy of the caster's side. */
    private static boolean isHostile(@Nullable PokemonEntity caster, LivingEntity candidate, PokemonEntity self) {
        if (candidate == self) return false;
        if (caster != null && candidate == caster) return false;
        java.util.UUID mine = caster == null ? null : com.pkmoba.charizard.CharizardManager.getControlledOwner(caster);
        if (candidate instanceof net.minecraft.server.level.ServerPlayer player) {
            return mine == null || !mine.equals(player.getUUID());
        }
        if (candidate instanceof PokemonEntity other) {
            java.util.UUID theirs = com.pkmoba.charizard.CharizardManager.getControlledOwner(other);
            return mine == null || theirs == null || !mine.equals(theirs);
        }
        return true;
    }

    private static void faceTowards(PokemonEntity entity, Vec3 dir) {
        entity.setYRot((float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90F);
        entity.yRotO = entity.getYRot();
    }

    @Nullable
    private static PokemonEntity serverLevel(MinecraftServer server, UUID owner) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(owner) instanceof PokemonEntity pokemon) return pokemon;
        }
        return null;
    }
}

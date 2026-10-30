package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 0.135 Armarouge: real travelling fire bolts for the basic attack.
 *
 * <p>The design doc is explicit that the fire bolt must fly and collide instead of teleporting onto
 * the target, so the punch no longer applies damage directly for this species: it spawns a shot that
 * advances one block per tick, sweeps its own body box, and damages each victim at most once.
 * Everything runs on the server tick, which keeps damage server-authoritative and multiplayer safe.
 */
public final class BasicShots {
    private BasicShots() {
    }

    /** One flying bolt. {@code struck} is per shot, so three bolts can all hit the same body. */
    private static final class Shot {
        final ServerLevel level;
        final PokemonEntity owner;
        final Vec3 dir;
        final float damage;
        final double range;
        final int stage;
        Vec3 pos;
        double flown;
        boolean dead;
        final Set<UUID> struck = new HashSet<>();

        Shot(ServerLevel level, PokemonEntity owner, Vec3 pos, Vec3 dir, float damage, double range, int stage) {
            this.level = level;
            this.owner = owner;
            this.pos = pos;
            this.dir = dir;
            this.damage = damage;
            this.range = range;
            this.stage = stage;
        }
    }

    private static final List<Shot> ACTIVE = new ArrayList<>();

    public static void init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(BasicShots::tick);
    }

    /** Spawns one bolt from the caster's hand, flying along {@code dir}. */
    public static void spawn(ServerLevel level, PokemonEntity owner, Vec3 dir, float damage,
                             double range, int stage) {
        Vec3 start = owner.position().add(0, 1.05, 0).add(dir.scale(.65));
        ACTIVE.add(new Shot(level, owner, start, dir.normalize(), damage, range, stage));
        cue(level, "pkmoba:ars/" + stage + "/" + owner.getId(), start, dir);
    }

    private static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        for (Shot shot : new ArrayList<>(ACTIVE)) {
            if (shot.dead) continue;
            if (!shot.owner.isAlive() || shot.owner.isRemoved()
                    || shot.owner.level() != shot.level || shot.flown >= shot.range) {
                shot.dead = true;
                continue;
            }
            Vec3 intended = shot.pos.add(shot.dir.scale(1.0));
            Vec3 next = ExpansionGeometry.clipProjectile(shot.level, shot.owner, shot.pos, intended);
            LivingEntity hitTarget = null;
            for (LivingEntity target : ExpansionGeometry.swept(shot.level, shot.owner, shot.pos, next, .55)) {
                if (!shot.struck.add(target.getUUID())) continue;
                if (!CombatDamage.apply(shot.level, shot.owner, target, shot.damage)) continue;
                hitTarget = target;
                cue(shot.level, "pkmoba:ars/hit/" + shot.owner.getId(),
                        target.getBoundingBox().getCenter(), shot.dir);
            }
            shot.flown += shot.pos.distanceTo(next);
            shot.pos = next;
            cue(shot.level, "pkmoba:ars/" + shot.stage + "/" + shot.owner.getId(), next, shot.dir);
            if (hitTarget != null || next.distanceToSqr(intended) > 1E-4) shot.dead = true;
        }
        ACTIVE.removeIf(shot -> shot.dead || shot.owner.level() != shot.level);
    }

    private static void cue(ServerLevel level, String key, Vec3 pos, Vec3 dir) {
        EffectPayload payload = new EffectPayload(key, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) > 96 * 96) continue;
            if (ServerPlayNetworking.canSend(player, EffectPayload.TYPE)) ServerPlayNetworking.send(player, payload);
        }
    }
}

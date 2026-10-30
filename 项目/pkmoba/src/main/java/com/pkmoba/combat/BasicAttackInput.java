package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.network.BasicAttackPayload;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 0.93 left-click basic attack for the Pokémon the player controls.
 *
 * <p>The user asked for "靠近 3 格、必须自己点左键" on Lucario, so the punch is a real player input:
 * the client sends the crosshair pick, the server re-validates reach, line of sight and cooldown, then
 * runs the damage through the normal {@link CombatDamage} pipeline so buffs (Swords Dance) apply.
 */
public final class BasicAttackInput {
    private BasicAttackInput() {
    }

    /** Per-species punch numbers: reach, damage and cooldown in ticks. */
    private record Punch(float range, float damage, int cooldown, String clip) {
    }

    private static final java.util.Map<String, Punch> PUNCHES = java.util.Map.of(
            "lucario", new Punch(3.0F, 9.0F, 9, "w_melee"),
            "blaziken", new Punch(2.8F, 10.0F, 8, "w_melee"));

    /**
     * 0.132 Ceruledge: the twin blades swing in a three stage chain instead of one punch. Each hit
     * is its own damage instance (8 / 8 / 14), the chain keeps a 1.2 second window and the interval
     * between swings is three ticks, shortened while Swords Dance burns.
     */
    private record Chain(String[] clips, float[] damage, int window, int interval, float reach,
                         boolean projectile) {
    }

    private static final java.util.Map<String, Chain> CHAINS = java.util.Map.of(
            // 0.133.6: the baked swings run 0.26-0.38s, so the gap between hits is 5 ticks;
            // at 3 the next swing cut the previous one off mid-swing ("闪太快").
            "ceruledge", new Chain(new String[]{"q_swing_l", "q_swing_r", "q_cross"},
                    new float[]{8F, 8F, 14F}, 30, 5, 3.0F, false),
            // Hisuian Zoroark: left claw, right claw, then a two-claw ghost slam.
            // 0.133.6: slowed claws (0.42-0.55s) need a 6 tick gap or every swing is truncated.
            "hisuian_zoroark", new Chain(new String[]{"q_claw_l", "q_claw_r", "q_claw_cross"},
                    new float[]{10F, 10F, 15F}, 34, 6, 2.5F, false),
            // 0.134 Krookodile: left bite, counter-bite, then the full-jaw slam. 2.5 block reach.
            "krookodile", new Chain(new String[]{"q_bite_l", "q_bite_r", "q_bite_heavy"},
                    new float[]{10F, 10F, 15F}, 24, 3, 2.5F, false),
            // 0.135 Armarouge fires instead of swinging: 10 / 10 / 15 over 11 blocks, one tick
            // faster than the melee chains because a bolt travels before it lands.
            "armarouge", new Chain(new String[]{"q_fire_l", "q_fire_r", "q_fire_heavy"},
                    new float[]{10F, 10F, 15F}, 24, 4, 11.0F, true));

    private static final Map<UUID, Long> NEXT = new HashMap<>();
    /** Stage the player is on (1..3) plus the tick of the last swing, so the window can expire. */
    private static final Map<UUID, Integer> STAGE = new HashMap<>();
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private static int stage(ServerPlayer player, Chain chain, long now) {
        long last = LAST.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        int stage = STAGE.getOrDefault(player.getUUID(), 0);
        boolean chained = last != Long.MIN_VALUE && now - last <= chain.window();
        int next = !chained ? 1 : (stage % chain.damage().length) + 1;
        STAGE.put(player.getUUID(), next);
        LAST.put(player.getUUID(), now);
        return next;
    }

    public static void init() {
        BasicShots.init();
        ServerPlayNetworking.registerGlobalReceiver(BasicAttackPayload.TYPE, (payload, context) ->
                context.server().execute(() -> punch(context.player(), payload.targetId())));
    }

    private static void punch(ServerPlayer player, int targetId) {
        PokemonEntity mon = CharizardManager.getControlled(player);
        if (mon == null || !mon.isAlive() || mon.level() != player.level()) return;
        var species = ExpansionRoster.resolve(mon.getPokemon().getSpecies().getResourceIdentifier().toString());
        String id = species == null ? null : species.id();
        Punch punch = id == null ? null : PUNCHES.get(id);
        Chain chain = id == null ? null : CHAINS.get(id);
        if (punch == null && chain == null) return;
        if (chain != null && chain.projectile()) {
            long now = player.server.getTickCount();
            if (NEXT.getOrDefault(player.getUUID(), 0L) > now) return;
            int hit = stage(player, chain, now);
            float damage = chain.damage()[hit - 1] * (float) ExpansionControl.attackPower(mon);
            int cooldown = Math.max(1, (int) Math.round(chain.interval() * ExpansionControl.attackRate(mon)));
            NEXT.put(player.getUUID(), now + cooldown);
            Vec3 look = mon.getLookAngle().multiply(1, 0, 1);
            Vec3 dir = look.lengthSqr() < 1E-4 ? new Vec3(0, 0, 1) : look.normalize();
            BasicShots.spawn((ServerLevel) mon.level(), mon, dir, damage, chain.reach(), hit);
            Vec3 muzzle = mon.position().add(0, 1.05, 0).add(dir.scale(.8));
            EffectPayload fireCue = new EffectPayload("pkmoba:bap/" + id + hit + "/" + mon.getId(),
                    muzzle.x, muzzle.y, muzzle.z, dir.x, dir.y, dir.z);
            ServerLevel shotLevel = (ServerLevel) mon.level();
            for (ServerPlayer other : shotLevel.players()) {
                if (other.distanceToSqr(mon) > 96 * 96) continue;
                ServerPlayNetworking.send(other, fireCue);
            }
            return;
        }
        float reach = punch != null ? punch.range() : (chain != null ? chain.reach() : 3.0F);
        long now = player.server.getTickCount();
        if (NEXT.getOrDefault(player.getUUID(), 0L) > now) return;
        LivingEntity target = pick(mon, targetId, reach);
        if (target != null && (BasicAttack.boxDistanceSquared(mon.getBoundingBox(), target.getBoundingBox())
                > reach * reach || !BasicAttack.clearMeleeLine(mon, target))) {
            target = null;
        }
        String phase = "";
        float damage = 0F;
        int cooldown;
        if (chain == null) {
            damage = punch.damage();
            cooldown = punch.cooldown();
        } else {
            int hit = stage(player, chain, now);
            phase = Integer.toString(hit);
            damage = chain.damage()[hit - 1];
            cooldown = Math.max(1, (int) Math.round(chain.interval() * ExpansionControl.attackRate(mon)));
        }
        // Swords Dance raises every swing by a quarter.
        damage *= (float) ExpansionControl.attackPower(mon);
        ServerLevel level = (ServerLevel) mon.level();
        Vec3 at;
        Vec3 dir;
        if (target == null) {
            // 0.132b: swinging at thin air still animates and still advances the chain, otherwise the
            // blades look frozen whenever nothing is in reach.
            if (chain == null) return;
            Vec3 look = mon.getLookAngle();
            dir = new Vec3(look.x, 0, look.z);
            dir = dir.lengthSqr() < 1E-4 ? new Vec3(0, 0, 1) : dir.normalize();
            Vec3 chest = mon.position().add(0, 1.0, 0);
            at = chest.add(dir.scale(1.2));
        } else {
            if (!CombatDamage.apply(level, mon, target, damage)) return;
            at = target.getBoundingBox().getCenter();
            dir = at.subtract(mon.position()).multiply(1, 0, 1);
            dir = dir.lengthSqr() < 1E-4 ? mon.getLookAngle() : dir.normalize();
        }
        NEXT.put(player.getUUID(), now + cooldown);
        EffectPayload cue = new EffectPayload("pkmoba:bap/" + id + phase + "/" + mon.getId(),
                at.x, at.y, at.z, dir.x, dir.y, dir.z);
        for (ServerPlayer other : level.players()) {
            if (other.distanceToSqr(mon) > 96 * 96) continue;
            ServerPlayNetworking.send(other, cue);
        }
        if (target == null) return;
        target.push(dir.x * .22, .10, dir.z * .22);
    }

    private static LivingEntity pick(PokemonEntity mon, int targetId, float range) {
        if (targetId >= 0) {
            Entity picked = mon.level().getEntity(targetId);
            if (picked instanceof LivingEntity living && BasicAttack.validTarget(living, mon)) return living;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 look = mon.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1E-4) look = new Vec3(0, 0, 1);
        look = look.normalize();
        for (LivingEntity candidate : mon.level().getEntitiesOfClass(LivingEntity.class,
                mon.getBoundingBox().inflate(range), e -> BasicAttack.validTarget(e, mon))) {
            Vec3 offset = candidate.getBoundingBox().getCenter().subtract(mon.position()).multiply(1, 0, 1);
            if (offset.lengthSqr() < 1E-4) continue;
            if (offset.normalize().dot(look) < .25) continue;
            double distance = offset.length();
            if (distance >= bestDistance) continue;
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }
}

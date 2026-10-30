package com.pkmoba.combat;

import com.pkmoba.network.EffectPayload;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pushes the small "status badge" that floats over a marked victim (Blaziken's Flame Mark, Gengar's
 * hypnosis pips and sleep) to nearby clients.
 *
 * <p>Why a dedicated payload instead of a vanilla mob effect: the badge must appear over <b>every</b>
 * living entity, and Cobblemon's PokemonEntity does not reliably forward vanilla effects, so the
 * client cannot trust {@code hasEffect}. This sends the badge state directly, with its own timer, and
 * the client draws it in the HUD layer - the only layer that is guaranteed to be visible on top.
 *
 * <p>Wire format reuses {@link EffectPayload}: id = {@code pkmoba:badge/<kind>/<count>/<ticks>},
 * x = target entity id packed as a double. No new packet type is registered.
 */
public final class StatusBadges {
    public static final String PREFIX = "pkmoba:badge/";

    private StatusBadges() {
    }

    public static void send(LivingEntity target, String kind, int count, int ticks) {
        if (!(target.level() instanceof ServerLevel level)) return;
        String id = PREFIX + kind + "/" + count + "/" + Math.max(0, ticks);
        var payload = new EffectPayload(id, target.getId(), 0, 0, 0, 0, 0);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(target) < 128 * 128
                    && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                    player, EffectPayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public static void clear(LivingEntity target) {
        send(target, "clear", 0, 0);
    }
}

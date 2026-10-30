package com.pkmoba.combat;

import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 0.133 Hisuian Zoroark: 怨念印记 (malice marks).
 *
 * <p>Q/W/E hits stamp one stack on the victim, three at most, five seconds each, refreshed by the
 * next hit. Every stack is +4% skill damage from that caster to that victim, so a fully marked
 * target takes 12% more. The stack count lives on the server only; the client is told a
 * (target, stacks) pair and draws the sigil, so it can never invent a stack of its own.
 */
public final class Malice {
    private static final int MAX_STACKS = 3;
    private static final int LIFE = 100;
    private static final int SYNC_RANGE = 64;

    private record Hold(UUID owner, int stacks, long until) {
    }

    private static final Map<UUID, Hold> STACKS = new HashMap<>();

    private Malice() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Malice::tick);
    }

    /** Stamps one stack (never more than three), refreshes the timer, syncs the count. */
    public static void mark(ServerLevel level, LivingEntity owner, LivingEntity target) {
        if (level == null || owner == null || target == null || owner == target) return;
        Hold hold = STACKS.get(target.getUUID());
        if (hold != null && hold.until() <= level.getGameTime()) hold = null;
        int stacks = Math.min(MAX_STACKS, hold == null ? 1 : hold.stacks() + 1);
        STACKS.put(target.getUUID(), new Hold(owner.getUUID(), stacks, level.getGameTime() + LIFE));
        broadcast(level, target, stacks);
    }

    /** Damage multiplier the caster enjoys against that target: 1.00 / 1.04 / 1.08 / 1.12. */
    public static double multiplier(LivingEntity owner, LivingEntity target) {
        if (owner == null || target == null) return 1.0;
        Hold hold = STACKS.get(target.getUUID());
        if (hold == null || !hold.owner().equals(owner.getUUID())) return 1.0;
        if (hold.until() <= target.level().getGameTime()) return 1.0;
        return 1.0 + 0.04 * hold.stacks();
    }

    public static void clear() {
        STACKS.clear();
    }

    public static void clear(UUID target) {
        STACKS.remove(target);
    }

    private static void tick(MinecraftServer server) {
        if (STACKS.isEmpty()) return;
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, Hold>> it = STACKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hold> entry = it.next();
            Hold hold = entry.getValue();
            if (hold.until() > now) continue;
            it.remove();
            // The timer ran out: tell every nearby client to fade the sigil instead of leaving a
            // ghost ring hovering over a target that no longer carries anything.
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(entry.getKey()) instanceof LivingEntity target) {
                    broadcast(level, target, 0);
                }
            }
        }
    }

    private static void broadcast(ServerLevel level, LivingEntity target, int stacks) {
        Vec3 at = target.getBoundingBox().getCenter();
        EffectPayload payload = new EffectPayload("pkmoba:mark/" + target.getId() + "/" + stacks,
                at.x, at.y, at.z, 0, 0, 0);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) > (double) SYNC_RANGE * SYNC_RANGE) continue;
            ServerPlayNetworking.send(player, payload);
        }
    }
}

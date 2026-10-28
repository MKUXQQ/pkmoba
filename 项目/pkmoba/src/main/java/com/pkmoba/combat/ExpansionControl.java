package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.entity.LivingEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Source-owned, bounded control. Damage wakes sleep; removal never clears another caster's effect. */
public final class ExpansionControl {
    private record Sleep(UUID source, long until, float healthAtStart) {}
    private record Slow(UUID source, long until, double factor) {}
    private static final Map<UUID, Sleep> SLEEP = new HashMap<>();
    private static final Map<UUID, Slow> SLOW = new HashMap<>();
    private static final Map<UUID, Long> IMMUNITY = new HashMap<>();
    private ExpansionControl() {}
    public static boolean sleep(PokemonEntity source, LivingEntity target, int ticks) {
        long now = target.level().getGameTime();
        IMMUNITY.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (ticks <= 0 || IMMUNITY.getOrDefault(target.getUUID(), 0L) > now) return false;
        int bounded = Math.min(120, ticks);
        SLEEP.put(target.getUUID(), new Sleep(source.getUUID(), now + bounded, target.getHealth()));
        IMMUNITY.put(target.getUUID(), now + bounded + 60);
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (target instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
        return true;
    }
    public static boolean sleeping(LivingEntity target) {
        Sleep value = SLEEP.get(target.getUUID());
        if (value == null) return false;
        if (!target.isAlive() || value.until <= target.level().getGameTime()
                || target.getHealth() + .001F < value.healthAtStart) {
            SLEEP.remove(target.getUUID());
            return false;
        }
        return true;
    }
    public static void wake(LivingEntity target) { SLEEP.remove(target.getUUID()); }
    public static void slow(PokemonEntity source, LivingEntity target, int ticks, double factor) {
        if (ticks <= 0 || !Double.isFinite(factor)) return;
        SLOW.put(target.getUUID(), new Slow(source.getUUID(), target.level().getGameTime() + Math.min(ticks, 100),
                Math.max(.35, Math.min(1, factor))));
    }
    public static double movementMultiplier(LivingEntity target) {
        if (sleeping(target)) return 0;
        Slow value = SLOW.get(target.getUUID());
        if (value == null) return 1;
        if (!target.isAlive() || value.until <= target.level().getGameTime()) {
            SLOW.remove(target.getUUID());
            return 1;
        }
        return value.factor;
    }
    public static void removeSource(UUID source) {
        SLEEP.entrySet().removeIf(e -> e.getValue().source.equals(source));
        SLOW.entrySet().removeIf(e -> e.getValue().source.equals(source));
    }
    public static void removeEntity(UUID entity) {
        removeSource(entity);
        SLEEP.remove(entity);
        SLOW.remove(entity);
        IMMUNITY.remove(entity);
    }
    public static void resetAll() { SLEEP.clear(); SLOW.clear(); IMMUNITY.clear(); }
}

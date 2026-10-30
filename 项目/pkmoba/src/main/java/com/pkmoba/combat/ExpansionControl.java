package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/** Source-owned, bounded control. Damage wakes sleep; removal never clears another caster's effect. */
public final class ExpansionControl {
    private record Sleep(UUID source, long until, float healthAtStart) {}
    private record Slow(UUID source, long until, double factor) {}
    /** 0.130 self sprint: a bounded movement bonus that only ever speeds its own caster up. */
    private record Haste(UUID source, long until, double factor) {}
    /** Persistent hypnosis stacks: three marks on the same victim put it to sleep. */
    private record Mark(UUID source, int count, long until) {}
    /** 0.87 timed attack buff (Swords Dance) and damage guard (Aurora Veil). */
    private record Buff(long until, double power, double guard) {}
    private static final Map<UUID, Mark> HYPNO = new HashMap<>();
    private static final Map<UUID, Sleep> SLEEP = new HashMap<>();
    private static final Map<UUID, Slow> SLOW = new HashMap<>();
    private static final Map<UUID, Haste> HASTE = new HashMap<>();
    private static final Map<UUID, Long> IMMUNITY = new HashMap<>();
    private static final Map<UUID, Buff> BUFF = new HashMap<>();
    /** 0.132 Ceruledge 剑舞: a timed self buff that raises attacks and shortens the swing. */
    private record Dance(long until) {}
    private static final Map<UUID, Dance> DANCE = new HashMap<>();
    private ExpansionControl() {}
    public static boolean sleep(PokemonEntity source, LivingEntity target, int ticks) {
        long now = target.level().getGameTime();
        IMMUNITY.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (ticks <= 0 || IMMUNITY.getOrDefault(target.getUUID(), 0L) > now) return false;
        int bounded = Math.min(120, ticks);
        SLEEP.put(target.getUUID(), new Sleep(source.getUUID(), now + bounded, target.getHealth()));
        HYPNO.remove(target.getUUID());
        // Invisible marker effect: clients read it to draw the hypnotised icon over the victim's
        // head. UNLUCK has no gameplay effect, so this is purely a synchronised status flag.
        // Amplifier 9 means "asleep"; 0..2 means 1..3 hypnosis marks.
        target.removeEffect(MobEffects.UNLUCK);
        target.addEffect(new MobEffectInstance(MobEffects.UNLUCK, bounded, 9, false, false, false));
        // Push the badge as its own packet: Cobblemon Pokemon do not reliably forward vanilla
        // effects to the client, which is why the world-space badge never appeared.
        // Badge outlives the control by design: the mark stays on screen for thirty seconds.
        com.pkmoba.combat.StatusBadges.send(target, "sleep", 0, Math.max(bounded, 600));
        IMMUNITY.put(target.getUUID(), now + bounded + 60);
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (target instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
        return true;
    }
    /**
     * Adds one hypnosis mark and returns how many the victim currently carries. Marks persist
     * between casts (600 ticks) so three separate sprays put the target to sleep.
     */
    public static int addHypnoMark(PokemonEntity source, LivingEntity target, int ticks) {
        long now = target.level().getGameTime();
        HYPNO.entrySet().removeIf(entry -> entry.getValue().until <= now);
        Mark old = HYPNO.get(target.getUUID());
        int count = (old == null ? 0 : old.count) + 1;
        int bounded = Math.max(20, Math.min(600, ticks));
        HYPNO.put(target.getUUID(), new Mark(source.getUUID(), count, now + bounded));
        if (sleeping(target)) return count;
        target.removeEffect(MobEffects.UNLUCK);
        target.addEffect(new MobEffectInstance(MobEffects.UNLUCK, bounded, Math.min(2, count - 1),
                false, false, false));
        com.pkmoba.combat.StatusBadges.send(target, "pip", count, Math.max(bounded, 600));
        return count;
    }

    public static int hypnoMarks(LivingEntity target) {
        Mark mark = HYPNO.get(target.getUUID());
        if (mark == null || mark.until <= target.level().getGameTime()) {
            HYPNO.remove(target.getUUID());
            return 0;
        }
        return mark.count;
    }

    public static void clearHypno(LivingEntity target) {
        HYPNO.remove(target.getUUID());
        target.removeEffect(MobEffects.UNLUCK);
    }

    /** 0.101: the third hypnosis mark must always land, even inside the sleep immunity window. */
    public static boolean sleepForce(PokemonEntity source, LivingEntity target, int ticks) {
        if (source == null || target == null || ticks <= 0) return false;
        IMMUNITY.remove(target.getUUID());
        return sleep(source, target, ticks);
    }

    public static boolean sleeping(LivingEntity target) {
        Sleep value = SLEEP.get(target.getUUID());
        if (value == null) return false;
        if (!target.isAlive() || value.until <= target.level().getGameTime()
                || target.getHealth() + .001F < value.healthAtStart) {
            SLEEP.remove(target.getUUID());
            target.removeEffect(MobEffects.UNLUCK);
            return false;
        }
        return true;
    }
    public static void wake(LivingEntity target) {
        com.pkmoba.combat.StatusBadges.clear(target);
        SLEEP.remove(target.getUUID());
        target.removeEffect(MobEffects.UNLUCK);
    }
    /** Raises the victim's outgoing damage for a while (multiplicative, refreshes on recast). */
    public static void empower(PokemonEntity source, int ticks, double power) {
        if (source == null || ticks <= 0) return;
        long now = source.level().getGameTime();
        Buff old = BUFF.get(source.getUUID());
        double guard = old != null && old.until > now ? old.guard : 1.0;
        BUFF.put(source.getUUID(), new Buff(now + ticks, Math.max(1, power), guard));
    }

    /** Softens incoming damage for a while (Aurora Veil). */
    public static void guard(PokemonEntity source, int ticks, double reduction) {
        if (source == null || ticks <= 0) return;
        long now = source.level().getGameTime();
        Buff old = BUFF.get(source.getUUID());
        double power = old != null && old.until > now ? old.power : 1.0;
        BUFF.put(source.getUUID(), new Buff(now + ticks, power, 1.0 - Math.min(.8, Math.max(0, reduction))));
    }

    /**
     * 0.134 Crunch: the victim's guard breaks, so everything hits it 10% harder for a while.
     * The effect never stacks - a second bite only refreshes the timer and the amount.
     */
    public static void defenseDown(LivingEntity target, int ticks, double amount) {
        if (target == null || ticks <= 0) return;
        long now = target.level().getGameTime();
        Buff old = BUFF.get(target.getUUID());
        double power = old != null && old.until > now ? old.power : 1.0;
        BUFF.put(target.getUUID(), new Buff(now + ticks, power, Math.min(1.5, 1.0 + Math.max(0, amount))));
    }

    public static double powerMultiplier(LivingEntity entity) {
        if (entity == null) return 1.0;
        Buff buff = BUFF.get(entity.getUUID());
        if (buff == null) return 1.0;
        if (buff.until <= entity.level().getGameTime()) {
            BUFF.remove(entity.getUUID());
            return 1.0;
        }
        return buff.power;
    }

    public static double damageTakenMultiplier(LivingEntity entity) {
        if (entity == null) return 1.0;
        Buff buff = BUFF.get(entity.getUUID());
        if (buff == null) return 1.0;
        if (buff.until <= entity.level().getGameTime()) {
            BUFF.remove(entity.getUUID());
            return 1.0;
        }
        return buff.guard;
    }

    /** Swords Dance: never stacks, never outlives its own timer, cleared on death or expiry. */
    public static void dance(PokemonEntity source, int ticks) {
        if (source == null || ticks <= 0) return;
        DANCE.put(source.getUUID(), new Dance(source.level().getGameTime() + ticks));
    }

    public static boolean dancing(LivingEntity entity) {
        if (entity == null) return false;
        Dance value = DANCE.get(entity.getUUID());
        if (value == null) return false;
        if (!entity.isAlive() || value.until <= entity.level().getGameTime()) {
            DANCE.remove(entity.getUUID());
            return false;
        }
        return true;
    }

    /** Basic attacks gain 25% while the blades burn. */
    public static double attackPower(LivingEntity entity) {
        return dancing(entity) ? 1.25 : 1.0;
    }

    /** Q, W and E gain 20%. */
    public static double skillPower(LivingEntity entity) {
        return dancing(entity) ? 1.20 : 1.0;
    }

    /** Cooldown scale of the three-hit chain: 20% shorter between swings. */
    public static double attackRate(LivingEntity entity) {
        return dancing(entity) ? 0.8 : 1.0;
    }

    public static void slow(PokemonEntity source, LivingEntity target, int ticks, double factor) {
        if (ticks <= 0 || !Double.isFinite(factor)) return;
        SLOW.put(target.getUUID(), new Slow(source.getUUID(), target.level().getGameTime() + Math.min(ticks, 100),
                Math.max(.35, Math.min(1, factor))));
    }
    public static void haste(PokemonEntity source, int ticks, double factor) {
        if (ticks <= 0 || !Double.isFinite(factor)) return;
        HASTE.put(source.getUUID(), new Haste(source.getUUID(),
                source.level().getGameTime() + Math.min(ticks, 100),
                Math.max(1, Math.min(1.6, factor))));
    }

    public static double hasteMultiplier(LivingEntity target) {
        Haste value = HASTE.get(target.getUUID());
        if (value == null) return 1;
        if (!target.isAlive() || value.until <= target.level().getGameTime()) {
            HASTE.remove(target.getUUID());
            return 1;
        }
        return value.factor;
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
        HASTE.entrySet().removeIf(e -> e.getValue().source.equals(source));
    }
    public static void removeEntity(UUID entity) {
        BUFF.remove(entity);
        removeSource(entity);
        SLEEP.remove(entity);
        SLOW.remove(entity);
        HASTE.remove(entity);
        DANCE.remove(entity);
        IMMUNITY.remove(entity);
        HYPNO.remove(entity);
    }
    public static void resetAll() { SLEEP.clear(); SLOW.clear(); HASTE.clear(); IMMUNITY.clear(); HYPNO.clear(); DANCE.clear(); }
}

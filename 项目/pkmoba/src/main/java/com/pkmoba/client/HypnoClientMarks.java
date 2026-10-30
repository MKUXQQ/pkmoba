package com.pkmoba.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side mirror of Gengar's hypnosis marks.
 *
 * <p>The authoritative state lives on the server as an invisible {@code UNLUCK} effect, but that
 * only helps if the effect packet arrives before the client renders. The server also fires a
 * {@code mark1/2/3} (and {@code sleep}) cue at the victim's position on every hit, so those cues
 * are used here as a second, independent source: whichever arrives first is what the badge uses.
 */
public final class HypnoClientMarks {
    private static final int MARK_TICKS = 200;
    private static final int SLEEP_TICKS = 110;

    private record Mark(int marks, long until) {
    }

    private static final Map<Integer, Mark> MARKS = new HashMap<>();
    private static final Map<Integer, Long> ASLEEP = new HashMap<>();

    private HypnoClientMarks() {
    }

    /** Called from the expansion cue handler with the position the server stamped the mark at. */
    public static void apply(Level level, Vec3 at, int marks, boolean sleep) {
        if (level == null || at == null) {
            return;
        }
        long now = level.getGameTime();
        AABB box = new AABB(at, at).inflate(1.8);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distance = candidate.getBoundingBox().getCenter().distanceToSqr(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null) {
            return;
        }
        int id = best.getId();
        if (sleep) {
            ASLEEP.put(id, now + SLEEP_TICKS);
            MARKS.remove(id);
        } else {
            MARKS.put(id, new Mark(Math.min(3, Math.max(1, marks)), now + MARK_TICKS));
        }
    }

    public static void clearAll() {
        MARKS.clear();
        ASLEEP.clear();
    }

    /** Marks visible for this entity right now (0..3). */
    public static int marksOf(LivingEntity entity) {
        var mark = MARKS.get(entity.getId());
        if (mark == null) {
            return 0;
        }
        if (entity.level().getGameTime() > mark.until()) {
            MARKS.remove(entity.getId());
            return 0;
        }
        return mark.marks();
    }

    public static boolean asleepOf(LivingEntity entity) {
        Long until = ASLEEP.get(entity.getId());
        if (until == null) {
            return false;
        }
        if (entity.level().getGameTime() > until) {
            ASLEEP.remove(entity.getId());
            return false;
        }
        return true;
    }
}

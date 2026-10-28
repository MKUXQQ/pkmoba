package com.pkmoba.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Cosmetic anchors for the eight expansion species: hands, feet, mouth and chest in world space.
 *
 * <p>Why a dedicated class instead of {@link MouthLocator}: that helper only knows the mouth and it
 * validates with a mouth-specific height band. Expansion skills need "which hand throws this" and
 * "which foot kicks that", and two of the eight models ship <b>no</b> hand/foot locators at all
 * ({@code blaziken}, {@code gardevoir}). So the rule here is:
 * <ol>
 *   <li>ask Cobblemon for the live locator bone (follows the running animation, so a throwing arm
 *       really throws from the arm);</li>
 *   <li>if the model has no such locator, or the value looks unreasonable, use a body-relative
 *       offset derived from the bounding box and the entity yaw.</li>
 * </ol>
 * Nothing here ever throws at the caller: a bad locator degrades to the body-relative estimate,
 * never to missing effects. All offsets are cosmetic only and never feed damage or aim.
 */
public final class ExpansionAnchors {

    /** Which body part a skill casts from. */
    public enum Slot { MOUTH, HAND_PRIMARY, HAND_SECONDARY, FOOT_PRIMARY, FOOT_SECONDARY, CHEST, HEAD, TARGET }

    /** A resolved anchor set. Never null; every field is a finite world position. */
    public record Set(Vec3 mouth, Vec3 handPrimary, Vec3 handSecondary,
                      Vec3 footPrimary, Vec3 footSecondary, Vec3 chest, Vec3 head) {

        /** Body centre the first-pass visuals orbit around; same point as {@link #chest()}. */
        public Vec3 position() {
            return chest;
        }

        public Vec3 get(Slot slot) {
            return switch (slot) {
                case MOUTH -> mouth;
                case HAND_PRIMARY -> handPrimary;
                case HAND_SECONDARY -> handSecondary;
                case FOOT_PRIMARY -> footPrimary;
                case FOOT_SECONDARY -> footSecondary;
                case CHEST -> chest;
                case HEAD, TARGET -> head;
            };
        }

        /** Mirrors the set to the other arm/leg, used by two-handed skills. */
        public Set lean(double forward) {
            Vec3 d = new Vec3(forward, 0, 0);
            return new Set(mouth.add(d), handPrimary.add(d), handSecondary.add(d),
                    footPrimary, footSecondary, chest.add(d), head.add(d));
        }
    }

    /** Locator names shipped by the eight models (some use the bare name, some a "locator_" prefix). */
    private static final String[] MOUTH = {"mouth", "locator_mouth", "internal_locator__mouth", "jaw_upper", "muzzle"};
    private static final String[] HAND_PRIMARY = {"hand_primary", "locator_hand_primary", "internal_locator__hand_primary", "physical"};
    private static final String[] HAND_SECONDARY = {"hand_secondary", "locator_hand_secondary", "internal_locator__hand_secondary"};
    private static final String[] FOOT_PRIMARY = {"foot_primary", "locator_foot_primary", "internal_locator__foot_primary"};
    private static final String[] FOOT_SECONDARY = {"foot_secondary", "locator_foot_secondary", "internal_locator__foot_secondary"};
    private static final String[] HEAD = {"head", "locator_head", "internal_locator__head", "top"};
    private static final String[] CHEST = {"middle", "locator_middle", "internal_locator__middle", "thorax"};

    /** Sanity bands: a locator must sit near the body or it is not trusted. */
    private static final double MAX_LOCATOR_DISTANCE_SQR = 10.0 * 10.0;
    private static final double MIN_ABOVE_FEET = -1.0;
    private static final double MAX_ABOVE_FEET = 6.5;

    private ExpansionAnchors() {
    }

    /** Resolve every anchor for this entity. Cheap enough for per-frame use, never allocates entities. */
    public static Set of(Entity entity) {
        if (!(entity instanceof PokemonEntity pokemon)) {
            return bodyOnly(entity);
        }
        double height = Math.max(0.5, pokemon.getBbHeight());
        double width = Math.max(0.4, pokemon.getBbWidth());
        float yaw = pokemon.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 base = pokemon.position();

        Vec3 mouth = locator(pokemon, MOUTH);
        if (mouth == null) {
            mouth = base.add(0, height * 0.82, 0).add(forward.scale(width * 0.20));
        }
        Vec3 head = locator(pokemon, HEAD);
        if (head == null) {
            head = base.add(0, height * 0.90, 0);
        }
        Vec3 handPrimary = locator(pokemon, HAND_PRIMARY);
        if (handPrimary == null) {
            handPrimary = base.add(0, height * 0.55, 0)
                    .add(forward.scale(width * 0.18))
                    .add(right.scale(width * 0.30));
        }
        Vec3 handSecondary = locator(pokemon, HAND_SECONDARY);
        if (handSecondary == null) {
            handSecondary = base.add(0, height * 0.55, 0)
                    .add(forward.scale(width * 0.18))
                    .add(right.scale(-width * 0.30));
        }
        Vec3 footPrimary = locator(pokemon, FOOT_PRIMARY);
        if (footPrimary == null) {
            footPrimary = base.add(0, height * 0.06, 0).add(right.scale(width * 0.20));
        }
        Vec3 footSecondary = locator(pokemon, FOOT_SECONDARY);
        if (footSecondary == null) {
            footSecondary = base.add(0, height * 0.06, 0).add(right.scale(-width * 0.20));
        }
        Vec3 chest = locator(pokemon, CHEST);
        if (chest == null) {
            chest = base.add(0, height * 0.60, 0);
        }
        return new Set(finiteOr(mouth, base), finiteOr(handPrimary, base), finiteOr(handSecondary, base),
                finiteOr(footPrimary, base), finiteOr(footSecondary, base), finiteOr(chest, base), finiteOr(head, base));
    }

    /** Non-Pokemon entities only get a body estimate; expansion skills should never end up here. */
    private static Set bodyOnly(Entity entity) {
        Vec3 base = entity.position();
        double height = Math.max(0.5, entity.getBbHeight());
        Vec3 forward = Vec3.directionFromRotation(0.0F, entity.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 chest = base.add(0, height * 0.60, 0);
        return new Set(base.add(0, height * 0.82, 0).add(forward.scale(0.2)),
                chest.add(right.scale(0.3)).add(forward.scale(0.2)),
                chest.add(right.scale(-0.3)).add(forward.scale(0.2)),
                base.add(0, height * 0.06, 0).add(right.scale(0.2)),
                base.add(0, height * 0.06, 0).add(right.scale(-0.2)),
                chest,
                base.add(0, height * 0.90, 0));
    }

    /** Try every candidate key; returns null when the model simply has no usable locator. */
    private static Vec3 locator(PokemonEntity entity, String[] keys) {
        try {
            if (!(entity.getDelegate() instanceof PokemonClientDelegate delegate)) {
                return null;
            }
            var states = delegate.getLocatorStates();
            if (states == null || states.isEmpty()) {
                return null;
            }
            for (String key : keys) {
                var state = states.get(key);
                if (state == null) {
                    continue;
                }
                Vec3 origin = state.getOrigin();
                if (plausible(entity, origin)) {
                    return origin;
                }
            }
        } catch (RuntimeException ignored) {
            // Any model/delegate mismatch degrades to the body-relative estimate.
        }
        return null;
    }

    private static boolean plausible(PokemonEntity entity, Vec3 origin) {
        if (origin == null || !finite(origin)) {
            return false;
        }
        double up = origin.y - entity.getY();
        if (up < MIN_ABOVE_FEET || up > MAX_ABOVE_FEET) {
            return false;
        }
        return origin.distanceToSqr(entity.position()) <= MAX_LOCATOR_DISTANCE_SQR;
    }

    private static Vec3 finiteOr(Vec3 value, Vec3 fallback) {
        return value != null && finite(value) ? value : fallback;
    }

    public static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}

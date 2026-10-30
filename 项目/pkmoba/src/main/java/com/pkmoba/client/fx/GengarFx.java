package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionSkillMeshes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Gengar v4: solid textured shapes only.
 *
 * <p>The old kit was made of trails, connector streams and vanilla particle smoke, which read as
 * "still the old skill" no matter how the sprite changed. This version draws no particles, no beams
 * and no connecting chains: every phase is one clean pixel object - an orb, a shock ring, a ground
 * splat, a hypnosis bullseye, a crescent moon or a vortex disc - at most three of them per phase.
 *
 * <p>Server phases: {@code start / projectile / lob / impact / melee}, {@code ray1..3},
 * {@code lit1..3}, {@code sleep}, {@code blink} ({@code pos} = start, {@code dir} = arrival) and
 * {@code heal} ({@code pos} = victim, {@code dir} = Gengar).
 */
public final class GengarFx {
    private GengarFx() {
    }

    private static final ResourceLocation ORB = tex("gengar_orb");
    private static final ResourceLocation RING = tex("gengar_ring");
    private static final ResourceLocation STAR = tex("gengar_star");
    private static final ResourceLocation SPLAT = tex("gengar_splat");
    private static final ResourceLocation HYPNO = tex("gengar_hypno");
    private static final ResourceLocation MOON = tex("gengar_moon");
    private static final ResourceLocation SWIRL = tex("gengar_swirl");

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    public static void render(FxView v) {
        WorldRenderContext c = v.fx.context();
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> shadowBall(c, v, a);
            case 1 -> sludgeBomb(c, v, a);
            case 2 -> hypnosis(c, v, a);
            case 3 -> dreamEater(c, v, a);
            default -> { }
        }
    }

    // ---- Q 暗影球: orb, then ring, then one flash ------------------------------------------

    private static void shadowBall(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            Vec3 hand = v.hand();
            double size = .9 + 1.1 * v.progress;
            sheet(c, ORB, hand, size, size, v.time * .8, a);
            double ringSize = 1.8 - .9 * v.progress;
            sheet(c, RING, hand.add(0, .05, 0), ringSize, ringSize, v.time * 1.2, a * .7f);
        } else if (v.in("projectile", "lob")) {
            sheet(c, ORB, v.pos, 1.45, 1.45, v.time * 1.4, a);
            sheet(c, RING, v.pos, 1.9 + .25 * Math.sin(v.time * 6), 1.9 + .25 * Math.sin(v.time * 6),
                    -v.time * 2.2, a * .55f);
        } else if (v.in("impact", "melee")) {
            double grow = 1.4 + 2.4 * v.progress;
            sheet(c, RING, v.pos, grow, grow, v.time * .8, a * .95f);
            double flash = 1.0 + 1.2 * (1 - v.progress);
            sheet(c, STAR, v.pos, flash, flash, v.time, a);
        }
    }

    // ---- W 污泥炸弹: lobbed orb, then splat + solid dome + ground ring ----------------------

    private static void sludgeBomb(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            Vec3 hand = v.hand();
            sheet(c, ORB, hand, 1.1 + .5 * v.progress, 1.1 + .5 * v.progress, -v.time * .6, a);
        } else if (v.in("projectile", "lob")) {
            sheet(c, ORB, v.pos, 1.3, 1.3, v.time * .9, a);
        } else if (v.in("impact", "melee")) {
            Vec3 ground = v.pos.subtract(0, .45, 0);
            double spread = 2.6 + 2.2 * v.progress;
            horizontal(c, SPLAT, ground, spread, a);
            double dome = .9 + 1.1 * v.progress;
            ExpansionSkillMeshes.sphere(c, ground.add(0, .05, 0), new Vec3(dome, dome * .55, dome),
                    v.time * .4, SPLAT, a * .85f, true);
            double ring = 1.8 + 3.4 * v.progress;
            horizontal(c, RING, ground.add(0, .06, 0), ring, a * .8f);
        }
    }

    // ---- E 催眠术: bullseye rings, no beam ---------------------------------------------------

    private static void hypnosis(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            Vec3 hand = v.hand();
            double size = 1.5 + .7 * v.progress;
            sheet(c, HYPNO, hand, size, size, v.time * 1.3, a);
        } else if (v.is("stream")) {
            // Flamethrower-style stream: five hypnotic rings sliding from the open hand to the aim
            // point, each one a little bigger, so the skill reads as a continuous spray cone.
            Vec3 from = v.hand();
            double length = Math.min(1, from.distanceTo(v.pos) / 12.0) + .001;
            for (int i = 0; i < 5; i++) {
                double f = (v.time * .55 + i * .2) % 1.0;
                Vec3 at = from.lerp(v.pos, .15 + .85 * f);
                double size = 1.1 + 1.5 * f;
                sheet(c, HYPNO, at, size, size, (v.time * 1.4 + i) * (i % 2 == 0 ? 1 : -1),
                        a * (.9f - .1f * i) * (1f - (float) (length * .2)));
            }
            for (int i = 0; i < 2; i++) {
                double f = (v.time * .4 + i * .5) % 1.0;
                Vec3 at = from.lerp(v.pos, .2 + .8 * f);
                sheet(c, RING, at, 1.5 + 2.2 * f, 1.5 + 2.2 * f, -v.time * 2.0 - i, a * .4f);
            }
        } else if (v.phase.startsWith("ray")) {
            int layer = Math.max(1, Math.min(3, v.phase.charAt(3) - '0'));
            for (int i = 0; i < layer; i++) {
                double f = .35 + .22 * i;
                Vec3 at = v.hand().lerp(v.pos, f);
                double size = 1.5 + .45 * i;
                sheet(c, HYPNO, at, size, size, v.time * 1.5 + i, a * (.9f - .12f * i));
            }
        } else if (v.phase.startsWith("lit")) {
            int lit = Math.max(1, Math.min(3, v.phase.charAt(3) - '0'));
            Vec3 head = v.pos.add(0, 1.05, 0);
            sheet(c, RING, head, 1.6 + .2 * lit, 1.6 + .2 * lit, -v.time * 1.1, a * .7f);
            for (int i = 0; i < lit; i++) {
                double size = 1.9 + .35 * lit - .3 * i;
                sheet(c, HYPNO, head.add(0, .06 * i, 0), size, size, v.time * (1.6 + .3 * i),
                        a * (.95f - .14f * i));
            }
        } else if (v.phase.startsWith("mark")) {
            // One flash per mark: the count itself is drawn persistently by HypnoStatusIcon, this is
            // the "stamp" that lands on the victim so the player notices the stack going up.
            int marks = Math.max(1, Math.min(3, v.phase.charAt(4) - '0'));
            Vec3 head = v.pos.add(0, 1.05, 0);
            sheet(c, HYPNO, head, 1.5 + .25 * marks, 1.5 + .25 * marks, v.time * 1.6, a * .95f);
            sheet(c, RING, head, 2.0 + .2 * marks, 2.0 + .2 * marks, -v.time * 1.2, a * .7f);
            for (int i = 0; i < marks; i++) {
                double offset = (i - (marks - 1) / 2.0) * .55;
                sheet(c, STAR, head.add(offset, -.9, 0), .55, .55, v.time + i, a);
            }
        } else if (v.is("sleep")) {
            Vec3 head = v.pos.add(0, 1.1, 0);
            sheet(c, HYPNO, head, 2.4, 2.4, v.time * 1.5, a * .95f);
            sheet(c, RING, head, 2.2 + .12 * Math.sin(v.time * 4), 2.2 + .12 * Math.sin(v.time * 4),
                    v.time * .9, a * .6f);
        } else if (v.is("impact")) {
            sheet(c, HYPNO, v.pos.add(0, 1.05, 0), 2.2, 2.2, v.time, a * .95f);
            sheet(c, RING, v.pos.add(0, 1.05, 0), 2.6, 2.6, -v.time, a * .75f);
        }
    }

    // ---- R 食梦: crescent moon, vortex disc, no connector ------------------------------------

    private static void dreamEater(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            Vec3 chest = v.chest().add(0, .25, 0);
            double size = 1.4 + 1.0 * v.progress;
            sheet(c, MOON, chest, size, size, v.time * .9, a);
            sheet(c, RING, chest, size * 1.5, size * 1.5, -v.time * .7, a * .55f);
        } else if (v.is("blink")) {
            // Arrival only: a moon and one ring where Gengar lands, a swirl left where he was.
            sheet(c, MOON, v.dir.add(0, .9, 0), 1.8, 1.8, -v.time, a * .85f);
            sheet(c, RING, v.dir.add(0, .6, 0), 2.4 + .5 * v.progress, 2.4 + .5 * v.progress,
                    v.time * .8, a * .8f);
            sheet(c, SWIRL, v.pos.add(0, .9, 0), 1.5, 1.5, v.time, a * .55f);
        } else if (v.in("melee", "impact")) {
            // A big crescent sweeping across the victim, plus the vortex that eats the dream.
            double size = 2.4 + .9 * v.progress;
            sheet(c, MOON, v.pos.add(0, .35, 0), size, size, -v.time * 2.4, a);
            double vortex = 1.0 + 1.4 * v.progress;
            sheet(c, SWIRL, v.pos.add(0, .2, 0), vortex, vortex, v.time * 1.8, a * .9f);
            sheet(c, STAR, v.pos.add(0, .25, 0), 1.0, 1.0, -v.time, a * .7f);
        } else if (v.is("heal")) {
            // The victim's vortex shrinks while Gengar's moon swells - two objects, nothing drawn
            // between them, so the effect never looks like a particle chain.
            double shrink = 1.8 * (1 - v.progress * .8);
            sheet(c, SWIRL, v.pos.add(0, .9, 0), shrink, shrink, v.time * 1.6, a * .85f);
            double grow = 1.2 + 1.4 * v.progress;
            sheet(c, MOON, v.dir.add(0, 1.0, 0), grow, grow, v.time * 1.1, a * .85f);
        }
    }

    // ---- primitives -------------------------------------------------------------------------

    private static void sheet(WorldRenderContext c, ResourceLocation tex, Vec3 center, double w, double h,
                              double angle, float alpha) {
        if (alpha <= .01f) return;
        Vec3[] axes = axes(c, angle);
        Vec3 right = axes[0].scale(w * .5), up = axes[1].scale(h * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(right).subtract(up),
                center.subtract(right).add(up), center.add(right).add(up),
                center.add(right).subtract(up), 0, 0, 1, 1, alpha);
    }

    /** Flat sheet lying on the ground, for splats and shock rings. */
    private static void horizontal(WorldRenderContext c, ResourceLocation tex, Vec3 center, double size,
                                   float alpha) {
        Vec3 x = new Vec3(size * .5, 0, 0), z = new Vec3(0, 0, size * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(x).subtract(z),
                center.subtract(x).add(z), center.add(x).add(z), center.add(x).subtract(z),
                0, 0, 1, 1, alpha);
    }

    private static Vec3[] axes(WorldRenderContext c, double angle) {
        var q = c.camera().rotation();
        var r = q.transform(new org.joml.Vector3f(1, 0, 0));
        var u = q.transform(new org.joml.Vector3f(0, 1, 0));
        Vec3 right = new Vec3(r.x, r.y, r.z);
        Vec3 up = new Vec3(u.x, u.y, u.z);
        double cs = Math.cos(angle), sn = Math.sin(angle);
        return new Vec3[]{right.scale(cs).add(up.scale(sn)), up.scale(cs).subtract(right.scale(sn))};
    }
}

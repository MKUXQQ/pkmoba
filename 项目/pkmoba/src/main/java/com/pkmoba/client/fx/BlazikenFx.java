package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Blaziken: fire that hugs the body instead of a screen-filling haze.
 *
 * <p>The old version scaled every flame to 1-2.4 blocks, which read as a giant orange cloud.
 * These passes are deliberately small and layered: licking tongues that grow upward, a thin bright
 * core and embers, so the silhouette of the Pokemon stays visible while it burns.
 */
public final class BlazikenFx {
    private BlazikenFx() {
    }

    /** Small licking flames around a point: narrow, tall, rising. */
    private static void tongues(Fx fx, Vec3 base, double radius, int count, double time, float a) {
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + time * .12;
            double r = radius * (.35 + .5 * ((i * .37) % 1.0));
            double h = .18 + .45 * ((i * .61) % 1.0) + .12 * Math.sin(time * .25 + i);
            Vec3 p = base.add(Math.cos(ang) * r, h, Math.sin(ang) * r);
            double w = .22 + .16 * ((i * .29) % 1.0);
            fx.sheet(p, w, w * 1.9, Math.sin(time * .2 + i) * .35, a * (float) (.55 + .45 * ((i * .41) % 1.0)));
        }
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Blaze Kick: a single burning foot arc
                if (v.is("start")) {
                    tongues(fx, v.feet(), .42, 6, v.time, a * .85f);
                    fx.sheet(v.feet(), .38, .62, v.time * .2, a * .7f);
                    fx.groundRing(v.feet(), .5, 2, v.time, a * .45f);
                } else if (v.in("melee", "followup", "impact")) {
                    Vec3 at = v.pos;
                    fx.crescent(at, .62 + .18 * v.progress, -1.0, 1.8, .20, v.time * .45, a);
                    tongues(fx, at.add(0, -.12, 0), .5, 8, v.time, a * .9f);
                    fx.fist(at, v.dir, .5, a * .8f);
                    fx.groundRing(at, .7 + .5 * v.progress, 2, v.time, a * .55f);
                }
            }
            case 1 -> { // Double Kick: two mirrored foot crescents, left then right
                if (v.is("start")) {
                    tongues(fx, v.feet(), .4, 5, v.time, a * .7f);
                    fx.sheet(v.offHand(), .3, .3, 0, a * .5f);
                } else if (v.in("melee", "followup", "impact")) {
                    Vec3 at = v.pos;
                    boolean right = ((int) (v.age / 2)) % 2 == 1;
                    Vec3 side = fx.side().scale(right ? .45 : -.45);
                    fx.crescent(at.add(side), .60, -1.1, 1.6, .17, v.time * .4, a);
                    tongues(fx, at.add(side).add(0, -.1, 0), .38, 5, v.time, a * .8f);
                    fx.fist(at.add(side), v.dir, .42, a * .7f);
                }
            }
            case 2 -> { // Flame Charge: a tight burning cloak while running
                Vec3 p = v.is("projectile") ? v.pos : v.owner;
                if (v.is("start") || v.is("buff") || v.in("projectile", "melee", "followup", "release")) {
                    tongues(fx, p, .55, 12, v.time, a);
                    fx.groundRing(p, .75, 2, v.time, a * .55f);
                    for (int i = 1; i <= 4; i++) {
                        fx.sheet(p.subtract(v.dir.scale(i * .35)), .45 - i * .06, .8 - i * .1,
                                v.time * .1, a * (.6f - i * .1f));
                    }
                }
            }
            default -> { // Flare Blitz: small comet, long thin tail, ground fire on impact
                if (v.is("start") || v.is("buff")) {
                    tongues(fx, v.chest(), .6, 10, v.time, a);
                    fx.sphere(v.chest(), new Vec3(.34 + .12 * v.progress, .34 + .12 * v.progress, .34 + .12 * v.progress),
                            v.time * .25, false, a * .9f);
                    fx.groundRing(v.owner, .9, 2, v.time, a * .6f);
                } else if (v.in("projectile", "melee", "followup", "release")) {
                    Vec3 p = v.is("projectile") ? v.pos : v.chest();
                    fx.sphere(p, new Vec3(.36, .36, .36), v.time * .35, false, a);
                    for (int i = 1; i <= 6; i++) {
                        fx.sheet(p.subtract(v.dir.scale(i * .38)), .42 - i * .05, .62 - i * .06,
                                v.time * .1 + i, a * (.7f - i * .1f));
                    }
                    tongues(fx, p, .45, 8, v.time, a * .9f);
                    fx.flow(p.subtract(v.dir.scale(2.2)), p, .3, v.time, a * .6f);
                } else if (v.is("impact")) {
                    fx.groundRing(v.pos, 1.0 + 1.2 * v.progress, 3, v.time, a);
                    tongues(fx, v.pos, 1.0, 12, v.time, a);
                    fx.burst(v.pos, .8 + .8 * v.progress, 8, .25, v.time, a * .8f);
                }
            }
        }
    }
}

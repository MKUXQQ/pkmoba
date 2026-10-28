package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Lucario: aura, clean and readable.
 *
 * <p>Q 波导弹 = one contained aura sphere with a thin double rim; W 发劲 = a single forward palm
 * strike (no ring of random sheets); E 神速 = plain speed lines; R 近身战 = two forward punches and
 * one final cross slash.
 */
public final class LucarioFx {
    private LucarioFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Aura Sphere
                if (v.is("start")) {
                    fx.sphere(v.hand(), new Vec3(.22, .22, .22), v.time * .4, false, a);
                    fx.ring(v.hand(), .42, .024, a * .6f);
                    fx.glow(v.hand(), .45, a * .7f);
                } else if (v.in("projectile", "lob")) {
                    fx.sphere(v.pos, new Vec3(.28, .28, .28), v.time * .45, false, a);
                    fx.ring(v.pos, .45, .022, a * .7f);
                    fx.flow(v.pos.subtract(v.dir.scale(1.6)), v.pos, .20, v.time, a * .6f);
                } else if (v.in("impact", "melee")) {
                    fx.sphere(v.pos, new Vec3(.26 + .18 * v.progress, .26 + .18 * v.progress, .26 + .18 * v.progress),
                            v.time, false, a);
                    fx.ringWave(v.pos, .5 + 1.1 * v.progress, .03, 2, v.time, a * .85f);
                }
            }
            case 1 -> { // Force Palm: one decisive palm thrust
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .35, a * .75f);
                    fx.sheet(v.hand(), .3, .3, 0, a * .6f);
                } else if (v.in("melee", "followup", "impact")) {
                    Vec3 strike = v.chest().add(v.dir.scale(.95));
                    fx.fist(strike, v.dir, .42, a);
                    fx.ringWave(strike, .45 + .65 * v.progress, .025, 2, v.time, a * .7f);
                    fx.streak(v.chest(), strike, .16, a * .6f);
                }
            }
            case 2 -> { // Extreme Speed: pure motion lines
                Vec3 p = v.is("projectile") ? v.pos : v.owner;
                for (int i = 1; i <= 4; i++) {
                    fx.streak(p.subtract(v.dir.scale(.4 + i * .55)), p.subtract(v.dir.scale(.1 + i * .4)),
                            .09 - i * .012, a * (.65f - i * .1f));
                }
                fx.sheet(p, .45, .45, v.time * .3, a * .8f);
                if (v.is("impact") || v.is("melee")) fx.burst(v.pos, .8, 6, .3, v.time, a * .7f);
            }
            default -> { // Close Combat: two punches then a cross slash
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .4, a * .7f);
                    fx.glow(v.offHand(), .4, a * .7f);
                } else if (v.in("melee", "followup")) {
                    int beat = (int) (v.age / 2);
                    for (int i = 0; i < 2; i++) {
                        int b = beat - i;
                        if (b < 0) continue;
                        Vec3 fist = v.chest().add(fx.side().scale(b % 2 == 1 ? .3 : -.3))
                                .add(v.dir.scale(1.0)).add(0, .05, 0);
                        fx.fist(fist, v.dir, .3, a * (.9f - i * .3f));
                    }
                    fx.crescent(v.pos, .75, -.6, 1.9, .18, v.time * .3, a * .8f);
                    fx.crescent(v.pos, .75, 2.4, 1.9, .16, -v.time * .3, a * .7f);
                } else if (v.is("impact")) {
                    fx.cross(v.pos, .9 + .3 * v.progress, v.time * .3, a);
                    fx.burst(v.pos, 1.0, 8, .3, v.time, a * .8f);
                }
            }
        }
    }
}

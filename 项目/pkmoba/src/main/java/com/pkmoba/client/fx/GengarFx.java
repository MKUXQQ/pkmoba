package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Gengar: clean shapes, not a pile of wisps.
 *
 * <p>Q 暗影球 = one dark sphere with a single thin halo ring; W 污泥炸弹 = a dripping sludge ball;
 * E 催眠术 = three calm expanding rings plus a slow pendulum (readable, obviously a hypnotic
 * pattern); R 食梦 = straight shadow tendrils running from the victim back into Gengar so the drain
 * reads instantly.
 */
public final class GengarFx {
    private GengarFx() {
    }

    private static void orb(Fx fx, Vec3 p, double size, double time, float a) {
        fx.sphere(p, new Vec3(size, size, size), time * .35, false, a);
        fx.ring(p, size * 1.55, .022, a * .55f);
        fx.glow(p, size * 2.1, a * .7f);
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Shadow Ball
                if (v.is("start")) {
                    orb(fx, v.hand(), .20 + .12 * v.progress, v.time, a);
                    fx.orbit(v.hand(), .38, 3, .10, v.time, a * .6f, .12);
                } else if (v.in("projectile", "lob")) {
                    orb(fx, v.pos, .26, v.time, a);
                    fx.flow(v.pos.subtract(v.dir.scale(1.4)), v.pos, .16, v.time, a * .6f);
                    fx.pixels(v.pos, .5, 8, .10, v.time, a * .8f);
                } else if (v.in("impact", "melee")) {
                    orb(fx, v.pos, .30 + .30 * v.progress, v.time, a);
                    fx.ringWave(v.pos, .6 + 1.2 * v.progress, .03, 2, v.time, a * .85f);
                    fx.burst(v.pos, 1.1, 8, .4, v.time, a * .8f);
                }
            }
            case 1 -> { // Sludge Bomb
                if (v.is("start")) {
                    fx.sphere(v.hand(), new Vec3(.22, .22, .22), v.time * .4, true, a);
                    fx.pixels(v.hand(), .4, 8, .11, v.time, a * .7f);
                } else if (v.in("projectile", "lob")) {
                    fx.sphere(v.pos, new Vec3(.28, .24, .28), v.time * .5, true, a);
                    // Dripping sludge trail, one drop per beat.
                    for (int i = 0; i < 4; i++) {
                        double d = (i + 1) * .28;
                        Vec3 drop = v.pos.subtract(v.dir.scale(d)).add(0, -.12 * d, 0);
                        fx.sheet(drop, .16, .24, 0, a * (.6f - i * .1f));
                    }
                } else if (v.in("impact", "melee")) {
                    fx.disc(v.pos.add(0, .04, 0), .9 + 1.0 * v.progress, a * .8f);
                    fx.sphere(v.pos.add(0, .1, 0), new Vec3(.3, .24, .3), v.time, true, a);
                    fx.pixels(v.pos, .9, 12, .13, v.time, a * .85f);
                    for (int i = 0; i < 6; i++) {
                        double ang = i * Math.PI / 3;
                        fx.sheet(v.pos.add(Math.cos(ang) * .7, .3 + (i % 2) * .25, Math.sin(ang) * .7),
                                .2, .3, ang, a * .6f);
                    }
                }
            }
            case 2 -> { // Hypnosis: calm concentric rings + slow pendulum
                if (v.is("start") || v.is("release") || v.is("projectile")) {
                    Vec3 focus = v.is("projectile") ? v.pos : v.head().add(v.dir.scale(.6));
                    fx.glow(focus, .55, a * .8f);
                    for (int i = 0; i < 3; i++) {
                        double r = .45 + .42 * i + .25 * Math.sin(v.time * .12 - i);
                        fx.ring(focus, r, .028, a * (.8f - i * .18f));
                    }
                    // Pendulum swinging on its own axis: the classic hypnotic motion.
                    Vec3 arm = fx.side().scale(Math.sin(v.time * .16) * .42);
                    fx.beam(focus.subtract(arm).add(0, .45, 0), focus.add(arm).subtract(0, .2, 0), .10, a * .55f);
                    fx.sheet(focus.add(arm).subtract(0, .25, 0), .55, .55, v.time * .1, a * .85f);
                    fx.orbit(focus, .85, 4, .12, v.time * .5, a * .5f, .4);
                } else if (v.in("impact", "melee")) {
                    // The sleeping victim gets one calm halo, nothing noisy.
                    fx.ringWave(v.pos.add(0, 1.1, 0), .55 + .5 * v.progress, .03, 2, v.time, a * .8f);
                    fx.glow(v.pos.add(0, 1.2, 0), .6, a * .6f);
                    fx.motes(v.pos.add(0, 1.3, 0), .5, 5, .10, v.time, a * .6f);
                }
            }
            default -> { // Dream Eater: shadow tendrils flowing victim -> Gengar
                Vec3 from = v.pos;
                Vec3 to = v.chest();
                if (v.in("start", "release")) {
                    Vec3 hand = v.hand();
                    fx.glow(hand, .5, a * .8f);
                    fx.orbit(hand, .5, 4, .12, v.time, a * .6f, .3);
                } else if (v.in("projectile", "melee", "followup")) {
                    for (int i = 0; i < 5; i++) {
                        double off = (i - 2) * .22;
                        Vec3 a0 = from.add(fx.side().scale(off)).add(0, .3, 0);
                        Vec3 a1 = to.add(fx.side().scale(off * .6)).add(0, .2, 0);
                        fx.beam(a0, a1, .07, a * (.75f - i * .06f));
                        fx.flow(a0, a1, .07, v.time + i * .7, a * .5f);
                    }
                    fx.glow(to, .55, a * .7f);
                    fx.glow(from, .5, a * .6f);
                } else if (v.is("impact")) {
                    fx.glow(to, .7, a * .8f);
                    fx.ringWave(to, .5 + .7 * v.progress, .03, 2, v.time, a * .7f);
                }
            }
        }
    }
}

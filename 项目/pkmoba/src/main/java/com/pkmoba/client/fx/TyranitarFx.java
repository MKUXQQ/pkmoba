package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Tyranitar: rock, jaw, sand.
 *
 * <p>Q 岩崩 = boulders dropping on the target; W 咬碎 = a clean jaw that closes once; E 沙暴 = three
 * small sand tornadoes orbiting Tyranitar itself (they keep spinning for the whole field duration,
 * which is also what damages nearby enemies); R 尖石攻击 = a line of stone spikes bursting from the
 * ground toward the target.
 */
public final class TyranitarFx {
    private TyranitarFx() {
    }

    /** The three orbiting sand spouts, shared by the start cast and the sustained field. */
    private static void sandstorm(Fx fx, Vec3 owner, double time, float a) {
        for (int i = 0; i < 3; i++) {
            double ang = i * Math.PI * 2 / 3 + time * .09;
            Vec3 p = owner.add(Math.cos(ang) * 1.35, .02, Math.sin(ang) * 1.35);
            fx.tornado(p, .55, 1.5, time + i * 1.7, a * .9f);
            fx.dust(p, .6, 5, time + i, a * .5f);
        }
        fx.groundRing(owner, 1.6, 2, time, a * .4f);
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Rock Slide
                if (v.is("start") || v.is("buff")) {
                    fx.motes(v.chest(), .7, 5, .16, v.time, a * .6f);
                    fx.glow(v.chest(), .5, a * .6f);
                } else if (v.in("projectile", "lob", "impact", "melee")) {
                    Vec3 p = v.is("impact") || v.is("melee") ? v.pos : v.pos;
                    for (int i = 0; i < 5; i++) {
                        double ang = i * Math.PI * 2 / 5 + v.time * .1;
                        Vec3 rock = p.add(Math.cos(ang) * (.5 + i * .18), .5 + (i % 3) * .55, Math.sin(ang) * (.5 + i * .18));
                        fx.rocks(rock, .22, 1, .26, v.time + i, a * .9f);
                    }
                    if (v.is("impact") || v.is("melee")) {
                        fx.disc(p.add(0, .05, 0), 1.0 + .8 * v.progress, a * .7f);
                        fx.spikeRing(p, 1.0, 5, a * .65f);
                        fx.dust(p, 1.2, 8, v.time, a * .7f);
                    }
                }
            }
            case 1 -> { // Crunch: one big closing jaw
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.head(), .45, a * .7f);
                    fx.sheet(v.head().add(v.dir.scale(.6)), .55, .45, 0, a * .7f);
                } else if (v.in("melee", "followup", "impact")) {
                    Vec3 jaw = v.head().add(v.dir.scale(.85));
                    float open = v.in("melee") ? 1f - (float) Math.min(1, v.progress * 3) : .2f;
                    fx.sheet(jaw.add(0, .28 * open, 0), .7, .5, .22 * open, a);
                    fx.sheet(jaw.subtract(0, .24 * open, 0), .7, .5, -.18 * open, a * .9f);
                    fx.pixels(jaw, .45, 8, .12, v.time, a * .8f);
                    if (v.is("impact")) fx.burst(jaw, .8, 6, .3, v.time, a * .7f);
                }
            }
            case 2 -> { // Sandstorm: three spouts orbiting Tyranitar
                if (v.is("start") || v.is("release")) {
                    sandstorm(fx, v.owner, v.time, a * (float) (.55 + .45 * v.progress));
                } else if (v.in("field", "buff")) {
                    sandstorm(fx, v.owner, v.time, a);
                } else if (v.in("impact", "melee")) {
                    sandstorm(fx, v.owner, v.time, a * .8f);
                    fx.dust(v.pos, 1.4, 10, v.time, a * .8f);
                }
            }
            default -> { // Stone Edge: spikes march forward along the aim direction
                Vec3 from = v.owner.add(0, .02, 0);
                Vec3 dir = v.dir.lengthSqr() < 1E-4 ? new Vec3(0, 0, 1) : v.dir.normalize();
                int count = 6;
                for (int i = 0; i < count; i++) {
                    double f = (i + 1) / (double) count;
                    double reach = Math.max(1.5, v.radius) * f * (v.in("start", "release") ? .6 : 1.0);
                    Vec3 p = from.add(dir.scale(reach));
                    fx.sheet(p.add(0, .55, 0), .60, 1.5, Math.atan2(dir.z, dir.x), a * (.95f - i * .1f));
                    fx.spikeRing(p, .35, 3, a * (.5f - i * .05f));
                }
                if (v.in("impact", "melee")) {
                    fx.dust(v.pos, 1.2, 8, v.time, a * .75f);
                    fx.spikeRing(v.pos, 1.1, 6, a * .8f);
                }
            }
        }
    }
}

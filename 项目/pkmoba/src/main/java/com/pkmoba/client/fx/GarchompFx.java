package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Garchomp: heavy, grounded, three clean beats.
 *
 * <p>Q 龙爪 = three parallel claw slashes; W 重踏 = a ground shock disc with a ring of dirt
 * spikes; E 挖洞 = an eruption of earth from below; R 龙之俯冲 = a diving comet with a straight
 * speed cone and a wide landing ring.
 */
public final class GarchompFx {
    private GarchompFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Dragon Claw: three parallel slashes
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .4, a * .7f);
                    fx.sheet(v.hand(), .45, .45, v.time * .2, a * .7f);
                }
                if (v.in("melee", "followup", "impact", "release")) {
                    Vec3 at = v.pos;
                    for (int i = -1; i <= 1; i++) {
                        Vec3 off = fx.side().scale(i * .30);
                        fx.crescent(at.add(off), .80 + .2 * v.progress, -1.0, 1.9, .17,
                                .55 + i * .12, a * (1f - Math.abs(i) * .2f));
                    }
                    fx.burst(at, .8, 6, .3, v.time, a * .6f);
                }
            }
            case 1 -> { // Earthquake: ground shock
                if (v.is("start") || v.is("buff")) {
                    fx.groundRing(v.owner, 1.2, 2, v.time, a * .6f);
                }
                if (v.in("field", "impact", "release")) {
                    fx.disc(v.pos.add(0, .04, 0), 1.4 + 1.6 * v.progress, a * .7f);
                    fx.groundRing(v.pos, 1.2 + 1.5 * v.progress, 3, v.time, a * .85f);
                    fx.shards(v.pos, 1.4, 10, .55, v.time, a * .8f);
                    fx.dust(v.pos, 1.6, 10, v.time, a * .7f);
                }
            }
            case 2 -> { // Dig: earth erupts under the target
                if (v.is("start") || v.is("buff")) {
                    fx.dust(v.owner, .9, 7, v.time, a * .7f);
                }
                if (v.in("impact", "spike", "melee")) {
                    fx.disc(v.pos.add(0, .04, 0), 1.0 + .9 * v.progress, a * .7f);
                    for (int i = 0; i < 7; i++) {
                        double ang = i * Math.PI * 2 / 7;
                        Vec3 p = v.pos.add(Math.cos(ang) * (.5 + .5 * v.progress), 0, Math.sin(ang) * (.5 + .5 * v.progress));
                        fx.sheet(p.add(0, .35, 0), .45, .6, ang, a * .8f);
                    }
                    fx.spikeRing(v.pos, .9, 6, a * .7f);
                    fx.dust(v.pos, 1.2, 8, v.time, a * .75f);
                }
            }
            default -> { // Dragon Dive: comet from above
                Vec3 p = v.is("projectile") ? v.pos : v.chest();
                if (v.is("start") || v.is("buff")) {
                    fx.sheet(v.chest(), .7, .7, v.time * .3, a * .8f);
                    fx.glow(v.chest(), .5, a * .7f);
                } else if (v.in("projectile", "release", "melee", "followup")) {
                    fx.sphere(p, new Vec3(.4, .4, .4), v.time * .4, false, a);
                    fx.cone(p.subtract(v.dir.scale(.2)), v.dir.scale(-1), 2.2, .35, v.time, a * .55f);
                    fx.flow(p.subtract(v.dir.scale(2.4)), p, .35, v.time, a * .7f);
                } else if (v.is("impact")) {
                    fx.groundRing(v.pos, 1.4 + 2.0 * v.progress, 3, v.time, a);
                    fx.burst(v.pos, 1.4, 10, .2, v.time, a * .8f);
                    fx.dust(v.pos, 1.8, 10, v.time, a * .8f);
                }
            }
        }
    }
}

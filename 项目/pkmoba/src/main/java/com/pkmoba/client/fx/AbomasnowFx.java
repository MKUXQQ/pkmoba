package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Abomasnow: ice in three clearly different shapes.
 *
 * <p>Q 冰砾 = a small volley of sharp ice shards; W 冰冻之风 = a continuous frost spray cone that
 * leaves a freezing ring where it lands; E 白雾 = a wide, slowly drifting mist field around the
 * Pokemon (large radius, obvious slow zone); R 暴风雪 = one ground tornado wrapped in snow, plus
 * snow falling inside it.
 */
public final class AbomasnowFx {
    private AbomasnowFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Ice Shard
                if (v.is("start") || v.is("buff")) {
                    fx.crystal(v.hand(), .35, .55, v.time * .3, a);
                    fx.glow(v.hand(), .4, a * .7f);
                } else if (v.in("projectile", "lob")) {
                    for (int i = 0; i < 3; i++) {
                        Vec3 p = v.pos.add(fx.side().scale((i - 1) * .22)).add(0, .05 * i, 0);
                        fx.crystal(p, .30, .45, v.time * .4 + i, a);
                    }
                    fx.flow(v.pos.subtract(v.dir.scale(1.6)), v.pos, .18, v.time, a * .5f);
                } else if (v.in("impact", "melee")) {
                    fx.shards(v.pos, .55 + .5 * v.progress, 8, .45, v.time, a);
                    fx.ringWave(v.pos, .5 + .9 * v.progress, .025, 2, v.time, a * .7f);
                }
            }
            case 1 -> { // Icy Wind: continuous spray cone that freezes
                if (v.is("start") || v.is("release") || v.in("projectile", "followup")) {
                    Vec3 from = v.chest();
                    Vec3 dir = v.dir.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : v.dir.normalize();
                    fx.cone(from, dir, 4.2, .55, v.time, a * .9f);
                    for (int i = 0; i < 3; i++) {
                        fx.sheet(from.add(dir.scale(1.0 + i * .9)).add(0, .1 * i, 0),
                                .7 + i * .12, 1.4 + i * .2, v.time * .1, a * (.55f - i * .12f));
                    }
                    if (v.is("followup") || v.is("release")) {
                        fx.sheet(v.pos, .6, .6, v.time * .2, a * .7f);
                        fx.ringWave(v.pos, .6 + .8 * v.progress, .022, 2, v.time, a * .6f);
                    }
                } else if (v.in("impact", "melee")) {
                    fx.cone(v.chest(), v.dir, 3.0, .45, v.time, a * .8f);
                    fx.ringWave(v.pos, .6, .025, 2, v.time, a * .8f);
                    fx.mist(v.pos, 1.1, 5, v.time, a * .5f);
                }
            }
            case 2 -> { // Mist: a big soft field the enemy has to wade through
                Vec3 base = v.is("projectile") || v.in("field", "wall", "impact") ? v.pos : v.owner;
                double radius = Math.max(2.2, Math.min(4.5, v.radius));
                if (v.in("field", "buff", "wall", "impact", "release", "projectile")) {
                    fx.mist(base, radius, 14, v.time, a * .8f);
                    fx.groundRing(base, radius * .9, 2, v.time, a * .35f);
                    fx.motes(base.add(0, .6, 0), radius * .7, 6, .18, v.time, a * .45f);
                }
            }
            default -> { // Blizzard: one ground tornado + snowfall
                Vec3 base = (v.in("impact", "field") ? v.pos : v.owner);
                if (v.in("start", "release", "buff", "field", "impact", "projectile")) {
                    double h = 2.6 + .6 * Math.sin(v.time * .2);
                    fx.tornado(base, 1.1, h, v.time, a);
                    fx.rain(base.add(0, 1.2, 0), 1.6, 3.0, 10, .22, v.time, a * .55f);
                    fx.groundRing(base, 1.6, 3, v.time, a * .5f);
                    fx.glow(base.add(0, 1.0, 0), .8, a * .5f);
                }
            }
        }
    }
}

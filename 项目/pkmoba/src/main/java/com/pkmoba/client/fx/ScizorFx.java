package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Scizor: fists in front, blades second.
 *
 * <p>Q 子弹拳 = rapid alternating punches, both claws firing forward from the chest; W 连斩 = two
 * clean crossing blade arcs; E 急速折返 = a straight rush of speed lines; R 剑舞 = a slow, elegant
 * ring of blades lifting around the body (no random clutter).
 */
public final class ScizorFx {
    private ScizorFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Bullet Punch: alternating left/right fist strikes in front of the chest
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .35, a * .7f);
                    fx.glow(v.offHand(), .35, a * .7f);
                } else if (v.in("melee", "followup", "attack", "impact")) {
                    // Alternating fists produce a visible punch combo rather than one flash.
                    int beat = (int) (v.age / 2);
                    for (int i = 0; i < 3; i++) {
                        int b = beat - i;
                        if (b < 0) continue;
                        boolean right = b % 2 == 1;
                        Vec3 fist = v.chest().add(fx.side().scale(right ? .34 : -.34))
                                .add(v.dir.scale(1.15 + .12 * i)).add(0, .05, 0);
                        float k = a * (.95f - i * .28f);
                        fx.fist(fist, v.dir, .30, k);
                        fx.streak(v.chest().add(fx.side().scale(right ? .34 : -.34)), fist, .12, k * .8f);
                    }
                    fx.burst(v.pos, .9, 6, .3, v.time, a * .7f);
                }
            }
            case 1 -> { // Slash: two clean crossing blades
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .4, a * .7f);
                    fx.sheet(v.hand(), .5, .5, v.time * .2, a * .7f);
                }
                if (v.in("melee", "followup", "attack", "release", "impact")) {
                    Vec3 at = v.pos;
                    fx.crescent(at, .85 + .25 * v.progress, -.8, 2.0, .22, .55, a);
                    fx.crescent(at, .85 + .25 * v.progress, 2.3, 2.0, .20, -.55, a * .9f);
                    fx.burst(at, .7, 6, .3, v.time, a * .55f);
                }
            }
            case 2 -> { // U-turn: a straight rush line
                Vec3 p = v.is("projectile") ? v.pos : v.owner;
                for (int i = 1; i <= 5; i++) {
                    fx.streak(p.subtract(v.dir.scale(.5 + i * .5)),
                            p.subtract(v.dir.scale(.15 + i * .35)), .10 - i * .012, a * (.7f - i * .1f));
                }
                fx.sheet(p, .55, .55, v.time * .3, a * .8f);
                if (v.is("impact") || v.is("melee")) fx.burst(v.pos, .9, 7, .3, v.time, a * .75f);
            }
            default -> { // Swords Dance: blades rise in a tidy rotating ring
                if (v.is("start") || v.is("buff") || v.is("field")) {
                    fx.groundRing(v.owner, 1.0, 2, v.time, a * .55f);
                    fx.glow(v.chest(), .6, a * .6f);
                    int count = 6;
                    for (int i = 0; i < count; i++) {
                        double ang = i * Math.PI * 2 / count + v.time * .09;
                        double lift = .25 + .45 * ((i + v.age * .08) % 1.0);
                        Vec3 p = v.owner.add(Math.cos(ang) * .85, lift, Math.sin(ang) * .85);
                        fx.sheet(p, .34, 1.0, ang + Math.PI * .5, a * (.55f + .3f * (float) Math.sin(i + v.time * .2)));
                    }
                    fx.motes(v.chest(), .75, 6, .12, v.time, a * .6f);
                } else if (v.is("release")) {
                    fx.ringWave(v.chest(), .9 + .8 * v.progress, .03, 2, v.time, a * .8f);
                    fx.burst(v.chest(), 1.0, 8, .3, v.time, a * .7f);
                }
            }
        }
    }
}

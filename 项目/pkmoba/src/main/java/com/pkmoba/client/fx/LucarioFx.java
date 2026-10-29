package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Lucario: aura, clean and readable.
 *
 * <p>0.56 pass — the old version stacked a shared "abundance" pass (packet stream + chunky pixels
 * + halo) on top of per-skill spheres/rings/crescents, which at close range smeared into one blue
 * blob and hid the body animation. Lucario now draws a deliberately sparse set: one shape per
 * beat, thin lines, no halo, so the skill reads as <em>the model's action plus one hit mark</em>.
 *
 * <p>Q 波导弹 = palm charge, one contained sphere in flight, one impact ring. W 骨棒乱打 = one thin
 * sweep arc per club beat. E 神速 = two speed streaks. R 近身打 = one punch crescent, one cross on
 * impact.
 */
public final class LucarioFx {
    private LucarioFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        // 0.55 波导蓄势: the stack rings and the spend burst are their own scenes, drawn before the
        // per-move choreography because the server re-cues them at 2 Hz on a moving body.
        if (v.phase.startsWith("aura")) {
            int n = layers(v.phase, 4);
            Vec3 chest = v.chest();
            for (int i = 0; i < n; i++) {
                double r = .40 + i * .13;
                fx.ringWave(chest.add(0, -.16 + i * .08, 0), r, .020, 1, v.time * (i % 2 == 0 ? .18 : -.18),
                        a * .55f);
            }
            if (n > 0) fx.sphere(chest, new Vec3(.11, .11, .11), v.time * .5, false, a * .6f);
            return;
        }
        if (v.phase.startsWith("spend")) {
            int spent = Math.max(1, layers(v.phase, 5));
            Vec3 chest = v.chest();
            for (int i = 0; i < spent; i++) {
                fx.ringWave(chest.add(0, -.16, 0), (.26 + i * .2) * (1 + v.progress * 1.6), .024, 1,
                        v.time * .3 + i, a * .6f);
            }
            fx.burst(chest.add(v.dir.scale(.35)), .30 + spent * .10, 4 + spent * 2, .32, v.time, a * .7f);
            return;
        }
        switch (v.skill) {
            case 0 -> { // 波导弹: charge in the palms, one clean sphere, one impact ring
                if (v.is("start")) {
                    fx.sphere(v.hand(), new Vec3(.15, .15, .15), v.time * .4, false, a * .9f);
                    fx.ring(v.hand(), .30, .018, a * .5f);
                } else if (v.in("projectile", "lob")) {
                    fx.sphere(v.pos, new Vec3(.20, .20, .20), v.time * .45, false, a);
                    fx.ring(v.pos, .34, .016, a * .55f);
                } else if (v.in("impact", "melee")) {
                    fx.ringWave(v.pos, .35 + .95 * v.progress, .022, 1, v.time, a * .8f);
                    fx.sphere(v.pos, new Vec3(.14, .14, .14), v.time, false, a * .7f);
                }
            }
            case 1 -> { // 骨棒乱打: exactly one thin arc per club beat
                if (v.is("start")) {
                    fx.glow(v.hand(), .22, a * .45f);
                } else if (v.is("impact")) {
                    // Damage resolves on the struck body on the same tick as the beat cue, so the
                    // arc is drawn at the victim instead of at the caster to keep hit and action
                    // visually welded together.
                    fx.crescent(v.pos, .52, 1.4, 1.7, .060, v.time * .35, a * .75f);
                } else if (v.in("melee", "followup")) {
                    Vec3 strike = v.chest().add(v.dir.scale(.85));
                    fx.crescent(strike, .58, 1.3, 1.7, .070, v.time * .35, a * .8f);
                    fx.streak(v.hand(), strike, .070, a * .40f);
                }
            }
            case 2 -> { // 神速: two streaks, nothing else
                Vec3 p = v.is("projectile") ? v.pos : v.owner;
                for (int i = 1; i <= 2; i++) {
                    fx.streak(p.subtract(v.dir.scale(.5 + i * .8)), p.subtract(v.dir.scale(.15 + i * .5)),
                            .065 - i * .015, a * (.5f - i * .12f));
                }
            }
            default -> { // 近身打: one punch crescent on the beat, one cross at the hit
                if (v.is("start") || v.is("buff")) {
                    fx.glow(v.hand(), .2, a * .45f);
                } else if (v.in("melee", "followup")) {
                    Vec3 fist = v.chest().add(v.dir.scale(.9));
                    fx.fist(fist, v.dir, .26, a * .85f);
                    fx.crescent(fist, .60, v.dir.y > 0 ? -1.4 : 1.4, 1.6, .070, v.time * .3, a * .55f);
                } else if (v.is("impact")) {
                    fx.cross(v.pos, .65 + .25 * v.progress, v.time * .3, a * .8f);
                }
            }
        }
    }

    /** Layer count of an "aura2" / "spend3" cue, clamped so a malformed cue cannot overdraw. */
    private static int layers(String phase, int at) {
        if (phase.length() <= at) return 0;
        char c = phase.charAt(at);
        return c < '0' || c > '9' ? 0 : Math.min(3, c - '0');
    }
}

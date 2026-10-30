package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * 0.133 Hisuian Zoroark (洗翠索罗亚克) visuals.
 *
 * <p>Red-white ghost energy on black: claw arcs for Shadow Claw, the grudge ring for Bitter Malice,
 * a black-red orb for Shadow Ball and the mirrored silhouettes of the illusion ultimate. The malice
 * sigil has its own phase so a marked target wears the same symbol no matter which skill stamped it.
 */
public final class ZoroarkFx {
    private ZoroarkFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation CLAW_L = tex("zo_claw_l");
    private static final ResourceLocation CLAW_R = tex("zo_claw_r");
    private static final ResourceLocation CROSS = tex("zo_claw_cross");
    private static final ResourceLocation RING = tex("zo_ring");
    private static final ResourceLocation WISP = tex("zo_wisp");
    private static final ResourceLocation ORB = tex("zo_orb");
    private static final ResourceLocation BURST = tex("zo_burst");
    private static final ResourceLocation SIGIL = tex("zo_sigil");
    private static final ResourceLocation AFTER = tex("zo_after");

    public static void render(FxView v) {
        Fx clawL = new Fx(v.fx.context(), CLAW_L);
        Fx clawR = new Fx(v.fx.context(), CLAW_R);
        Fx cross = new Fx(v.fx.context(), CROSS);
        Fx ring = new Fx(v.fx.context(), RING);
        Fx wisp = new Fx(v.fx.context(), WISP);
        Fx orb = new Fx(v.fx.context(), ORB);
        Fx burst = new Fx(v.fx.context(), BURST);
        Fx sigil = new Fx(v.fx.context(), SIGIL);
        Fx after = new Fx(v.fx.context(), AFTER);
        float a = v.fade;
        switch (v.phase) {
            case "start" -> {
                wisp.sheet(v.hand(), .9, .9, 0, a * .8f);
                sigil.starBurst(v.chest(), .9, 5, v.time, a * .5f);
            }
            case "sink" -> {
                ring.upright(v.feet(), 1.1, 1.1, 0, a * .8f);
                for (int i = 0; i < 4; i++) {
                    double ang = v.time * .3 + i * Math.PI / 2;
                    wisp.sheet(v.chest().add(Math.cos(ang) * .7, .1, Math.sin(ang) * .7), .5, .5, 0, a * .6f);
                }
            }
            case "wisp" -> {
                after.upright(v.chest(), .7, 1.0, 0, a * .55f);
                clawL.sheet(v.chest(), .9, .9, 18, a * .7f);
                clawR.sheet(v.chest(), .9, .9, -18, a * .7f);
            }
            case "claw" -> {
                Vec3 at = v.pos;
                clawL.sheet(at, 1.3, 1.3, 20, a);
                clawR.sheet(at, 1.3, 1.3, -20, a * .9f);
                sigil.starBurst(at, .8, 5, v.time, a * .6f);
            }
            // 0.133.7: a claw bite from the body or from an illusion. The slash sits right in
            // front of the paw that swings, so the mark lands where the arm actually goes.
            case "attack" -> {
                Vec3 at = v.front(.7);
                boolean right = Math.abs(v.dir.x) + Math.abs(v.dir.z) > 0 && v.time % 2 == 0;
                (right ? clawR : clawL).sheet(at, 1.1, 1.1, -16, a);
                clawL.sheet(at.add(0, .2, 0), .9, .9, 22, a * .8f);
                burst.starBurst(at, .5, 4, v.time, a * .55f);
                wisp.sheet(at.subtract(v.dir.scale(.2)), .6, .6, 0, a * .5f);
            }
            case "cross" -> {
                cross.sheet(v.pos, 1.6, 1.6, 0, a);
                burst.ringWave(v.pos, 1.0, .12, 2, v.time, a * .7f);
            }
            case "gather" -> {
                for (int i = 0; i < 5; i++) {
                    double ang = v.time * .5 + i * 1.2566;
                    wisp.sheet(v.chest().add(Math.cos(ang) * (1.0 - v.progress * .6), .3, Math.sin(ang) * (1.0 - v.progress * .6)),
                            .55, .55, 0, a * .7f);
                }
                sigil.upright(v.feet(), 1.4, 1.4, v.time * 40, a * .5f);
            }
            case "charge" -> {
                orb.sheet(v.pos, 1.0 + v.progress * .5, 1.0 + v.progress * .5, v.time * 120, a);
                wisp.sheet(v.pos, .8, .8, 0, a * .6f);
            }
            case "burst" -> {
                ring.upright(v.feet(), 2.6, 2.6, v.time * 30, a);
                for (int i = 0; i < 6; i++) {
                    double ang = i * Math.PI / 3 + v.time * .2;
                    wisp.sheet(v.chest().add(Math.cos(ang) * 2.2, -.4, Math.sin(ang) * 2.2), .7, .7, 0, a * .7f);
                }
            }
            case "projectile" -> {
                orb.sheet(v.pos, 1.1, 1.1, v.time * 160, a);
                for (int i = 1; i <= 3; i++) {
                    orb.sheet(v.pos.add(v.dir.scale(-i * .45)), 1.1 - i * .22, 1.1 - i * .22, v.time * 160, a * (.45f - i * .1f));
                }
                wisp.sheet(v.pos, .9, .9, 0, a * .5f);
            }
            case "impact" -> {
                burst.sheet(v.pos, 1.8, 1.8, 0, a);
                burst.ringWave(v.pos, 2.4, .18, 3, v.time, a * .8f);
                cross.sheet(v.pos, 1.2, 1.2, 30, a * .8f);
                wisp.sheet(v.pos, 1.2, 1.2, 0, a * .5f);
            }
            case "split" -> {
                ring.upright(v.feet(), 3.0, 3.0, v.time * 60, a);
                for (int i = 0; i < 3; i++) {
                    double ang = i * Math.PI * 2 / 3 + .6;
                    after.upright(v.feet().add(Math.cos(ang) * 1.4, 0, Math.sin(ang) * 1.4), .9, 1.3, 0, a * .7f);
                }
            }
            case "orbit" -> {
                for (int i = 0; i < 3; i++) {
                    double ang = i * Math.PI * 2 / 3 + .6 + v.time * .05;
                    after.upright(v.feet().add(Math.cos(ang) * 3.0, 0, Math.sin(ang) * 3.0), .9, 1.3, 0, a * .55f);
                }
            }
            case "rush" -> {
                cross.sheet(v.pos, 1.8, 1.8, 0, a);
                for (int i = 0; i < 3; i++) {
                    double ang = i * Math.PI * 2 / 3 + .6;
                    after.upright(v.feet().add(Math.cos(ang) * 1.2, 0, Math.sin(ang) * 1.2), 1.0, 1.4, 0, a * .8f);
                }
            }
            case "final" -> {
                cross.sheet(v.pos, 2.4, 2.4, 0, a);
                burst.ringWave(v.pos, 3.0, .22, 3, v.time, a);
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4;
                    clawL.sheet(v.pos.add(Math.cos(ang) * 1.8, 0, Math.sin(ang) * 1.8), .9, .9, 0, a * .6f);
                }
            }
            case "detonate" -> {
                burst.sheet(v.pos, 2.2, 2.2, 0, a);
                sigil.starBurst(v.pos, 1.6, 8, v.time, a);
                burst.ringWave(v.pos, 2.6, .20, 2, v.time, a * .9f);
            }
            case "mark1", "mark2", "mark3" -> {
                int stacks = v.phase.charAt(4) - '0';
                float strength = .35f + stacks * .2f;
                sigil.upright(v.head().add(0, .35, 0), .55, .55, v.time * 40, a * strength);
                if (stacks >= 3) {
                    ring.upright(v.feet(), 1.1, 1.1, -v.time * 50, a * .65f);
                    sigil.starBurst(v.chest(), 1.0, 6, v.time, a * .5f);
                }
            }
            case "markfade" -> {
                sigil.upright(v.head().add(0, .35, 0), .55, .55, 0, a * .4f);
                wisp.starBurst(v.chest(), .8, 5, v.time, a * .4f);
            }
            default -> {
                wisp.sheet(v.pos, .7, .7, 0, a * .5f);
            }
        }
    }
}

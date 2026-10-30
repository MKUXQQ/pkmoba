package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Greninja 2.0 (m00465) visuals - a clean-sheet kit, no sheet of the old set is reused.
 *
 * <p>Q is a spinning four-blade water star thrown five times, each star its own cue so they fly
 * apart instead of stacking. W tears a deep blue-black slash with a violet edge and a white glint.
 * E is a black-blue orb wrapped in two dark rings. R is a real pressure hose: a churning water
 * sphere, then a white-blue jet with bubbles racing along it.
 */
public final class GreninjaFx {
    private GreninjaFx() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation STAR = tex("gj_star");
    private static final ResourceLocation SLASH = tex("gj_slash");
    private static final ResourceLocation ORB = tex("gj_orb");
    private static final ResourceLocation RING = tex("gj_ring");
    private static final ResourceLocation SEAL = tex("gj_plot_seal");
    private static final ResourceLocation BURST = tex("gj_plot_burst");
    private static final ResourceLocation MOTE = tex("gj_plot_mote");
    private static final ResourceLocation BUBBLE = tex("gj_bubble");
    private static final ResourceLocation SPLASH = tex("gj_splash");
    private static final ResourceLocation DROP = tex("gj_drop");

    public static void render(FxView v) {
        Fx star = new Fx(v.fx.context(), STAR);
        Fx slash = new Fx(v.fx.context(), SLASH);
        Fx orb = new Fx(v.fx.context(), ORB);
        Fx ring = new Fx(v.fx.context(), RING);
        Fx seal = new Fx(v.fx.context(), SEAL);
        Fx burst = new Fx(v.fx.context(), BURST);
        Fx mote = new Fx(v.fx.context(), MOTE);
        Fx bubble = new Fx(v.fx.context(), BUBBLE);
        Fx splash = new Fx(v.fx.context(), SPLASH);
        Fx drop = new Fx(v.fx.context(), DROP);
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> shuriken(v, star, drop, splash, ring, a);
            case 1 -> nightSlash(v, slash, orb, a);
            case 2 -> darkPulse(v, orb, ring, drop, a);
            default -> nastyPlot(v, seal, burst, mote, bubble, ring, a);
        }
    }

    /** Q 螺旋手里剑: five independent water stars, each spinning on its own line. */
    private static void shuriken(FxView v, Fx star, Fx drop, Fx splash, Fx ring, float a) {
        if (v.phase.startsWith("shuriken")) {
            int index = v.phase.length() > 8 ? v.phase.charAt(8) - '0' : 0;
            double spin = v.time * 560 + index * 41;
            star.sheet(v.pos, 1.15, 1.15, spin, a);
            star.sheet(v.pos, .74, .74, -spin * 1.4, a * .55f);
            star.streak(v.pos.subtract(v.dir.scale(1.5)), v.pos, .34, a * .5f);
            for (int i = 0; i < 2; i++) {
                Vec3 at = v.pos.subtract(v.dir.scale(.5 + i * .55))
                        .add(0, .16 * Math.sin(v.time * 5 + i + index), 0);
                drop.sheet(at, .26, .26, v.time * 300, a * .8f);
            }
            return;
        }
        switch (v.phase) {
            case "start" -> {
                Vec3 at = v.hand().add(v.dir.scale(.35));
                star.sheet(at, .55 + v.progress * .45, .55 + v.progress * .45, v.time * 480, a * .95f);
                for (int i = 0; i < 4; i++) {
                    double ang = v.time * 3.4 + i * 1.5708;
                    drop.sheet(at.add(Math.cos(ang) * .42, .12 + .12 * Math.sin(v.time * 4 + i),
                            Math.sin(ang) * .42), .2, .2, -v.time * 260, a * .85f);
                }
            }
            case "spark" -> {
                splash.slab(v.pos.add(0, .04, 0), 1.5, 1.5, 0, a * .8f);
                star.sheet(v.pos, .9, .9, v.time * 420, a * .7f);
                for (int i = 0; i < 3; i++) {
                    double ang = i * 2.09 + .4;
                    drop.sheet(v.pos.add(Math.cos(ang) * .7, .3, Math.sin(ang) * .7),
                            .3, .3, v.time * 300, a * .85f);
                }
            }
            case "impact" -> {
                splash.slab(v.pos.add(0, .04, 0), 2.6, 2.6, v.time * 20, a * .9f);
                splash.burst(v.pos, 1.1, 10, .4, v.time, a * .9f);
                star.sheet(v.pos, 1.5, 1.5, v.time * 380, a * .85f);
                for (int i = 0; i < 5; i++) {
                    double ang = i * 1.257 + .2;
                    drop.sheet(v.pos.add(Math.cos(ang) * 1.2, .35 + .2 * (i % 2), Math.sin(ang) * 1.2),
                            .36, .36, -v.time * 240, a * .9f);
                }
            }
            default -> {
            }
        }
    }

    /** W 暗袭要害: crouch, a dark lunge and one crossed slash with a white glint. */
    private static void nightSlash(FxView v, Fx slash, Fx orb, float a) {
        switch (v.phase) {
            case "start" -> {
                Vec3 at = v.chest().add(v.dir.scale(.4));
                slash.sheet(at, .70 + v.progress * .35, .70 + v.progress * .35, v.time * 260, a * .9f);
                orb.ring(at, .55 + v.progress * .30, .03, a * .55f);
                slash.slab(v.feet().add(0, .05, 0), 1.5, 1.5, v.time * 20, a * .45f);
            }
            case "streak" -> {
                slash.streak(v.pos.subtract(v.dir.scale(1.6)), v.pos, .48, a * .75f);
                slash.sheet(v.pos, 1.1, 1.1, v.time * 300, a * .65f);
                orb.slab(v.pos.add(0, -.75, 0), 1.5, 1.5, v.time * 30, a * .35f);
            }
            case "attack" -> {
                Vec3 at = v.chest().add(v.dir.scale(.8));
                slash.cross(at, 2.2 + v.progress * .6, v.time * 140, a * .95f);
                slash.sheet(at, 2.4, 2.4, v.time * 180, a * .8f);
            }
            case "impact" -> {
                slash.cross(v.pos, 2.6, v.time * 160, a * .95f);
                slash.slab(v.pos.add(0, -.6, 0), 3.0, 3.0, v.time * 20, a * .55f);
                orb.ringWave(v.pos, 1.0, .10, 2, v.time, a * .7f);
                slash.burst(v.pos, 1.2, 8, .35, v.time, a * .8f);
            }
            default -> {
            }
        }
    }

    /** E 恶之波动: a black-blue orb carried by two dark rings, bursting into a violet shock. */
    private static void darkPulse(FxView v, Fx orb, Fx ring, Fx drop, float a) {
        switch (v.phase) {
            case "start" -> {
                Vec3 at = v.hand().add(v.dir.scale(.45));
                orb.sphere(at, new Vec3(.30 + v.progress * .22, .30 + v.progress * .22,
                        .30 + v.progress * .22), v.time * 120, true, a * .95f);
                ring.ring(at, .55 + v.progress * .35, .07, a * .8f);
                ring.sheet(at, .9 + v.progress * .4, .9 + v.progress * .4, v.time * 90, a * .5f);
            }
            case "projectile" -> {
                orb.sphere(v.pos, new Vec3(.52, .52, .52), v.time * 200, true, a);
                ring.ring(v.pos, .95, .10, a * .85f);
                ring.sheet(v.pos, 1.5, 1.5, -v.time * 240, a * .6f);
                ring.ringWave(v.pos, .7, .07, 2, v.time, a * .55f);
                orb.streak(v.pos.subtract(v.dir.scale(1.6)), v.pos, .5, a * .4f);
            }
            case "impact" -> {
                ring.slab(v.pos.add(0, .06, 0), 5.0, 5.0, 0, a * .9f);
                ring.ringWave(v.pos, 1.1, .16, 3, v.time, a * .95f);
                orb.sphere(v.pos.add(0, .4, 0), new Vec3(1.2, .8, 1.2), v.time * 140, true, a * .8f);
                orb.burst(v.pos.add(0, .3, 0), 2.0, 12, .5, v.time, a * .85f);
                for (int i = 0; i < 6; i++) {
                    double ang = i * 1.047 + .3;
                    drop.sheet(v.pos.add(Math.cos(ang) * 1.6, .3 + .2 * (i % 3), Math.sin(ang) * 1.6),
                            .34, .34, v.time * 200, a * .8f);
                }
            }
            default -> {
            }
        }
    }

    /** R 诡计: energy drawn inward to both hands, then one outward shockwave; the eight-second
     * aura itself lives in {@link com.pkmoba.client.NastyAura} so it outlives this scene. */
    private static void nastyPlot(FxView v, Fx seal, Fx burst, Fx mote, Fx bubble, Fx ring, float a) {
        switch (v.phase) {
            case "start" -> {
                Vec3 at = v.chest().add(v.dir.scale(.25));
                double shrink = 1.0 - v.progress * .55;
                seal.slab(v.feet().add(0, .06, 0), 2.0 * shrink, 2.0 * shrink, v.time * 60, a * .8f);
                for (int i = 0; i < 10; i++) {
                    double ang = v.time * 2.6 + i * .628;
                    double r = (1.30 - v.progress * .90) * (1 + .06 * Math.sin(v.time * 3 + i));
                    Vec3 p = at.add(Math.cos(ang) * r, .35 * Math.sin(v.time * 1.7 + i) - v.progress * .12,
                            Math.sin(ang) * r);
                    mote.sheet(p, .30, .30, v.time * 150 + i * 30, a * .85f);
                }
                bubble.sphere(at, new Vec3(.30 + v.progress * .20, .30 + v.progress * .20, .30 + v.progress * .20),
                        v.time * 110, true, a * .9f);
                burst.sheet(at, .9 + v.progress * .8, .9 + v.progress * .8, v.time * 40, a * .55f);
                ring.ring(at, .45 + v.progress * .3, .05, a * .5f);
            }
            case "buff", "impact" -> {
                Vec3 centre = v.pos.add(0, .85, 0);
                burst.sphere(centre, new Vec3(.85 + v.progress * 1.25, .85 + v.progress * .85, .85 + v.progress * 1.25),
                        v.time * 170, true, a * .9f);
                ring.slab(v.feet().add(0, .07, 0), 2.4 + v.progress * 4.4, 2.4 + v.progress * 4.4, 0, a * .85f);
                ring.ringWave(centre, 1.0, .15, 3, v.time, a * .95f);
                seal.slab(v.feet().add(0, .05, 0), 2.8, 2.8, v.time * 40, a * .5f);
                for (int i = 0; i < 12; i++) {
                    double ang = i * .5236 + v.time * 1.4;
                    double r = .65 + v.progress * 2.5;
                    mote.sheet(v.feet().add(Math.cos(ang) * r, .30 + .55 * (1 - v.progress) + .2 * (i % 3),
                            Math.sin(ang) * r), .34, .34, v.time * 190 + i * 20, a * .8f);
                }
            }
            default -> {
            }
        }
    }
}

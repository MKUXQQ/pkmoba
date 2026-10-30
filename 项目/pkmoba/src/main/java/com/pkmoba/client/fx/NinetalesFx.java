package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Alolan Ninetales 0.98 - Freeze-Dry / Aurora Veil / Blizzard / Tera Blast.
 *
 * <p>The blizzard is built as a real funnel: slanted wind-streak panels wrapped around a stack of
 * narrowing rings, spinning faster towards the top, with snow spiralling up the outside and a
 * rotating ground spiral. The veil is only a glow on the chest (it is a buff). The tera blast fires
 * a beam of light with snow and ice crystals, and bursts into frozen shards.
 */
public final class NinetalesFx {
    private NinetalesFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation DRY = tex("nt_fdry");
    private static final ResourceLocation CURTAIN = tex("nt_veilc");
    private static final ResourceLocation STORM = tex("nt_blizzard");
    private static final ResourceLocation FUNNEL = tex("nt_funnel");
    private static final ResourceLocation TERA = tex("nt_tera");
    private static final ResourceLocation FLAKE = tex("nt_flake");
    private static final ResourceLocation PUFF = tex("nt_puff");
    private static final ResourceLocation GLINT = tex("nt_glint");

    public static void render(FxView v) {
        Fx dry = new Fx(v.fx.context(), DRY);
        Fx curtain = new Fx(v.fx.context(), CURTAIN);
        Fx storm = new Fx(v.fx.context(), STORM);
        Fx funnel = new Fx(v.fx.context(), FUNNEL);
        Fx tera = new Fx(v.fx.context(), TERA);
        Fx flake = new Fx(v.fx.context(), FLAKE);
        Fx puff = new Fx(v.fx.context(), PUFF);
        Fx glint = new Fx(v.fx.context(), GLINT);
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> freezeDry(v, dry, flake, glint, puff, a);
            case 1 -> auroraVeil(v, curtain, glint, flake, a);
            case 2 -> blizzard(v, storm, funnel, flake, glint, puff, a);
            default -> teraBlast(v, tera, dry, flake, glint, puff, a);
        }
    }

    private static Vec3 fwd(FxView v) {
        return v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
    }

    private static void snowfall(Fx flake, Vec3 centre, double radius, double height,
                                 double time, float alpha, int count) {
        for (int i = 0; i < count; i++) {
            double seed = (i * 0.6180339887) % 1.0;
            double ang = time * (0.8 + seed * .6) + i * 1.7;
            double r = radius * (.18 + .82 * seed);
            double fall = ((time * .36 + seed * 5) % 1.0);
            Vec3 p = centre.add(Math.cos(ang) * r, height * (1 - fall) - .35, Math.sin(ang) * r);
            double size = .32 + .16 * seed;
            flake.sheet(p, size, size, time * 40 + i * 37, (float) (alpha * (.4 + .45 * (1 - fall))));
        }
    }

    private static void groundFrost(Fx flake, Fx puff, Vec3 at, double time, float alpha, double radius) {
        puff.groundRing(at.add(0, .05, 0), radius, 3, time, alpha * .7f);
        flake.groundRing(at.add(0, .02, 0), radius * .72, 2, time + 1.1, alpha * .55f);
    }

    /** Q 冷冻干燥: a spinning frozen droplet with a snow wake; bursts into shards on contact. */
    private static void freezeDry(FxView v, Fx dry, Fx flake, Fx glint, Fx puff, float a) {
        Vec3 dir = fwd(v);
        switch (v.phase) {
            case "start" -> {
                Vec3 hand = v.chest().add(dir.scale(.85));
                glint.sheet(hand, 1.4, 1.4, 0, a * .5f);
                dry.sheet(hand, 1.4, 1.4, v.time * 200, a * .9f);
                snowfall(flake, v.pos, 1.2, 1.5, v.time, a * .55f, 8);
                groundFrost(flake, puff, v.pos, v.time, a * .4f, .85);
            }
            case "projectile" -> {
                Vec3 at = v.pos;
                glint.sheet(at, 1.6, 1.6, 0, a * .45f);
                dry.sheet(at, 1.55, 1.55, v.time * 300, a);
                for (int i = 1; i <= 3; i++) {
                    Vec3 back = at.subtract(dir.scale(.45 * i));
                    dry.sheet(back, 1.1 - .2 * i, 1.1 - .2 * i, v.time * 240 - i * 50, a * (.4f - .1f * i));
                    puff.sheet(back, .75 - .12 * i, .75 - .12 * i, 0, a * .25f);
                }
                snowfall(flake, at, .95, 1.2, v.time, a * .45f, 4);
            }
            default -> {
                Vec3 at = v.pos;
                puff.sheet(at, 2.3, 1.9, 0, a * .65f);
                glint.sheet(at, 2.0, 2.0, 0, a * .5f);
                groundFrost(flake, puff, at, v.time, a * .85f, 1.5);
                for (int i = 0; i < 8; i++) {
                    double ang = v.time * 2.0 + i * Math.PI / 4;
                    double out = .3 + .95 * Math.min(1, v.progress * 1.6);
                    Vec3 p = at.add(Math.cos(ang) * out, .22 + .18 * Math.sin(v.time * 5 + i), Math.sin(ang) * out);
                    dry.sheet(p, .58, .58, ang * 57.3 + v.time * 150, a * .7f);
                }
                snowfall(flake, at, 1.7, 1.8, v.time, a * .8f, 11);
            }
        }
    }

    /** W 极光幕: a buff, so it is a soft aurora glow on the chest - no rays, no curtain wall. */
    private static void auroraVeil(FxView v, Fx curtain, Fx glint, Fx flake, float a) {
        Vec3 chest = v.chest();
        double breathe = .82 + .18 * Math.sin(v.time * 2.2);
        glint.sheet(chest, 2.1 * breathe, 2.1 * breathe, 0, a * .38f);
        curtain.sheet(chest, 1.5 * breathe, 1.5 * breathe, v.time * 26, a * .34f);
        glint.sheet(chest, .85 * breathe, .85 * breathe, 0, a * .55f);
        for (int i = 0; i < 6; i++) {
            double ang = v.time * .8 + i * (Math.PI * 2 / 6);
            Vec3 p = v.pos.add(Math.cos(ang) * .78, .75 + .22 * Math.sin(v.time * 1.7 + i), Math.sin(ang) * .78);
            flake.sheet(p, .34, .34, 0, a * .5f);
        }
    }

    /** E 暴风雪: a fixed-size inverted funnel - only the alpha ramps in, the cone never grows. */
    private static void blizzard(FxView v, Fx storm, Fx funnel, Fx flake, Fx glint, Fx puff, float a) {
        Vec3 base = v.pos;
        double appear = Math.min(1.0, v.age / 8.0);
        double height = 4.2;
        puff.sheet(base.add(0, .25, 0), 3.2, 1.5, 0, (float) (a * .35 * appear));
        glint.sheet(base.add(0, .3, 0), 2.4, 1.2, 0, (float) (a * .18 * appear));
        int layers = 14;
        for (int layer = 0; layer < layers; layer++) {
            double t = layer / (double) (layers - 1);
            double y = .2 + t * height;
            double radius = 2.7 - 2.25 * Math.pow(t, .85);
            double spin = v.time * (1.6 + 2.4 * t) + layer * 1.1;
            double sway = .08 * Math.sin(v.time * .9 + layer * .7);
            double alpha = a * appear * (.62 - .28 * t);
            int panels = 8;
            for (int i = 0; i < panels; i++) {
                double ang = spin + i * (Math.PI * 2 / panels);
                Vec3 at = base.add(Math.cos(ang) * radius + sway, y, Math.sin(ang) * radius + sway);
                double w = 1.5 - .45 * t;
                funnel.upright(at, w, .74, Math.toDegrees(ang) + 90, (float) alpha);
            }
        }
        glint.sheet(base.add(0, height * .35, 0), 1.4, height * .5, 0, (float) (a * .18 * appear));
        for (int i = 0; i < 36; i++) {
            double seed = (i * .618) % 1.0;
            double ang = v.time * (2.2 + seed) + i * 1.3;
            double rise = (v.time * .55 + seed * 3) % 1.0;
            double radius = (2.7 - 2.25 * Math.pow(rise, .85)) * (.92 + .12 * seed);
            Vec3 p = base.add(Math.cos(ang) * radius, .25 + rise * height, Math.sin(ang) * radius);
            double size = .34 + .2 * seed;
            flake.sheet(p, size, size, v.time * 60 + i * 41, (float) (a * appear * (.45 + .4 * (1 - rise))));
        }
        storm.groundRing(base, 2.3, 3, v.time, (float) (a * .4 * appear));
        puff.groundRing(base.add(0, .04, 0), 2.7, 2, v.time + .6, (float) (a * .38 * appear));
        puff.sheet(base.add(0, height, 0), 1.9, .8, 0, (float) (a * .3 * appear));
        snowfall(flake, base.add(0, height - .3, 0), 1.1, .9, v.time, (float) (a * .5 * appear), 8);
    }

    /** R 太晶爆发: crystal swells, then a beam of tera light with snow and ice shards. */
    private static void teraBlast(FxView v, Fx tera, Fx dry, Fx flake, Fx glint, Fx puff, float a) {
        Vec3 dir = fwd(v);
        Vec3 start = v.chest().add(0, .45, 0);
        Vec3 end = v.pos.distanceTo(start) > 1.2 ? v.pos : start.add(dir.scale(14));
        switch (v.phase) {
            case "start" -> {
                double swell = .8 + 1.3 * Math.min(1, v.progress * 1.7);
                Vec3 crown = v.head().add(0, .85, 0);
                glint.sheet(crown, 2.4 * swell, 2.4 * swell, 0, a * .42f);
                tera.sheet(crown, 1.4 * swell, 1.7 * swell, v.time * 80, a * .95f);
                for (int i = 0; i < 6; i++) {
                    double ang = v.time * 2.2 + i * (Math.PI * 2 / 6);
                    Vec3 p = crown.add(Math.cos(ang) * .85, .1 * Math.sin(v.time * 5 + i), Math.sin(ang) * .85);
                    (i % 2 == 0 ? glint : flake).sheet(p, .5, .5, v.time * 90 + i * 40, a * .6f);
                }
                snowfall(flake, v.pos, 1.4, 1.9, v.time, a * .5f, 9);
            }
            case "beam" -> {
                tera.beam(start, end, 1.2, a);
                tera.beam(start, end, .55, a * .95f);
                glint.sheet(start, 1.7, 1.7, 0, a * .6f);
                glint.sheet(end, 2.1, 2.1, 0, a * .55f);
                for (int i = 0; i < 7; i++) {
                    double along = .1 + .13 * i;
                    Vec3 p = start.add(end.subtract(start).scale(along));
                    Vec3 side = new Vec3(-dir.z, 0, dir.x).scale((i % 2 == 0 ? 1 : -1) * .34);
                    (i % 2 == 0 ? tera : dry).sheet(p.add(side), .5, .5, v.time * 190 + i * 45, a * .6f);
                    flake.sheet(p.add(0, -.2 + .12 * (i % 3), 0), .4, .4, v.time * 120 + i * 30, a * .45f);
                }
                snowfall(flake, end, 1.5, 1.6, v.time, a * .55f, 8);
            }
            default -> {
                // icy shock: whiteout, frozen shards, snow burst, frost rings
                puff.sheet(end, 3.0, 2.3, 0, a * .75f);
                glint.sheet(end, 2.6, 2.6, 0, a * .6f);
                tera.sheet(end, 1.9, 1.9, v.time * 220, a * .9f);
                groundFrost(flake, puff, end, v.time, a * .85f, 2.0);
                for (int i = 0; i < 10; i++) {
                    double ang = i * Math.PI * 2 / 10 + v.time * .9;
                    double out = .3 + 1.5 * Math.min(1, v.progress * 1.5);
                    Vec3 p = end.add(Math.cos(ang) * out, .28 + .3 * Math.sin(v.time * 4 + i), Math.sin(ang) * out);
                    dry.sheet(p, .7, .7, ang * 57.3 + v.time * 200, a * .75f);
                }
                for (int i = 0; i < 14; i++) {
                    double seed = (i * .37) % 1.0;
                    double ang = i * 1.7 + v.time * 1.2;
                    double out = .4 + 1.8 * seed * Math.min(1, v.progress * 1.4);
                    Vec3 p = end.add(Math.cos(ang) * out, .25 + (.9 * seed) * Math.min(1, v.progress * 1.2),
                            Math.sin(ang) * out);
                    flake.sheet(p, .5 + .2 * seed, .5 + .2 * seed, v.time * 80 + i * 37, a * .8f);
                }
                // 0.104 (m15857): explicit radial burst rays + expanding shock rings so the
                // Tera Blast detonation reads as an explosion, not a puff.
                for (int i = 0; i < 10; i++) {
                    double ang = i * Math.PI * 2 / 10 + .15;
                    Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    double len = 1.4 + 2.6 * Math.min(1, v.progress * 1.6);
                    Vec3 base = end.add(0, .55, 0);
                    glint.beam(base, base.add(out.scale(len)), .34, a * .85f);
                    glint.beam(base, base.add(out.scale(len * .55)).add(0, .5, 0), .22, a * .6f);
                }
                for (int ring = 0; ring < 3; ring++) {
                    double rr = .8 + (v.progress * 3.4) + ring * .55;
                    glint.groundRing(end, rr, 1, v.time + ring * .25, a * (.75f - ring * .18f));
                }
                snowfall(flake, end, 2.2, 2.4, v.time, a * .8f, 12);
            }
        }
    }
}

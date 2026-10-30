package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Tyranitar V5 ("sandstone monolith", m16048).
 *
 * <p>A deliberately different language from every earlier pass: the whole kit is built from
 * chiselled sandstone primitives - isometric cubes for boulders, octagonal bands for shock waves,
 * right-angled lattice cracks for the ground, rectangular pillars for the quake and stacked bars
 * for dust. Nothing here is a round glow or a particle cloud, and every shape is a flat textured
 * quad so the pixels stay crisp.
 */
public final class TyranitarFx {
    private TyranitarFx() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation CUBE = tex("tt5_cube");
    private static final ResourceLocation GRIT = tex("tt5_grid");
    private static final ResourceLocation DUST = tex("tt5_dust");
    private static final ResourceLocation MAW = tex("tt5_maw");
    private static final ResourceLocation OCTA = tex("tt5_octa");
    private static final ResourceLocation CRACK = tex("tt5_crack");
    private static final ResourceLocation PILLAR = tex("tt5_pillar");
    private static final ResourceLocation SPARK = tex("tt5_spark");
    private static final ResourceLocation WALL = tex("tt5_wall");

    public static void render(FxView v) {
        Fx cube = new Fx(v.fx.context(), CUBE);
        Fx grit = new Fx(v.fx.context(), GRIT);
        Fx dust = new Fx(v.fx.context(), DUST);
        Fx maw = new Fx(v.fx.context(), MAW);
        Fx octa = new Fx(v.fx.context(), OCTA);
        Fx crack = new Fx(v.fx.context(), CRACK);
        Fx pillar = new Fx(v.fx.context(), PILLAR);
        Fx spark = new Fx(v.fx.context(), SPARK);
        Fx wall = new Fx(v.fx.context(), WALL);
        switch (v.skill) {
            case 0 -> rockSlide(v, cube, grit, dust, octa, spark);
            case 1 -> crunch(v, maw, grit, spark);
            case 2 -> sandstorm(v, cube, dust, octa, grit);
            default -> earthquake(v, crack, pillar, dust, octa, spark, grit);
        }
    }

    private static Vec3 flat(Vec3 p) {
        return new Vec3(p.x, 0, p.z);
    }

    private static void tumblingCube(Fx cube, Vec3 at, double size, double spin, float alpha) {
        cube.upright(at, size, size, spin, alpha);
        cube.upright(at.add(0, .02, 0), size * .62, size * .62, -spin * 1.7, alpha * .55f);
    }

    private static void rockSlide(FxView v, Fx cube, Fx grit, Fx dust, Fx octa, Fx spark) {
        float a = Math.min(1f, v.fade);
        switch (v.phase) {
            case "start" -> {
                Vec3 base = v.chest().add(v.dir.scale(.8));
                for (int i = 0; i < 3; i++) {
                    double ang = v.time * 2.2 + i * (Math.PI * 2 / 3);
                    double r = .75 + .12 * Math.sin(v.time * 3 + i);
                    Vec3 at = base.add(Math.cos(ang) * r, .35 + .18 * Math.sin(v.time * 2 + i), Math.sin(ang) * r);
                    tumblingCube(cube, at, .55, v.time * 90 + i * 60, a * .95f);
                }
                grit.sheet(base.add(0, .05, 0), 1.3, 1.3, 0, a * .5f);
                octa.slab(v.pos.add(0, .06, 0), 2.0, 2.0, 0, a * .45f);
            }
            case "projectile" -> {
                Vec3 at = v.pos.add(0, .55 + .18 * Math.sin(v.time * 6), 0);
                tumblingCube(cube, at, .95, v.time * 220, a);
                Vec3 back = flat(v.dir);
                if (back.length() > 1.0E-4) back = back.normalize();
                for (int i = 1; i <= 3; i++) {
                    Vec3 trail = at.subtract(back.scale(i * .55));
                    tumblingCube(cube, trail, .5 - i * .09, v.time * 160 + i * 40, a * (.6f - i * .13f));
                }
                grit.sheet(at, .9, .9, v.time * 90, a * .5f);
            }
            case "impact" -> {
                double grow = Math.min(1.0, v.progress * 2.2);
                for (int ring = 0; ring < 2; ring++) {
                    double size = 1.4 + grow * (2.6 + ring * 1.1);
                    octa.slab(v.pos.add(0, .05 + ring * .01, 0), size, size, ring * 22, (float) (a * (.8 - ring * .25)));
                }
                for (int i = 0; i < 6; i++) {
                    double ang = i * Math.PI * 2 / 6 + .4;
                    double r = .5 + grow * 2.1;
                    Vec3 at = v.pos.add(Math.cos(ang) * r, .25 + grow * .6, Math.sin(ang) * r);
                    tumblingCube(cube, at, .45 + .25 * (1 - grow), v.time * 200 + i * 55, (float) (a * (1 - grow * .7)));
                }
                spark.sheet(v.pos.add(0, .6, 0), 2.4, 2.4, v.time * 30, a);
                dust.slab(v.pos.add(0, .12, 0), 3.4, 3.4, 0, a * .55f);
            }
            default -> {
            }
        }
    }

    private static void crunch(FxView v, Fx maw, Fx grit, Fx spark) {
        float a = Math.min(1f, v.fade);
        switch (v.phase) {
            case "start", "attack" -> {
                Vec3 at = v.chest().add(v.dir.scale(1.25));
                double open = .9 + .55 * Math.sin(v.time * 7);
                maw.upright(at, 2.1, 1.5 * open, 0, a * .95f);
                grit.sheet(at.add(0, -.35, 0), 1.1, .8, v.time * 40, a * .4f);
            }
            case "impact", "hit" -> {
                Vec3 at = v.pos.add(0, .7, 0);
                maw.upright(at, 2.4, 1.2, 90, a);
                maw.upright(at, 1.5, 2.0, 0, a * .55f);
                spark.sheet(at, 2.0, 2.0, v.time * 60, a * .9f);
                grit.sheet(at.add(0, -.3, 0), 1.6, 1.2, v.time * 70, a * .6f);
            }
            default -> {
            }
        }
    }

    /** E 沙暴: six big stone cubes orbit the caster and slam into anything they touch. */
    private static void sandstorm(FxView v, Fx cube, Fx dust, Fx octa, Fx grit) {
        float a = Math.min(1f, v.fade);
        Vec3 base = v.chest().add(0, .55, 0);
        int blocks = 6;
        for (int i = 0; i < blocks; i++) {
            double ang = v.time * .85 + i * (Math.PI * 2 / blocks);
            double r = 2.55;
            Vec3 at = base.add(Math.cos(ang) * r, .18 * Math.sin(v.time * 2 + i), Math.sin(ang) * r);
            double size = 1.15 + .12 * Math.sin(v.time * 3 + i);
            tumblingCube(cube, at, size, v.time * 55 + i * 60, a);
        }
        octa.slab(v.pos.add(0, .05, 0), 5.4, 5.4, v.time * 20, a * .45f);
        if (v.phase.equals("hit") || v.phase.equals("pulse")) {
            dust.slab(v.pos.add(0, .18, 0), 3.0, 3.0, 0, a * .5f);
        }
    }

    /** R 地震: the floor behaves like a struck drum - plates ripple outward from the epicentre. */
    private static void earthquake(FxView v, Fx crack, Fx pillar, Fx dust, Fx octa, Fx spark, Fx grit) {
        float a = Math.min(1f, v.fade);
        double spread = Math.min(1.0, v.progress * 2.2);
        float punch = (float) Math.pow(1 - Math.min(.999, v.progress), 1.35);
        Vec3 centre = v.pos;

        crack.slab(centre.add(0, .05, 0), 3.0 + spread * 9.0, 3.0 + spread * 9.0, 0, a * .8f);

        // four concentric plate rings, each lifting and falling as the wave passes through it
        for (int ring = 0; ring < 4; ring++) {
            double radius = 1.3 + ring * 1.5 + spread * 2.2;
            double phase = spread * 6.0 - ring * 1.15;
            double lift = Math.sin(phase) * (.30 + .22 * punch);
            double plate = 2.0 + ring * .55;
            dust.slab(centre.add(0, .10 + lift, 0), plate, plate, ring * 17, a * (.85f - ring * .13f));
            octa.slab(centre.add(0, .16 + lift, 0), plate * 1.02, plate * 1.02, ring * 17 + 45, a * (.55f - ring * .09f));
            if (ring < 3) {
                for (int i = 0; i < 4; i++) {
                    double ang = i * Math.PI / 2 + ring * .55 + v.time * .6;
                    Vec3 at = centre.add(Math.cos(ang) * radius, 0, Math.sin(ang) * radius);
                    grit.sheet(at.add(0, .12, 0), .9, .9, v.time * 40, a * .45f);
                }
            }
        }

        spark.sheet(centre.add(0, .55, 0), 2.6 + 1.4 * punch, 2.6 + 1.4 * punch, v.time * 30, a * (.55f + .45f * punch));
        if (v.phase.equals("impact")) {
            octa.slab(centre.add(0, .12, 0), 9.5, 9.5, 0, a * .8f);
            dust.slab(centre.add(0, .28, 0), 7.0, 7.0, 0, a * .5f);
        }
    }
}

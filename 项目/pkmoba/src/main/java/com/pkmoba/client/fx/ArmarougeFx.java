package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * 0.135 Armarouge (红莲铠骑) visuals.
 *
 * <p>Two palettes only: fire (deep red shell, orange body, white hot core) for the cannon, the dash
 * and the bolts, psychic (deep violet field, lilac core) for Psyshock and Expanding Force. Every beat
 * is a stack of small additive layers - core shape, casing glow, spin layer, trailing shards, ground
 * burn and a few glints for direction - so the kit reads as layered light instead of one sticker.
 */
public final class ArmarougeFx {
    private ArmarougeFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation BOLT = tex("ar_bolt");
    private static final ResourceLocation BOLT_HEAVY = tex("ar_bolt_heavy");
    private static final ResourceLocation PSY = tex("ar_psy");
    private static final ResourceLocation FLAME = tex("ar_flame");
    private static final ResourceLocation CANNON = tex("ar_cannon");
    private static final ResourceLocation BOOM = tex("ar_boom");
    private static final ResourceLocation RUNE = tex("ar_rune");
    private static final ResourceLocation PULSE = tex("ar_pulse");
    private static final ResourceLocation MUZZLE = tex("ar_muzzle");
    private static final ResourceLocation SCORCH = tex("ar_scorch");
    private static final ResourceLocation GLYPH = tex("ar_glyph");
    private static final ResourceLocation SPARK = tex("ar_spark");
    private static final ResourceLocation SMOKE = tex("ar_smoke");

    public static void render(FxView v) {
        Fx bolt = new Fx(v.fx.context(), BOLT);
        Fx heavy = new Fx(v.fx.context(), BOLT_HEAVY);
        Fx psy = new Fx(v.fx.context(), PSY);
        Fx flame = new Fx(v.fx.context(), FLAME);
        Fx cannon = new Fx(v.fx.context(), CANNON);
        Fx boom = new Fx(v.fx.context(), BOOM);
        Fx rune = new Fx(v.fx.context(), RUNE);
        Fx pulse = new Fx(v.fx.context(), PULSE);
        Fx muzzle = new Fx(v.fx.context(), MUZZLE);
        Fx scorch = new Fx(v.fx.context(), SCORCH);
        Fx glyph = new Fx(v.fx.context(), GLYPH);
        Fx spark = new Fx(v.fx.context(), SPARK);
        Fx smoke = new Fx(v.fx.context(), SMOKE);
        float a = v.fade;
        Vec3 dir = v.dir;
        double t = v.time;
        boolean psychic = v.skill == 0 || v.skill == 3;
        switch (v.phase) {
            // --- windup: energy is pulled in, and the floor answers first ---------------------
            case "start" -> {
                double charge = .18 + .38 * v.progress;
                glyph.slab(v.feet(), 1.1 + charge, .86 + charge * .7, t * .5, a * .75f);
                rune.slab(v.feet(), .8 + charge * .9, .62 + charge * .6, -t * .7, a * .5f);
                pulse.ringWave(v.feet().add(0, .06, 0), .7 + charge, .09, 2, t, a * .55f);
                (psychic ? psy : bolt).motes(v.feet().add(0, .1, 0), 1.2, 10, .16, t, a * .8f);
                muzzle.sheet(v.hand(), .55 + charge * .5, .55 + charge * .5, t * 8, a * .8f);
                muzzle.sheet(v.offHand(), .45 + charge * .4, .45 + charge * .4, -t * 8, a * .6f);
                spark.glow(v.hand(), .55 + charge * .6, a * .30f);
                spark.glow(v.offHand(), .45 + charge * .4, a * .22f);
            }
            // --- release: muzzle star, launch streaks and a floor ring -----------------------
            case "release" -> {
                Vec3 o = v.hand();
                muzzle.sheet(o, 1.5, 1.5, 0, a);
                muzzle.sheet(o, 2.1, 2.1, t * 5, a * .45f);
                spark.starBurst(o, 1.5, 8, t, a * .8f);
                launchStreaks(bolt, o, dir, t, a * .85f);
                scorch.ringWave(v.feet(), 1.0, .10, 2, t, a * .5f);
                glyph.slab(v.feet(), 1.8, 1.2, t * .9, a * .55f);
                Fx lance = psychic ? psy : bolt;
                Vec3 tip = o.add(dir.scale(2.2));
                lance.beam(o, tip, .16, a * .8f);
                lance.beam(o, tip, .34, a * .3f);
            }
            case "melee" -> {
                Vec3 o = v.chest();
                muzzle.sheet(v.front(.7), 1.4, 1.4, 0, a);
                boom.ringWave(o, 1.1, .14, 2, t, a * .6f);
                spark.starBurst(o, 1.2, 6, t, a * .7f);
                flameStack(flame, o.add(0, -.2, 0), .9, t, a);
            }
            // --- Q 精神冲击: head, casing, spin, trailing shards ------------------------------
            case "psy" -> {
                psy.upright(v.pos, 1.15, 1.15, t * 10, a);
                psy.sheet(v.pos, .8, .8, -t * 7, a * .5f);
                spark.glow(v.pos, .9, a * .35f);
                pulse.ring(v.pos, .55 + v.progress * .25, .10, a * .55f);
                for (int i = 1; i <= 3; i++) {
                    Vec3 back = v.pos.subtract(dir.scale(.30 * i));
                    psy.upright(back, .55 - i * .1, .55 - i * .1, -t * 8 - i, a * (.55f / i));
                    spark.sheet(back.add(Math.sin(t + i) * .22, 0, Math.cos(t + i) * .22), .18, .18,
                            t * 6, a * .4f);
                }
                glyph.sheet(v.pos.add(0, .05, 0), .9, .9, t * 4, a * .35f);
                if (v.progress > .5) pulse.ringWave(v.pos, .7, .09, 2, t, a * .4f);
            }
            // --- W 火焰旋涡: jets, trail, heat shimmer, burnt ground -------------------------
            case "flame" -> {
                flameStack(flame, v.feet(), 1.5, t, a);
                flameStack(flame, v.chest(), 1.1, t, a * .8f);
                for (int i = 1; i <= 4; i++) {
                    Vec3 back = v.feet().add(dir.scale(-.42 * i));
                    flame.sheet(back, .95 - i * .15, .75 - i * .12, 0, a * (.6f - i * .12f));
                    if (i <= 3) smoke.sheet(back.add(0, .35, 0), .8 + i * .1, .6 + i * .1, t * 2, a * .28f);
                }
                spark.burst(v.feet().add(dir.scale(-.3)), .8, 6, .5, t, a * .6f);
                scorch.slab(v.feet().add(0, .02, 0), 1.0, .72, 0, a * .5f);
                spark.glow(v.feet().add(dir.scale(-.6)), .9, a * .28f);
            }
            // --- E 铠甲炮: shell, hot core, smoke sleeve, ember wake -------------------------
            case "cannon" -> {
                cannon.upright(v.pos, 1.25, 1.25, t * 9, a);
                cannon.sheet(v.pos, 1.5, 1.5, -t * 6, a * .5f);
                spark.glow(v.pos, 1.2 + .2 * Math.sin(t * .8), a * .42f);
                smoke.sheet(v.pos.subtract(dir.scale(.45)), 1.0, .9, t * 3, a * .55f);
                smoke.sheet(v.pos.subtract(dir.scale(.95)), .8, .7, -t * 2, a * .35f);
                for (int i = 0; i < 3; i++) {
                    double ang = t * .9 + i * 2.09;
                    bolt.sheet(v.pos.add(Math.cos(ang) * .55, Math.sin(ang) * .4, Math.sin(ang) * .55),
                            .3, .3, 0, a * .65f);
                    spark.sheet(v.pos.add(Math.cos(ang + 1) * .7, Math.sin(ang + 1) * .35,
                            Math.sin(ang + 1) * .7), .2, .2, 0, a * .5f);
                }
                pulse.ring(v.pos, .8, .08, a * .4f);
            }
            // --- R 扩展力量: the field opens before anything lands ----------------------------
            case "rune" -> {
                double grow = v.progress;
                glyph.slab(v.pos, 2.2 + grow * 3.0, 1.6 + grow * 2.2, t * .5, a * .9f);
                rune.slab(v.pos, 2.6 + grow * 3.2, 1.9 + grow * 2.4, -t * .35, a * .6f);
                pulse.ringWave(v.pos.add(0, .08, 0), 1.4 + grow * 1.8, .12, 2, t, a * .55f);
                psy.motes(v.pos.add(0, .1, 0), 2.4, 14, .18, t, a * .8f);
                for (int i = 0; i < 4; i++) {
                    double ang = t * .4 + i * 1.57;
                    glyph.sheet(v.pos.add(Math.cos(ang) * (2.0 + grow), .35,
                            Math.sin(ang) * (2.0 + grow)), .7, .7, -t * 2, a * .45f);
                }
            }
            case "pulse1", "pulse2", "pulse3", "pulse4", "pulse5" -> {
                int index = v.phase.charAt(5) - '0';
                double grow = 1.0 + index * .32 + v.progress * 1.1;
                pulse.slab(v.pos, grow * 1.7, grow * 1.2, t * .9, a * .85f);
                pulse.ringWave(v.pos.add(0, .10, 0), grow, .16, 2, t, a);
                rune.slab(v.pos, grow * 2.0, grow * 1.4, -t * .6, a * .4f);
                glyph.slab(v.pos, grow * 2.3, grow * 1.6, t * .5, a * .35f);
                psy.motes(v.pos.add(0, .15, 0), grow * 1.6, 8, .2, t, a * .7f);
                if (index >= 3) boom.ringWave(v.pos, grow * 1.15, .13, 3, t, a * .55f);
                if (index >= 4) spark.starBurst(v.pos, grow * 1.3, 8, t, a * .6f);
            }
            // --- basic attack bolts -----------------------------------------------------------
            case "shot1" -> boltHead(v, bolt, spark, .6, a);
            case "shot2" -> boltHead(v, heavy, spark, .78, a);
            case "shot3" -> boltHead(v, heavy, spark, 1.0, a);
            case "shot_hit" -> {
                muzzle.sheet(v.pos, 1.0, 1.0, 0, a);
                boom.sheet(v.pos, 1.3, 1.3, 0, a * .8f);
                boom.ringWave(v.pos, .85, .12, 2, t, a * .6f);
                spark.starBurst(v.pos, 1.1, 6, t, a * .8f);
                for (int i = 0; i < 4; i++) {
                    double ang = i * 1.57 + .4;
                    bolt.sheet(v.pos.add(Math.cos(ang) * .5, Math.sin(ang) * .32, Math.sin(ang) * .5),
                            .32, .32, 0, a * .7f);
                }
            }
            // --- impacts ---------------------------------------------------------------------
            case "quake" -> {
                boom.sheet(v.pos, 3.0, 3.0, 0, a);
                boom.sheet(v.pos, 2.2, 2.2, t * 2, a * .6f);
                boom.ringWave(v.pos, 2.4, .18, 4, t, a * .85f);
                pulse.slab(v.pos, 4.0, 2.8, t, a * .55f);
                glyph.slab(v.pos, 3.6, 2.6, -t * .8, a * .5f);
                spark.starBurst(v.pos, 2.6, 10, t, a * .9f);
                for (int i = 0; i < 10; i++) {
                    double ang = i * .628 + t * .3;
                    boom.sheet(v.pos.add(Math.cos(ang) * (1.7 + v.progress * 2.2), .35,
                            Math.sin(ang) * (1.7 + v.progress * 2.2)), .55, .55, 0, a * .55f);
                }
                scorch.slab(v.pos.add(0, .02, 0), 3.0 * (1 + v.progress), 2.2 * (1 + v.progress), 0, a * .65f);
                smoke.sheet(v.pos.add(0, .4, 0), 2.6, 1.8, t, a * .3f);
            }
            case "impact" -> {
                muzzle.sheet(v.pos, 2.0, 2.0, t * 6, a);
                boom.sheet(v.pos, 1.8, 1.8, 0, a * .9f);
                boom.ringWave(v.pos, 1.35, .14, 3, t, a * .7f);
                spark.starBurst(v.pos, 1.5, 8, t, a * .85f);
                for (int i = 0; i < 6; i++) {
                    double ang = i * 1.047 + .2;
                    bolt.sheet(v.pos.add(Math.cos(ang) * (1.1 + v.progress * .9), .22,
                            Math.sin(ang) * (1.1 + v.progress * .9)), .5, .5, 0, a * .6f);
                }
                smoke.sheet(v.pos.add(0, .25, 0), 1.6, 1.1, t * 1.5, a * .35f);
                scorch.slab(v.pos.add(0, .02, 0), 1.6, 1.2, 0, a * .45f);
            }
            default -> muzzle.sheet(v.pos, .7, .7, 0, a * .45f);
        }
    }

    // ----------------------------------------------------------------------------------------
    // stacked layer helpers - each one only adds a layer, it never replaces another
    // ----------------------------------------------------------------------------------------

    private static void boltHead(FxView v, Fx body, Fx spark, double size, float a) {
        body.upright(v.pos, size, size, v.time * 12, a);
        body.sheet(v.pos, size * 1.6, size * 1.6, -v.time * 6, a * .4f);
        spark.starBurst(v.pos, size * 1.4, 4, v.time, a * .7f);
        for (int i = 1; i <= 2; i++) {
            body.sheet(v.pos.subtract(v.dir.scale(.32 * i)), size * (.55 - i * .12),
                    size * (.55 - i * .12), 0, a * (.5f / i));
        }
        spark.glow(v.pos, size * 1.5, a * .3f);
    }

    private static void flameStack(Fx flame, Vec3 at, double scale, double time, float a) {
        for (int i = 0; i < 3; i++) {
            double sway = Math.sin(time * .7 + i * 2.1) * .12;
            flame.upright(at.add(sway, .25 + i * .18, Math.cos(time * .6 + i) * .12),
                    scale * (.8 - i * .16), scale * (1.0 - i * .18), time * 3 + i * 40,
                    a * (.75f - i * .18f));
        }
        flame.flames(at, scale * .8, 6, time, a * .7f);
    }

    private static void launchStreaks(Fx bolt, Vec3 from, Vec3 dir, double time, float a) {
        for (int i = 0; i < 3; i++) {
            double ang = i * 1.2 + time * .4;
            Vec3 side = new Vec3(Math.cos(ang), Math.sin(ang) * .5, Math.sin(ang));
            Vec3 to = from.add(dir.scale(1.4)).add(side.scale(.35));
            bolt.streak(from, to, .10 - i * .02, a * (.8f - i * .2f));
        }
    }
}

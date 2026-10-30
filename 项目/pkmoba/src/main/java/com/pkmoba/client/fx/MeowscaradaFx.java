package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Meowscarada (magic-cat assassin) 2.0 visuals.
 *
 * <p>Four separate languages so the buttons never blur together: sharp spinning leaves for the
 * homing Q, deep-green afterimages and a cross cut for the U-turn, a pink-and-gold bloom for the
 * guaranteed-crit Flower Trick, and a layered petal vortex standing on a ground circle for the
 * Petal Blizzard.
 */
public final class MeowscaradaFx {
    private MeowscaradaFx() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation LEAF = tex("mw2_leaf");
    private static final ResourceLocation CUT = tex("mw2_cut");
    private static final ResourceLocation FLOWER = tex("mw2_flower");
    private static final ResourceLocation BLOOM = tex("mw2_bloom");
    private static final ResourceLocation PETAL = tex("mw2_petal");
    private static final ResourceLocation STORM = tex("mw2_storm");
    private static final ResourceLocation RING = tex("mw2_ring");
    private static final ResourceLocation GLOW = tex("mw2_glow");

    public static void render(FxView v) {
        Fx leaf = new Fx(v.fx.context(), LEAF);
        Fx cut = new Fx(v.fx.context(), CUT);
        Fx flower = new Fx(v.fx.context(), FLOWER);
        Fx bloom = new Fx(v.fx.context(), BLOOM);
        Fx petal = new Fx(v.fx.context(), PETAL);
        Fx storm = new Fx(v.fx.context(), STORM);
        Fx ring = new Fx(v.fx.context(), RING);
        Fx glow = new Fx(v.fx.context(), GLOW);
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> magicalLeaf(v, leaf, cut, petal, glow, a);
            case 1 -> uTurn(v, cut, petal, glow, a);
            case 2 -> flowerTrick(v, flower, bloom, petal, ring, glow, a);
            default -> petalBlizzard(v, storm, petal, ring, glow, a);
        }
    }

    /** Q 魔法叶: leaves are spun off the paw one at a time and then bend onto the lock-on. */
    private static void magicalLeaf(FxView v, Fx leaf, Fx cut, Fx petal, Fx glow, float a) {
        if (v.phase.equals("start") || v.phase.equals("attack")) {
            Vec3 paw = v.hand().add(v.dir.scale(.35)).add(0, .18, 0);
            glow.glow(paw, .75 + v.progress * .35, a * .75f);
            leaf.sheet(paw, .8, .8, v.time * 220, a * .95f);
            leaf.sheet(paw.add(0, .1, 0), .5, .5, -v.time * 260, a * .55f);
            petal.orbit(paw, .55, 4, .18, v.time, a * .6f, .35);
            return;
        }
        if (v.phase.startsWith("leaf") && !v.phase.equals("leafHit") && !v.phase.equals("leafFade")) {
            // a leaf, not a blur: it keeps its silhouette and only flutters a little
            double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
            double flutter = Math.sin(v.time * 6.5) * 16 + v.progress * 40;
            leaf.sheet(v.pos, .95, .95, yaw + flutter, a);
            leaf.sheet(v.pos.subtract(v.dir.scale(.42)), .62, .62, yaw + flutter - 8, a * .45f);
            petal.streak(v.pos.subtract(v.dir.scale(.9)), v.pos, .2, a * .45f);
            glow.glow(v.pos, .38, a * .35f);
            petal.sheet(v.pos.add(Math.sin(v.time * 5) * .25, -0.2, Math.cos(v.time * 5) * .25),
                    .22, .22, yaw + v.time * 120, a * .5f);
            return;
        }
        switch (v.phase) {
            case "leafHit" -> {
                cut.sheet(v.pos, 1.15, 1.15, v.time * 320, a);
                petal.pixels(v.pos, .55, 6, .16, v.time, a * .8f);
                glow.glow(v.pos, .55, a * .5f);
            }
            case "leafFade" -> {
                leaf.sheet(v.pos, .55, .55, Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z)) + v.progress * 30, a * .4f);
                petal.pixels(v.pos, .35, 3, .12, v.time, a * .3f);
            }
            default -> {
            }
        }
    }

    /** W 急速折返: a green wake behind the real dash, a cross cut on contact, a sprint aura. */
    private static void uTurn(FxView v, Fx cut, Fx petal, Fx glow, float a) {
        switch (v.phase) {
            case "start" -> {
                Vec3 feet = v.feet();
                glow.glow(feet.add(0, .25, 0), 1.0 + v.progress * .5, a * .7f);
                petal.orbit(feet.add(0, .35, 0), .9, 5, .2, v.time, a * .65f, .6);
                petal.groundRing(feet.add(0, .06, 0), .8, 2, v.time, a * .45f);
            }
            case "trail", "melee", "attack" -> {
                Vec3 body = v.pos.add(0, .85, 0);
                double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
                // afterimages: three staggered ghosts that fade backwards along the dash
                for (int i = 0; i < 3; i++) {
                    Vec3 ghost = v.pos.subtract(v.dir.scale(.45 + i * .55)).add(0, .9, 0);
                    glow.sheet(ghost, .95 - i * .18, 1.15 - i * .18, yaw, a * (.42f - i * .1f));
                }
                petal.streak(v.pos.subtract(v.dir.scale(2.4)).add(0, .55, 0), v.pos.add(0, .55, 0), .3, a * .55f);
                // leaves kicked up along the ground behind the dash
                petal.pixels(v.pos.add(0, .08, 0), 1.25, 9, .2, v.time, a * .8f);
                petal.slab(v.pos.subtract(v.dir.scale(.9)).add(0, .07, 0), .9, 1.6, yaw, a * .35f);
                petal.groundRing(v.pos.add(0, .06, 0), 1.1, 2, v.time, a * .4f);
                if (v.phase.equals("melee")) cut.sheet(body.add(0, .2, 0), 1.5, 1.5, yaw + 40, a * .9f);
            }
            case "cut" -> {
                Vec3 at = v.pos.add(0, .1, 0);
                double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
                cut.sheet(at, 1.7, 1.7, yaw + 30 + v.progress * 40, a);
                cut.sheet(at, 1.1, 1.1, yaw - 60, a * .6f);
                petal.burst(at, .6, 8, .35, v.time, a * .8f);
                glow.glow(at, .7, a * .5f);
            }
            case "blink" -> {
                Vec3 feet = v.feet();
                double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
                glow.ringWave(feet.add(0, .3, 0), 1.5, .12, 2, v.time, a * .85f);
                glow.glow(feet.add(0, .6, 0), 1.2, a * .5f);
                petal.streak(feet.subtract(v.dir.scale(6)).add(0, .45, 0), feet.add(0, .45, 0), .5, a * .5f);
                for (int i = 0; i < 4; i++) {
                    Vec3 ghost = feet.subtract(v.dir.scale(i * .5)).add(0, .75, 0);
                    glow.sheet(ghost, .9 - i * .15, 1.1 - i * .15, yaw, a * (.35f - i * .07f));
                }
                petal.pixels(feet.add(0, .35, 0), 1.2, 10, .2, v.time, a * .8f);
                petal.groundRing(feet.add(0, .06, 0), 1.4, 2, v.time, a * .5f);
            }
            case "haste" -> {
                Vec3 feet = v.pos.add(0, .12, 0);
                glow.ringWave(feet, .9, .08, 2, v.time, a * .8f);
                petal.orbit(v.pos.add(0, .7, 0), 1.0, 6, .22, v.time, a * .7f, .5);
                glow.glow(v.pos.add(0, .8, 0), 1.1, a * .35f);
            }
            case "impact" -> {
                Vec3 at = v.pos.add(0, .5, 0);
                petal.pixels(at, 1.0, 8, .22, v.time, a * .6f);
                petal.groundRing(v.pos.add(0, .06, 0), 1.3, 2, v.time, a * .45f);
            }
            default -> {
            }
        }
    }

    /** E 千变万花: a real flying flower, blooming into a crit petal storm. */
    private static void flowerTrick(FxView v, Fx flower, Fx bloom, Fx petal, Fx ring, Fx glow, float a) {
        switch (v.phase) {
            case "start" -> {
                Vec3 paw = v.hand().add(v.dir.scale(.5));
                flower.sheet(paw, .7 + v.progress * .5, .7 + v.progress * .5, v.time * 180, a * .95f);
                glow.glow(paw, .8, a * .5f);
                petal.orbit(paw, .7, 5, .22, v.time, a * .6f, .4);
            }
            case "flower", "projectile", "lob" -> {
                double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
                flower.sheet(v.pos, .95, .95, v.time * 260, a);
                glow.glow(v.pos, .6, a * .45f);
                for (int i = 0; i < 3; i++) {
                    double ang = v.time * 3 + i * 2.094;
                    petal.sheet(v.pos.add(Math.cos(ang) * .5, .18 * Math.sin(v.time * 4 + i), Math.sin(ang) * .5),
                            .26, .26, yaw + ang * 30, a * .65f);
                }
                petal.streak(v.pos.subtract(v.dir.scale(1.1)), v.pos, .28, a * .4f);
            }
            case "bloom", "impact", "hit", "melee" -> {
                Vec3 at = v.pos;
                double grow = .8 + v.progress * 1.5;
                bloom.slab(at.add(0, .1, 0), 4.2 * grow, 4.2 * grow, v.time * 20, a * .9f);
                flower.sheet(at.add(0, .5, 0), 1.9 * grow, 1.9 * grow, v.time * 140, a * .95f);
                ring.ringWave(at.add(0, .18, 0), 1.1, .1, 3, v.time, a * .8f);
                petal.rain(at.add(0, .3, 0), 3.0, 2.6, 16, .3, v.time, a * .9f);
                glow.burst(at.add(0, .6, 0), 1.2, 10, .45, v.time, a * .6f);
            }
            case "petalHit" -> {
                petal.burst(v.pos, .5, 6, .3, v.time, a * .7f);
                glow.glow(v.pos, .45, a * .4f);
            }
            default -> {
            }
        }
    }

    /** R 落英缤纷: charge-up, then a layered petal vortex over a marked ground circle. */
    private static void petalBlizzard(FxView v, Fx storm, Fx petal, Fx ring, Fx glow, float a) {
        switch (v.phase) {
            case "start" -> {
                // petals gather into the caster's raised hands while the aim circle is drawn
                Vec3 body = v.chest();
                petal.orbit(body, 1.0 + v.progress * .5, 8, .2, v.time, a * .7f, .4);
                glow.glow(body, 1.2, a * .35f);
                petal.groundRing(v.pos.add(0, .06, 0), 4.0, 2, v.time, a * .55f);
                ring.slab(v.pos.add(0, .08, 0), 8.0, 8.0, 0, a * .35f);
            }
            case "charge" -> {
                // petals spiral in toward the caster, more of them the longer the windup runs
                int count = 4 + (int) (v.progress * 8);
                petal.orbit(v.chest().add(v.dir.scale(.35)), 1.1 - v.progress * .5, count,
                        .18, v.time, a * .8f, .35);
                glow.glow(v.chest().add(v.dir.scale(.35)), .7 + v.progress * .6, a * .4f);
                petal.groundRing(v.pos.add(0, .06, 0), 4.0, 2, v.time, a * .5f);
            }
            case "pulse" -> {
                Vec3 at = v.pos;
                // dense core, sparse rim: three counter-rotating storm sheets at different scales
                storm.slab(at.add(0, .2, 0), 7.6, 7.6, v.time * 55, a * .8f);
                storm.sheet(at.add(0, .9, 0), 4.0, 4.0, -v.time * 120, a * .55f);
                petal.groundRing(at.add(0, .07, 0), 4.0, 3, v.time, a * .7f);
                ring.ringWave(at.add(0, .3, 0), 1.6, .12, 3, v.time, a * .6f);
                for (int i = 0; i < 12; i++) {
                    double ang = v.time * 2.2 + i * .5236;
                    double radius = .7 + (i % 4) * .8;
                    petal.sheet(at.add(Math.cos(ang) * radius, .3 + .5 * Math.sin(v.time * 3 + i),
                                    Math.sin(ang) * radius),
                            (.24 + (i % 3) * .07), (.24 + (i % 3) * .07), ang * 60 + v.time * 200, a * .8f);
                }
                glow.glow(at.add(0, .45, 0), 2.0, a * .25f);
            }
            case "petalHit" -> {
                petal.burst(v.pos, .45, 5, .3, v.time, a * .7f);
                glow.glow(v.pos, .4, a * .35f);
            }
            case "fade", "impact" -> {
                Vec3 at = v.pos;
                storm.slab(at.add(0, .2, 0), 7.0, 7.0, v.time * 40, a * .4f);
                petal.rain(at.add(0, .4, 0), 3.6, 2.4, 10, .26, v.time, a * .45f);
                petal.groundRing(at.add(0, .07, 0), 4.0, 2, v.time, a * .35f);
            }
            default -> {
            }
        }
    }
}

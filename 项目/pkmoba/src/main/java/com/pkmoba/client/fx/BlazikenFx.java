package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Blaziken V5 (m16146): rebuilt from scratch as a "fighter made of light".
 *
 * <p>Every effect is a flat fire shape with a white-hot core - punch flash, crescent arc, spinning
 * ring, motion streaks, impact flash and a skyward pillar. No smoke clouds, no dot particles and
 * none of the earlier fire-foot / fire-wall language.
 */
public final class BlazikenFx {
    private BlazikenFx() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation FIST = tex("bz5_slash");
    private static final ResourceLocation ARC = tex("bz5_crescent");
    private static final ResourceLocation RING = tex("bz5_ring");
    private static final ResourceLocation TRAIL = tex("bz5_trail");
    private static final ResourceLocation SPARK = tex("bz5_star");
    private static final ResourceLocation PILLAR = tex("bz5_pillar");

    public static void render(FxView v) {
        Fx fist = new Fx(v.fx.context(), FIST);
        Fx arc = new Fx(v.fx.context(), ARC);
        Fx ring = new Fx(v.fx.context(), RING);
        Fx spark = new Fx(v.fx.context(), SPARK);
        Fx pillar = new Fx(v.fx.context(), PILLAR);
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> blazeKick(v, fist, arc, spark, a);
            case 1 -> skyUppercut(v, pillar, arc, spark, a);
            case 2 -> highJumpKick(v, ring, arc, spark, a);
            default -> flareBlitz(v, arc, spark, pillar, a);
        }
    }

    /** Q 火焰踢: a flame-wrapped kick, drawn as a boot-shaped arc + impact flash. */
    private static void blazeKick(FxView v, Fx fist, Fx arc, Fx spark, float a) {
        Vec3 front = v.chest().add(v.dir.scale(1.1));
        switch (v.phase) {
            case "start", "attack" -> {
                arc.sheet(front.add(0, .15, 0), 1.6, .9, 22, a * .9f);
                fist.sheet(front.add(0, -.25, 0), 1.1, 1.1, 30, a * .8f);
                spark.sheet(front.add(v.dir.scale(.8)), 1.4, 1.4, v.time * 45, a * .5f);
            }
            case "spark", "brand", "impact", "melee" -> {
                spark.sheet(v.pos, 2.4, 2.4, v.time * 60, a);
                arc.sheet(v.pos, 1.5, .8, -v.time * 80, a * .85f);
            }
            default -> {
            }
        }
    }

    /** W 冲天拳: a rising column of fire under the fist. */
    private static void skyUppercut(FxView v, Fx pillar, Fx arc, Fx spark, float a) {
        Vec3 front = v.chest().add(v.dir.scale(1.0));
        switch (v.phase) {
            case "start", "attack" -> {
                pillar.sheet(front.add(0, .8, 0), 1.0, 2.0, 0, a * .85f);
                arc.sheet(front.add(0, .3, 0), 1.4, .8, -60, a * .85f);
            }
            case "spark", "brand", "impact", "melee" -> {
                spark.sheet(v.pos.add(0, .6, 0), 2.8, 2.8, v.time * 55, a);
                pillar.sheet(v.pos.add(0, 1.0, 0), 1.2, 2.4, 0, a * .9f);
                for (int i = 0; i < 3; i++)
                    arc.sheet(v.pos.add(0, .8 + i * .5, 0), 1.1, .55, i * 120, a * (.7f - i * .15f));
            }
            default -> {
            }
        }
    }

    /** E 飞膝踢: leap up, then a straight knee-drive into the point with a ring on arrival. */
    private static void highJumpKick(FxView v, Fx ring, Fx arc, Fx spark, float a) {
        switch (v.phase) {
            case "start", "rise" -> {
                arc.sheet(v.pos.add(0, 1.1, 0), 1.3, .7, -40, a * .7f);
            }
            case "charge" -> {
                arc.sheet(v.pos.add(0, .3, 0), 1.4, .8, v.time * 90, a * .8f);
            }
            case "impact", "hit" -> {
                ring.slab(v.pos.add(0, .05, 0), 3.4, 3.4, v.time * 200, a);
                spark.sheet(v.pos.add(0, .7, 0), 2.8, 2.8, v.time * 60, a);
                arc.sheet(v.pos.add(0, .5, 0), 1.6, .85, 0, a * .8f);
            }
            default -> {
            }
        }
    }

    /** R 闪焰冲锋: the whole body is fire while it steamrolls forward. */
    private static void flareBlitz(FxView v, Fx arc, Fx spark, Fx pillar, float a) {
        switch (v.phase) {
            case "start" -> {
                pillar.sheet(v.pos.add(0, .8, 0), 1.0, 2.0, 0, a * .85f);
            }
            case "trail" -> {
                for (int i = 1; i <= 3; i++)
                    arc.sheet(v.pos.subtract(v.dir.scale(i * .7)).add(0, .6, 0), 1.3 - i * .22, .7 - i * .1, 0, a * (.65f - i * .15f));
            }
            case "impact", "hit", "melee" -> {
                spark.sheet(v.pos.add(0, .8, 0), 3.2, 3.2, v.time * 50, a);
                pillar.sheet(v.pos.add(0, 1.6, 0), 1.2, 2.4, 0, a);
                for (int i = 0; i < 4; i++) {
                    double ang = i * Math.PI / 2 + .4;
                    arc.sheet(v.pos.add(Math.cos(ang) * 1.1, .6, Math.sin(ang) * 1.1), 1.4, .7, ang, a * .75f);
                }
            }
            default -> {
            }
        }
    }
}

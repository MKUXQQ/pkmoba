package com.pkmoba.client.fx;

import net.minecraft.resources.ResourceLocation;

/**
 * 0.134 Krookodile (流氓鳄) visuals.
 *
 * <p>Sand, desert brown, dark red and a little amber - no blue or violet anywhere, so the kit reads
 * as a ground/dark bruiser. Every bite is a jaw closing on the target, every Dig step leaves a
 * moving dune on the floor, Earthquake paints radial cracks that grow out of one centre, and Outrage
 * fires five different swing sigils before the tired puff.
 */
public final class KrookodileFx {
    private KrookodileFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation BITE = tex("kd_bite");
    private static final ResourceLocation BITE_HEAVY = tex("kd_bite_heavy");
    private static final ResourceLocation DUST = tex("kd_dust");
    private static final ResourceLocation BURST = tex("kd_burst");
    private static final ResourceLocation QUAKE = tex("kd_quake");
    private static final ResourceLocation SHOCK = tex("kd_shock");
    private static final ResourceLocation RAGE = tex("kd_rage");
    private static final ResourceLocation CLAW = tex("kd_claw");

    public static void render(FxView v) {
        Fx bite = new Fx(v.fx.context(), BITE);
        Fx heavy = new Fx(v.fx.context(), BITE_HEAVY);
        Fx dust = new Fx(v.fx.context(), DUST);
        Fx burst = new Fx(v.fx.context(), BURST);
        Fx quake = new Fx(v.fx.context(), QUAKE);
        Fx shock = new Fx(v.fx.context(), SHOCK);
        Fx rage = new Fx(v.fx.context(), RAGE);
        Fx claw = new Fx(v.fx.context(), CLAW);
        float a = v.fade;
        switch (v.phase) {
            // --- Crunch -------------------------------------------------------------------
            case "bite_wind" -> {
                bite.sheet(v.head(), 1.0, 1.0, 0, a * .7f);
                dust.sheet(v.feet(), .8, .5, 0, a * .5f);
            }
            case "bite_step" -> {
                dust.upright(v.feet(), .7, .9, 0, a * .55f);
                bite.sheet(v.front(.6), .8, .8, 0, a * .5f);
            }
            case "bite" -> {
                heavy.sheet(v.pos, 1.35, 1.35, 0, a);
                shock.ringWave(v.pos, .9, .10, 2, v.time, a * .6f);
                dust.sheet(v.pos, 1.0, .8, 0, a * .7f);
            }
            case "bite_miss" -> heavy.sheet(v.pos, 1.0, 1.0, 0, a * .55f);
            // --- Dig ----------------------------------------------------------------------
            case "dig_down" -> {
                dust.upright(v.feet(), 1.3, 1.0, 0, a);
                burst.ringWave(v.feet(), .8, .14, 2, v.time, a * .7f);
            }
            case "dig_move" -> {
                dust.slab(v.feet(), .9, .7, 0, a * .8f);
                for (int i = 0; i < 3; i++) {
                    double back = .25 + i * .35;
                    dust.sheet(v.feet().add(v.dir.scale(-back)), .55, .45, 0, a * (.6f - i * .12f));
                }
            }
            case "dig_up" -> {
                burst.sheet(v.pos, 1.6, 1.6, 0, a);
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4 + v.time * .2;
                    dust.sheet(v.pos.add(Math.cos(ang) * (1.0 + v.progress), .1,
                            Math.sin(ang) * (1.0 + v.progress)), .7, .6, 0, a * .7f);
                }
                quake.ringWave(v.pos, 1.4, .12, 1, v.time, a * .5f);
            }
            case "dig_hit" -> {
                shock.ringWave(v.pos, 1.1, .10, 3, v.time, a * .8f);
                dust.sheet(v.pos, 1.1, .9, 0, a * .7f);
            }
            // --- Earthquake ---------------------------------------------------------------
            case "quake_charge" -> {
                quake.slab(v.pos, 1.6 + v.progress * .6, 1.1 + v.progress * .4, 0, a * .85f);
                for (int i = 0; i < 4; i++) {
                    double ang = i * Math.PI / 2 + v.time * .15;
                    dust.sheet(v.pos.add(Math.cos(ang) * 1.4, .1, Math.sin(ang) * 1.4), .5, .4, 0, a * .5f);
                }
            }
            case "quake" -> {
                quake.slab(v.pos, 3.6, 2.4, 0, a);
                for (int i = 0; i < 3; i++) {
                    shock.ringWave(v.pos.add(0, .12 + i * .04, 0), 1.2 + i * .7, .10, 2, v.time, a * (.8f - i * .18f));
                }
                for (int i = 9; i > 0; i--) {
                    double ang = i * Math.PI * 2 / 9 + .2;
                    dust.sheet(v.pos.add(Math.cos(ang) * 2.2, .15, Math.sin(ang) * 2.2), .8, .6, 0, a * .6f);
                }
            }
            case "quake_hit" -> {
                shock.ringWave(v.pos, .9, .12, 2, v.time, a * .75f);
                claw.sheet(v.pos, .7, .7, v.time * 40, a * .5f);
            }
            // --- Outrage ------------------------------------------------------------------
            case "rage_start" -> {
                rage.sheet(v.chest(), 1.5, 1.5, v.time * 90, a);
                for (int i = 0; i < 5; i++) {
                    double ang = i * 1.2566 + v.time * .4;
                    dust.sheet(v.feet().add(Math.cos(ang) * 1.2, .1, Math.sin(ang) * 1.2), .6, .5, 0, a * .5f);
                }
            }
            case "rage_hit0", "rage_hit2" -> {
                bite.sheet(v.pos.add(v.dir.scale(.7)), 1.1, 1.1, v.time * 30, a);
                claw.sheet(v.pos, 1.2, 1.2, 24, a * .8f);
            }
            case "rage_hit1" -> {
                claw.sheet(v.pos, 1.5, 1.5, -20, a);
                claw.sheet(v.pos.add(0, .2, 0), 1.2, 1.2, 26, a * .8f);
            }
            case "rage_hit3" -> {
                claw.sheet(v.pos, 1.4, 1.4, 34, a);
                claw.sheet(v.pos, 1.4, 1.4, -34, a * .9f);
                dust.sheet(v.pos, .9, .8, 0, a * .6f);
            }
            case "rage_final" -> {
                heavy.sheet(v.pos.add(v.dir.scale(.9)), 1.6, 1.6, 0, a);
                rage.sheet(v.pos, 1.6, 1.6, v.time * 120, a * .9f);
                for (int i = 0; i < 3; i++)
                    shock.ringWave(v.pos, 1.0 + i * .6, .12, 2, v.time, a * (.8f - i * .2f));
            }
            case "rage_land" -> {
                claw.sheet(v.pos, 1.0, 1.0, v.time * 60, a * .9f);
                dust.sheet(v.pos, .8, .65, 0, a * .55f);
            }
            case "tired" -> {
                dust.upright(v.feet(), 1.1, .6, 0, a * .6f);
                rage.sheet(v.chest(), .9, .9, v.time * 20, a * .35f);
            }
            default -> {
                bite.sheet(v.pos, 1.0, 1.0, 0, a * .8f);
                dust.sheet(v.pos, .9, .7, 0, a * .5f);
            }
        }
    }
}

package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * 0.132 Ceruledge (苍炎刃鬼) visuals.
 *
 * <p>One violet-blue blade for Psycho Cut, a low ghost silhouette for Shadow Sneak, the twin
 * blue-flame swings of Bitter Blade with the life-energy回流 on hit, and the Swords Dance shockwave.
 * Blue flame is the whole identity, so every phase is built from the same four sheets: crescent
 * slashes, the cross, the ember and the dance ring.
 */
public final class CeruledgeFx {
    private CeruledgeFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation SLASH_L = tex("ce_flare_l");
    private static final ResourceLocation SLASH_R = tex("ce_flare_r");
    private static final ResourceLocation CROSS = tex("ce_cross");
    private static final ResourceLocation BLADE = tex("ce_blade");
    private static final ResourceLocation SHADOW = tex("ce_shadow");
    private static final ResourceLocation DANCE = tex("ce_dance");
    private static final ResourceLocation DRAIN = tex("ce_drain");
    private static final ResourceLocation EMBER = tex("ce_ember");

    public static void render(FxView v) {
        Fx slashL = new Fx(v.fx.context(), SLASH_L);
        Fx slashR = new Fx(v.fx.context(), SLASH_R);
        Fx cross = new Fx(v.fx.context(), CROSS);
        Fx blade = new Fx(v.fx.context(), BLADE);
        Fx shadow = new Fx(v.fx.context(), SHADOW);
        Fx dance = new Fx(v.fx.context(), DANCE);
        Fx drain = new Fx(v.fx.context(), DRAIN);
        Fx ember = new Fx(v.fx.context(), EMBER);
        float a = v.fade;
        double yaw = Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
        switch (v.skill) {
            case 0 -> psychoCut(v, blade, cross, ember, a, yaw);
            case 1 -> shadowSneak(v, shadow, cross, ember, a, yaw);
            case 2 -> bitterBlade(v, slashL, slashR, cross, ember, drain, a, yaw);
            default -> swordsDance(v, dance, ember, a);
        }
    }

    private static void psychoCut(FxView v, Fx blade, Fx cross, Fx ember, float a, double yaw) {
        switch (v.phase) {
            case "start" -> {
                ember.sheet(v.hand(), .75, .75, yaw, a * .85f);
                ember.sheet(v.hand(), .45, .45, yaw + 30, a * .55f);
            }
            case "attack" -> {
                blade.sheet(v.pos, 1.5, 1.5, yaw, a);
                ember.sheet(v.pos, .8, .8, yaw + 20, a * .7f);
                cross.starBurst(v.pos, 1.1, 6, v.time, a * .5f);
            }
            case "projectile" -> {
                blade.sheet(v.pos, 1.15, 1.15, v.time * 320, a);
                blade.sheet(v.pos.subtract(v.dir.scale(.5)), .8, .8, v.time * 300 + 40, a * .45f);
                ember.sheet(v.pos, .5, .5, v.time * 240, a * .5f);
                ember.pixels(v.pos, .45, 3, .12, v.time, a * .45f);
            }
            case "impact", "hit" -> {
                cross.sheet(v.pos, 1.7, 1.7, yaw + v.time * 90, a);
                cross.starBurst(v.pos, 1.5, 8, v.time, a * .8f);
                blade.sheet(v.pos.subtract(v.dir.scale(.4)), .8, .8, yaw + 60, a * .5f);
                ember.pixels(v.pos, .7, 6, .16, v.time, a * .7f);
            }
            case "end", "clear" -> {
                blade.sheet(v.pos, .7, .7, yaw, a * .35f);
                ember.pixels(v.pos, .5, 4, .12, v.time, a * .3f);
            }
            default -> {
            }
        }
    }

    private static void shadowSneak(FxView v, Fx shadow, Fx cross, Fx ember, float a, double yaw) {
        switch (v.phase) {
            case "start", "vanish" -> {
                Vec3 feet = v.feet();
                shadow.sheet(feet.add(0, .9, 0), 1.5, 1.7, yaw, a * .95f);
                shadow.groundRing(feet.add(0, .04, 0), 1.35, 2, v.time, a * .7f);
                ember.pixels(feet.add(0, .3, 0), 1.0, 7, .2, v.time, a * .6f);
            }
            case "wisp" -> {
                Vec3 feet = v.feet();
                shadow.sheet(feet.add(0, .9, 0), 1.25, 1.45, yaw, a * .75f);
                shadow.sheet(feet.subtract(v.dir.scale(.9)).add(0, .8, 0), .9, 1.1, yaw, a * .4f);
                ember.pixels(feet.add(0, .35, 0), .85, 6, .18, v.time, a * .55f);
            }
            case "appear" -> {
                Vec3 feet = v.feet();
                shadow.sheet(feet.add(0, .95, 0), 1.6, 1.8, yaw, a);
                cross.groundRing(feet.add(0, .05, 0), 1.5, 2, v.time, a * .6f);
            }
            case "cross" -> {
                Vec3 at = v.pos;
                cross.sheet(at, 2.1, 2.1, yaw, a);
                cross.sheet(at, 1.5, 1.5, yaw + 45, a * .6f);
                cross.starBurst(at, 1.6, 8, v.time, a * .7f);
                ember.pixels(at, .8, 6, .2, v.time, a * .6f);
            }
            case "impact", "melee", "hit" -> {
                cross.starBurst(v.pos, 1.2, 7, v.time, a * .7f);
                ember.pixels(v.pos, .6, 5, .16, v.time, a * .6f);
            }
            case "end", "clear" -> {
                shadow.pixels(v.feet().add(0, .6, 0), .8, 5, .18, v.time, a * .3f);
            }
            default -> {
            }
        }
    }

    private static void bitterBlade(FxView v, Fx slashL, Fx slashR, Fx cross, Fx ember, Fx drain,
                                    float a, double yaw) {
        switch (v.phase) {
            case "start" -> {
                ember.sheet(v.hand(), .9, .9, yaw, a * .9f);
                ember.sheet(v.offHand(), .9, .9, yaw, a * .9f);
            }
            case "slash_l" -> {
                Vec3 at = v.chest().add(v.dir.scale(1.1)).add(0, .1, 0);
                slashL.sheet(at, 2.0, 2.0, yaw + 25, a);
                slashL.sheet(at, 1.4, 1.4, yaw - 15, a * .55f);
                ember.pixels(at, .9, 6, .2, v.time, a * .65f);
            }
            case "slash_r" -> {
                Vec3 at = v.chest().add(v.dir.scale(1.1)).add(0, .1, 0);
                slashR.sheet(at, 2.0, 2.0, yaw - 25, a);
                slashR.sheet(at, 1.4, 1.4, yaw + 15, a * .55f);
                ember.pixels(at, .9, 6, .2, v.time, a * .65f);
            }
            case "cross" -> {
                Vec3 at = v.chest().add(v.dir.scale(1.3));
                cross.sheet(at, 2.4, 2.4, yaw, a);
                cross.starBurst(at, 1.8, 9, v.time, a * .8f);
                ember.pixels(at, 1.0, 8, .22, v.time, a * .7f);
            }
            case "drain" -> {
                Vec3 to = v.chest();
                drain.streak(v.pos, to, .55, a * .85f);
                drain.sheet(v.pos, .9, .9, yaw, a * .7f);
                drain.sheet(to, .8, .8, yaw, a * .6f);
                drain.pixels(v.pos.lerp(to, .5), .8, 6, .16, v.time, a * .6f);
            }
            case "heal" -> {
                Vec3 feet = v.feet();
                ember.ringWave(feet.add(0, .9, 0), .95, .16, 2, v.time, a * .8f);
                ember.sheet(feet.add(0, 1.0, 0), 1.2, 1.2, v.time * 180, a * .5f);
                ember.pixels(feet.add(0, .5, 0), .9, 6, .18, v.time, a * .6f);
            }
            case "impact", "melee", "hit" -> {
                cross.starBurst(v.pos, 1.1, 7, v.time, a * .7f);
                ember.pixels(v.pos, .6, 5, .16, v.time, a * .6f);
            }
            case "end", "clear" -> {
                ember.pixels(v.chest(), .8, 5, .18, v.time, a * .3f);
            }
            default -> {
            }
        }
    }

    private static void swordsDance(FxView v, Fx dance, Fx ember, float a) {
        switch (v.phase) {
            case "start", "buff" -> {
                Vec3 feet = v.feet();
                ember.sheet(v.hand(), .85, .85, 0, a * .8f);
                ember.sheet(v.offHand(), .85, .85, 0, a * .8f);
                dance.sheet(feet.add(0, 1.0, 0), 1.6, 1.6, v.time * 140, a * .8f);
                ember.groundRing(feet.add(0, .05, 0), 1.6, 2, v.time, a * .6f);
            }
            case "dance" -> {
                Vec3 feet = v.feet();
                dance.ringWave(feet.add(0, .8, 0), 2.2, .3, 3, v.time, a);
                dance.groundRing(feet.add(0, .05, 0), 2.4, 3, v.time, a * .85f);
                dance.sheet(feet.add(0, 1.1, 0), 1.9, 1.9, v.time * 200, a * .6f);
                for (int i = 0; i < 6; i++) {
                    double ang = i * (Math.PI * 2 / 6) + v.time * .8;
                    ember.sheet(feet.add(Math.cos(ang) * 1.3, .5 + (i % 2) * .3, Math.sin(ang) * 1.3),
                            .7, .9, Math.toDegrees(ang) + 90, a * .7f);
                }
            }
            case "end", "clear" -> {
                ember.pixels(v.chest(), 1.0, 6, .18, v.time, a * .3f);
            }
            default -> {
            }
        }
    }
}

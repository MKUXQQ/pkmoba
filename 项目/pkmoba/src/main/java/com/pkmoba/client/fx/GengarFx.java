package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionSkillMeshes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Gengar: chunky Minecraft-pixel geometry, sprites only as seasoning.
 *
 * <p>0.66 rewrite. Two complaints drove it: the bodies were too small to read, and the old pass
 * sprayed enough vanilla particles that the hit area looked twice its real size. Every move now
 * draws a big UV-mapped pixel-art body, and the painted sheets are gone from all but a couple of
 * contact frames — the shape carries the readability, not the particles.
 *
 * <p>Phases used by the server: {@code start / projectile / lob / impact / melee} for the two
 * projectiles, {@code ray1..3 / lit1..3 / sleep} for the hypnosis beam and {@code blink / melee /
 * heal} for the dream-eater. For {@code blink} the payload carries the start point in {@code pos}
 * and the arrival point in {@code dir}.
 */
public final class GengarFx {
    private GengarFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/expansion/" + name + ".png");
    }

    private static final ResourceLocation Q = tex("gengar_q_surface");
    private static final ResourceLocation W = tex("gengar_w_surface");
    private static final ResourceLocation E = tex("gengar_e_surface");
    private static final ResourceLocation R = tex("gengar_r_surface");

    /** Shadow body: solid core plus a slowly counter-rotating inner shell. */
    private static void body(WorldRenderContext c, ResourceLocation t, Vec3 p, double size,
                             double time, float alpha) {
        ExpansionSkillMeshes.sphere(c, p, new Vec3(size, size, size), time * .35, t,
                Math.min(1f, alpha * .9f), false);
        ExpansionSkillMeshes.sphere(c, p, new Vec3(size * .6, size * .6, size * .6), -time * .55, t,
                Math.min(1f, alpha * .75f), true);
    }

    private static void tendrils(WorldRenderContext c, ResourceLocation t, Vec3 p, double length,
                                 int count, double time, float alpha) {
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + time * .18;
            double lift = .2 + .14 * Math.sin(i * 1.7 + time);
            Vec3 end = p.add(Math.cos(ang) * length, lift, Math.sin(ang) * length);
            ExpansionSkillMeshes.tube(c, p, end, Math.max(.05, length * .07), t, alpha * .85f);
        }
    }

    /** Three pixel rings marching down one beam, so the light reads as "scanning forward". */
    private static void beam(WorldRenderContext c, Vec3 from, Vec3 to, float alpha, int layer) {
        Vec3 axis = to.subtract(from);
        double len = axis.length();
        if (len < .05) return;
        Vec3 unit = axis.scale(1 / len);
        ExpansionSkillMeshes.tube(c, from, to, .13, E, alpha * .9f);
        ExpansionSkillMeshes.tube(c, from, to, .05, E, Math.min(1f, alpha));
        for (int i = 0; i < 3; i++) {
            double t = .35 + .25 * i + .06 * layer;
            if (t > 1) break;
            ExpansionSkillMeshes.ring(c, from.add(unit.scale(len * t)), .55 - .1 * i, .06, E,
                    alpha * (.85f - .15f * i));
        }
        ExpansionSkillMeshes.sphere(c, from.add(unit.scale(len * Math.min(1, .3 * layer))),
                new Vec3(.16, .16, .16), len, E, alpha * .8f, true);
    }

    /** Hypnotic spiral parked on the victim's head; {@code rings} grows with the layer count. */
    private static void hypno(WorldRenderContext c, Vec3 p, int rings, double time, float alpha) {
        Vec3 top = p.add(0, 1.0, 0);
        for (int i = 0; i < rings; i++) {
            double r = .5 + .34 * i;
            ExpansionSkillMeshes.ring(c, top.add(0, .06 * i, 0), r, .07, E, alpha * (.9f - i * .18f));
        }
        ExpansionSkillMeshes.tube(c, top.add(0, .55, 0), top.subtract(0, .5, 0), .09, E, alpha * .85f);
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        WorldRenderContext c = fx.context();
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> { // Shadow Ball · 暗影球
                if (v.is("start")) {
                    Vec3 hand = v.hand();
                    body(c, Q, hand, .30 + .18 * v.progress, v.time, a);
                    ExpansionSkillMeshes.tube(c, hand, hand.add(v.dir.scale(.7)), .10, Q, a * .8f);
                } else if (v.in("projectile", "lob")) {
                    body(c, Q, v.pos, .40, v.time, a);
                    ExpansionSkillMeshes.tube(c, v.pos.subtract(v.dir.scale(.95)), v.pos, .18, Q, a * .7f);
                    ExpansionSkillMeshes.tube(c, v.pos.subtract(v.dir.scale(1.8)), v.pos.subtract(v.dir.scale(.9)),
                            .11, Q, a * .45f);
                } else if (v.in("impact", "melee")) {
                    double r = .45 + .65 * v.progress;
                    body(c, Q, v.pos, r, v.time, a);
                    tendrils(c, Q, v.pos, r * 2.2, 7, v.time, a);
                    ExpansionSkillMeshes.ring(c, v.pos.add(0, .08, 0), r * 2.0, .07, Q, a * .85f);
                }
            }
            case 1 -> { // Sludge Bomb · 污泥炸弹
                if (v.is("start")) {
                    Vec3 hand = v.hand();
                    ExpansionSkillMeshes.sphere(c, hand, new Vec3(.28, .25, .28), v.time * .4, W, a * .95f, true);
                    ExpansionSkillMeshes.ring(c, hand, .52, .06, W, a * .7f);
                } else if (v.in("projectile", "lob")) {
                    ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(.38, .33, .38), v.time * .5, W, a * .95f, true);
                    for (int i = 0; i < 3; i++) {
                        double d = (i + 1) * .42;
                        Vec3 drop = v.pos.subtract(v.dir.scale(d)).add(0, -.18 * d, 0);
                        ExpansionSkillMeshes.sphere(c, drop, new Vec3(.16, .21, .16), v.time + i, W,
                                a * (.8f - i * .18f), true);
                    }
                } else if (v.in("impact", "melee")) {
                    Vec3 p = v.pos;
                    ExpansionSkillMeshes.sphere(c, p.add(0, .14, 0), new Vec3(.48, .36, .48), v.time, W, a * .95f, true);
                    ExpansionSkillMeshes.ring(c, p.add(0, .07, 0), 1.0 + 1.3 * v.progress, .09, W, a * .9f);
                    for (int i = 0; i < 6; i++) {
                        double ang = i * Math.PI / 3;
                        Vec3 b0 = p.add(Math.cos(ang) * .62, .12, Math.sin(ang) * .62);
                        Vec3 b1 = b0.add(Math.cos(ang) * .55, .6 + .25 * (i % 2), Math.sin(ang) * .55);
                        ExpansionSkillMeshes.tube(c, b0, b1, .10, W, a * .7f);
                    }
                }
            }
            case 2 -> { // Hypnosis Beam · 催眠光
                if (v.is("start")) {
                    Vec3 hand = v.hand();
                    for (int i = 0; i < 2; i++)
                        ExpansionSkillMeshes.ring(c, hand.add(0, .1 * i, 0), .38 + .22 * i, .05, E, a * .8f);
                    ExpansionSkillMeshes.sphere(c, hand, new Vec3(.2, .2, .2), v.time * .3, E, a * .85f, true);
                } else if (v.phase.startsWith("ray")) {
                    int layer = Math.max(1, Math.min(3, v.phase.charAt(3) - '0'));
                    beam(c, v.hand(), v.pos, a, layer);
                    hypno(c, v.pos, layer, v.time, a * .55f);
                } else if (v.phase.startsWith("lit")) {
                    int lit = Math.max(1, Math.min(3, v.phase.charAt(3) - '0'));
                    hypno(c, v.pos, lit, v.time, a);
                    ExpansionSkillMeshes.sphere(c, v.pos.add(0, 1.0, 0), new Vec3(.2, .2, .2), v.time, E, a * .9f, true);
                } else if (v.is("sleep")) {
                    hypno(c, v.pos, 3, v.time, a);
                    ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.6, 0), .85, .09, E, a * .9f);
                    ExpansionSkillMeshes.ring(c, v.pos.add(0, 2.0, 0), .55, .07, E, a * .7f);
                } else if (v.is("impact")) {
                    hypno(c, v.pos, 3, v.time, a);
                }
            }
            default -> { // Dream Eater · 食梦：闪现贴身，睡着则吸血
                if (v.is("start")) {
                    body(c, R, v.hand(), .3, v.time, a * .95f);
                } else if (v.is("blink")) {
                    Vec3 from = v.pos;
                    Vec3 to = v.dir;
                    Vec3 axis = to.subtract(from);
                    Vec3 side = axis.normalize().cross(new Vec3(0, 1, 0));
                    if (side.lengthSqr() < 1e-4) side = new Vec3(1, 0, 0);
                    side = side.normalize();
                    for (int i = 0; i < 3; i++) {
                        double off = (i - 1) * .32;
                        ExpansionSkillMeshes.tube(c, from.add(side.scale(off)).add(0, .3, 0),
                                to.add(side.scale(off * .5)).add(0, .3, 0), .10, R, a * (.85f - i * .15f));
                    }
                    body(c, R, to, .55, v.time, a * .9f);
                    ExpansionSkillMeshes.ring(c, to.add(0, .08, 0), .95, .07, R, a * .8f);
                } else if (v.in("melee", "impact")) {
                    double r = .5 + .4 * v.progress;
                    body(c, R, v.pos, r, v.time, a);
                    tendrils(c, R, v.pos, r * 1.8, 6, v.time, a * .9f);
                    ExpansionSkillMeshes.ring(c, v.pos.add(0, .4, 0), r * 1.8, .08, R, a * .85f);
                } else if (v.is("heal")) {
                    Vec3 from = v.pos;
                    Vec3 to = v.chest();
                    for (int i = 0; i < 3; i++) {
                        double ang = i * Math.PI * 2 / 3 + v.time * .2;
                        Vec3 sway = new Vec3(Math.cos(ang) * .3, 0, Math.sin(ang) * .3);
                        ExpansionSkillMeshes.tube(c, from.add(sway).add(0, .5, 0), to, .09, R,
                                a * (.85f - i * .15f));
                    }
                    body(c, R, to, .5, v.time, a * .9f);
                    ExpansionSkillMeshes.ring(c, to.add(0, .1, 0), 1.0, .08, R, a * .8f);
                }
            }
        }
    }
}

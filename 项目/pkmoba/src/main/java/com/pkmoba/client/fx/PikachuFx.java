package com.pkmoba.client.fx;

import com.pkmoba.client.ElectricSkillMesh;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Pikachu: lightning first, texture second.
 *
 * <p>The moves read as electricity because every one of them carries real jagged bolts (the closed
 * emissive meshes in {@link ElectricSkillMesh}) with a bright white-hot core, branching forks and
 * ground arcs. The painted sheets only add the glow around them. Q 十万伏特 = a long fork storm,
 * W 电球 = a plasma sphere shedding bolts, E 电光一闪 = a volt-tackle banner with a bolt tail,
 * R 打雷 = a storm cage and a falling strike.
 */
public final class PikachuFx {
    private PikachuFx() {
    }

    private static final ResourceLocation Q = tex("pikachu_q");
    private static final ResourceLocation W = tex("pikachu_w");
    private static final ResourceLocation E = tex("pikachu_e");
    private static final ResourceLocation R = tex("pikachu_r");

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    /** World-space matrix for the closed mesh helpers (they skip the camera subtraction themselves). */
    private static Matrix4f offset(WorldRenderContext c) {
        Vec3 cam = c.camera().getPosition();
        return new Matrix4f().translation((float) -cam.x, (float) -cam.y, (float) -cam.z);
    }

    private static void fork(WorldRenderContext c, Matrix4f m, Vec3 from, Vec3 to, double radius,
                             float alpha, double seed) {
        ElectricSkillMesh.bolt(c, m, from, to, radius, alpha, seed);
        ElectricSkillMesh.bolt(c, m, from, to, radius * .55, alpha, seed + 2.7);
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() > .5) {
            Vec3 mid = from.add(axis.scale(.45 + .2 * Math.sin(seed)));
            Vec3 side = axis.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1E-4) side = new Vec3(1, 0, 0);
            side = side.normalize().scale(axis.length() * .28);
            ElectricSkillMesh.bolt(c, m, mid, mid.add(side).add(0, -.15, 0), radius * .45, alpha * .85f, seed + 1.3);
            ElectricSkillMesh.bolt(c, m, mid, mid.subtract(side).add(0, .12, 0), radius * .45, alpha * .8f, seed + 4.1);
        }
    }

    public static void render(WorldRenderContext c, String kind, Vec3 pos, Vec3 vec, double t, float alpha) {
        Matrix4f m = offset(c);
        Fx base = new Fx(c, kind.startsWith("q") ? Q : kind.startsWith("e") ? E : kind.startsWith("r") ? R : W);
        base.glow(pos, .55 + Math.min(1.2, vec.length() * .22), alpha * .7f);
        base.pixels(pos, 1.0, 16, .16, t, alpha * .9f);
        Vec3 tail = pos.subtract(vec.lengthSqr() < 1e-4 ? new Vec3(0, 0, .9) : vec.normalize().scale(.9));
        base.flow(tail, pos, .34, t, alpha * .7f);
        switch (kind) {
            case "q_bolt" -> {
                Vec3 end = pos.add(vec);
                Vec3 up = new Vec3(0, .45, 0);
                fork(c, m, pos.add(up.scale(.4)), end, .14, alpha, t);
                for (int i = 0; i < 3; i++) {
                    double seed = t * .7 + i * 2.3;
                    Vec3 mid = pos.add(vec.scale(.3 + i * .2)).add(0, .1 - i * .25, 0);
                    fork(c, m, mid, mid.add(vec.normalize().scale(1.1)).add(
                            Math.sin(seed) * .5, Math.cos(seed * 1.4) * .5, Math.cos(seed) * .5), .08, alpha * .8f, seed);
                }
                Fx fx = new Fx(c, Q);
                fx.sheet(end, .8, .8, t * .5, alpha);
                fx.sheet(pos.add(vec.scale(.55)), .7, 1.1, t * .3, alpha * .7f);
                fx.burst(end, 1.2, 10, .35, t, alpha * .9f);
                fx.groundRing(end, .9, 2, t, alpha * .5f);
            }
            case "w_charge", "r_charge" -> {
                boolean ult = kind.equals("r_charge");
                Fx fx = new Fx(c, ult ? R : W);
                Vec3 core = pos.add(0, .45, 0);
                double size = (ult ? .42 : .28) + Math.min(1, Math.max(0, vec.x)) * .35;
                ElectricSkillMesh.orb(c, m, core, size, alpha * .95f, t * .4);
                fx.sheet(core, size * 2.0, size * 2.0, t * .3, alpha * .85f);
                base.pixels(core, size * 1.6, 10, .12, t, alpha);
                int spokes = ult ? 8 : 5;
                for (int i = 0; i < spokes; i++) {
                    double ang = i * Math.PI * 2 / spokes + t * .25;
                    Vec3 out = core.add(Math.cos(ang) * size * 1.8, Math.sin(ang * 1.7) * size, Math.sin(ang) * size * 1.8);
                    ElectricSkillMesh.bolt(c, m, core, out, .035, alpha * .8f, i + t * .5);
                }
                for (int i = 0; i < (ult ? 4 : 3); i++) fx.ring(core, size * (1 + i * .32), .018, alpha * .7f);
                if (ult) fx.groundRing(pos, 1.5, 3, t, alpha * .6f);
            }
            case "w_orb_fly" -> {
                Fx fx = new Fx(c, W);
                Vec3 dir = vec.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : vec.normalize();
                ElectricSkillMesh.orb(c, m, pos, .34, alpha, t * .5);
                fx.sheet(pos, 1.0, 1.0, t * .5, alpha);
                for (int i = 0; i < 4; i++) {
                    Vec3 a = pos.subtract(dir.scale(.25 + i * .22));
                    ElectricSkillMesh.bolt(c, m, a, a.add(dir.cross(new Vec3(0, 1, 0)).normalize().scale(
                            Math.sin(i + t * .4) * .35)).add(0, Math.cos(i * 1.3 + t * .3) * .3, 0),
                            .045, alpha * (.8f - i * .15f), i + t);
                }
            }
            case "w_orb_burst" -> {
                Fx fx = new Fx(c, W);
                double radius = Math.max(.5, vec.x) * (.5 + .5 * Math.min(1, t % 8 / 4));
                ElectricSkillMesh.ring(c, m, pos.add(0, .12, 0), radius * .9, .05, alpha * .8f, t);
                for (int i = 0; i < 10; i++) {
                    double ang = i * Math.PI * 2 / 10 + t * .3;
                    Vec3 end = pos.add(Math.cos(ang) * radius, .25 + Math.sin(i * 1.7) * .5, Math.sin(ang) * radius);
                    ElectricSkillMesh.bolt(c, m, pos.add(0, .25, 0), end, .05, alpha * .85f, i + t);
                }
                fx.disc(pos.add(0, .05, 0), radius * 1.3, alpha * .7f);
                fx.burst(pos.add(0, .25, 0), radius, 12, .3, t, alpha * .85f);
                fx.groundRing(pos, radius * 1.1, 3, t, alpha * .6f);
            }
            case "e_start", "e_trail", "e_hit" -> {
                Fx fx = new Fx(c, E);
                Vec3 head = pos.add(0, .5, 0);
                Vec3 dir = vec.lengthSqr() < .001 ? new Vec3(0, 0, 1) : vec.normalize();
                for (int i = 0; i < 4; i++) {
                    double seed = t * .6 + i * 1.9;
                    Vec3 a = head.subtract(dir.scale(.5 + i * .45));
                    Vec3 b = head.subtract(dir.scale(.15 + i * .3)).add(
                            Math.sin(seed) * .22, Math.cos(seed * 1.3) * .18, 0);
                    ElectricSkillMesh.bolt(c, m, a, b, .05, alpha * (.85f - i * .15f), seed);
                }
                fx.beam(head.subtract(dir.scale(2.2)), head, .5, alpha * .45f);
                fx.sheet(head, 1.1, .6, dir.y, alpha);
                if (kind.equals("e_hit")) {
                    fx.disc(pos.add(0, .05, 0), 1.6, alpha * .75f);
                    for (int i = 0; i < 8; i++) {
                        double ang = i * Math.PI / 4 + t;
                        ElectricSkillMesh.bolt(c, m, pos.add(0, .2, 0),
                                pos.add(Math.cos(ang) * 1.3, .2, Math.sin(ang) * 1.3), .05, alpha * .8f, i + t);
                    }
                    fx.burst(pos.add(0, .3, 0), 1.4, 10, .3, t, alpha * .85f);
                }
            }
            case "r_warn", "r_field" -> {
                Fx fx = new Fx(c, R);
                double radius = kind.equals("r_field") ? Math.max(1, Math.min(9, vec.x)) : Math.max(1, vec.x);
                fx.disc(pos.add(0, .05, 0), radius, alpha * .5f);
                fx.groundRing(pos, radius, 4, t, alpha * .7f);
                int count = kind.equals("r_field") ? 12 : 7;
                for (int i = 0; i < count; i++) {
                    double ang = i * Math.PI * 2 / count + t * .05;
                    Vec3 foot = pos.add(Math.cos(ang) * radius * .85, 0, Math.sin(ang) * radius * .85);
                    Vec3 top = foot.add(0, 2.4 + (i % 3) * 1.0, 0);
                    ElectricSkillMesh.bolt(c, m, foot, top, .06, alpha * .85f, i + t);
                    ElectricSkillMesh.bolt(c, m, foot, foot.add(Math.cos(ang) * radius * .25, 1.2, Math.sin(ang) * radius * .25),
                            .035, alpha * .6f, i * 2 + t);
                }
                base.pixels(pos.add(0, 1, 0), radius * .8, 12, .16, t, alpha);
            }
            case "r_strike" -> {
                Fx fx = new Fx(c, R);
                Vec3 end = pos.add(vec);
                double radius = com.pkmoba.combat.PikachuCombat.R_RADIUS;
                fork(c, m, pos, end, .22, alpha, t);
                for (int i = 0; i < 5; i++) {
                    double seed = t + i * 2.1;
                    Vec3 from = pos.add(vec.scale(.15 + i * .16));
                    fork(c, m, from, from.add(vec.normalize().scale(1.4)).add(
                            Math.sin(seed) * .8, Math.cos(seed) * .5, Math.cos(seed * .7) * .8), .09, alpha * .75f, seed);
                }
                fx.disc(end.add(0, .05, 0), radius, alpha * .7f);
                fx.groundRing(end, radius, 4, t, alpha * .85f);
                for (int i = 0; i < 12; i++) {
                    double ang = i * Math.PI * 2 / 12 + t * .1;
                    Vec3 foot = end.add(Math.cos(ang) * radius * .7, 0, Math.sin(ang) * radius * .7);
                    ElectricSkillMesh.bolt(c, m, foot, foot.add(0, 2.2 + (i % 4) * .8, 0), .06, alpha * .8f, i + t);
                }
                fx.burst(end.add(0, .2, 0), radius * .9, 14, .15, t, alpha * .8f);
            }
            default -> { }
        }
    }
}

package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionSkillMeshes;
import com.pkmoba.combat.PikachuCombat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Pikachu v4: continuous textured lightning, no outlines, low density.
 *
 * <p>v3 drew every segment as its own textured block with a dark rim, which read as a chain of
 * separated chips. Here a bolt is one continuous ribbon: the texture scrolls along the whole path
 * (u = running length), the width is carried from segment to segment, and the white-hot core is a
 * second continuous ribbon inside it. Zigzags stay in the camera plane so the drawing always reads
 * as a bolt, and the layer count is capped at bolt + core + one spark + one ground ring.
 */
public final class PikachuFx {
    private PikachuFx() {
    }

    private static final ResourceLocation SHOCK = tex("pikachu_thunder_shock");
    private static final ResourceLocation CHANNEL = tex("pikachu_thunderbolt");
    private static final ResourceLocation COLUMN = tex("pikachu_thunder_bolt");
    private static final ResourceLocation CLOUD_BODY = tex("pikachu_cloud_body");
    private static final ResourceLocation SPARK = tex("pikachu_spark");

    /** How many texture repeats one world unit of bolt consumes; keeps the band from smearing. */
    private static final double UV_SCALE = 3.2;

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    public static void render(WorldRenderContext c, String kind, Vec3 pos, Vec3 vec, double t,
                              float alpha, double seed) {
        switch (kind) {
            case "q_bolt" -> {
                Vec3 end = pos.add(vec);
                Vec3[] path = zig(c, pos, end, 5, .55, seed);
                bolt(c, path, SHOCK, .95, alpha);
                forks(c, path, 1, .9, alpha * .7f, seed);
                spark(c, end, 1.5, t, alpha);
            }
            case "q_chain" -> bolt(c, zig(c, pos, pos.add(vec), 4, .3, seed), SHOCK, .5, alpha * .9f);
            case "w_charge" -> charge(c, pos, t, alpha, seed);
            case "w_beam" -> {
                Vec3 end = pos.add(vec);
                Vec3[] path = zig(c, pos, end, 6, .95, seed);
                bolt(c, path, CHANNEL, 1.7, alpha);
                forks(c, path, 1, 1.4, alpha * .7f, seed + 9);
                spark(c, end, 2.2 + .15 * Math.sin(t * 7), t * .6, alpha);
            }
            case "w_beam_end" -> spark(c, pos, 2.4, t, alpha);
            case "e_start" -> dashPath(c, pos, vec, t, alpha);
            case "e_trail" -> {
                Vec3 dir = vec.lengthSqr() < .01 ? new Vec3(0, 0, 1) : vec.normalize();
                Vec3 back = pos.subtract(dir.scale(1.8)).add(0, .45, 0);
                bolt(c, zig(c, back, pos.add(0, .45, 0), 4, .4, seed), SHOCK, .55, alpha * .8f);
            }
            case "e_hit" -> spark(c, pos, 2.0, t, alpha);
            case "e_chain" -> bolt(c, zig(c, pos, pos.add(vec), 4, .26, seed), SHOCK, .42, alpha * .85f);
            // Charging happens on Pikachu, but the storm cloud only ever appears where the strike
            // is aimed - a cloud over the caster's head made the real lightning unreadable.
            case "r_charge" -> charge(c, pos, t, alpha, seed);
            case "r_cloud", "r_warn" -> cloud3d(c, pos.add(0, 7.3, 0), 1.15, t, alpha, seed);
            case "r_strike" -> {
                Vec3 ground = pos.add(vec);
                Vec3 cloudAt = ground.add(0, 7.3, 0);
                cloud3d(c, cloudAt, 1.2, t, alpha * .95f, seed);
                Vec3[] path = zig(c, cloudAt.add(0, -1.1, 0), ground, 6, 1.35, seed + 5);
                bolt(c, path, COLUMN, 1.8, alpha);
                forks(c, path, 1, 1.4, alpha * .7f, seed + 17);
                spark(c, ground, 2.6, t, alpha);
            }
            case "r_field" -> {
                double radius = Math.max(1, Math.min(PikachuCombat.R_RADIUS, vec.x));
                for (int i = 0; i < 3; i++) {
                    double ang = i * Math.PI * 2 / 3 + t * .1;
                    Vec3 foot = pos.add(Math.cos(ang) * radius * .65, 0, Math.sin(ang) * radius * .65);
                    bolt(c, zig(c, foot, foot.add(0, 1.6, 0), 3, .3, seed + i), SHOCK, .45, alpha * .7f);
                }
            }
            default -> { }
        }
    }

    // ---- compositions ----------------------------------------------------------------------

    private static void charge(WorldRenderContext c, Vec3 pos, double t, float alpha, double seed) {
        Vec3 core = pos.add(0, .2, 0);
        spark(c, core, 1.0 + .1 * Math.sin(t * 6), t * .8, alpha);
        for (int i = 0; i < 2; i++) {
            double ang = i * Math.PI + t * .5;
            Vec3 out = core.add(Math.cos(ang) * 1.1, .2, Math.sin(ang) * 1.1);
            bolt(c, zig(c, out, core, 3, .28, seed + i), SHOCK, .38, alpha * .7f);
        }
    }

    /** The 电光一闪 route: start, left, right, landing spot - the caster runs along this path. */
    private static void dashPath(WorldRenderContext c, Vec3 pos, Vec3 vec, double t, float alpha) {
        // Only the lightning path is drawn: the stacked Pikachu silhouettes read as a glitch.
        bolt(c, dashRoute(pos, vec), SHOCK, 1.0, alpha);
        spark(c, pos, 1.1, t, alpha * .7f);
    }

    public static Vec3[] dashRoute(Vec3 start, Vec3 vec) {
        Vec3 flat = new Vec3(vec.x, 0, vec.z);
        if (flat.lengthSqr() < 1.0e-4) flat = new Vec3(0, 0, 1);
        Vec3 dir = flat.normalize();
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        double len = flat.length();
        // pos from the cue is already the chest height, and the server walks the body along this
        // same polyline, so the bolt has to sit exactly on it - no extra vertical offset.
        return new Vec3[]{
                start,
                start.add(dir.scale(len * .32)).add(side.scale(1.5)),
                start.add(dir.scale(len * .66)).subtract(side.scale(1.5)),
                start.add(vec)
        };
    }

    /**
     * A real solid cloud instead of a camera-facing sticker: three rough textured lumps, no icon
     * badge, so the lightning below it stays the only readable shape.
     */
    private static void cloud3d(WorldRenderContext c, Vec3 at, double size, double t, float alpha,
                                double seed) {
        ExpansionSkillMeshes.sphere(c, at, new Vec3(size * 1.75, size * .85, size * 1.2), seed,
                CLOUD_BODY, alpha, true);
        ExpansionSkillMeshes.sphere(c, at.add(size * .95, -.12, size * .15),
                new Vec3(size * .95, size * .6, size * .85), seed + 1, CLOUD_BODY, alpha, true);
        ExpansionSkillMeshes.sphere(c, at.add(-size * .95, -.05, -size * .2),
                new Vec3(size * .85, size * .55, size * .8), seed + 2, CLOUD_BODY, alpha, true);
    }

    // ---- primitives ------------------------------------------------------------------------

    /**
     * One continuous ribbon along the polyline: shared width and running UV at every joint, so the
     * lightning never breaks into separate chips.
     */
    private static void bolt(WorldRenderContext c, Vec3[] pts, ResourceLocation body, double thickness,
                             float alpha) {
        if (pts.length < 2 || alpha <= .01f) return;
        Vec3 camera = c.camera().getPosition();
        Vec3[] sides = new Vec3[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Vec3 prev = pts[Math.max(0, i - 1)], next = pts[Math.min(pts.length - 1, i + 1)];
            Vec3 axis = next.subtract(prev);
            Vec3 side = axis.cross(camera.subtract(pts[i]));
            if (side.lengthSqr() < 1.0e-6) side = axis.cross(new Vec3(0, 1, 0));
            sides[i] = side.normalize().scale(thickness * .5);
        }
        double u = 0;
        for (int i = 0; i < pts.length - 1; i++) {
            Vec3 from = pts[i], to = pts[i + 1];
            double len = from.distanceTo(to);
            if (len < 1.0e-4) continue;
            double u2 = u + len * UV_SCALE;
            Vec3 a = from.subtract(sides[i]), b = from.add(sides[i]);
            Vec3 d = to.add(sides[i + 1]), e = to.subtract(sides[i + 1]);
            // one ribbon only: the white-hot core is baked into the band texture, so segments can
            // never drift apart into separate chips and no second outline appears.
            ExpansionSkillMeshes.raw(c, body, a, b, d, e, (float) u, 0, (float) u2, 1, alpha);
            u = u2;
        }
    }

    private static void forks(WorldRenderContext c, Vec3[] path, int count, double length,
                              float alpha, double seed) {
        java.util.Random rnd = new java.util.Random((long) (seed * 1000));
        for (int i = 0; i < count; i++) {
            Vec3 base = path[1 + rnd.nextInt(Math.max(1, path.length - 2))];
            Vec3 dir = new Vec3(rnd.nextDouble() - .5, rnd.nextDouble() * .6 - .15,
                    rnd.nextDouble() - .5).normalize().scale(length);
            bolt(c, zig(c, base, base.add(dir), 3, .35, seed + i * 7), SHOCK, .55, alpha);
        }
    }

    private static void spark(WorldRenderContext c, Vec3 at, double size, double t, float alpha) {
        sheet(c, SPARK, at, size * 1.5, size * 1.5, t * .4, alpha);
    }

    private static void sheet(WorldRenderContext c, ResourceLocation tex, Vec3 center, double w, double h,
                              double angle, float alpha) {
        if (alpha <= .01f) return;
        Vec3[] axes = axes(c, angle);
        Vec3 right = axes[0].scale(w * .5), up = axes[1].scale(h * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(right).subtract(up),
                center.subtract(right).add(up), center.add(right).add(up),
                center.add(right).subtract(up), 0, 0, 1, 1, alpha);
    }

    private static Vec3[] axes(WorldRenderContext c, double angle) {
        var q = c.camera().rotation();
        var r = q.transform(new org.joml.Vector3f(1, 0, 0));
        var u = q.transform(new org.joml.Vector3f(0, 1, 0));
        Vec3 right = new Vec3(r.x, r.y, r.z);
        Vec3 up = new Vec3(u.x, u.y, u.z);
        double cs = Math.cos(angle), sn = Math.sin(angle);
        return new Vec3[]{right.scale(cs).add(up.scale(sn)), up.scale(cs).subtract(right.scale(sn))};
    }

    /** Jagged polyline whose offsets live in the camera plane, so it reads as a drawn bolt. */
    private static Vec3[] zig(WorldRenderContext c, Vec3 from, Vec3 to, int segments, double amp,
                              double seed) {
        Vec3[] axes = axes(c, 0);
        Vec3 right = axes[0], up = axes[1];
        java.util.Random rnd = new java.util.Random((long) (seed * 9176) + 31);
        Vec3[] pts = new Vec3[segments + 1];
        pts[0] = from;
        pts[segments] = to;
        for (int i = 1; i < segments; i++) {
            double f = (double) i / segments;
            Vec3 base = from.lerp(to, f);
            double sign = (i % 2 == 0) ? 1 : -1;
            double dx = sign * amp * (.6 + .4 * rnd.nextDouble());
            double dy = (rnd.nextDouble() - .5) * amp * .35;
            pts[i] = base.add(right.scale(dx)).add(up.scale(dy));
        }
        return pts;
    }
}

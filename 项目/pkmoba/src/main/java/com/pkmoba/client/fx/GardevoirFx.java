package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionSkillMeshes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Gardevoir v8 - less clutter, bigger shapes, one clear idea per move.
 *
 * <ul>
 *   <li>Q 精神强念 Psychic - the aim point becomes a gravity well: a rune circle, two spinning
 *       arms and sparks that are dragged <b>inwards</b>, then one implosion. No projectile.</li>
 *   <li>W 月亮之力 Moonblast - a <b>solid 3D moon</b> drops from a low altitude (6 blocks, so a
 *       ceiling never hides it) and lands with one big flash and shockwave rings.</li>
 *   <li>E 瞬间移动 Teleport - phantom copies of the body along the jump.</li>
 *   <li>R 魔法闪耀 Dazzling Gleam - light leaves the caster's own body in every direction.</li>
 * </ul>
 */
public final class GardevoirFx {
    private GardevoirFx() {
    }

    private static final ResourceLocation RING = tex("gardevoir_ring");
    private static final ResourceLocation MOONSPHERE = tex("gardevoir_moonsphere");
    /** The classic v6 moon skin the user asked to get back. */
    private static final ResourceLocation MOON_CLASSIC = tex("gardevoir_moon_classic");
    private static final ResourceLocation CRUSH = tex("gardevoir_crush");
    private static final ResourceLocation FLASH = tex("gardevoir_flash");
    private static final ResourceLocation RAY = tex("gardevoir_ray");
    private static final ResourceLocation SPARK = tex("gardevoir_spark");
    private static final ResourceLocation GHOST = tex("gardevoir_ghost");
    private static final ResourceLocation GLOW = tex("gardevoir_glow");

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    public static void render(FxView v) {
        WorldRenderContext c = v.fx.context();
        float a = Math.min(1f, v.fade);
        switch (v.skill) {
            case 0 -> psychic(c, v, a);
            case 1 -> moonblast(c, v, a);
            case 2 -> teleport(c, v, a);
            case 3 -> gleam(c, v, a);
            default -> { }
        }
    }

    // ---- Q 精神强念：把周围的生物拖向落点 ------------------------------------------------------

    private static void psychic(WorldRenderContext c, FxView v, float a) {
        Vec3 centre = v.pos.add(0, .12, 0);
        if (v.is("start")) {
            range(c, v, 4.0, a, 1.0);
            face(c, GLOW, v.pos.add(0, 1.0, 0), 1.4, 1.4, 0, a * .5f);
        } else if (v.is("pull")) {
            // The well is drawn at its real radius (4 blocks) with the rim marked, then twelve
            // textured arms are dragged from the rim into the centre so the pull is unmistakable.
            range(c, v, 4.0, a * .95f, .9);
            double tighten = 1.0 - .55 * v.progress;
            for (int i = 0; i < 12; i++) {
                double fast = v.time * 1.6;
                double angle = fast + i * (Math.PI * 2 / 12);
                double rr = 3.85 * tighten;
                Vec3 from = centre.add(Math.cos(angle) * rr, .75 + .25 * Math.sin(i * 1.7 + v.time),
                        Math.sin(angle) * rr);
                Vec3 to = centre.add(Math.cos(angle + .45) * .35, 1.0, Math.sin(angle + .45) * .35);
                v.fx.flow(from, to, .55, v.time + i * .3, a * .95f);
            }
            for (int i = 0; i < 4; i++) {
                face(c, CRUSH, v.pos.add(0, 1.0, 0), 3.6 - .7 * i, 3.6 - .7 * i,
                        v.time * (1.2 + i * .6), a * (.55f - .1f * i));
            }
            face(c, GLOW, v.pos.add(0, 1.05, 0), 1.5 + .5 * v.progress, 1.5 + .5 * v.progress, 0,
                    a * .85f);
        } else if (v.is("hold")) {
            // Everything caught by the well is pinned: the rim snaps inward and the core swells.
            range(c, v, 4.0 - 1.9 * v.progress, a, 1.0);
            ExpansionSkillMeshes.sphere(c, v.pos.add(0, 1.0, 0),
                    new Vec3(.6 + .7 * v.progress, .6 + .7 * v.progress, .6 + .7 * v.progress),
                    v.time * 1.4, CRUSH, a * .95f, true);
            face(c, GLOW, v.pos.add(0, 1.0, 0), 2.2 + 1.2 * v.progress, 2.2 + 1.2 * v.progress, 0,
                    a * .95f);
            ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.02, 0), 2.0 - 1.0 * v.progress, .14, RING,
                    a * .9f);
        } else if (v.in("impact", "detonate")) {
            float burst = (float) Math.min(1.0, v.progress * 1.8);
            face(c, FLASH, v.pos.add(0, 1.0, 0), 2.0 + 2.6 * burst, 2.0 + 2.6 * burst, v.time,
                    a * (.9f - .3f * burst));
            ground(c, RING, centre, 2.6 + 3.0 * burst, a * (.8f - .25f * burst));
            for (int i = 0; i < 4; i++) {
                double angle = i * Math.PI / 2 + .5;
                face(c, SPARK, v.pos.add(Math.cos(angle) * (1.4 + 1.8 * burst), .7,
                        Math.sin(angle) * (1.4 + 1.8 * burst)), .5, .5, v.time * 2, a * .8f);
            }
        }
    }

    /** Draws the skill's real radius on the ground with a bright rim and 16 tick marks. */
    private static void range(WorldRenderContext c, FxView v, double radius, float a, double rim) {
        Vec3 base = v.pos.add(0, .06, 0);
        ground(c, RING, base, radius * 2.0, a * .55f);
        ExpansionSkillMeshes.ring(c, base.add(0, .02, 0), radius, .16, RING, a * (float) rim);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8.0;
            face(c, SPARK, base.add(Math.cos(angle) * radius, .05, Math.sin(angle) * radius),
                    .34, .34, angle, a * .9f);
        }
    }

    // ---- W 月亮之力：实体 3D 月亮砸下来 --------------------------------------------------------

    private static void moonblast(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            // Only the moon itself waits high above the aim point - no ground ring, no sparkles.
            Vec3 high = v.pos.add(0, 8.4 - 1.0 * v.progress, 0);
            ExpansionSkillMeshes.sphere(c, high, new Vec3(1.25, 1.25, 1.25), v.time * .5,
                    MOON_CLASSIC, a, true);
        } else if (v.is("launch")) {
            // Fire-and-forget: the client animates the whole fall from the single launch cue, so the
            // moon keeps dropping even if the caster starts Psychic halfway through.
            double q = Math.min(1.0, v.progress);
            double height = 8.5 * (1 - q * q);
            Vec3 at = v.pos.add(0, height, 0);
            double radius = 2.6;
            ExpansionSkillMeshes.sphere(c, at, new Vec3(radius * 1.02, radius * 1.02, radius * 1.02),
                    v.time * .3, MOON_CLASSIC, a * .4f, true);
            ExpansionSkillMeshes.sphere(c, at, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);
            for (int i = 1; i <= 3; i++) {
                double trail = Math.max(0, q * 26 - i * 2.2);
                double tq = Math.min(1.0, trail / 26.0);
                Vec3 past = v.pos.add(0, 8.5 * (1 - tq * tq), 0);
                ExpansionSkillMeshes.sphere(c, past,
                        new Vec3(radius * .55, radius * .55, radius * .55), v.time * .4,
                        MOON_CLASSIC, a * (.22f - .05f * i), true);
            }
        } else if (v.is("moon")) {
            // v6 moon: a solid lit sphere with craters that roll as it spins, two tumbling 3D
            // rings, a hot core, a four step trail and orbiting sparkles.
            double radius = 2.6;
            // Just the moon falling: one lit skin with a dim limb shell so it still reads as a ball.
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius * 1.02, radius * 1.02, radius * 1.02),
                    v.time * .3, MOON_CLASSIC, a * .35f, true);
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);
        } else if (v.in("impact", "detonate")) {
            // Ground zero is exactly the aimed point: every burst is centred on v.pos.
            float burst = (float) Math.min(1.0, v.progress * 1.5);
            ExpansionSkillMeshes.sphere(c, v.pos,
                    new Vec3(2.4 + 2.8 * burst, 1.9 + 2.4 * burst, 2.4 + 2.8 * burst), v.time,
                    MOON_CLASSIC, a * (.95f - .35f * burst), true);
            ExpansionSkillMeshes.sphere(c, v.pos,
                    new Vec3(1.6 + 3.0 * burst, 1.4 + 2.6 * burst, 1.6 + 3.0 * burst), -v.time,
                    GLOW, a * (.75f - .3f * burst), true);
            face(c, FLASH, v.pos.add(0, .9, 0), 2.6 + 4.4 * burst, 2.6 + 4.4 * burst, v.time * .6, a);
            for (int i = 0; i < 10; i++) {
                double angle = i * Math.PI / 5;
                face(c, RAY, v.pos.add(0, .8, 0), .6, 2.2 + 3.0 * burst, angle, a * .7f);
            }
            for (int i = 0; i < 3; i++) {
                ground(c, RING, v.pos.add(0, .06 + .03 * i, 0),
                        2.2 + (3.0 + i * 1.5) * Math.min(1, v.progress * (1 + .2 * i)),
                        a * (.9f - .25f * i));
            }
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4 + .4;
                double dist = 2.0 + 3.2 * burst;
                face(c, SPARK, v.pos.add(Math.cos(angle) * dist, .45 + .8 * (i % 3),
                        Math.sin(angle) * dist), .75, .75, v.time * 2, a * .9f);
            }
        }
    }

    // ---- E 瞬间移动：幻影的移动 ----------------------------------------------------------------

    private static void teleport(WorldRenderContext c, FxView v, float a) {
        if (v.is("blink")) {
            Vec3 from = v.pos.add(0, .9, 0);
            Vec3 to = v.dir.add(0, .9, 0);
            for (int i = 1; i <= 3; i++) {
                Vec3 at = from.lerp(to, i / 4.0);
                face(c, GHOST, at, 1.2, 1.7, 0, a * (0.4f - .09f * i));
            }
            face(c, GHOST, from, 1.2, 1.7, 0, a * .45f);
            face(c, GHOST, to, 1.3, 1.8, 0, a);
            face(c, FLASH, to, 2.2, 2.2, v.time, a * .85f);
            face(c, GLOW, to, 1.7, 1.7, 0, a * .65f);
            ExpansionSkillMeshes.ring(c, to, .95, .1, RING, a * .85f);
            for (int i = 0; i < 4; i++) {
                double angle = i * Math.PI / 2 + v.time;
                face(c, SPARK, to.add(Math.cos(angle) * 1.3, .5 - .35 * (i % 2),
                        Math.sin(angle) * 1.3), .6, .6, 0, a * .85f);
            }
            ground(c, RING, from.subtract(0, .85, 0), 1.2 + .9 * v.progress, a * .55f);
            ground(c, RING, to.subtract(0, .85, 0), 1.7 - .5 * v.progress, a * .9f);
        } else if (v.is("start")) {
            ground(c, RING, v.pos.add(0, .06, 0), 1.2 + 1.0 * v.progress, a * .7f);
            ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.0, 0), .8 + .3 * v.progress, .09, RING, a * .7f);
        }
    }

    // ---- R 魔法闪耀：以自身为中心向四周发射光 ---------------------------------------------------

    private static void gleam(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            Vec3 chest = v.pos.add(0, 1.15, 0);
            ExpansionSkillMeshes.sphere(c, chest, new Vec3(.7, .7, .7), v.time, GLOW,
                    a * (float) (.6 + .4 * v.progress), true);
            face(c, FLASH, chest, 1.6 + 1.6 * v.progress, 1.6 + 1.6 * v.progress, v.time * 1.4,
                    a * .95f);
            ExpansionSkillMeshes.ring(c, chest, 1.0 + .5 * v.progress, .1, RING, a * .85f);
            ground(c, RING, v.pos.add(0, .06, 0), 1.6 + 2.0 * v.progress, a * .7f);
        } else if (v.in("impact", "detonate")) {
            float burst = (float) Math.min(1.0, v.progress * 1.7);
            Vec3 core = v.pos.add(0, 1.0, 0);
            // a clean starburst: twelve 3D rays, one expanding glow ball, one flash, one ring
            double reach = 3.0 + 4.0 * burst;
            for (int i = 0; i < 12; i++) {
                double phi = Math.acos(1 - 2 * (i + .5) / 12);
                double theta = Math.PI * (1 + Math.sqrt(5)) * i + v.time * .25;
                Vec3 dir = new Vec3(Math.cos(theta) * Math.sin(phi), Math.cos(phi),
                        Math.sin(theta) * Math.sin(phi));
                ExpansionSkillMeshes.tube(c, core, core.add(dir.scale(reach)), .16, RAY,
                        a * (.95f - .25f * burst));
            }
            ExpansionSkillMeshes.sphere(c, core, new Vec3(1.2 + 3.4 * burst, 1.1 + 3.0 * burst,
                    1.2 + 3.4 * burst), v.time * .4, GLOW, a * (.85f - .3f * burst), true);
            face(c, FLASH, core, 3.0 + 4.0 * burst, 3.0 + 4.0 * burst, v.time * .9, a);
            ExpansionSkillMeshes.ring(c, core, 1.4 + 3.4 * burst, .16, RING, a * .95f);
            ground(c, RING, v.pos.add(0, .07, 0), 2.4 + 5.0 * burst, a * .95f);
            for (int i = 0; i < 8; i++) {
                double phi = Math.acos(1 - 2 * (i + .5) / 8);
                double theta = 2.2 * i + v.time * .9;
                double dist = 2.2 + 3.0 * burst;
                Vec3 at = core.add(Math.cos(theta) * Math.sin(phi) * dist,
                        Math.cos(phi) * dist + .3, Math.sin(theta) * Math.sin(phi) * dist);
                face(c, SPARK, at, .85, .85, v.time * 3, a * .9f);
            }
        }
    }

    // ---- primitives ---------------------------------------------------------------------------

    private static void face(WorldRenderContext c, ResourceLocation tex, Vec3 center, double w, double h,
                             double angle, float alpha) {
        if (alpha <= .01f) return;
        Vec3[] axes = axes(c, angle);
        Vec3 right = axes[0].scale(w * .5), up = axes[1].scale(h * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(right).subtract(up),
                center.subtract(right).add(up), center.add(right).add(up),
                center.add(right).subtract(up), 0, 0, 1, 1, alpha);
    }

    private static void ground(WorldRenderContext c, ResourceLocation tex, Vec3 center, double size,
                               float alpha) {
        if (alpha <= .01f) return;
        Vec3 x = new Vec3(size * .5, 0, 0), z = new Vec3(0, 0, size * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(x).subtract(z),
                center.subtract(x).add(z), center.add(x).add(z), center.add(x).subtract(z),
                0, 0, 1, 1, alpha);
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
}

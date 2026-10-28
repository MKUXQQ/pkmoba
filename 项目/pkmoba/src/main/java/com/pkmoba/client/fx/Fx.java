package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionSkillMeshes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Small textured drawing kit shared by the per-skill visuals.
 *
 * <p>Unlike the old shared pipeline, nothing here decides composition: each Pokemon/skill class
 * asks for the primitive it wants (a flame tongue, a claw crescent, a jaw, a snow banner...). The
 * textures are the actual art; this class only places them in the world.
 */
public final class Fx {
    private final WorldRenderContext c;
    public final ResourceLocation tex;

    public Fx(WorldRenderContext c, ResourceLocation tex) {
        this.c = c;
        this.tex = tex;
    }

    public WorldRenderContext context() {
        return c;
    }

    /** Camera right/up axes, optionally rotated inside the screen plane. */
    private Vec3[] axes(double angle) {
        var q = c.camera().rotation();
        var r = q.transform(new org.joml.Vector3f(1, 0, 0));
        var u = q.transform(new org.joml.Vector3f(0, 1, 0));
        Vec3 right = new Vec3(r.x, r.y, r.z);
        Vec3 up = new Vec3(u.x, u.y, u.z);
        double cs = Math.cos(angle), sn = Math.sin(angle);
        return new Vec3[]{right.scale(cs).add(up.scale(sn)), up.scale(cs).subtract(right.scale(sn))};
    }

    /**
    /** Camera-right axis in world space (used to offset paired effects, e.g. left/right kicks). */
    public Vec3 side() { return axes(0)[0]; }

    /** Camera-up axis in world space. */
    public Vec3 up() { return axes(0)[1]; }

    /**
     * Screen-facing textured plane. This is the "hand drawn sheet" look users asked for.
     *
     * <p>Every sheet is drawn twice: a wider soft halo pass plus the crisp motif on top. The halo
     * keeps the effect readable against dark terrain (the whole point of the pixel effect pass) and
     * makes overlapping layers blend into light instead of turning muddy.
     */
    public void sheet(Vec3 center, double w, double h, double angle, float alpha) {
        if (alpha <= 0) return;
        // Three stacked passes: wide aura, mid bloom, crisp motif. Overlapping alpha fills add up
        // toward white in the middle, which is what makes the sheets read as emissive neon instead
        // of dark paint.
        quad(center, w * 1.62, h * 1.62, angle - .09, 0, 1, 1, 0, alpha * .34f);
        quad(center, w * 1.18, h * 1.18, angle - .03, 0, 1, 1, 0, alpha * .30f);
        quad(center, w, h, angle, 0, 1, 1, 0, Math.min(1f, alpha * 1.08f));
    }

    private void quad(Vec3 center, double w, double h, double angle,
                      float u0, float v0, float u1, float v1, float alpha) {
        Vec3[] ax = axes(angle);
        Vec3 r = ax[0].scale(w * .5), u = ax[1].scale(h * .5);
        ExpansionSkillMeshes.raw(c, tex, center.subtract(r).subtract(u), center.subtract(r).add(u),
                center.add(r).add(u), center.add(r).subtract(u), u0, v0, u1, v1, alpha);
    }

    /**
     * Flowing packets run from {@code from} to {@code to}. This is the "流动感" layer: the sheets
     * keep marching along the path, so even a standing projectile keeps streaming.
     */
    public void flow(Vec3 from, Vec3 to, double width, double time, float alpha) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1E-5 || alpha <= 0) return;
        int count = 7;
        for (int i = 0; i < count; i++) {
            double u = ((i / (double) count) + time * .22) % 1.0;
            Vec3 p = from.add(axis.scale(u));
            double taper = .55 + .75 * Math.sin(u * Math.PI);
            Vec3 view = c.camera().getPosition().subtract(p);
            Vec3 side = axis.cross(view);
            if (side.lengthSqr() < 1E-7) side = axis.cross(new Vec3(0, 1, 0));
            side = side.normalize();
            double w = width * taper;
            ExpansionSkillMeshes.raw(c, tex, p.subtract(side.scale(w * .5)), p.add(side.scale(w * .5)),
                    p.add(side.scale(w * .5)).add(axis.normalize().scale(w * .9)),
                    p.subtract(side.scale(w * .5)).add(axis.normalize().scale(w * .9)),
                    0, 1, .35f, 0, alpha * (float) (.45 + .55 * Math.sin(u * Math.PI)));
        }
    }

    /**
     * Chunky pixel sparks: small camera-facing quads that sample one tile of the motif texture.
     * Reading a single tile (instead of the whole picture) gives the blocky Minecraft particle feel.
     */
    public void pixels(Vec3 base, double radius, int count, double size, double time, float alpha) {
        if (alpha <= 0) return;
        Vec3[] ax = axes(0);
        count = (int) (count * 1.8);
        for (int i = 0; i < count; i++) {
            double seed = (i * 0.6180339887) % 1.0;
            double ang = i * 2.399963 + seed * 6.28;
            double h = ((i * 0.37 + time * .085) % 1.0);
            double rr = radius * (.35 + .65 * seed);
            double spread = radius * (.25 + .30 * h);
            Vec3 p = base.add(Math.cos(ang) * (rr + h * radius * .5), (h - .35) * radius * 1.2,
                    Math.sin(ang) * (rr + h * radius * .5));
            double s = size * (.55 + .9 * ((i * 0.7071) % 1.0));
            double u = ((i * 3) % 4) * .25, v = ((i * 5) % 4) * .25;
            Vec3 r = ax[0].scale(s * .5), u2 = ax[1].scale(s * .5);
            ExpansionSkillMeshes.raw(c, tex, p.subtract(r).subtract(u2), p.subtract(r).add(u2),
                    p.add(r).add(u2), p.add(r).subtract(u2), (float) u, (float) v,
                    (float) (u + .25), (float) (v + .25), alpha * (float) (.55 + .45 * seed));
            if (spread > .5) {
                // Second pixel so clusters read as blocks, not single dots.
                Vec3 q = p.add(ax[0].scale(seed * .12 - .06)).add(ax[1].scale(.10 - seed * .12));
                ExpansionSkillMeshes.raw(c, tex, q.subtract(r).subtract(u2), q.subtract(r).add(u2),
                        q.add(r).add(u2), q.add(r).subtract(u2), (float) u, (float) v,
                        (float) (u + .25), (float) (v + .25), alpha * .5f);
            }
        }
    }

    /** Vertical spinning tornado: stacked ribbons that widen toward the top and drift upwards. */
    public void tornado(Vec3 base, double radius, double height, double time, float alpha) {
        int layers = 9;
        for (int i = 0; i < layers; i++) {
            double f = i / (double) (layers - 1);
            double ang = time * .55 + i * 1.15;
            double r = radius * (.45 + .75 * f);
            Vec3 p = base.add(Math.cos(ang) * r, f * height + Math.sin(time * .3 + i) * .04, Math.sin(ang) * r);
            double w = radius * (1.15 - .35 * f);
            sheet(p, w, w * .85, ang + Math.PI * .5, alpha * (float) (.35 + .65 * (1 - f) + .2 * f));
        }
    }

    /** Big, slow drifting fog puffs filling an area (blizzard / mist fields). */
    public void mist(Vec3 base, double radius, int count, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double seed = (i * .6180339887) % 1.0;
            double ang = i * 2.399963 + time * .02;
            double r = radius * (.25 + .75 * seed);
            Vec3 p = base.add(Math.cos(ang) * r, .25 + seed * 1.1 + Math.sin(time * .1 + i) * .12,
                    Math.sin(ang) * r);
            sheet(p, radius * .95, radius * .8, ang * .4 + time * .02, alpha * (float) (.30 + .35 * seed));
        }
    }

    /** Frost/flame spray cone: many small sheets pushed out along a direction. */
    public void cone(Vec3 from, Vec3 dir, double length, double spread, double time, float alpha) {
        int count = 16;
        for (int i = 0; i < count; i++) {
            double f = (i + .5) / count;
            double seed = (i * .618) % 1.0;
            Vec3 p = from.add(dir.scale(length * f))
                    .add(axes(0)[0].scale(Math.sin(i * 2.1 + time * .3) * spread * f + (seed - .5) * spread * .5))
                    .add(axes(0)[1].scale(Math.cos(i * 1.7 + time * .25) * spread * f * .6));
            double s = (.28 + .5 * seed) * (1 - .3 * f);
            sheet(p, s, s, time * .3 + i, alpha * (float) (.5 + .5 * (1 - f)));
        }
    }

    /** Punch flash: paired streak + star burst at a contact point. */
    public void fist(Vec3 at, Vec3 dir, double size, float alpha) {
        Vec3 back = at.subtract(dir.normalize().length() < 1E-6 ? new Vec3(0, 0, 1) : dir.normalize().scale(size * .8));
        streak(back, at, size * .55, alpha * .8f);
        sheet(at, size, size, timeHash(at), alpha);
        burst(at, size * .9, 6, .35, 0, alpha * .8f);
    }

    private static double timeHash(Vec3 p) {
        return (p.x * 12.9898 + p.y * 78.233 + p.z * 37.719) % 6.283;
    }

    /** Low dust puffs hugging the ground (earth, sand, dirt). */
    public void dust(Vec3 base, double radius, int count, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double seed = (i * .6180339887) % 1.0;
            double ang = i * 2.399963 + time * .03;
            double r = radius * (.25 + .75 * seed);
            double rise = .12 + .35 * ((i * .37 + time * .02) % 1.0);
            Vec3 p = base.add(Math.cos(ang) * r, rise, Math.sin(ang) * r);
            double s = .35 + .35 * seed;
            sheet(p, s, s * .8, ang + time * .02, alpha * (float) (.4 + .4 * (1 - seed)));
        }
    }

    /** Upright spikes breaking out of the ground around a point. */
    public void spikeRing(Vec3 base, double radius, int count, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count;
            Vec3 p = base.add(Math.cos(ang) * radius * .7, .25, Math.sin(ang) * radius * .7);
            sheet(p, .35, .9, ang, alpha * (float) (.6 + .4 * ((i * .41) % 1.0)));
        }
    }

    /** Radial star burst: short streaks + a bright centre, used for psychic detonations. */
    public void starBurst(Vec3 center, double length, int count, double time, float alpha) {
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + time * .1;
            Vec3 dir = side().scale(Math.cos(ang)).add(up().scale(Math.sin(ang)));
            streak(center, center.add(dir.scale(length)), .07 + .02 * Math.sin(i + time), alpha * .8f);
        }
        sheet(center, length * .9, length * .9, time * .3, alpha);
        glow(center, length * .8, alpha * .7f);
    }

    /** Big soft halo used to keep a skill lit up from far away. */
    public void glow(Vec3 center, double radius, float alpha) {
        sheet(center, radius * 2.0, radius * 2.0, 0, alpha * .55f);
        sheet(center, radius * 1.2, radius * 1.2, 0, alpha * .35f);
    }

    /** Vertical ribbon between two points, always facing the camera (energy beam / water jet). */
    public void beam(Vec3 from, Vec3 to, double width, float alpha) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1E-7 || alpha <= 0) return;
        Vec3 mid = from.add(to).scale(.5);
        Vec3 view = c.camera().getPosition().subtract(mid);
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1E-7) side = axis.cross(new Vec3(0, 1, 0));
        side = side.normalize().scale(width * .5);
        ExpansionSkillMeshes.raw(c, tex, from.subtract(side), from.add(side),
                to.add(side), to.subtract(side), 0, 1, 1, 0, alpha);
    }

    /** Thin speed line with tapering width. */
    public void streak(Vec3 from, Vec3 to, double width, float alpha) {
        beam(from, to, width, alpha);
    }

    /** Horizontal textured shock disc lying on the ground. */
    public void disc(Vec3 center, double radius, float alpha) {
        if (alpha <= 0 || radius <= 0) return;
        int steps = 14;
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps, b = a + Math.PI * 2 / steps;
            Vec3 p = center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            Vec3 q = center.add(Math.cos(b) * radius, 0, Math.sin(b) * radius);
            ExpansionSkillMeshes.raw(c, tex, center, p, q, center, .5f, .5f,
                    (float) (.5 + .5 * Math.cos(a)), (float) (.5 + .5 * Math.sin(a)), alpha);
        }
    }

    /** Crescent slash made from textured arc segments (claw marks, sword waves, kicks). */
    public void crescent(Vec3 center, double radius, double startAngle, double sweep,
                         double thickness, double angleInScreen, float alpha) {
        if (alpha <= 0) return;
        int steps = 10;
        Vec3[] ax = axes(angleInScreen);
        Vec3 right = ax[0], up = ax[1];
        double step = sweep / steps;
        for (int i = 0; i < steps; i++) {
            double a = startAngle + step * i, b = a + step;
            double fade = 1 - Math.abs((i + .5) / steps - .5) * .8;
            Vec3 pa = center.add(right.scale(Math.cos(a) * radius)).add(up.scale(Math.sin(a) * radius));
            Vec3 pb = center.add(right.scale(Math.cos(b) * radius)).add(up.scale(Math.sin(b) * radius));
            Vec3 pia = center.add(right.scale(Math.cos(a) * (radius - thickness))).add(up.scale(Math.sin(a) * (radius - thickness)));
            Vec3 pib = center.add(right.scale(Math.cos(b) * (radius - thickness))).add(up.scale(Math.sin(b) * (radius - thickness)));
            ExpansionSkillMeshes.raw(c, tex, pia, pa, pb, pib, i / (float) steps, 1,
                    (i + 1) / (float) steps, 0, (float) (alpha * fade));
        }
    }

    /** Two crossing slashes, used by claw and sword moves. */
    public void cross(Vec3 center, double size, double spin, float alpha) {
        crescent(center, size, -.6, 1.9, size * .45, spin, alpha);
        crescent(center, size * 1.12, 2.5, 1.9, size * .34, spin, alpha * .85f);
    }

    /** Ring of textured flame tongues rising from a base point. */
    public void flames(Vec3 base, double radius, int count, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + time * .04;
            double h = (i % 4) * .22 + .25;
            Vec3 p = base.add(Math.cos(ang) * radius * (.35 + (i % 3) * .22), h, Math.sin(ang) * radius * (.35 + (i % 3) * .22));
            sheet(p, .55 + (i % 3) * .18, .85 + (i % 4) * .22, ang + time * .05, alpha * (i % 2 == 0 ? 1 : .75f));
        }
    }

    public void sphere(Vec3 p, Vec3 scale, double rotation, boolean rough, float alpha) {
        ExpansionSkillMeshes.sphere(c, p, scale, rotation, tex, alpha, rough);
    }

    public void crystal(Vec3 base, double width, double height, double rotation, float alpha) {
        ExpansionSkillMeshes.crystal(c, base, width, height, rotation, tex, alpha);
    }

    public void tube(Vec3 from, Vec3 to, double radius, float alpha) {
        ExpansionSkillMeshes.tube(c, from, to, radius, tex, alpha);
    }

    public void ring(Vec3 center, double radius, double thickness, float alpha) {
        ExpansionSkillMeshes.ring(c, center, radius, thickness, tex, alpha);
    }

    /** Closed torus whose proud face is a different texture region (energy halo). */
    public void halo(Vec3 center, double radius, double thickness, float alpha) {
        ring(center, radius, thickness, alpha);
    }

    public void ringWave(Vec3 center, double radius, double thickness, int layers, double time, float alpha) {
        for (int i = 0; i < layers; i++) {
            double r = radius * (1 + i * .28);
            ring(center, r, thickness * (1 - i * .15), alpha * (1 - i * .22f));
        }
    }

    public void groundRing(Vec3 base, double radius, int layers, double time, float alpha) {
        for (int i = 0; i < layers; i++) {
            disc(base.add(0, .02 + i * .01, 0), radius * (1 - i * .22), alpha * (1 - i * .28f));
        }
    }

    /** Concrete debris blocks: rough spheres with the move texture. */
    public void rocks(Vec3 base, double radius, int count, double size, double time, float alpha) {
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .03;
            double r = radius * (.25 + (i % 5) / 6.0);
            Vec3 p = base.add(Math.cos(ang) * r, .18 + (i % 4) * .30, Math.sin(ang) * r);
            sphere(p, new Vec3(size, size * 1.15, size * .92), ang, true, alpha * (i % 2 == 0 ? 1 : .8f));
        }
    }

    /** Flying ice shards / crystal splinters. */
    public void shards(Vec3 base, double radius, int count, double height, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .04;
            double r = radius * (.3 + (i % 4) / 5.0);
            Vec3 p = base.add(Math.cos(ang) * r, .2 + (i % 5) * height * .4, Math.sin(ang) * r);
            crystal(p, .10 + (i % 3) * .02, height * (.7 + (i % 4) * .12), ang + i, alpha);
        }
    }

    /** Round sparks/fireflies as tiny glowing balls. */
    public void motes(Vec3 base, double radius, int count, double size, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .10;
            double r = radius * (.25 + (i % 6) / 7.0);
            Vec3 p = base.add(Math.cos(ang) * r, .2 + (i % 5) * .32, Math.sin(ang) * r);
            sphere(p, new Vec3(size, size, size), ang, true, alpha * (i % 3 == 0 ? 1 : .7f));
        }
    }

    /** Radial burst of streaks, e.g. a fist shockwave. */
    public void burst(Vec3 center, double length, int count, double spreadY, double time, float alpha) {
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + time * .03;
            Vec3 dir = new Vec3(Math.cos(ang), spreadY * Math.sin(i * 1.7), Math.sin(ang)).normalize();
            streak(center.add(dir.scale(.15)), center.add(dir.scale(length)), .09, alpha * (i % 2 == 0 ? 1 : .7f));
        }
    }

    /** Orbiting textured sheets around a body part (psychic runes, spinning blades). */
    public void orbit(Vec3 center, double radius, int count, double size, double time, float alpha, double tilt) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = time * .12 + i * Math.PI * 2 / count;
            double y = Math.sin(ang * 1.6 + tilt) * radius * .30;
            Vec3 p = center.add(Math.cos(ang) * radius, y, Math.sin(ang) * radius);
            sheet(p, size, size, ang + time * .2, alpha);
        }
    }

    /** Falling rune/particle curtain from above. */
    public void rain(Vec3 center, double radius, double height, int count, double size, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996;
            double f = (time * .05 + i * .13) % 1;
            Vec3 p = center.add(Math.cos(ang) * radius * (.3 + (i % 4) / 5.0), height * (1 - f), Math.sin(ang) * radius * (.3 + (i % 4) / 5.0));
            sheet(p, size, size * 2.1, ang, alpha * (float) (1 - f * .7));
        }
    }

    /** Texture sheet that follows the ground with a swirl, for sand/fog fields. */
    public void fog(Vec3 base, double radius, int count, double size, double time, float alpha) {
        count = (int) (count * 1.5);
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .03;
            Vec3 p = base.add(Math.cos(ang) * radius * (.2 + (i % 5) / 6.0),
                    .3 + (i % 4) * .35, Math.sin(ang) * radius * (.2 + (i % 5) / 6.0));
            sheet(p, size, size * .7, ang, alpha * (i % 2 == 0 ? 1 : .6f));
        }
    }
}

package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;

/** Closed UV-mapped effect geometry. No billboard or camera-facing surface is used. */
public final class ExpansionSkillMeshes {
    private ExpansionSkillMeshes() {}

    public static void sphere(WorldRenderContext c, Vec3 p, Vec3 scale, double rotation,
                              ResourceLocation texture, float alpha, boolean rough) {
        for (int y = 0; y < 8; y++) {
            for (int i = 0; i < 12; i++) {
                double u = i / 12.0, v = y / 8.0;
                face(c, texture, p,
                        point(u, v, scale, rotation, rough), point(u + 1.0 / 12, v, scale, rotation, rough),
                        point(u + 1.0 / 12, v + .125, scale, rotation, rough), point(u, v + .125, scale, rotation, rough),
                        (float) u, (float) v, 1F / 12, .125F, alpha);
            }
        }
    }

    private static Vec3 point(double u, double v, Vec3 s, double rotation, boolean rough) {
        double a = u * Math.PI * 2, lat = v * Math.PI;
        double bump = rough ? 1 + .16 * Math.sin(a * 3) * Math.sin(lat * 4) + .08 * Math.cos(a * 5 + lat * 2) : 1;
        return new Vec3(Math.cos(a) * Math.sin(lat) * s.x * bump,
                Math.cos(lat) * s.y * bump, Math.sin(a) * Math.sin(lat) * s.z * bump).yRot((float) rotation);
    }

    /** Six-sided crystal with a capped root, faceted shoulder and pointed tip. */
    public static void crystal(WorldRenderContext c, Vec3 base, double width, double height,
                               double rotation, ResourceLocation texture, float alpha) {
        for (int i = 0; i < 6; i++) {
            double a = rotation + i * Math.PI / 3, b = a + Math.PI / 3;
            Vec3 lo = new Vec3(Math.cos(a) * width, 0, Math.sin(a) * width);
            Vec3 next = new Vec3(Math.cos(b) * width, 0, Math.sin(b) * width);
            Vec3 hi = lo.scale(.75).add(0, height * .62, 0);
            Vec3 hiNext = next.scale(.75).add(0, height * .62, 0);
            face(c, texture, base, lo, hi, hiNext, next, i / 6F, 0, 1F / 6, .65F, alpha);
            face(c, texture, base, hi, new Vec3(0, height, 0), new Vec3(0, height, 0), hiNext,
                    i / 6F, .65F, 1F / 6, .35F, alpha);
            face(c, texture, base, Vec3.ZERO, lo, next, Vec3.ZERO, 0, 0, 1, 1, alpha);
        }
    }

    /** Closed solid tube between authoritative endpoints; useful for energy/drain and short sparks. */
    public static void tube(WorldRenderContext c, Vec3 from, Vec3 to, double radius,
                            ResourceLocation texture, float alpha) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < 1E-8) return;
        Vec3 n = delta.normalize();
        Vec3 right = n.cross(Math.abs(n.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = n.cross(right).normalize();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, b = a + Math.PI / 4;
            Vec3 r = right.scale(Math.cos(a) * radius).add(up.scale(Math.sin(a) * radius));
            Vec3 s = right.scale(Math.cos(b) * radius).add(up.scale(Math.sin(b) * radius));
            face(c, texture, from, r, r.add(delta), s.add(delta), s, i / 8F, 0, .125F, 1, alpha);
            face(c, texture, from, Vec3.ZERO, r, s, Vec3.ZERO, 0, 0, 1, 1, alpha);
            face(c, texture, to, Vec3.ZERO, s, r, Vec3.ZERO, 0, 0, 1, 1, alpha);
        }
    }

    /** Low polygon torus: a volumetric telegraph, never a transparent ground sheet. */
    public static void ring(WorldRenderContext c, Vec3 center, double radius, double thickness,
                            ResourceLocation texture, float alpha) {
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12, b = a + Math.PI / 12;
            for (int j = 0; j < 4; j++) {
                double f = j * Math.PI / 2, g = f + Math.PI / 2;
                face(c, texture, center, torus(a, f, radius, thickness), torus(a, g, radius, thickness),
                        torus(b, g, radius, thickness), torus(b, f, radius, thickness),
                        i / 24F, j / 4F, 1F / 24, .25F, alpha);
            }
        }
    }

    private static Vec3 torus(double a, double b, double r, double w) {
        return new Vec3(Math.cos(a) * (r + Math.cos(b) * w), Math.sin(b) * w,
                Math.sin(a) * (r + Math.cos(b) * w));
    }

    /**
     * Textured quad given as absolute world corners in winding order a→b→c→d.
     *
     * <p>This is the low-level door used by the per-skill texture effects (slash fans, rune sheets,
     * flame tongues, snow banners). Everything is camera-relative before upload so it stays stable
     * at large coordinates.
     */
    public static void raw(WorldRenderContext c, ResourceLocation t, Vec3 a, Vec3 b, Vec3 d, Vec3 e,
                           float u0, float v0, float u1, float v1, float alpha) {
        if (c.consumers() == null || c.matrixStack() == null) return;
        Vec3 origin = c.camera().getPosition();
        Vec3 pa = a.subtract(origin), pb = b.subtract(origin), pd = d.subtract(origin), pe = e.subtract(origin);
        Vec3 n = pb.subtract(pa).cross(pe.subtract(pa));
        if (n.lengthSqr() > 1E-9) n = n.normalize();
        float shade = (float) (.90 + .10 * Math.max(0, n.dot(new Vec3(-.35, .8, .48))));
        VertexConsumer consumer = c.consumers().getBuffer(RenderType.entityTranslucentEmissive(t));
        Matrix4f matrix = c.matrixStack().last().pose();
        // Minecraft samples texture V=0 at the TOP row, while quad corners a/e are the bottom edge.
        // The V coordinates are therefore mirrored here; without this every motif renders upside
        // down (flames pointing into the floor, crescents bending the wrong way).
        vertex(consumer, matrix, pa, n, u0, v1, shade, alpha);
        vertex(consumer, matrix, pb, n, u0, v0, shade, alpha);
        vertex(consumer, matrix, pd, n, u1, v0, shade, alpha);
        vertex(consumer, matrix, pe, n, u1, v1, shade, alpha);
    }

    private static void face(WorldRenderContext c, ResourceLocation t, Vec3 origin,                             Vec3 a, Vec3 b, Vec3 d, Vec3 e, float u, float v, float w, float h, float alpha) {
        if (c.consumers() == null || c.matrixStack() == null) return;
        Vec3 offset = origin.subtract(c.camera().getPosition());
        Vec3 n = b.subtract(a).cross(e.subtract(a)).normalize();
        float shade = (float) (.90 + .10 * Math.max(0, n.dot(new Vec3(-.35, .8, .48))));
        VertexConsumer consumer = c.consumers().getBuffer(RenderType.entityTranslucentEmissive(t));
        Matrix4f matrix = c.matrixStack().last().pose();
        vertex(consumer, matrix, a.add(offset), n, u, v, shade, alpha);
        vertex(consumer, matrix, b.add(offset), n, u, v + h, shade, alpha);
        vertex(consumer, matrix, d.add(offset), n, u + w, v + h, shade, alpha);
        vertex(consumer, matrix, e.add(offset), n, u + w, v, shade, alpha);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 p, Vec3 n,
                               float u, float v, float shade, float alpha) {
        int value = (int) (255 * shade);
        out.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(value, value, value, (int) (255 * Math.clamp((float) Math.sqrt(alpha), 0F, 1F)))
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                // Uniform up normal: the emissive shader dims faces whose normal points away from
                // the light (lightAccum down to 0.4), which reads as a grey veil. (0,1,0) keeps it at 1.0.
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}

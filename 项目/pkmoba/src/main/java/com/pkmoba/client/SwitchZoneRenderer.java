package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Draws the red and blue four-point switching polygons sent by the server. */
public final class SwitchZoneRenderer {
    private static final long TTL_NANOS = 1_600_000_000L;
    private static final class Polygon {
        final Vec3[] points = new Vec3[4];
        final long[] updated = new long[4];
        final boolean red;
        Polygon(boolean red) { this.red = red; }
        boolean complete(long now) {
            for (int i = 0; i < 4; i++) {
                if (points[i] == null || now - updated[i] > TTL_NANOS) return false;
            }
            return true;
        }
    }

    private static final List<Polygon> POLYGONS = new ArrayList<>();
    private static net.minecraft.client.multiplayer.ClientLevel polygonLevel;

    private SwitchZoneRenderer() {}

    public static void init() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SwitchZoneRenderer::render);
    }

    public static boolean accept(EffectPayload payload) {
        String id = payload.effectId();
        if (!id.startsWith("pkmoba:switch_zone/")) return false;
        String[] parts = id.split("/");
        if (parts.length != 3) return true;
        boolean red;
        if (parts[1].equals("red")) red = true;
        else if (parts[1].equals("blue")) red = false;
        else return true;
        int index;
        try { index = Integer.parseInt(parts[2]); }
        catch (NumberFormatException ignored) { return true; }
        if (index < 0 || index >= 4 || !Double.isFinite(payload.x())
                || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())) return true;

        var level = Minecraft.getInstance().level;
        if (polygonLevel != level) {
            POLYGONS.clear();
            polygonLevel = level;
        }
        Polygon polygon = POLYGONS.stream().filter(p -> p.red == red).findFirst().orElseGet(() -> {
            Polygon created = new Polygon(red);
            POLYGONS.add(created);
            return created;
        });
        polygon.points[index] = new Vec3(payload.x(), payload.y(), payload.z());
        polygon.updated[index] = System.nanoTime();
        return true;
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (polygonLevel != mc.level) {
            POLYGONS.clear();
            polygonLevel = mc.level;
        }
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        POLYGONS.removeIf(p -> {
            boolean expired = true;
            for (int i = 0; i < 4; i++) expired &= p.points[i] == null || now - p.updated[i] > TTL_NANOS;
            return expired;
        });
        if (context.matrixStack() == null || context.consumers() == null) return;

        Vec3 camera = context.camera().getPosition();
        PoseStack stack = context.matrixStack();
        stack.pushPose();
        stack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer out = context.consumers().getBuffer(RenderType.debugQuads());
        for (Polygon polygon : POLYGONS) {
            if (!polygon.complete(now)) continue;
            Vec3 center = polygon.points[0];
            if (center.distanceToSqr(camera) > 160 * 160) continue;
            for (int i = 0; i < 4; i++) {
                Vec3 a = polygon.points[i];
                Vec3 b = polygon.points[(i + 1) % 4];
                drawEdge(out, stack.last(), polygon, a, b);
            }
        }
        stack.popPose();
    }

    private static void drawEdge(VertexConsumer out, PoseStack.Pose pose, Polygon polygon, Vec3 a, Vec3 b) {
        int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) * 2));
        double width = .055;
        for (int i = 0; i < steps; i++) {
            Vec3 p = a.lerp(b, i / (double) steps);
            Vec3 q = a.lerp(b, (i + 1) / (double) steps);
            Vec3 direction = new Vec3(q.x - p.x, 0, q.z - p.z);
            double length = Math.sqrt(direction.lengthSqr());
            if (length < 1.0E-6) continue;
            Vec3 side = new Vec3(-direction.z / length * width, 0, direction.x / length * width);
            quad(out, pose, polygon.red, p.add(side), q.add(side), q.subtract(side), p.subtract(side));
        }
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, boolean red,
                             Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        for (Vec3 point : new Vec3[]{a, b, c, d}) {
            var mc = Minecraft.getInstance();
            var hit = mc.level.clip(new ClipContext(point.add(0, 3, 0), point.add(0, -5, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            double y = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                    ? point.y : hit.getLocation().y;
            out.addVertex(pose, (float) point.x, (float) (y + .045), (float) point.z)
                    .setColor(red ? 1f : .18f, red ? .28f : .70f, red ? .27f : 1f, .9f)
                    .setNormal(0, 1, 0);
        }
    }
}

package com.pkmoba.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared finite 3D Q sector: distances are radial, never longer than ten blocks. */
public final class FlameJetGeometry {
    public static final double RANGE = 10.0;
    public static final double HALF_ANGLE = Math.toRadians(20.0);
    private static final double COS = Math.cos(HALF_ANGLE), SIN = Math.sin(HALF_ANGLE);
    private FlameJetGeometry() { }

    public static Vec3 project(Vec3 relative, Vec3 dir, double range) {
        double length = relative.length();
        if (length < 1.0E-9) return Vec3.ZERO;
        double along = relative.dot(dir);
        if (along >= length * COS) return relative.scale(Math.min(1, range / length));
        Vec3 lateral = relative.subtract(dir.scale(along));
        if (lateral.lengthSqr() < 1.0E-12) return Vec3.ZERO;
        Vec3 edge = dir.scale(COS).add(lateral.normalize().scale(SIN));
        return edge.scale(Math.max(0, Math.min(range, relative.dot(edge))));
    }

    public static Vec3 clamp(Vec3 p, AABB box) {
        return new Vec3(Math.max(box.minX, Math.min(box.maxX, p.x)),
                Math.max(box.minY, Math.min(box.maxY, p.y)),
                Math.max(box.minZ, Math.min(box.maxZ, p.z)));
    }

    /** Alternating projections onto two convex volumes, including face/edge contact. */
    public static Vec3 contact(AABB box, Vec3 origin, Vec3 dir, double range) {
        Vec3 point = clamp(origin, box);
        for (int i = 0; i < 96; i++) {
            Vec3 sector = origin.add(project(point.subtract(origin), dir, range));
            Vec3 next = clamp(sector, box);
            if (next.distanceToSqr(sector) < 1.0E-10) return sector;
            point = next;
        }
        return null;
    }
}

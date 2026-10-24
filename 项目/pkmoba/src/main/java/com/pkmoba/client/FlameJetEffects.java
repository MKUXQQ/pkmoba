package com.pkmoba.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.FlameJetGeometry;
import com.pkmoba.network.JetPayload;
import com.pkmoba.particle.PkmobaParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Q particles stay inside the same finite, wall-clipped sector as server damage. */
public final class FlameJetEffects {
    private FlameJetEffects() { }

    public static void spawn(Minecraft mc, JetPayload p) {
        var level = mc.level;
        if (level == null || !(level.getEntity(p.entityId()) instanceof PokemonEntity entity)) return;
        Vec3 origin = new Vec3(p.x(), p.y(), p.z());
        Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
        if (!finite(origin) || !finite(dir) || !Double.isFinite(p.length()) || dir.lengthSqr() < 1.0E-8) return;
        dir = dir.normalize();
        double length = Math.max(0, Math.min(FlameJetGeometry.RANGE, p.length()));
        if (length < 0.1) return;

        // 用户要求"从嘴巴喷火"：模型 mouth locator 就是火焰起点。
        // 服务端 origin 只作为权限/伤害锚点，这里优先用真实嘴部位置，取不到才回退服务端原点。
        Vec3 mouth = mouthOf(entity);
        if (mouth != null && finite(mouth) && mouth.distanceTo(origin) < 2.5) {
            length = Math.max(0.1, Math.min(FlameJetGeometry.RANGE, length + mouth.distanceTo(origin)));
            origin = mouth;
        }

        Vec3 right = new Vec3(0, 1, 0).cross(dir).normalize();
        Vec3 up = dir.cross(right).normalize();
        // 粒子比"恢复火焰"那版更小（用户反馈太大），但数量加密保证整条火柱连成一片。
        int rays = 60;
        for (int i = 0; i < rays; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double distance = 0.4 + Math.max(0, length - 0.9) * ((i + level.random.nextDouble()) / (double) rays);
            double theta = Math.max(0, FlameJetGeometry.HALF_ANGLE - Math.asin(Math.min(1, 0.28 / distance)))
                    * Math.sqrt(level.random.nextDouble());
            Vec3 ray = dir.scale(Math.cos(theta)).add(right.scale(Math.sin(theta) * Math.cos(angle)))
                    .add(up.scale(Math.sin(theta) * Math.sin(angle)));
            Vec3 end = origin.add(ray.scale(length));
            HitResult hit = level.clip(new ClipContext(origin, end,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            double reach = hit.getType() == HitResult.Type.BLOCK ? origin.distanceTo(hit.getLocation()) : length;
            float scale = 0.75F + level.random.nextFloat() * 0.55F;
            // 粒子自身有半径，越靠近墙面越要留出余量，避免穿墙。
            double usable = Math.max(0, reach - (0.2 + scale * 0.4));
            if (distance > usable) continue;
            Vec3 point = origin.add(ray.scale(distance));
            puff(mc, point, ray.scale(0.05), scale, 5 + level.random.nextInt(4));
        }
    }

    /** 取模型的嘴部定位点（客户端渲染信息，取不到就返回 null）。 */
    private static Vec3 mouthOf(PokemonEntity entity) {
        try {
            if (entity.getDelegate() instanceof PokemonClientDelegate delegate) {
                var locator = delegate.getLocatorStates().get("mouth");
                if (locator != null) return locator.getOrigin();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static void puff(Minecraft mc, Vec3 point, Vec3 velocity, float scale, int lifeTicks) {
        // Also reject grazing side-wall intersections (a ray-only margin is insufficient there).
        if (mc.level.getBlockCollisions(null,
                new net.minecraft.world.phys.AABB(point, point).inflate(0.6)).iterator().hasNext()) return;
        var particle = mc.particleEngine.createParticle(PkmobaParticles.FLAME_PUFF,
                point.x, point.y, point.z, velocity.x, velocity.y, velocity.z);
        if (particle != null) {
            particle.scale(scale);
            particle.setLifetime(lifeTicks);
        }
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}

package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;

/** Swept-volume targeting and sub-stepped, grounded dash/blink movement. Never edits blocks. */
public final class ExpansionGeometry {
    private ExpansionGeometry() {}
    public static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    public static Vec3 direction(PokemonEntity entity, Vec3 aim) {
        Vec3 d = aim.subtract(entity.position()).multiply(1, 0, 1);
        if (d.lengthSqr() < 1e-6) d = entity.getLookAngle().multiply(1, 0, 1);
        return d.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : d.normalize();
    }
    public static Vec3 point(PokemonEntity entity, Vec3 aim, double range) {
        Vec3 d = aim.subtract(entity.position()).multiply(1, 0, 1);
        if (d.length() > range) d = d.normalize().scale(range);
        Vec3 desired = CharizardCombat.clampToArena(entity, entity.position().add(d));
        double ground = ground(entity, desired);
        return new Vec3(desired.x, Double.isFinite(ground) ? ground : entity.getY(), desired.z);
    }
    public static List<LivingEntity> swept(net.minecraft.world.level.Level level, PokemonEntity owner, Vec3 from, Vec3 to, double radius) {
        AABB query = new AABB(from, to).inflate(radius + 1);
        return level.getEntitiesOfClass(LivingEntity.class, query, t -> BasicAttack.validTarget(t, owner))
                .stream().filter(t -> t.getBoundingBox().inflate(radius).contains(from)
                        || t.getBoundingBox().inflate(radius).clip(from, to).isPresent())
                .sorted(Comparator.comparingDouble(t -> from.distanceToSqr(
                        t.getBoundingBox().inflate(radius).contains(from) ? from
                        : t.getBoundingBox().inflate(radius).clip(from,to).orElse(to))))
                .toList();
    }
    public static Vec3 clipProjectile(ServerLevel level, PokemonEntity entity, Vec3 from, Vec3 to) {
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        Vec3 endpoint=hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
        return ExpansionWalls.clip(entity,from,endpoint);
    }
    public static boolean visible(ServerLevel level,PokemonEntity entity,Vec3 from,Vec3 to) {
        return clipProjectile(level,entity,from,to).distanceToSqr(to)<.01;
    }
    public static boolean inCone(PokemonEntity source, LivingEntity target, Vec3 direction, double range, double degrees) {
        Vec3 d = target.getBoundingBox().getCenter().subtract(source.position()).multiply(1, 0, 1);
        double extra = target.getBbWidth() * .5;
        if (Math.abs(target.getY() - source.getY()) > Math.max(2.5, source.getBbHeight())) return false;
        if (d.length() > range + extra) return false;
        return d.lengthSqr() < .01 || d.normalize().dot(direction) >= Math.cos(Math.toRadians(degrees));
    }
    private static double ground(PokemonEntity entity,Vec3 pos) {
        var hit=entity.level().clip(new ClipContext(pos.add(0,.5,0),pos.add(0,-4,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity));
        return hit.getType()==HitResult.Type.BLOCK?hit.getLocation().y:Double.NaN;
    }
    /** Read-only, client-safe terrain preview. Server applies the same .2-block checks. */
    public static Vec3 previewMove(PokemonEntity entity,Vec3 delta) {
        if(!finite(delta)||delta.lengthSqr()>32*32)return entity.position();
        Vec3 origin=entity.position(),current=origin;
        int steps=Math.max(1,(int)Math.ceil(Math.min(32,delta.length())/.2));
        Vec3 step=delta.scale(1.0/steps);
        for(int i=0;i<steps;i++) {
            Vec3 requested=current.add(step);
            Vec3 bounded=entity.level().isClientSide?requested:CharizardCombat.clampToArena(entity,requested);
            if(bounded.subtract(requested).horizontalDistanceSqr()>.001)break;
            double floor=ground(entity,bounded);
            if(!Double.isFinite(floor)||Math.abs(floor-current.y)>.65)break;
            Vec3 next=new Vec3(bounded.x,floor,bounded.z);
            AABB box=entity.getBoundingBox().move(next.subtract(origin));
            if(!entity.level().noCollision(entity,box)||ExpansionWalls.blocked(entity,box))break;
            current=next;
        }
        return current;
    }
    /** Returns actual travel. Each .2-block step checks full body collision, ground and arena. */
    public static double move(PokemonEntity entity, Vec3 delta) {
        if (!finite(delta) || delta.lengthSqr()>32*32 || ExpansionControl.sleeping(entity)) return 0;
        Vec3 start = entity.position();
        int steps = Math.max(1, (int) Math.ceil(delta.length() / .2));
        Vec3 step = delta.scale(1.0 / steps);
        for (int i = 0; i < steps; i++) {
            Vec3 requested = entity.position().add(step);
            Vec3 bounded = CharizardCombat.clampToArena(entity, requested);
            if (bounded.subtract(requested).horizontalDistanceSqr() > .001) break;
            double floor = CharizardCombat.groundBelow((ServerLevel) entity.level(), entity, bounded);
            if (!Double.isFinite(floor) || Math.abs(floor - entity.getY()) > .65) break;
            Vec3 next = new Vec3(bounded.x, floor, bounded.z);
            Vec3 actual = next.subtract(entity.position());
            if (!entity.level().noCollision(entity, entity.getBoundingBox().move(actual))
                    || ExpansionWalls.blocked(entity, entity.getBoundingBox().move(actual))) break;
            entity.setPos(next.x, next.y, next.z);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0;
        return entity.position().distanceTo(start);
    }
}

package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Temporary arena-only collision slabs. No block placement, no invisible permanent map changes. */
public final class ExpansionWalls {
    private record Wall(UUID owner, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                        AABB box, long expires, boolean client) {}
    private static final List<Wall> WALLS=new java.util.concurrent.CopyOnWriteArrayList<>();
    private ExpansionWalls() {}
    public static boolean create(PokemonEntity owner, Vec3 center, Vec3 direction, double halfWidth, int ticks) {
        var level=owner.level();
        // Axis-aligned, visibly represented slab; refuse spawn intersecting any living body.
        boolean alongX=Math.abs(direction.z)>=Math.abs(direction.x);
        AABB box=new AABB(center.x-(alongX?halfWidth:.35),center.y,center.z-(alongX?.35:halfWidth),
            center.x+(alongX?halfWidth:.35),center.y+2.5,center.z+(alongX?.35:halfWidth));
        if(!level.isClientSide && !level.getEntitiesOfClass(LivingEntity.class,box,LivingEntity::isAlive).isEmpty()) return false;
        WALLS.add(new Wall(owner.getUUID(),level.dimension(),box,level.getGameTime()+Math.min(100,ticks),level.isClientSide));
        return true;
    }
    public static boolean blocked(LivingEntity entity,AABB destination) {
        long now=entity.level().getGameTime();
        WALLS.removeIf(w -> w.client==entity.level().isClientSide && w.dimension.equals(entity.level().dimension()) && w.expires<=now);
        return WALLS.stream().anyMatch(w -> w.client==entity.level().isClientSide && w.dimension.equals(entity.level().dimension()) && w.box.intersects(destination));
    }
    public static Vec3 clip(LivingEntity entity, Vec3 from, Vec3 to) {
        long now=entity.level().getGameTime();
        WALLS.removeIf(w -> w.client==entity.level().isClientSide && w.dimension.equals(entity.level().dimension()) && w.expires<=now);
        Vec3 nearest=to;
        for (Wall wall:WALLS) {
            if (wall.client!=entity.level().isClientSide || !wall.dimension.equals(entity.level().dimension())) continue;
            if (wall.box.contains(from)) return from;
            var intercept=wall.box.clip(from,to);
            if (intercept.isPresent() && from.distanceToSqr(intercept.get())<from.distanceToSqr(nearest)) nearest=intercept.get();
        }
        return nearest;
    }
    /** Sweep the whole body; point-only clipping allows large models to straddle slabs. */
    public static Vec3 constrain(LivingEntity entity,Vec3 movement) {
        if(movement.lengthSqr()<1.0E-10)return movement;
        long now=entity.level().getGameTime();
        WALLS.removeIf(w -> w.client==entity.level().isClientSide && w.dimension.equals(entity.level().dimension()) && w.expires<=now);
        double fraction=1;
        AABB body=entity.getBoundingBox();
        Vec3 center=body.getCenter();
        for(Wall wall:WALLS) {
            if(wall.client!=entity.level().isClientSide || !wall.dimension.equals(entity.level().dimension()))continue;
            AABB expanded=wall.box.inflate(body.getXsize()/2,body.getYsize()/2,body.getZsize()/2);
            if(expanded.contains(center)) {
                if(wall.box.intersects(body.move(movement)))fraction=0;
                continue;
            }
            var hit=expanded.clip(center,center.add(movement));
            if(hit.isPresent())fraction=Math.min(fraction,Math.max(0,center.distanceTo(hit.get())/movement.length()-.002));
        }
        return movement.scale(fraction);
    }
    public static void clearClient() {WALLS.removeIf(Wall::client);}
    public static void remove(UUID owner) {WALLS.removeIf(w -> w.owner.equals(owner));}
    public static void resetAll() {WALLS.clear();}
}

package com.pkmoba.client;

import com.pkmoba.combat.BasicAttack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Cursor entity picking and conservative, aim-specific hit previews (not a radius-only test). */
public final class Targeting {
    private Targeting() { }

    public static LivingEntity pick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.screen != null
                || !GodView.isActive() || SkillHud.isMouseOver()) return null;
        var block = AimRay.pick(mc);
        if (block == null) return null;
        Vec3 start = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 end = block.getLocation();
        var self = mc.level.getEntity(ClientState.getCharizardId());
        if (self == null) return null;
        LivingEntity selected = null;
        double nearest = start.distanceToSqr(end);
        for (LivingEntity candidate : mc.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(start, end).inflate(0.1), e -> BasicAttack.validTarget(e, self))) {
            AABB box = candidate.getBoundingBox();
            var intersection = box.clip(start, end);
            if (!box.contains(start) && intersection.isEmpty()) continue;
            double distance = box.contains(start) ? 0 : start.distanceToSqr(intersection.get());
            if (distance < nearest) {
                nearest = distance;
                selected = candidate;
            }
        }
        return selected;
    }

    private static double basicRangeSquared(LivingEntity self) {
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isBlastoise(pokemon)) return BasicAttack.WATER_RANGE * BasicAttack.WATER_RANGE;
        return BasicAttack.RANGE * BasicAttack.RANGE;
    }

    public static boolean covered(Minecraft mc, LivingEntity target) {
        if (mc.level == null) return false;
        var found = mc.level.getEntity(ClientState.getCharizardId());
        if (!(found instanceof LivingEntity self)) return false;
        int skill = SkillKeys.getAimedSkillIndex();
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isBlastoise(pokemon) && skill >= 0) return false;
        Vec3 aim = AimRay.aimPoint(mc);
        Vec3 origin = self.position();
        Vec3 eye = self.getEyePosition();
        if (skill < 0) {
            return ClientState.getChannel() == 0
                    && BasicAttack.boxDistanceSquared(self.getBoundingBox(), target.getBoundingBox())
                            <= basicRangeSquared(self)
                    && BasicAttack.clearMeleeLine(self, target);
        }
        // Never advertise a target behind solid terrain as hittable.
        if (!BasicAttack.clearMeleeLine(self, target)) return false;
        if (skill == 0) {
            Vec3 direction = new Vec3(aim.x - eye.x, 0, aim.z - eye.z);
            Vec3 to = new Vec3(target.getX() - eye.x, 0, target.getZ() - eye.z);
            return direction.lengthSqr() > 1.0E-8 && to.length() >= 0.1 && to.length() <= 10.0
                    && direction.normalize().dot(to.normalize()) >= Math.cos(Math.toRadians(20));
        }
        if (skill == 1) {
            Vec3 delta = aim.subtract(origin);
            Vec3 center = delta.lengthSqr() > 64 ? origin.add(delta.normalize().scale(8)) : aim;
            return target.position().distanceToSqr(center) <= 16
                    && BasicAttack.clearLine(mc.level, self, eye, center)
                    && BasicAttack.clearLine(mc.level, self, center.add(0, 0.1, 0),
                            target.getBoundingBox().getCenter());
        }
        if (skill == 2) {
            Vec3 delta = new Vec3(aim.x - origin.x, 0, aim.z - origin.z);
            Vec3 direction = delta.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : delta.normalize();
            // Straight initial-heading approximation only: later mouse steering is not predicted.
            // Follow safe terrain up to four blocks below, matching low glide rather than a ground dash.
            Vec3 previous = origin;
            for (int step = 1; step <= 60; step++) {
                Vec3 horizontal = origin.add(direction.scale(step * 0.25));
                var ground = mc.level.clip(new net.minecraft.world.level.ClipContext(
                        new Vec3(horizontal.x, previous.y + 0.2, horizontal.z),
                        new Vec3(horizontal.x, previous.y - 4.0, horizontal.z),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, self));
                if (ground.getType() != HitResult.Type.BLOCK) return false;
                Vec3 point = new Vec3(horizontal.x, ground.getLocation().y + 0.8, horizontal.z);
                AABB body = self.getBoundingBox().move(point.subtract(origin));
                if (mc.level.getBlockCollisions(self, body).iterator().hasNext()
                        || !BasicAttack.clearLine(mc.level, self, previous.add(0, 0.1, 0),
                                point.add(0, 0.1, 0))) return false;
                if (body.inflate(1.5).intersects(target.getBoundingBox())) return true;
                previous = point;
            }
            return false;
        }
        if (skill == 3) {
            Vec3 delta = aim.subtract(eye);
            Vec3 direction = new Vec3(delta.x, delta.y * 0.25, delta.z);
            direction = direction.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : direction.normalize();
            Vec3 launch = eye.add(direction);
            Vec3 previous = launch;
            for (int step = 1; step <= 34; step++) {
                double travel = step * 0.6;
                Vec3 point = launch.add(direction.scale(travel));
                if (!BasicAttack.clearLine(mc.level, self, previous, point)) return false;
                double radius = 3.0 - 2.0 * Math.min(1, travel / 20.0);
                // A nearer body intercepts the projectile; do not paint entities behind it red.
                for (LivingEntity candidate : mc.level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(point, point).inflate(radius + 1), e -> BasicAttack.validTarget(e, self))) {
                    if (candidate.getBoundingBox().inflate(radius).contains(point)) {
                        return target.getBoundingBox().intersects(new AABB(point, point).inflate(radius));
                    }
                }
                previous = point;
            }
        }
        return false;
    }
}

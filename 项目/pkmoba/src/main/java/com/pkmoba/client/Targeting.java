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

    /**
     * 普攻判定半径（格）：喷火龙 {@link BasicAttack#RANGE}、水箭龟 {@link BasicAttack#WATER_RANGE}。
     *
     * <p>0.38.5：半径的唯一来源移到 {@link BasicAttack#rangeFor} —— 服务端的命中判定与客户端的
     * 白色范围圈必须读同一个数，否则"圈画到哪、伤害打到哪"就会对不上。
     */
    public static double basicRangeRadius(LivingEntity self) {
        return BasicAttack.rangeFor(self);
    }

    private static double basicRangeSquared(LivingEntity self) {
        double radius = basicRangeRadius(self);
        return radius * radius;
    }

    /** 光标当前锁定的目标（与左键普攻选中走同一套拾取）。 */
    public static LivingEntity hoveredTarget(Minecraft mc) {
        return pick(mc);
    }

    /**
     * 是否已经有可用目标进入普攻判定范围（"交战中"）。
     *
     * <p>白色范围圈据此在"玩家锁定目标"之外补一个"已经在打"的显示条件：
     * 光标移开但敌人仍贴身时圈不消失，走出范围 / 目标失效则立刻消失。
     */
    public static boolean hasEngagedTarget(Minecraft mc, LivingEntity self, double radius) {
        if (mc.level == null || self == null) return false;
        double rangeSquared = radius * radius;
        for (LivingEntity candidate : mc.level.getEntitiesOfClass(LivingEntity.class,
                self.getBoundingBox().inflate(radius + 1.0), e -> BasicAttack.validTarget(e, self))) {
            if (BasicAttack.boxDistanceSquared(self.getBoundingBox(), candidate.getBoundingBox()) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    public static boolean covered(Minecraft mc, LivingEntity target) {
        if (mc.level == null) return false;
        var found = mc.level.getEntity(ClientState.getCharizardId());
        if (!(found instanceof LivingEntity self)) return false;
        int skill = SkillKeys.getAimedSkillIndex();
        boolean blastoise = self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isBlastoise(pokemon);
        Vec3 aim = AimRay.aimPoint(mc);
        Vec3 origin = self.position();
        Vec3 eye = self.getEyePosition();
        if (blastoise && skill >= 0) {
            return blastoiseCovered(mc, self, target, skill, aim, origin, eye);
        }
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
            // 0.38.2：火焰旋涡半径 4 → 7（直径 14 格），释放距离 8 → 12，与服务端 W_RADIUS/W_CAST_RANGE 对齐。
            Vec3 delta = aim.subtract(origin);
            Vec3 center = delta.lengthSqr() > 144 ? origin.add(delta.normalize().scale(12)) : aim;
            return target.position().distanceToSqr(center) <= 49
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

    /**
     * 0.38 水箭龟四技能预览：Q 直线水炮胶囊、W 旋转近身环、E 冲浪直线推进、R 喷水地面扇形。
     *
     * <p>只回答"这个目标是否落在技能覆盖范围内"，用于选中框红/灰提示；真实命中判定仍以服务端为准。
     */
    private static boolean blastoiseCovered(Minecraft mc, LivingEntity self, LivingEntity target, int skill,
                                            Vec3 aim, Vec3 origin, Vec3 eye) {
        // 隔墙的目标一律不发红，和服务端普攻/技能的视线检查保持一致。
        if (!BasicAttack.clearMeleeLine(self, target)) return false;
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        if (skill == 0) {
            // 加农水炮：16 格直线、半径 1.6 的水平胶囊（引导期间炮口跟随鼠标，这里只近似初始朝向）。
            Vec3 direction = new Vec3(aim.x - eye.x, 0, aim.z - eye.z);
            if (direction.lengthSqr() < 1.0E-8) return false;
            direction = direction.normalize();
            Vec3 to = new Vec3(targetCenter.x - eye.x, 0, targetCenter.z - eye.z);
            double along = to.dot(direction);
            if (along < -1.0 || along > 16.0) return false;
            double lateral = Math.sqrt(Math.max(0, to.lengthSqr() - along * along));
            if (lateral > 1.6 + target.getBbWidth() * 0.5) return false;
            if (Math.abs(targetCenter.y - eye.y) > 2.2 + target.getBbHeight() * 0.5) return false;
            Vec3 hit = new Vec3(eye.x + direction.x * along, targetCenter.y, eye.z + direction.z * along);
            return BasicAttack.clearLine(mc.level, self, eye, hit);
        }
        if (skill == 1) {
            // 高速旋转：以自身为中心、水平 inflate(1.3, 0.6, 1.3) 的近身范围。
            AABB area = self.getBoundingBox().inflate(1.3, 0.6, 1.3);
            return BasicAttack.boxDistanceSquared(area, target.getBoundingBox()) <= 0
                    && Math.abs(origin.y - target.getY()) <= 2.0;
        }
        if (skill == 2) {
            // 冲浪术：约 11 格直线推进，判定半径 1.2。
            Vec3 direction = new Vec3(aim.x - origin.x, 0, aim.z - origin.z);
            if (direction.lengthSqr() < 1.0E-4) return false;
            direction = direction.normalize();
            Vec3 delta = new Vec3(targetCenter.x - origin.x, 0, targetCenter.z - origin.z);
            double along = delta.dot(direction);
            if (along < -0.5 || along > 11.0) return false;
            double lateral = Math.sqrt(Math.max(0, delta.lengthSqr() - along * along));
            if (lateral > 1.2 + target.getBbWidth() * 0.5) return false;
            return BasicAttack.clearLine(mc.level, self, origin.add(0, 0.8, 0),
                    new Vec3(origin.x + direction.x * along, targetCenter.y, origin.z + direction.z * along));
        }
        if (skill == 3) {
            // 喷水：半径 14 格、±30° 的地面扇形。
            Vec3 direction = new Vec3(aim.x - origin.x, 0, aim.z - origin.z);
            if (direction.lengthSqr() < 1.0E-4) return false;
            direction = direction.normalize();
            Vec3 delta = new Vec3(targetCenter.x - origin.x, 0, targetCenter.z - origin.z);
            double distance = delta.length();
            if (distance < 1.0 || distance > 14.0) return false;
            return direction.dot(delta.normalize()) >= Math.cos(Math.toRadians(30));
        }
        return false;
    }
}

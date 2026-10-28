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

    /** 光标当前锁定的目标（与左键普攻选中走同一套拾取）。 */
    public static LivingEntity hoveredTarget(Minecraft mc) {
        return pick(mc);
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
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.ExpansionCombat.supports(pokemon)) {
            if (skill < 0 || skill >= 4 || !BasicAttack.clearMeleeLine(self,target)) return false;
            var move=com.pkmoba.combat.ExpansionCombat.species(pokemon).move(skill);
            Vec3 direction=com.pkmoba.combat.ExpansionGeometry.direction(pokemon,aim);
            Vec3 relative=target.getBoundingBox().getCenter().subtract(origin).multiply(1,0,1);
            double width=target.getBbWidth()*.5;
            if (move.shape()==com.pkmoba.combat.ExpansionRoster.Shape.DASH) {
                Vec3 endpoint=com.pkmoba.combat.ExpansionGeometry.previewMove(pokemon,direction.scale(move.range()));
                return com.pkmoba.combat.ExpansionGeometry.swept(mc.level,pokemon,origin.add(0,.5,0),endpoint.add(0,.5,0),.75).contains(target);
            }
            return switch(move.shape()) {
                case CONE -> com.pkmoba.combat.ExpansionGeometry.inCone(pokemon,target,direction,move.range(),move.radius());
                case LINE -> {
                    double forward=relative.dot(direction);
                    yield forward>=0 && forward<=move.range()+width
                        && relative.subtract(direction.scale(forward)).lengthSqr()<=Math.pow(.55+width,2);
                }
                case POINT -> {
                    if (move.name().equals("喷水"))
                        yield relative.lengthSqr()<=Math.pow(move.radius()+width,2);
                    Vec3 center=com.pkmoba.combat.ExpansionGeometry.point(pokemon,aim,move.range());
                    yield target.position().subtract(center).horizontalDistanceSqr()<=Math.pow(move.radius()+width,2);
                }
                case SELF -> relative.lengthSqr()<=Math.pow(move.radius()+width,2);
                case BLINK -> {
                    Vec3 delta=aim.subtract(origin).multiply(1,0,1);
                    Vec3 end=com.pkmoba.combat.ExpansionGeometry.previewMove(pokemon,delta.length()>move.range()
                            ?delta.normalize().scale(move.range()):delta);
                    yield target.getBoundingBox().inflate(width+.75).contains(end.add(0,.5,0));
                }
                case WALL, BUFF, DASH -> false;
            };
        }
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isVenusaur(pokemon) && skill >= 0) {
            return venusaurCovered(mc, pokemon, target, skill, aim, origin);
        }
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && com.pkmoba.combat.Skill.isPikachu(pokemon) && skill >= 0) {
            if (!BasicAttack.clearMeleeLine(self, target)) return false;
            Vec3 direction = new Vec3(aim.x - origin.x, 0, aim.z - origin.z).normalize();
            Vec3 relative = target.getBoundingBox().getCenter().subtract(origin);
            if (skill == 0 || skill == 2) {
                double forward = relative.dot(direction);
                double width = skill == 0 ? 0.5 : 0.7;
                return forward >= 0 && forward <= (skill == 0 ? com.pkmoba.combat.PikachuCombat.Q_RANGE : com.pkmoba.combat.PikachuCombat.E_RANGE)
                        && relative.subtract(direction.scale(forward)).horizontalDistanceSqr()
                        <= Math.pow(width + target.getBbWidth() * 0.5, 2);
            }
            if (skill == 1) {
                double travel = com.pkmoba.combat.PikachuCombat.wTravelDistance(
                        new Vec3(aim.x - origin.x, 0, aim.z - origin.z).length());
                Vec3 intended = origin.add(direction.scale(travel));
                Vec3 endpoint = com.pkmoba.combat.PikachuCombat.previewGround(self, intended);
                double radius = com.pkmoba.combat.PikachuCombat.W_RADIUS + target.getBbWidth() * 0.5;
                return target.position().subtract(endpoint).horizontalDistanceSqr() <= radius * radius;
            }
            double travel = Math.min(com.pkmoba.combat.PikachuCombat.R_RANGE,
                    new Vec3(aim.x - origin.x, 0, aim.z - origin.z).length());
            Vec3 requested = origin.add(direction.scale(travel));
            Vec3 center = com.pkmoba.combat.PikachuCombat.previewGround(self, requested);
            return target.position().subtract(center).horizontalDistanceSqr()
                    <= Math.pow(com.pkmoba.combat.PikachuCombat.R_RADIUS + target.getBbWidth() * 0.5, 2);
        }
        if (blastoise && skill >= 0) {
            return blastoiseCovered(mc, self, target, skill, aim, origin, eye);
        }
        if (skill < 0) return false;
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
            Vec3 flat = aim.subtract(origin).multiply(1, 0, 1);
            flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
            Vec3 launch = self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                    ? com.pkmoba.combat.CharizardCombat.mouthPosition(pokemon, flat, 0.55, 0.22) : eye;
            Vec3 delta = aim.add(0, 0.45, 0).subtract(launch);
            Vec3 direction = delta.lengthSqr() < 1.0E-4 ? flat : delta.normalize();
            Vec3 previous = launch;
            for (int step = 1; step <= 34; step++) {
                double travel = step * 0.6;
                Vec3 point = launch.add(direction.scale(travel));
                if (!BasicAttack.clearLine(mc.level, self, previous, point)) return false;
                double radius = 3.0 - 2.0 * Math.min(1, (travel - 0.6) / 20.0);
                Vec3 contact = null;
                double nearest = Double.POSITIVE_INFINITY;
                for (LivingEntity candidate : mc.level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(previous, point).inflate(2.4), e -> BasicAttack.validTarget(e, self))) {
                    AABB body = candidate.getBoundingBox().inflate(0.9);
                    Vec3 hit = body.contains(previous) ? previous : body.clip(previous, point).orElse(null);
                    if (hit != null && hit.distanceToSqr(previous) < nearest) {
                        nearest = hit.distanceToSqr(previous);
                        contact = hit;
                    }
                }
                if (contact != null) {
                    return BasicAttack.closestPoint(target.getBoundingBox(), contact).distanceTo(contact) <= radius
                            && BasicAttack.clearLine(mc.level, self, contact, target.getBoundingBox().getCenter());
                }
                previous = point;
            }
        }
        return false;
    }

    /** Mirrors VenusaurCombat: vine cone, grounded fields and terrain-clipped solar beam. */
    private static boolean venusaurCovered(Minecraft mc, com.cobblemon.mod.common.entity.pokemon.PokemonEntity self, LivingEntity target, int skill,
                                           Vec3 aim, Vec3 origin) {
        if (mc.level == null) return false;
        Vec3 flower = com.pkmoba.combat.VenusaurAnchor.position(self);
        Vec3 direction = com.pkmoba.combat.CharizardCombat.aimDirection(self, aim);
        if (skill == 0) {
            Vec3 end = flower.add(direction.scale(com.pkmoba.combat.VenusaurCombat.Q_RANGE));
            AABB body = target.getBoundingBox().inflate(com.pkmoba.combat.VenusaurCombat.Q_RADIUS);
            return (body.contains(flower) || body.clip(flower, end).isPresent())
                    && BasicAttack.clearLine(mc.level, self, flower, target.getBoundingBox().getCenter());
        }
        if (skill == 2) {
            Vec3 intended = flower.add(direction.scale(com.pkmoba.combat.VenusaurCombat.E_RANGE));
            HitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(flower, intended,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, self));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? intended : hit.getLocation();
            AABB body = target.getBoundingBox().inflate(com.pkmoba.combat.VenusaurCombat.E_RADIUS);
            return body.contains(flower) || body.clip(flower, end).isPresent();
        }
        if (skill == 1 || skill == 3) {
            double range = skill == 1 ? 16 : 15;
            double radius = skill == 1 ? 2.4 : com.pkmoba.combat.VenusaurCombat.R_RADIUS;
            Vec3 delta = aim.subtract(origin).multiply(1, 0, 1);
            if (delta.lengthSqr() > range * range) delta = delta.normalize().scale(range);
            Vec3 center = origin.add(delta);
            // Match the synchronized legacy circle when enforced. Saved polygon vertices
            // are not in this payload, so polygon-edge previews remain approximate.
            double arenaRadius = Math.max(0, ClientState.getArenaRadius() - 1.0);
            if (arenaRadius > 0 && ClientState.getArenaDist() < 999.0F) {
                Vec3 arenaCenter = new Vec3(ClientState.getArenaX(), center.y, ClientState.getArenaZ());
                Vec3 fromCenter = center.subtract(arenaCenter).multiply(1, 0, 1);
                if (fromCenter.lengthSqr() > arenaRadius * arenaRadius) center = arenaCenter.add(fromCenter.normalize().scale(arenaRadius));
            }
            HitResult ground = mc.level.clip(new net.minecraft.world.level.ClipContext(center.add(0, 3, 0), center.add(0, -6, 0),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, self));
            if (ground.getType() != HitResult.Type.MISS) center = ground.getLocation().add(0, 0.03, 0);
            double dx = target.getX() - center.x, dz = target.getZ() - center.z;
            return dx * dx + dz * dz <= radius * radius && Math.abs(target.getY() - center.y) <= 3;
        }
        return false;
    }

    /** Blastoise Q cannon, W spin, E surf and R spout coverage preview. */
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

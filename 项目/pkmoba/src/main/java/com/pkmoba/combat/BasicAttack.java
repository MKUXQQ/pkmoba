package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.duel.BotDuelService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/** Shared target and geometry helpers used by skill hit validation. */
public final class BasicAttack {
    private BasicAttack() { }

    public static boolean validTarget(LivingEntity target, Entity self) {
        if (target == null || target == self || !target.isAlive() || target.isRemoved()) return false;
        if (Illusions.isIllusion(target)) return false;
        if ((self instanceof PokemonEntity pokemonSelf && BotDuelService.isBot(pokemonSelf))
                || (target instanceof PokemonEntity pokemonTarget && BotDuelService.isBot(pokemonTarget))) {
            return BotDuelService.areOpponents(self, target);
        }
        return true;
    }

    public static double boxDistanceSquared(AABB a, AABB b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    public static Vec3 closestPoint(AABB box, Vec3 point) {
        return new Vec3(Math.max(box.minX, Math.min(box.maxX, point.x)),
                Math.max(box.minY, Math.min(box.maxY, point.y)),
                Math.max(box.minZ, Math.min(box.maxZ, point.z)));
    }

    public static boolean clearLine(Level level, Entity source, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
    }

    public static boolean clearMeleeLine(LivingEntity self, LivingEntity target) {
        Vec3 end = closestPoint(target.getBoundingBox(), self.getEyePosition());
        return clearLine(self.level(), self, self.getEyePosition(), end);
    }
}

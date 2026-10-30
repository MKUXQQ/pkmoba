package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Scoped authorization for deliberate PKMOBA skill damage through LivingEntity.hurt. */
public final class SkillDamagePermit {
    private record Permit(UUID source, UUID target) { }
    private static final ThreadLocal<Permit> ACTIVE = new ThreadLocal<>();

    private SkillDamagePermit() { }

    public static boolean allows(Entity attacker, LivingEntity target) {
        Permit permit = ACTIVE.get();
        return attacker instanceof PokemonEntity pokemon && permit != null
                && permit.source().equals(pokemon.getUUID())
                && target != null && permit.target().equals(target.getUUID());
    }

    public static boolean hurt(PokemonEntity source, LivingEntity target,
                               java.util.function.BooleanSupplier action) {
        Permit previous = ACTIVE.get();
        ACTIVE.set(new Permit(source.getUUID(), target.getUUID()));
        try {
            return action.getAsBoolean();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

}

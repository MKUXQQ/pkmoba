package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.PkmobaMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** World-entity damage, not Cobblemon turn-based battle HP. Never writes health directly. */
public final class CombatDamage {
    private CombatDamage() { }

    public static boolean apply(ServerLevel level, PokemonEntity source, LivingEntity target, float amount) {
        if (source == null || !BasicAttack.validTarget(target, source) || target.level() != level
                || !Float.isFinite(amount) || amount <= 0) return false;
        float before = target.getHealth();
        // Skill cadence is authoritative. Remove only the previous hit's vanilla cooldown
        // during this call, not entity invulnerability, creative protection or event cancellation.
        int previousCooldown = target.invulnerableTime;
        target.invulnerableTime = 0;
        boolean accepted;
        try {
            accepted = target.hurt(level.damageSources().mobAttack(source), amount);
        } catch (RuntimeException | Error failure) {
            target.invulnerableTime = previousCooldown;
            throw failure;
        }
        if (!accepted) target.invulnerableTime = previousCooldown;
        PkmobaMod.LOGGER.debug("[pkmoba] damage source={} target={} requested={} accepted={} hp={}->{} invulnerable={}",
                source.getUUID(), target.getUUID(), amount, accepted, before, target.getHealth(), target.isInvulnerable());
        return accepted;
    }
}

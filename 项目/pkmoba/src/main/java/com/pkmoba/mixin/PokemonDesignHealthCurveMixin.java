package com.pkmoba.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonServerDelegate;
import com.pkmoba.combat.PokemonHp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** PKMOBA uses the same HP units on the Pokemon and its world entity. */
@Mixin(PokemonServerDelegate.class)
public abstract class PokemonDesignHealthCurveMixin {
    @Shadow(remap = false) public abstract PokemonEntity getEntity();

    @Inject(method = "maxHpToMaxHealthCurve", at = @At("HEAD"), cancellable = true, remap = false)
    private void pkmoba$preserveDesignHealth(int maxHp, CallbackInfoReturnable<Integer> cir) {
        PokemonEntity entity = getEntity();
        if (entity != null && PokemonHp.maxHealthOf(entity.getPokemon()) > 0) {
            cir.setReturnValue(maxHp);
        }
    }
}

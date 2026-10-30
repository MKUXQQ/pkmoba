package com.pkmoba.mixin;

import com.pkmoba.charizard.CharizardManager;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevent controlled Cobblemon from executing autonomous melee attacks or entering attack pose. */
@Mixin(Mob.class)
public abstract class ControlledPokemonAttackBlockMixin {
    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void pkmoba$blockAutonomousAttack(net.minecraft.world.entity.Entity target,
                                              CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && CharizardManager.isMatchEntity(pokemon)) cir.setReturnValue(false);
    }

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void pkmoba$clearAutonomousTarget(net.minecraft.world.entity.LivingEntity target, CallbackInfo ci) {
        Object self = this;
        if (target != null && self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && CharizardManager.isMatchEntity(pokemon)) ci.cancel();
    }

    @Inject(method = "setAggressive", at = @At("HEAD"), cancellable = true)
    private void pkmoba$blockAggressivePose(boolean aggressive, CallbackInfo ci) {
        Object self = this;
        if (aggressive && self instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && CharizardManager.isMatchEntity(pokemon)) ci.cancel();
    }
}

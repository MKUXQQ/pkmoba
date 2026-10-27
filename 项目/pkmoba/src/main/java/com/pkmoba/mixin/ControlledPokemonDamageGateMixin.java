package com.pkmoba.mixin;

import com.pkmoba.combat.SkillDamagePermit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only deliberate, scoped PKMOBA skill hits may damage targets from controlled Pokemon. */
@Mixin(LivingEntity.class)
public abstract class ControlledPokemonDamageGateMixin {
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
    private void pkmoba$requireSkillPermit(DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> cir) {
        Entity attacker = source.getEntity();
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(attacker instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) return;
        // Controlled and wild Cobblemon both use doHurtTarget; only scoped PKMOBA skill hits are allowed.
        if (SkillDamagePermit.allows(pokemon, self)) return;
        cir.setReturnValue(false);
    }
}

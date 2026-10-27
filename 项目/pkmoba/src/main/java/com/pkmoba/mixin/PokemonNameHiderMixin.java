package com.pkmoba.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide the entire native Cobblemon overhead label, including name, level and icons.
 *
 * <p>In Cobblemon 1.8.2, shouldShowName already returns false. The custom render
 * path instead gates renderNameTag through shouldRenderLabel. Suppress that
 * gate as well. Entity names and server gameplay data remain intact.
 */
@Mixin(targets = "com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer")
public abstract class PokemonNameHiderMixin {
    @Inject(
            method = "shouldRenderLabel(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1
    )
    private void pkmoba$hideName(PokemonEntity entity, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}

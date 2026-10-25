package com.pkmoba.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关掉 Cobblemon 给宝可梦画的头顶名牌。
 *
 * <p>客户端的 {@code PokemonRenderer} 自己重写了 {@code shouldShowName}，
 * 所以原版"是否显示名字"的开关管不到它，必须在它的实现上掐掉。
 * 目标类是模组类（不是原版类），因此 {@code remap = false}，方法用完整描述符定位。
 * 不影响战斗、血量与服务端任何逻辑。
 */
@Mixin(targets = "com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer")
public abstract class PokemonNameHiderMixin {

    @Inject(
            method = "shouldShowName(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void pkmoba$hideName(PokemonEntity entity, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}

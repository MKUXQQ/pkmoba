package com.pkmoba.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.pkmoba.combat.PokemonHp;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Cobblemon 的 {@code Pokemon.getMaxHealth()} 认同我们的设计血量。
 *
 * <p>原实现是 {@code return getStat(Stats.HP)}（种族/等级/个体值算出来的，通常 90 上下）。
 * 受控宝可梦的持久数据里已经被 {@link PokemonHp#apply} 写入 {@code PkmobaMaxHp}，
 * 这里直接返回它，达到"实体属性 / Pokemon 对象 / 宝可梦界面 三处同一个上限"的效果。
 *
 * <p>只对写过 KEY 的宝可梦生效，普通野生/玩家自己的宝可梦完全不受影响。
 */
@Mixin(Pokemon.class)
public abstract class PokemonMaxHealthMixin {

    // Cobblemon 的类不参与 Fabric 的混淆映射，所以必须 remap = false（否则 mixin AP 报
    // "Unable to locate obfuscation mapping for @Inject target getMaxHealth"）。
    @Inject(method = "getMaxHealth", at = @At("RETURN"), cancellable = true, remap = false)
    private void pkmoba$designMaxHealth(CallbackInfoReturnable<Integer> cir) {
        Pokemon self = (Pokemon) (Object) this;
        CompoundTag tag = self.getPersistentData();
        if (tag == null || !tag.contains(PokemonHp.KEY)) {
            return;
        }
        int design = tag.getInt(PokemonHp.KEY);
        if (design > 0) {
            cir.setReturnValue(design);
        }
    }
}

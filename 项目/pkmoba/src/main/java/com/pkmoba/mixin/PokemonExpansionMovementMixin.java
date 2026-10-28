package com.pkmoba.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.ExpansionControl;
import com.pkmoba.combat.ExpansionWalls;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Physical movement gate includes navigation, knockback and old-species dashes. */
@Mixin(Entity.class)
public abstract class PokemonExpansionMovementMixin {
    @ModifyVariable(method="move", at=@At("HEAD"), argsOnly=true, ordinal=0)
    private Vec3 pkmoba$expansionCollision(Vec3 movement) {
        if (!((Object)this instanceof PokemonEntity pokemon) || pokemon.level().isClientSide) return movement;
        if (ExpansionControl.sleeping(pokemon)) return new Vec3(0,movement.y,0);
        return ExpansionWalls.constrain(pokemon,movement);
    }
}

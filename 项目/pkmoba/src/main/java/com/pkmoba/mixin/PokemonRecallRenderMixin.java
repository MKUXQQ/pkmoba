package com.pkmoba.mixin;

import com.pkmoba.client.RecallFx;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide a recalled Pokémon after it has converted into the client-rendered ball. */
@Mixin(EntityRenderDispatcher.class)
public abstract class PokemonRecallRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1)
    private void pkmoba$hideRecalled(Entity entity, double x, double y, double z,
            float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int light, CallbackInfo ci) {
        if (RecallFx.hidesEntity(entity.getId())) ci.cancel();
    }
}

package com.pkmoba.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pkmoba.client.GodView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅在本地玩家处于上帝视角时隐藏其模型。 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void pkmoba$hideLocalPlayer(AbstractClientPlayer player, float entityYaw, float partialTick,
                                         PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                         CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (GodView.isActive() && mc.player == player) {
            ci.cancel();
        }
    }
}

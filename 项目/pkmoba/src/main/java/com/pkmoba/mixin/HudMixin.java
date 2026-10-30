package com.pkmoba.mixin;

import com.pkmoba.client.GodView;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 上帝视角下隐藏原版底部 HUD 和准星，退出后恢复原版绘制。 */
@Mixin(Gui.class)
public abstract class HudMixin {
    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void pkmoba$hideCrosshair(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (GodView.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void pkmoba$hideVanillaHud(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (GodView.isActive()) {
            ci.cancel();
        }
    }
}

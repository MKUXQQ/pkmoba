package com.pkmoba.mixin;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.toast.CobblemonToast;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppress only the starter reminder's drawing, without changing starter or toast state. */
@Mixin(value = CobblemonToast.class, remap = false)
public abstract class StarterOverlayMixin {
    @Shadow(remap = false)
    private Toast.Visibility nextVisibility;

    // Cobblemon overrides a Minecraft interface method: named in development,
    // intermediary in the shipped Fabric jar. Neither selector is AP-remapped.
    @Inject(method = {"render", "method_1986"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 1)
    private void pkmoba$hideStarterReminder(GuiGraphics graphics, ToastComponent manager,
                                           long elapsed, CallbackInfoReturnable<Toast.Visibility> cir) {
        if ((Object) this == CobblemonClient.INSTANCE.getOverlay().getStarterToast()) {
            // Preserve Cobblemon's own SHOW/HIDE decision; skip pixels only.
            cir.setReturnValue(nextVisibility);
        }
    }
}

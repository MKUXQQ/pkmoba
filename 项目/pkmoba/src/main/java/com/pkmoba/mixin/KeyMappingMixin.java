package com.pkmoba.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Dispatch physical input to every binding, not only the last entry in vanilla's MAP. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Shadow @Final
    private static Map<String, KeyMapping> ALL;

    @Shadow
    private InputConstants.Key key;

    @Shadow
    private int clickCount;

    @Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void pkmoba$broadcastClick(InputConstants.Key input, CallbackInfo ci) {
        for (KeyMapping mapping : ALL.values()) {
            KeyMappingMixin binding = (KeyMappingMixin) (Object) mapping;
            if (binding.key.equals(input)) {
                binding.clickCount++;
            }
        }
        // Do not let vanilla increment the MAP winner a second time.
        ci.cancel();
    }

    @Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void pkmoba$broadcastSet(InputConstants.Key input, boolean down, CallbackInfo ci) {
        for (KeyMapping mapping : ALL.values()) {
            KeyMappingMixin binding = (KeyMappingMixin) (Object) mapping;
            if (binding.key.equals(input)) {
                // Preserve virtual dispatch, including ToggleKeyMapping semantics.
                mapping.setDown(down);
            }
        }
        ci.cancel();
    }
}

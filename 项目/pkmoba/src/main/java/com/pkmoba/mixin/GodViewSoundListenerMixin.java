package com.pkmoba.mixin;

import com.pkmoba.client.GodView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep positional combat audio near the player when the camera is high above the arena. */
@Mixin(SoundEngine.class)
public abstract class GodViewSoundListenerMixin {
    @Redirect(method = "updateSource", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 pkmoba$groundListener(Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        return GodView.isActive() && mc.player != null ? mc.player.getEyePosition() : camera.getPosition();
    }
}

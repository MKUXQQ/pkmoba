package com.pkmoba.mixin;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.client.ClientState;
import com.pkmoba.client.ShellAnimator;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply after all additive pose animations; never mutate the shared model outside rendering. */
@Mixin(value = PosableModel.class, remap = false)
public abstract class BlastoiseSpinPoseMixin {
    @Inject(method = "applyAnimations", at = @At("TAIL"), require = 1, remap = false)
    private void pkmoba$proneSpin(Entity entity, PosableState state, float limbSwing,
            float limbSwingAmount, float ageInTicks, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof PokemonEntity pokemon) || !ShellAnimator.appliesTo(pokemon)
                || entity.getId() != ClientState.getCharizardId()) return;
        PosableModel model = (PosableModel) (Object) this;
        var parts = model.getRelevantPartsByName();
        if (ClientState.getChannel() != 3) {
            // Forward in model space; descendants include the actual muzzle locators.
            for (String name : new String[]{"cannon_left", "cannon_right"}) {
                ModelPart part = parts.get(name);
                if (part != null) part.z -= 2.0F;
            }
            model.updateLocators(entity, state);
            return;
        }
        ModelPart body = parts.get("body"), prone = parts.get("body2");
        if (body == null || prone == null) return;
        // Separate parent yaw from child pitch: Ry(spin) * Rx(90), NOT rotating the tilted shell locally.
        body.loadPose(body.getInitialPose());
        body.y += 12.5F;
        body.yRot = (float) (state.getAnimationSeconds() * Math.PI * 4);
        prone.loadPose(prone.getInitialPose());
        prone.xRot = (float) (Math.PI / 2);
        for (String name : new String[]{"torso", "shell"}) {
            ModelPart part = parts.get(name);
            if (part != null) {
                part.loadPose(part.getInitialPose());
                part.xRot = part.yRot = part.zRot = 0;
            }
        }
        // Hide the entire subtrees, including head accessories and cannon barrels.
        for (String name : new String[]{"head", "arm_rotate_left", "arm_rotate_right",
                "leg_left", "leg_right", "tail", "shell_cannon_left", "shell_cannon_right"}) {
            ModelPart part = parts.get(name);
            if (part != null) part.xScale = part.yScale = part.zScale = 0;
        }
        // Locator matrices must correspond to the final pose, not the preceding idle animation.
        model.updateLocators(entity, state);
    }
}

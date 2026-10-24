package com.pkmoba.client;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockActiveAnimation;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;

/** Client-only Blastoise shell-curl animation used during skill channel 3. */
public final class ShellAnimator {
    private static final String ANIMATION_GROUP = "pkmoba_blastoise";
    private static final String ANIMATION_NAME = "animation.pkmoba.blastoise.shell_spin";
    private static final String ANIMATION_REFERENCE = ANIMATION_GROUP + ":" + ANIMATION_NAME;

    private ShellAnimator() {
    }

    public static void tick(PokemonEntity entity, boolean shouldCurl) {
        if (!(entity.getDelegate() instanceof PosableState state)) {
            return;
        }

        // The final render-tail pose owns yaw, prone pitch and retraction. Remove the old
        // additive shell-only animation; it rotated a vertical shell and fought locomotion.
        state.getActiveAnimations().removeIf(ShellAnimator::isShellCurl);
        if (shouldCurl) state.setPrimaryAnimation(null);
    }

    public static boolean appliesTo(PokemonEntity entity) {
        return CharizardManager.isBlastoise(entity);
    }

    private static boolean isShellCurl(Object animation) {
        return animation instanceof BedrockActiveAnimation bedrock
                && ANIMATION_NAME.equals(bedrock.getAnimation().getName());
    }

    public static void clear(PokemonEntity entity) {
        tick(entity, false);
    }
}

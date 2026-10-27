package com.pkmoba.mixin;

import com.cobblemon.mod.common.client.particle.ParticleStorm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppress only Cobblemon 1.8.2's send-out particle storms. */
@Mixin(targets = "com.cobblemon.mod.common.client.entity.PokemonClientDelegate")
public abstract class PokemonSendOutParticleMixin {
    /**
     * The first callback opens the ball; the nested callbacks add the beams,
     * sparks and delayed finish. Leave their state changes, sounds and entity
     * scaling alone. Do not intercept ambient, battle or move particles.
     *
     * <p>These are exact private Kotlin callback names verified in the bundled
     * Cobblemon 1.8.2 jar. The four spawn sites belong exclusively to send-out.
     */
    @Redirect(
            method = {
                    "onSyncedDataUpdated$lambda$5",
                    "onSyncedDataUpdated$lambda$5$0$0$0$0",
                    "onSyncedDataUpdated$lambda$5$0$0$0$0$2"
            },
            at = @At(value = "INVOKE",
                    target = "Lcom/cobblemon/mod/common/client/particle/ParticleStorm;spawn()V",
                    remap = false),
            remap = false,
            require = 4
    )
    private static void pkmoba$skipSendOutParticleStorm(ParticleStorm storm) {
        // Appearance effects are disabled; all summon gameplay still runs.
    }
}

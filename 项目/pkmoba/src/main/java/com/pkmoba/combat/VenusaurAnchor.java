package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.phys.Vec3;

/** Generic server-side fallback; the client uses the live flower locator when available. */
public final class VenusaurAnchor {
    private VenusaurAnchor() { }

    public static Vec3 position(PokemonEntity entity) {
        // Measured flower_top center [0, 34.51, 3] in model units at baseScale 1.2.
        double scale = 1.2;
        Vec3 forward = MouthAnchor.forward(entity.getYRot());
        return entity.position().add(forward.scale(-3.0 / 16.0 * scale))
                .add(0, 34.51 / 16.0 * scale, 0);
    }

}

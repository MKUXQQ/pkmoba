package com.pkmoba.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.LocalPlayer;

/** Prevent client gravity prediction from fighting the server's suspended body anchor. */
public final class BodyAnchorStabilizer {
    private static LocalPlayer locked;
    private BodyAnchorStabilizer() {}
    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (locked != null && (locked != mc.player || !GodView.isActive())) {
                // The server may already have sent NoGravity=true before activation.
                // That is the temporary anchor flag, never a value to restore after exit.
                locked.setNoGravity(false);
                locked.setDeltaMovement(Vec3.ZERO);
                locked.fallDistance = 0;
                locked = null;
            }
            if (mc.player == null || !GodView.isActive()) return;
            if (locked == null) {
                locked = mc.player;
            }
            locked.setNoGravity(true);
            locked.setDeltaMovement(Vec3.ZERO);
            locked.fallDistance = 0;
        });
    }
}

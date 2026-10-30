package com.pkmoba.client;

import com.pkmoba.client.fx.Fx;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Swords Dance aura: five translucent blades orbit the Lucario for the whole 8 s buff, and keep
 * orbiting while the player fires other skills.
 *
 * <p>The old version lived inside the Q cast scene, so the blades disappeared the moment the skill
 * channel ended. This renderer is keyed by entity id with its own expiry, so it tracks the body
 * until the server-side buff actually runs out.
 */
public final class SwordAura {
    private SwordAura() {
    }

    private static final ResourceLocation BLADE =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/lu_sword.png");
    private static final Map<Integer, Long> UNTIL = new HashMap<>();

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(SwordAura::render);
    }

    public static void activate(int entityId, int ticks) {
        UNTIL.put(entityId, System.currentTimeMillis() + ticks * 50L);
    }

    public static void clear() {
        UNTIL.clear();
    }

    private static void render(WorldRenderContext context) {
        if (UNTIL.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || context.matrixStack() == null || context.consumers() == null
                || context.camera() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> it = UNTIL.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue() <= now) it.remove();
        if (UNTIL.isEmpty()) return;
        Fx blade = new Fx(context, BLADE);
        Vec3 camera = context.camera().getPosition();
        double spin = mc.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (int id : UNTIL.keySet()) {
            Entity entity = mc.level.getEntity(id);
            if (entity == null || entity.isRemoved()) continue;
            if (camera.distanceToSqr(entity.position()) > 48 * 48) continue;
            Vec3 centre = entity.position().add(0, 1.05, 0);
            // 0.94: world-upright blades (a camera-facing sheet lays down when the camera looks
            // down, which the user reported as "swords are horizontal").
            // 0.96: four slimmer blades that keep drifting instead of a stiff ring of five.
            for (int i = 0; i < 4; i++) {
                double angle = Math.toRadians(i * 90 + spin * 26);
                double bob = .12 * Math.sin(spin * .5 + i * 1.3);
                Vec3 at = centre.add(Math.cos(angle) * .82, .28 + bob, Math.sin(angle) * .82);
                double yaw = Math.toDegrees(angle) + 90;
                blade.upright(at, .44, 1.28, yaw, .42f);
                blade.upright(at, .26, .84, yaw, .85f);
            }
        }
    }
}

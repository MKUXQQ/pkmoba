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
 * 0.132 Swords Dance aura for Ceruledge.
 *
 * <p>While the server-side {@code blades} buff runs, the twin blades keep burning: a blue-violet
 * ember wraps each hand and a low flame ring licks around the body. Keyed by entity id with its own
 * expiry so it outlives the cast scene and dies exactly when the buff does.
 */
public final class BladeAura {
    private BladeAura() {
    }

    private static final ResourceLocation EMBER =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_ember.png");
    private static final ResourceLocation DANCE =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_dance.png");
    private static final Map<Integer, Long> UNTIL = new HashMap<>();

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(BladeAura::render);
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
        Fx ember = new Fx(context, EMBER);
        Fx dance = new Fx(context, DANCE);
        Vec3 camera = context.camera().getPosition();
        double spin = mc.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (int id : UNTIL.keySet()) {
            Entity entity = mc.level.getEntity(id);
            if (entity == null || entity.isRemoved()) continue;
            if (camera.distanceToSqr(entity.position()) > 48 * 48) continue;
            Vec3 centre = entity.position().add(0, 1.0, 0);
            // Twin blades: a small flame on each side of the body, breathing out of phase.
            for (int i = 0; i < 2; i++) {
                double side = i == 0 ? -1 : 1;
                double bob = .1 * Math.sin(spin * .6 + i * 2.1);
                Vec3 at = centre.add(side * .46, .18 + bob, 0);
                ember.upright(at, .34, .62, 12 * side, .68f);
                ember.upright(at, .2, .38, 0, .9f);
            }
            // Low ring so the buff is readable even from behind.
            dance.groundRing(entity.position().add(0, .04, 0), .95, 2, spin * .35, .35f);
        }
    }
}

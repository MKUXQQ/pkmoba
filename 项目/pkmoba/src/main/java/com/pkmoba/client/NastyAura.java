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
 * 0.125 诡计 (Nasty Plot) aura: while the eight-second buff runs, a dark-violet seal pulses under
 * the Greninja and motes of dark energy orbit and drift up through the body.
 *
 * <p>Same ownership rules as {@link SwordAura}: the server broadcasts
 * {@code pkmoba:buff/plot/<ticks>} to every nearby client, so the buff is visible on other players'
 * screens and not only on the caster's. Keyed by entity id with its own expiry, so it keeps running
 * while the buffed player fires Q/W/E and stops exactly when the server-side buff runs out.
 */
public final class NastyAura {
    private NastyAura() {
    }

    private static final ResourceLocation SEAL = ResourceLocation.fromNamespaceAndPath(
            "pkmoba", "textures/effect/gj_plot_seal.png");
    private static final ResourceLocation MOTE = ResourceLocation.fromNamespaceAndPath(
            "pkmoba", "textures/effect/gj_plot_mote.png");
    private static final Map<Integer, Long> UNTIL = new HashMap<>();

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(NastyAura::render);
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
        Fx seal = new Fx(context, SEAL);
        Fx mote = new Fx(context, MOTE);
        Vec3 camera = context.camera().getPosition();
        double spin = mc.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (int id : UNTIL.keySet()) {
            Entity entity = mc.level.getEntity(id);
            if (entity == null || entity.isRemoved()) continue;
            if (camera.distanceToSqr(entity.position()) > 48 * 48) continue;
            Vec3 feet = entity.position();
            // Flat seal on the ground: two counter-rotating copies read as a slow dark churn.
            seal.slab(feet.add(0, .06, 0), 2.1, 2.1, (float) (spin * 18), .55f);
            seal.slab(feet.add(0, .07, 0), 1.5, 1.5, (float) (-spin * 34), .35f);
            Vec3 chest = feet.add(0, .95, 0);
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(i * 45 + spin * 42);
                double rise = ((spin * .11 + i / 8.0) % 1.0);
                double r = .78 * (1 - rise * .45);
                double y = .18 + rise * 1.35;
                double wobble = .06 * Math.sin(spin * .9 + i * 1.7);
                Vec3 at = chest.add(Math.cos(angle) * (r + wobble), y - .95, Math.sin(angle) * (r + wobble));
                double size = .26 - rise * .10;
                mote.sheet(at, size, size, (float) (spin * 120 + i * 40), .85f - (float) rise * .35f);
            }
            // A taller, thinner mote over the head so the buff reads at a distance.
            mote.upright(feet.add(0, 1.55, 0), .34, .34, (float) (spin * 90), .5f);
        }
    }
}

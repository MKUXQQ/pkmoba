package com.pkmoba.client;

import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-owned Pikachu QWER cues.
 *
 * <p>All art lives in {@link com.pkmoba.client.fx.PikachuFx}: chunky textured bolts with a white core.
 * This class only keeps per-cast scenes alive (TTL refresh for channelled moves), adds a restrained
 * sprinkling of vanilla electric pixels and forwards the scene to the painter. The old hairlines of
 * geometry are gone, which is what made the v2 effects read as one thin line each.
 */
public final class PikachuEffects {
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    private PikachuEffects() {}

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(PikachuEffects::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> SCENES.clear());
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                SCENES.values().removeIf(s -> mc.level != s.level || ++s.age - s.refreshedAge >= s.life));
    }

    public static boolean accept(EffectPayload p) {
        if (p == null || p.effectId() == null || !p.effectId().startsWith("pkmoba:pikachu/")) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        // Wire format is pkmoba:pikachu/<kind>/<entityId>; the final segment is NOT the kind.
        String[] parts = p.effectId().split("/");
        if (parts.length != 3) return true;
        String kind = parts[1], owner = parts[2];
        Vec3 pos = new Vec3(p.x(), p.y(), p.z()), vec = new Vec3(p.dx(), p.dy(), p.dz());
        if (!finite(pos) || !finite(vec)) return true;
        if (kind.equals("clear")) {
            SCENES.entrySet().removeIf(e -> e.getKey().startsWith(owner + ":"));
            return true;
        }
        int life = switch (kind) {
            case "r_warn", "r_charge", "r_cloud", "w_charge" -> 10;
            case "q_bolt" -> 14;
            case "q_chain", "e_chain" -> 10;
            case "w_beam" -> 6;
            case "w_beam_end" -> 18;
            case "r_field" -> 24;
            // The dash trail has to outlive the ten-tick dash so the bolt path stays on screen.
            case "e_start" -> 20;
            case "e_trail", "e_hit" -> 12;
            case "r_strike" -> 26;
            default -> 0;
        };
        if (kind.equals("w_end")) {
            SCENES.remove(owner + ":w_beam");
            SCENES.remove(owner + ":w_charge");
            return true;
        }
        if (life == 0) return true;
        if (kind.equals("r_strike")) {
            SCENES.remove(owner + ":r_warn");
            SCENES.remove(owner + ":r_charge");
            SCENES.remove(owner + ":r_cloud");
            try {
                HitFeedback.onPikachuThunderStrike(Integer.parseInt(owner));
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        if (kind.equals("r_charge")) {
            try {
                HitFeedback.onPikachuCharge(Integer.parseInt(owner), (float) vec.x);
            } catch (NumberFormatException ignored) {
                // 非数字 owner 不是玩家宝可梦，不需要本地镜头反馈
            }
        }
        if (kind.equals("w_beam")) SCENES.remove(owner + ":w_charge");
        if (kind.equals("w_beam_end")) SCENES.remove(owner + ":w_beam");
        String key = owner + ":" + kind;
        if (kind.equals("e_trail")) key += ":" + mc.level.getGameTime();
        Scene previous = SCENES.get(key);
        boolean continuous = kind.equals("r_charge") || kind.equals("r_warn") || kind.equals("r_cloud")
                || kind.equals("r_field") || kind.equals("w_beam") || kind.equals("w_charge");
        // Keep a cast's birth time. Snapshot refreshes extend TTL, never restart its animation.
        if (continuous && previous != null && previous.level == mc.level) {
            previous.pos = pos;
            previous.vec = vec;
            previous.refreshedAge = previous.age;
            previous.updated = System.nanoTime();
            return true;
        }
        SCENES.put(key, new Scene(mc.level, kind, pos, vec, life));
        while (SCENES.size() > 160) SCENES.remove(SCENES.keySet().iterator().next());
        return true;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** A few blocky vanilla sparks along the bolt: the pixel grain without flooding the screen. */
    private static void electricParticles(Scene s, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || alpha <= .05f) return;
        long now = mc.level.getGameTime();
        java.util.Random rnd = new java.util.Random((long) (s.pos.x * 92821 + s.pos.z * 68917) * 31 + now / 3);
        if (rnd.nextFloat() > .5f) return;
        Vec3 end = s.pos.add(s.vec);
        Vec3 mid = s.vec.lengthSqr() > .01 ? s.pos.add(s.vec.scale(rnd.nextDouble())) : s.pos;
        int n = 1 + rnd.nextInt(2);
        for (int i = 0; i < n; i++) {
            Vec3 p = mid.lerp(end, rnd.nextDouble() * .5);
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    p.x + (rnd.nextDouble() - .5) * .4, p.y + rnd.nextDouble() * .4,
                    p.z + (rnd.nextDouble() - .5) * .4,
                    (rnd.nextDouble() - .5) * .08, rnd.nextDouble() * .1, (rnd.nextDouble() - .5) * .08);
        }
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.matrixStack() == null || ctx.consumers() == null) return;
        Vec3 camera = ctx.camera().getPosition();
        double partial = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        for (Scene s : SCENES.values()) {
            if (s.level != mc.level || s.pos.distanceToSqr(camera) > 160 * 160) continue;
            double alive = s.life - (s.age - s.refreshedAge) - partial;
            float alpha = (float) Math.min(1, alive / 4.0);
            if (alpha <= 0) continue;
            double t = (mc.level.getGameTime() + partial) * .16 + s.seed;
            electricParticles(s, alpha);
            com.pkmoba.client.fx.PikachuFx.render(ctx, s.kind, s.pos, s.vec, t, alpha, s.seed);
        }
    }

    private static final class Scene {
        final ClientLevel level;
        final String kind;
        Vec3 pos, vec;
        final int life;
        final double seed = Math.random() * Math.PI * 2;
        int age, refreshedAge;
        long updated = System.nanoTime();

        Scene(ClientLevel level, String kind, Vec3 pos, Vec3 vec, int life) {
            this.level = level;
            this.kind = kind;
            this.pos = pos;
            this.vec = vec;
            this.life = life;
        }
    }
}

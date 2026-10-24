package com.pkmoba.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.client.particle.BedrockParticleOptionsRepository;
import com.cobblemon.mod.common.client.particle.ParticleStorm;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.network.WaterEffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;

/** Bounded, continuously refreshed water meshes. No gameplay or hit prediction lives here. */
public final class WaterEffects {
    // Dedicated low-frequency gradient: no tiled sprite sheet or coarse particle cells.
    private static final ResourceLocation SURFACE = ResourceLocation.parse("pkmoba:textures/effect/water_smooth.png");
    private static final ResourceLocation FOAM = ResourceLocation.parse("cobblemon:watergun_targetfoam");
    private static final ResourceLocation BURST = ResourceLocation.parse("cobblemon:hydropump_targetburst");
    private static final Map<Key, State> ACTIVE = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long ticks;
    private static boolean initialized;
    private WaterEffects() { }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(WaterEffectPayload.TYPE, (p, context) ->
                context.client().execute(() -> accept(context.client(), p)));
        ClientTickEvents.END_CLIENT_TICK.register(WaterEffects::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WaterEffects::render);
    }

    private static void clear() {
        ACTIVE.clear();
        currentLevel = null;
        ticks = 0;
    }

    private static void accept(Minecraft mc, WaterEffectPayload p) {
        if (mc.level == null) return;
        if (currentLevel != mc.level) { clear(); currentLevel = mc.level; }
        if (p.kind() == 0) {
            ACTIVE.keySet().removeIf(key -> key.entityId == p.entityId() && key.kind != 1 && key.kind != 2 && key.kind != 6);
            return;
        }
        if ((p.kind() == 1 || p.kind() == 6) && p.length() <= 0) {
            ACTIVE.remove(new Key(p.entityId(), p.kind()));
            return;
        }
        if (p.kind() < 1 || p.kind() > 6 || !finite(p)) return;
        if (!(mc.level.getEntity(p.entityId()) instanceof PokemonEntity)) return;
        Key key = new Key(p.entityId(), p.kind());
        if (!ACTIVE.containsKey(key) && ACTIVE.size() >= 32) return;
        State old = ACTIVE.get(key);
        int ttl = p.kind() == 2 && p.length() <= 0 ? 12 : 10;
        if (old == null || (p.kind() == 2 && p.length() <= 0 && old.packet.length() > 0))
            ACTIVE.put(key, new State(p, ticks + ttl, Long.MIN_VALUE));
        else { old.previous = old.sample(ticks); old.packet = p; old.received = ticks; old.expires = ticks + ttl; }
    }

    private static boolean finite(WaterEffectPayload p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
                && Double.isFinite(p.dx()) && Double.isFinite(p.dy()) && Double.isFinite(p.dz())
                && Double.isFinite(p.length()) && Double.isFinite(p.radius());
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.level != currentLevel) { clear(); return; }
        ticks++;
        ACTIVE.entrySet().removeIf(e -> ticks > e.getValue().expires
                || mc.level.getEntity(e.getKey().entityId) == null);
        // Small, bounded white-blue muzzle mist; never fill the length of the water beams.
        if (ticks % 2 == 0) for (var entry : ACTIVE.entrySet()) {
            if (entry.getKey().kind != 4 || !(mc.level.getEntity(entry.getKey().entityId) instanceof PokemonEntity entity)) continue;
            Vec3 dir = direction(entry.getValue().packet);
            Vec3 right = new Vec3(-dir.z, 0, dir.x).normalize();
            Vec3 fallback = entity.getEyePosition().add(0, 0.3, 0);
            muzzleMist(mc, cannon(entity, "cannon1", fallback.add(right.scale(0.65))), dir);
            muzzleMist(mc, cannon(entity, "cannon2", fallback.add(right.scale(-0.65))), dir);
        }
    }

    private static void muzzleMist(Minecraft mc, Vec3 pos, Vec3 dir) {
        if (mc.player == null || mc.player.distanceToSqr(pos) > 80 * 80) return;
        for (int i = 0; i < 3; i++) {
            var random = mc.level.random;
            Vec3 offset = new Vec3((random.nextDouble() - 0.5) * 0.55,
                    (random.nextDouble() - 0.5) * 0.42, (random.nextDouble() - 0.5) * 0.55);
            Vec3 velocity = dir.scale(0.07).add(offset.scale(0.18));
            Vec3 p = pos.add(dir.scale(0.14)).add(offset);
            var particle = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.CLOUD,
                    p.x, p.y, p.z, velocity.x, velocity.y, velocity.z);
            if (particle != null) {
                particle.setColor(0.78F, 0.93F, 1.0F);
                particle.scale(0.55F);
                particle.setLifetime(11 + random.nextInt(5));
            }
        }
    }

    private static void spawn(ClientLevel level, ResourceLocation id, Vec3 pos) {
        try {
            var effect = BedrockParticleOptionsRepository.INSTANCE.getEffect(id);
            if (effect != null) ParticleStorm.Companion.createAtPosition(level, effect, pos).spawn();
        } catch (RuntimeException ignored) {
            // Optional particles must never take down the mandatory mesh render.
        }
    }

    private static Vec3 direction(WaterEffectPayload p) {
        Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
        return dir.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : dir.normalize();
    }

    private static double length(WaterEffectPayload p) {
        return Math.max(0, Math.min(64, p.length()));
    }

    private static Vec3 cannon(PokemonEntity entity, String name, Vec3 fallback) {
        try {
            if (entity.getDelegate() instanceof PokemonClientDelegate delegate) {
                String side = "cannon1".equals(name) ? "right" : "left";
                var locator = delegate.getLocatorStates().get(name);
                if (locator == null) locator = delegate.getLocatorStates().get("cannon_" + side);
                if (locator == null) locator = delegate.getLocatorStates().get("locator_cannon_" + side);
                if (locator != null) {
                    Vec3 origin = locator.getOrigin();
                    if (Double.isFinite(origin.x) && Double.isFinite(origin.y) && Double.isFinite(origin.z)
                            && origin.distanceToSqr(entity.position()) < 64) return origin;
                }
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != currentLevel || ctx.matrixStack() == null
                || ctx.consumers() == null || ACTIVE.isEmpty()) return;
        Matrix4f matrix = ctx.matrixStack().last().pose();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.entityTranslucentEmissive(SURFACE));
        Vec3 camera = ctx.camera().getPosition();
        double time = mc.level.getGameTime() + ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        for (var entry : ACTIVE.entrySet()) {
            State state = entry.getValue();
            if (ticks > state.expires) continue;
            if (!(mc.level.getEntity(entry.getKey().entityId) instanceof PokemonEntity entity)
                    || !entity.isAlive()) continue;
            WaterEffectPayload p = state.sample(ticks + ctx.tickCounter().getGameTimeDeltaPartialTick(false));
            Vec3 pos = new Vec3(p.x(), p.y(), p.z());
            if (pos.distanceToSqr(camera) > 160 * 160) continue;
            Vec3 dir = direction(p);
            Vec3 right = new Vec3(-dir.z, 0, dir.x);
            if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
            right = right.normalize();
            if (p.kind() == 1) {
                blade(vc, matrix, pos.subtract(camera), dir, Math.max(0.3, Math.min(2, p.radius())));
            } else if (p.kind() == 4) {
                Vec3 fallback = entity.getEyePosition().add(0, 0.3, 0);
                Vec3 a = cannon(entity, "cannon1", fallback.add(right.scale(0.65)));
                Vec3 b = cannon(entity, "cannon2", fallback.add(right.scale(-0.65)));
                double frameTime = ticks + ctx.tickCounter().getGameTimeDeltaPartialTick(false);
                double blend = state.frameTime < 0 ? 1 : 1 - Math.exp(-Math.max(0, frameTime - state.frameTime) / 0.6);
                state.cannonA = state.cannonA == null ? a : state.cannonA.lerp(a, blend);
                state.cannonB = state.cannonB == null ? b : state.cannonB.lerp(b, blend);
                state.frameTime = frameTime;
                a = state.cannonA; b = state.cannonB;
                // Two independent full-length cannon streams; server damage and range stay unchanged.
                double r = 0.48;
                tube(vc, matrix, a.subtract(camera), dir, length(p), r, time, false);
                tube(vc, matrix, b.subtract(camera), dir, length(p), r, time + 8, false);
            } else if (p.kind() == 6) {
                // A readable, modest 0.46-block bubble; visual size does not change server hitboxes.
                sphere(vc, matrix, pos.subtract(camera), Math.max(0.18, Math.min(0.28, p.radius())));
            } else if (p.kind() == 2) {
                if (state.packet.length() > 0) {
                    sphere(vc, matrix, pos.subtract(camera), Math.max(0.2, Math.min(2, p.radius())));
                } else {
                    double age = Math.max(0, ticks + ctx.tickCounter().getGameTimeDeltaPartialTick(false) - state.born);
                    double progress = Math.min(1, age / 12.0);
                    ring(vc, matrix, pos.subtract(camera).add(0, 0.06, 0), new Vec3(1, 0, 0),
                            new Vec3(0, 0, 1), Math.max(0.1, p.radius() * progress),
                            0.24 * (1 - progress) + 0.03, 0, (int) (220 * (1 - progress)));
                }
            } else {
                Vec3 center = entity.position().add(0, p.kind() == 5 ? 1.4 : 0.5, 0).subtract(camera);
                double r = p.kind() == 5 ? 0.8 + 0.12 * Math.sin(time * 0.4)
                        : Math.max(0.8, Math.min(3, p.radius()));
                for (int tier = 0; tier < (p.kind() == 3 ? 1 : 2); tier++) {
                    ring(vc, matrix, center.add(0, tier * 0.4, 0), new Vec3(1, 0, 0),
                            new Vec3(0, 0, 1), r + tier * 0.15, 0.10, time * 0.22 + tier, 180);
                }
                if (p.kind() == 5) {
                    Vec3 fallback = entity.getEyePosition().subtract(camera);
                    ring(vc, matrix, cannon(entity, "cannon1", fallback.add(camera)).subtract(camera),
                            right, dir.cross(right).normalize(), 0.42, 0.09, time * 0.2, 210);
                    ring(vc, matrix, cannon(entity, "cannon2", fallback.add(camera)).subtract(camera),
                            right, dir.cross(right).normalize(), 0.42, 0.09, -time * 0.2, 210);
                }
            }
        }
    }

    /** A connected crescent in the flight plane, with a soft rim and tapered short wake. */
    private static void blade(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 dir, double size) {
        Vec3 right = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0)
                : new Vec3(0, 1, 0).cross(dir).normalize();
        Vec3 normal = dir.cross(right).normalize();
        // Continuous strips share their edge coordinates. No sprite tiles or scattered particles.
        for (int side = -1; side <= 1; side += 2) {
            for (int strip = 0; strip < 6; strip++) {
                double inner = strip / 6.0, outer = (strip + 1) / 6.0;
                for (int i = 0; i < 64; i++) {
                    double a = -1.45 + 2.9 * i / 64, b = -1.45 + 2.9 * (i + 1) / 64;
                    Vec3 p0 = bladePoint(center, right, dir, normal, size, a, inner, side);
                    Vec3 p1 = bladePoint(center, right, dir, normal, size, b, inner, side);
                    Vec3 p2 = bladePoint(center, right, dir, normal, size, b, outer, side);
                    Vec3 p3 = bladePoint(center, right, dir, normal, size, a, outer, side);
                    int alpha = strip == 5 ? 85 : strip >= 3 ? 210 : 145;
                    int red = strip >= 3 ? 195 : 35 + strip * 25;
                    if (side > 0) quad(vc, m, p0, p1, p2, p3, red, 220 + strip * 5, 255, alpha);
                    else quad(vc, m, p3, p2, p1, p0, red, 220 + strip * 5, 255, alpha);
                }
            }
        }
        // Three low-frequency streamlines taper behind the moving blade, not back to the cannon.
        for (int line = -1; line <= 1; line++) {
            double offset = line * size * 0.48;
            Vec3 head = center.add(right.scale(offset)).add(dir.scale(size * 0.1));
            Vec3 tail = head.subtract(dir.scale(size * (1.05 - Math.abs(line) * 0.25)));
            Vec3 width = right.scale(size * 0.045);
            quad(vc, m, head.subtract(width), head.add(width), tail, tail, 150, 240, 255, 90);
        }
    }

    private static Vec3 bladePoint(Vec3 center, Vec3 right, Vec3 dir, Vec3 normal,
                                   double size, double angle, double across, int side) {
        double taper = Math.max(0, Math.cos(angle / 1.45 * Math.PI / 2));
        double x = Math.sin(angle) * size * 1.5;
        double front = Math.cos(angle) * size * 0.8;
        double depth = size * 0.6 * taper * (1 - across);
        double thickness = side * size * 0.06 * taper * Math.sin(across * Math.PI);
        return center.add(right.scale(x)).add(dir.scale(front - depth)).add(normal.scale(thickness));
    }

    /** Closed, continuous cylindrical surfaces, bright core, turquoise shell and spiral ribbons. */
    private static void tube(VertexConsumer vc, Matrix4f m, Vec3 start, Vec3 dir,
                             double length, double radius, double time, boolean details) {
        if (length <= 0.01) return;
        Vec3 right = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0).cross(dir).normalize();
        Vec3 up = dir.cross(right).normalize();
        for (int layer = 0; layer < 2; layer++) {
            double r = radius * (layer == 0 ? 0.66 : 1);
            for (int side = 0; side < 48; side++) {
                double a = side * Math.PI * 2 / 48, b = (side + 1) * Math.PI * 2 / 48;
                Vec3 ra = right.scale(Math.cos(a) * r).add(up.scale(Math.sin(a) * r));
                Vec3 rb = right.scale(Math.cos(b) * r).add(up.scale(Math.sin(b) * r));
                quad(vc, m, start.add(ra), start.add(rb), start.add(rb).add(dir.scale(length)),
                        start.add(ra).add(dir.scale(length)), layer == 0 ? 190 : 35,
                        layer == 0 ? 240 : 165, 255, layer == 0 ? 220 : 125);
                quad(vc, m, start, start.add(rb), start.add(ra), start, 100, 210, 255, 180);
                Vec3 end = start.add(dir.scale(length));
                quad(vc, m, end, end.add(ra), end.add(rb), end, 160, 235, 255, 170);
            }
        }
        if (!details) return;
        int segments = Math.min(120, Math.max(16, (int) (length * 3)));
        for (int arm = 0; arm < 1; arm++) {
            for (int i = 0; i < segments; i++) {
                double t = length * i / segments, next = length * (i + 1) / segments;
                double a = t * 0.35 - time * 0.12, b = next * 0.35 - time * 0.12;
                Vec3 c = start.add(dir.scale(t)), d = start.add(dir.scale(next));
                quad(vc, m, radial(c, right, up, radius * 1.015, a), radial(c, right, up, radius * 1.015, a + 0.11),
                        radial(d, right, up, radius * 1.015, b + 0.11), radial(d, right, up, radius * 1.015, b),
                        215, 255, 255, 195);
            }
        }
    }

    private static Vec3 radial(Vec3 center, Vec3 right, Vec3 up, double radius, double angle) {
        return center.add(right.scale(radius * Math.cos(angle))).add(up.scale(radius * Math.sin(angle)));
    }

    private static void ring(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 right, Vec3 up,
                             double radius, double thickness, double phase, int alpha) {
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI / 20 + phase, b = (i + 1) * Math.PI / 20 + phase;
            double ripple = Math.sin(a * 5 + phase * 3) * 0.04;
            quad(vc, m, radial(center, right, up, radius + ripple, a),
                    radial(center, right, up, radius + thickness + ripple, a),
                    radial(center, right, up, radius + thickness, b), radial(center, right, up, radius, b),
                    110, 225, 255, alpha);
        }
    }

    private static void sphere(VertexConsumer vc, Matrix4f m, Vec3 center, double radius) {
        for (int row = 0; row < 20; row++) {
            double a = -Math.PI / 2 + row * Math.PI / 20;
            double b = -Math.PI / 2 + (row + 1) * Math.PI / 20;
            for (int col = 0; col < 40; col++) {
                double u = col * Math.PI / 20, v = (col + 1) * Math.PI / 20;
                int red = (int) (100 + 110 * (Math.sin(a) + 1) / 2);
                quad(vc, m, ballPoint(center, radius, a, u), ballPoint(center, radius, a, v),
                        ballPoint(center, radius, b, v), ballPoint(center, radius, b, u), red, 225, 255, 225);
            }
        }
    }

    private static Vec3 ballPoint(Vec3 center, double r, double latitude, double longitude) {
        return center.add(Math.cos(latitude) * Math.cos(longitude) * r, Math.sin(latitude) * r,
                Math.cos(latitude) * Math.sin(longitude) * r);
    }

    private static void wall(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 right, Vec3 dir, double time) {
        for (int layer = 0; layer < 3; layer++) {
            Vec3 base = center.add(dir.scale((layer - 1) * 0.22));
            for (int i = 0; i < 24; i++) {
                double x = -3 + i * 0.25, nx = x + 0.25;
                double h = 1.85 + 0.15 * Math.sin(x * 3 - time * 0.3);
                double nh = 1.85 + 0.15 * Math.sin(nx * 3 - time * 0.3);
                Vec3 a = base.add(right.scale(x)), b = base.add(right.scale(nx));
                quad(vc, m, a, b, b.add(0, nh, 0), a.add(0, h, 0), 45, 170 + layer * 20, 255, 125);
                quad(vc, m, a.add(0, h - 0.15, 0), b.add(0, nh - 0.15, 0),
                        b.add(0, nh, 0).add(dir.scale(-0.3)), a.add(0, h, 0).add(dir.scale(-0.3)),
                        215, 250, 255, 220);
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             int r, int g, int blue, int alpha) {
        vertex(vc, m, a, 0, 1, r, g, blue, alpha);
        vertex(vc, m, b, 1, 1, r, g, blue, alpha);
        vertex(vc, m, c, 1, 0, r, g, blue, alpha);
        vertex(vc, m, d, 0, 0, r, g, blue, alpha);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float u, float v,
                               int r, int g, int b, int alpha) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, alpha)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0, 1, 0);
    }

    private record Key(int entityId, int kind) { }
    private static final class State {
        WaterEffectPayload packet;
        WaterEffectPayload previous;
        long expires;
        long received;
        final long born;
        long lastSplash;
        Vec3 cannonA, cannonB;
        double frameTime = -1;
        State(WaterEffectPayload packet, long expires, long lastSplash) {
            this.packet = this.previous = packet; this.expires = expires; this.lastSplash = lastSplash;
            this.received = this.born = ticks;
        }
        WaterEffectPayload sample(double now) {
            double t = Math.max(0, Math.min(1, (now - received) / 1.5));
            WaterEffectPayload a = previous, b = packet;
            Vec3 direction = new Vec3(a.dx(), a.dy(), a.dz()).lerp(new Vec3(b.dx(), b.dy(), b.dz()), t);
            return new WaterEffectPayload(b.entityId(), b.kind(),
                    a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t,
                    a.z() + (b.z() - a.z()) * t, direction.x, direction.y, direction.z,
                    a.length() + (b.length() - a.length()) * t,
                    a.radius() + (b.radius() - a.radius()) * t);
        }
    }
}

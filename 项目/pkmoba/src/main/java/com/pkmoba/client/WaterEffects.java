package com.pkmoba.client;

import com.cobblemon.mod.common.client.particle.BedrockParticleOptionsRepository;
import com.cobblemon.mod.common.client.particle.ParticleStorm;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.combat.MouthAnchor;
import com.pkmoba.combat.Skill;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;

/** Bounded, continuously refreshed water meshes. No gameplay or hit prediction lives here. */
public final class WaterEffects {
    // Dedicated low-frequency gradient: no tiled sprite sheet or coarse particle cells.
    // 包内可见：SpoutEffects / SurfEffects 复用同一张水流渐变贴图，避免多一份资源。
    static final ResourceLocation SURFACE = ResourceLocation.parse("pkmoba:textures/effect/water_smooth.png");
    /** 超过这个距离就不再生成粒子（格）。 */
    private static final double PARTICLE_DISTANCE_SQR = 96 * 96;
    /** 加农水炮（channel 4）：客户端按 charge 进度自己画聚水 / 炮口亮球。 */
    private static final int CANNON_CHANNEL = 4;
    /** 出火后炮管半径从细到满值所用的 tick 数（"先蓄力、再爆发"的出手感）。 */
    private static final int BEAM_RAMP_TICKS = 4;
    // Streaming geometry is deterministic in world space; animation locators must not steer a beam.
    private static final int JET_SIDES = 16;
    private static final int MAX_JET_SEGMENTS = 64;
    private static final ResourceLocation FOAM = ResourceLocation.parse("cobblemon:watergun_targetfoam");
    private static final ResourceLocation BURST = ResourceLocation.parse("cobblemon:hydropump_targetburst");
    private static final Map<Key, State> ACTIVE = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long ticks;
    private static boolean initialized;
    /** 加农水炮前摇状态：charge 进度 0~1、两个炮口位置、前摇已经过的 tick。 */
    private static float chargeProgress;
    private static int chargeTicks;
    private static Vec3 chargeMuzzleA;
    private static Vec3 chargeMuzzleB;
    private static Vec3 chargeDir = Vec3.ZERO;
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
        chargeProgress = 0.0F;
        chargeTicks = 0;
        chargeMuzzleA = null;
        chargeMuzzleB = null;
        chargeDir = Vec3.ZERO;
    }

    private static void accept(Minecraft mc, WaterEffectPayload p) {
        if (mc.level == null) return;
        if (currentLevel != mc.level) { clear(); currentLevel = mc.level; }
        if (p.kind() == 0) {
            // kind 1（水之刃）已在 0.38 移除，只剩持续性特效在这里被统一清掉。
            ACTIVE.keySet().removeIf(key -> key.entityId == p.entityId() && key.kind != 2 && key.kind != 6);
            return;
        }
        if (p.kind() == 6 && p.length() <= 0) {
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
        if (mc.level == null) { clear(); return; }
        if (mc.level != currentLevel) { clear(); currentLevel = mc.level; }
        ticks++;
        ACTIVE.entrySet().removeIf(e -> ticks > e.getValue().expires
                || mc.level.getEntity(e.getKey().entityId) == null);
        // Small, bounded white-blue muzzle mist; never fill the length of the water beams.
        if (ticks % 2 == 0) for (var entry : ACTIVE.entrySet()) {
            if (entry.getKey().kind != 4 || !(mc.level.getEntity(entry.getKey().entityId) instanceof PokemonEntity entity)) continue;
            Vec3 dir = direction(entry.getValue().packet);
            for (Vec3 muzzle : beamAnchors(entry.getValue().packet)) muzzleMist(mc, muzzle, dir);
        }
        // 0.38.2：加农水炮的两段式节奏（前摇聚水 → 爆发束流）完全在客户端补，
        // 服务端只负责 kind5(前摇)/kind4(束流) 的语义，不改任何判定。
        tickCannon(mc);
        tickBeamSpray(mc);
    }

    /** 受控的水箭龟；channel 4 在喷火龙那边是 R 蓄力，所以这里必须按物种挡一下。 */
    private static PokemonEntity controlledBlastoise(Minecraft mc) {
        if (mc.level == null) return null;
        int id = ClientState.getCharizardId();
        if (id < 0) return null;
        if (mc.level.getEntity(id) instanceof PokemonEntity pokemon
                && pokemon.isAlive() && Skill.isBlastoise(pokemon)) {
            return pokemon;
        }
        return null;
    }

    /** 模型正前方：MC yaw=0 朝 +Z。 */
    private static Vec3 entityForward(PokemonEntity entity) {
        double radians = Math.toRadians(entity.getYRot());
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** Cobblemon 1.8 uses cannon2 for the left barrel, cannon1 for the right. */
    private static Vec3[] cannonAnchors(PokemonEntity entity) {
        return new Vec3[]{liveCannon(entity, true), liveCannon(entity, false)};
    }

    private static Vec3 liveCannon(PokemonEntity entity, boolean left) {
        if (entity.getDelegate() instanceof com.cobblemon.mod.common.client.entity.PokemonClientDelegate delegate) {
            var states = delegate.getLocatorStates();
            String[] keys = left ? new String[]{"cannon2", "special2", "locator_cannon_left"}
                    : new String[]{"cannon1", "special1", "locator_cannon_right"};
            if (states != null) for (String key : keys) {
                var locator = states.get(key);
                if (locator == null) continue;
                Vec3 pos = locator.getOrigin();
                if (pos != null && Double.isFinite(pos.x) && Double.isFinite(pos.y)
                        && Double.isFinite(pos.z) && pos.distanceToSqr(entity.position()) < 100
                        && pos.y > entity.getY() + 0.2) return pos;
            }
        }
        return MouthAnchor.cannonPosition(entity, left);
    }

    /** Only the origins follow animation; the shared endpoint remains server authoritative. */
    private static Vec3[] beamAnchors(WaterEffectPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(p.entityId()) instanceof PokemonEntity entity)
            return cannonAnchors(entity);
        Vec3 center = new Vec3(p.x(), p.y(), p.z());
        return new Vec3[]{center, center};
    }

    private static Vec3 beamEnd(WaterEffectPayload p) {
        return new Vec3(p.x(), p.y(), p.z()).add(direction(p).scale(length(p)));
    }

    /**
     * 加农水炮前摇（channel 4 且 charge &lt; 1）：两个炮口把水滴吸进来、炮口越来越亮；
     * charge 满的下一 tick 在炮口炸一圈水花，作为"喷出去"的起点。
     */
    private static void tickCannon(Minecraft mc) {
        boolean charging = ClientState.getChannel() == CANNON_CHANNEL && ClientState.getCharge() < 0.999F;
        if (!charging) {
            if (chargeTicks > 0) {
                if (chargeMuzzleA != null) burst(mc, chargeMuzzleA, chargeDir);
                if (chargeMuzzleB != null) burst(mc, chargeMuzzleB, chargeDir);
                chargeTicks = 0;
            }
            chargeProgress = 0.0F;
            chargeMuzzleA = null;
            chargeMuzzleB = null;
            return;
        }
        PokemonEntity entity = controlledBlastoise(mc);
        if (entity == null) return;
        chargeTicks++;
        chargeProgress = Math.max(chargeProgress, Math.min(1.0F, ClientState.getCharge()));
        chargeDir = entityForward(entity);
        // 0.38.5：聚水也贴在背部两个炮口上，和后面真正出流的束流起点接得上。
        Vec3[] muzzles = cannonAnchors(entity);
        chargeMuzzleA = muzzles[0];
        chargeMuzzleB = muzzles[1];
        if (mc.player == null || chargeMuzzleA.distanceToSqr(mc.player.position()) > PARTICLE_DISTANCE_SQR) return;
        var random = mc.level.random;
        int drops = 1 + (int) Math.round(chargeProgress * 3);
        for (Vec3 muzzle : new Vec3[]{chargeMuzzleA, chargeMuzzleB}) {
            for (int i = 0; i < drops; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double radius = 0.35 + random.nextDouble() * (0.85 - 0.35 * chargeProgress);
                Vec3 from = muzzle.add(chargeDir.scale(-0.1)).add(
                        Math.cos(angle) * radius,
                        (random.nextDouble() - 0.4) * radius * 0.9,
                        Math.sin(angle) * radius);
                Vec3 velocity = muzzle.subtract(from).scale(0.20 + chargeProgress * 0.12);
                mc.level.addParticle(ParticleTypes.SPLASH, from.x, from.y, from.z,
                        velocity.x, velocity.y, velocity.z);
            }
            if (chargeTicks % 3 == 0) {
                var mist = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                        muzzle.x, muzzle.y, muzzle.z,
                        chargeDir.x * 0.01, 0.01, chargeDir.z * 0.01);
                if (mist != null) {
                    mist.setColor(0.80F, 0.94F, 1.0F);
                    mist.scale(0.5F + chargeProgress * 0.7F);
                    mist.setLifetime(10 + random.nextInt(6));
                }
            }
        }
    }

    /** 满蓄力 / 出火瞬间：炮口一圈水滴，读起来像被压力顶开。 */
    private static void burst(Minecraft mc, Vec3 muzzle, Vec3 dir) {
        if (mc.level == null) return;
        var random = mc.level.random;
        Vec3 axis = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : dir;
        for (int i = 0; i < 8; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            Vec3 spread = new Vec3(Math.cos(angle), (random.nextDouble() - 0.3) * 0.8, Math.sin(angle))
                    .scale(0.10 + random.nextDouble() * 0.10);
            Vec3 velocity = axis.scale(0.22 + random.nextDouble() * 0.16).add(spread);
            mc.level.addParticle(ParticleTypes.SPLASH, muzzle.x, muzzle.y, muzzle.z,
                    velocity.x, velocity.y, velocity.z);
        }
        var mist = mc.particleEngine.createParticle(ParticleTypes.CLOUD,
                muzzle.x, muzzle.y, muzzle.z, axis.x * 0.12, 0.03, axis.z * 0.12);
        if (mist != null) {
            mist.setColor(0.86F, 0.96F, 1.0F);
            mist.scale(1.2F);
            mist.setLifetime(12);
        }
    }

    /** 束流中段的水雾 + 末端落点水花：让整条水柱"有体积、打到东西"。 */
    private static void tickBeamSpray(Minecraft mc) {
        if (mc.player == null || ticks % 2 != 0 || ACTIVE.isEmpty()) return;
        var random = mc.level.random;
        for (var entry : ACTIVE.entrySet()) {
            if (entry.getKey().kind != 4) continue;
            if (!(mc.level.getEntity(entry.getKey().entityId) instanceof PokemonEntity entity)) continue;
            WaterEffectPayload p = entry.getValue().packet;
            Vec3 tip = beamEnd(p);
            if (length(p) < 1.0) continue;
            // Spray follows the same converging segments as the rendered water surfaces.
            for (Vec3 start : beamAnchors(p)) {
                if (start.distanceToSqr(mc.player.position()) > PARTICLE_DISTANCE_SQR) continue;
                Vec3 delta = tip.subtract(start);
                Vec3 dir = delta.normalize();
                Vec3 right = new Vec3(-dir.z, 0, dir.x).normalize();
                double length = delta.length();
                Vec3 mid = start.add(dir.scale(length * (0.35 + random.nextDouble() * 0.55)))
                        .add(right.scale((random.nextDouble() - 0.5) * 0.5))
                        .add(0, (random.nextDouble() - 0.5) * 0.5, 0);
                // Tiny detached spray, not smoke clouds obscuring the continuous water surface.
                mc.level.addParticle(ParticleTypes.SPLASH, mid.x, mid.y, mid.z,
                        dir.x * 0.35, dir.y * 0.35 + 0.025, dir.z * 0.35);
                for (int k = 0; k < 2; k++) {
                    mc.level.addParticle(ParticleTypes.SPLASH, tip.x, tip.y, tip.z,
                            -dir.x * 0.12 + (random.nextDouble() - 0.5) * 0.34,
                            -dir.y * 0.12 + 0.18 + random.nextDouble() * 0.16,
                            -dir.z * 0.12 + (random.nextDouble() - 0.5) * 0.34);
                }
            }
        }
    }

    private static void muzzleMist(Minecraft mc, Vec3 pos, Vec3 dir) {
        if (mc.player == null || mc.player.distanceToSqr(pos) > 80 * 80) return;
        for (int i = 0; i < 1; i++) {
            var random = mc.level.random;
            Vec3 offset = new Vec3((random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.30, (random.nextDouble() - 0.5) * 0.35);
            Vec3 velocity = dir.scale(0.07).add(offset.scale(0.18));
            Vec3 p = pos.add(dir.scale(0.14)).add(offset);
            var particle = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.CLOUD,
                    p.x, p.y, p.z, velocity.x, velocity.y, velocity.z);
            if (particle != null) {
                particle.setColor(0.78F, 0.93F, 1.0F);
                particle.scale(0.30F);
                particle.setLifetime(5 + random.nextInt(3));
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

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        boolean chargeVisible = chargeTicks > 0 && chargeMuzzleA != null;
        if (mc.level == null || mc.level != currentLevel || ctx.matrixStack() == null
                || ctx.consumers() == null || (ACTIVE.isEmpty() && !chargeVisible)) return;
        Matrix4f matrix = ctx.matrixStack().last().pose();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.entityTranslucentEmissive(SURFACE));
        Vec3 camera = ctx.camera().getPosition();
        double time = mc.level.getGameTime() + ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        // 前摇：两个炮口各聚起一颗越来越大的水球（charge 进度 = ClientState.getCharge()）。
        if (chargeVisible) {
            double progress = Math.max(0.12, chargeProgress);
            double radius = 0.10 + 0.26 * progress;
            int alpha = (int) (150 + 100 * progress);
            for (Vec3 muzzle : new Vec3[]{chargeMuzzleA, chargeMuzzleB}) {
                if (muzzle == null) continue;
                Vec3 local = muzzle.subtract(camera);
                sphere(vc, matrix, local, radius);
                Vec3 axis = chargeDir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : chargeDir;
                Vec3 right = Math.abs(axis.y) > 0.95 ? new Vec3(1, 0, 0)
                        : new Vec3(0, 1, 0).cross(axis).normalize();
                ring(vc, matrix, local.add(axis.scale(radius * 0.3)), right,
                        axis.cross(right).normalize(), radius * 1.35, 0.07, time * 0.3, alpha);
            }
        }
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
            if (p.kind() == 4) {
                // Live barrel origins follow the rendered pose; both jets share the network endpoint.
                Vec3[] muzzles = beamAnchors(p);
                double ramp = Math.min(1.0, Math.max(0.35,
                        (ticks + ctx.tickCounter().getGameTimeDeltaPartialTick(false) - state.born + 1)
                                / BEAM_RAMP_TICKS));
                double radius = 0.31 * ramp;
                Vec3 end = beamEnd(p);
                Vec3 impactAxis = end.subtract(muzzles[0]).normalize();
                Vec3 impactRight = Math.abs(impactAxis.y) > .95 ? new Vec3(1, 0, 0)
                        : new Vec3(0, 1, 0).cross(impactAxis).normalize();
                Vec3 impactUp = impactAxis.cross(impactRight).normalize();
                // Converging jets finish in a compact, layered pressure crown at the server endpoint.
                for (int layer = 0; layer < 2; layer++) {
                    double pulse = (time * .12 + layer * .5) % 1.0;
                    ring(vc, matrix, end.subtract(camera).add(impactAxis.scale(.02 + layer * .03)),
                            impactRight, impactUp, .24 + pulse * .48, .07 * (1 - pulse) + .025,
                            time * .16, (int) (175 * (1 - pulse)));
                }
                for (int i = 0; i < muzzles.length; i++) {
                    Vec3 axis = end.subtract(muzzles[i]);
                    pressureJet(vc, matrix, muzzles[i].subtract(camera), axis.normalize(),
                            axis.length(), radius, time + i * 1.7);
                }
            } else if (p.kind() == 6) {
                // A readable, modest 0.46-block bubble; visual size does not change server hitboxes.
                sphere(vc, matrix, pos.subtract(camera), Math.max(0.18, Math.min(0.28, p.radius())));
            } else if (p.kind() == 2) {
                if (state.packet.length() > 0) {
                    // 飞行中的零散水滴：0.38 起统一用"上尖下圆 + 拖尾"外观，不再用球体。
                    double radius = Math.max(0.14, Math.min(0.4, p.radius() * 0.22));
                    droplet(vc, matrix, pos.subtract(camera), new Vec3(0, -1, 0), radius, 235);
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
                    // 0.38.5：蓄力环也贴背部两个真实炮口，和束流同一对锚点。
                    Vec3 armAxis = dir.cross(right).normalize();
                    Vec3[] muzzles = cannonAnchors(entity);
                    for (int i = 0; i < muzzles.length; i++) {
                        ring(vc, matrix, muzzles[i].subtract(camera),
                                right, armAxis, 0.42, 0.09, (i == 0 ? time * 0.2 : -time * 0.2), 210);
                    }
                }
            }
        }
    }

    /**
     * 单颗水滴：旋转体（8 纬 × 6 经 = 48 quad）上尖下圆，底部内收，后方拖 3 段短尾迹。
     *
     * <p>dir 是运动方向：圆底朝前、尖尾朝后，尾迹再向后延伸，这样下落时有速度感而不像静止球。
     * 供 kind 2（零散水滴）与 SpoutEffects（喷水降雨）共用。
     */
    static void droplet(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 dir, double radius, int alpha) {
        Vec3 axis = dir.lengthSqr() < 1.0E-8 ? new Vec3(0, -1, 0) : dir.normalize();
        Vec3 right = Math.abs(axis.y) > 0.95 ? new Vec3(1, 0, 0)
                : new Vec3(0, 1, 0).cross(axis).normalize();
        Vec3 up = axis.cross(right).normalize();
        for (int ring = 0; ring < 8; ring++) {
            double a = ring / 8.0, b = (ring + 1) / 8.0;
            for (int seg = 0; seg < 6; seg++) {
                double u = seg * Math.PI / 3, v = (seg + 1) * Math.PI / 3;
                Vec3 p0 = dropPoint(center, axis, right, up, radius, a, u);
                Vec3 p1 = dropPoint(center, axis, right, up, radius, a, v);
                Vec3 p2 = dropPoint(center, axis, right, up, radius, b, v);
                Vec3 p3 = dropPoint(center, axis, right, up, radius, b, u);
                // 上部尖尾提亮，下部圆底偏内核蓝，整体半透明。
                int red = ring >= 6 ? 235 : 120;
                int green = ring >= 6 ? 250 : 200;
                int a0 = ring >= 6 ? alpha : (int) (alpha * 0.85);
                quad(vc, m, p0, p1, p2, p3, red, green, 255, a0);
            }
        }
        // 3 段逐渐变窄的半透明尾迹，沿运动反方向拖出约 3.2 个半径。
        Vec3 tail = axis.scale(-radius * 3.2);
        for (int i = 0; i < 3; i++) {
            double t0 = i / 3.0, t1 = (i + 1) / 3.0;
            double w = radius * (0.55 - 0.14 * i);
            Vec3 base = center.add(tail.scale(t0));
            Vec3 tip = center.add(tail.scale(t1));
            int a0 = (int) (alpha * (0.4 - i * 0.1));
            quad(vc, m, base.subtract(right.scale(w)), base.add(right.scale(w)), tip, tip,
                    200, 240, 255, a0);
        }
    }

    private static Vec3 dropPoint(Vec3 center, Vec3 axis, Vec3 right, Vec3 up, double radius,
                                  double along, double angle) {
        // along: 0 = 前侧（运动方向）端点，1 = 后侧尖尾；两端半径收到 0，中前段鼓起，形成拉长的水滴轮廓。
        double profile = Math.pow(Math.sin(Math.PI * along), 0.8) * (1 - 0.5 * along);
        double forward = 0.9 - 2.7 * along;
        Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)))
                .scale(radius * profile);
        return center.add(axis.scale(radius * forward)).add(radial);
    }

    /** A solid pressure core, flowing water skin, fast white aeration and a terminal splash crown. */
    private static void pressureJet(VertexConsumer vc, Matrix4f m, Vec3 start, Vec3 dir,
                                    double length, double radius, double time) {
        if (length <= 0.01) return;
        Vec3 right = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0)
                : new Vec3(0, 1, 0).cross(dir).normalize();
        Vec3 up = dir.cross(right).normalize();
        int segments = Math.min(MAX_JET_SEGMENTS, Math.max(1, (int) Math.ceil(length * 2)));
        // The axis is perfectly straight. Only the surface radius changes, never the aim/origin.
        for (int layer = 0; layer < 2; layer++) {
            for (int i = 0; i < segments; i++) {
                double s = length * i / segments, t = length * (i + 1) / segments;
                Vec3 a = start.add(dir.scale(s)), b = start.add(dir.scale(t));
                double ra = jetRadius(radius, s, length, time) * (layer == 0 ? 0.67 : 1);
                double rb = jetRadius(radius, t, length, time) * (layer == 0 ? 0.67 : 1);
                for (int side = 0; side < JET_SIDES; side++) {
                    double u = side * Math.PI * 2 / JET_SIDES;
                    double v = (side + 1) * Math.PI * 2 / JET_SIDES;
                    // Continuous UVs along the entire jet, not a new sprite cell per axial segment.
                    waterQuad(vc, m, radial(a, right, up, ra, u), radial(a, right, up, ra, v),
                            radial(b, right, up, rb, v), radial(b, right, up, rb, u),
                            (float) side / JET_SIDES, (float) (side + 1) / JET_SIDES,
                            (float) (s / length), (float) (t / length),
                            layer == 0 ? 165 : 48, layer == 0 ? 228 : 171, 255,
                            layer == 0 ? 215 : 150);
                }
            }
        }
        // Thin longitudinal foam filaments travel OUT of the cannons. No orbiting/helix beam axis.
        for (int strand = 0; strand < 7; strand++) {
            double angle = strand * Math.PI * 2 / 7;
            for (int i = 0; i < segments; i++) {
                double s = length * i / segments, t = length * (i + 1) / segments;
                double flow = 0.5 + 0.5 * Math.sin(s * 3.6 - time * 2.5 + strand * 2.1);
                double width = 0.035 + 0.095 * flow;
                double a = angle + 0.025 * Math.sin(s * 2 - time * 0.8 + strand);
                double b = angle + 0.025 * Math.sin(t * 2 - time * 0.8 + strand);
                Vec3 c = start.add(dir.scale(s)), d = start.add(dir.scale(t));
                double ra = jetRadius(radius, s, length, time) * 1.025;
                double rb = jetRadius(radius, t, length, time) * 1.025;
                quad(vc, m, radial(c, right, up, ra, a - width), radial(c, right, up, ra, a + width),
                        radial(d, right, up, rb, b + width), radial(d, right, up, rb, b - width),
                        225, 249, 255, (int) (70 + 155 * flow));
            }
        }
        // Sparse elongated flecks make velocity legible even when looking almost down the barrel.
        for (int fleck = 0; fleck < 12; fleck++) {
            double s = ((time * 0.9 + fleck * 1.73) % length + length) % length;
            double t = Math.min(length, s + 0.45 + (fleck % 3) * 0.13);
            double angle = fleck * 2.39996;
            double r = jetRadius(radius, s, length, time) * 1.14;
            Vec3 a = radial(start.add(dir.scale(s)), right, up, r, angle);
            Vec3 b = radial(start.add(dir.scale(t)), right, up, r, angle);
            Vec3 width = right.scale(-Math.sin(angle)).add(up.scale(Math.cos(angle))).scale(0.026);
            quad(vc, m, a.subtract(width), a.add(width), b.add(width), b.subtract(width),
                    240, 253, 255, 205);
        }
        // Tight white nozzle collar: visually pressurised, never a large cloud hiding the model.
        ring(vc, m, start.add(dir.scale(Math.min(0.10, length * 0.2))), right, up,
                radius * 0.86, radius * 0.24, time * 0.17, 220);
        jetImpact(vc, m, start.add(dir.scale(length)), dir, right, up, radius, time);
    }

    private static double jetRadius(double radius, double along, double length, double time) {
        double opening = 0.78 + 0.22 * Math.min(1, along / 0.65);
        double tip = 1 + 0.16 * Math.pow(along / Math.max(0.01, length), 6);
        return radius * opening * tip * (1 + 0.045 * Math.sin(along * 5.2 - time * 2.7));
    }

    /** Impact is compact and attached to the network endpoint, not an independently aimed effect. */
    private static void jetImpact(VertexConsumer vc, Matrix4f m, Vec3 end, Vec3 dir,
                                  Vec3 right, Vec3 up, double radius, double time) {
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI / 10, b = (i + 1) * Math.PI / 10;
            double wave = 0.5 + 0.5 * Math.sin(time * 1.8 + i * 2.4);
            double outer = radius * (1.8 + wave * 0.8);
            Vec3 innerA = radial(end, right, up, radius * 0.65, a);
            Vec3 innerB = radial(end, right, up, radius * 0.65, b);
            Vec3 outerA = radial(end.subtract(dir.scale(0.12 + wave * 0.18)), right, up, outer, a);
            Vec3 outerB = radial(end.subtract(dir.scale(0.12 + wave * 0.18)), right, up, outer, b);
            quad(vc, m, innerA, innerB, outerB, outerA, 218, 246, 255, 190);
            // Broken, bright foam edge; the negative axial component reads as rebounding spray.
            quad(vc, m, outerA, outerB,
                    radial(end.subtract(dir.scale(0.42 + wave * 0.25)), right, up, outer * 1.12, b),
                    radial(end.subtract(dir.scale(0.42 + wave * 0.25)), right, up, outer * 1.12, a),
                    245, 253, 255, (int) (80 + 100 * wave));
        }
        for (int i = 0; i < 10; i++) {
            double flight = (time * 0.15 + i * 0.137) % 1;
            double angle = i * 2.39996;
            Vec3 normal = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            Vec3 p = end.add(normal.scale(radius * (1.2 + flight * 3)))
                    .subtract(dir.scale(flight * 0.85)).add(0, -flight * flight * 0.32, 0);
            Vec3 tail = p.subtract(normal.scale(0.12 + flight * 0.10)).add(dir.scale(0.10));
            Vec3 w = right.scale(0.025 * (1 - flight) + 0.008);
            quad(vc, m, p.subtract(w), p.add(w), tail.add(w), tail.subtract(w),
                    235, 251, 255, (int) (210 * (1 - flight)));
        }
    }

    private static void waterQuad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                                  float u0, float u1, float v0, float v1,
                                  int red, int green, int blue, int alpha) {
        vertex(vc, m, a, u0, v0, red, green, blue, alpha);
        vertex(vc, m, b, u1, v0, red, green, blue, alpha);
        vertex(vc, m, c, u1, v1, red, green, blue, alpha);
        vertex(vc, m, d, u0, v1, red, green, blue, alpha);
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

    /** 包内可见：SpoutEffects 拼水滴/水膜网格时复用同一套顶点格式。 */
    static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
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
        State(WaterEffectPayload packet, long expires, long lastSplash) {
            this.packet = this.previous = packet; this.expires = expires; this.lastSplash = lastSplash;
            this.received = this.born = ticks;
        }
        WaterEffectPayload sample(double now) {
            // The beam packet already IS the server ray. Blending opposite aims can collapse to
            // zero and fall back to +Z; a second low-pass also makes the jet trail behind the model.
            if (packet.kind() == 4) return packet;
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

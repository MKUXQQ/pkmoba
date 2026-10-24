package com.pkmoba.client;

import com.cobblemon.mod.common.client.particle.BedrockParticleOptionsRepository;
import com.cobblemon.mod.common.client.particle.ParticleStorm;
import com.pkmoba.network.TornadoPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/** 复用Cobblemon的firespin火焰材质/参数动画；网络只传区域出生事件。 */
public final class TornadoEffects {
    private static final ResourceLocation FIRE = ResourceLocation.parse("cobblemon:firespin_target");
    private static final ResourceLocation CINDER = ResourceLocation.parse("cobblemon:firespin_actorcinder");
    private static final List<Vortex> ACTIVE = new ArrayList<>();
    private TornadoEffects() { }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(TornadoPayload.TYPE, (p, context) ->
                context.client().execute(() -> {
                    ClientLevel level = context.client().level;
                    if (level == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y())
                            || !Double.isFinite(p.z()) || !Double.isFinite(p.radius())) return;
                    if (ACTIVE.size() >= 8) ACTIVE.remove(0).clear();
                    ACTIVE.add(new Vortex(level, p.entityId(), new Vec3(p.x(), p.y(), p.z()),
                            Math.max(0.5, Math.min(4, p.radius())), Math.max(1, Math.min(100, p.durationTicks()))));
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> ACTIVE.removeIf(v -> !v.tick(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ACTIVE.forEach(Vortex::clear);
            ACTIVE.clear();
        });
    }

    private static final class Vortex {
        final ClientLevel level;
        final int entityId;
        final Vec3 center;
        final double radius;
        final int duration;
        final List<Layer> layers = new ArrayList<>();
        int age;

        Vortex(ClientLevel level, int entityId, Vec3 center, double radius, int duration) {
            this.level = level; this.entityId = entityId; this.center = center;
            this.radius = radius; this.duration = duration;
        }

        boolean tick(Minecraft client) {
            if (client.level != level || ClientState.getCharizardId() != entityId
                    || level.getEntity(entityId) == null || age >= duration) {
                clear();
                return false;
            }
            // 复用效果本身1.1秒发射/0.6秒粒子寿命，每22tick补一批，不无限积累。
            layers.removeIf(layer -> !layer.storm.isAlive());
            if (age % 22 == 0 && age < duration - 12) {
                for (int tier = 0; tier < 3; tier++) {
                    for (int arm = 0; arm < 2; arm++) {
                        ParticleStorm storm = spawn(FIRE, center);
                        if (storm != null) layers.add(new Layer(storm, tier, arm));
                    }
                }
            }
            double envelope = Math.min(1, (age + 1) / 8.0) * Math.min(1, (duration - age) / 12.0);
            for (Layer layer : layers) {
                double t = layer.tier / 2.0;
                // 底部收束，上层展开；Cobblemon自身的相对粒子轨迹继续螺旋上升。
                double r = (0.15 + t * Math.max(0, radius - 1.5)) * envelope;
                double angle = age * 0.16 + layer.arm * Math.PI + t * 2.1;
                Vec3 pos = center.add(Math.cos(angle) * r, 0.1 + t * 2.4, Math.sin(angle) * r);
                PoseStack pose = new PoseStack();
                pose.translate(pos.x, pos.y, pos.z);
                layer.storm.getEmitterSpaceMatrix().updateMatrix(pose.last().pose());
            }
            if (age % 4 == 0 && age < duration - 5) {
                double angle = age * 0.3;
                spawn(CINDER, center.add(Math.cos(angle) * radius * 0.7, 0.5, Math.sin(angle) * radius * 0.7));
            }
            age++;
            return true;
        }

        ParticleStorm spawn(ResourceLocation id, Vec3 pos) {
            var effect = BedrockParticleOptionsRepository.INSTANCE.getEffect(id);
            if (effect == null) return null;
            ParticleStorm storm = ParticleStorm.Companion.createAtPosition(level, effect, pos);
            storm.spawn();
            return storm;
        }

        void clear() {
            for (Layer layer : layers) {
                layer.storm.setInvisible();
                layer.storm.remove();
            }
            layers.clear();
        }
    }

    private record Layer(ParticleStorm storm, int tier, int arm) { }
}

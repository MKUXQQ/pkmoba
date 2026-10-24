package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：播放一个 Cobblemon 招式粒子特效。
 *
 * <p>为什么由服务端发 id、客户端去查表生成：
 * <ul>
 *   <li>{@code ParticleStorm} / {@code BedrockParticleOptionsRepository} 都是客户端类，
 *       服务端拿不到；</li>
 *   <li>把特效交给客户端生成，就不必依赖 Cube/粒子在服务端的可用性。</li>
 * </ul>
 * effectId 例如 {@code cobblemon:flamethrower_actor}。
 */
public record EffectPayload(String effectId, double x, double y, double z,
                            double dx, double dy, double dz) implements CustomPacketPayload {
    public static final Type<EffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EffectPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeUtf(p.effectId());
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                        buf.writeDouble(p.dx());
                        buf.writeDouble(p.dy());
                        buf.writeDouble(p.dz());
                    },
                    buf -> new EffectPayload(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

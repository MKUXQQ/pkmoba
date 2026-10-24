package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：Q 喷火射流。
 *
 * <p>服务端提供判伤原点、方向和被墙截断后的径向长度；客户端 mouth locator
 * 只用于短接驳，不能平移或延长主体判伤锥。
 */
public record JetPayload(int entityId, double x, double y, double z, double dx, double dy, double dz,
                         double length) implements CustomPacketPayload {
    public static final Type<JetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "jet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JetPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeVarInt(p.entityId());
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                        buf.writeDouble(p.dx());
                        buf.writeDouble(p.dy());
                        buf.writeDouble(p.dz());
                        buf.writeDouble(p.length());
                    },
                    buf -> new JetPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

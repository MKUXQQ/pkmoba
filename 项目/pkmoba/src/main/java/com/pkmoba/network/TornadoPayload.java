package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 单次W区域出生事件；持续动画完全在客户端生成。 */
public record TornadoPayload(int entityId, double x, double y, double z,
                             double radius, int durationTicks) implements CustomPacketPayload {
    public static final Type<TornadoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "tornado"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TornadoPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.entityId());
                buf.writeDouble(p.x()); buf.writeDouble(p.y()); buf.writeDouble(p.z());
                buf.writeDouble(p.radius()); buf.writeVarInt(p.durationTicks());
            }, buf -> new TornadoPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readVarInt()));
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C water visuals: 0 stop skills, 1 blade, 2 falling orb, 3 spin, 4 beam, 5 charge, 6 basic bubble. */
public record WaterEffectPayload(int entityId, int kind, double x, double y, double z,
                                 double dx, double dy, double dz, double length,
                                 double radius) implements CustomPacketPayload {
    public static final Type<WaterEffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "water_effect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaterEffectPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.entityId());
                buf.writeVarInt(p.kind());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeDouble(p.dx());
                buf.writeDouble(p.dy());
                buf.writeDouble(p.dz());
                buf.writeDouble(p.length());
                buf.writeDouble(p.radius());
            }, buf -> new WaterEffectPayload(buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

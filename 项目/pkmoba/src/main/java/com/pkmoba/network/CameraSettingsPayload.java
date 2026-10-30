package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Server command requests only; client owns persistence and current settings. */
public record CameraSettingsPayload(int action, double left, double right, double vertical, double speed) implements CustomPacketPayload {
    public static final Type<CameraSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "camera_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CameraSettingsPayload> CODEC = StreamCodec.of((b,p) -> {
        b.writeVarInt(p.action()); b.writeDouble(p.left()); b.writeDouble(p.right()); b.writeDouble(p.vertical()); b.writeDouble(p.speed());
    }, b -> new CameraSettingsPayload(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

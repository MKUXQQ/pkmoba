package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 0.93: left-click punch input for the controlled Pokémon.
 *
 * <p>{@code targetId} is the entity the crosshair picked (or -1 when nothing was picked, in which
 * case the server punches whatever stands in front within range).
 */
public record BasicAttackPayload(int targetId) implements CustomPacketPayload {
    public static final Type<BasicAttackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "basic_attack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BasicAttackPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeVarInt(payload.targetId()),
                    buf -> new BasicAttackPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

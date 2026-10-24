package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: request one basic attack; all eligibility is checked by the server. */
public record BasicAttackPayload(int targetId) implements CustomPacketPayload {
    public static final Type<BasicAttackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "basic_attack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BasicAttackPayload> CODEC =
            StreamCodec.of((buf, value) -> buf.writeVarInt(value.targetId()),
                    buf -> new BasicAttackPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

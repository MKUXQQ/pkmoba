package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端把**它实际接受的目标点**回发给客户端。
 *
 * <p>这是诊断"点了却走不到"的关键：HUD 会同时显示
 * 「客户端点中的点」和「服务端接受的点」，两者不一致就说明是射线算偏了。
 */
public record MoveAckPayload(double x, double y, double z, boolean accepted) implements CustomPacketPayload {
    public static final Type<MoveAckPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "move_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MoveAckPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                        buf.writeDouble(payload.x());
                        buf.writeDouble(payload.y());
                        buf.writeDouble(payload.z());
                        buf.writeBoolean(payload.accepted());
                    },
                    buf -> new MoveAckPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

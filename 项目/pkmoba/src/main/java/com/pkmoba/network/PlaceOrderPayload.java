package com.pkmoba.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：选点召唤——客户端把"鼠标点到的方块坐标"发给服务端，在那里生成喷火龙。
 */
public record PlaceOrderPayload(BlockPos target) implements CustomPacketPayload {
    public static final Type<PlaceOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "place_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceOrderPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBlockPos(payload.target()),
                    buf -> new PlaceOrderPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

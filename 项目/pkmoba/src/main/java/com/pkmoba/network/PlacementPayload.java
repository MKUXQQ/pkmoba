package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：告诉客户端"现在是否处于选点召唤状态"。
 *
 * <p>执行 {@code /mo summon} 后进入选点：客户端据此拦截下一次右键，把点到的方块坐标
 * 用 {@link PlaceOrderPayload} 发回来。召唤完成后服务端回 armed=false。
 */
public record PlacementPayload(boolean armed) implements CustomPacketPayload {
    public static final Type<PlacementPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "placement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlacementPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBoolean(payload.armed()),
                    buf -> new PlacementPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

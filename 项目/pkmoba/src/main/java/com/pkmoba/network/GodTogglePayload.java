package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端请求切换上帝视角。
 *
 * <p>为什么是“请求”而不是“状态上报”：任务书第 4 节要求**服务端权威**——
 * 客户端只说“我想切”，到底切没切、玩家的锁定与解锁由服务端决定（见 ServerGodMode），
 * 避免改客户端就获得上帝视角。
 *
 * <p>空包体，用 {@link StreamCodec#unit(Object)} 即可（不写任何字节）。
 */
public record GodTogglePayload() implements CustomPacketPayload {
    public static final Type<GodTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "god_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GodTogglePayload> CODEC =
            StreamCodec.unit(new GodTogglePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

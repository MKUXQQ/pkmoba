package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端通知客户端“你现在是否处于上帝视角”。
 *
 * <p>这样 F12 和 {@code /mo god} 两条入口都走同一套状态：客户端不再自己改开关，
 * 一律以服务端回包为准，保证镜头状态和服务端的锁定状态永远一致。
 */
public record GodStatePayload(boolean active, double anchorX, double anchorY, double anchorZ) implements CustomPacketPayload {
    public GodStatePayload(boolean active) {
        this(active, Double.NaN, Double.NaN, Double.NaN);
    }
    public static final Type<GodStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "god_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GodStatePayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeBoolean(payload.active());
                buf.writeDouble(payload.anchorX());
                buf.writeDouble(payload.anchorY());
                buf.writeDouble(payload.anchorZ());
            }, buf -> new GodStatePayload(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

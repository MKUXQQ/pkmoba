package com.pkmoba.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：对某条挑战请求的接受/拒绝意图。
 *
 * <p>服务端会重新核对 requestId、被挑战者身份、是否过期、是否已处理、双方是否在局内；
 * 客户端发什么都不能直接开战。
 */
public record ChallengeActionPayload(UUID requestId, boolean accept) implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("pkmoba", "challenge_action");
    public static final Type<ChallengeActionPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeActionPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeUUID(payload.requestId() == null ? new UUID(0L, 0L) : payload.requestId());
                buf.writeBoolean(payload.accept());
            }, buf -> new ChallengeActionPayload(buf.readUUID(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

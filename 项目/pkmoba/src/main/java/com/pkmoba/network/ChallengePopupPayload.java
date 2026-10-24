package com.pkmoba.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：1v1 挑战弹窗状态。
 *
 * <p>{@code open=false} 表示弹窗应当关闭（接受/拒绝/超时/断线都会下发一帧）。
 * {@code secondsLeft} 由服务端按 tick 计算，客户端只负责显示，不自行判断过期。
 */
public record ChallengePopupPayload(boolean open, UUID requestId, String challengerName, int secondsLeft)
        implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("pkmoba", "challenge_popup");
    public static final Type<ChallengePopupPayload> TYPE = new Type<>(ID);

    public ChallengePopupPayload {
        if (challengerName == null) challengerName = "";
        if (challengerName.length() > 64) challengerName = challengerName.substring(0, 64);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ChallengePopupPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeBoolean(payload.open());
                buf.writeUUID(payload.requestId() == null ? new UUID(0L, 0L) : payload.requestId());
                buf.writeUtf(payload.challengerName(), 64);
                buf.writeVarInt(Math.max(0, payload.secondsLeft()));
            }, buf -> new ChallengePopupPayload(
                    buf.readBoolean(), buf.readUUID(), buf.readUtf(64), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

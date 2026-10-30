package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/** S2C：对局结束与胜方播报。 */
public record MatchResultPayload(String winnerName, boolean youWin, String reason) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "match_result");
    public static final CustomPacketPayload.Type<MatchResultPayload> TYPE = new CustomPacketPayload.Type<>(ID);
    private static final int MAX_STRING = 64;

    public static final StreamCodec<FriendlyByteBuf, MatchResultPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.winnerName == null ? "" : payload.winnerName, MAX_STRING);
                buf.writeBoolean(payload.youWin);
                buf.writeUtf(payload.reason == null ? "" : payload.reason, MAX_STRING);
            },
            buf -> new MatchResultPayload(buf.readUtf(MAX_STRING), buf.readBoolean(), buf.readUtf(MAX_STRING)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

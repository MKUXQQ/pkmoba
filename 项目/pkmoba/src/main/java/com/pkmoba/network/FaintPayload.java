package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：某只宝可梦开始播放战败动画。
 *
 * <p>服务端不会立刻移除实体，客户端收到后对没有 native faint 的种族播自制动画。
 */
public record FaintPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "faint");
    public static final CustomPacketPayload.Type<FaintPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, FaintPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.entityId);
                buf.writeVarInt(payload.ticks);
            },
            buf -> new FaintPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

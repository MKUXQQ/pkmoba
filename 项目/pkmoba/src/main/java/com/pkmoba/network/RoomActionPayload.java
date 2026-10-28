package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：房间大厅操作。
 *
 * <p>{@code action} 的合法值：{@code OPEN}、{@code CREATE}、{@code JOIN}、{@code LEAVE}、
 * {@code START}、{@code SELECT}。客户端传来的只是意图，权限与状态全部在服务端复核。
 */
public record RoomActionPayload(String action, int roomId, String arg) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "room_action");
    public static final CustomPacketPayload.Type<RoomActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, RoomActionPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.action == null ? "" : p.action, 32);
                buf.writeVarInt(Math.max(0, p.roomId));
                buf.writeUtf(p.arg == null ? "" : p.arg, 256);
            },
            buf -> new RoomActionPayload(buf.readUtf(32), buf.readVarInt(), buf.readUtf(256)));

    public static RoomActionPayload of(String action) {
        return new RoomActionPayload(action, 0, "");
    }

    public static RoomActionPayload of(String action, int roomId) {
        return new RoomActionPayload(action, roomId, "");
    }

    public static RoomActionPayload of(String action, int roomId, String arg) {
        return new RoomActionPayload(action, roomId, arg);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

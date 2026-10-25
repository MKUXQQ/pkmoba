package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：红光召回 / 放出（0.41.8）。
 *
 * <p>用户 m05076：「按 C 收回我需要一束红光，要你自己画的，然后收回到宝可梦球中
 * 并且融入玩家身体里面」。所以召回不再是"原地竖一根光柱"，而是
 * <b>宝可梦 → 精灵球光点 → 玩家身体</b> 的一条红色光束，由客户端自己绘制。
 *
 * @param entityId 宝可梦实体 id（客户端用它取实时位置，飞向玩家时不会错位）
 * @param playerId 玩家实体 id（光束终点）
 * @param durationTicks 总时长
 * @param recall true=召回（宝可梦 → 玩家），false=放出（玩家 → 宝可梦）
 */
public record RecallFxPayload(int entityId, int playerId, int durationTicks, boolean recall)
        implements CustomPacketPayload {
    public static final Type<RecallFxPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "recall_fx"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecallFxPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.entityId());
                buf.writeVarInt(p.playerId());
                buf.writeVarInt(p.durationTicks());
                buf.writeBoolean(p.recall());
            }, buf -> new RecallFxPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

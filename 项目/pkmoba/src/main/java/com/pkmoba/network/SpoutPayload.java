package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：水箭龟 R 槽「喷水」的降雨区域（0.38）。
 *
 * <p>服务端在喷水前摇结束（t=10）时广播一次，客户端据此在固定扇形内自行模拟雨滴
 * 与溅射视觉；服务端的命中结算与视觉独立，客户端不需要回包。
 *
 * <p>字段契约（客户端按此实现，不可改）：
 * <ul>
 *   <li>{@code entityId}：施法者实体 id，客户端用于定位/追踪归属；</li>
 *   <li>{@code x/y/z}：扇形锚点（施法瞬间宝可梦位置），整轮降雨期间固定不变；</li>
 *   <li>{@code yaw}：扇形中心朝向（MC 角度制，与 {@code modelForward} 的 yaw 口径一致）；</li>
 *   <li>{@code halfAngleDeg}：扇形半角（±30°）；</li>
 *   <li>{@code length}：扇形半径（14 格）；</li>
 *   <li>{@code durationTicks}：降雨持续时间；<b>0 表示立即结束</b>（施法者死亡/换人/对局结束）；</li>
 *   <li>{@code seed}：该轮降雨的随机种子，客户端用它复现雨滴落点节奏。</li>
 * </ul>
 */
public record SpoutPayload(int entityId, double x, double y, double z,
                           float yaw, float halfAngleDeg, float length,
                           int durationTicks, long seed) implements CustomPacketPayload {
    public static final Type<SpoutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "spout"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpoutPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.entityId());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.yaw());
                buf.writeFloat(p.halfAngleDeg());
                buf.writeFloat(p.length());
                buf.writeVarInt(p.durationTicks());
                buf.writeLong(p.seed());
            }, buf -> new SpoutPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

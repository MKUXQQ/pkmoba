package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 水箭龟 R「水炮风暴」的客户端表现广播（0.43.0）。
 *
 * <p>服务端只负责伤害与节奏，视觉交给客户端自己画：收到这条包就开始在实体周围
 * 播一场持续 {@code durationTicks} 的水龙卷；{@code durationTicks = 0} 表示立刻停止
 * （打断/死亡/换人/对局结束），客户端据此清理，不会留下残影。
 *
 * @param entityId      施法者实体 id
 * @param durationTicks 整场风暴的 tick 数（含蓄力）；0 = 立即停止
 * @param radius        范围半径（格），客户端用它画范围圈与水柱半径
 * @param seed          随机种子，保证多端水花走向一致
 */
public record StormPayload(int entityId, int durationTicks, float radius, long seed)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StormPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "storm"));

    public static final StreamCodec<ByteBuf, StormPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeInt(payload.entityId());
                buf.writeInt(payload.durationTicks());
                buf.writeFloat(payload.radius());
                buf.writeLong(payload.seed());
            },
            buf -> new StormPayload(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readLong()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

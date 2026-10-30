package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：一次性批量生成同一特效的多个实例。
 *
 * <p>为什么需要批量：Q 每 tick 要撒 10 个点、W 18 个、R 23 个。
 * 如果每点一个包，单 tick 就是几十个包，网络和客户端都吃不消。
 * 这里把同一帧的坐标打包成一个包发过去。
 *
 * <p>数据布局：{@code coords} 是扁平的 [x,y,z, x,y,z, ...]。
 */
public record MultiEffectPayload(String effectId, double[] coords) implements CustomPacketPayload {
    public static final Type<MultiEffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "multi_effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MultiEffectPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeUtf(p.effectId());
                        int count = p.coords().length / 3;
                        buf.writeVarInt(count);
                        for (int i = 0; i < count * 3; i++) {
                            buf.writeDouble(p.coords()[i]);
                        }
                    },
                    buf -> {
                        String id = buf.readUtf();
                        int count = buf.readVarInt();
                        double[] data = new double[count * 3];
                        for (int i = 0; i < data.length; i++) {
                            data[i] = buf.readDouble();
                        }
                        return new MultiEffectPayload(id, data);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：引导期间持续上报瞄准点。
 *
 * <p>为什么需要它：Q 要求"边移动边跟随鼠标喷火"，但 {@link SkillCastPayload} 只在按下瞬间发一次。
 * 引导期间客户端每隔几 tick 发一次最新瞄准点，服务端据此每 tick 转向 + 重算扇形。
 * 服务端只在"确实在引导"时才接受，防止被滥用。
 */
public record AimPayload(double x, double y, double z) implements CustomPacketPayload {
    public static final Type<AimPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "aim"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AimPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                    },
                    buf -> new AimPayload(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

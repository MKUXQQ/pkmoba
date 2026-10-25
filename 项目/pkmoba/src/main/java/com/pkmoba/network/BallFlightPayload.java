package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：投球召唤的精灵球飞行轨迹（0.41.8）。
 *
 * <p>用户反馈「球没有飞出去，一直卡在头顶」——根因是服务端用 {@code ItemEntity} 摆位置，
 * 客户端对物品实体的位置同步/插值不可靠。改成纯客户端渲染：服务端只广播一条轨迹，
 * 客户端自己按时间插值画出精灵球，位置绝对平滑，也不会在世界里留下任何实体。
 *
 * @param fx/fy/fz 出手点（玩家眼睛附近）
 * @param tx/ty/tz 落点
 * @param durationTicks 飞行总 tick
 * @param item 物品 id（一般是 cobblemon:poke_ball）
 * @param seed 随机种子（客户端做轻微摆动用）
 */
public record BallFlightPayload(double fx, double fy, double fz,
                                double tx, double ty, double tz,
                                int durationTicks, String item, long seed)
        implements CustomPacketPayload {
    public static final Type<BallFlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "ball_flight"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BallFlightPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeDouble(p.fx());
                buf.writeDouble(p.fy());
                buf.writeDouble(p.fz());
                buf.writeDouble(p.tx());
                buf.writeDouble(p.ty());
                buf.writeDouble(p.tz());
                buf.writeVarInt(p.durationTicks());
                buf.writeUtf(p.item(), 64);
                buf.writeLong(p.seed());
            }, buf -> new BallFlightPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt(), buf.readUtf(64), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

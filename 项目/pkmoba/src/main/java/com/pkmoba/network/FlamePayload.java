package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：批量生成自定义火焰团粒子。
 *
 * <p>mode：
 * <ul>
 *   <li>{@code vortex} —— 绕 (cx,cy,cz) 的切向速度 + 缓慢上升（W 龙卷）；</li>
 *   <li>{@code glyph} —— 几乎静止（R 拖尾、爆炸余焰）。</li>
 * </ul>
 * tier 决定用哪档粒子：small / normal / glyph / big。
 */
public record FlamePayload(String mode, double dx, double dy, double dz,
                           double cx, double cy, double cz,
                           String tier, double[] coords) implements CustomPacketPayload {
    public static final Type<FlamePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "flame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlamePayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeUtf(p.mode());
                        buf.writeDouble(p.dx());
                        buf.writeDouble(p.dy());
                        buf.writeDouble(p.dz());
                        buf.writeDouble(p.cx());
                        buf.writeDouble(p.cy());
                        buf.writeDouble(p.cz());
                        buf.writeUtf(p.tier());
                        int count = p.coords().length / 3;
                        buf.writeVarInt(count);
                        for (int i = 0; i < count * 3; i++) {
                            buf.writeDouble(p.coords()[i]);
                        }
                    },
                    buf -> {
                        String mode = buf.readUtf();
                        double dx = buf.readDouble();
                        double dy = buf.readDouble();
                        double dz = buf.readDouble();
                        double cx = buf.readDouble();
                        double cy = buf.readDouble();
                        double cz = buf.readDouble();
                        String tier = buf.readUtf();
                        int count = buf.readVarInt();
                        double[] data = new double[count * 3];
                        for (int i = 0; i < data.length; i++) {
                            data[i] = buf.readDouble();
                        }
                        return new FlamePayload(mode, dx, dy, dz, cx, cy, cz, tier, data);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

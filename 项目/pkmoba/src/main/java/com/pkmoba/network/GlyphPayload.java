package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：R 的"大"字弹道位置 + 飞行方向。
 *
 * <p>方向是给客户端用来在字面后方补粒子拖尾的（字面冒火由客户端自己算，
 * 因为字的朝向取决于镜头，只有客户端知道）。
 */
public record GlyphPayload(boolean active, double x, double y, double z,
                           double size, double dx, double dy, double dz) implements CustomPacketPayload {
    public static final Type<GlyphPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "glyph"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlyphPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeBoolean(p.active());
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                        buf.writeDouble(p.size());
                        buf.writeDouble(p.dx());
                        buf.writeDouble(p.dy());
                        buf.writeDouble(p.dz());
                    },
                    buf -> new GlyphPayload(buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

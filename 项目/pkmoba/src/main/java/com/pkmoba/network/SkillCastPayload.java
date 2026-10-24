package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：释放技能。携带技能序号与鼠标瞄准点。
 *
 * <p>方向型技能（Q）用瞄准点算方向；点地型（W/E/R）直接把瞄准点当落点。
 */
public record SkillCastPayload(int skill, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<SkillCastPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "skill_cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SkillCastPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                        buf.writeVarInt(payload.skill());
                        buf.writeDouble(payload.x());
                        buf.writeDouble(payload.y());
                        buf.writeDouble(payload.z());
                    },
                    buf -> new SkillCastPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

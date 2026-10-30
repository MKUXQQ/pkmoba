package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：技能状态 + 场地信息。
 *
 * <p>场地信息（圆心 / 半径 / 距边界）是给客户端画地面圆环用的 —— 光有距离数字
 * 玩家看不到边界在哪，体验很差。
 */
public record SkillStatePayload(float hp, float maxHp, float points,
                                int cdQ, int cdW, int cdE, int cdR,
                                int channel, float charge,
                                float arenaDist, float arenaCx, float arenaCy, float arenaCz,
                                float arenaRadius) implements CustomPacketPayload {
    public static final Type<SkillStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "skill_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SkillStatePayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeFloat(p.hp());
                        buf.writeFloat(p.maxHp());
                        buf.writeFloat(p.points());
                        buf.writeVarInt(p.cdQ());
                        buf.writeVarInt(p.cdW());
                        buf.writeVarInt(p.cdE());
                        buf.writeVarInt(p.cdR());
                        buf.writeVarInt(p.channel());
                        buf.writeFloat(p.charge());
                        buf.writeFloat(p.arenaDist());
                        buf.writeFloat(p.arenaCx());
                        buf.writeFloat(p.arenaCy());
                        buf.writeFloat(p.arenaCz());
                        buf.writeFloat(p.arenaRadius());
                    },
                    buf -> new SkillStatePayload(
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readVarInt(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

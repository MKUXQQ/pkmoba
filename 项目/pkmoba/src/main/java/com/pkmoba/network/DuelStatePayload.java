package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：对局中"敌对玩家 + 敌对宝可梦"的实时信息。
 *
 * <p>血量全部取自服务端实体真实 {@code getHealth()/getMaxHealth()}，
 * 客户端只负责画条，不做任何本地推算，避免和真实血量不同步。
 * {@code active=false} 表示对局结束，客户端隐藏对手面板。
 *
 * @param ownerId       对手玩家 UUID（客户端据此画玩家头）
 * @param ownerName     对手玩家名
 * @param pokemonName   对手宝可梦显示名
 * @param species       对手宝可梦物种路径（charizard/blastoise），客户端据此选头像
 * @param teamRemaining 对手还剩几只没倒下的宝可梦
 */
public record DuelStatePayload(boolean active, float hp, float maxHp,
                               UUID ownerId, String ownerName,
                               String pokemonName, String species, int teamRemaining)
        implements CustomPacketPayload {
    public static final Type<DuelStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "duel_state"));

    private static final int MAX_NAME = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, DuelStatePayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeBoolean(p.active());
                        buf.writeFloat(p.hp());
                        buf.writeFloat(p.maxHp());
                        buf.writeBoolean(p.ownerId() != null);
                        if (p.ownerId() != null) buf.writeUUID(p.ownerId());
                        buf.writeUtf(p.ownerName() == null ? "" : p.ownerName(), MAX_NAME);
                        buf.writeUtf(p.pokemonName() == null ? "" : p.pokemonName(), MAX_NAME);
                        buf.writeUtf(p.species() == null ? "" : p.species(), MAX_NAME);
                        buf.writeVarInt(p.teamRemaining());
                    },
                    buf -> {
                        boolean active = buf.readBoolean();
                        float hp = buf.readFloat();
                        float maxHp = buf.readFloat();
                        UUID ownerId = buf.readBoolean() ? buf.readUUID() : null;
                        return new DuelStatePayload(active, hp, maxHp, ownerId,
                                buf.readUtf(MAX_NAME), buf.readUtf(MAX_NAME),
                                buf.readUtf(MAX_NAME), buf.readVarInt());
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

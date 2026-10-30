package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：告诉客户端"当前被操控的喷火龙是哪个实体"。
 *
 * <p>为什么需要它：上帝视角的相机要锚在喷火龙身上，客户端必须能拿到它的 EntityId。
 * 不能靠"在客户端遍历找 charizard 物种"——世界里可能有野生喷火龙，会认错。
 * entityId = -1 表示当前没有。
 */
public record CharizardSyncPayload(int entityId) implements CustomPacketPayload {
    public static final Type<CharizardSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "charizard_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CharizardSyncPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeVarInt(payload.entityId()),
                    buf -> new CharizardSyncPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

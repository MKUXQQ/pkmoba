package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * C2S：右键点地后，把**鼠标实际命中的精确坐标**发给服务端。
 *
 * <p>为什么不再用 BlockPos（用户选 D2-B）：
 * 用方块中心会导致"你点格子边缘、它走格子中间"的偏差，最多差 0.7 格。
 * 改成 {@link Vec3} 精确命中点后，落点和你眼睛看到的点一致。
 */
public record MoveOrderPayload(Vec3 pos) implements CustomPacketPayload {
    public static final Type<MoveOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "move_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MoveOrderPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                        buf.writeDouble(payload.pos().x);
                        buf.writeDouble(payload.pos().y);
                        buf.writeDouble(payload.pos().z);
                    },
                    buf -> new MoveOrderPayload(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

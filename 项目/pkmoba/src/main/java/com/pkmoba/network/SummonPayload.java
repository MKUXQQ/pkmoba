package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/** C2S：按 X 在准星指向的地面点召唤当前宝可梦。 */
public record SummonPayload(double x, double y, double z) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "summon");
    public static final CustomPacketPayload.Type<SummonPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, SummonPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeDouble(payload.x);
                buf.writeDouble(payload.y);
                buf.writeDouble(payload.z);
            },
            buf -> new SummonPayload(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.pkmoba.network;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** S2C server-authoritative Venusaur skill and seed projectile visuals. */
public record VenusaurEffectPayload(UUID entityId, String kind, Vec3 position, Vec3 direction,
                                    double radius, int durationTicks, float value)
        implements CustomPacketPayload {
    public static final Type<VenusaurEffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "venusaur_effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VenusaurEffectPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                        buf.writeUUID(payload.entityId());
                        buf.writeUtf(payload.kind());
                        writeVec(buf, payload.position());
                        writeVec(buf, payload.direction());
                        buf.writeDouble(payload.radius());
                        buf.writeVarInt(payload.durationTicks());
                        buf.writeFloat(payload.value());
                    }, buf -> new VenusaurEffectPayload(buf.readUUID(), buf.readUtf(), readVec(buf), readVec(buf),
                            buf.readDouble(), buf.readVarInt(), buf.readFloat()));

    private static void writeVec(RegistryFriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

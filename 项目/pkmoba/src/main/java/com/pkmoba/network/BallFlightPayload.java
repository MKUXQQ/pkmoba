package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;

/** Server-clipped trajectory, one sample per tick. Empty points cancels this flight. */
public record BallFlightPayload(long flightId, List<Vec3> points) implements CustomPacketPayload {
    public static final Type<BallFlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "ball_flight"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BallFlightPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeLong(p.flightId()); buf.writeVarInt(p.points().size());
                for (Vec3 v : p.points()) { buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); }
            }, buf -> {
                long id = buf.readLong(); int size = buf.readVarInt();
                if (size < 0 || size > 257) throw new IllegalArgumentException("Invalid ball trajectory length");
                List<Vec3> points = new ArrayList<>(size);
                for (int i=0;i<size;i++) points.add(new Vec3(buf.readDouble(),buf.readDouble(),buf.readDouble()));
                return new BallFlightPayload(id, List.copyOf(points));
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

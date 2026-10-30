package com.pkmoba.network;

import com.pkmoba.network.MenuOpenPayload.MenuKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Client menu intent; the server must recheck permissions, players and duel state. */
public record MenuActionPayload(MenuKind kind, String action, String argA, String argB)
        implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("pkmoba", "menu_action");
    public static final Type<MenuActionPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuActionPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeEnum(payload.kind());
                buf.writeUtf(payload.action(), 16);
                buf.writeUtf(payload.argA(), 64);
                buf.writeUtf(payload.argB(), 64);
            }, buf -> new MenuActionPayload(buf.readEnum(MenuKind.class),
                    buf.readUtf(16), buf.readUtf(64), buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

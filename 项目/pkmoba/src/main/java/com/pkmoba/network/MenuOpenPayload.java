package com.pkmoba.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Server-authorized menu snapshot. Actions are still validated by the server. */
public record MenuOpenPayload(MenuKind kind, List<String> players, List<String> arenas)
        implements CustomPacketPayload {
    /**
     * BATTLE：普通玩家开界面（不含自己，选人即挑战）。
     * ADMIN：管理员强制开战界面（含自己，可选两名玩家）。
     * CHALLENGE_ALL：管理员挑战界面（含自己但自己不可选，选人即发 10 秒挑战弹窗）。
     */
    public enum MenuKind { BATTLE, ADMIN, CHALLENGE_ALL }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("pkmoba", "menu_open");
    public static final Type<MenuOpenPayload> TYPE = new Type<>(ID);
    private static final int MAX_PLAYERS = 1024;
    private static final int MAX_ARENAS = 128;

    public MenuOpenPayload {
        java.util.Objects.requireNonNull(kind, "kind");
        players = List.copyOf(players);
        arenas = arenas == null ? List.of() : List.copyOf(arenas);
        if (players.size() > MAX_PLAYERS) throw new IllegalArgumentException("Too many menu players");
        if (arenas.size() > MAX_ARENAS) throw new IllegalArgumentException("Too many menu arenas");
    }

    /** 旧签名：没有场地列表。 */
    public MenuOpenPayload(MenuKind kind, List<String> players) {
        this(kind, players, List.of());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuOpenPayload> CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeEnum(payload.kind());
                buf.writeVarInt(payload.players().size());
                for (String player : payload.players()) buf.writeUtf(player, 64);
                buf.writeVarInt(payload.arenas().size());
                for (String arena : payload.arenas()) buf.writeUtf(arena, 64);
            }, buf -> {
                MenuKind kind = buf.readEnum(MenuKind.class);
                int size = buf.readVarInt();
                if (size < 0 || size > MAX_PLAYERS) throw new IllegalArgumentException("Invalid menu player count");
                List<String> players = new ArrayList<>(size);
                for (int i = 0; i < size; i++) players.add(buf.readUtf(64));
                int arenaCount = buf.readVarInt();
                if (arenaCount < 0 || arenaCount > MAX_ARENAS) {
                    throw new IllegalArgumentException("Invalid menu arena count");
                }
                List<String> arenas = new ArrayList<>(arenaCount);
                for (int i = 0; i < arenaCount; i++) arenas.add(buf.readUtf(64));
                return new MenuOpenPayload(kind, players, arenas);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

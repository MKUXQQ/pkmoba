package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：房间大厅快照（供 H 键界面渲染）。
 *
 * <p>{@code myRoomId} = 0 表示当前不在任何房间里；{@code mySide} 0=无、1=红、2=蓝。
 */
public record RoomListPayload(List<RoomView> rooms, int myRoomId, int mySide, List<String> arenas, List<String> favorites)
        implements CustomPacketPayload {
    private static final int MAX_ROOMS = 64;
    private static final int MAX_ARENAS = 64;

    /** 单个房间的展示数据。 */
    public record RoomView(int id, String arena, String host, String red, String blue,
                           int state, int seconds, boolean canJoin, boolean canStart,
                           String hostId, String redId, String blueId, List<String> redTeam, List<String> blueTeam,
                            int maxPlayers, int capacity, boolean passwordProtected, String displayName,
                            List<String> redRoster, List<String> blueRoster, boolean ready, int readyCount, int visibility,
                             String botId) {
        public RoomView(int id, String arena, String host, String red, String blue,
                        int state, int seconds, boolean canJoin, boolean canStart,
                        String hostId, String redId, String blueId, List<String> redTeam, List<String> blueTeam,
                        int maxPlayers, int capacity, boolean passwordProtected, String displayName,
                        List<String> redRoster, List<String> blueRoster, boolean ready, int readyCount, int visibility) {
            this(id, arena, host, red, blue, state, seconds, canJoin, canStart, hostId, redId, blueId,
                    redTeam, blueTeam, maxPlayers, capacity, passwordProtected, displayName,
                    redRoster, blueRoster, ready, readyCount, visibility, "");
        }
        public String arenaDisplayName() { return com.pkmoba.duel.RoomRules.arenaDisplayName(arena); }
        public boolean hasBot() { return !botId.isEmpty(); }
        public boolean isBot(String seatId) { return hasBot() && botId.equals(seatId); }
        public RoomView(int id, String arena, String host, String red, String blue,
                        int state, int seconds, boolean canJoin, boolean canStart,
                        String hostId, String redId, String blueId, List<String> redTeam, List<String> blueTeam) {
            this(id, arena, host, red, blue, state, seconds, canJoin, canStart, hostId, redId, blueId,
                    redTeam, blueTeam, 2, 3, false, arena, List.of(), List.of(), false, 0, 0);
        }
    }

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "room_list");
    public static final CustomPacketPayload.Type<RoomListPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, RoomListPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.rooms.size());
                for (RoomView room : p.rooms) {
                    buf.writeVarInt(room.id());
                    buf.writeUtf(room.arena(), 64);
                    buf.writeUtf(room.host(), 64);
                    buf.writeUtf(room.red(), 64);
                    buf.writeUtf(room.blue(), 64);
                    buf.writeVarInt(room.state());
                    buf.writeVarInt(Math.max(0, room.seconds()));
                    buf.writeBoolean(room.canJoin());
                    buf.writeBoolean(room.canStart());
                    buf.writeUtf(room.hostId(), 64);
                    buf.writeUtf(room.redId(), 64);
                    buf.writeUtf(room.blueId(), 64);
                    writeTeam(buf, room.redTeam());
                    writeTeam(buf, room.blueTeam());
                    buf.writeVarInt(room.maxPlayers());
                    buf.writeVarInt(room.capacity());
                    buf.writeBoolean(room.passwordProtected());
                    buf.writeUtf(room.displayName(), 64);
                    writeTeam(buf, room.redRoster());
                    writeTeam(buf, room.blueRoster());
                    buf.writeBoolean(room.ready());
                    buf.writeVarInt(room.readyCount());
                    buf.writeVarInt(room.visibility());
                    buf.writeUtf(room.botId(), 64);
                }
                buf.writeVarInt(p.myRoomId);
                buf.writeVarInt(p.mySide);
                buf.writeVarInt(p.arenas.size());
                for (String arena : p.arenas) buf.writeUtf(arena, 64);
                buf.writeVarInt(p.favorites.size());
                for (String favorite : p.favorites) buf.writeUtf(favorite, 128);
            },
            buf -> {
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_ROOMS) throw new IllegalArgumentException("room count");
                List<RoomView> rooms = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    rooms.add(new RoomView(buf.readVarInt(), buf.readUtf(64), buf.readUtf(64),
                            buf.readUtf(64), buf.readUtf(64), buf.readVarInt(),
                            buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                            buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), readTeam(buf), readTeam(buf),
                            buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(64),
                            readTeam(buf), readTeam(buf), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));
                }
                int myRoomId = buf.readVarInt();
                int mySide = buf.readVarInt();
                int arenaCount = buf.readVarInt();
                if (arenaCount < 0 || arenaCount > MAX_ARENAS) throw new IllegalArgumentException("arena count");
                List<String> arenas = new ArrayList<>(arenaCount);
                for (int i = 0; i < arenaCount; i++) arenas.add(buf.readUtf(64));
                int favoriteCount = buf.readVarInt();
                if (favoriteCount < 0 || favoriteCount > 4096) throw new IllegalArgumentException("favorite count");
                List<String> favorites = new ArrayList<>(favoriteCount);
                for (int i = 0; i < favoriteCount; i++) favorites.add(buf.readUtf(128));
                return new RoomListPayload(rooms, myRoomId, mySide, arenas, favorites);
            });

    private static void writeTeam(FriendlyByteBuf buf, List<String> team) {
        int count = team.size();
        if (count > com.pkmoba.pokemon.SpeciesRegistry.ids().size()) throw new IllegalArgumentException("room team count");
        buf.writeVarInt(count);
        for (String species : team) buf.writeUtf(species, 128);
    }

    private static List<String> readTeam(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > com.pkmoba.pokemon.SpeciesRegistry.ids().size()) throw new IllegalArgumentException("room team count");
        List<String> team = new ArrayList<>(count);
        for (int i = 0; i < count; i++) team.add(buf.readUtf(128));
        return team;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

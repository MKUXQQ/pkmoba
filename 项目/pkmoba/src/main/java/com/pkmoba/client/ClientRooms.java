package com.pkmoba.client;

import com.pkmoba.network.RoomActionPayload;
import com.pkmoba.network.RoomListPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import javax.annotation.Nullable;

/** 0.49：所有房间子屏共享权威快照；返回时重新解析房间，不返回陈旧 Screen 实例。 */
public final class ClientRooms {
    @Nullable private static volatile RoomListPayload latest;
    private static int pendingJoinRoom;
    private static final boolean[] COUNTDOWN_KEY_DOWN = new boolean[10];
    private ClientRooms() {}
    @Nullable public static RoomListPayload latest() { return latest; }

    /** 清理跨服务器缓存与按键边沿；由客户端断开连接回调调用。 */
    public static void reset() {
        latest = null;
        pendingJoinRoom = 0;
        java.util.Arrays.fill(COUNTDOWN_KEY_DOWN, false);
        StarterScreen.cancelRoomReturn();
    }

    public static void handle(RoomListPayload payload) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            latest = payload;
            RoomListPayload.RoomView mine = currentRoom(payload.myRoomId());
            boolean roomScreen = client.screen instanceof RoomLobbyScreen || client.screen instanceof RoomDetailScreen
                    || client.screen instanceof RoomTeamScreen || client.screen instanceof RoomSettingsScreen
                    || client.screen instanceof RoomPasswordScreen
                    || client.screen instanceof StarterScreen starter && starter.isRoomPicker();
            if (mine != null && mine.state() == 3 && roomScreen) {
                StarterScreen.cancelRoomReturn();
                client.setScreen(null);
            } else if (client.screen instanceof RoomLobbyScreen lobby) {
                lobby.update(payload);
            } else if (client.screen instanceof RoomDetailScreen detail) {
                detail.update(payload);
            } else if (client.screen instanceof RoomTeamScreen team) {
                if (mine == null || mine.id() != team.roomId()) returnToRoom(team.roomId());
                else team.update(mine);
            } else if (client.screen instanceof RoomSettingsScreen settings) {
                if (mine == null || mine.id() != settings.roomId()) returnToRoom(settings.roomId());
                else settings.update(mine);
            } else if (client.screen instanceof StarterScreen starter && starter.isRoomPicker()) {
                starter.updateRoom(payload);
            } else if (client.screen instanceof RoomPasswordScreen password) {
                password.update(payload);
            }
        });
    }
    public static void openLobby() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        latest = null;
        pendingJoinRoom = 0;
        StarterScreen.cancelRoomReturn();
        send("OPEN", 0, "");
        client.setScreen(new RoomLobbyScreen());
    }
    @Nullable public static RoomListPayload.RoomView currentRoom(int roomId) {
        RoomListPayload snapshot = latest;
        if (snapshot == null || roomId == 0) return null;
        return snapshot.rooms().stream().filter(room -> room.id() == roomId).findFirst().orElse(null);
    }
    public static void returnToRoom(int roomId) {
        Minecraft mc = Minecraft.getInstance();
        RoomListPayload.RoomView room = currentRoom(roomId);
        if (room != null && myRoomId() == roomId) mc.setScreen(room.state() == 3 ? null : new RoomDetailScreen(room));
        else mc.setScreen(new RoomLobbyScreen());
        send("OPEN", 0, "");
    }
    public static boolean isBlue(RoomListPayload.RoomView room) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().toString().equals(room.blueId());
    }
    public static boolean isHost(RoomListPayload.RoomView room) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().toString().equals(room.hostId());
    }
    public static String status(RoomListPayload.RoomView room) {
        return switch (room.state()) {
            case 2 -> "倒计时 " + Math.max(0, room.seconds()) + "s";
            case 3 -> "对战中";
            case 4 -> "结算中";
            default -> "准备 " + room.readyCount() + "/2";
        };
    }
    public static boolean inCountdown() {
        RoomListPayload.RoomView room = currentRoom(myRoomId());
        return room != null && room.state() == 2;
    }
    public static int myRoomId() { return latest == null ? 0 : latest.myRoomId(); }
    public static void markPendingJoin(int roomId) { pendingJoinRoom = roomId; }
    public static boolean consumePendingJoin(int roomId) {
        if (roomId == 0 || pendingJoinRoom != roomId) return false;
        pendingJoinRoom = 0;
        return true;
    }
    public static void tickCountdownInput(Minecraft mc) {
        if (mc.getWindow() == null) return;
        boolean active = mc.screen == null && mc.player != null && inCountdown();
        long window = mc.getWindow().getWindow();
        RoomListPayload.RoomView room = currentRoom(myRoomId());
        java.util.List<String> team = room == null ? java.util.List.of() : isBlue(room) ? room.blueRoster() : room.redRoster();
        for (int i = 1; i <= 9; i++) {
            boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_0 + i);
            if (down && !COUNTDOWN_KEY_DOWN[i] && active && i <= team.size()) send("SELECT", myRoomId(), team.get(i - 1));
            COUNTDOWN_KEY_DOWN[i] = down;
        }
    }
    public static void send(String action, int roomId, String arg) {
        if (ClientPlayNetworking.canSend(RoomActionPayload.TYPE)) ClientPlayNetworking.send(RoomActionPayload.of(action, roomId, arg));
    }
}

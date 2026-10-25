package com.pkmoba.client;

import com.pkmoba.network.RoomActionPayload;
import com.pkmoba.network.RoomListPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * 房间大厅的客户端状态缓存。
 *
 * <p>服务端每次房间状态变化都会推一份 {@link RoomListPayload}；这里只保存最新快照，
 * 界面自己从快照渲染。所有操作都发回服务端复核，客户端不做任何本地判定。
 */
public final class ClientRooms {
    @Nullable
    private static volatile RoomListPayload latest;

    private ClientRooms() {
    }

    @Nullable
    public static RoomListPayload latest() {
        return latest;
    }

    public static void handle(RoomListPayload payload) {
        latest = payload;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof RoomLobbyScreen lobby) {
                lobby.update(payload);
            } else if (client.screen instanceof RoomDetailScreen detail) {
                detail.update(payload);
            }
        });
    }

    /** H 键入口：先请求一份快照，同时立刻打开界面（没数据时显示"读取中"）。 */
    public static void openLobby() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (ClientPlayNetworking.canSend(RoomActionPayload.TYPE)) {
            ClientPlayNetworking.send(RoomActionPayload.of("OPEN"));
        }
        client.setScreen(new RoomLobbyScreen());
    }

    /** 当前是否在自己房间的开局倒计时里（倒计时才允许选宝可梦/选落点）。 */
    public static boolean inCountdown() {
        RoomListPayload snapshot = latest;
        if (snapshot == null || snapshot.myRoomId() == 0) return false;
        for (RoomListPayload.RoomView room : snapshot.rooms()) {
            if (room.id() == snapshot.myRoomId()) return room.state() == 2;
        }
        return false;
    }

    public static int myRoomId() {
        RoomListPayload snapshot = latest;
        return snapshot == null ? 0 : snapshot.myRoomId();
    }

    private static final boolean[] COUNTDOWN_KEY_DOWN = new boolean[10];

    /** 倒计时期间数字键 1..9 选择队伍里的宝可梦（方案 §三.1）。 */
    public static void tickCountdownInput(Minecraft mc) {
        if (mc.getWindow() == null) return;
        boolean active = mc.screen == null && mc.player != null && inCountdown();
        long window = mc.getWindow().getWindow();
        java.util.List<String> team = ClientState.getTeam();
        for (int i = 1; i <= 9; i++) {
            boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_0 + i);
            if (down && !COUNTDOWN_KEY_DOWN[i] && active && i <= team.size()) {
                send("SELECT", myRoomId(), team.get(i - 1));
            }
            COUNTDOWN_KEY_DOWN[i] = down;
        }
    }

    public static void send(String action, int roomId, String arg) {
        if (ClientPlayNetworking.canSend(RoomActionPayload.TYPE)) {
            ClientPlayNetworking.send(RoomActionPayload.of(action, roomId, arg));
        }
    }
}

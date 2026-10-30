package com.pkmoba.duel;

import com.pkmoba.network.MenuOpenPayload;
import com.pkmoba.network.MenuOpenPayload.MenuKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 对战菜单的服务端入口：把客户端菜单点击翻译成对局操作。
 *
 * <p>所有会改变状态的判断都在服务端重做一遍（在线、权限、是否已在局内），
 * 客户端传来的名字只是"意图"，不是授权。
 */
public final class MenuActions {
    private MenuActions() {
    }

    /** 打开菜单：把在线玩家名单发过去（客户端只负责显示）。 */
    public static void open(ServerPlayer player, MenuKind kind) {
        if ((kind == MenuKind.ADMIN || kind == MenuKind.CHALLENGE_ALL) && !player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("只有管理员可以打开管理员单挑菜单。"));
            return;
        }
        if (DuelManager.inMatch(player)) {
            player.sendSystemMessage(Component.literal("你已经在对局中，结束之后才能发起挑战。"));
            return;
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            // 管理员菜单（强制开战 / 挑战界面）允许列出自己；普通玩家菜单不含自己
            if (other != player || kind != MenuKind.BATTLE) {
                names.add(other.getGameProfile().getName());
            }
        }
        List<String> arenas = ArenaStore.get(player.server).allArenas().stream()
                .filter(ArenaStore.Arena::complete).map(arena -> arena.name).toList();
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, MenuOpenPayload.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                    new MenuOpenPayload(kind, names, arenas));
        }
    }

    /** 处理客户端菜单点击。全部返回中文提示，避免客户端静默失败。 */
    public static void handle(ServerPlayer player, com.pkmoba.network.MenuActionPayload payload) {
        String action = payload.action();
        switch (action) {
            case "quick_bot" -> player.sendSystemMessage(Component.literal("请按 H 选择房间，加入后点击开始对战。"));
            case "CHALLENGE" -> {
                ServerPlayer target = find(player, payload.argA());
                if (target == null) {
                    player.sendSystemMessage(Component.literal("找不到在线玩家：" + payload.argA()));
                    return;
                }
                if (target == player) {
                    player.sendSystemMessage(Component.literal("不能挑战自己，请选择其他玩家。"));
                    return;
                }
                // 道具/界面发起的挑战走 10 秒弹窗；命令发起的仍是 30 秒聊天邀请
                boolean fromUi = payload.kind() == MenuKind.CHALLENGE_ALL || payload.kind() == MenuKind.BATTLE;
                // argB 是客户端选的场地名，空串表示不用场地（无界自由对局）
                String arena = payload.argB() == null || payload.argB().isBlank() ? null : payload.argB();
                DuelManager.challenge(player, target, arena, fromUi);
            }
            case "ADMIN" -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("只有管理员可以指定单挑双方。"));
                    return;
                }
                ServerPlayer a = find(player, payload.argA());
                ServerPlayer b = find(player, payload.argB());
                if (a == null || b == null) {
                    player.sendSystemMessage(Component.literal("指定的玩家有一方不在线。"));
                    return;
                }
                if (!DuelManager.adminStart(player, a, b)) {
                    player.sendSystemMessage(Component.literal("无法开始：双方必须不同且都不在其他对局中。"));
                }
            }
            default -> player.sendSystemMessage(Component.literal("未知的菜单操作：" + action));
        }
    }

    private static ServerPlayer find(ServerPlayer requester, String name) {
        if (name == null || name.isBlank()) return null;
        for (ServerPlayer other : requester.server.getPlayerList().getPlayers()) {
            if (other.getGameProfile().getName().equalsIgnoreCase(name)) return other;
        }
        return null;
    }
}

package com.pkmoba.godmode;

import com.pkmoba.lock.PlayerLockService;
import com.pkmoba.network.GodStatePayload;
import com.pkmoba.network.GodTogglePayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 服务端权威开关；锁定记录的保存、返回和清理只有这一条生命周期。 */
public final class ServerGodMode {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private ServerGodMode() { }
    public static void init() {
        PlayerLockService.init();
        ServerPlayNetworking.registerGlobalReceiver(GodTogglePayload.TYPE, (payload, context) ->
                context.server().execute(() -> toggle(context.player())));
        // 连接关闭时先恢复位置与属性，再由原版保存玩家，不能先删锁定记录。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forceOff(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ACTIVE.remove(handler.player.getUUID());
            PlayerLockService.recoverInactive(handler.player, false);
            sendState(handler.player, false);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                forceOffWithoutReturn(player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) forceOffWithoutReturn(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // 即使其他模组绕过死亡事件，也只恢复属性，不返回旧坐标。
            ACTIVE.remove(oldPlayer.getUUID());
            PlayerLockService.unlock(newPlayer, false);
            sendState(newPlayer, false);
        });
    }
    public static boolean isActive(Player player) { return ACTIVE.contains(player.getUUID()); }
    public static void toggle(ServerPlayer player) { setActive(player, !isActive(player)); }
    public static void setActive(ServerPlayer player, boolean active) {
        if (active) {
            if (isActive(player)) return;
            net.minecraft.world.phys.Vec3 entryAnchor = player.getEyePosition();
            // 0.38：本体是挂在受控宝可梦上方的动态锚点，不再要求头顶 12 格有安全空间。
            if (!player.isAlive() || !PlayerLockService.lock(player)) {
                player.displayClientMessage(Component.literal("当前状态无法进入上帝视角。"), false);
                sendState(player, false);
                return;
            }
            ACTIVE.add(player.getUUID());
            if (ServerPlayNetworking.canSend(player, GodStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, new GodStatePayload(true, entryAnchor.x, entryAnchor.y, entryAnchor.z));
            }
        } else {
            forceOff(player);
            PlayerLockService.recoverInactive(player, true);
        }
    }
    public static void forceOff(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        PlayerLockService.recoverInactive(player, false);
        sendState(player, false);
    }
    /** 死亡或跨维度时不向旧位置传送；只清理锁并还原进入前的属性。 */
    public static void forceOffWithoutReturn(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        PlayerLockService.unlock(player, false);
        sendState(player, false);
    }
    private static void sendState(ServerPlayer player, boolean active) {
        if (ServerPlayNetworking.canSend(player, GodStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, new GodStatePayload(active));
        }
    }
}

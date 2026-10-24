package com.pkmoba.move;

import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.network.PlaceOrderPayload;
import com.pkmoba.network.PlacementPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 选点召唤：{@code /mo summon} 只"上膛"，下一次右键点地才真正生成。
 *
 * <p>为什么这么设计：用户要求"用鼠标指定生成位置"。做成两步（输入指令 → 点地）比
 * "指令里带坐标"顺手，也不需要玩家手打坐标。
 */
public final class PlacementService {
    private static final Map<UUID, ResourceLocation> ARMED = new HashMap<>();

    private PlacementService() {
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(PlaceOrderPayload.TYPE, (payload, context) ->
                context.server().execute(() -> place(context.player(), payload.target())));

        // 断线清掉上膛状态，避免残留
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> disarm(handler.player));
    }

    public static boolean isArmed(ServerPlayer player) {
        return ARMED.containsKey(player.getUUID());
    }

    /** 进入选点状态，并通知客户端开始拦截右键。 */
    public static void arm(ServerPlayer player) {
        arm(player, CharizardManager.CHARIZARD_ID);
    }

    public static void arm(ServerPlayer player, ResourceLocation speciesId) {
        if (!CharizardManager.CHARIZARD_ID.equals(speciesId)
                && !CharizardManager.BLASTOISE_ID.equals(speciesId)) return;
        ARMED.put(player.getUUID(), speciesId);
        send(player, true);
    }

    public static void disarm(ServerPlayer player) {
        if (ARMED.remove(player.getUUID()) != null) {
            send(player, false);
        }
    }

    private static void send(ServerPlayer player, boolean armed) {
        if (ServerPlayNetworking.canSend(player, PlacementPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PlacementPayload(armed));
        }
    }

    private static void place(ServerPlayer player, BlockPos pos) {
        // 没上膛就无视，防止客户端乱发包凭空召唤
        ResourceLocation speciesId = ARMED.remove(player.getUUID());
        if (speciesId == null) {
            return;
        }
        send(player, false);

        ServerLevel level = player.serverLevel();
        Vec3 at = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);

        if (CharizardManager.getActive(player.getServer()) != null) {
            PkmobaMod.LOGGER.info("[pkmoba] 已有受控宝可梦，选点召唤被忽略");
            return;
        }

        // 兜底：召唤涉及 Cobblemon 的 Kotlin 代码与数据包，任何异常都不能让它掀翻服务端 tick。
        // 之前实测过：Cobblemon 缺失时这里会抛 ClassNotFound，直接把 server thread 打崩。
        net.minecraft.world.entity.Entity entity;
        try {
            entity = CharizardManager.spawn(level, at, player, speciesId);
        } catch (Throwable t) {
            PkmobaMod.LOGGER.error("[pkmoba] 选点召唤异常，已吞掉以免崩服", t);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("召唤失败：请查看日志中的 [pkmoba] 选点召唤异常。可重新执行 /mo summon charizard 或 /mo summon blastoise 再试。"));
            return;
        }
        if (entity == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("未能生成宝可梦：物种数据未就绪或生成被取消，请检查日志。"));
            return;
        }
        PkmobaMod.LOGGER.info("[pkmoba] 选点召唤成功 pos={}", entity.position());
    }

    /** 召唤成功后清理指令目标，避免新实体被旧目标带着跑。 */
    public static void resetMoveTarget() {
        MoveOrderService.clear();
    }
}

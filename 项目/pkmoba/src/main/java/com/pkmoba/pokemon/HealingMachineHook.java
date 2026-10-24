package com.pkmoba.pokemon;

import com.pkmoba.PkmobaMod;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * 用 Cobblemon 治疗机复活 pkmoba 的阵亡宝可梦。
 *
 * <p>Cobblemon 的治疗机只管自己队伍的 HP，不知道 pkmoba 的阵亡列表；
 * 这里在玩家右键治疗机时顺手把阵亡记录清掉。
 */
public final class HealingMachineHook {
    private HealingMachineHook() {
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            var state = level.getBlockState(hit.getBlockPos());
            var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!"cobblemon".equals(key.getNamespace()) || !"healing_machine".equals(key.getPath())) {
                return InteractionResult.PASS;
            }
            PlayerProfile profile = TeamService.profile(serverPlayer.server, serverPlayer.getUUID());
            if (profile.fainted.isEmpty()) {
                return InteractionResult.PASS;
            }
            TeamService.revive(serverPlayer);
            PkmobaMod.LOGGER.info("[pkmoba] {} 通过治疗机复活了宝可梦",
                    serverPlayer.getGameProfile().getName());
            serverPlayer.sendSystemMessage(Component.literal("治疗机同时复活了你倒下的宝可梦。"));
            return InteractionResult.PASS;
        });
    }
}

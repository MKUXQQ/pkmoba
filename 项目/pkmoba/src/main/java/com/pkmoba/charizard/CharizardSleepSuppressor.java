package com.pkmoba.charizard;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.PkmobaMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 阻止我们那只喷火龙夜间睡觉（用户选 P2-A + P2-C 双保险）。
 *
 * <p>背景（源码依据）：
 * <ul>
 *   <li>{@code DrowsySensor} 在夜间时段内按概率设置 {@code POKEMON_DROWSY} 记忆；</li>
 *   <li>{@code GoToSleepTask} 在犯困且位置可睡时设置 {@code POKEMON_SLEEPING}；</li>
 *   <li>{@code PokemonServerDelegate.updatePoseType} 里 sleeping → {@code PoseType.SLEEP}，
 *       这就是你夜里看到的睡觉动作。</li>
 * </ul>
 *
 * <p>所以做两层：
 * <ol>
 *   <li><b>A（根部）</b>：每 tick 清掉 {@code POKEMON_DROWSY}，让它永远不产生睡意；</li>
 *   <li><b>C（兜底）</b>：万一已经进入 {@code SLEEP} 姿态，直接改回 {@code STAND}。</li>
 * </ol>
 *
 * <p>只处理被操控的这一只，世界里其他宝可梦照常睡觉；其他表现（走路、转头、甩尾、
 * 站着时的呼吸等原生动画）完全不动，符合"只要不睡觉，其它照常"的要求。
 */
public final class CharizardSleepSuppressor {
    private CharizardSleepSuppressor() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(CharizardSleepSuppressor::tick);
    }

    private static void tick(MinecraftServer server) {
        PokemonEntity entity = CharizardManager.getActive(server);
        if (entity == null) {
            return;
        }
        try {
            // A：清掉睡意。用 try 包住是因为 Brain/记忆系统跨版本可能变，不能让它掀翻 tick。
            entity.getBrain().eraseMemory(CobblemonMemories.POKEMON_DROWSY);

            // C：兜底改姿态。只会命中"已经睡着"的那一帧，平时完全不插手。
            if (entity.getCurrentPoseType() == PoseType.SLEEP) {
                entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), PoseType.STAND);
            }
        } catch (Throwable t) {
            PkmobaMod.LOGGER.warn("[pkmoba] 抑制睡眠时出错（已忽略）：{}", t.toString());
        }
    }
}

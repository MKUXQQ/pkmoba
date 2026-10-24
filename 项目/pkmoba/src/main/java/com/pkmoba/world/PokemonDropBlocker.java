package com.pkmoba.world;

import com.cobblemon.mod.common.world.gamerules.CobblemonGameRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 关闭宝可梦死亡后的全部掉落。
 *
 * <p>Cobblemon 的物品掉落只走 {@code PokemonServerDelegate.doDeathDrops()}：
 * 它先把宝可梦手上的持有物 {@code spawnAtLocation}，再按种族掉落表
 * {@code entity.pokemon.form.drops} 生成物品，而整段逻辑被游戏规则
 * {@code doPokemonLoot}（{@link CobblemonGameRules#DO_POKEMON_LOOT}）挡住。
 * 本项目召唤的宝可梦既没有 {@code ownerUUID} 也没有 {@code owner}，
 * 因此判定为"野生"会正常掉落，于是这里在服务端启动时把该规则强制关掉。
 *
 * <p>顺带说明：{@code PokemonEntity.dropExperience} 同样读这条规则，
 * 因此宝可梦死亡也不会再掉经验球；原版 {@code super.dropAllDeathLoot}
 * 依赖实体掉落表，而 Cobblemon 并没有给宝可梦实体注册掉落表，所以没有额外物品来源。
 */
public final class PokemonDropBlocker {

    private static final Logger LOGGER = LoggerFactory.getLogger("pkmoba");

    private PokemonDropBlocker() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(PokemonDropBlocker::apply);
    }

    private static void apply(MinecraftServer server) {
        try {
            GameRules rules = server.overworld().getGameRules();
            GameRules.BooleanValue rule = rules.getRule(CobblemonGameRules.DO_POKEMON_LOOT);
            if (rule.get()) {
                rule.set(false, server);
                LOGGER.info("[pkmoba] 已关闭宝可梦死亡掉落（doPokemonLoot=false）。");
            }
        } catch (Throwable t) {
            LOGGER.warn("[pkmoba] 关闭宝可梦死亡掉落失败", t);
        }
    }
}

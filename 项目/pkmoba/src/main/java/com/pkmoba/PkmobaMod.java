package com.pkmoba;

import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.command.MoCommand;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.move.PlacementService;
import com.pkmoba.network.CharizardSyncPayload;
import com.pkmoba.network.GodStatePayload;
import com.pkmoba.network.GodTogglePayload;
import com.pkmoba.network.MoveOrderPayload;
import com.pkmoba.network.PlaceOrderPayload;
import com.pkmoba.network.PlacementPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口（物理服务端与逻辑服务端都会加载）。
 *
 * <p>初始化顺序有意为之：
 * <ol>
 *   <li>先注册网络包类型 —— 接收器/发送方都可能在其他初始化回调里立刻用到；</li>
 *   <li>再初始化服务端状态机（上帝视角、移动指令、选点召唤）；</li>
 *   <li>最后注册指令，指令执行时依赖上面的状态机。</li>
 * </ol>
 */
public class PkmobaMod implements ModInitializer {
    public static final String MOD_ID = "pkmoba";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // C2S
        PayloadTypeRegistry.playC2S().register(GodTogglePayload.TYPE, GodTogglePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MoveOrderPayload.TYPE, MoveOrderPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaceOrderPayload.TYPE, PlaceOrderPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.SkillCastPayload.TYPE, com.pkmoba.network.SkillCastPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.AimPayload.TYPE, com.pkmoba.network.AimPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.BasicAttackPayload.TYPE, com.pkmoba.network.BasicAttackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.MenuActionPayload.TYPE, com.pkmoba.network.MenuActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.StarterChoosePayload.TYPE, com.pkmoba.network.StarterChoosePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.SwitchPokemonPayload.TYPE, com.pkmoba.network.SwitchPokemonPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.SummonPayload.TYPE, com.pkmoba.network.SummonPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.pkmoba.network.ChallengeActionPayload.TYPE, com.pkmoba.network.ChallengeActionPayload.CODEC);
        // S2C
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.CameraSettingsPayload.TYPE, com.pkmoba.network.CameraSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GodStatePayload.TYPE, GodStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CharizardSyncPayload.TYPE, CharizardSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlacementPayload.TYPE, PlacementPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.MoveAckPayload.TYPE, com.pkmoba.network.MoveAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.SkillStatePayload.TYPE, com.pkmoba.network.SkillStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.EffectPayload.TYPE, com.pkmoba.network.EffectPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.MultiEffectPayload.TYPE, com.pkmoba.network.MultiEffectPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.VanillaParticlePayload.TYPE, com.pkmoba.network.VanillaParticlePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.FlamePayload.TYPE, com.pkmoba.network.FlamePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.JetPayload.TYPE, com.pkmoba.network.JetPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.GlyphPayload.TYPE, com.pkmoba.network.GlyphPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.WaterEffectPayload.TYPE, com.pkmoba.network.WaterEffectPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.TornadoPayload.TYPE, com.pkmoba.network.TornadoPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.MenuOpenPayload.TYPE, com.pkmoba.network.MenuOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.DuelStatePayload.TYPE, com.pkmoba.network.DuelStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.ChallengePopupPayload.TYPE, com.pkmoba.network.ChallengePopupPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.StarterListPayload.TYPE, com.pkmoba.network.StarterListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.TeamStatePayload.TYPE, com.pkmoba.network.TeamStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.FaintPayload.TYPE, com.pkmoba.network.FaintPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.pkmoba.network.MatchResultPayload.TYPE, com.pkmoba.network.MatchResultPayload.CODEC);
        // 自定义粒子类型（火焰团）
        com.pkmoba.particle.PkmobaParticles.register();

        ServerGodMode.init();
        MoveOrderService.init();
        PlacementService.init();
        // 阻止夜间睡觉（用户选 P2-A + P2-C 双保险）
        com.pkmoba.charizard.CharizardSleepSuppressor.init();
        // 技能系统（技能点 / 冷却 / 四技能判定）
        com.pkmoba.combat.CharizardCombat.init();
        com.pkmoba.combat.BasicAttack.init();
        // 0.35：关闭世界自然生成（只拦截自然生成，玩家主动召唤不受影响）
        com.pkmoba.world.NaturalSpawnBlocker.init();
        com.pkmoba.world.PokemonDropBlocker.init();
        com.pkmoba.item.ModeItems.init();
        com.pkmoba.pokemon.HealingMachineHook.init();
        com.pkmoba.duel.DuelManager.init();

        // 对战菜单点击：服务端重新校验在线/权限/是否已在局内
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.pkmoba.network.MenuActionPayload.TYPE, (payload, context) ->
                        context.server().execute(() ->
                                com.pkmoba.duel.MenuActions.handle(context.player(), payload)));

        // 开局/更换宝可梦选择
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.pkmoba.network.StarterChoosePayload.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            switch (payload.mode() == null ? "" : payload.mode()) {
                                case "ADD" -> com.pkmoba.pokemon.TeamService.addSpecies(
                                        context.player(), payload.species());
                                case "DELETE" -> com.pkmoba.pokemon.TeamService.deleteFromPicker(
                                        context.player(), payload.species());
                                default -> com.pkmoba.pokemon.TeamService.choose(
                                        context.player(), payload.species());
                            }
                        }));

        // X 键：把宝可梦召唤到准星指向的地面点
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.pkmoba.network.SummonPayload.TYPE, (payload, context) ->
                        context.server().execute(() ->
                                com.pkmoba.pokemon.TeamService.summonAt(context.player(),
                                        new net.minecraft.world.phys.Vec3(payload.x(), payload.y(), payload.z()))));

        // 挑战弹窗的接受/拒绝：服务端复核 requestId、过期时间、双方状态后才真正开战
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.pkmoba.network.ChallengeActionPayload.TYPE, (payload, context) ->
                        context.server().execute(() ->
                                com.pkmoba.duel.DuelManager.handleChallengeAction(context.player(), payload)));

        // 玩家进服时同步一次当前喷火龙，避免客户端 HUD/相机不知道实体 id
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    CharizardManager.broadcastSync(server);
                    // 没有选择过宝可梦：自动弹开局选择界面
                    com.pkmoba.pokemon.PlayerProfile profile =
                            com.pkmoba.pokemon.ProfileStore.get(server).profile(handler.getPlayer().getUUID());
                    if (!profile.hasStarter()) {
                        com.pkmoba.pokemon.TeamService.sendStarterList(handler.getPlayer());
                    }
                    // 已选过宝可梦：先把队伍状态下发，HUD 才能立刻显示"我选的是哪只"
                    com.pkmoba.pokemon.TeamService.sendTeamState(handler.getPlayer());
                }));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MoCommand.register(dispatcher));

        LOGGER.info("[pkmoba] init (common) done");
    }
}

package com.pkmoba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.duel.ArenaStore;
import com.pkmoba.duel.DuelManager;
import com.pkmoba.move.PlacementService;
import com.pkmoba.pokemon.ProfileStore;
import com.pkmoba.pokemon.SpeciesRegistry;
import com.pkmoba.pokemon.TeamService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /mo} 指令族（0.31 精简版）。
 *
 * <p>已删除：{@code /mo team}（HUD 自带队伍显示）、{@code /mo camera}（回归默认镜头）、
 * {@code /mo god}（用 Z 键）、{@code /mo items}（创造模式物品栏可拿）。
 */
public final class MoCommand {
    private MoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mo")
                .then(Commands.literal("summon").executes(MoCommand::summonOwn)
                        .then(Commands.argument("pokemon", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        shortNames(), builder))
                                .executes(MoCommand::summonNamed)))
                .then(Commands.literal("pokemon")
                        .executes(MoCommand::pokemonMenu)
                        .then(Commands.literal("add")
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(shortNames(), builder))
                                        .executes(MoCommand::pokemonAdd)))
                        .then(Commands.literal("list").executes(MoCommand::pokemonList))
                        .then(Commands.literal("remove")
                                .executes(MoCommand::pokemonRemove)
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        // 只补全"自己实际拥有的"宝可梦，外加 all；
                                        // 不把 all 做成 literal 兄弟节点，否则 Brigadier 只会补全 literal。
                                        .suggests((context, builder) -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            List<String> owned = new ArrayList<>();
                                            if (player != null) {
                                                for (String species : TeamService.teamOf(player)) {
                                                    ResourceLocation id = ResourceLocation.tryParse(species);
                                                    if (id != null) owned.add(id.getPath());
                                                }
                                            }
                                            owned.add("all");
                                            return SharedSuggestionProvider.suggest(owned, builder);
                                        })
                                        .then(Commands.literal("confirm").executes(MoCommand::removeAllConfirm))
                                        .executes(MoCommand::removeSpecies))))
                .then(Commands.literal("revive").executes(MoCommand::revive))
                .then(Commands.literal("arena")
                        .then(Commands.literal("list").executes(MoCommand::arenaList))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(MoCommand::arenaCreate)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            List<String> names = new ArrayList<>();
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player != null) names.addAll(ArenaStore.get(player.server).names());
                                            return SharedSuggestionProvider.suggest(names, builder);
                                        })
                                        .executes(MoCommand::arenaDelete))))
                .then(duelCommand()));
    }

    private static List<String> shortNames() {
        List<String> names = new ArrayList<>();
        for (var entry : SpeciesRegistry.all()) names.add(entry.id().getPath());
        return names;
    }

    /** {@code /mo pokemon}：打开选择界面。 */
    private static int pokemonMenu(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        TeamService.openPicker(player);
        return 1;
    }

    /** {@code /mo pokemon add <物种>}：往队伍里加一只。 */
    private static int pokemonAdd(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        return TeamService.addSpecies(player, StringArgumentType.getString(context, "pokemon")) ? 1 : 0;
    }

    /** {@code /mo pokemon list}：列出自己的队伍。 */
    private static int pokemonList(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        TeamService.listTeam(player);
        return 1;
    }

    /** {@code /mo revive}：复活全部宝可梦。 */
    private static int revive(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        TeamService.revive(player);
        return 1;
    }

    private static int arenaList(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        var names = ArenaStore.get(player.server).names();
        if (names.isEmpty()) {
            player.sendSystemMessage(Component.literal("还没有保存过场地。用法：先用两个点位放置器记录 4 个点，再 /mo arena create <名字>"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("已有场地：" + String.join("、", names)));
        return 1;
    }

    private static int arenaCreate(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        ArenaStore store = ArenaStore.get(player.server);
        var draft = ArenaStore.draft(player.getUUID());
        if (!draft.complete()) {
            player.sendSystemMessage(Component.literal(
                    "点位还没设全（需要玩家红/蓝两点、宝可梦红/蓝两点）。用「玩家点位放置器」和「宝可梦点位放置器」右键方块：蹲下=红，不蹲=蓝。"));
            return 0;
        }
        ArenaStore.Arena arena = new ArenaStore.Arena(name);
        arena.redPlayer = draft.redPlayer;
        arena.bluePlayer = draft.bluePlayer;
        arena.redPokemon = draft.redPokemon;
        arena.bluePokemon = draft.bluePokemon;
        arena.dimension = draft.dimension;
        store.put(arena);
        ArenaStore.clearDraft(player.getUUID());
        player.sendSystemMessage(Component.literal("场地「" + name + "」已保存。开打：/mo duel <玩家> " + name));
        return 1;
    }

    private static int arenaDelete(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        if (ArenaStore.get(player.server).remove(name)) {
            player.sendSystemMessage(Component.literal("场地「" + name + "」已删除。"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("找不到场地：" + name));
        return 0;
    }

    /**
     * {@code /mo duel <玩家> [场地]} 发起 1v1；{@code /mo duel consent} 接受邀请；
     * {@code /mo duel stop <场地|all>} 管理员强制结束。
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> duelCommand() {
        return Commands.literal("duel")
                .then(Commands.literal("consent").executes(c -> {
                    ServerPlayer player = c.getSource().getPlayer();
                    return player != null && DuelManager.consent(player) ? 1 : 0;
                }))
                .then(Commands.literal("stop")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    List<String> names = new ArrayList<>();
                                    names.add("all");
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player != null) names.addAll(ArenaStore.get(player.server).names());
                                    return SharedSuggestionProvider.suggest(names, builder);
                                })
                                .executes(c -> {
                                    ServerPlayer player = c.getSource().getPlayer();
                                    if (player == null) {
                                        c.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
                                        return 0;
                                    }
                                    DuelManager.stop(player, StringArgumentType.getString(c, "target"));
                                    return 1;
                                })))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getServer().getPlayerList().getPlayers().stream()
                                        .map(p -> p.getGameProfile().getName()).toList(), builder))
                        .executes(c -> duel(c, null))
                        .then(Commands.argument("arena", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    List<String> names = new ArrayList<>();
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player != null) names.addAll(ArenaStore.get(player.server).names());
                                    return SharedSuggestionProvider.suggest(names, builder);
                                })
                                .executes(c -> duel(c, StringArgumentType.getString(c, "arena")))));
    }

    private static int duel(CommandContext<CommandSourceStack> c, String arenaName) {
        ServerPlayer from = c.getSource().getPlayer();
        if (from == null) {
            c.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String name = StringArgumentType.getString(c, "player");
        ServerPlayer target = c.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            from.sendSystemMessage(Component.literal("找不到在线玩家：" + name));
            return 0;
        }
        if (!DuelManager.challenge(from, target, arenaName)) {
            from.sendSystemMessage(Component.literal("无法发起邀请：你或对方已在其他对局中。"));
            return 0;
        }
        return 1;
    }

    /** /mo summon：进入选点模式，下一次右键点地才真正生成；不写物种则用玩家已选的宝可梦。 */
    private static int summonOwn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("这个指令只能由玩家执行（需要鼠标选点）。"));
            return 0;
        }
        var profile = ProfileStore.get(player.server).profile(player.getUUID());
        String species = profile.activeSpecies();
        if (species == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "你还没有选择宝可梦，先输入 /mo pokemon 选择（或右键「宝可梦选择器」）。"));
            return 0;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(species);
        if (id == null) return 0;
        return summon(ctx, id);
    }

    private static int summonNamed(CommandContext<CommandSourceStack> ctx) {
        String species = StringArgumentType.getString(ctx, "pokemon");
        net.minecraft.resources.ResourceLocation id = SpeciesRegistry.parse(species);
        if (id == null) {
            ctx.getSource().sendFailure(Component.literal("尚未适配此宝可梦，可用：" + String.join("、", shortNames())));
            return 0;
        }
        return summon(ctx, id);
    }

    private static int summon(CommandContext<CommandSourceStack> ctx,
                              net.minecraft.resources.ResourceLocation speciesId) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令只能由玩家执行（需要鼠标选点）。"));
            return 0;
        }
        if (CharizardManager.getControlled(player) != null) {
            source.sendFailure(Component.literal("你已经有一只宝可梦在场了，先按 C 收回再重新选点。"));
            return 0;
        }
        PlacementService.arm(player, speciesId);
        String name = SpeciesRegistry.displayName(speciesId);
        source.sendSuccess(() -> Component.literal("选点模式：右键点击地面，" + name + "会生成在那里。"), false);
        return 1;
    }

    /**
     * {@code /mo pokemon remove <宝可梦>}：从自己的队伍里删掉这只（只影响自己）。
     *
     * <p>参数写 {@code all} 时转去走二次确认流程，方便补全里直接选 all。
     */
    private static int removeSpecies(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String target = StringArgumentType.getString(ctx, "pokemon");
        if ("all".equalsIgnoreCase(target)) {
            return TeamService.requestRemoveAll(player) ? 1 : 0;
        }
        return TeamService.removeSpecies(player, target) ? 1 : 0;
    }

    /** {@code /mo pokemon remove}：无参时给用法提示。 */
    private static int pokemonRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(
                "用法：/mo pokemon remove <宝可梦> 删除自己拥有的一只；"
                        + "/mo pokemon remove all 删除全部（需二次确认）。"), false);
        return 1;
    }

    /** {@code /mo pokemon remove all confirm}：确认删除全部宝可梦。 */
    private static int removeAllConfirm(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        return TeamService.confirmRemoveAll(player) ? 1 : 0;
    }
}

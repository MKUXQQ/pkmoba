package com.pkmoba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pkmoba.duel.ArenaStore;
import com.pkmoba.item.ModeItems;
import com.pkmoba.move.PlacementService;
import com.pkmoba.pokemon.ProfileStore;
import com.pkmoba.pokemon.SpeciesRegistry;
import com.pkmoba.pokemon.TeamService;
import com.pkmoba.world.HazardService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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
                                // m04660：允许名字里带空格（历史点位组叫「房间组 1」），
                                // 用 greedyString 把后面的全部当成一个名字，避免被拆成多个参数报错。
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            List<String> names = new ArrayList<>();
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player != null) names.addAll(ArenaStore.get(player.server).names());
                                            return SharedSuggestionProvider.suggest(names, builder);
                                        })
                                        .executes(MoCommand::arenaDelete))))
                .then(hazardCommand()));
    }

    /** {@code /mo hazard}：0.38 场地陷阱调试入口（高速旋转 W 会清掉身边的钉子）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> hazardCommand() {
        return Commands.literal("hazard")
                .then(Commands.literal("add")
                        .executes(context -> hazardAdd(context, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> hazardAdd(context,
                                        IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("count").executes(MoCommand::hazardCount))
                .then(Commands.literal("clear").executes(MoCommand::hazardClear));
    }

    private static int hazardAdd(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        Vec3 pos = source.getPosition();
        HazardService.add(pos, count);
        source.sendSuccess(() -> Component.literal("已放置 " + count + " 个陷阱（当前共 "
                + HazardService.count() + " 个）。"), false);
        return 1;
    }

    private static int hazardCount(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("当前陷阱数："
                + HazardService.count()), false);
        return 1;
    }

    private static int hazardClear(CommandContext<CommandSourceStack> context) {
        int before = HazardService.count();
        HazardService.clearAll();
        context.getSource().sendSuccess(() -> Component.literal("已清空 " + before + " 个陷阱。"), false);
        return 1;
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

    /** {@code /mo arena show <名字>}：查看红蓝玩家点 / 边界是否配齐。 */
    private static int arenaShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        ArenaStore.Arena arena = ArenaStore.get(player.server).byName(name);
        if (arena == null) {
            source.sendFailure(Component.literal("找不到点位组「" + name + "」。用 /mo arena list 看现有组名。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("———— 点位组「" + arena.name + "」————"), false);
        source.sendSuccess(() -> Component.literal("红方玩家点：" + fmtPoint(arena.redPlayer)), false);
        source.sendSuccess(() -> Component.literal("蓝方玩家点：" + fmtPoint(arena.bluePlayer)), false);
        source.sendSuccess(() -> Component.literal("边界点数：" + arena.boundary.size()
                + (arena.hasBoundary() ? "（已生效）" : "（不足 4 点，未生效）")), false);
        source.sendSuccess(() -> Component.literal("换宠区顶点：红 " + arena.redSwitchPolygon.size() + "/4 · 蓝 " + arena.blueSwitchPolygon.size() + "/4"), false);
        String verdict = arena.complete()
                ? "可开房对战" : "未就绪：需要四点边界和红蓝四点换宠区，双方玩家点须在己方区内";
        source.sendSuccess(() -> Component.literal("状态：" + verdict), false);
        return 1;
    }

    /** {@code /mo arena marker <player|pokemon|boundary>}：直接发一个点位放置器。 */
    private static int arenaGiveMarker(CommandContext<CommandSourceStack> context, int kind) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        net.minecraft.world.item.Item item = switch (kind) {
            case 0 -> ModeItems.PLAYER_MARKER;
            case 1 -> ModeItems.POKEMON_MARKER;
            default -> ModeItems.BOUNDARY_MARKER;
        };
        if (!player.getInventory().add(new ItemStack(item))) {
            player.drop(new ItemStack(item), false);
        }
        String label = switch (kind) {
            case 0 -> "玩家点位放置器";
            case 1 -> "宝可梦点位放置器";
            default -> "边界画笔";
        };
        source.sendSuccess(() -> Component.literal("已发放：" + label), false);
        return 1;
    }

    /** {@code /mo arena items}：一次发齐两个布点工具（道具是直观的做法，指令只是省事）。 */
    private static int arenaGivePlacers(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        for (net.minecraft.world.item.Item item : new net.minecraft.world.item.Item[]{
                ModeItems.PLAYER_MARKER,
                ModeItems.BOUNDARY_MARKER,
                ModeItems.SWITCH_MARKER}) {
            if (!player.getInventory().add(new ItemStack(item))) {
                player.drop(new ItemStack(item), false);
            }
        }
        source.sendSuccess(() -> Component.literal(
                "已发放 3 个布点工具：玩家点 / 边界画笔 / 换宠区。"
                        + "都用右键方块记点，蹲下 = 红方，站立 = 蓝方。"), false);
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
            player.sendSystemMessage(Component.literal("还没有保存过场地。用法：先画 4 点边界，再记录红蓝玩家点；就绪后自动生成房间"));
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
        return saveDraftArena(player, name);
    }

    /** 把当前草稿保存成点位组（`create` 与 `create_auto` 共用）。 */
    private static int saveDraftArena(ServerPlayer player, String name) {
        ArenaStore store = ArenaStore.get(player.server);
        var draft = ArenaStore.draft(player.getUUID());
        if (!draft.complete()) {
            player.sendSystemMessage(Component.literal(
                    "场地未就绪：需要有效的 4 点边界，以及边界内的红蓝玩家点（蹲下=红，不蹲=蓝）。"));
            return 0;
        }
        ArenaStore.Arena arena = new ArenaStore.Arena(name);
        arena.redPlayer = draft.redPlayer;
        arena.bluePlayer = draft.bluePlayer;
        arena.redPokemon = draft.redPlayer;
        arena.bluePokemon = draft.bluePlayer;
        arena.boundary.addAll(draft.boundary);
        arena.dimension = draft.dimension;
        arena.redSwitch = draft.redSwitch;
        arena.blueSwitch = draft.blueSwitch;
        arena.switchRadius = draft.switchRadius;
        arena.redSwitchPolygon.addAll(draft.redSwitchPolygon);
        arena.blueSwitchPolygon.addAll(draft.blueSwitchPolygon);
        store.put(arena);
        com.pkmoba.duel.RoomManager.create(player, arena.name);
        ArenaStore.clearDraft(player.getUUID());
        player.sendSystemMessage(Component.literal("点位组「" + name + "」已保存（边界 "
                + arena.boundary.size() + " 点）。按 H 加入房间。"));
        return 1;
    }

    /** 名字下拉：列出已有点位组。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestArenas(CommandContext<CommandSourceStack> context,
                  com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            builder.suggest("all");
            for (String name : ArenaStore.get(player.server).names()) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    /** {@code /mo arena create_auto}：名字自动取「房间组 N」。 */
    private static int arenaCreateAuto(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        return saveDraftArena(player, ArenaStore.get(player.server).nextGroupName());
    }

    /** {@code /mo arena boundary <名字> add|undo|clear|list}：站着点一下就把当前位置加进边界多边形。 */
    private static int arenaBoundary(CommandContext<CommandSourceStack> context, int action) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        String name = StringArgumentType.getString(context, "name");
        ArenaStore.Arena arena = resolveArena(player, name);
        if (arena == null) return 0;
        if (action == 0) {
            arena.boundary.add(player.position());
            ArenaStore.get(player.server).put(arena);
            player.sendSystemMessage(Component.literal("已加入边界第 " + arena.boundary.size()
                    + " 个点。至少 4 个点后生效，用 /mo arena boundary " + arena.name + " list 查看。"));
        } else if (action == 1) {
            if (arena.boundary.isEmpty()) {
                player.sendSystemMessage(Component.literal("边界还是空的。"));
                return 0;
            }
            Vec3 removed = arena.boundary.remove(arena.boundary.size() - 1);
            ArenaStore.get(player.server).put(arena);
            player.sendSystemMessage(Component.literal("已撤销最后一个边界点（"
                    + fmtPoint(removed) + "），剩余 " + arena.boundary.size() + " 个。"));
        } else if (action == 2) {
            arena.boundary.clear();
            ArenaStore.get(player.server).put(arena);
            player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」边界已清空，不再限制范围。"));
        } else {
            if (!arena.hasBoundary()) {
                player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」还没有有效边界（需要 ≥4 点）。"));
                return 0;
            }
            StringBuilder sb = new StringBuilder("边界点：");
            for (int i = 0; i < arena.boundary.size(); i++) {
                sb.append(i + 1).append('=').append(fmtPoint(arena.boundary.get(i))).append(' ');
            }
            player.sendSystemMessage(Component.literal(sb.toString()));
        }
        return 1;
    }

    private static String fmtPoint(Vec3 p) {
        return p == null ? "未配置" : String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
    }

    private static ArenaStore.Arena resolveArena(ServerPlayer player, String name) {
        ArenaStore store = ArenaStore.get(player.server);
        ArenaStore.Arena arena = store.byName(name);
        if (arena == null) {
            player.sendSystemMessage(Component.literal("找不到点位组：" + name
                    + "（已有：" + String.join("、", store.names()) + "）"));
        }
        return arena;
    }

    private static int arenaDelete(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令只能由玩家执行。"));
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        if (ArenaStore.get(player.server).remove(name)) {
            // m05326：删组 = 连这一组的房间一起删掉，里面所有点位（边界/点位）都没了。
            com.pkmoba.duel.RoomManager.deleteByArena(name);
            player.sendSystemMessage(Component.literal("点位组「" + name + "」及其房间、边界、全部点位已删除。"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("找不到场地：" + name));
        return 0;
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

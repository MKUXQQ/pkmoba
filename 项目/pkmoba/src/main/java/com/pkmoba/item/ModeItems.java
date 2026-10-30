package com.pkmoba.item;

import com.pkmoba.PkmobaMod;
import com.pkmoba.duel.ArenaStore;
import com.pkmoba.pokemon.TeamService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Registry;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.core.registries.BuiltInRegistries;

/** Right-click entry items for the duel modes, species selection and arena points. */
public final class ModeItems {
    /** 宝可梦选择器：右键打开选择界面（也用于更换）。 */
    public static final Item SPECIES_SWITCH = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "species_switch"),
            new Item(new Item.Properties().stacksTo(1)));
    /** 玩家点位放置器：右键方块记录红/蓝玩家点。 */
    public static final Item PLAYER_MARKER = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "player_marker"),
            new Item(new Item.Properties().stacksTo(1)));
    /** 宝可梦点位放置器：右键方块记录红/蓝宝可梦点。 */
    public static final Item POKEMON_MARKER = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "pokemon_marker"),
            new Item(new Item.Properties().stacksTo(1)));

    /** 边界画笔：蹲下右键给当前点位组加边界点（方案 §四.4）。 */
    public static final Item BOUNDARY_MARKER = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "boundary_marker"),
            new Item(new Item.Properties().stacksTo(1)));

    public static final Item SWITCH_MARKER = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "switch_marker"),
            new Item(new Item.Properties().stacksTo(1)));

    /** 创造模式物品栏标签页：没有注册这个分组，物品就只存在于注册表里，物品栏里永远看不到。 */
    public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "main"),
            CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, 7)
                    .title(Component.translatable("itemGroup.pkmoba"))
                    .icon(() -> new ItemStack(PLAYER_MARKER))
                    .displayItems((params, output) -> {
                        output.accept(PLAYER_MARKER);
                        // m05418：宝可梦放置点已删除
                        output.accept(BOUNDARY_MARKER);
                        output.accept(SWITCH_MARKER);
                    })
                    .build());

    private ModeItems() {}

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(PLAYER_MARKER);
            entries.accept(POKEMON_MARKER);
            entries.accept(BOUNDARY_MARKER);
            entries.accept(SWITCH_MARKER);
        });
        // 工具粒子提示（每 5 tick 一次，避免粒子刷屏）
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 5 == 0) {
                tickHints(server);
            }
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResultHolder.pass(stack);
            }
            if (stack.is(SWITCH_MARKER)) {
                ArenaStore.Arena draft = loadEditingArena(serverPlayer);
                boolean red = player.isShiftKeyDown();
                if (!draft.hasBoundary()) {
                    serverPlayer.displayClientMessage(Component.literal("附近没有可编辑场地；请先持边界画笔补齐场地边界，或站在已有场地内。"), true);
                    return InteractionResultHolder.success(stack);
                }
                serverPlayer.displayClientMessage(Component.literal("对方块依次右键四个顶点设置蓝方换宠区；按住潜行设置红方。对空气使用不会清除区域。"), true);
                return InteractionResultHolder.success(stack);
            }
            if (stack.is(SPECIES_SWITCH)) {
                serverPlayer.sendSystemMessage(Component.literal("所有已实装宝可梦已自动解锁，按 C 管理队伍；按 H 配置本场阵容。"));
                return InteractionResultHolder.success(stack);
            }
            return InteractionResultHolder.pass(stack);
        });
        // 点位放置器需要右键方块：蹲下 = 红点，不蹲 = 蓝点
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide) {
                return stack.is(SWITCH_MARKER) || stack.is(BOUNDARY_MARKER) || stack.is(PLAYER_MARKER)
                        || stack.is(POKEMON_MARKER) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (stack.is(BOUNDARY_MARKER)) {
                // m05263：一个组只允许 4 个边界点（画满就自动成组，不再继续加点）。
                ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
                if (draft.boundary.size() >= ArenaStore.BOUNDARY_MIN_POINTS) {
                    // m05326：允许多个组 —— 这一组的 4 点已经齐了，再点画笔就开一组新的。
                    ArenaStore.clearDraft(player.getUUID());
                    draft = ArenaStore.draft(player.getUUID());
                    serverPlayer.displayClientMessage(Component.literal(
                            "已开始新的一组（上一组已保存）"), true);
                }
                draft.dimension = level.dimension().location();
                Vec3 point = hit.getBlockPos().above().getCenter();
                draft.boundary.add(point);
                int need = ArenaStore.BOUNDARY_MIN_POINTS;
                int have = draft.boundary.size();
                // 边界点只走行动栏，不刷聊天（用户 m04462："不要聊天栏里显示那些东西"）。
                serverPlayer.displayClientMessage(Component.literal("边界第 " + have + " 点"
                        + (have >= need ? "（4 点已齐，点位组已建立，接着放红蓝玩家点）"
                        : "（还需 " + (need - have) + " 点）")), true);
                autoGroup(serverPlayer);
                return InteractionResult.SUCCESS;
            }
            if (stack.is(SWITCH_MARKER)) {
                Vec3 point = hit.getBlockPos().above().getCenter();
                ArenaStore.Arena draft = loadEditingArena(serverPlayer);
                if (outsideArenaBoundary(serverPlayer, draft, point)) {
                    serverPlayer.displayClientMessage(Component.literal("请先画好四点边界，换宠区四个顶点必须放在这间房的边界内。"), true);
                    return InteractionResult.SUCCESS;
                }
                boolean red = player.isShiftKeyDown();
                java.util.List<Vec3> polygon = red ? draft.redSwitchPolygon : draft.blueSwitchPolygon;
                if (polygon.size() >= 4) {
                    polygon.clear();
                    if (red) draft.redSwitch = null;
                    else draft.blueSwitch = null;
                }
                polygon.add(point);
                if (red) draft.redSwitch = point;
                else draft.blueSwitch = point;
                int count = polygon.size();
                if (count == 4) {
                    ArenaStore.Arena shape = new ArenaStore.Arena("switch-zone-check");
                    shape.boundary.addAll(polygon);
                    if (!shape.hasBoundary()) {
                        polygon.clear();
                        if (red) draft.redSwitch = null;
                        else draft.blueSwitch = null;
                        serverPlayer.displayClientMessage(Component.literal("换宠区四点无效（重叠或面积过小），请重新设置该方区域"), true);
                        autoGroup(serverPlayer);
                        return InteractionResult.SUCCESS;
                    }
                }
                autoGroup(serverPlayer);
                serverPlayer.displayClientMessage(Component.literal((red ? "红" : "蓝") + "方换宠区顶点 "
                        + count + "/4" + (count == 4 ? " 已闭合" : "；未满四点时该方换宠区不可用")), true);
                return InteractionResult.SUCCESS;
            }
            if (stack.is(POKEMON_MARKER)) {
                // m05418：宝可梦放置点已删除，出场点直接用玩家点。
                serverPlayer.displayClientMessage(Component.literal(
                        "宝可梦放置点已删除：现在直接用「玩家点位放置器」记出场点"), true);
                return InteractionResult.SUCCESS;
            }
            if (!stack.is(PLAYER_MARKER)) return InteractionResult.PASS;
            boolean red = player.isShiftKeyDown();
            Vec3 point = hit.getBlockPos().above().getCenter();
            ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
            if (outsideBoundary(serverPlayer, draft, point)) {
                serverPlayer.displayClientMessage(Component.literal(
                        "点位只能记在边界之内，先把边界画好"), true);
                return InteractionResult.SUCCESS;
            }
            draft.dimension = level.dimension().location();
            if (red) draft.redPlayer = point;
            else draft.bluePlayer = point;
            String label = "玩家" + (red ? "红" : "蓝") + "点";
            // 只走行动栏，聊天栏保持干净。
            serverPlayer.displayClientMessage(Component.literal("已记录 " + label), true);
            autoGroup(serverPlayer);
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * m05326 / m05418：放置器只能在边界之内落点。
     *
     * <p>草稿里没有边界时（游戏重启后重新拿道具），回退到"这个玩家最后一次自动成组的那块边界"，
     * 否则草稿是空的会把所有位置都放行 —— 这就是"边界之外没有被拒收"的原因。</p>
     */
    private static ArenaStore.Arena loadEditingArena(ServerPlayer player) {
        ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
        if (draft.autoName != null) {
            ArenaStore.Arena saved = ArenaStore.get(player.server).byName(draft.autoName);
            if (saved != null && !saved.name.equals("draft")) copyArena(saved, draft);
        }
        if (!draft.hasBoundary()) {
            for (ArenaStore.Arena saved : ArenaStore.get(player.server).allArenas()) {
                if (saved.hasBoundary() && sameDimension(player, saved)
                        && saved.insideBoundary(player.position())) {
                    copyArena(saved, draft);
                    break;
                }
            }
        } else if (draft.autoName == null && sameDimension(player, draft)
                && !draft.insideBoundary(player.position())) {
            for (ArenaStore.Arena saved : ArenaStore.get(player.server).allArenas()) {
                if (saved.hasBoundary() && sameDimension(player, saved)
                        && saved.insideBoundary(player.position())) {
                    copyArena(saved, draft);
                    break;
                }
            }
        }
        return draft;
    }

    private static boolean sameDimension(ServerPlayer player, ArenaStore.Arena arena) {
        return arena.dimension == null
                ? player.serverLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                : arena.dimension.equals(player.serverLevel().dimension().location());
    }

    private static boolean outsideArenaBoundary(ServerPlayer player, ArenaStore.Arena draft, Vec3 point) {
        return !draft.hasBoundary() || !draft.insideBoundary(point) || !sameDimension(player, draft);
    }

    private static boolean outsideBoundary(ServerPlayer player, ArenaStore.Arena draft, Vec3 point) {
        ArenaStore.Arena editing = loadEditingArena(player);
        return outsideArenaBoundary(player, editing, point);
    }

    private static void copyArena(ArenaStore.Arena source, ArenaStore.Arena target) {
        target.autoName = source.name;
        target.dimension = source.dimension;
        target.boundary.clear();
        target.boundary.addAll(source.boundary);
        target.redPlayer = source.redPlayer;
        target.bluePlayer = source.bluePlayer;
        target.redPokemon = source.redPokemon;
        target.bluePokemon = source.bluePokemon;
        target.redSwitch = source.redSwitch;
        target.blueSwitch = source.blueSwitch;
        target.switchRadius = source.switchRadius;
        target.redSwitchPolygon.clear();
        target.redSwitchPolygon.addAll(source.redSwitchPolygon);
        target.blueSwitchPolygon.clear();
        target.blueSwitchPolygon.addAll(source.blueSwitchPolygon);
    }

    /**
     * 四个点一凑齐就自动成组并自动开房（用户 m04462："这四个点弄完就自动弄成一个组 / 自动创建房间"），
     * 玩家不用再敲 /mo arena create。补画边界后同样会原地更新这一组。
     */
    private static void autoGroup(ServerPlayer player) {
        ArenaStore store = ArenaStore.get(player.server);
        ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
        boolean wasReady = draft.autoName != null && store.byName(draft.autoName) != null
                && store.byName(draft.autoName).complete();
        String name = store.saveDraftAuto(player.server, player.getUUID());
        if (name == null) return;
        ArenaStore.Arena saved = store.byName(name);
        if (saved != null && saved.complete()) {
            com.pkmoba.duel.RoomManager.create(player, name);
            if (!wasReady) player.displayClientMessage(Component.literal(
                    "场地「" + name + "」已就绪，按 H 加入房间"), true);
            if (!saved.insideSwitchZone(saved.redPlayer, true)
                    || !saved.insideSwitchZone(saved.bluePlayer, false)) {
                player.displayClientMessage(Component.literal(
                        "红蓝换宠区尚未设置完整；场地和房间可正常使用，但战中无法换宠"), true);
            }
        } else {
            player.displayClientMessage(Component.literal(
                    "场地「" + name + "」未就绪：需补齐四点边界、边界内红蓝玩家点，以及红蓝各四个顶点的换宠区域"), true);
        }
    }

    /** 主副手持有编辑工具即持续显示预览；不依赖蹲下、使用动作、房间或战斗状态。 */
    public static void tickHints(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean holdsPlayerMarker = holds(player, PLAYER_MARKER);
            boolean holdsPokemonMarker = holds(player, POKEMON_MARKER);
            boolean holdsBoundary = holds(player, BOUNDARY_MARKER);
            boolean holdsSwitch = holds(player, SWITCH_MARKER);
            if (!holdsPlayerMarker && !holdsPokemonMarker && !holdsBoundary && !holdsSwitch) continue;
            ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
            // 已存场地独立于草稿：自动成组、换组、重新登录后仍可直接拿工具看见。
            java.util.List<ArenaStore.Arena> previews = ArenaStore.get(server).allArenas();
            previews.add(draft);
            // 去重当前草稿与已存版本相同的点/线，避免每次自动保存后粒子翻倍。
            java.util.Set<String> emitted = new java.util.HashSet<>();
            for (ArenaStore.Arena arena : previews) {
                if (arena.dimension != null && !arena.dimension.equals(player.level().dimension().location())) continue;
                // 没有维度的空草稿不画；旧存档缺省维度按主世界解释，避免跨维度误画。
                if (arena.dimension == null && arena != draft
                        && !player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) continue;
                if (holdsPlayerMarker || holdsPokemonMarker) {
                    // 宝可梦点位工具已停用，但旧物品持握时仍预览实际使用的玩家出场点。
                    hintPoint(player, arena.redPlayer != null ? arena.redPlayer : arena.redPokemon,
                            1.0F, 0.25F, 0.25F, emitted);
                    hintPoint(player, arena.bluePlayer != null ? arena.bluePlayer : arena.bluePokemon,
                            0.25F, 0.55F, 1.0F, emitted);
                }
                if (holdsSwitch) {
                    boolean red = player.isShiftKeyDown();
                    boolean editingArena = arena == draft || (draft.autoName != null
                            && draft.autoName.equalsIgnoreCase(arena.name));
                    java.util.List<Vec3> redPoints = editingArena
                            ? draft.redSwitchPolygon : arena.switchPolygon(true);
                    java.util.List<Vec3> bluePoints = editingArena
                            ? draft.blueSwitchPolygon : arena.switchPolygon(false);
                    polygonHint(player, redPoints, 1F, .25F, .25F, emitted);
                    polygonHint(player, bluePoints, .25F, .55F, 1F, emitted);
                    java.util.List<Vec3> current = red ? redPoints : bluePoints;
                    if (current.size() < 4) {
                        Vec3 preview = player.pick(5.0, 0.0F, false).getLocation();
                        if (preview != null && (editingArena
                                ? sameDimension(player, arena)
                                : !outsideBoundary(player, arena, preview))) {
                            hintPoint(player, preview, red ? 1F : .25F, red ? .25F : .55F,
                                    red ? .25F : 1F, emitted);
                        }
                    }
                    if (redPoints.size() != 4 || bluePoints.size() != 4) {
                        hintPoint(player, arena.redPlayer, 1F, .25F, .25F, emitted);
                        hintPoint(player, arena.bluePlayer, .25F, .55F, 1F, emitted);
                    }
                }
                if (holdsBoundary) polygonHint(player, arena.boundary, 0.55F, 1.0F, 0.55F, emitted);
            }
        }
    }

    private static boolean holds(ServerPlayer player, Item item) {
        return player.getMainHandItem().is(item) || player.getOffhandItem().is(item);
    }

    /** 未满四点时画开放折线，满四点才闭合；单点也有可见标记。 */
    private static void polygonHint(ServerPlayer player, java.util.List<Vec3> points,
                                    float r, float g, float b, java.util.Set<String> emitted) {
        for (Vec3 point : points) hintPoint(player, point, r, g, b, emitted);
        int edges = points.size() >= 4 ? points.size() : points.size() - 1;
        for (int i = 0; i < edges; i++) {
            lineHint(player, points.get(i), points.get((i + 1) % points.size()), r, g, b, emitted);
        }
    }

    private static void lineHint(ServerPlayer player, Vec3 a, Vec3 b,
                                  float r, float g, float blue, java.util.Set<String> emitted) {
        int steps = (int) Math.min(256, Math.max(2, a.distanceTo(b) / 0.6));
        for (int s = 0; s <= steps; s++) {
            previewParticle(player, a.lerp(b, s / (double) steps).add(0, 0.08, 0),
                    r, g, blue, 1, emitted);
        }
    }

    private static void hintPoint(ServerPlayer player, Vec3 point,
                                  float r, float g, float b, java.util.Set<String> emitted) {
        if (point != null) previewParticle(player, point.add(0, 0.15, 0), r, g, b, 3, emitted);
    }

    private static void previewParticle(ServerPlayer player, Vec3 point, float r, float g, float b,
                                        int count, java.util.Set<String> emitted) {
        String key = point.x + ":" + point.y + ":" + point.z + ":" + r + ":" + g + ":" + b;
        if (!emitted.add(key)) return;
        // 只发送给持握者；force 绕过普通粒子的短距离发送与客户端粒子减少设置。
        player.serverLevel().sendParticles(player,
                new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(r, g, b), 1.0F),
                true, point.x, point.y, point.z, count,
                count > 1 ? 0.18 : 0.0, count > 1 ? 0.05 : 0.0, count > 1 ? 0.18 : 0.0, 0.0);
    }

    private static String format(Vec3 vec) {
        return String.format("%.1f, %.1f, %.1f", vec.x, vec.y, vec.z);
    }

}

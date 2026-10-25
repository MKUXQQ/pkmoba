package com.pkmoba.item;

import com.pkmoba.PkmobaMod;
import com.pkmoba.duel.ArenaStore;
import com.pkmoba.pokemon.TeamService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Right-click entry items for the duel modes, species selection and arena points. */
public final class ModeItems {
    public static final Item BATTLE_MENU = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "battle_menu"),
            new Item(new Item.Properties().stacksTo(1)));
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

    /** 换宠区放置器：右键方块记录该方换宠区中心（蹲下 = 红方，站立 = 蓝方）。 */
    public static final Item SWITCH_MARKER = Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "switch_marker"),
            new Item(new Item.Properties().stacksTo(1)));

    /** 创造模式物品栏标签页：没有注册这个分组，物品就只存在于注册表里，物品栏里永远看不到。 */
    public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "main"),
            CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, 7)
                    .title(Component.translatable("itemGroup.pkmoba"))
                    .icon(() -> new ItemStack(BATTLE_MENU))
                    .displayItems((params, output) -> {
                        output.accept(BATTLE_MENU);
                        output.accept(SPECIES_SWITCH);
                        output.accept(PLAYER_MARKER);
                        // m05418：宝可梦放置点已删除
                        output.accept(BOUNDARY_MARKER);
                        output.accept(SWITCH_MARKER);
                    })
                    .build());

    private ModeItems() {}

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(BATTLE_MENU);
            entries.accept(SPECIES_SWITCH);
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
            if (stack.is(BATTLE_MENU)) {
                openBattleMenu(serverPlayer);
                return InteractionResultHolder.success(stack);
            }
            if (stack.is(SPECIES_SWITCH)) {
                TeamService.openPicker(serverPlayer);
                return InteractionResultHolder.success(stack);
            }
            return InteractionResultHolder.pass(stack);
        });
        // 点位放置器需要右键方块：蹲下 = 红点，不蹲 = 蓝点
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
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
                        + (have >= need ? "（4 点已齐，点位组已建立，接着放点位/换宠区）"
                        : "（还需 " + (need - have) + " 点）")), true);
                autoGroup(serverPlayer);
                return InteractionResult.SUCCESS;
            }
            if (stack.is(SWITCH_MARKER)) {
                Vec3 center = hit.getBlockPos().above().getCenter();
                ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
                if (outsideBoundary(serverPlayer, draft, center)) {
                    serverPlayer.displayClientMessage(Component.literal(
                            "换宠区只能放在边界之内，先把边界画好"), true);
                    return InteractionResult.SUCCESS;
                }
                draft.dimension = level.dimension().location();
                // m05418：换宠区不分红蓝，右键记 4 个点围成一块，双方都在里面换。
                java.util.List<Vec3> quad = draft.switchQuad;
                if (quad.size() >= 4) {
                    quad.clear();
                    serverPlayer.displayClientMessage(Component.literal(
                            "换宠区已满 4 点，重新从第 1 点开始"), true);
                }
                quad.add(center);
                serverPlayer.displayClientMessage(Component.literal(
                        "换宠区第 " + quad.size() + " 点"
                                + (quad.size() >= 4 ? "（4 点已齐，区域生效）"
                                : "（还需 " + (4 - quad.size()) + " 点）")), true);
                autoGroup(serverPlayer);
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
            if (stack.is(PLAYER_MARKER)) {
                if (red) draft.redPlayer = point;
                else draft.bluePlayer = point;
            } else {
                if (red) draft.redPokemon = point;
                else draft.bluePokemon = point;
            }
            String label = (stack.is(PLAYER_MARKER) ? "玩家" : "宝可梦") + (red ? "红" : "蓝") + "点";
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
    private static boolean outsideBoundary(ServerPlayer player, ArenaStore.Arena draft, Vec3 point) {
        java.util.List<Vec3> boundary = draft.boundary;
        if (boundary.size() < ArenaStore.BOUNDARY_MIN_POINTS) {
            String name = draft.autoName;
            ArenaStore.Arena saved = name == null ? null
                    : ArenaStore.get(player.server).byName(name);
            if (saved == null) {
                java.util.List<ArenaStore.Arena> all = ArenaStore.get(player.server).allArenas();
                if (!all.isEmpty()) saved = all.get(all.size() - 1);
            }
            if (saved == null) {
                return false;
            }
            boundary = saved.boundary;
        }
        if (boundary.size() < ArenaStore.BOUNDARY_MIN_POINTS) {
            return false;
        }
        ArenaStore.Arena probe = new ArenaStore.Arena("probe");
        probe.boundary.addAll(boundary);
        return !probe.insideBoundary(point);
    }

    /**
     * 四个点一凑齐就自动成组并自动开房（用户 m04462："这四个点弄完就自动弄成一个组 / 自动创建房间"），
     * 玩家不用再敲 /mo arena create。补画边界后同样会原地更新这一组。
     */
    private static void autoGroup(ServerPlayer player) {
        String before = ArenaStore.draft(player.getUUID()).autoName;
        String name = ArenaStore.get(player.server).saveDraftAuto(player.server, player.getUUID());
        if (name == null) {
            return;
        }
        // m04816：去掉「必须先不在房间里」这道门 —— 只要四点凑齐就自动建房，
        // 建房是幂等的（同一 arena 已有房间会复用），所以在不在房间里都该触发。
        com.pkmoba.duel.RoomManager.create(player, name);
        if (before == null) {
            // 只在第一次成组时报一次；之后每次补点都刷同一条会被当成 bug。
            player.displayClientMessage(Component.literal(
                    "点位组「" + name + "」已就绪，房间已创建（按 H 查看）"), true);
        }
    }

    /** 手持点位/边界工具时，在已记录的点上持续撒粒子（不持有时完全不显示）。 */
    public static void tickHints(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack main = player.getMainHandItem();
            boolean holdsPlayerMarker = main.is(PLAYER_MARKER);
            boolean holdsPokemonMarker = main.is(POKEMON_MARKER);
            boolean holdsBoundary = main.is(BOUNDARY_MARKER);
            boolean holdsSwitch = main.is(SWITCH_MARKER);
            if (!holdsPlayerMarker && !holdsPokemonMarker && !holdsBoundary && !holdsSwitch) continue;
            ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
            var level = player.serverLevel();
            if (holdsPlayerMarker) {
                hintPoint(level, draft.redPlayer, 1.0F, 0.25F, 0.25F);
                hintPoint(level, draft.bluePlayer, 0.25F, 0.55F, 1.0F);
            }
            if (holdsPokemonMarker) {
                hintPoint(level, draft.redPokemon, 1.0F, 0.6F, 0.2F);
                hintPoint(level, draft.bluePokemon, 0.2F, 0.9F, 0.9F);
            }
            if (holdsSwitch) {
                switchHint(level, draft.redSwitch, draft.redPlayer, draft.switchRadius, 1.0F, 0.3F, 0.3F);
                switchHint(level, draft.blueSwitch, draft.bluePlayer, draft.switchRadius, 0.3F, 0.6F, 1.0F);
            }
            if (holdsBoundary) {
                for (Vec3 point : draft.boundary) {
                    hintPoint(level, point, 0.55F, 1.0F, 0.55F);
                }
                // 边界连线：相邻两点之间按 0.6 格采样撒点，方便看出多边形形状
                if (draft.boundary.size() >= 2) {
                    for (int i = 0; i < draft.boundary.size(); i++) {
                        Vec3 a = draft.boundary.get(i);
                        Vec3 b = draft.boundary.get((i + 1) % draft.boundary.size());
                        double len = a.distanceTo(b);
                        int steps = (int) Math.min(48, Math.max(2, len / 0.6));
                        for (int s = 0; s <= steps; s++) {
                            Vec3 p = a.lerp(b, s / (double) steps);
                            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                                    p.x, p.y + 0.08, p.z, 1, 0.0, 0.0, 0.0, 0.0);
                        }
                    }
                }
            }
        }
    }

    /** 换宠区提示：地面圆环 + 一条从换宠区中心到本方玩家点的虚线（和边界画笔同样的粒子线）。 */
    private static void switchHint(net.minecraft.server.level.ServerLevel level, Vec3 center, Vec3 playerPoint,
                                   double radius, float r, float g, float b) {
        if (center == null) return;
        int ringSteps = 44;
        for (int i = 0; i < ringSteps; i++) {
            double a = (Math.PI * 2.0) * i / ringSteps;
            double x = center.x + Math.cos(a) * radius;
            double z = center.z + Math.sin(a) * radius;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    x, center.y + 0.08, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (playerPoint != null) {
            int steps = (int) Math.min(64, Math.max(4, center.distanceTo(playerPoint) / 0.5));
            for (int s = 0; s <= steps; s++) {
                Vec3 p = center.lerp(playerPoint, s / (double) steps);
                level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                                new org.joml.Vector3f(r, g, b), 1.0F),
                        p.x, p.y + 0.1, p.z, 1, 0.02, 0.0, 0.02, 0.0);
            }
        }
    }

    private static void hintPoint(net.minecraft.server.level.ServerLevel level, Vec3 point,
                                  float r, float g, float b) {
        if (point == null) return;
        level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                        new org.joml.Vector3f(r, g, b), 1.0F),
                point.x, point.y + 0.15, point.z, 3, 0.18, 0.05, 0.18, 0.0);
    }

    private static String format(Vec3 vec) {
        return String.format("%.1f, %.1f, %.1f", vec.x, vec.y, vec.z);
    }

    private static void openBattleMenu(ServerPlayer player) {
        com.pkmoba.duel.MenuActions.open(player, com.pkmoba.network.MenuOpenPayload.MenuKind.BATTLE);
    }
}

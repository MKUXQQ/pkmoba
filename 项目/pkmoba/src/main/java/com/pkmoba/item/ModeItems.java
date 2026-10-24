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
                        output.accept(POKEMON_MARKER);
                    })
                    .build());

    private ModeItems() {}

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(BATTLE_MENU);
            entries.accept(SPECIES_SWITCH);
            entries.accept(PLAYER_MARKER);
            entries.accept(POKEMON_MARKER);
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
            if (!stack.is(PLAYER_MARKER) && !stack.is(POKEMON_MARKER)) return InteractionResult.PASS;
            boolean red = player.isShiftKeyDown();
            Vec3 point = hit.getBlockPos().above().getCenter();
            ArenaStore.Arena draft = ArenaStore.draft(player.getUUID());
            draft.dimension = level.dimension().location();
            if (stack.is(PLAYER_MARKER)) {
                if (red) draft.redPlayer = point;
                else draft.bluePlayer = point;
            } else {
                if (red) draft.redPokemon = point;
                else draft.bluePokemon = point;
            }
            String label = (stack.is(PLAYER_MARKER) ? "玩家" : "宝可梦") + (red ? "红" : "蓝") + "点";
            serverPlayer.sendSystemMessage(Component.literal("已记录 " + label + "：" + format(point)
                    + (draft.complete() ? "，输入 /mo arena create <名字> 保存场地。" : "")));
            return InteractionResult.SUCCESS;
        });
    }

    private static String format(Vec3 vec) {
        return String.format("%.1f, %.1f, %.1f", vec.x, vec.y, vec.z);
    }

    private static void openBattleMenu(ServerPlayer player) {
        com.pkmoba.duel.MenuActions.open(player, com.pkmoba.network.MenuOpenPayload.MenuKind.BATTLE);
    }
}

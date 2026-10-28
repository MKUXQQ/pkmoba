package com.pkmoba.pokemon;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * PKMOBA 已实装宝可梦注册表。
 *
 * <p>新增一只宝可梦时只改这里 + 对应战斗引擎；选择界面、指令补全、队伍、头像都从这里取。
 */
public final class SpeciesRegistry {
    public record Entry(ResourceLocation id, String displayName, String portrait) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    // 0.51 扩展物种：8 只新物种的战斗/控制/动画已通过构建，这里注册后即进入选人界面、
    // 指令补全（MoCommand）、队伍（TeamService）、房间/DuelManager 与头像解析，无需改别处。
    static {
        add("charizard", "喷火龙");
        add("blastoise", "水箭龟");
        add("venusaur", "妙蛙花");
        add("pikachu", "皮卡丘");
        add("blaziken", "火焰鸡");
        add("gardevoir", "沙奈朵");
        add("scizor", "巨钳螳螂");
        add("gengar", "耿鬼");
        add("garchomp", "烈咬陆鲨");
        add("lucario", "路卡利欧");
        add("tyranitar", "班基拉斯");
        add("abomasnow", "暴雪王");
    }

    private SpeciesRegistry() {
    }

    private static void add(String path, String displayName) {
        ENTRIES.add(new Entry(ResourceLocation.fromNamespaceAndPath("cobblemon", path),
                displayName, "textures/gui/pokemon/" + path + ".png"));
    }

    public static List<Entry> all() {
        return List.copyOf(ENTRIES);
    }

    public static List<String> ids() {
        List<String> list = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) list.add(entry.id().toString());
        return list;
    }

    public static boolean contains(@Nullable ResourceLocation id) {
        if (id == null) return false;
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return true;
        return false;
    }

    /** 解析 {@code charizard} / {@code cobblemon:charizard} 两种写法。 */
    @Nullable
    public static ResourceLocation parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!text.contains(":")) text = "cobblemon:" + text;
        ResourceLocation id = ResourceLocation.tryParse(text);
        return contains(id) ? id : null;
    }

    public static String displayName(@Nullable ResourceLocation id) {
        if (id == null) return "宝可梦";
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return entry.displayName();
        return id.getPath();
    }

    @Nullable
    public static Entry byId(@Nullable ResourceLocation id) {
        if (id == null) return null;
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return entry;
        return null;
    }
}

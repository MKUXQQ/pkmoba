package com.pkmoba.pokemon;

import com.cobblemon.mod.common.pokemon.Pokemon;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

/**
 * PKMOBA 已实装宝可梦注册表。
 *
 * <p>新增一只宝可梦时只改这里 + 对应战斗引擎；选择界面、指令补全、队伍、头像都从这里取。
 *
 * <p>0.87: a species may be a regional form of another Cobblemon species (Alolan Ninetales is
 * {@code cobblemon:ninetales} with the {@code alolan} aspect), so each entry carries both the local
 * id used for assets/room rosters and the real Cobblemon species to spawn.
 */
public final class SpeciesRegistry {
    public record Entry(ResourceLocation id, String displayName, String portrait,
                        ResourceLocation species, @Nullable String aspect) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add("charizard", "喷火龙");
        add("blastoise", "水箭龟");
        add("venusaur", "妙蛙花");
        add("pikachu", "皮卡丘");
        add("blaziken", "火焰鸡");
        add("gardevoir", "沙奈朵");
        add("gengar", "耿鬼");
        add("lucario", "路卡利欧");
        add("tyranitar", "班基拉斯");
        add("greninja", "甲贺忍蛙");
        add("meowscarada", "魔幻假面喵");
        add("ceruledge", "苍炎刃鬼");
        add("krookodile", "流氓鳄");
        add("armarouge", "红莲铠骑");
        // 0.133.1: Hisuian Zoroark is cobblemon:zoroark + the "hisuian" aspect. Spawning the bare
        // cobblemon:hisuian_zoroark id failed (no such species), which surfaced as
        // "宝可梦生成失败或原位置空间不足".
        addForm("hisuian_zoroark", "洗翠索罗亚克", "zoroark", "hisuian");
        addForm("alolan_ninetales", "阿罗拉九尾", "ninetales", "alolan");
    }

    private SpeciesRegistry() {
    }

    private static void add(String path, String displayName) {
        ENTRIES.add(new Entry(ResourceLocation.fromNamespaceAndPath("cobblemon", path), displayName,
                "textures/gui/pokemon/" + path + ".png",
                ResourceLocation.fromNamespaceAndPath("cobblemon", path), null));
    }

    private static void addForm(String localPath, String displayName, String speciesPath, String aspect) {
        ENTRIES.add(new Entry(ResourceLocation.fromNamespaceAndPath("cobblemon", localPath), displayName,
                "textures/gui/pokemon/" + localPath + ".png",
                ResourceLocation.fromNamespaceAndPath("cobblemon", speciesPath), aspect));
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

    /** The Cobblemon species to spawn for a registry id (falls back to the id itself). */
    public static ResourceLocation cobbleSpecies(@Nullable ResourceLocation id) {
        Entry entry = byId(id);
        return entry == null ? id : entry.species();
    }

    /**
     * Reverse lookup for regional forms: a spawned entity only knows its Cobblemon species
     * ({@code cobblemon:ninetales}), so battle code asks which registry id owns that species.
     */
    @Nullable
    public static ResourceLocation localForCobble(@Nullable ResourceLocation cobbleId) {
        if (cobbleId == null) return null;
        for (Entry entry : ENTRIES) {
            if (entry.aspect() != null && entry.species().equals(cobbleId)) return entry.id();
        }
        return null;
    }

    /** Applies a regional form (aspect) to a freshly created Pokemon. */
    /**
     * 0.133.1: the Cobblemon species path a local id spawns as. Regional forms spawn as their base
     * species (Hisuian Zoroark is {@code cobblemon:zoroark}), so any client-side check that
     * compares an entity's species against a roster id must go through this.
     */
    public static String cobblePath(@Nullable String localPath) {
        if (localPath == null) return "";
        Entry entry = byId(ResourceLocation.tryParse(
                localPath.contains(":") ? localPath : "cobblemon:" + localPath));
        return entry == null ? localPath : entry.species().getPath();
    }

    public static void applyForm(Pokemon pokemon, @Nullable ResourceLocation id) {
        Entry entry = byId(id);
        if (pokemon == null || entry == null || entry.aspect() == null) return;
        pokemon.setForcedAspects(java.util.Set.of(entry.aspect()));
        pokemon.updateForm();
    }

    /** One startup line so a jar can be told apart from an older build without opening the game. */
    static {
        com.pkmoba.PkmobaMod.LOGGER.info("[pkmoba] species registry: {} entries, last={}",
                ENTRIES.size(), ENTRIES.isEmpty() ? "-" : ENTRIES.get(ENTRIES.size() - 1).id());
    }
}

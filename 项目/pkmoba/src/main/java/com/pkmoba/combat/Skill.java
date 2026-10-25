package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/**
 * 四个技能的静态定义（耗点、冷却）。
 *
 * <p>当前确认值：Q/W/E/R 分别消耗 3/3/2/4 豆；成功起手只扣一次。
 */
public enum Skill {
    Q("skill.pkmoba.q", 3),
    W("skill.pkmoba.w", 3),
    E("skill.pkmoba.e", 2),
    R("skill.pkmoba.r", 4);

    /** 技能名称的语言文件键。 */
    public final String translationKey;

    /** 释放瞬间扣除的技能点。 */
    public final int cost;

    Skill(String translationKey, int cost) {
        this.translationKey = translationKey;
        this.cost = cost;
    }

    public static boolean isBlastoise(PokemonEntity pokemon) {
        return pokemon != null && "cobblemon:blastoise".equals(
                pokemon.getPokemon().getSpecies().getResourceIdentifier().toString());
    }

    /** 按物种 id 判断（已召唤/未召唤都能用）。 */
    public static boolean isBlastoise(String speciesId) {
        if (speciesId == null || speciesId.isBlank()) return false;
        return speciesId.contains(":") ? "cobblemon:blastoise".equals(speciesId)
                : "blastoise".equals(speciesId);
    }

    public static String translationKey(PokemonEntity pokemon, int index) {
        return translationKey(isBlastoise(pokemon), index);
    }

    /** 按物种 id 取技能名（未召唤时也能画出正确的名字）。 */
    public static String translationKey(String speciesId, int index) {
        return translationKey(isBlastoise(speciesId), index);
    }

    private static String translationKey(boolean blastoise, int index) {
        String suffix = switch (index) {
            case 0 -> "q";
            case 1 -> "w";
            case 2 -> "e";
            case 3 -> "r";
            default -> throw new IndexOutOfBoundsException(index);
        };
        return "skill.pkmoba." + (blastoise ? "blastoise." : "") + suffix;
    }

    /** 喷火龙耗点（Q/W/E/R）。 */
    private static final int[] CHARIZARD_COSTS = {3, 3, 2, 4};
    /** 水箭龟耗点（0.38 槽位重排：加农水炮/高速旋转/冲浪术/喷水）。 */
    private static final int[] BLASTOISE_COSTS = {4, 2, 3, 4};

    /**
     * 按实体取耗点。
     *
     * <p>0.38 起耗点表按物种不同：喷火龙 {3,3,2,4}、水箭龟 {4,2,3,4}。
     * 不要再读 {@link #cost} 字段，那只是喷火龙的默认值。
     */
    public static int cost(PokemonEntity pokemon, int index) {
        return cost(isBlastoise(pokemon), index);
    }

    /** 按物种 id 取耗点（未召唤时 HUD 也能画对）。 */
    public static int cost(String speciesId, int index) {
        return cost(isBlastoise(speciesId), index);
    }

    public static int cost(boolean blastoise, int index) {
        int[] table = blastoise ? BLASTOISE_COSTS : CHARIZARD_COSTS;
        return index < 0 || index >= table.length ? 0 : table[index];
    }

    /**
     * 技能冷却（tick）。喷火龙 Q/W/E/R = 3.5s / 5s / 4s / 6s。
     *
     * <p>用户 m01881："所有的技能 CD 都太快了" —— 0.38.3 及之前全部技能都硬编码 40 tick（2 秒），
     * 一轮技能转得比走位还快，完全没有"交了技能要等窗口"的博弈。0.38.4 起按技能定位给差异化 CD：
     * 小技能 3.5 秒、范围控制 5 秒、位移 4 秒、大招 6 秒。
     */
    private static final int[] CHARIZARD_COOLDOWNS = {70, 100, 80, 120};
    /** 水箭龟（0.38 槽位重排：加农水炮/高速旋转/冲浪术/喷水）。 */
    private static final int[] BLASTOISE_COOLDOWNS = {80, 60, 90, 140};

    /**
     * 按物种取冷却 tick。
     *
     * <p>HUD 冷却遮罩必须和服务端用<b>同一张表</b>，否则会出现"图标转完圈了技能还没好"
     * 或者"技能好了图标还是灰的"。
     */
    public static int cooldownTicks(boolean blastoise, int index) {
        int[] table = blastoise ? BLASTOISE_COOLDOWNS : CHARIZARD_COOLDOWNS;
        return index < 0 || index >= table.length ? 40 : table[index];
    }

    public static int cooldownTicks(String speciesId, int index) {
        return cooldownTicks(isBlastoise(speciesId), index);
    }

    public static int cooldownTicks(PokemonEntity pokemon, int index) {
        return cooldownTicks(isBlastoise(pokemon), index);
    }

    public static Skill byIndex(int i) {
        return switch (i) {
            case 0 -> Q;
            case 1 -> W;
            case 2 -> E;
            case 3 -> R;
            default -> null;
        };
    }
}

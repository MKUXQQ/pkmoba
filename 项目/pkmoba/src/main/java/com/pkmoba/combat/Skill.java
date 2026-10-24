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

    public static int cost(PokemonEntity pokemon, int index) {
        Skill skill = byIndex(index);
        return skill == null ? 0 : skill.cost;
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

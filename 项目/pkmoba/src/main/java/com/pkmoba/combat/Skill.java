package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/** Shared skill names, bean costs and server/HUD cooldowns. */
public enum Skill {
    Q("skill.pkmoba.q", 3), W("skill.pkmoba.w", 3),
    E("skill.pkmoba.e", 2), R("skill.pkmoba.r", 3);

    public final String translationKey;
    public final int cost;
    Skill(String translationKey, int cost) { this.translationKey = translationKey; this.cost = cost; }

    public static boolean isBlastoise(PokemonEntity pokemon) { return species(pokemon, "cobblemon:blastoise"); }
    public static boolean isVenusaur(PokemonEntity pokemon) { return species(pokemon, "cobblemon:venusaur"); }
    public static boolean isPikachu(PokemonEntity pokemon) { return species(pokemon, "cobblemon:pikachu"); }
    private static boolean species(PokemonEntity pokemon, String id) {
        return pokemon != null && id.equals(pokemon.getPokemon().getSpecies().getResourceIdentifier().toString());
    }
    public static boolean isBlastoise(String id) { return species(id, "blastoise"); }
    public static boolean isVenusaur(String id) { return species(id, "venusaur"); }
    public static boolean isPikachu(String id) { return species(id, "pikachu"); }
    private static boolean species(String id, String name) { return name.equals(id) || ("cobblemon:" + name).equals(id); }

    public static String translationKey(PokemonEntity pokemon, int index) {
        var expansion = ExpansionCombat.species(pokemon);
        if (expansion != null) return translationKey(expansion.id(), index);
        return translationKey(isBlastoise(pokemon), isVenusaur(pokemon), isPikachu(pokemon), index);
    }
    public static String translationKey(String id, int index) {
        var expansion = ExpansionRoster.resolve(id);
        if (expansion != null) {
            if (index < 0 || index > 3) throw new IndexOutOfBoundsException(index);
            return "skill.pkmoba." + expansion.id() + "." + "qwer".charAt(index);
        }
        return translationKey(isBlastoise(id), isVenusaur(id), isPikachu(id), index);
    }
    private static String translationKey(boolean blastoise, boolean venusaur, boolean pikachu, int index) {
        String suffix = switch (index) {
            case 0 -> "q"; case 1 -> "w"; case 2 -> "e"; case 3 -> "r";
            default -> throw new IndexOutOfBoundsException(index);
        };
        return "skill.pkmoba." + (pikachu ? "pikachu." : venusaur ? "venusaur." : blastoise ? "blastoise." : "") + suffix;
    }

    private static final int[] CHARIZARD_COSTS = {3, 3, 2, 3};
    private static final int[] BLASTOISE_COSTS = {4, 2, 3, 3};
    private static final int[] VENUSAUR_COSTS = {3, 3, 2, 3};
    private static final int[] PIKACHU_COSTS = {2, 3, 2, 3};
    private static final int[] CHARIZARD_COOLDOWNS = {90, 140, 140, 180};
    private static final int[] BLASTOISE_COOLDOWNS = {100, 120, 140, 180};
    private static final int[] VENUSAUR_COOLDOWNS = {100, 140, 160, 180};
    private static final int[] PIKACHU_COOLDOWNS = {60, 120, 100, 180};

    public static int cost(PokemonEntity pokemon, int index) {
        var expansion = ExpansionCombat.species(pokemon);
        return expansion == null ? cost(isBlastoise(pokemon), isVenusaur(pokemon), isPikachu(pokemon), index)
                : index < 0 || index > 3 ? 0 : expansion.move(index).cost();
    }
    public static int cost(String id, int index) {
        var expansion = ExpansionRoster.resolve(id);
        return expansion == null ? cost(isBlastoise(id), isVenusaur(id), isPikachu(id), index)
                : index < 0 || index > 3 ? 0 : expansion.move(index).cost();
    }
    public static int cost(boolean blastoise, int index) { return cost(blastoise, false, false, index); }
    private static int cost(boolean blastoise, boolean venusaur, boolean pikachu, int index) {
        int[] table = pikachu ? PIKACHU_COSTS : venusaur ? VENUSAUR_COSTS : blastoise ? BLASTOISE_COSTS : CHARIZARD_COSTS;
        return index < 0 || index >= table.length ? 0 : table[index];
    }
    public static int cooldownTicks(PokemonEntity pokemon, int index) {
        var expansion = ExpansionCombat.species(pokemon);
        return expansion == null ? cooldownTicks(isBlastoise(pokemon), isVenusaur(pokemon), isPikachu(pokemon), index)
                : index < 0 || index > 3 ? 40 : expansion.move(index).cooldown();
    }
    public static int cooldownTicks(String id, int index) {
        var expansion = ExpansionRoster.resolve(id);
        return expansion == null ? cooldownTicks(isBlastoise(id), isVenusaur(id), isPikachu(id), index)
                : index < 0 || index > 3 ? 40 : expansion.move(index).cooldown();
    }
    public static int cooldownTicks(boolean blastoise, int index) { return cooldownTicks(blastoise, false, false, index); }
    private static int cooldownTicks(boolean blastoise, boolean venusaur, boolean pikachu, int index) {
        int[] table = pikachu ? PIKACHU_COOLDOWNS : venusaur ? VENUSAUR_COOLDOWNS : blastoise ? BLASTOISE_COOLDOWNS : CHARIZARD_COOLDOWNS;
        return index < 0 || index >= table.length ? 40 : table[index];
    }
    public static Skill byIndex(int i) {
        return switch (i) { case 0 -> Q; case 1 -> W; case 2 -> E; case 3 -> R; default -> null; };
    }
}

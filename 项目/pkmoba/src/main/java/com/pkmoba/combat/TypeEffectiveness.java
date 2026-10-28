package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import java.util.Locale;

/** Bounded, explicit move-type chart for the three playable species. Unknown species are neutral. */
public final class TypeEffectiveness {
    public enum MoveType { FIRE, WATER, GRASS, FLYING, POISON, ELECTRIC, FIGHTING, NORMAL, ICE, GHOST, DARK, ROCK, GROUND, DRAGON, STEEL, BUG, PSYCHIC, FAIRY, PHYSICAL }

    public static final double MIN_MULTIPLIER = 0.75;
    public static final double MAX_MULTIPLIER = 1.5;

    public static MoveType forAttackerSpecies(String speciesId) {
        String species = normalize(speciesId);
        return switch (species) {
            case "charizard" -> MoveType.FIRE;
            case "blastoise" -> MoveType.WATER;
            case "venusaur" -> MoveType.GRASS;
            case "pikachu" -> MoveType.ELECTRIC;
            case "lucario" -> MoveType.FIGHTING;
            case "abomasnow" -> MoveType.ICE;
            case "blaziken" -> MoveType.FIRE;
            case "gengar" -> MoveType.GHOST;
            case "tyranitar" -> MoveType.ROCK;
            case "garchomp" -> MoveType.DRAGON;
            case "scizor" -> MoveType.STEEL;
            case "gardevoir" -> MoveType.PSYCHIC;
            default -> MoveType.PHYSICAL;
        };
    }

    public static MoveType moveType(PokemonEntity entity, int skill) {
        var expansion=ExpansionCombat.species(entity);
        if (expansion!=null && skill>=0 && skill<4) return expansionMoveType(expansion.id(),skill);
        if (CharizardManager.isPikachu(entity)) return MoveType.ELECTRIC;
        if (CharizardManager.isVenusaur(entity)) return skill == 3 ? MoveType.POISON : MoveType.GRASS;
        if (CharizardManager.isBlastoise(entity)) return MoveType.WATER;
        if (entity != null && entity.getPokemon() != null
                && CharizardManager.CHARIZARD_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier())) {
            return skill == 2 ? MoveType.FLYING : MoveType.FIRE;
        }
        return MoveType.PHYSICAL;
    }

    public static MoveType expansionMoveType(String id, int skill) {
        return switch (normalize(id)) {
            case "lucario" -> skill==2?MoveType.NORMAL:MoveType.FIGHTING;
            case "abomasnow" -> MoveType.ICE;
            case "blaziken" -> skill==1?MoveType.FIGHTING:MoveType.FIRE;
            case "gengar" -> skill==0?MoveType.GHOST:skill==1?MoveType.POISON:MoveType.PSYCHIC;
            case "tyranitar" -> skill==1?MoveType.DARK:MoveType.ROCK;
            case "garchomp" -> skill==1 || skill==2?MoveType.GROUND:MoveType.DRAGON;
            case "scizor" -> skill==0?MoveType.STEEL:skill==3?MoveType.NORMAL:MoveType.BUG;
            case "gardevoir" -> skill==1?MoveType.FAIRY:MoveType.PSYCHIC;
            default -> MoveType.PHYSICAL;
        };
    }

    public static double multiplier(MoveType moveType, String defenderSpecies) {
        return multiplier(moveType == null ? "physical" : moveType.name(), defenderSpecies);
    }

    private TypeEffectiveness() {}

    /** Pure chart for focused tests; species may be bare or namespaced. */
    public static double multiplier(String moveType, String defenderSpecies) {
        String move = normalize(moveType);
        String species = normalize(defenderSpecies);
        if (move.isEmpty() || species.isEmpty()) return 1.0;
        double value = switch (species) {
            case "charizard" -> switch (move) {
                case "water" -> 1.5;
                case "fire", "grass" -> 0.75;
                default -> 1.0;
            };
            case "blastoise" -> switch (move) {
                case "grass", "electric" -> 1.5;
                case "fire", "water" -> 0.75;
                default -> 1.0;
            };
            case "venusaur" -> switch (move) {
                case "fire", "flying" -> 1.5;
                case "grass", "water" -> 0.75;
                // Poison is super-effective against grass but resisted by poison: neutral overall.
                case "poison" -> 1.0;
                default -> 1.0;
            };
            case "lucario" -> dual(move, "fighting", "steel");
            case "abomasnow" -> dual(move, "grass", "ice");
            case "blaziken" -> dual(move, "fire", "fighting");
            case "gengar" -> dual(move, "ghost", "poison");
            case "tyranitar" -> dual(move, "rock", "dark");
            case "garchomp" -> dual(move, "dragon", "ground");
            case "scizor" -> dual(move, "bug", "steel");
            case "gardevoir" -> dual(move, "psychic", "fairy");
            default -> 1.0;
        };
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
    }

    /** Resolve dual typing before applying the established 0.75..1.5 MOBA bounds.
     * Immunities deliberately use the resistance floor, not invulnerability.
     * The original four defender rows above are unchanged for balance compatibility. */
    private static double dual(String move, String first, String second) {
        return single(move, first) * single(move, second);
    }

    private static double single(String move, String defense) {
        String weak = switch (defense) {
            case "fighting" -> "flying psychic fairy";
            case "steel" -> "fire fighting ground";
            case "grass" -> "fire ice poison flying bug";
            case "ice" -> "fire fighting rock steel";
            case "fire" -> "water ground rock";
            case "ghost" -> "ghost dark";
            case "poison" -> "ground psychic";
            case "rock" -> "water grass fighting ground steel";
            case "dark" -> "fighting bug fairy";
            case "dragon" -> "ice dragon fairy";
            case "ground" -> "water grass ice";
            case "bug" -> "fire flying rock";
            case "psychic" -> "bug ghost dark";
            case "fairy" -> "poison steel";
            default -> "";
        };
        String resisted = switch (defense) {
            case "fighting" -> "bug rock dark";
            case "steel" -> "normal grass ice flying psychic bug rock dragon steel fairy";
            case "grass" -> "water electric grass ground";
            case "ice" -> "ice";
            case "fire" -> "fire grass ice bug steel fairy";
            case "ghost" -> "poison bug";
            case "poison" -> "grass fighting poison bug fairy";
            case "rock" -> "normal fire poison flying";
            case "dark" -> "ghost dark";
            case "dragon" -> "fire water electric grass";
            case "ground" -> "poison rock";
            case "bug" -> "grass fighting ground";
            case "psychic" -> "fighting psychic";
            case "fairy" -> "fighting bug dark";
            default -> "";
        };
        String immune = switch (defense) {
            case "steel" -> "poison";
            case "ghost" -> "normal fighting";
            case "dark" -> "psychic";
            case "ground" -> "electric";
            case "fairy" -> "dragon";
            default -> "";
        };
        // Raw canonical ratios preserve weakness/resistance cancellation for dual types.
        if (contains(immune, move)) return 0;
        if (contains(weak, move)) return 2;
        if (contains(resisted, move)) return .5;
        return 1;
    }

    private static boolean contains(String types, String move) {
        return !move.isEmpty() && (" " + types + " ").contains(" " + move + " ");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }
}

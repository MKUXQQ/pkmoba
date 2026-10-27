package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import java.util.Locale;

/** Bounded, explicit move-type chart for the three playable species. Unknown species are neutral. */
public final class TypeEffectiveness {
    public enum MoveType { FIRE, WATER, GRASS, FLYING, POISON, ELECTRIC, PHYSICAL }

    public static final double MIN_MULTIPLIER = 0.75;
    public static final double MAX_MULTIPLIER = 1.5;

    public static MoveType forAttackerSpecies(String speciesId) {
        String species = normalize(speciesId);
        return switch (species) {
            case "charizard" -> MoveType.FIRE;
            case "blastoise" -> MoveType.WATER;
            case "venusaur" -> MoveType.GRASS;
            case "pikachu" -> MoveType.ELECTRIC;
            default -> MoveType.PHYSICAL;
        };
    }

    public static MoveType moveType(PokemonEntity entity, int skill) {
        if (CharizardManager.isPikachu(entity)) return MoveType.ELECTRIC;
        if (CharizardManager.isVenusaur(entity)) return skill == 3 ? MoveType.POISON : MoveType.GRASS;
        if (CharizardManager.isBlastoise(entity)) return MoveType.WATER;
        if (entity != null && entity.getPokemon() != null
                && CharizardManager.CHARIZARD_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier())) {
            return skill == 2 ? MoveType.FLYING : MoveType.FIRE;
        }
        return MoveType.PHYSICAL;
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
            default -> 1.0;
        };
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }
}

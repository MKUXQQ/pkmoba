package com.pkmoba.duel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure-Java validation and password helpers for room rule tests. */
public final class RoomRules {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RoomRules() { }

    public static int capacity(int requested) {
        if (requested < 1 || requested > com.pkmoba.pokemon.SpeciesRegistry.ids().size())
            throw new IllegalArgumentException("capacity exceeds registered species count");
        return requested;
    }

    /** Display-only alias: never use this value to look up or persist an arena. */
    public static String arenaDisplayName(String arena) {
        return "arena1".equalsIgnoreCase(arena) ? "一号竞技场" : arena;
    }

    public static String displayName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > 32 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("room name must be at most 32 characters");
        return value;
    }

    public static List<String> roster(List<String> requested, List<String> owned,
                                      List<String> allowedSpecies, int capacity) {
        capacity(capacity);
        if (requested == null || requested.size() > capacity) throw new IllegalArgumentException("roster exceeds capacity");
        Set<String> unique = new LinkedHashSet<>();
        for (String id : requested) {
            if (id == null || !owned.contains(id)) throw new IllegalArgumentException("roster contains unowned species");
            // 0.86: a saved roster may still name a species that has since been removed from the game;
            // drop it instead of blocking the whole room on a stale entry.
            if (!allowedSpecies.isEmpty() && !allowedSpecies.contains(id)) continue;
            if (!unique.add(id)) throw new IllegalArgumentException("roster contains duplicates");
        }
        return List.copyOf(unique);
    }

    public static List<String> species(List<String> requested, List<String> knownSpecies) {
        if (requested == null || requested.size() > knownSpecies.size()) throw new IllegalArgumentException("invalid species rule");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : requested) {
            if (!knownSpecies.contains(id) || !unique.add(id)) throw new IllegalArgumentException("invalid species rule");
        }
        return List.copyOf(unique);
    }

    public static String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String passwordHash(String password, String salt) {
        if (password == null || password.length() > 32) throw new IllegalArgumentException("password must be at most 32 characters");
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), 65536, 256);
            try {
                return HexFormat.of().formatHex(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded());
            } finally { spec.clearPassword(); }
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public static boolean verifyPassword(String password, String salt, String expectedHash) {
        if (expectedHash == null || expectedHash.isEmpty()) return true;
        if (password == null || password.length() > 32 || salt == null || salt.isEmpty()) return false;
        return MessageDigest.isEqual(passwordHash(password, salt).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public static List<String> parseRoster(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(",", -1)) result.add(part.trim());
        return result;
    }
}

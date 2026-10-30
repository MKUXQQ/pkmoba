import com.pkmoba.duel.RoomRules;
import java.util.List;

/** Runs against production RoomRules, no Minecraft/JUnit dependencies. */
public final class RoomRulesCheck {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var owned = List.of("cobblemon:charizard", "cobblemon:blastoise", "cobblemon:venusaur", "cobblemon:pikachu");
        int registered = com.pkmoba.pokemon.SpeciesRegistry.ids().size();
        check(RoomRules.capacity(1) == 1 && RoomRules.capacity(registered) == registered, "capacity endpoints");
        check(RoomRules.capacity(7) == 7, "capacity beyond old six cap");
        var allSpecies = com.pkmoba.pokemon.SpeciesRegistry.ids();
        check(RoomRules.roster(allSpecies, allSpecies, allSpecies, registered).equals(allSpecies), "full registry roster accepted");
        check(RoomRules.species(allSpecies, allSpecies).size() == registered, "full species allowlist accepted");
        check(RoomRules.arenaDisplayName("arena1").equals("一号竞技场"), "display alias");
        check(RoomRules.arenaDisplayName("Arena1").equals("一号竞技场"), "case insensitive alias");
        check(RoomRules.arenaDisplayName("custom").equals("custom"), "custom arena unchanged");
        rejects(() -> RoomRules.capacity(0), "zero capacity");
        rejects(() -> RoomRules.capacity(registered + 1), "oversized capacity");
        rejects(() -> RoomRules.capacity(-1), "negative capacity");
        check(RoomRules.displayName("  test room  ").equals("test room"), "name trimming");
        check(RoomRules.displayName(null).isEmpty(), "empty default name");
        rejects(() -> RoomRules.displayName("x".repeat(33)), "overlong name");
        rejects(() -> RoomRules.displayName("one\ntwo"), "multiline name");
        rejects(() -> RoomRules.displayName("one\rtwo"), "carriage return name");
        var order = List.of(owned.get(3), owned.get(1), owned.get(0));
        var result = RoomRules.roster(order, owned, List.of(), 3);
        check(result.equals(order), "first slot and order preserved");
        checks++;
        try { result.add(owned.get(2)); throw new AssertionError("mutable accepted roster"); }
        catch (UnsupportedOperationException expected) { /* immutable server snapshot */ }
        check(owned.size() == 4, "collection not altered");
        check(RoomRules.roster(List.of(), owned, List.of(), 3).isEmpty(), "empty draft may be saved");
        rejects(() -> RoomRules.roster(owned, owned, List.of(), 3), "over capacity draft");
        rejects(() -> RoomRules.roster(List.of(owned.get(0), owned.get(0)), owned, List.of(), 3), "duplicates");
        rejects(() -> RoomRules.roster(List.of("cobblemon:mew"), owned, List.of(), 3), "unowned entry");
        rejects(() -> RoomRules.roster(List.of(owned.get(0)), owned, List.of(owned.get(1)), 3), "species allowlist");
        check(RoomRules.parseRoster(String.join(",", order)).equals(order), "wire order");
        check(RoomRules.parseRoster("").isEmpty(), "empty wire draft");
        check(RoomRules.verifyPassword("anything", "", ""), "public room");
        String salt = RoomRules.newSalt(), otherSalt = RoomRules.newSalt();
        check(!salt.equals(otherSalt), "unique salts");
        String hash = RoomRules.passwordHash("room-secret", salt);
        check(!hash.equals("room-secret"), "no plaintext hash");
        check(RoomRules.verifyPassword("room-secret", salt, hash), "correct password");
        check(!RoomRules.verifyPassword("wrong", salt, hash), "wrong password");
        check(!RoomRules.verifyPassword(null, salt, hash), "missing password");
        check(!RoomRules.verifyPassword("x".repeat(33), salt, hash), "oversized join password rejected safely");
        rejects(() -> RoomRules.passwordHash("x".repeat(33), salt), "oversized password validation");
        check(!RoomRules.verifyPassword("room-secret", "", hash), "missing salt");
        check(!RoomRules.passwordHash("room-secret", otherSalt).equals(hash), "salt changes hash");
        for (int mode = 0; mode <= 2; mode++) {
            for (boolean started : new boolean[]{false, true}) {
                var hidden = com.pkmoba.duel.RosterVisibility.filter(order, false, mode, started, java.util.Set.of(order.get(0)));
                check(hidden.size() == order.size(), "redaction preserves slot count");
                for (int i = 0; i < order.size(); i++) {
                    boolean visible = mode == 0 || mode == 1 && started || mode == 2 && i == 0;
                    check(hidden.get(i).equals(visible ? order.get(i) : "hidden"), "visibility mode " + mode);
                }
                check(com.pkmoba.duel.RosterVisibility.filter(order, true, mode, started, java.util.Set.of()).equals(order), "owner sees own roster");
            }
        }
        rejects(() -> com.pkmoba.duel.RosterVisibility.validate(3), "invalid disclosure mode");
        rejects(() -> com.pkmoba.duel.RosterVisibility.validate(-1), "negative disclosure mode");
        System.out.println("PASS: " + checks + " production room/password/roster visibility checks");
    }
}

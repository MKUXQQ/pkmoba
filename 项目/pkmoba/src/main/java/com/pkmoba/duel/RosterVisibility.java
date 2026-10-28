package com.pkmoba.duel;

import java.util.List;
import java.util.Set;

/** Server-side redaction: hidden identities never enter the recipient's packet. */
public final class RosterVisibility {
    public static final String HIDDEN = "hidden";
    private RosterVisibility() {}
    public static int validate(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("visibility must be 0..2");
        return mode;
    }
    public static List<String> filter(List<String> roster, boolean owner, int mode,
                                      boolean started, Set<String> revealed) {
        validate(mode);
        if (owner || mode == 0 || mode == 1 && started) return List.copyOf(roster);
        return roster.stream().map(id -> mode == 2 && revealed.contains(id) ? id : HIDDEN).toList();
    }
}

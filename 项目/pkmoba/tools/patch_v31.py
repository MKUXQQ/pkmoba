"""0.101 batch 2: quake feel/shake/plates, single hypnosis badge, CD clamp, switch-zone fix, Q steering."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def sub(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"anchor missing in {path}:\n{old[:140]}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# ------------------------------------------------------------------ HitFeedback
HF = "src/main/java/com/pkmoba/client/HitFeedback.java"
sub(HF, "    private static long lastEpicSlamTick", "    private static long lastEarthquakeTick\n = Long.MIN_VALUE;\n    private static long lastEpicSlamTick")
sub(HF, """    /** One camera shake per locally controlled Pikachu R cast, independent of target hits. */""",
    """    /**
     * 0.101 (m15488): Earthquake needs a real screen shake with distance falloff, a sharp first
     * kick and a decaying tail. It is driven by the server's quake beats, so the shake starts, peaks
     * and fades with the actual rupture instead of buzzing randomly for the whole cast.
     */
    public static void onEarthquake(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || at == null) return;
        double dist = mc.player.position().distanceTo(at);
        if (dist > 26.0) return;
        long now = mc.level.getGameTime();
        if (now - lastEarthquakeTick < 4) return;
        lastEarthquakeTick = now;
        Vec3 away = mc.player.position().subtract(at);
        if (away.lengthSqr() < 1.0E-4) away = Vec3.ZERO; else away = away.normalize();
        double strength = Math.max(.22, 1.0 - dist / 26.0);
        addShake(EffectPayload.Power.HEAVY, away.scale(strength));
        if (dist < 12.0) beginHitstop(EffectPayload.Power.LIGHT);
    }

    /** One camera shake per locally controlled Pikachu R cast, independent of target hits. */""")

# ------------------------------------------------------------------ ExpansionEffects
EE = "src/main/java/com/pkmoba/client/ExpansionEffects.java"
sub(EE, """            // The badge over a hypnotised target must appear even before the effect packet lands (and
            // for any living entity, not just Pokemon), so the mark/sleep cues seed a client mirror.
            if (species.id().equals("gengar")) {
                if (phase.equals("sleep")) {
                    HypnoClientMarks.apply(mc.level, pos, 3, true);
                } else if (phase.startsWith("mark")) {
                    int marks = phase.length() > 4 && Character.isDigit(phase.charAt(4)) ? phase.charAt(4) - '0' : 1;
                    HypnoClientMarks.apply(mc.level, pos, marks, false);
                }
            }""", """            // 0.101 (m15488): the hypnosis badge is owned by the server's StatusBadges stream only.
            // Mirroring the cue here created a second icon on the victim's head, so it is gone.""")
sub(EE, """            if (phase.equals("start")) animate(entity, species.id(), skill, "_windup");""",
    """            if (phase.equals("quake")) HitFeedback.onEarthquake(pos);
            if (phase.equals("start")) animate(entity, species.id(), skill, "_windup");""")

# ------------------------------------------------------------------ CD clamp
ER = "src/main/java/com/pkmoba/combat/ExpansionRoster.java"
sub(ER, """    private static Move m(String name, Shape shape, int cost, int seconds, int windup,""",
    """    private static Move m(String name, Shape shape, int cost, int seconds, int windup,""")
print("batch2 skeleton")

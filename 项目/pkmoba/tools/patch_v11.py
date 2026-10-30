"""Patch v11: Blaziken full rework (no mark loop) + Gardevoir Psychic hold-then-explode."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------- roster
roster = ROOT / "src/main/java/com/pkmoba/combat/ExpansionRoster.java"
s = roster.read_text(encoding="utf-8")
s = s.replace('m("精神强念", Shape.POINT, 2, 5, 4, 18, 13, 4.0, 14),',
              'm("精神强念", Shape.POINT, 2, 5, 4, 32, 13, 4.0, 14),')
roster.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------- combat
combat = ROOT / "src/main/java/com/pkmoba/combat/ExpansionCombat.java"
src = combat.read_text(encoding="utf-8")

start = src.index("    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {")
end = src.index("    private static void gengar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {")
new_blaziken = '''    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        // 0.76 rework: the old Flame Mark loop is gone. Each move now just does its own thing, so
        // the fire visuals (crescent kick, paired slashes, burning comet) match what the skill does.
        switch (s.skill) {
            case 0 -> { // 火焰踢: one heavy burning kick that stops on the first body it reaches.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, true);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    LivingEntity target = s.target;
                    hit(l, e, s, target, m.damage() + 2, 1);
                    cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                }
            }
            case 1 -> { // 二连踢: two kicks in a row, the second one is heavier.
                if (t == 1 || t == 8) {
                    boolean second = t == 8;
                    if (second) ExpansionGeometry.move(e, s.direction.scale(.55));
                    cue(e, s, "attack", e.position(), s.direction);
                    float amount = second ? m.damage() * .72F : m.damage() * .4F;
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m))
                        meleeHit(l, e, s, target, amount, 2);
                }
            }
            case 2 -> { // 蓄能焰袭: dash through the target, then +25% movement for four seconds.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, false);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    Vec3 at = s.target.getBoundingBox().getCenter();
                    hit(l, e, s, s.target, m.damage() + 2, 1);
                    s.speed = BLAZE_HASTE_TICKS;
                    cue(e, s, "impact", at, s.direction);
                    cue(e, s, "haste", e.position(), s.direction);
                }
            }
            default -> { // 闪焰冲锋: long charge, heavy contact, then a fire burst on the victim.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, true);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    LivingEntity target = s.target;
                    Vec3 at = target.getBoundingBox().getCenter();
                    hit(l, e, s, target, m.damage(), 1);
                    cue(e, s, "impact", at, s.direction);
                    for (LivingEntity other : around(l, e, target.position(), 2.4))
                        if (other != target) hit(l, e, s, other, 16, 1);
                }
            }
        }
    }
'''
src = src[:start] + new_blaziken + src[end:]

# Gardevoir Q: drag only, hold at the centre, then one explosion.
old_q = """                if (t <= 16) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius() + 1.5)) {
                        if (target == e) continue;
                        Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
                        double d = to.length();
                        if (d < 1.0) continue;
                        // Slow drag that grows the closer the victim already is to the centre.
                        double speed = .11 + .58 * Math.max(0, 1 - d / (m.radius() + .6));
                        Vec3 step = to.scale(Math.min(.85, speed) / d);
                        target.setDeltaMovement(step.x, Math.max(0, target.getDeltaMovement().y * .2),
                                step.z);
                        target.hurtMarked = true;
                    }
                }
                if (t == 18 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }"""
new_q = """                if (t <= 16) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);
                    dragToCentre(l, e, s, m);
                } else if (t <= 26) {
                    // Hold: everything caught by the well sits at the centre for half a second.
                    if (t % 3 == 0) cue(e, s, "hold", s.point, s.direction);
                    dragToCentre(l, e, s, m);
                } else if (t == 27 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, Math.min(3.2, m.radius())))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }"""
assert old_q in src
src = src.replace(old_q, new_q)

helper = '''    /** Psychic drag: no damage, the pull only gets stronger the closer a victim already is. */
    private static void dragToCentre(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m) {
        for (LivingEntity target : around(l, e, s.point, m.radius() + 1.5)) {
            if (target == e) continue;
            Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
            double d = to.length();
            if (d < 1.0) continue;
            double speed = .11 + .58 * Math.max(0, 1 - d / (m.radius() + .6));
            Vec3 step = to.scale(Math.min(.85, speed) / d);
            target.setDeltaMovement(step.x, Math.max(0, target.getDeltaMovement().y * .2), step.z);
            target.hurtMarked = true;
        }
    }

'''
anchor = "    private static void gengar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {"
src = src.replace(anchor, helper + anchor)

combat.write_text(src, encoding="utf-8")
print("patched combat + roster")

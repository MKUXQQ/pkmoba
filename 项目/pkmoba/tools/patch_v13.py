"""0.80 server pass: Blaziken's fire-foot kit.

* Q still throws a burning kick (now faster) and brands the victim.
* W is two lunging kicks with alternating fire arcs; a branded victim detonates.
* E charges in place for 6 ticks, then dashes, cueing a low fire-wall segment every tick.
* R leaps through the air (teleport along a parabola) and lands exactly on the clicked point.

Run from the project root: python tools/patch_v13.py
"""
from pathlib import Path

COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")
EFFECTS = Path("src/main/java/com/pkmoba/client/ExpansionEffects.java")

METHOD = '''    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        // 0.80 fire-foot kit: Q brands with a thrown burning foot, W double-kicks it, E dashes and
        // paints a low fire wall behind itself, R leaps through the air onto the clicked point.
        switch (s.skill) {
            case 0 -> { // 火焰踢: a burning foot is thrown forward; the hit brands the victim.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "kick", launchPoint(e, s.direction), s.direction);
                }
                projectile(l, e, s, m, 1.35, 1.8, 0);
                if (t > 1) {
                    for (UUID id : s.hit.keySet()) {
                        if (l.getEntity(id) instanceof LivingEntity victim && victim.isAlive()
                                && blazeMark(victim))
                            cue(e, s, "brand", victim.position(), s.direction);
                    }
                }
            }
            case 1 -> { // 二连踢: two lunging kicks; a branded victim detonates and refunds Q.
                if (t == 1 || t == 9) {
                    boolean second = t == 9;
                    ExpansionGeometry.move(e, s.direction.scale(second ? .7 : .4));
                    cue(e, s, "attack", e.position(), s.direction);
                    float amount = second ? m.damage() * .75F : m.damage() * .4F;
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        float total = amount;
                        if (takeBlazeMark(target)) {
                            total += 16;
                            s.cd[0] = Math.max(0, s.cd[0] - 40);
                            cue(e, s, "detonate", target.getBoundingBox().getCenter(), s.direction);
                        }
                        meleeHit(l, e, s, target, total, 2);
                    }
                }
            }
            case 2 -> { // 蓄能焰袭: charge in place, then dash leaving a low fire wall behind.
                final int windup = 6;
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                if (t <= windup) {
                    if (t % 2 == 1) cue(e, s, "warn", e.position(), s.direction);
                } else {
                    dash(l, e, s, m, t - windup, false);
                    cue(e, s, "trail", e.position(), s.direction);
                    if (s.target != null && !s.recoil) {
                        s.recoil = true;
                        Vec3 at = s.target.getBoundingBox().getCenter();
                        boolean branded = takeBlazeMark(s.target);
                        hit(l, e, s, s.target, m.damage() + 2, 1);
                        s.speed = BLAZE_HASTE_TICKS;
                        if (branded) {
                            s.speed = BLAZE_HASTE_TICKS * 2;
                            cue(e, s, "detonate", at, s.direction);
                        }
                        cue(e, s, "impact", at, s.direction);
                        cue(e, s, "haste", e.position(), s.direction);
                    }
                }
            }
            default -> { // 闪焰冲锋: leap through the air and land exactly on the clicked point.
                final int windup = 6;
                final int flight = 13;
                if (t == 1) {
                    s.origin = e.position();
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "attack", e.position(), s.direction);
                }
                if (t <= windup) {
                    cue(e, s, "warn", e.position(), s.direction);
                } else if (t <= windup + flight) {
                    // A real airborne somersault: the body is teleported along a parabola, and the
                    // last step is the clicked point itself, so the dive always arrives there.
                    double f = (t - windup) / (double) flight;
                    Vec3 from = s.origin;
                    Vec3 to = s.point;
                    double arc = 3.6 * Math.sin(Math.PI * f);
                    Vec3 wanted = new Vec3(from.x + (to.x - from.x) * f,
                            from.y + (to.y - from.y) * f + arc,
                            from.z + (to.z - from.z) * f);
                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    cue(e, s, "charge", wanted, s.direction);
                } else if (t == windup + flight + 1 && !s.stopped) {
                    s.stopped = true;
                    e.teleportTo(s.point.x, s.point.y, s.point.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "impact", s.point, s.direction);
                    boolean branded = false;
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        float damage = m.damage();
                        if (takeBlazeMark(target)) {
                            damage = damage * 1.6F + 20F;
                            branded = true;
                        }
                        hit(l, e, s, target, damage, 1);
                    }
                    if (branded) {
                        cue(e, s, "detonate", s.point, s.direction);
                        for (LivingEntity target : around(l, e, s.point, m.radius() * 1.5))
                            hit(l, e, s, target, 12, 1);
                    }
                    finish(e, s);
                }
            }
        }
    }
'''


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    start = text.index("    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t,")
    end = text.index("    /** Psychic drag:", start)
    COMBAT.write_text(text[:start] + METHOD + "\n" + text[end:], encoding="utf-8")
    print("patched ExpansionCombat.blaziken")


def patch_effects() -> None:
    text = EFFECTS.read_text(encoding="utf-8")
    if 'case "trail" ->' not in text:
        text = text.replace('                case "kick" -> 16;',
                            '                case "kick" -> 16;\n'
                            '                // 0.80 蓄能焰袭 leaves one low fire-wall segment per dash step.\n'
                            '                case "trail" -> 26;')
        EFFECTS.write_text(text, encoding="utf-8")
        print("patched ExpansionEffects trail life")
    else:
        print("trail life already present")


if __name__ == "__main__":
    patch_combat()
    patch_effects()

"""0.90: new Alolan Ninetales kit (Icy Wind / Ice Beam / Aurora Veil / Blizzard), Lucario art swap,
and the regional-form recall fix."""
from pathlib import Path

ROOT = Path('src/main/java/com/pkmoba')

# ---------------------------------------------------------------- Lucario art swap
p = ROOT / 'client/fx/LucarioFx.java'
s = p.read_text(encoding='utf-8')
s = s.replace('tex("lucario_blade")', 'tex("lu_blade")')
s = s.replace('tex("lucario_bolt")', 'tex("lu_bolt")')
s = s.replace('tex("lucario_star")', 'tex("lu_star")')
s = s.replace('tex("lucario_band")', 'tex("lu_rock")')
s = s.replace('tex("lucario_fist")', 'tex("lu_core")')
s = s.replace('tex("lucario_glow")', 'tex("lu_core")')
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------- roster: new Ninetales kit
p = ROOT / 'combat/ExpansionRoster.java'
s = p.read_text(encoding='utf-8')
start = s.index('        new Species("alolan_ninetales"')
end = s.index('        new Species("lucario"')
s = s[:start] + '''        // 0.90 ice/fairy kit: fan of frost, channelled beam, self veil, falling storm.
        new Species("alolan_ninetales", "阿罗拉九尾", 310, List.of(
            m("冰冻之风", Shape.CONE, 1, 5, 3, 14, 7, 3.4, 20),
            m("冰冻光束", Shape.LINE, 2, 8, 4, 14, 13, 1.2, 34),
            m("极光幕", Shape.SELF, 2, 12, 4, 6, 3, 3.0, 0),
            m("暴风雪", Shape.POINT, 3, 14, 6, 18, 11, 4.2, 40))),
''' + s[end:]
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------- server kit
p = ROOT / 'combat/ExpansionCombat.java'
s = p.read_text(encoding='utf-8')
start = s.index('    private static void alolanNinetales(')
end = s.index('    /**\n     * 0.54 Blaziken kit')
s = s[:start] + '''    private static void alolanNinetales(ServerLevel l, PokemonEntity e, State s, int t,
                                       ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 冰冻之风: a fan of frost that chills and shoves everything in front.
                if (t <= 12 && t % 3 == 1) {
                    cue(e, s, "wind", s.point, s.direction);
                    for (LivingEntity target : cone(l, e, s, m)) {
                        if (hit(l, e, s, target, m.damage() * .28F, 3)) {
                            ExpansionControl.slow(e, target, 30, .55);
                            target.push(s.direction.x * .35, .12, s.direction.z * .35);
                            cue(e, s, "whip", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
            }
            case 1 -> { // 冰冻光束: one channelled beam from the body out to the aimed point.
                Vec3 start = launchPoint(e, s.direction);
                Vec3 end = ExpansionGeometry.clip(l, start, s.point);
                if (t <= 12) {
                    if (t % 3 == 1) cue(e, s, "beam", start, end);
                    if (t % 3 == 2) for (LivingEntity target : around(l, e, end, m.radius())) {
                        if (hit(l, e, s, target, m.damage() * .25F, 4)) {
                            ExpansionControl.slow(e, target, 25, .5);
                            target.setTicksFrozen(Math.min(160, target.getTicksFrozen() + 40));
                        }
                    }
                }
            }
            case 2 -> { // 极光幕: a self guard of drifting aurora light.
                if (t == 1) {
                    ExpansionControl.guard(e, 160, .35);
                    cue(e, s, "veil", e.position(), s.direction);
                }
                if (t == 2) finish(e, s);
            }
            default -> { // 暴风雪: a storm cell that keeps falling for six seconds.
                if (t == 1) {
                    s.snow = 120;
                    s.snowPoint = s.point;
                    cue(e, s, "field", s.point, s.direction);
                    finish(e, s);
                }
            }
        }
    }

''' + s[end:]
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------- regional form recall/switch fix
p = ROOT / 'pokemon/TeamService.java'
s = p.read_text(encoding='utf-8')
s = s.replace('''    public static String speciesOf(@Nullable PokemonEntity entity) {
        if (entity == null) return "";
        return entity.getPokemon().getSpecies().getResourceIdentifier().toString();
    }''',
'''    public static String speciesOf(@Nullable PokemonEntity entity) {
        if (entity == null) return "";
        var spawned = entity.getPokemon().getSpecies().getResourceIdentifier();
        // 0.90: regional forms spawn as their base species, so the profile/team key must be the
        // registry alias again. Without this an Alolan Ninetales could never be recalled or swapped
        // out: the recall looked up "cobblemon:ninetales" and silently left the old body in place.
        var local = SpeciesRegistry.localForCobble(spawned);
        return (local == null ? spawned : local).toString();
    }''')
p.write_text(s, encoding='utf-8')
print('patched roster/combat/teamservice/lucario art')

from pathlib import Path
import re

ROOT = Path('src/main/java/com/pkmoba')

# ---------------------------------------------------------------- species resolve
p = ROOT / 'combat/ExpansionRoster.java'
s = p.read_text(encoding='utf-8')
if 'public static Species resolve(' not in s:
    s = s.replace('    public static Species find(String id) {',
'''    /**
     * 0.88: regional forms spawn as their base Cobblemon species, so every client lookup (HUD, key
     * map, animations, effects) must resolve through the registry alias as well. Without this an
     * Alolan Ninetales entity was "unknown" on the client and the whole skill bar went dead.
     */
    public static Species resolve(String identifier) {
        Species found = find(identifier);
        if (found != null) return found;
        var id = net.minecraft.resources.ResourceLocation.tryParse(identifier);
        var local = com.pkmoba.pokemon.SpeciesRegistry.localForCobble(id);
        return local == null ? null : find(local.toString());
    }

    public static Species find(String id) {''', 1)
    p.write_text(s, encoding='utf-8')

for rel in ('client/SkillHud.java', 'client/SkillKeys.java', 'client/SkillPresentation.java',
            'combat/CharizardCombat.java', 'combat/Skill.java', 'client/ExpansionEffects.java',
            'combat/ExpansionCombat.java'):
    f = ROOT / rel
    s = f.read_text(encoding='utf-8')
    s2 = re.sub(r'(com\.pkmoba\.combat\.)?ExpansionRoster\.find\(', lambda m: (m.group(1) or '') + 'ExpansionRoster.resolve(', s)
    # keep the server's own two-step fallback intact: it already falls back to the registry
    if rel == 'combat/ExpansionCombat.java':
        s2 = s2.replace('ExpansionRoster.resolve(identifier.toString())', 'ExpansionRoster.find(identifier.toString())')
        s2 = s2.replace('return local == null ? null : ExpansionRoster.resolve(local.toString());',
                        'return local == null ? null : ExpansionRoster.find(local.toString());')
    if rel == 'combat/ExpansionRoster.java':
        pass
    if s2 != s:
        f.write_text(s2, encoding='utf-8')
        print('resolve ->', rel)

# ExpansionRoster.resolve itself must not be rewritten into a loop
s = (ROOT / 'combat/ExpansionRoster.java').read_text(encoding='utf-8')
s = s.replace('Species found = ExpansionRoster.resolve(identifier);', 'Species found = find(identifier);')
(ROOT / 'combat/ExpansionRoster.java').write_text(s, encoding='utf-8')

# ---------------------------------------------------------------- Extreme Speed dash
p = ROOT / 'combat/ExpansionCombat.java'
s = p.read_text(encoding='utf-8')
old = s[s.index('            default -> { // 神速: a long, snappy dash that lands one hit on contact.'):
        s.index('        }\n    }\n    /** Banks Aura')]
new = '''            default -> { // 神速: one clean snap along the aim line, then a single contact hit.
                if (t == 1) {
                    Vec3 start = e.position();
                    Vec3 step = ExpansionGeometry.direction(e, s.point);
                    double reach = Math.max(0, Math.min(m.range(), start.distanceTo(s.point)));
                    // Move in slices so a wall stops the leap instead of teleporting through it.
                    double left = reach;
                    while (left > 1E-3) {
                        double slice = Math.min(1.6, left);
                        double moved = ExpansionGeometry.move(e, step.scale(slice));
                        left -= Math.max(moved, .25);
                    }
                    cue(e, s, "start", start, e.position());
                    cue(e, s, "arrive", e.position(), step);
                    for (LivingEntity target : around(l, e, e.position(), Math.max(1.3, m.radius() + .5))) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            target.push(step.x * .5, .2, step.z * .5);
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), step);
                        }
                    }
                    finish(e, s);
                }
            }
'''
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('extreme speed rewritten')

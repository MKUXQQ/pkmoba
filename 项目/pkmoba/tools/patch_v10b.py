from pathlib import Path

combat = Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')
s = s.replace('''                if (t <= 18) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);''',
              '''                if (t <= 8) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);''')
s = s.replace('''                if (t == 20 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);''',
              '''                if (t == 10 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);''')
combat.write_text(s, encoding='utf-8')

roster = Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
r = r.replace('m("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 4.0, 14),',
              'm("精神强念", Shape.POINT, 2, 5, 4, 10, 13, 4.0, 14),')
r = r.replace('m("魔法闪耀", Shape.POINT, 2, 11, 6, 16, 7, 4.6, 34)',
              'm("魔法闪耀", Shape.SELF, 2, 11, 6, 16, 7, 5.0, 34)')
roster.write_text(r, encoding='utf-8')

bar = Path('src/main/java/com/pkmoba/client/PokemonOverheadBar.java')
b = bar.read_text(encoding='utf-8')
b = b.replace('private static final float WORLD_SCALE = 0.0185F;', 'private static final float WORLD_SCALE = 0.0155F;')
b = b.replace('private static final int BAR_WIDTH = 54;', 'private static final int BAR_WIDTH = 46;')
b = b.replace('private static final int BAR_HEIGHT = 5;', 'private static final int BAR_HEIGHT = 4;')
b = b.replace('private static final double BAR_LIFT = 0.30;', 'private static final double BAR_LIFT = 0.62;')
bar.write_text(b, encoding='utf-8')

fx = Path('src/main/java/com/pkmoba/client/fx/GardevoirFx.java')
f = fx.read_text(encoding='utf-8')
f = f.replace('''            double radius = 2.3;
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);''',
              '''            double radius = 3.4;
            // The moon is a real volumetric body: a dim shell for the limb, the lit skin, a hot core.
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius * 1.03, radius * 1.03, radius * 1.03),
                    v.time * .3, MOON_CLASSIC, a * .5f, true);
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);''')
f = f.replace('''            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius * .52, radius * .52, radius * .52),
                    -v.time * .4, GLOW, a * .6f, true);''',
              '''            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius * .5, radius * .5, radius * .5),
                    -v.time * .4, GLOW, a * .55f, true);
            face(c, FLASH, v.pos, radius * 2.9, radius * 2.9, v.time * .4, a * .55f);''')
f = f.replace('double shrink = 1 - i * .17;', 'double shrink = 1 - i * .15;')
fx.write_text(f, encoding='utf-8')
print('patched v10b')

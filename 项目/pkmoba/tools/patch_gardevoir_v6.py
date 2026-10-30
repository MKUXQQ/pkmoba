import pathlib

combat = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')
start = s.index('    private static void gardevoir(')
end = s.index('    /** Authority uses model-scaled emission offsets', start)
new = '''    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 精神强念 Psychic: the aim point is crushed in place. Lock on, space closes in, then
                // it implodes - a pin-point slam instead of a travelling ring.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t >= 2 && t <= 8 && t % 2 == 0) cue(e, s, "crush", s.point, s.direction);
                if (t == 10 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
            case 1 -> {
                // 月亮之力 Moonblast: a real 3D moon drops straight down onto the aim point, so the
                // cast is a heavy vertical slam - the same 3D model the earlier versions used.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 26) {
                    double f = Math.min(1, t / 26.0);
                    Vec3 top = s.point.add(0, 11.0, 0);
                    cue(e, s, "moon", top.lerp(s.point, f * f), s.direction);
                } else if (t == 27 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
            case 2 -> {
                // 瞬间移动 Teleport: leave a phantom trail, appear under a ring of light.
                if (t == 1) {
                    Vec3 start = e.position();
                    ExpansionGeometry.move(e, s.point.subtract(start).multiply(1, 0, 1));
                    cue(e, s, "blink", start, e.position());
                    finish(e, s);
                }
            }
            case 3 -> {
                // 魔法闪耀 Dazzling Gleam: a flash out of the caster's own body, no projectile and no
                // aim travel. It is a normal skill, so no ultimate shake or hitstop is attached.
                if (t == 1) cue(e, s, "start", e.position(), s.direction);
                if (t == 6 && !s.stopped) {
                    s.stopped = true;
                    Vec3 centre = e.position().add(0, 1.0, 0);
                    cue(e, s, "impact", centre, s.direction);
                    for (LivingEntity target : around(l, e, centre, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
        }
    }
'''
s = s[:start] + new + s[end:]
combat.write_text(s, encoding='utf-8')

roster = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
old = '''            m("精神强念", Shape.LINE, 2, 4, 6, 30, 16, 2.0, 26),
            m("月亮之力", Shape.POINT, 3, 8, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 9, 4, 6, 10, .5, 0),
            m("巨声", Shape.LINE, 3, 20, 8, 24, 16, 2.2, 40)))'''
new_r = '''            m("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 2.6, 28),
            m("月亮之力", Shape.POINT, 3, 8, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 9, 4, 6, 10, .5, 0),
            m("魔法闪耀", Shape.POINT, 2, 11, 6, 16, 7, 4.6, 34)))'''
assert old in r
r = r.replace(old, new_r)
roster.write_text(r, encoding='utf-8')

zh = pathlib.Path('src/main/resources/assets/pkmoba/lang/zh_cn.json')
z = zh.read_text(encoding='utf-8').replace('"skill.pkmoba.gardevoir.r": "巨声"', '"skill.pkmoba.gardevoir.r": "魔法闪耀"')
zh.write_text(z, encoding='utf-8')
en = pathlib.Path('src/main/resources/assets/pkmoba/lang/en_us.json')
u = en.read_text(encoding='utf-8').replace('"skill.pkmoba.gardevoir.r": "Hyper Voice"', '"skill.pkmoba.gardevoir.r": "Dazzling Gleam"')
en.write_text(u, encoding='utf-8')

# The gleam is a normal skill: drop the heavy slam shake that used to fire on both W and R.
effects = pathlib.Path('src/main/java/com/pkmoba/client/ExpansionEffects.java')
x = effects.read_text(encoding='utf-8')
old_hook = '''                if (species.id().equals("gardevoir") && (skill == 1 || skill == 3))
                    HitFeedback.onEpicSlam(pos);'''
new_hook = '''                if (species.id().equals("gardevoir") && skill == 1)
                    HitFeedback.onEpicSlam(pos);'''
assert old_hook in x
x = x.replace(old_hook, new_hook)
old_style = '''            case "gardevoir" -> switch (skill) { case 0 -> "PSYCHIC"; case 1 -> "MOON"; case 2 -> "BLINK"; default -> "MOON"; };'''
new_style = '''            case "gardevoir" -> switch (skill) { case 0 -> "PSYCHIC"; case 1 -> "MOON"; case 2 -> "BLINK"; default -> "GLEAM"; };'''
assert old_style in x
x = x.replace(old_style, new_style)
effects.write_text(x, encoding='utf-8')
print('gardevoir v6 server + lang + effects patched')

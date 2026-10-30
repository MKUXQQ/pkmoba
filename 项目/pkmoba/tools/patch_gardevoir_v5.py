import pathlib

combat = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')
start = s.index('    private static void gardevoir(')
end = s.index('    /** Authority uses model-scaled emission offsets', start)
new = '''    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 精神强念 Psychic: two stepped rings travel forward and crush what they pass.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 30) {
                    Vec3 from = launchPoint(e, s.direction);
                    Vec3 front = from.add(s.direction.scale(Math.min(m.range(), t * .95)));
                    if (t % 2 == 0) cue(e, s, "wave", front, s.direction);
                    for (LivingEntity target : around(l, e, front, 2.0)) {
                        if (hit(l, e, s, target, m.damage() * (1 - .25F * (t / 30F)), 1))
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 1 -> {
                // 月亮之力 Moonblast: the moon drops straight down onto the aim point. No parabola
                // and no lobbed ball - a heavy vertical slam with a landing shockwave.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 26) {
                    double f = Math.min(1, t / 26.0);
                    Vec3 top = s.point.add(0, 10.0, 0);
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
                // 巨声 Hyper Voice: a wall of sound rolls forward from the mouth. Stacked arc cues
                // make the shout read as movement, and the wavefront is what damages.
                if (t == 1) cue(e, s, "start", launchPoint(e, s.direction), s.direction);
                if (t <= 22) {
                    Vec3 from = launchPoint(e, s.direction);
                    Vec3 front = from.add(s.direction.scale(Math.min(m.range(), t * .95)));
                    if (t % 2 == 0) cue(e, s, "sound", front, s.direction);
                    for (LivingEntity target : around(l, e, front, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                }
            }
        }
    }
'''
s = s[:start] + new + s[end:]
combat.write_text(s, encoding='utf-8')

roster = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
old = '''            m("预知未来", Shape.POINT, 3, 20, 8, 40, 16, 4.0, 44)))'''
new_r = '''            m("巨声", Shape.LINE, 3, 20, 8, 24, 16, 2.2, 40)))'''
assert old in r
r = r.replace(old, new_r)
roster.write_text(r, encoding='utf-8')

zh = pathlib.Path('src/main/resources/assets/pkmoba/lang/zh_cn.json')
z = zh.read_text(encoding='utf-8').replace('"skill.pkmoba.gardevoir.r": "预知未来"', '"skill.pkmoba.gardevoir.r": "巨声"')
zh.write_text(z, encoding='utf-8')
en = pathlib.Path('src/main/resources/assets/pkmoba/lang/en_us.json')
u = en.read_text(encoding='utf-8').replace('"skill.pkmoba.gardevoir.r": "Future Sight"', '"skill.pkmoba.gardevoir.r": "Hyper Voice"')
en.write_text(u, encoding='utf-8')

effects = pathlib.Path('src/main/java/com/pkmoba/client/ExpansionEffects.java')
x = effects.read_text(encoding='utf-8')
old_life = '''                case "mark" -> 16;'''
new_life = '''                case "mark" -> 16;
                // 巨声: the arc wave is re-cued every other tick while it travels.
                case "sound" -> 12;'''
assert old_life in x
x = x.replace(old_life, new_life, 1)
effects.write_text(x, encoding='utf-8')
print('gardevoir v5 server + lang + effects patched')

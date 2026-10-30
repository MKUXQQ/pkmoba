import pathlib

combat = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')

start = s.index('    private static void gardevoir(')
end = s.index('    /** Authority uses model-scaled emission offsets', start)
new = '''    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 精神强念 Psychic: stepped psychic rings travel forward and crush what they pass.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 30) {
                    Vec3 from = launchPoint(e, s.direction);
                    Vec3 front = from.add(s.direction.scale(Math.min(m.range(), t * .95)));
                    cue(e, s, "wave", front, s.direction);
                    for (LivingEntity target : around(l, e, front, 2.0)) {
                        if (hit(l, e, s, target, m.damage() * (1 - .25F * (t / 30F)), 1))
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 1 -> {
                // 月亮之力 Moonblast: the moon itself falls straight down, slowly, onto the aim
                // point. No parabola, no lobbed ball - it is a heavy drop with a shockwave landing.
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
                // 瞬间移动 Teleport: leave a rune, appear under a shaft of starlight.
                if (t == 1) {
                    Vec3 start = e.position();
                    ExpansionGeometry.move(e, s.point.subtract(start).multiply(1, 0, 1));
                    cue(e, s, "blink", start, e.position());
                    finish(e, s);
                }
            }
            case 3 -> {
                // 预知未来 Future Sight: the clock locks onto the spot first, then a psychic comet
                // drops out of the sky and detonates. The delay is what makes it readable and
                // dodgeable, like the real move landing two turns later.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t >= 2 && t <= 26 && t % 2 == 0) {
                    double f = Math.min(1, (t - 2) / 24.0);
                    Vec3 top = s.point.add(0, 13.0, 0);
                    cue(e, s, "comet", top.lerp(s.point, Math.pow(f, 1.6)), s.direction);
                }
                if (t == 28 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
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
old = '''        new Species("gardevoir", "沙奈朵", 260, List.of(
            // 0.68: the whole kit is rebuilt. A travelling psychic ripple, the moon itself dropped
            // straight down, a phantom step and a five star verdict - no orb, no parabola, and
            // nothing shared with Gengar's silhouette.
            m("心灵波纹", Shape.LINE, 2, 4, 6, 30, 16, 1.9, 24),
            m("月亮之力", Shape.POINT, 3, 7, 10, 26, 15, 3.0, 40),
            m("幻影步", Shape.BLINK, 2, 9, 4, 6, 10, .5, 0),
            m("星之裁决", Shape.POINT, 3, 20, 8, 40, 16, 4.0, 34)))'''
new_r = '''        new Species("gardevoir", "沙奈朵", 260, List.of(
            // 0.69: official Gardevoir moves only (Gen VIII learnset): Psychic, Moonblast, Teleport
            // and Future Sight. Every visual is rebuilt from chunky 32px pixel sheets.
            m("精神强念", Shape.LINE, 2, 4, 6, 30, 16, 2.0, 26),
            m("月亮之力", Shape.POINT, 3, 8, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 9, 4, 6, 10, .5, 0),
            m("预知未来", Shape.POINT, 3, 20, 8, 40, 16, 4.0, 44)))'''
assert old in r
r = r.replace(old, new_r)
roster.write_text(r, encoding='utf-8')

zh = pathlib.Path('src/main/resources/assets/pkmoba/lang/zh_cn.json')
z = zh.read_text(encoding='utf-8')
z = z.replace('"skill.pkmoba.gardevoir.q": "心灵波纹"', '"skill.pkmoba.gardevoir.q": "精神强念"')
z = z.replace('"skill.pkmoba.gardevoir.e": "幻影步"', '"skill.pkmoba.gardevoir.e": "瞬间移动"')
z = z.replace('"skill.pkmoba.gardevoir.r": "星之裁决"', '"skill.pkmoba.gardevoir.r": "预知未来"')
zh.write_text(z, encoding='utf-8')

en = pathlib.Path('src/main/resources/assets/pkmoba/lang/en_us.json')
u = en.read_text(encoding='utf-8')
u = u.replace('"skill.pkmoba.gardevoir.q": "Psy Ripple"', '"skill.pkmoba.gardevoir.q": "Psychic"')
u = u.replace('"skill.pkmoba.gardevoir.w": "Moonfall"', '"skill.pkmoba.gardevoir.w": "Moonblast"')
u = u.replace('"skill.pkmoba.gardevoir.e": "Phantom Step"', '"skill.pkmoba.gardevoir.e": "Teleport"')
u = u.replace('"skill.pkmoba.gardevoir.r": "Star Verdict"', '"skill.pkmoba.gardevoir.r": "Future Sight"')
en.write_text(u, encoding='utf-8')
print('server + roster + lang patched')

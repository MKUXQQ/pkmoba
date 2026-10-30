from pathlib import Path

combat = Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')

s = s.replace("int marks = ExpansionControl.addHypnoMark(e, target, 600);",
              "int marks = ExpansionControl.addHypnoMark(e, target, 200);")

old_q = """            case 0 -> {
                // 精神强念 Psychic: the aim point is crushed in place. Lock on, space closes in, then
                // it implodes - a pin-point slam instead of a travelling ring.
                if (t == 1) cue(e, s, "start", launchPoint(e, s.direction), s.direction);
                if (t <= 18) {
                    Vec3 from = launchPoint(e, s.direction);
                    Vec3 front = from.add(s.direction.scale(Math.min(m.range(), t * .85)));
                    if (t % 2 == 0) cue(e, s, "wave", front, s.direction);
                    for (LivingEntity target : around(l, e, front, m.radius()))
                        hit(l, e, s, target, m.damage() * (1 - .3F * (t / 20F)), 1);
                }
            }"""
new_q = """            case 0 -> {
                // 精神强念 Psychic: a gravity well at the aim point. Every creature inside it is
                // dragged towards the centre for the whole channel, then crushed once at the end.
                // Nothing is locked on: the well sits exactly where the player aimed.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 18) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius() + 1.5)) {
                        if (target == e) continue;
                        Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
                        double d = to.length();
                        if (d < 1.2) continue;
                        double speed = Math.min(.62, .14 + d * .07);
                        Vec3 step = to.scale(speed / d);
                        target.setDeltaMovement(step.x, Math.max(0, target.getDeltaMovement().y * .25),
                                step.z);
                        target.hurtMarked = true;
                    }
                }
                if (t == 20 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }"""
assert old_q in s
s = s.replace(old_q, new_q)

s = s.replace("Vec3 top = s.point.add(0, 11.0, 0);", "Vec3 top = s.point.add(0, 7.5, 0);")
combat.write_text(s, encoding='utf-8')

roster = Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
old = 'm("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 2.6, 28),'
new = 'm("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 6.0, 30),'
assert old in r
r = r.replace(old, new)
roster.write_text(r, encoding='utf-8')
print('patched combat + roster')

import pathlib

base = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = base.read_text(encoding='utf-8')

old = '''            case 2 -> {
                // 0.67 催眠光环：像喷射火焰一样持续喷出催眠光圈。每次命中 70% 概率催眠，
                // 同一目标第 3 次命中必定催眠（不再看运气）。
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 24 && t % 2 == 0) cue(e, s, "stream", s.point, s.direction);
                if (t <= 24 && (t - 1) % 4 == 0) {
                    Vec3 from = launchPoint(e, s.direction);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, s.point, Math.max(.9, m.radius()))) {
                        int lit = s.hit.merge(target.getUUID(), 1, Integer::sum);
                        cue(e, s, "lit" + Math.min(3, lit), target.getBoundingBox().getCenter(), s.direction);
                        boolean third = lit >= 3;
                        if (third || l.random.nextFloat() < .70F) {
                            if (ExpansionControl.sleep(e, target, 100))
                                cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
            }'''
new = '''            case 2 -> {
                // 0.68 催眠术: a two second spray of hypnotic rings. Each spray leaves at most one
                // mark on a victim; the third mark puts it to sleep, so it always takes three casts
                // against the same target and no lucky single hit can end the fight.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 38 && t % 2 == 0) cue(e, s, "stream", s.point, s.direction);
                if (t <= 38 && (t - 1) % 4 == 0) {
                    Vec3 from = launchPoint(e, s.direction);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, s.point, Math.max(.9, m.radius()))) {
                        if (s.marked.contains(target.getUUID())) continue;
                        s.marked.add(target.getUUID());
                        int marks = ExpansionControl.addHypnoMark(e, target, 600);
                        cue(e, s, "mark" + Math.min(3, marks), target.getBoundingBox().getCenter(), s.direction);
                        if (marks >= 3 && ExpansionControl.sleep(e, target, 100)) {
                            ExpansionControl.clearHypno(target);
                            cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
            }'''
assert old in s, 'gengar hypnosis block not found'
s = s.replace(old, new)

old_r = '''                } else if (t == 6 && !s.recoil && s.target != null && s.target.isAlive()) {
                    s.recoil = true;
                    float drainDamage = s.dream ? m.damage() * 1.4F : m.damage();
                    if (hit(l, e, s, s.target, drainDamage, 1)) {
                        cue(e, s, "melee", s.target.getBoundingBox().getCenter(), s.direction);
                        if (s.dream) {
                            e.heal(18);
                            cue(e, s, "heal", s.target.position(), e.position());
                        }
                    }
                }'''
new_r = '''                } else if (t == 6 && !s.recoil && s.target != null && s.target.isAlive()) {
                    s.recoil = true;
                    // 食梦 only drains a sleeping victim: heavy damage plus health back to Gengar.
                    // Anything awake only takes a light hit and gives nothing back.
                    boolean asleep = ExpansionControl.sleeping(s.target);
                    float drainDamage = asleep ? m.damage() * 1.5F : m.damage() * .5F;
                    if (hit(l, e, s, s.target, drainDamage, 1)) {
                        cue(e, s, "melee", s.target.getBoundingBox().getCenter(), s.direction);
                        if (asleep) {
                            float healed = Math.min(e.getMaxHealth() - e.getHealth(), drainDamage * .8F);
                            if (healed > 0) e.heal(healed);
                            ExpansionControl.wake(s.target);
                            cue(e, s, "heal", s.target.position(), e.position());
                        }
                    }
                }'''
assert old_r in s, 'gengar dream eater block not found'
s = s.replace(old_r, new_r)

start = s.index('    private static void gardevoir(')
end = s.index('    /** Authority uses model-scaled emission offsets', start)
new_g = '''    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 心灵波纹: a psychic ripple runs forward from the palm and shoves everything it
                // passes; no projectile orb, so it cannot be confused with a Shadow Ball reskin.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 30) {
                    Vec3 from = launchPoint(e, s.direction);
                    Vec3 front = from.add(s.direction.scale(Math.min(m.range(), t * .95)));
                    cue(e, s, "wave", front, s.direction);
                    for (LivingEntity target : around(l, e, front, 1.9)) {
                        if (hit(l, e, s, target, m.damage() * (1 - .25F * (t / 30F)), 1))
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 1 -> {
                // 月亮之力: the moon itself falls straight down onto the aim point (no parabola),
                // accelerating until it slams the ground.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 22) {
                    double f = Math.min(1, t / 22.0);
                    Vec3 top = s.point.add(0, 9.5, 0);
                    cue(e, s, "moon", top.lerp(s.point, f * f), s.direction);
                } else if (t == 23 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
            case 2 -> {
                // 幻影步: leave a rune where she stood and appear inside a column of starlight.
                if (t == 1) {
                    Vec3 start = e.position();
                    ExpansionGeometry.move(e, s.point.subtract(start).multiply(1, 0, 1));
                    cue(e, s, "blink", start, e.position());
                    finish(e, s);
                }
            }
            case 3 -> {
                // 星之裁决: five stars crash down around the aim point, then the verdict lands.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                int star = (t - 1) / 6;
                if (t <= 30 && (t - 1) % 6 == 0 && star < 5) {
                    double angle = star * 1.2566 + .4;
                    double distance = star == 4 ? 0 : 2.4;
                    Vec3 at = s.point.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                    cue(e, s, "star" + (star + 1), at, s.direction);
                    for (LivingEntity target : around(l, e, at, 2.1))
                        hit(l, e, s, target, m.damage() * .45F, 1);
                }
                if (t == 33 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage() * .8F, 1);
                    finish(e, s);
                }
            }
        }
    }
'''
s = s[:start] + new_g + s[end:]
base.write_text(s, encoding='utf-8')

# State needs the per-cast "already marked" set.
state = pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java').read_text(encoding='utf-8')
if 'final java.util.Set<java.util.UUID> marked' not in state:
    anchor = '        boolean dream;'
    if anchor in state:
        state = state.replace(anchor, anchor + '\n        /** Victims already given a hypnosis mark by this cast. */\n        final java.util.Set<java.util.UUID> marked = new java.util.HashSet<>();', 1)
        pathlib.Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java').write_text(state, encoding='utf-8')
        print('state field added')
    else:
        print('WARN: state anchor not found')
print('combat patched')

from pathlib import Path

fx = Path('src/main/java/com/pkmoba/client/fx/GardevoirFx.java')
s = fx.read_text(encoding='utf-8')

s = s.replace(
    '    private static final ResourceLocation MOONSPHERE = tex("gardevoir_moonsphere");',
    '    private static final ResourceLocation MOONSPHERE = tex("gardevoir_moonsphere");\n'
    '    /** The classic v6 moon skin the user asked to get back. */\n'
    '    private static final ResourceLocation MOON_CLASSIC = tex("gardevoir_moon_classic");')

start = s.index('    private static void moonblast(')
end = s.index('    // ---- E 瞬间移动')
new_moon = '''    private static void moonblast(WorldRenderContext c, FxView v, float a) {
        if (v.is("start")) {
            ground(c, RING, v.pos.add(0, .06, 0), 2.2 + 1.4 * v.progress, a * .85f);
            // The classic look: a small lit moon waiting high above the aim point.
            Vec3 high = v.pos.add(0, 8.4 - 1.0 * v.progress, 0);
            ExpansionSkillMeshes.sphere(c, high, new Vec3(1.25, 1.25, 1.25), v.time * .5,
                    MOON_CLASSIC, a, true);
            ExpansionSkillMeshes.sphere(c, high, new Vec3(.62, .62, .62), -v.time * .4, GLOW,
                    a * .7f, true);
            ExpansionSkillMeshes.ring(c, high, 1.45, .08, RING, a * .75f);
            for (int i = 0; i < 3; i++) {
                double angle = i * 2.094 + v.time;
                face(c, SPARK, high.add(Math.cos(angle) * 1.35, .45 - .35 * (i % 2),
                        Math.sin(angle) * 1.35), .6, .6, 0, a * .9f);
            }
        } else if (v.is("moon")) {
            // v6 moon: a solid lit sphere with craters that roll as it spins, two tumbling 3D
            // rings, a hot core, a four step trail and orbiting sparkles.
            double radius = 2.3;
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(radius * .52, radius * .52, radius * .52),
                    -v.time * .4, GLOW, a * .6f, true);
            ExpansionSkillMeshes.ring(c, v.pos, radius * 1.05, .12, RING, a * .9f);
            ExpansionSkillMeshes.ring(c, v.pos.add(0, .35, 0), radius * 1.16, .07, RING, a * .55f);
            for (int i = 1; i <= 4; i++) {
                double shrink = 1 - i * .17;
                ExpansionSkillMeshes.sphere(c, v.pos.add(0, i * .9, 0),
                        new Vec3(radius * shrink, radius * shrink, radius * shrink), v.time * .5,
                        MOON_CLASSIC, a * (.32f - .06f * i), true);
            }
            for (int i = 0; i < 5; i++) {
                double angle = i * 1.2566 + v.time * .7;
                double dist = radius * 1.2;
                face(c, SPARK, v.pos.add(Math.cos(angle) * dist, Math.sin(v.time + i) * .85,
                        Math.sin(angle) * dist), .7, .7, 0, a * .9f);
            }
            ground(c, RING, v.pos.add(0, .05, 0), 2.6 + 1.4 * v.progress, a * .45f);
        } else if (v.in("impact", "detonate")) {
            // Ground zero is exactly the aimed point: every burst is centred on v.pos.
            float burst = (float) Math.min(1.0, v.progress * 1.5);
            ExpansionSkillMeshes.sphere(c, v.pos,
                    new Vec3(2.4 + 2.8 * burst, 1.9 + 2.4 * burst, 2.4 + 2.8 * burst), v.time,
                    MOON_CLASSIC, a * (.95f - .35f * burst), true);
            ExpansionSkillMeshes.sphere(c, v.pos,
                    new Vec3(1.6 + 3.0 * burst, 1.4 + 2.6 * burst, 1.6 + 3.0 * burst), -v.time,
                    GLOW, a * (.75f - .3f * burst), true);
            face(c, FLASH, v.pos.add(0, .9, 0), 2.6 + 4.4 * burst, 2.6 + 4.4 * burst, v.time * .6, a);
            for (int i = 0; i < 10; i++) {
                double angle = i * Math.PI / 5;
                face(c, RAY, v.pos.add(0, .8, 0), .6, 2.2 + 3.0 * burst, angle, a * .7f);
            }
            for (int i = 0; i < 3; i++) {
                ground(c, RING, v.pos.add(0, .06 + .03 * i, 0),
                        2.2 + (3.0 + i * 1.5) * Math.min(1, v.progress * (1 + .2 * i)),
                        a * (.9f - .25f * i));
            }
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4 + .4;
                double dist = 2.0 + 3.2 * burst;
                face(c, SPARK, v.pos.add(Math.cos(angle) * dist, .45 + .8 * (i % 3),
                        Math.sin(angle) * dist), .75, .75, v.time * 2, a * .9f);
            }
        }
    }

'''
s = s[:start] + new_moon + s[end:]

# Q: a 4 block well, pulling harder the closer the victim already is
s = s.replace('ground(c, RING, centre, 3.0, a * .9f);', 'ground(c, RING, centre, 2.3, a * .9f);')
s = s.replace('ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.1, 0), 1.4, .12, RING, a * .85f);',
              'ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.1, 0), 1.15, .12, RING, a * .85f);')
s = s.replace('face(c, CRUSH, v.pos.add(0, 1.15, 0), 2.6, 2.6, v.time * 4.0, a * .95f);',
              'face(c, CRUSH, v.pos.add(0, 1.15, 0), 2.0, 2.0, v.time * 4.0, a * .95f);')
s = s.replace('face(c, CRUSH, v.pos.add(0, 1.15, 0), 1.9, 1.9, -v.time * 5.5, a * .7f);',
              'face(c, CRUSH, v.pos.add(0, 1.15, 0), 1.45, 1.45, -v.time * 5.5, a * .7f);')
s = s.replace('double dist = 3.4 - 2.6 * v.progress;', 'double dist = 2.6 - 2.0 * v.progress;')
fx.write_text(s, encoding='utf-8')

combat = Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
c = combat.read_text(encoding='utf-8')
c = c.replace('Vec3 top = s.point.add(0, 7.5, 0);', 'Vec3 top = s.point.add(0, 8.5, 0);')
old = '''                        Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
                        double d = to.length();
                        if (d < 1.2) continue;
                        double speed = Math.min(.62, .14 + d * .07);'''
new = '''                        Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
                        double d = to.length();
                        if (d < 1.0) continue;
                        // The closer a victim already is, the harder the well pulls it in.
                        double speed = .16 + .62 * Math.max(0, 1 - d / (m.radius() + .6));'''
assert old in c
c = c.replace(old, new)
combat.write_text(c, encoding='utf-8')

roster = Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
old = 'm("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 6.0, 30),'
new = 'm("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 4.0, 14),'
assert old in r
r = r.replace(old, new)
roster.write_text(r, encoding='utf-8')
print('patched v9 moon + psychic')

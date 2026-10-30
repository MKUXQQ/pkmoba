from pathlib import Path

ROOT = Path('src/main/java/com/pkmoba')
p = ROOT / 'client/fx/LucarioFx.java'
s = p.read_text(encoding='utf-8')

if 'ExpansionSkillMeshes' not in s:
    s = s.replace('import net.minecraft.resources.ResourceLocation;',
                  'import com.pkmoba.client.ExpansionSkillMeshes;\nimport net.minecraft.resources.ResourceLocation;', 1)

start = s.index('    /** W ')
end = s.index('    /** R 神速')
new_methods = '''    /** W 彗星拳: a solid 3D comet - glowing head with faceted rock chunks streaming behind. */
    private static void meteorMash(FxView v, Fx fist, Fx band, Fx star, Fx glow, float a) {
        var c = v.fx.context();
        Vec3 fwd = v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
        Vec3 chest = v.chest();
        if (v.in("start", "attack", "windup")) {
            Vec3 hand = chest.add(fwd.scale(.45));
            ExpansionSkillMeshes.sphere(c, hand, new Vec3(.34, .34, .34), v.time * .5, GLOW, a * .9f, false);
            ExpansionSkillMeshes.sphere(c, hand, new Vec3(.52, .52, .52), -v.time * .3, STAR, a * .35f, true);
        } else if (v.in("meteor", "release")) {
            Vec3 head = chest.add(fwd.scale(1.7));
            ExpansionSkillMeshes.sphere(c, head, new Vec3(.52, .52, .52), v.time * .7, GLOW, a, false);
            ExpansionSkillMeshes.sphere(c, head, new Vec3(.78, .78, .78), -v.time * .45, STAR, a * .42f, true);
            ExpansionSkillMeshes.ring(c, head, .95, .09, BAND, a * .75f);
            for (int i = 0; i < 8; i++) {
                double trail = .7 + i * .38;
                Vec3 at = head.subtract(fwd.scale(trail)).add(0, (i % 2 == 0 ? .2 : -.16), 0);
                ExpansionSkillMeshes.crystal(c, at.add(0, -.18, 0), .17 - i * .012, .6 - i * .035,
                        v.time * 1.2 + i, BAND, a * (0.85f - i * 0.07f));
            }
        } else if (v.in("impact", "melee", "detonate")) {
            double burst = Math.min(1.0, v.progress * 1.8);
            Vec3 at = v.pos;
            ExpansionSkillMeshes.sphere(c, at, new Vec3(.6 + .7 * burst, .6 + .7 * burst, .6 + .7 * burst),
                    v.time, STAR, a * .9f, true);
            ExpansionSkillMeshes.ring(c, at, 1.1 + 1.8 * burst, .12, GLOW, a * .8f);
            for (int i = 0; i < 6; i++) {
                double angle = i * Math.PI / 3 + .4;
                ExpansionSkillMeshes.crystal(c, at.add(Math.cos(angle) * (.6 + burst), -.3,
                        Math.sin(angle) * (.6 + burst)), .14, .7 + .5 * burst, angle, BAND, a * .8f);
            }
        }
    }

    /** E 雷电拳: the knuckle is wrapped in solid crystal bolts, no flat sheets. */
    private static void thunderPunch(FxView v, Fx fist, Fx bolt, Fx star, float a) {
        var c = v.fx.context();
        Vec3 fwd = v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
        Vec3 chest = v.chest();
        if (v.in("start", "attack")) {
            Vec3 strike = chest.add(fwd.scale(1.0));
            ExpansionSkillMeshes.sphere(c, strike, new Vec3(.34, .34, .34), v.time * .8, GLOW, a * .85f, false);
            for (int i = 0; i < 3; i++)
                ExpansionSkillMeshes.crystal(c, strike.add((i - 1) * .28, .1, 0), .1, .8, v.time * 2 + i, BOLT, a * .8f);
        } else if (v.is("bolt")) {
            Vec3 strike = chest.add(fwd.scale(1.9));
            ExpansionSkillMeshes.sphere(c, strike, new Vec3(.55, .55, .55), v.time * .9, GLOW, a, false);
            for (int i = 0; i < 5; i++) {
                Vec3 from = chest.add(fwd.scale(.5 + i * .3)).add((i % 2 == 0 ? .22 : -.22), .1, 0);
                ExpansionSkillMeshes.crystal(c, from, .12, .9, v.time * 1.6 + i, BOLT, a * .9f);
            }
            ExpansionSkillMeshes.ring(c, strike, .9, .1, BOLT, a * .8f);
        } else if (v.in("impact", "melee", "detonate")) {
            double burst = Math.min(1.0, v.progress * 1.9);
            ExpansionSkillMeshes.sphere(c, v.pos, new Vec3(.5 + .5 * burst, .5 + .5 * burst, .5 + .5 * burst),
                    v.time, GLOW, a * .9f, false);
            for (int i = 0; i < 7; i++) {
                double angle = i * Math.PI * 2 / 7 + v.time * .25;
                ExpansionSkillMeshes.crystal(c, v.pos.add(Math.cos(angle) * .5, -.2, Math.sin(angle) * .5),
                        .12, .8 + .6 * burst, angle, BOLT, a * .85f);
            }
            ExpansionSkillMeshes.ring(c, v.pos, .8 + 1.3 * burst, .1, BOLT, a * .75f);
        }
    }

'''
s = s[:start] + new_methods + s[end:]

es_start = s.index('    /** R 神速')
es_end = s.rindex('}')
s = s[:es_start] + '''    /** R 神速: stripped down - a short launch burst, then a clean arrival flash. */
    private static void extremeSpeed(FxView v, Fx band, Fx glow, Fx star, float a) {
        Vec3 body = v.pos.add(0, .55, 0);
        if (v.in("start", "dash")) {
            band.groundRing(v.pos, 1.0, 2, v.time, a * .7f);
            glow.sheet(body, 1.1, .7, 0, a * .5f);
        }
        if (v.in("arrive", "impact", "melee", "detonate")) {
            star.starBurst(v.pos.add(0, .6, 0), 1.1, 6, v.time, a * .8f);
            band.groundRing(v.pos, 1.3, 2, v.time, a * .7f);
        }
    }
''' + s[es_end:]
p.write_text(s, encoding='utf-8')
print('LucarioFx rewritten')

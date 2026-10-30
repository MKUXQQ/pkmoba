import pathlib

# ---- Gengar: vertex flash showing how many hypnosis marks a victim now carries ----------------
g = pathlib.Path('src/main/java/com/pkmoba/client/fx/GengarFx.java')
s = g.read_text(encoding='utf-8')
anchor = '        } else if (v.is("sleep")) {'
addition = '''        } else if (v.phase.startsWith("mark")) {
            // One flash per mark: the count itself is drawn persistently by HypnoStatusIcon, this is
            // the "stamp" that lands on the victim so the player notices the stack going up.
            int marks = Math.max(1, Math.min(3, v.phase.charAt(4) - '0'));
            Vec3 head = v.pos.add(0, 1.05, 0);
            sheet(c, HYPNO, head, 1.5 + .25 * marks, 1.5 + .25 * marks, v.time * 1.6, a * .95f);
            sheet(c, RING, head, 2.0 + .2 * marks, 2.0 + .2 * marks, -v.time * 1.2, a * .7f);
            for (int i = 0; i < marks; i++) {
                double offset = (i - (marks - 1) / 2.0) * .55;
                sheet(c, STAR, head.add(offset, -.9, 0), .55, .55, v.time + i, a);
            }
'''
assert anchor in s and 'mark' not in s
s = s.replace(anchor, addition + anchor, 1)
g.write_text(s, encoding='utf-8')

# ---- Persistent status: Zz badge when asleep, 1-3 pips while marks accumulate -----------------
h = pathlib.Path('src/main/java/com/pkmoba/client/HypnoStatusIcon.java')
t = h.read_text(encoding='utf-8')
old = '''            Vec3 center = new Vec3(x, y, z);
            ExpansionSkillMeshes.raw(context, TEX,
                    center.subtract(side).subtract(lift),
                    center.subtract(side).add(lift),
                    center.add(side).add(lift),
                    center.add(side).subtract(lift),
                    0, 0, 1, 1, .95F);'''
new = '''            var effect = pokemon.getEffect(MobEffects.UNLUCK);
            int amplifier = effect == null ? 0 : effect.getAmplifier();
            Vec3 center = new Vec3(x, y, z);
            if (amplifier >= 9) {
                // Asleep: the full Zz badge, rocking gently.
                ExpansionSkillMeshes.raw(context, TEX,
                        center.subtract(side).subtract(lift),
                        center.subtract(side).add(lift),
                        center.add(side).add(lift),
                        center.add(side).subtract(lift),
                        0, 0, 1, 1, .95F);
            } else {
                // Marked but still awake: a small pips row, one square per hypnosis mark.
                int marks = Math.min(3, amplifier + 1);
                double pip = .17;
                double spacing = .42;
                double start = -(marks - 1) * spacing * .5;
                for (int i = 0; i < marks; i++) {
                    Vec3 at = center.add(axisRight.scale(start + i * spacing));
                    Vec3 px = axisRight.scale(pip), py = axisUp.scale(pip);
                    ExpansionSkillMeshes.raw(context, PIP,
                            at.subtract(px).subtract(py), at.subtract(px).add(py),
                            at.add(px).add(py), at.add(px).subtract(py),
                            0, 0, 1, 1, .9F);
                }
            }'''
assert old in t
t = t.replace(old, new)
t = t.replace('''    private static final double RANGE_SQR = 48.0 * 48.0;''',
              '''    private static final ResourceLocation PIP = ResourceLocation.fromNamespaceAndPath(
            "pkmoba", "textures/effect/gengar_hypno_pip.png");
    private static final double RANGE_SQR = 48.0 * 48.0;''')
h.write_text(t, encoding='utf-8')
print('client patched')

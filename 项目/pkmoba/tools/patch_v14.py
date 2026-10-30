"""0.81: Blaziken fire wall, leap-timed flip animation, fewer flame particles, HUD badges.

Run from the project root: python tools/patch_v14.py
"""
from pathlib import Path

EFFECTS = Path("src/main/java/com/pkmoba/client/ExpansionEffects.java")
COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")

HELPER = '''
    /**
     * 0.81 蓄能焰袭: a real fire wall that grows out of the ground behind the dash.
     *
     * <p>It is a vertical quad spanning the dash direction (a curtain, not a camera-facing sprite),
     * drawn with the shared textured-mesh path so it is visible from both sides: the early billboard
     * version sat flat on the floor and could not be seen at all.
     */
    private static void blazikenFireWall(WorldRenderContext c, Scene s, double age, float fade) {
        if (c.matrixStack() == null || c.consumers() == null) return;
        Vec3 dir = s.dir.lengthSqr() > 1E-5 ? s.dir.normalize() : new Vec3(0, 0, 1);
        double rise = Math.min(1.0, age / 3.0);
        double height = 2.2 * rise;
        if (height < .05) return;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 a = s.pos.subtract(dir.scale(1.45));
        Vec3 e = s.pos.add(dir.scale(1.45));
        Vec3 n = dir.cross(up);
        n = n.lengthSqr() > 1E-6 ? n.normalize().scale(.05) : new Vec3(0, 0, .05);
        ResourceLocation wall = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_wall.png");
        float alpha = Math.min(1f, fade);
        // Two faces, so the wall reads whether the camera is on the left or the right of the dash.
        for (int side = -1; side <= 1; side += 2) {
            Vec3 off = n.scale(side);
            ExpansionSkillMeshes.raw(c, wall,
                    a.add(off), a.add(off).add(up.scale(height)),
                    e.add(off).add(up.scale(height)), e.add(off),
                    0f, (float) (1.0 - rise), 1f, 1f, alpha);
        }
        // A hot line along the base sells the "wall standing on the ground" read.
        ExpansionSkillMeshes.raw(c,
                ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/blaziken_flame.png"),
                a, a.add(up.scale(.22)), e.add(up.scale(.22)), e, 0f, .55f, 1f, 1f, alpha * .9f);
    }
'''

ANIM = '''            // 0.81: the flip is timed to the actual airborne leap, not to the button press, so the
            // animation matches what the body is doing on screen.
            if (phase.equals("leap") && species.id().equals("blaziken") && skill == 3) {
                animate(entity, species.id(), skill, "_cast");
            }
'''


def patch_effects() -> None:
    text = EFFECTS.read_text(encoding="utf-8")
    # 1) helper before accept()
    if "blazikenFireWall" not in text:
        anchor = "    public static boolean accept(EffectPayload payload) {"
        text = text.replace(anchor, HELPER + "\n" + anchor)
    # 2) leap animation, next to the attack beats
    if 'phase.equals("leap")' not in text:
        anchor = '            if (phase.equals("attack")) {'
        text = text.replace(anchor, ANIM + anchor)
    # 3) draw the wall instead of dispatching "trail"
    if "blazikenFireWall(context, s, age, fade);" not in text:
        anchor = ("            if (dispatch(context, s, anchors, tex, time, age, progress, fade, dir, "
                  "radius, owner.position())) continue;")
        hook = ("            if (s.id.equals(\"blaziken\") && s.skill == 2 && s.phase.equals(\"trail\")) {\n"
                "                blazikenFireWall(context, s, age, fade);\n"
                "                continue;\n"
                "            }\n")
        text = text.replace(anchor, hook + anchor)
    # 4) fewer flame particles: the fire sheets now carry the shape, particles only add embers
    if "FLAME_EMBERS" not in text:
        text = text.replace(
            "        if (rnd.nextFloat() > .62f) return;\n"
            "        int n = 2 + (int) Math.min(7, radius * 2.2);",
            "        // 0.81: fire has its own textured sheets now, so particles are embers only.\n"
            "        final boolean FLAME_EMBERS = \"FLAME\".equals(style);\n"
            "        if (rnd.nextFloat() > (FLAME_EMBERS ? .26f : .62f)) return;\n"
            "        int n = FLAME_EMBERS ? 1 + (int) Math.min(2, radius * .7)\n"
            "                : 2 + (int) Math.min(7, radius * 2.2);")
    EFFECTS.write_text(text, encoding="utf-8")
    print("patched ExpansionEffects (wall, leap, embers)")


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    old = """                    e.teleportTo(wanted.x, wanted.y, wanted.z);"""
    new = """                    if (t == windup + 1) cue(e, s, "leap", wanted, s.direction);
                    e.teleportTo(wanted.x, wanted.y, wanted.z);"""
    if 'cue(e, s, "leap"' not in text:
        assert old in text
        text = text.replace(old, new, 1)
        COMBAT.write_text(text, encoding="utf-8")
        print("patched ExpansionCombat leap cue")
    else:
        print("leap cue already present")


if __name__ == "__main__":
    patch_effects()
    patch_combat()

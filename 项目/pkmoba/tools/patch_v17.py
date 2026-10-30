"""0.84: five second fire wall, a proper dive kick, smoother flight.

* E wall segments live ~5 seconds, are longer and taller, and fade out smoothly instead of popping.
* R dive kick: legs no longer split - one leg is extended into the kick, the other is tucked; the
  body is interpolated between ticks so the flight reads as continuous motion, not 1-block steps.

Run from the project root: python tools/patch_v17.py
"""
import json
import re
from pathlib import Path

COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")
EFFECTS = Path("src/main/java/com/pkmoba/client/ExpansionEffects.java")
ANIM = Path("src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_blaziken.animation.json")


def smooth_teleport(text: str, var: str) -> str:
    """Insert old-position bookkeeping right after each teleportTo so the client interpolates."""
    pattern = re.compile(r"(\s*)e\.teleportTo\(" + var + r"\.x, " + var + r"\.y, " + var + r"\.z\);\n")
    def repl(match: re.Match) -> str:
        indent = match.group(1)
        return (match.group(0)
                + indent + "e.xo = e.getX(); e.yo = e.getY(); e.zo = e.getZ();\n")
    return pattern.sub(repl, text)


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    # smooth the rise and the dash (the goal-point landing stays a hard snap, it is an impact)
    text = text.replace("""                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);""",
                        """                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.xo = up.x; e.yo = up.y; e.zo = up.z;""")
    text = text.replace("""                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    cue(e, s, "charge", wanted, s.direction);""",
                        """                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    // The client interpolates between the previous and current tick position, so
                    // these must be the *previous* sample or the dive looks like 1-block steps.
                    e.xo = from.x + (s.point.x - from.x) * Math.max(0, f - 1.0 / dash);
                    e.yo = from.y + (s.point.y - from.y) * Math.max(0, f - 1.0 / dash);
                    e.zo = from.z + (s.point.z - from.z) * Math.max(0, f - 1.0 / dash);
                    cue(e, s, "charge", wanted, s.direction);""")
    COMBAT.write_text(text, encoding="utf-8")
    print("patched R interpolation")


def patch_animation() -> None:
    data = json.loads(ANIM.read_text(encoding="utf-8"))
    key = "animation.pkmoba_blaziken.r_leap"
    # One leg spears forward (the kick), the other folds up behind it - no split.
    clip = {
        "animation_length": 1.15,
        "loop": False,
        "bones": {
            "body": {
                "rotation": {"0.0": [0, 0, 0], "0.18": [-16, 0, 0], "0.7": [-24, 0, 0],
                             "1.15": [-20, 0, 0]},
                "position": {"0.0": [0, 0, 0], "0.18": [0, 2, 3], "0.7": [0, 2, 5],
                             "1.15": [0, 1, 5]},
            },
            "torso": {"rotation": {"0.0": [0, 0, 0], "0.35": [-6, 0, 0], "1.15": [-8, 0, 0]}},
            # kicking leg: knee comes up then spears out in front
            "leg_right": {"rotation": {"0.0": [0, 0, 0], "0.2": [-45, 0, 0], "0.45": [-82, 0, 0],
                                       "1.15": [-86, 0, 0]}},
            "leg_right2": {"rotation": {"0.0": [0, 0, 0], "0.2": [50, 0, 0], "0.45": [4, 0, 0],
                                        "1.15": [0, 0, 0]}},
            "foot_right": {"rotation": {"0.0": [0, 0, 0], "0.45": [22, 0, 0], "1.15": [16, 0, 0]}},
            # trailing leg: folded up, not extended backwards
            "leg_left": {"rotation": {"0.0": [0, 0, 0], "0.25": [12, 0, 0], "1.15": [14, 0, 0]}},
            "leg_left2": {"rotation": {"0.0": [0, 0, 0], "0.25": [78, 0, 0], "1.15": [82, 0, 0]}},
            "foot_left": {"rotation": {"0.0": [0, 0, 0], "0.25": [-26, 0, 0], "1.15": [-28, 0, 0]}},
            "arm_left": {"rotation": {"0.0": [0, 0, 0], "0.3": [-18, 0, -22], "1.15": [-22, 0, -24]}},
            "arm_right": {"rotation": {"0.0": [0, 0, 0], "0.3": [-18, 0, 22], "1.15": [-22, 0, 24]}},
            "tail1": {"rotation": {"0.0": [0, 0, 0], "0.4": [12, 0, 0], "1.15": [14, 0, 0]}},
        },
    }
    data["animations"][key] = clip
    ANIM.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print("rewrote r_leap clip (spear kick, folded trailing leg)")


def patch_effects() -> None:
    text = EFFECTS.read_text(encoding="utf-8")
    start = text.index("    private static void blazikenFireWall(")
    end = text.index("    public static boolean accept(EffectPayload payload) {")
    wall = '''    private static void blazikenFireWall(WorldRenderContext c, Scene s, double age, float fade) {
        if (c.matrixStack() == null || c.consumers() == null) return;
        Vec3 dir = s.dir.lengthSqr() > 1E-5 ? s.dir.normalize() : new Vec3(0, 0, 1);
        // 0.84: every dash step leaves a long, tall segment that stands for ~5 seconds (its scene
        // life), rising slowly behind the Pokemon and fading out over the last second.
        double rise = Math.min(1.0, age / 11.0);
        double height = 3.4 * rise;
        if (height < .04) return;
        double remaining = s.life - age;
        float alpha = (float) Math.min(1.0, Math.min(1.0, remaining / 18.0)) * Math.min(1f, fade);
        if (alpha <= .01f) return;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 a = s.pos.subtract(dir.scale(4.2));
        Vec3 e = s.pos.add(dir.scale(.7));
        Vec3 n = dir.cross(up);
        n = n.lengthSqr() > 1E-6 ? n.normalize().scale(.05) : new Vec3(0, 0, .05);
        ResourceLocation wall = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_wall.png");
        ResourceLocation flame = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_flame.png");
        for (int side = -1; side <= 1; side += 2) {
            Vec3 off = n.scale(side);
            ExpansionSkillMeshes.raw(c, wall,
                    a.add(off), a.add(off).add(up.scale(height)),
                    e.add(off).add(up.scale(height)), e.add(off),
                    0f, (float) (1.0 - rise), 1.6f, 1f, alpha);
        }
        for (int i = 0; i <= 3; i++) {
            Vec3 base = a.add(e.subtract(a).scale(.14 + .24 * i)).add(0, height * .88, 0);
            double flick = .85 + .3 * Math.sin(age * .6 + i * 2.1);
            ExpansionSkillMeshes.raw(c, flame, base, base.add(0, 1.9 * flick, 0),
                    base.add(dir.scale(1.6)).add(0, 1.9 * flick, 0), base.add(dir.scale(1.6)),
                    0f, 0f, 1f, 1f, alpha);
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) {
            java.util.Random rnd = new java.util.Random((long) (s.pos.x * 977 + s.pos.z * 641));
            if (rnd.nextFloat() < .42f) {
                for (int i = 0; i < 2; i++) {
                    double along = rnd.nextDouble();
                    Vec3 p = a.add(e.subtract(a).scale(along)).add(0, .2 + rnd.nextDouble() * .8, 0);
                    mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,
                            p.x, p.y, p.z, 0, .05 + rnd.nextDouble() * .05, 0);
                }
            }
        }
    }
'''
    text = text[:start] + wall + "\n" + text[end:]
    text = text.replace('                case "trail" -> 40;',
                        '                // A fire wall has to stand on the field for about five seconds.\n'
                        '                case "trail" -> 105;')
    EFFECTS.write_text(text, encoding="utf-8")
    print("patched wall (5 s, longer/taller, smooth fade)")


def patch_version() -> None:
    gradle = Path("build.gradle")
    text = gradle.read_text(encoding="utf-8")
    text = text.replace("version = '0.83.0'", "version = '0.84.0'")
    gradle.write_text(text, encoding="utf-8")
    print("version 0.84.0")


if __name__ == "__main__":
    patch_combat()
    patch_animation()
    patch_effects()
    patch_version()

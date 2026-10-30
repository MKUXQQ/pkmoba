"""0.83: fire wall follows the model, longer E dash, slower dive-kick Flame Charge.

* E wall: each segment only starts rising at the position the body has *reached*, grows slowly, and
  the dash itself was lengthened (8 -> 12 blocks) so the wall spans the real travel distance.
* R: no more in-place kick at the button press. The body rises, then does one slow dive-kick clip
  while flying onto the clicked point.

Run from the project root: python tools/patch_v16.py
"""
import json
from pathlib import Path

COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")
EFFECTS = Path("src/main/java/com/pkmoba/client/ExpansionEffects.java")
ANIM = Path("src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_blaziken.animation.json")

R_CASE = '''            default -> { // 闪焰冲锋: rise straight up, then fly a slow dive kick onto the clicked point.
                final int rise = 6;
                final int dash = 14;
                if (t == 1) {
                    s.origin = e.position();
                    e.setDeltaMovement(Vec3.ZERO);
                }
                if (t <= rise) {
                    // Levitate: no kick yet, the body simply rises off the ground.
                    double f = (t - 1) / (double) rise;
                    Vec3 up = s.origin.add(0, 1.9 * f, 0);
                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    cue(e, s, "rise", up, s.direction);
                } else if (t <= rise + dash) {
                    // The dive itself is slow enough to read: one kick, held all the way down.
                    double f = (t - rise) / (double) dash;
                    Vec3 from = s.origin.add(0, 1.9, 0);
                    Vec3 wanted = new Vec3(from.x + (s.point.x - from.x) * f,
                            from.y + (s.point.y - from.y) * f,
                            from.z + (s.point.z - from.z) * f);
                    if (t == rise + 1) cue(e, s, "leap", wanted, s.direction);
                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    cue(e, s, "charge", wanted, s.direction);
                } else if (t == rise + dash + 1 && !s.stopped) {
                    s.stopped = true;
                    e.teleportTo(s.point.x, s.point.y, s.point.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "impact", s.point, s.direction);
                    boolean branded = false;
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        float damage = m.damage();
                        if (takeBlazeMark(target)) {
                            damage = damage * 1.6F + 20F;
                            branded = true;
                        }
                        hit(l, e, s, target, damage, 1);
                    }
                    if (branded) {
                        cue(e, s, "detonate", s.point, s.direction);
                        for (LivingEntity target : around(l, e, s.point, m.radius() * 1.5))
                            hit(l, e, s, target, 12, 1);
                    }
                    finish(e, s);
                }
            }
        }
    }
'''

WALL = '''    private static void blazikenFireWall(WorldRenderContext c, Scene s, double age, float fade) {
        if (c.matrixStack() == null || c.consumers() == null) return;
        Vec3 dir = s.dir.lengthSqr() > 1E-5 ? s.dir.normalize() : new Vec3(0, 0, 1);
        // 0.83: each segment belongs to one dash step and rises *after* the body has reached it,
        // slowly, so the wall grows behind the Pokemon instead of appearing ahead of it.
        double rise = Math.min(1.0, age / 9.0);
        double height = 2.7 * rise;
        if (height < .04) return;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 a = s.pos.subtract(dir.scale(2.6));
        Vec3 e = s.pos.add(dir.scale(.35));
        Vec3 n = dir.cross(up);
        n = n.lengthSqr() > 1E-6 ? n.normalize().scale(.05) : new Vec3(0, 0, .05);
        ResourceLocation wall = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_wall.png");
        ResourceLocation flame = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_flame.png");
        float alpha = Math.min(1f, fade);
        for (int side = -1; side <= 1; side += 2) {
            Vec3 off = n.scale(side);
            ExpansionSkillMeshes.raw(c, wall,
                    a.add(off), a.add(off).add(up.scale(height)),
                    e.add(off).add(up.scale(height)), e.add(off),
                    0f, (float) (1.0 - rise), 1.0f, 1f, alpha);
        }
        // two tongues of flame on the top edge of this segment
        for (int i = 0; i <= 1; i++) {
            Vec3 base = a.add(e.subtract(a).scale(.28 + .44 * i)).add(0, height * .9, 0);
            double flick = .8 + .3 * Math.sin(age * .7 + i * 1.9);
            ExpansionSkillMeshes.raw(c, flame, base, base.add(0, 1.5 * flick, 0),
                    base.add(dir.scale(1.3)).add(0, 1.5 * flick, 0), base.add(dir.scale(1.3)),
                    0f, 0f, 1f, 1f, alpha);
        }
        ExtensionSpawn: {
            // embers drifting up from the base of this segment
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) break ExtensionSpawn;
            java.util.Random rnd = new java.util.Random((long) (s.pos.x * 977 + s.pos.z * 641));
            if (rnd.nextFloat() > .5f) break ExtensionSpawn;
            for (int i = 0; i < 2; i++) {
                double along = rnd.nextDouble();
                Vec3 p = a.add(e.subtract(a).scale(along)).add(0, .2 + rnd.nextDouble() * .6, 0);
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,
                        p.x, p.y, p.z, 0, .05 + rnd.nextDouble() * .04, 0);
            }
        }
    }
'''


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    start = text.index("            default -> { // 闪焰冲锋: rise straight up")
    anchor = "    /** Psychic drag"
    end = text.index(anchor, start)
    text = text[:start] + R_CASE + "\n" + text[end:]
    COMBAT.write_text(text, encoding="utf-8")
    print("patched R (slow dive kick, no ground kick)")


def patch_effects() -> None:
    text = EFFECTS.read_text(encoding="utf-8")
    start = text.index("    private static void blazikenFireWall(")
    end = text.index("    public static boolean accept(EffectPayload payload) {")
    text = text[:start] + WALL + "\n" + text[end:]
    # the dive stance plays once, when the dive starts
    if 'phase.equals("leap")' not in text:
        hook = '''            if (phase.equals("leap") && species.id().equals("blaziken") && skill == 3) {
                animate(entity, species.id(), skill, "_leap");
            }
'''
        anchor = "            if (phase.equals(\"attack\")) {"
        text = text.replace(anchor, hook + anchor)
    text = text.replace('                case "trail" -> 34;', '                case "trail" -> 40;')
    EFFECTS.write_text(text, encoding="utf-8")
    print("patched wall growth + leap clip")


def patch_animation() -> None:
    data = json.loads(ANIM.read_text(encoding="utf-8"))
    key = "animation.pkmoba_blaziken.r_leap"
    if key in data["animations"]:
        print("r_leap already present")
        return
    clip = {
        "animation_length": 1.15,
        "loop": False,
        "bones": {
            "body": {
                "rotation": {"0.0": [0, 0, 0], "0.15": [-18, 0, 0], "1.15": [-22, 0, 0]},
                "position": {"0.0": [0, 0, 0], "0.15": [0, 2, 4], "1.15": [0, 1, 6]},
            },
            "torso": {"rotation": {"0.0": [0, 0, 0], "0.3": [-8, 0, 0], "1.15": [-10, 0, 0]}},
            "leg_right": {
                "rotation": {"0.0": [0, 0, 0], "0.2": [-72, 0, 0], "1.15": [-78, 0, 0]},
            },
            "leg_right2": {"rotation": {"0.0": [0, 0, 0], "0.25": [26, 0, 0], "1.15": [20, 0, 0]}},
            "foot_right": {"rotation": {"0.0": [0, 0, 0], "0.3": [28, 0, 0], "1.15": [24, 0, 0]}},
            "leg_left": {"rotation": {"0.0": [0, 0, 0], "0.2": [42, 0, 0], "1.15": [46, 0, 0]}},
            "leg_left2": {"rotation": {"0.0": [0, 0, 0], "0.25": [58, 0, 0], "1.15": [62, 0, 0]}},
            "arm_left": {"rotation": {"0.0": [0, 0, 0], "0.25": [-26, 0, -18], "1.15": [-30, 0, -20]}},
            "arm_right": {"rotation": {"0.0": [0, 0, 0], "0.25": [-26, 0, 18], "1.15": [-30, 0, 20]}},
            "tail1": {"rotation": {"0.0": [0, 0, 0], "0.4": [10, 0, 0], "1.15": [12, 0, 0]}},
        },
    }
    data["animations"][key] = clip
    ANIM.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print("added r_leap clip (1.15s dive kick)")


if __name__ == "__main__":
    patch_combat()
    patch_effects()
    patch_animation()

"""0.85: Flare Blitz actually hits, and lands on the same tick as the explosion.

Root cause of the missing damage: the settlement branch waited for t == 6 + 14 + 1 == 21 while the
skill state only ever runs t = 1..18 (duration), so it never executed. The whole timeline is now
fitted inside the state: 5 tick lift, 20 tick dive, impact on the tick the body reaches the point
(or on timeout), with the explosion cue, the damage and CastLock release all on that same tick.

Run from the project root: python tools/patch_v18.py
"""
import json
import re
from pathlib import Path

ROSTER = Path("src/main/java/com/pkmoba/combat/ExpansionRoster.java")
COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")
ANIM = Path("src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_blaziken.animation.json")
FX = Path("src/main/java/com/pkmoba/client/fx/BlazikenFx.java")

R_CASE = '''            default -> { // 闪焰冲锋: lift off, dive-kick onto the clicked point, explode on arrival.
                final int rise = 5;
                final int dash = 20;
                final int land = rise + dash + 1; // == 26, inside the 30 tick state window
                if (t == 1) {
                    s.origin = e.position();
                    s.stopped = false;
                    s.recoil = false;
                    e.setDeltaMovement(Vec3.ZERO);
                }
                if (t <= rise) {
                    // Levitate: no kick yet, the body simply rises off the ground.
                    double f = (t - 1) / (double) rise;
                    Vec3 up = s.origin.add(0, 2.0 * f, 0);
                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.xo = up.x;
                    e.yo = up.y - 2.0 / rise;
                    e.zo = up.z;
                    e.resetFallDistance();
                    cue(e, s, "rise", up, s.direction);
                    return;
                }
                Vec3 from = s.origin.add(0, 2.0, 0);
                double horizontal = s.point.subtract(from).multiply(1, 0, 1).length();
                double f = Math.min(1.0, (t - rise) / (double) dash);
                Vec3 wanted = new Vec3(from.x + (s.point.x - from.x) * f,
                        from.y + (s.point.y - from.y) * f,
                        from.z + (s.point.z - from.z) * f);
                boolean arrived = horizontal < 1.2 || f >= 1.0 || t >= land;
                if (!s.recoil && !arrived) {
                    // The dive: one kick held all the way down, interpolated from the previous tick.
                    if (t == rise + 1) cue(e, s, "leap", wanted, s.direction);
                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    e.xo = from.x + (s.point.x - from.x) * Math.max(0, f - 1.0 / dash);
                    e.yo = from.y + (s.point.y - from.y) * Math.max(0, f - 1.0 / dash);
                    e.zo = from.z + (s.point.z - from.z) * Math.max(0, f - 1.0 / dash);
                    cue(e, s, "charge", wanted, s.direction);
                    return;
                }
                if (s.recoil) return;
                // Landing: the explosion, the damage and the body position all happen on this tick.
                s.recoil = true;
                s.stopped = true;
                e.teleportTo(s.point.x, s.point.y, s.point.z);
                e.setDeltaMovement(Vec3.ZERO);
                e.resetFallDistance();
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
                        hit(l, e, s, target, 14, 1);
                }
                HitFeedback.onEpicSlam(s.point);
                finish(e, s);
            }
'''

CLIP = {
    "animation_length": 1.0,
    "loop": False,
    "bones": {
        "body": {
            "rotation": {"0.0": [0, 0, 0], "0.15": [-14, 0, 0], "0.5": [-26, 0, 0],
                         "0.85": [-20, 0, 0], "1.0": [-18, 0, 0]},
            "position": {"0.0": [0, 0, 0], "0.15": [0, 2, 2], "0.5": [0, 2, 4],
                         "1.0": [0, 1, 5]},
        },
        "torso": {"rotation": {"0.0": [0, 0, 0], "0.35": [-6, 0, 0], "1.0": [-8, 0, 0]}},
        "leg_right": {"rotation": {"0.0": [0, 0, 0], "0.18": [-42, 0, 0], "0.42": [-84, 0, 0],
                                   "1.0": [-88, 0, 0]}},
        "leg_right2": {"rotation": {"0.0": [0, 0, 0], "0.18": [46, 0, 0], "0.42": [2, 0, 0],
                                    "1.0": [0, 0, 0]}},
        "foot_right": {"rotation": {"0.0": [0, 0, 0], "0.42": [20, 0, 0], "1.0": [14, 0, 0]}},
        "leg_left": {"rotation": {"0.0": [0, 0, 0], "0.22": [14, 0, 0], "1.0": [16, 0, 0]}},
        "leg_left2": {"rotation": {"0.0": [0, 0, 0], "0.22": [76, 0, 0], "1.0": [80, 0, 0]}},
        "foot_left": {"rotation": {"0.0": [0, 0, 0], "0.22": [-24, 0, 0], "1.0": [-26, 0, 0]}},
        "arm_left": {"rotation": {"0.0": [0, 0, 0], "0.3": [-16, 0, -24], "1.0": [-20, 0, -26]}},
        "arm_right": {"rotation": {"0.0": [0, 0, 0], "0.3": [-16, 0, 24], "1.0": [-20, 0, 26]}},
        "tail1": {"rotation": {"0.0": [0, 0, 0], "0.4": [12, 0, 0], "1.0": [14, 0, 0]}},
    },
}

FX_BODY = '''    private static void flareBlitz(FxView v, Fx plume, Fx band, Fx star, float a) {
        if (v.is("rise") || v.is("attack") || v.is("warn")) {
            // The body simply lifts off: fire pours out under the feet and climbs with it.
            Vec3 feet = v.feet().add(0, .1, 0);
            plume(plume, feet, 1.1 + .9 * v.progress, v.time, a, 0);
            plume(plume, feet.add(0, .6, 0), .9 + .8 * v.progress, v.time + .4, a * .9f, 1);
            for (int i = 0; i < 5; i++) {
                double angle = i * 1.26 + v.time;
                Vec3 at = v.pos.add(Math.cos(angle) * 1.0, .1, Math.sin(angle) * 1.0);
                plume(plume, at, .8, v.time + i, a * .85f, i + 2);
            }
            band.beam(feet, feet.add(0, 3.0, 0), .6, a * .5f);
            plume.groundRing(v.pos, 1.5 + .9 * v.progress, 2, v.time, a * .55f);
        } else if (v.is("charge")) {
            // Straight-line dive: a long fire comet streams out behind the body.
            Vec3 body = v.pos.add(0, .5, 0);
            plume(plume, body, 1.5, v.time, a, 1);
            for (int i = 1; i <= 8; i++) {
                Vec3 back = body.subtract(v.dir.scale(.5 * i));
                plume(plume, back, 1.1 - .1 * i, v.time + i * .16, a * (.95f - .1f * i), i + 2);
            }
            band.beam(body.subtract(v.dir.scale(5.0)), body, .7, a * .6f);
            star.starBurst(body, .8, 6, v.time, a * .35f);
        } else if (v.in("impact", "detonate")) {
            float burst = (float) Math.min(1.0, v.progress * 1.5);
            Vec3 core = v.pos.add(0, .5, 0);
            // expanding explosion dome + a wide floor of flame + vertical fire pillars
            plume(plume, core, 2.4 + 1.4 * burst, v.time, a, 5);
            plume(plume, core, 1.6 + .9 * burst, v.time + .5, a * .9f, 6);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6 + .25;
                double dist = .8 + 2.6 * burst;
                plume(plume, v.pos.add(Math.cos(angle) * dist, .1, Math.sin(angle) * dist),
                        1.05 + .5 * burst, v.time + i, a * .95f, i + 7);
            }
            for (int i = 0; i < 6; i++) {
                double angle = i * Math.PI / 3 + .5;
                Vec3 base = v.pos.add(Math.cos(angle) * (1.2 + 1.6 * burst), .1,
                        Math.sin(angle) * (1.2 + 1.6 * burst));
                band.beam(base, base.add(0, 2.6 + 1.6 * burst, 0), .55, a * .8f);
            }
            star.starBurst(core, 1.6 + 1.6 * burst, 14, v.time, a * .95f);
            plume.groundRing(v.pos, 2.0 + 3.4 * burst, 3, v.time, a * .8f);
            plume.ringWave(core, 1.0 + 3.0 * burst, .35, 2, v.time, a * .7f);
        }
    }
'''


def patch_roster() -> None:
    text = ROSTER.read_text(encoding="utf-8")
    old = 'm("闪焰冲锋", Shape.POINT, 3, 9, 10, 18, 14, 3.4, 42)'
    new = 'm("闪焰冲锋", Shape.POINT, 3, 9, 8, 30, 14, 3.4, 46)'
    assert old in text, "roster R entry not found"
    ROSTER.write_text(text.replace(old, new), encoding="utf-8")
    print("roster R: windup 8, duration 30, damage 46")


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    start = text.index("            default -> { // 闪焰冲锋:")
    end = text.index("    /** Psychic drag", start)
    COMBAT.write_text(text[:start] + R_CASE + "        }\n    }\n\n" + text[end:], encoding="utf-8")
    print("R timeline rebuilt inside the state window (impact tick == explosion tick)")


def patch_animation() -> None:
    data = json.loads(ANIM.read_text(encoding="utf-8"))
    data["animations"]["animation.pkmoba_blaziken.r_leap"] = CLIP
    ANIM.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print("r_leap fitted to 1.0 s dive")


def patch_fx() -> None:
    text = FX.read_text(encoding="utf-8")
    start = text.index("    private static void flareBlitz(")
    end = text.rindex("}\n")  # class closing brace
    FX.write_text(text[:start] + FX_BODY + "\n" + text[end:], encoding="utf-8")
    print("flare blitz visuals enlarged (rise pillar, comet dive, floor explosion)")


def patch_version() -> None:
    gradle = Path("build.gradle")
    text = gradle.read_text(encoding="utf-8").replace("version = '0.84.0'", "version = '0.85.0'")
    gradle.write_text(text, encoding="utf-8")
    print("version 0.85.0")


if __name__ == "__main__":
    patch_roster()
    patch_combat()
    patch_animation()
    patch_fx()
    patch_version()

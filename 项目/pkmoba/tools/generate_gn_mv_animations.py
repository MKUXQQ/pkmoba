# -*- coding: utf-8 -*-
"""Bedrock clips for the two new species so windup / cast / melee actually move the model.

Clip names must match ExpansionEffects.playClip: qwer + "_windup" | "_cast" | "_melee".
Bones unknown to a given Cobblemon model are simply ignored by the animation system, so the
generic body / head / arm rig is safe for both species.
"""
import json
from pathlib import Path

OUT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations")

# per slot: (windup pose, cast pose, melee pose) for the right arm
POSES = {
    "q": ((-72, 0, -18), (42, 0, 12), (-16, 6, -58)),
    "w": ((-46, 0, -34), (34, 0, 26), (58, 0, 44)),
    "e": ((-24, 0, -52), (10, 0, 58), (-8, 0, 62)),
    "r": ((-88, 0, -10), (52, 0, 6), (70, 0, 0)),
}


def scale(v, f):
    return (v[0] * f, v[1] * f, v[2] * f)


def clip(slot, kind, length):
    push, twist, tilt = POSES[slot]
    body = (tilt[0] * .4, tilt[1] * .6, tilt[2] * .25)
    if kind == "_windup":
        span = [0.0, length * .6, length]
        armk = [(0, 0, 0), push, push]
        bodyk = [(0, 0, 0), body, body]
    elif kind == "_cast":
        span = [0.0, length * .22, length * .55, length]
        armk = [push, push, scale(twist, .5), (0, 0, 0)]
        bodyk = [body, body, scale(body, .3), (0, 0, 0)]
    else:
        span = [0.0, length * .25, length * .6, length]
        armk = [push, (push[0] * .4, push[1], push[2] - 30), scale(push, -.2), (0, 0, 0)]
        bodyk = [body, (0, body[1] * .5, 0), (0, 0, 0), (0, 0, 0)]
    left = [(k[1] * .3, 0, -k[2] * .3) for k in armk]
    head = [(k[0] * .25, -k[1] * .3, 0) for k in bodyk]
    bones = {}
    for name, keys in (("arm_right", armk), ("arm_left", left), ("body", bodyk), ("head", head)):
        bones[name] = {"rotation": {("%.2f" % t): [round(v, 2) for v in k] for t, k in zip(span, keys)}}
    return {"animation_length": round(length, 2), "bones": bones}


# 0.124: Greninja clips are timed to the real 2.0 windups (Q 2t, W 5t, E 6t, R 12t) so the arm
# finishes its pose exactly when the shuriken leaves / the dash starts / the jet fires.
GRENINJA_LENGTHS = {
    "q": {"_windup": .10, "_cast": .50, "_melee": .35},
    "w": {"_windup": .20, "_cast": .35, "_melee": .30},
    "e": {"_windup": .30, "_cast": .80, "_melee": .35},
    "r": {"_windup": .20, "_cast": .50, "_melee": .40},
}
# 0.130: Meowscarada 2.0 windups are Q 2t, W 3t, E 5t and R 8t; the charge poses are short so a
# dashed or thrown skill never eats the next button press.
MEOW_LENGTHS = {
    "q": {"_windup": .10, "_cast": .55, "_melee": .30},
    "w": {"_windup": .15, "_cast": .30, "_melee": .30},
    "e": {"_windup": .25, "_cast": .70, "_melee": .30},
    "r": {"_windup": .40, "_cast": .55, "_melee": .30},
}
DEFAULT_LENGTHS = {"_windup": .45, "_cast": .7, "_melee": .5}


def main():
    for species in ("greninja", "meowscarada"):
        anims = {}
        lengths = GRENINJA_LENGTHS if species == "greninja" else MEOW_LENGTHS
        for slot in "qwer":
            table = lengths[slot] if lengths else DEFAULT_LENGTHS
            for kind in ("_windup", "_cast", "_melee"):
                length = table[kind]
                anims["animation.pkmoba_%s.%s%s" % (species, slot, kind)] = clip(slot, kind, length)
        data = {"format_version": "1.8.0", "animations": anims}
        (OUT / ("pkmoba_%s.animation.json" % species)).write_text(
            json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("wrote 2 animation files, 12 clips each")


if __name__ == "__main__":
    main()

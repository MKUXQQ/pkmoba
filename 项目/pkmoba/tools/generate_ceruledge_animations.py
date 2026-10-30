# -*- coding: utf-8 -*-
"""Ceruledge clips: twin blades, so the swing clips drive arm_left / arm_right separately."""
import json
from pathlib import Path

OUT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations")


def rot(keys):
    return {"rotation": {("%.2f" % t): [round(v, 2) for v in k] for t, k in keys}}


def swing(side, length):
    """One blade cutting across the front: the arm leads, the body twists after it."""
    lead = (0, 0, -78) if side == "left" else (0, 0, 78)
    follow = (0, 0, 46) if side == "left" else (0, 0, -46)
    arm = [(0.0, (0, 0, 0)), (length * .45, lead), (length, follow)]
    torso = [(0.0, (0, 0, 0)), (length * .45, (0, -14 if side == "left" else 14, 0)),
             (length, (0, 10 if side == "left" else -10, 0))]
    bones = {"body": rot(torso), "head": rot([(0.0, (0, 0, 0)), (length, (0, 6 if side == "left" else -6, 0))])}
    if side == "left":
        bones["arm_left"] = rot(arm)
        bones["arm_right"] = rot([(0.0, (0, 0, 0)), (length, (0, 0, 18))])
    else:
        bones["arm_right"] = rot(arm)
        bones["arm_left"] = rot([(0.0, (0, 0, 0)), (length, (0, 0, -18))])
    return {"animation_length": round(length, 2), "bones": bones}


def cross(length):
    """Both blades down the middle, then the follow through."""
    raise_ = [(0.0, (0, 0, 0)), (length * .3, (-92, 0, -30)), (length * .55, (36, 0, 22)), (length, (-10, 0, 8))]
    mirror = [(0.0, (0, 0, 0)), (length * .3, (-92, 0, 30)), (length * .55, (36, 0, -22)), (length, (-10, 0, -8))]
    body = [(0.0, (0, 0, 0)), (length * .3, (-8, 0, 0)), (length * .55, (10, 0, 0)), (length, (0, 0, 0))]
    head = [(0.0, (0, 0, 0)), (length * .55, (12, 0, 0)), (length, (0, 0, 0))]
    return {"animation_length": round(length, 2),
            "bones": {"arm_right": rot(raise_), "arm_left": rot(mirror), "body": rot(body), "head": rot(head)}}


POSES = {
    "q": ((-70, 0, -20), (44, 0, 14), (-14, 5, -56)),
    "w": ((-40, 0, -30), (30, 0, 24), (54, 0, 40)),
    "e": ((-26, 0, -46), (12, 0, 52), (-6, 0, 56)),
    "r": ((-84, 0, -12), (48, 0, 8), (64, 0, 0)),
}
LENGTHS = {
    "q": {"_windup": .15, "_cast": .50, "_melee": .30},
    "w": {"_windup": .15, "_cast": .35, "_melee": .30},
    "e": {"_windup": .20, "_cast": .50, "_melee": .35},
    "r": {"_windup": .30, "_cast": .50, "_melee": .30},
}
SWING_LENGTHS = {"q_swing_l": .20, "q_swing_r": .20, "q_cross": .32}


def clip(slot, kind, length):
    push, twist, tilt = POSES[slot]
    body = (tilt[0] * .4, tilt[1] * .6, tilt[2] * .25)
    if kind == "_windup":
        span = [0.0, length * .6, length]
        armk = [(0, 0, 0), push, push]
        bodyk = [(0, 0, 0), body, body]
    elif kind == "_cast":
        span = [0.0, length * .22, length * .55, length]
        armk = [push, push, (twist[0] * .5, twist[1] * .5, twist[2] * .5), (0, 0, 0)]
        bodyk = [body, body, (body[0] * .3, body[1] * .3, body[2] * .3), (0, 0, 0)]
    else:
        span = [0.0, length * .25, length * .6, length]
        armk = [push, (push[0] * .4, push[1], push[2] - 30), (push[0] * -.2, push[1] * -.2, push[2] * -.2), (0, 0, 0)]
        bodyk = [body, (0, body[1] * .5, 0), (0, 0, 0), (0, 0, 0)]
    left = [(k[1] * .3, 0, -k[2] * .3) for k in armk]
    head = [(k[0] * .25, -k[1] * .3, 0) for k in bodyk]
    bones = {}
    for name, keys in (("arm_right", armk), ("arm_left", left), ("body", bodyk), ("head", head)):
        bones[name] = {"rotation": {("%.2f" % t): [round(v, 2) for v in k] for t, k in zip(span, keys)}}
    return {"animation_length": round(length, 2), "bones": bones}


def main():
    anims = {}
    for slot in "qwer":
        for kind in ("_windup", "_cast", "_melee"):
            anims["animation.pkmoba_ceruledge.%s%s" % (slot, kind)] = clip(slot, kind, LENGTHS[slot][kind])
    anims["animation.pkmoba_ceruledge.q_swing_l"] = swing("left", SWING_LENGTHS["q_swing_l"])
    anims["animation.pkmoba_ceruledge.q_swing_r"] = swing("right", SWING_LENGTHS["q_swing_r"])
    anims["animation.pkmoba_ceruledge.q_cross"] = cross(SWING_LENGTHS["q_cross"])
    data = {"format_version": "1.8.0", "animations": anims}
    (OUT / "pkmoba_ceruledge.animation.json").write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("wrote 15 ceruledge clips")


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""Hisuian Zoroark clips: ghost-claw basic chain plus one trio per skill."""
import json
from pathlib import Path

OUT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations")


def rot(keys):
    return {"rotation": {("%.2f" % t): [round(v, 2) for v in k] for t, k in keys}}


def claw(side, length, cross=False):
    """One paw raking across the front; `cross` also throws the other paw the other way."""
    sign = 1 if side == "right" else -1
    lead = [0, 0, sign * -82]
    follow = [0, sign * 12, sign * 40]
    arm = [(0.0, (0, 0, 0)), (length * .35, (-24, 0, lead[2])), (length, tuple(follow))]
    other = [(0.0, (0, 0, 0)), (length * .5, (0, 0, 0)), (length, (0, 0, sign * 18))]
    body = [(0.0, (0, 0, 0)), (length * .35, (0, sign * -16, sign * 4)), (length, (0, sign * 10, 0))]
    bones = {"body": rot(body), "torso": rot([(0.0, (0, 0, 0)), (length, (0, sign * 8, 0))]),
             "head": rot([(0.0, (0, 0, 0)), (length, (6, sign * -8, 0))])}
    if cross:
        bones["arm_right"] = rot([(0.0, (0, 0, 0)), (length * .3, (-30, 0, -78)), (length, (0, 0, 34))])
        bones["arm_left"] = rot([(0.0, (0, 0, 0)), (length * .3, (-30, 0, 78)), (length, (0, 0, -34))])
        bones["hair"] = rot([(0.0, (0, 0, 0)), (length * .3, (-14, 0, 0)), (length, (8, 0, 0))])
    else:
        bones[f"arm_{side}"] = rot(arm)
        bones[f"arm_{'left' if side == 'right' else 'right'}"] = rot(other)
    return {"animation_length": round(length, 2), "loop": False, "bones": bones}


def clip(slot, kind, length):
    """Slot poses: q crouch-and-rake, w sink-and-burst, e gather-and-throw, r split-and-strike."""
    poses = {
        "q": ((-56, 0, -34), (28, 0, 18), (0, 14, 0)),
        "w": ((-34, 0, -48), (18, 0, 40), (-10, 0, 0)),
        "e": ((-70, 0, -18), (36, 0, 10), (0, -10, 0)),
        "r": ((-88, 0, -8), (46, 0, 6), (0, 0, 0)),
    }
    push, twist, twist_body = poses[slot]
    body = (twist_body[0] * .5, twist_body[1] * .5, 0)
    if kind == "_windup":
        span = [0.0, length * .6, length]
        armk = [(0, 0, 0), push, push]
        bodyk = [(0, 0, 0), body, body]
        hairk = [(0, 0, 0), (-10, 0, 0), (-10, 0, 0)]
    elif kind == "_cast":
        span = [0.0, length * .22, length * .55, length]
        armk = [push, push, (twist[0] * .5, twist[1] * .5, twist[2] * .5), (0, 0, 0)]
        bodyk = [body, body, (body[0] * .3, body[1] * .3, 0), (0, 0, 0)]
        hairk = [(-10, 0, 0), (14, 0, 0), (6, 0, 0), (0, 0, 0)]
    else:
        span = [0.0, length * .25, length * .6, length]
        armk = [push, (push[0] * .4, push[1], push[2] - 26), (push[0] * -.2, push[1] * -.2, push[2] * -.2), (0, 0, 0)]
        bodyk = [body, (0, body[1] * .5, 0), (0, 0, 0), (0, 0, 0)]
        hairk = [(0, 0, 0), (18, 0, 0), (6, 0, 0), (0, 0, 0)]
    bones = {}
    for name, keys in (("arm_right", armk),
                       ("arm_left", [(k[1] * .3, 0, -k[2] * .3) for k in armk]),
                       ("body", bodyk),
                       ("torso", [(k[0] * .6, k[1] * .6, 0) for k in bodyk]),
                       ("head", [(k[0] * .25, -k[1] * .3, 0) for k in bodyk]),
                       ("hair", hairk)):
        bones[name] = {"rotation": {("%.2f" % t): [round(v, 2) for v in k] for t, k in zip(span, keys)}}
    return {"animation_length": round(length, 2), "loop": False, "bones": bones}


def main():
    anims = {
        # 0.133.6: the claws used to flash by in a quarter second; every swing now lasts long
        # enough to read, and the chain gap in BasicAttackInput was widened to match.
        "q_claw_l": claw("left", .42),
        "q_claw_r": claw("right", .42),
        "q_claw_cross": claw("right", .55, cross=True),
    }
    lengths = {
        "q": {"_windup": .20, "_cast": .65, "_melee": .45},
        "w": {"_windup": .25, "_cast": .50, "_melee": .42},
        "e": {"_windup": .25, "_cast": .65, "_melee": .45},
        "r": {"_windup": .35, "_cast": .65, "_melee": .45},
    }
    for slot in "qwer":
        for kind, length in lengths[slot].items():
            anims[f"{slot}{kind}"] = clip(slot, kind, length)
    payload = {"format_version": "1.8.0",
               "animations": {f"animation.pkmoba_hisuian_zoroark.{name}": body for name, body in anims.items()}}
    target = OUT / "pkmoba_hisuian_zoroark.animation.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(anims)} clips -> {target.name}")


if __name__ == "__main__":
    main()

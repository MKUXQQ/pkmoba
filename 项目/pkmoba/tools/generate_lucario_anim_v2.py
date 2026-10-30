"""0.92 Lucario body animations: real punches instead of arms spread sideways.

Bedrock arms hang down, so rotation X swings an arm forward/back and rotation Z pulls it out to
the side. The generated 0.52 clips used large Y/Z values, which is why Lucario kept standing with
both arms open. Every clip here is X-dominant with a small torso twist, so the body reads as
punching, hooking, guarding and dashing.
"""
import json
from pathlib import Path

OUT = Path('src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_lucario.animation.json')


def clip(length, bones):
    return {'animation_length': length, 'bones': bones}


def rot(points):
    return {'rotation': {str(t): v for t, v in points}}


def seq(*pairs):
    return rot(pairs)


STANCE_LEFT = (14.0, 6.0)     # (Y, Z) arms hang close to the body, not splayed out
STANCE_RIGHT = (-14.0, -6.0)


def arm(points, left=True):
    """X animation, with the stance Y/Z kept so the arm never splays out."""
    y, z = STANCE_LEFT if left else STANCE_RIGHT
    if isinstance(points, (int, float)):
        points = [(0, points)]
    return seq(*[(t, [v, y, z]) for t, v in points])


def limbs(torso, torso2, arm_left, arm_right, head, legs=(0, 0), tail=0, spine=None, key=None):
    """Build a pose dict from a few scalar channels; each arg may be a list of (time, value)."""
    def ch(series):
        if isinstance(series, (int, float)):
            series = [(0, series)]
        return series

    bones = {
        'torso': seq(*[(t, [v, 0, 0]) for t, v in ch(torso)]),
        'torso2': seq(*[(t, [v, 0, 0]) for t, v in ch(torso2)]),
        'head': seq(*[(t, [v, 0, 0]) for t, v in ch(head)]),
        'arm_left': arm(arm_left, True),
        'arm_right': arm(arm_right, False),
        'leg_left': seq(*[(t, [-v, 0, 0]) for t, v in ch(legs[0])]),
        'leg_right': seq(*[(t, [v, 0, 0]) for t, v in ch(legs[1])]),
    }
    if tail:
        bones['tail'] = seq(*[(t, [v, 0, 0]) for t, v in ch(tail)])
    if spine:
        bones['torso']['rotation'] = {str(t): [v[0], v[1], 0] for t, v in spine}
    return bones


ANIM = {}

# ---------------------------------------------------------------- base loops
for slot in 'qwer':
    ANIM[f'animation.pkmoba_lucario.{slot}'] = clip(1.2, limbs(
        torso=[(0, 0), (.6, 1.4), (1.2, 0)],
        torso2=[(0, 0), (.6, .8), (1.2, 0)],
        arm_left=[(0, 2), (.6, -3), (1.2, 2)],
        arm_right=[(0, 2), (.6, -3), (1.2, 2)],
        head=[(0, 0), (.6, -1.2), (1.2, 0)],
        legs=([(0, 0), (1.2, 0)], [(0, 0), (1.2, 0)]),
        tail=0))

# ---------------------------------------------------------------- Q 剑舞: arms cross then flare
ANIM['animation.pkmoba_lucario.q_windup'] = clip(.4, limbs(
    torso=[(0, 0), (.4, 7)], torso2=[(0, 0), (.4, 5)],
    arm_left=[(0, 0), (.4, 26)], arm_right=[(0, 0), (.4, 26)],
    head=[(0, 0), (.4, 5)],
    legs=([(0, 0), (.4, 6)], [(0, 0), (.4, 6)])))
ANIM['animation.pkmoba_lucario.q_cast'] = clip(.5, limbs(
    torso=[(0, 7), (.12, -9), (.5, 0)], torso2=[(0, 5), (.12, -6), (.5, 0)],
    arm_left=[(0, 26), (.12, -46), (.32, -40), (.5, 0)],
    arm_right=[(0, 26), (.12, -46), (.32, -40), (.5, 0)],
    head=[(0, 5), (.12, -8), (.5, 0)],
    legs=([(0, 6), (.5, 0)], [(0, 6), (.5, 0)])))
ANIM['animation.pkmoba_lucario.q_melee'] = clip(.34, limbs(
    torso=[(0, 0), (.08, -12), (.2, -6), (.34, 0)], torso2=[(0, 0), (.08, -8), (.34, 0)],
    arm_left=[(0, 10), (.08, -78), (.2, -24), (.34, 0)],
    arm_right=[(0, 10), (.08, -78), (.2, -24), (.34, 0)],
    head=[(0, 0), (.1, -8), (.34, 0)],
    legs=([(0, 0), (.12, 8), (.34, 0)], [(0, 0), (.12, 6), (.34, 0)])))

# ---------------------------------------------------------------- W 彗星拳: straight punch, alternating hand
ANIM['animation.pkmoba_lucario.w_windup'] = clip(.36, limbs(
    torso=[(0, 0), (.36, 4)], torso2=[(0, 0), (.36, 3)],
    arm_left=[(0, 0), (.36, -14)], arm_right=[(0, 0), (.36, 32)],
    head=[(0, 0), (.36, 3)],
    legs=([(0, 0), (.36, 5)], [(0, 0), (.36, 5)]),
    spine=[(0, [0, 0]), (.36, [4, 14])]))
ANIM['animation.pkmoba_lucario.w_melee'] = clip(.3, limbs(
    torso=[(0, 4), (.08, -8), (.3, 0)], torso2=[(0, 3), (.08, -5), (.3, 0)],
    arm_left=[(0, -14), (.3, -10)], arm_right=[(0, 32), (.08, -96), (.18, -92), (.3, 0)],
    head=[(0, 3), (.1, -6), (.3, 0)],
    legs=([(0, 5), (.3, 0)], [(0, 5), (.12, 6), (.3, 0)]),
    spine=[(0, [4, 14]), (.08, [-6, -12]), (.3, [0, 0])]))
ANIM['animation.pkmoba_lucario.w_melee2'] = clip(.3, limbs(
    torso=[(0, 4), (.08, -8), (.3, 0)], torso2=[(0, 3), (.08, -5), (.3, 0)],
    arm_right=[(0, -14), (.3, -10)], arm_left=[(0, 32), (.08, -96), (.18, -92), (.3, 0)],
    head=[(0, 3), (.1, -6), (.3, 0)],
    legs=([(0, 5), (.12, 6), (.3, 0)], [(0, 5), (.3, 0)]),
    spine=[(0, [4, 14]), (.08, [-6, 12]), (.3, [0, 0])]))
ANIM['animation.pkmoba_lucario.w_cast'] = clip(.45, limbs(
    torso=[(0, 4), (.1, -9), (.45, 0)], torso2=[(0, 3), (.1, -6), (.45, 0)],
    arm_left=[(0, -14), (.1, -72), (.26, -68), (.45, 0)],
    arm_right=[(0, 32), (.1, -74), (.26, -70), (.45, 0)],
    head=[(0, 3), (.1, -7), (.45, 0)],
    legs=([(0, 5), (.45, 0)], [(0, 5), (.45, 0)]),
    spine=[(0, [4, 14]), (.1, [-8, 0]), (.45, [0, 0])]))

# ---------------------------------------------------------------- E 雷电拳: left hook then right cross
ANIM['animation.pkmoba_lucario.e_windup'] = clip(.36, limbs(
    torso=[(0, 0), (.36, 5)], torso2=[(0, 0), (.36, 4)],
    arm_left=[(0, 0), (.36, 16)], arm_right=[(0, 0), (.36, 14)],
    head=[(0, 0), (.36, 4)],
    legs=([(0, 0), (.36, 6)], [(0, 0), (.36, 6)]),
    spine=[(0, [0, 0]), (.36, [4, -16])]))
ANIM['animation.pkmoba_lucario.e_melee'] = clip(.42, limbs(
    torso=[(0, 5), (.1, -10), (.24, -6), (.42, 0)], torso2=[(0, 4), (.1, -7), (.42, 0)],
    arm_left=[(0, 16), (.1, -80), (.24, -36), (.42, 0)],
    arm_right=[(0, 14), (.24, -80), (.34, -26), (.42, 0)],
    head=[(0, 4), (.12, -7), (.42, 0)],
    legs=([(0, 6), (.42, 0)], [(0, 6), (.42, 0)]),
    spine=[(0, [4, -16]), (.1, [-8, 14]), (.24, [-8, -14]), (.42, [0, 0])]))
ANIM['animation.pkmoba_lucario.e_cast'] = clip(.45, limbs(
    torso=[(0, 5), (.12, -9), (.45, 0)], torso2=[(0, 4), (.12, -6), (.45, 0)],
    arm_left=[(0, 26), (.12, -74), (.3, -60), (.45, 0)],
    arm_right=[(0, 20), (.3, -70), (.45, 0)],
    head=[(0, 4), (.14, -6), (.45, 0)],
    legs=([(0, 6), (.45, 0)], [(0, 6), (.45, 0)]),
    spine=[(0, [4, -16]), (.12, [-8, 10]), (.45, [0, 0])]))

# ---------------------------------------------------------------- R 神速: crouch, then a flat sprint lean
ANIM['animation.pkmoba_lucario.r_windup'] = clip(.3, limbs(
    torso=[(0, 0), (.3, 10)], torso2=[(0, 0), (.3, 6)],
    arm_left=[(0, 0), (.3, 18)], arm_right=[(0, 0), (.3, 18)],
    head=[(0, 0), (.3, 6)],
    legs=([(0, 0), (.3, 10)], [(0, 0), (.3, 10)])))
ANIM['animation.pkmoba_lucario.r_cast'] = clip(.6, limbs(
    torso=[(0, 10), (.12, -22), (.6, 0)], torso2=[(0, 6), (.12, -14), (.6, 0)],
    arm_left=[(0, 18), (.12, -16), (.4, -10), (.6, 0)],
    arm_right=[(0, 18), (.12, -16), (.4, -10), (.6, 0)],
    head=[(0, 6), (.12, -12), (.6, 0)],
    legs=([(0, 12), (.12, -12), (.6, 0)], [(0, 12), (.12, 14), (.6, 0)])))
ANIM['animation.pkmoba_lucario.r_melee'] = clip(.34, limbs(
    torso=[(0, 0), (.08, -24), (.22, -16), (.34, 0)], torso2=[(0, 0), (.08, -14), (.34, 0)],
    arm_left=[(0, 0), (.08, -64), (.34, 0)], arm_right=[(0, 0), (.08, -64), (.34, 0)],
    head=[(0, 0), (.1, -12), (.34, 0)],
    legs=([(0, 0), (.1, 10), (.34, 0)], [(0, 0), (.1, -10), (.34, 0)])))

# keep the sustain channel the 0.90 json already advertised
ANIM['animation.pkmoba_lucario.e_sustain'] = clip(1.0, limbs(
    torso=[(0, -4), (.5, -8), (1, -4)], torso2=[(0, -2), (.5, -5), (1, -2)],
    arm_left=[(0, -26), (.5, -34), (1, -26)],
    arm_right=[(0, -26), (.5, -34), (1, -26)],
    head=[(0, -2), (1, -2)],
    legs=([(0, 0), (1, 0)], [(0, 0), (1, 0)])))

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({'format_version': '1.8.0', 'animations': ANIM}, ensure_ascii=False, indent=1),
               encoding='utf-8')
print(f'wrote {len(ANIM)} lucario clips')

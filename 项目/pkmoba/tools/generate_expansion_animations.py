"""Generate tailored QWER animation tracks, rejecting bone names absent from any gender model.

For every skill index this emits four clips:
  <slot>          legacy one-shot (kept: ExpansionEffects falls back to it)
  <slot>_windup   charge into the pose and hold the peek; `start` cue plays it, `release` takes over
  <slot>_cast     overshoot + settle; `release` cue plays it for instant skills
  <slot>_sustain  seamless loop ("loop": true); played while a sustained skill channels

Timings mirror ExpansionRoster. Run from the repo with:
    python 项目/pkmoba/tools/generate_expansion_animations.py
"""
import json
import zipfile
from pathlib import Path

project = Path(__file__).resolve().parents[1]
models = project.parent / '宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon/models'
cobblemon_jar = project / '.local-maven/com/cobblemon/fabric/1.8.2+1.21.1/fabric-1.8.2+1.21.1.jar'
out = project / 'src/main/resources/assets/pkmoba/bedrock/pokemon/animations'


def species_bones(species):
    """Every bone name in the real geometry, read from the sources folder or the Cobblemon jar."""
    found = set()
    for model in models.glob(f'*/{species}*.geo.json'):
        data = json.loads(model.read_text(encoding='utf-8'))
        for geometry in data['minecraft:geometry']:
            found |= {bone['name'] for bone in geometry['bones']}
    if found:
        return found
    with zipfile.ZipFile(cobblemon_jar) as jar:
        for entry in jar.namelist():
            if not entry.endswith('.geo.json'):
                continue
            parts = entry.split('/')
            if len(parts) < 2 or species not in parts[-2].lower():
                continue
            data = json.loads(jar.read(entry))
            for geometry in data['minecraft:geometry']:
                found |= {bone['name'] for bone in geometry['bones']}
    return found

# Each tuple: torso, left/right arm, left/right leg. Names are from the actual geometry audit.
bones = {
    'lucario': ('torso','arm_left','arm_right','leg_left','leg_right'),
    'abomasnow': ('torso','arm_left','arm_right','leg_left','leg_right'),
    'blaziken': ('torso','arm_left','arm_right','leg_left','leg_right'),
    'gengar': ('torso','arm_left','arm_right','leg_left','leg_right'),
    'tyranitar': ('torso','arm_left','arm_right','leg_left','leg_right'),
    'garchomp': ('torso','arm_left','arm_right','leg_left0','leg_right0'),
    'scizor': ('thorax','left_upper_arm','right_upper_arm','left_upper_leg','right_upper_leg'),
    'gardevoir': ('chest','arm_left','arm_right','leg_left','leg_right'),
}
# Four distinct poses per species: (lean, leftArmXyz, rightArmXyz, leftLegX, rightLegX).
poses = {
 'lucario': [(8,(-60,0,-30),(-60,0,30),0,0),(15,(-20,-30,-20),(-95,30,10),-15,15),(40,(-35,0,-25),(-35,0,25),-45,35),(20,(-100,0,-30),(-25,0,30),-65,40)],
 'abomasnow': [(10,(-35,0,-20),(-100,0,25),0,0),(18,(-45,0,-15),(-45,0,15),0,0),(-12,(-120,0,-35),(-120,0,35),10,10),(5,(-90,0,-55),(-90,0,55),-10,10)],
 'blaziken': [(20,(-30,0,-40),(-55,0,25),-100,25),(15,(-60,0,-30),(-20,0,30),35,-110),(45,(-70,0,-15),(-70,0,15),-50,45),(60,(-90,0,-25),(-90,0,25),-80,60)],
 'gengar': [(-8,(-60,0,-35),(-60,0,35),0,0),(15,(-20,0,-30),(-100,0,30),0,0),(-12,(-85,0,-50),(-85,0,50),0,0),(35,(-90,0,-55),(-90,0,55),-20,20)],
 'tyranitar': [(15,(-100,0,-20),(-100,0,20),10,10),(30,(-20,0,-20),(-20,0,20),-20,20),(5,(-40,0,-40),(-40,0,40),-12,12),(25,(-75,0,-35),(-75,0,35),-30,25)],
 'garchomp': [(25,(-70,-50,-20),(-70,50,20),-20,20),(35,(-20,0,-50),(-20,0,50),-35,20),(70,(-80,0,-30),(-80,0,30),-35,35),(55,(-60,0,-60),(-60,0,60),-55,45)],
 'scizor': [(25,(-20,0,-35),(-100,0,15),-30,25),(10,(-85,-45,-15),(-85,45,15),0,0),(40,(-55,0,-30),(-55,0,30),-45,40),(-10,(-40,0,-80),(-40,0,80),-15,15)],
 'gardevoir': [(-8,(-65,0,-25),(-65,0,25),0,0),(-15,(-105,0,-40),(-105,0,40),0,0),(0,(-25,0,-70),(-25,0,70),0,0),(-20,(-120,0,-55),(-120,0,55),0,0)],
}

# Contact-cue skills: attack body pose is played only on server phase=melee.
MELEE = {'lucario': [1, 2, 3], 'blaziken': [0, 1, 2, 3], 'tyranitar': [1],
         'garchomp': [0, 1, 2, 3], 'scizor': [0, 1, 2], 'gengar': [3]}

# Timings mirror ExpansionRoster (windup, duration in ticks).
TIMINGS = {
 'lucario':[(8,28),(6,6),(3,10),(12,32)],
 'abomasnow':[(8,26),(8,20),(12,80),(24,80)],
 'blaziken':[(5,8),(5,16),(4,12),(18,16)],
 'gengar':[(8,30),(8,24),(12,6),(12,14)],
 'tyranitar':[(20,8),(8,6),(8,4),(20,30)],
 'garchomp':[(5,10),(10,6),(10,24),(16,16)],
 'scizor':[(3,8),(4,20),(4,18),(12,4)],
 'gardevoir':[(8,28),(10,30),(6,4),(40,8)]}

SLOTS = ('q','w','e','r')
# Must mirror ExpansionEffects.sustained(style) per species/skill:
# lucario e(RUSH); abomasnow e(WALL) r(ICE_STORM); blaziken e r(RUSH); gengar w(LOB);
# tyranitar e(SAND_STORM); garchomp e(DIG) r(RUSH); scizor q e(RUSH) r(BLADES); gardevoir w(LOB).
SUSTAINED = {'lucario':[2], 'abomasnow':[2,3], 'blaziken':[2,3], 'gengar':[1], 'tyranitar':[2],
             'garchomp':[2,3], 'scizor':[0,2,3], 'gardevoir':[1]}
# Skills that read best with a body spin during the cast (charge/rush flavours).
SPIN_CAST = {('blaziken',3), ('garchomp',3), ('lucario',2)}
# Signature torso offsets in bedrock units (1 = 1/16 block): (windup, cast).
SPECIAL = {
 ('garchomp',2): ((0,-14,0),(0,3,0)),   # dig: sink underground, then erupt
 ('gardevoir',1): ((0,4,0),(0,2,0)),    # moon blast: float up and hurl
 ('gengar',1): ((0,2,0),(0,5,0)),       # sludge bomb: load low, lob high
 ('gengar',3): ((0,1,0),(0,0,0)),       # dream eater: lean into the drain
 ('tyranitar',2): ((0,0,0),(0,2,0)),    # sandstorm: arms out, chest up
 ('scizor',3): ((0,3,0),(0,1,0)),       # swords dance: rise on the blades
 ('abomasnow',2): ((0,1,0),(0,0,0)),    # ice wall: brace and push
 ('blaziken',3): ((0,2,0),(0,4,0)),     # flare blitz: leap into the charge
}


def scale(values, factor):
    return [round(v * factor, 1) for v in values]


def keyed(pairs):
    """Bedrock rotation/position map with rounded, time-sorted keys."""
    return {str(round(t, 2)): v for t, v in sorted(pairs, key=lambda p: p[0])}


def track(rotation_xyz, windup, active, release=1.0, repeat=False):
    """Legacy one-shot clip, unchanged from the first pass (kept for compatibility)."""
    start = windup / 20
    end = (windup + active) / 20
    keys = {'0.0': [0, 0, 0], str(round(start, 3)): rotation_xyz}
    if repeat:
        for tick in range(1, active, 4):
            factor = release if (tick // 4) % 2 == 0 else -.3
            keys[str(round(start + tick / 20, 3))] = [v * factor for v in rotation_xyz]
    else:
        keys[str(round(start + min(.15, active / 40), 3))] = [v * release for v in rotation_xyz]
        keys[str(round(max(start + .05, end - .1), 3))] = [v * .8 for v in rotation_xyz]
    keys[str(round(end + .15, 3))] = [0, 0, 0]
    return {'rotation': dict(sorted(keys.items(), key=lambda item: float(item[0])))}


def pose_of(species, index):
    """All five bone rotations plus the optional torso offset for one skill."""
    lean, left, right, leg_left, leg_right = poses[species][index]
    return [ [lean, 0, 0], list(left), list(right), [leg_left, 0, 0], [leg_right, 0, 0] ]


def shaped(tracks, names, factors, positions=None, yspin=0.0):
    """Build a bone map: each bone gets the same time keys with its own scaled pose."""
    result = {}
    for slot, name in enumerate(names):
        base = poses_frame[slot]
        if slot >= 3 and slot < 5 and not base:
            continue
        entries = {}
        for time, factor in factors:
            value = scale(base, factor)
            if slot == 0 and yspin:
                value = [value[0], round(yspin * min(1.0, abs(factor - 1.0) * 3), 1), value[2]]
            entries[time] = value
        result[name] = {'rotation': keyed(list(entries.items()))}
    if positions:
        for name, entries in positions.items():
            pairs = list(entries.items()) if isinstance(entries, dict) else list(entries)
            # Callers write "torso" generically; scizor's body bone is called "thorax".
            target = name if name in result else names[0]
            result[target]['position'] = keyed(pairs)
    return result


poses_frame = []


def windup_clip(species, index, names):
    """Charge into the pose and hold the peek: `release` swaps in the cast clip at the last tick."""
    global poses_frame
    poses_frame = pose_of(species, index)
    lean = poses_frame[0][0]
    hold = max(0.1, TIMINGS[species][index][0] / 20)
    ease = min(0.16, round(hold * 0.45, 2))
    factors = [(0.0, 0.0), (ease, 0.92), (round(hold, 2), 0.92)]
    positions = None
    special = SPECIAL.get((species, index))
    if special:
        positions = {'torso': [(0.0, [0, 0, 0]), (ease, list(special[0])), (round(hold, 2), list(special[0]))]}
    return {'animation_length': round(hold + 0.06, 2),
            'bones': shaped(None, names, factors, positions)}


def cast_clip(species, index, names):
    """Overshoot past the pose, settle, then release: the readable hit frames."""
    global poses_frame
    poses_frame = pose_of(species, index)
    duration = max(0.26, min(1.1, TIMINGS[species][index][1] / 20))
    snap = round(min(0.12, duration * 0.4), 2)
    settle = round(max(snap + 0.1, duration * 0.6), 2)
    # Start above zero so the swap from the held windup pose does not pop back to idle first.
    factors = [(0.0, 0.85), (snap, 1.25), (settle, 0.8), (round(duration, 2), 0.0)]
    positions = None
    special = SPECIAL.get((species, index))
    if special:
        positions = {'torso': [(0.0, [0, 0, 0]), (snap, list(special[1])), (settle, [0, 0, 0]), (round(duration, 2), [0, 0, 0])]}
    tracks = shaped(None, names, factors, positions,
                    yspin=360.0 if (species, index) in SPIN_CAST else 0.0)
    return {'animation_length': round(duration, 2), 'bones': tracks}


def sustain_clip(species, index, names):
    """Seamless loop: no zero keys at the ends, so channeled skills do not snap back to idle."""
    global poses_frame
    poses_frame = pose_of(species, index)
    factors = [(0.0, 0.62), (0.45, 0.92), (0.9, 0.62)]
    positions = None
    special = SPECIAL.get((species, index)) or SPECIAL.get((species, index))
    if special:
        base = list(special[0])
        positions = {'torso': [(0.0, scale(base, 0.6)), (0.45, base), (0.9, scale(base, 0.6))]}
    return {'loop': True, 'animation_length': 0.9, 'bones': shaped(None, names, factors, positions)}


def keys(*pairs):
    return {str(t): v for t, v in sorted(pairs, key=lambda pair: pair[0])}


def rot(*pairs):
    return {'rotation': keys(*pairs)}


def rotpos(rotation_pairs, position_pairs):
    return {'rotation': keys(*rotation_pairs), 'position': keys(*position_pairs)}


def anim(length, bone_map, loop=False):
    data = {'animation_length': length, 'bones': bone_map}
    if loop:
        data['loop'] = True
    return data


# Hand-authored clips for the two species whose shared template looked repetitive in game.
# Bone names are taken from the real Cobblemon geometry and asserted below.
# A move that kicks must move upper leg / lower leg / foot separately, and a combo move
# must have several distinct strike frames, which is exactly what the template could not do.
CUSTOM = {
    'blaziken': {
        # Double Kick: left leg snap kick. Waist and torso counter-twist, arms counterbalance.
        'w_kick_left': anim(0.36, {
            'waist': rot((0, [0, 0, 0]), (0.10, [-4, 14, -3]), (0.20, [-6, 20, -5]), (0.36, [0, 0, 0])),
            'torso': rot((0, [0, 0, 0]), (0.10, [-6, -10, 3]), (0.20, [-10, -16, 5]), (0.36, [0, 0, 0])),
            'leg_left': rot((0, [0, 0, 0]), (0.10, [-78, 0, -6]), (0.18, [-110, 0, -10]),
                            (0.26, [-46, 0, -5]), (0.36, [0, 0, 0])),
            'leg_left2': rot((0, [0, 0, 0]), (0.10, [-20, 0, 0]), (0.18, [-42, 0, 0]),
                             (0.26, [-8, 0, 0]), (0.36, [0, 0, 0])),
            'foot_left': rot((0, [0, 0, 0]), (0.14, [18, 0, 0]), (0.20, [36, 0, 0]), (0.30, [0, 0, 0])),
            'leg_right': rot((0, [0, 0, 0]), (0.18, [14, 0, 0]), (0.30, [4, 0, 0]), (0.36, [0, 0, 0])),
            'arm_left': rot((0, [0, 0, 0]), (0.10, [-25, 0, 35]), (0.20, [-45, 0, 62]), (0.36, [0, 0, 0])),
            'arm_right': rot((0, [0, 0, 0]), (0.10, [-15, 0, -30]), (0.20, [-30, 0, -52]), (0.36, [0, 0, 0])),
            'tail1': rot((0, [0, 0, 0]), (0.10, [0, -12, 0]), (0.20, [0, -22, 0]), (0.36, [0, 0, 0])),
        }),
        # Double Kick: mirrored right leg snap kick, so two beats read as two different legs.
        'w_kick_right': anim(0.36, {
            'waist': rot((0, [0, 0, 0]), (0.10, [-4, -14, 3]), (0.20, [-6, -20, 5]), (0.36, [0, 0, 0])),
            'torso': rot((0, [0, 0, 0]), (0.10, [-6, 10, -3]), (0.20, [-10, 16, -5]), (0.36, [0, 0, 0])),
            'leg_right': rot((0, [0, 0, 0]), (0.10, [-78, 0, 6]), (0.18, [-110, 0, 10]),
                             (0.26, [-46, 0, 5]), (0.36, [0, 0, 0])),
            'leg_right2': rot((0, [0, 0, 0]), (0.10, [-20, 0, 0]), (0.18, [-42, 0, 0]),
                              (0.26, [-8, 0, 0]), (0.36, [0, 0, 0])),
            'foot_right': rot((0, [0, 0, 0]), (0.14, [18, 0, 0]), (0.20, [36, 0, 0]), (0.30, [0, 0, 0])),
            'leg_left': rot((0, [0, 0, 0]), (0.18, [14, 0, 0]), (0.30, [4, 0, 0]), (0.36, [0, 0, 0])),
            'arm_right': rot((0, [0, 0, 0]), (0.10, [-25, 0, -35]), (0.20, [-45, 0, -62]), (0.36, [0, 0, 0])),
            'arm_left': rot((0, [0, 0, 0]), (0.10, [-15, 0, 30]), (0.20, [-30, 0, 52]), (0.36, [0, 0, 0])),
            'tail1': rot((0, [0, 0, 0]), (0.10, [0, 12, 0]), (0.20, [0, 22, 0]), (0.36, [0, 0, 0])),
        }),
        # Blaze Kick: one high right roundhouse with a full body wind-up and follow-through.
        'q_melee': anim(0.42, {
            'waist': rot((0, [0, 0, 0]), (0.10, [-6, 14, 4]), (0.18, [-10, -26, 10]),
                         (0.28, [-6, -14, 6]), (0.42, [0, 0, 0])),
            'torso': rot((0, [0, 0, 0]), (0.10, [-8, -10, 0]), (0.18, [-16, 18, -10]),
                         (0.28, [-8, 8, -4]), (0.42, [0, 0, 0])),
            'leg_right': rot((0, [0, 0, 0]), (0.08, [-52, 0, 8]), (0.16, [-122, 0, 14]),
                             (0.24, [-96, 0, 8]), (0.42, [0, 0, 0])),
            'leg_right2': rot((0, [0, 0, 0]), (0.16, [-48, 0, 0]), (0.26, [-20, 0, 0]), (0.42, [0, 0, 0])),
            'foot_right': rot((0, [0, 0, 0]), (0.16, [42, 0, 0]), (0.30, [0, 0, 0])),
            'leg_left': rot((0, [0, 0, 0]), (0.18, [18, 0, 0]), (0.42, [0, 0, 0])),
            'arm_left': rot((0, [0, 0, 0]), (0.10, [-20, 0, 25]), (0.20, [-55, 0, 70]), (0.42, [0, 0, 0])),
            'arm_right': rot((0, [0, 0, 0]), (0.10, [-30, 0, -45]), (0.20, [-15, 0, -20]), (0.42, [0, 0, 0])),
        }),
        # Blaze Kick / Blaze Charge contact: explosive forward shoulder-and-knee drive.
        'e_melee': anim(0.34, {
            'waist': rot((0, [0, 0, 0]), (0.08, [16, 0, 0]), (0.14, [24, -6, 0]), (0.34, [0, 0, 0])),
            'torso': rot((0, [0, 0, 0]), (0.08, [18, 0, 0]), (0.14, [26, 4, 0]), (0.34, [0, 0, 0])),
            'leg_left': rot((0, [0, 0, 0]), (0.10, [-70, 0, 0]), (0.18, [-30, 0, 0]), (0.34, [0, 0, 0])),
            'leg_left2': rot((0, [0, 0, 0]), (0.14, [-40, 0, 0]), (0.34, [0, 0, 0])),
            'leg_right': rot((0, [0, 0, 0]), (0.08, [28, 0, 0]), (0.24, [-20, 0, 0]), (0.34, [0, 0, 0])),
            'arm_left': rot((0, [0, 0, 0]), (0.08, [35, 0, 15]), (0.34, [0, 0, 0])),
            'arm_right': rot((0, [0, 0, 0]), (0.08, [35, 0, -15]), (0.34, [0, 0, 0])),
        }),
        # Flare Blitz: whole body becomes the projectile.
        'r_melee': anim(0.46, {
            'waist': rot((0, [0, 0, 0]), (0.10, [26, 0, 0]), (0.22, [34, 0, 0]), (0.46, [0, 0, 0])),
            'torso': rot((0, [0, 0, 0]), (0.10, [30, 0, 0]), (0.22, [38, 0, 0]), (0.46, [0, 0, 0])),
            'leg_left': rot((0, [0, 0, 0]), (0.10, [-85, 0, 0]), (0.22, [25, 0, 0]), (0.46, [0, 0, 0])),
            'leg_left2': rot((0, [0, 0, 0]), (0.14, [-45, 0, 0]), (0.46, [0, 0, 0])),
            'leg_right': rot((0, [0, 0, 0]), (0.10, [30, 0, 0]), (0.22, [-80, 0, 0]), (0.46, [0, 0, 0])),
            'leg_right2': rot((0, [0, 0, 0]), (0.22, [-40, 0, 0]), (0.46, [0, 0, 0])),
            'arm_left': rot((0, [0, 0, 0]), (0.10, [45, 0, 20]), (0.22, [-30, 0, 30]), (0.46, [0, 0, 0])),
            'arm_right': rot((0, [0, 0, 0]), (0.10, [45, 0, -20]), (0.22, [-30, 0, -30]), (0.46, [0, 0, 0])),
            'tail1': rot((0, [0, 0, 0]), (0.10, [-20, 0, 0]), (0.22, [10, 0, 0]), (0.46, [0, 0, 0])),
        }),
    },
    'scizor': {
        # Bullet Punch: four fast alternating straight punches, not one slow arm wave.
        'q_melee': anim(0.38, {
            'right_upper_arm': rot((0, [-15, 0, 0]), (0.05, [-100, 0, 0]), (0.10, [-25, 0, 0]),
                                   (0.15, [-100, 0, 0]), (0.20, [-25, 0, 0]), (0.25, [-105, 0, 0]),
                                   (0.38, [-15, 0, 0])),
            'right_lower_arm': rot((0, [0, 0, 0]), (0.05, [-15, 0, 0]), (0.10, [10, 0, 0]),
                                   (0.15, [-15, 0, 0]), (0.20, [10, 0, 0]), (0.25, [-20, 0, 0]),
                                   (0.38, [0, 0, 0])),
            'right_claw': rot((0, [0, 0, 0]), (0.05, [-25, 0, 0]), (0.10, [5, 0, 0]),
                              (0.25, [-30, 0, 0]), (0.38, [0, 0, 0])),
            'left_upper_arm': rot((0, [-10, 0, 0]), (0.10, [-70, 0, 0]), (0.16, [-15, 0, 0]),
                                  (0.22, [-80, 0, 0]), (0.38, [-10, 0, 0])),
            'left_lower_arm': rot((0, [0, 0, 0]), (0.10, [-20, 0, 0]), (0.16, [5, 0, 0]),
                                  (0.22, [-22, 0, 0]), (0.38, [0, 0, 0])),
            'thorax': rot((0, [0, 0, 0]), (0.05, [-4, 7, 0]), (0.12, [0, -5, 0]),
                          (0.20, [-4, 8, 0]), (0.30, [0, -3, 0]), (0.38, [0, 0, 0])),
            'left_upper_leg': rot((0, [0, 0, 0]), (0.10, [-16, 0, 0]), (0.20, [0, 0, 0]), (0.38, [0, 0, 0])),
            'right_upper_leg': rot((0, [0, 0, 0]), (0.15, [-14, 0, 0]), (0.25, [0, 0, 0]), (0.38, [0, 0, 0])),
            'head': rot((0, [0, 0, 0]), (0.05, [0, -6, 0]), (0.20, [0, 6, 0]), (0.38, [0, 0, 0])),
        }),
        # Fury Cutter: three visibly different diagonal slashes.
        'w_melee': anim(0.55, {
            'right_upper_arm': rot((0, [-20, 0, 0]), (0.08, [-120, 45, 0]), (0.18, [-30, -35, 0]),
                                   (0.26, [-125, 45, 0]), (0.36, [-30, -35, 0]), (0.44, [-130, 50, 0]),
                                   (0.55, [-20, 0, 0])),
            'right_lower_arm': rot((0, [0, 0, 0]), (0.08, [-20, 0, -25]), (0.18, [10, 0, 25]),
                                   (0.26, [-20, 0, -25]), (0.36, [10, 0, 25]), (0.44, [-25, 0, -30]),
                                   (0.55, [0, 0, 0])),
            'right_claw': rot((0, [0, 0, 0]), (0.08, [0, 0, -35]), (0.18, [0, 0, 20]),
                              (0.26, [0, 0, -35]), (0.36, [0, 0, 20]), (0.44, [0, 0, -40]),
                              (0.55, [0, 0, 0])),
            'left_upper_arm': rot((0, [-30, 0, 0]), (0.12, [-100, -40, 0]), (0.24, [-40, 30, 0]),
                                  (0.38, [-105, -40, 0]), (0.55, [-30, 0, 0])),
            'left_claw': rot((0, [0, 0, 0]), (0.12, [0, 0, 30]), (0.24, [0, 0, -25]),
                             (0.38, [0, 0, 30]), (0.55, [0, 0, 0])),
            'thorax': rot((0, [0, 0, 0]), (0.08, [-5, 10, 0]), (0.18, [0, -10, 0]),
                          (0.26, [-5, 12, 0]), (0.36, [0, -12, 0]), (0.44, [-6, 14, 0]),
                          (0.55, [0, 0, 0])),
            'left_upper_leg': rot((0, [0, 0, 0]), (0.14, [-18, 0, 0]), (0.30, [0, 0, 0]), (0.55, [0, 0, 0])),
            'right_upper_leg': rot((0, [0, 0, 0]), (0.30, [-16, 0, 0]), (0.44, [0, 0, 0]), (0.55, [0, 0, 0])),
        }),
        # U-turn: a real fast body flip so it stops looking like another arm swing.
        'e_melee': anim(0.40, {
            'body': rot((0, [0, 0, 0]), (0.20, [0, 180, 0]), (0.40, [0, 360, 0])),
            'thorax': rot((0, [0, 0, 0]), (0.10, [-10, 0, 0]), (0.30, [-6, 0, 0]), (0.40, [0, 0, 0])),
            'left_upper_arm': rot((0, [-20, 0, 0]), (0.12, [-110, -30, 0]), (0.40, [-20, 0, 0])),
            'right_upper_arm': rot((0, [-20, 0, 0]), (0.12, [-110, 30, 0]), (0.40, [-20, 0, 0])),
            'left_upper_leg': rot((0, [0, 0, 0]), (0.20, [-30, 0, 0]), (0.40, [0, 0, 0])),
            'right_upper_leg': rot((0, [0, 0, 0]), (0.20, [25, 0, 0]), (0.40, [0, 0, 0])),
        }),
        # Swords Dance: rise, cross the claws, then spread them overhead with a held flourish.
        'r_cast': anim(0.90, {
            'body': rot((0, [0, 0, 0]), (0.20, [-6, -12, 0]), (0.50, [-8, 12, 0]), (0.90, [0, 0, 0])),
            'thorax': rotpos([(0, [0, 0, 0]), (0.20, [-8, 0, 0]), (0.50, [-6, 0, 0]), (0.90, [0, 0, 0])],
                             [(0, [0, 0, 0]), (0.20, [0, 2, 0]), (0.50, [0, 3, 0]), (0.90, [0, 0, 0])]),
            'left_upper_arm': rot((0, [-20, 0, 0]), (0.15, [-150, 0, -20]), (0.45, [-160, 0, -35]),
                                  (0.90, [-20, 0, 0])),
            'right_upper_arm': rot((0, [-20, 0, 0]), (0.15, [-150, 0, 20]), (0.45, [-160, 0, 35]),
                                   (0.90, [-20, 0, 0])),
            'left_lower_arm': rot((0, [0, 0, 0]), (0.15, [-25, 0, 0]), (0.45, [-10, 0, 0]), (0.90, [0, 0, 0])),
            'right_lower_arm': rot((0, [0, 0, 0]), (0.15, [-25, 0, 0]), (0.45, [-10, 0, 0]), (0.90, [0, 0, 0])),
            'left_claw': rot((0, [0, 0, 0]), (0.15, [0, 0, 40]), (0.50, [0, 0, -25]), (0.90, [0, 0, 0])),
            'right_claw': rot((0, [0, 0, 0]), (0.15, [0, 0, -40]), (0.50, [0, 0, 25]), (0.90, [0, 0, 0])),
            'left_upper_wing': rot((0, [0, 0, 0]), (0.30, [0, 0, 25]), (0.60, [0, 0, 15]), (0.90, [0, 0, 0])),
            'right_upper_wing': rot((0, [0, 0, 0]), (0.30, [0, 0, -25]), (0.60, [0, 0, -15]), (0.90, [0, 0, 0])),
            'head': rot((0, [0, 0, 0]), (0.30, [-18, 0, 0]), (0.70, [-14, 0, 0]), (0.90, [0, 0, 0])),
        }),
        # Swords Dance loop: claws held high, slowly circling, body bobbing on the blades.
        'r_sustain': anim(1.20, {
            'body': rot((0, [0, 0, 0]), (0.30, [-6, -8, 0]), (0.90, [-6, 8, 0]), (1.20, [0, 0, 0])),
            'thorax': rotpos([(0, [-8, 0, 0]), (0.60, [-4, 0, 0]), (1.20, [-8, 0, 0])],
                             [(0, [0, 2, 0]), (0.60, [0, 3, 0]), (1.20, [0, 2, 0])]),
            'left_upper_arm': rot((0, [-155, -10, -30]), (0.60, [-150, 15, -30]), (1.20, [-155, -10, -30])),
            'right_upper_arm': rot((0, [-150, 15, 30]), (0.60, [-155, -10, 30]), (1.20, [-150, 15, 30])),
            'left_claw': rot((0, [0, 0, 35]), (0.40, [0, 0, -20]), (0.80, [0, 0, 30]), (1.20, [0, 0, 35])),
            'right_claw': rot((0, [0, 0, -35]), (0.40, [0, 0, 20]), (0.80, [0, 0, -30]), (1.20, [0, 0, -35])),
            'left_upper_wing': rot((0, [0, 0, 20]), (0.60, [0, 0, 30]), (1.20, [0, 0, 20])),
            'right_upper_wing': rot((0, [0, 0, -20]), (0.60, [0, 0, -30]), (1.20, [0, 0, -20])),
            'head': rot((0, [-14, 0, 0]), (0.60, [-16, 0, 0]), (1.20, [-14, 0, 0])),
        }, loop=True),
    },
}


out.mkdir(parents=True, exist_ok=True)
for species, names in bones.items():
    actual = species_bones(species)
    assert actual, f'no geometry found for {species}'
    assert set(names) <= actual, (species, set(names) - actual)
    for clip_name, clip in CUSTOM.get(species, {}).items():
        missing = set(clip['bones']) - actual
        assert not missing, (species, clip_name, missing)
    clips = {}
    for index, (lean, left, right, legleft, legright) in enumerate(poses[species]):
        body, armleft, armright, ll, lr = names
        windup_ticks, active_ticks = TIMINGS[species][index]
        combo = (species == 'lucario' and index == 3) or (species in ('scizor', 'blaziken') and index == 1)
        tracks = {body: track([lean, 0, 0], windup_ticks, active_ticks),
                  armleft: track(left, windup_ticks, active_ticks, 1.2, combo),
                  armright: track(right, windup_ticks, active_ticks, 1.2, combo),
                  ll: track([legleft, 0, 0], windup_ticks, active_ticks, 1.0, combo and species != 'scizor'),
                  lr: track([legright, 0, 0], windup_ticks, active_ticks, 1.0, combo and species != 'scizor')}
        slot = SLOTS[index]
        clips[f'animation.pkmoba_{species}.{slot}'] = {
            'animation_length': round((windup_ticks + active_ticks) / 20 + .15, 3), 'bones': tracks}
        clips[f'animation.pkmoba_{species}.{slot}_windup'] = windup_clip(species, index, names)
        clips[f'animation.pkmoba_{species}.{slot}_cast'] = cast_clip(species, index, names)
        if index in SUSTAINED[species]:
            clips[f'animation.pkmoba_{species}.{slot}_sustain'] = sustain_clip(species, index, names)
        if index in MELEE.get(species, []):
            clips[f'animation.pkmoba_{species}.{slot}_melee'] = cast_clip(species, index, names)
    # Hand-authored clips win over the shared template for the same clip name.
    for clip_name, clip in CUSTOM.get(species, {}).items():
        clips[f'animation.pkmoba_{species}.{clip_name}'] = clip
    path = out / f'pkmoba_{species}.animation.json'
    path.write_text(json.dumps({'format_version': '1.8.0', 'animations': clips}, indent=2) + '\n', encoding='utf-8')
    print(species, len(clips), 'clips, all referenced bones validated')

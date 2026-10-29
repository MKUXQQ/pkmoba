"""Bake Lucario skill clips out of Cobblemon's own action animations.

Lucario 1.8.2 has no ``physical``/``special`` attack clip, but the project file does ship a full
battle set: ``battle_idle`` (guard stance), ``battle_quirk`` (the aura arm flourish), ``cry``
(torso lunge) and ``ground_run`` (sprint). So the four PKMOBA skills lift those channels out,
retime them into each skill window and then layer our own aura flourish (palms gathering energy,
bone-club swings, punch compression) on top.

0.56 fix — why the cast used to look like walking:
    Cobblemon's own battle clips only animate arms/tail/head/torso; the leg bones are static there,
    so the ``spread < 1.5`` filter dropped every leg channel. A clip with no leg channel leaves the
    locomotion cycle (walk) driving the lower body, which is exactly what the player saw: the skill
    played on the upper body while the legs kept walking. Every clip now authors a planted stance
    on ``legs``/``leg_*``/``foot_*`` so the walk cycle is overridden for the whole skill.

Run from the repository root:
    python 项目/pkmoba/tools/gen_lucario_animation.py
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COBBLEMON = ROOT / "项目/宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon"
SOURCE_ANIM = COBBLEMON / "animations/0448_lucario/lucario.animation.json"
GEO_FILES = [
    COBBLEMON / "models/0448_lucario/lucario.geo.json",
]
TARGET = ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_lucario.animation.json"

# Silhouette bones only: fingers, eyelids, aura sensors and locators stay untouched so a retimed
# window can never tear the model or fight Cobblemon's cosmetic layer.
COPY_BONES = [
    "body", "torso", "torso2", "torso3", "neck", "head", "chest_fluff",
    "arms", "shoulder_left", "arm_left", "arm_left2", "wrist_left", "hand_left",
    "shoulder_right", "arm_right", "arm_right2", "wrist_right", "hand_right",
    "legs", "leg_left", "leg_left2", "leg_left3", "foot_left",
    "leg_right", "leg_right2", "leg_right3", "foot_right",
    "tail", "tail2", "tail3", "tail_end", "ears",
]
MIRROR = {"leg_left": "leg_right", "leg_right": "leg_left", "leg_left2": "leg_right2",
          "leg_right2": "leg_left2", "leg_left3": "leg_right3", "leg_right3": "leg_left3",
          "foot_left": "foot_right", "foot_right": "foot_left",
          "arm_left": "arm_right", "arm_right": "arm_left", "arm_left2": "arm_right2",
          "arm_right2": "arm_left2", "wrist_left": "wrist_right", "wrist_right": "wrist_left",
          "hand_left": "hand_right", "hand_right": "hand_left",
          "shoulder_left": "shoulder_right", "shoulder_right": "shoulder_left"}
PROPS = ("rotation", "position", "scale")

# Bones that must exist in every clip, otherwise the locomotion cycle keeps driving them.
STANCE_BONES = ("legs", "leg_left", "leg_left2", "leg_left3", "foot_left",
                "leg_right", "leg_right2", "leg_right3", "foot_right", "torso", "torso2", "head")


def _vec(value):
    if isinstance(value, (int, float)):
        return [float(value)] * 3
    try:
        vals = [float(v) for v in value][:3]
    except (TypeError, ValueError):
        return None
    while len(vals) < 3:
        vals.append(0.0)
    return vals


def load_keys(channel: dict, prop: str) -> list[tuple[float, list[float]]]:
    """Normalise one channel property into [(time, [x, y, z]), ...] sorted by time."""
    raw = channel.get(prop)
    if raw is None:
        return []
    if isinstance(raw, (list, tuple, int, float)):
        vec = _vec(raw)
        return [] if vec is None else [(0.0, vec)]
    out = []
    for key, value in raw.items():
        try:
            time = float(key)
        except (TypeError, ValueError):
            continue
        if isinstance(value, dict):
            value = value.get("post", value.get("pre", [0, 0, 0]))
        vec = _vec(value)
        if vec is None:
            # Molang-driven channel: cannot be resampled, so the property is dropped instead of
            # faking a flat pose.
            return []
        out.append((time, vec))
    out.sort(key=lambda kv: kv[0])
    return out


def sample(keys: list[tuple[float, list[float]]], time: float) -> list[float]:
    if not keys:
        return [0.0, 0.0, 0.0]
    if time <= keys[0][0]:
        return keys[0][1][:]
    if time >= keys[-1][0]:
        return keys[-1][1][:]
    for index in range(1, len(keys)):
        t1, v1 = keys[index]
        if time <= t1:
            t0, v0 = keys[index - 1]
            span = t1 - t0
            f = 0.0 if span <= 0 else (time - t0) / span
            return [v0[i] + (v1[i] - v0[i]) * f for i in range(3)]
    return keys[-1][1:]


def bake(animation: dict, start: float, end: float, length: float, fps: int = 12,
         bones: list[str] | None = None) -> dict:
    """Retime animation[start..end] into a clip of ``length`` seconds, sampling at ``fps``."""
    bones = bones or COPY_BONES
    frames = max(2, round(length * fps) + 1)
    baked: dict[str, dict] = {}
    for bone in bones:
        channel = animation.get("bones", {}).get(bone)
        if not channel:
            continue
        entry: dict[str, dict] = {}
        for prop in PROPS:
            keys = load_keys(channel, prop)
            if not keys:
                continue
            spread = max(max(vec[i] for _, vec in keys) - min(vec[i] for _, vec in keys)
                         for i in range(3))
            if spread < 0.6:
                continue
            timeline = {}
            for frame in range(frames):
                f = frame / (frames - 1)
                time = start + (end - start) * f
                value = [round(v, 2) for v in sample(keys, time)]
                timeline[f"{round(length * f, 4)}"] = value
            entry[prop] = timeline
        if entry:
            baked[bone] = entry
    return baked


def mirror(clip: dict) -> dict:
    """Mirror a baked clip left<->right so the bone club really alternates sides."""
    out: dict[str, dict] = {}
    for bone, entry in clip.items():
        target_bone = MIRROR.get(bone, bone)
        new_entry = {}
        for prop, timeline in entry.items():
            new_timeline = {}
            for key, frame in timeline.items():
                values = list(frame)
                if prop == "rotation":
                    values[1] = -values[1]
                    values[2] = -values[2]
                elif prop == "position":
                    values[0] = -values[0]
                new_timeline[key] = values
            new_entry[prop] = new_timeline
        out[target_bone] = new_entry
    return out


def shift(clip: dict, offset: float) -> dict:
    out = {}
    for bone, entry in clip.items():
        new_entry = {}
        for prop, timeline in entry.items():
            new_entry[prop] = {f"{round(float(k) + offset, 4)}": frame for k, frame in timeline.items()}
        out[bone] = new_entry
    return out


def merge(*clips: dict) -> dict:
    out: dict[str, dict] = {}
    for clip in clips:
        for bone, entry in clip.items():
            bucket = out.setdefault(bone, {})
            for prop, timeline in entry.items():
                bucket.setdefault(prop, {}).update(timeline)
    return out


def overlay(clip: dict, bone: str, prop: str, timeline: dict) -> None:
    clip.setdefault(bone, {}).setdefault(prop, {}).update(timeline)


def keyframe(pairs: list[tuple[float, list[float]]]) -> dict:
    return {f"{round(t, 4)}": [round(v, 4) for v in vec] for t, vec in pairs}


def loopify(clip: dict, length: float) -> dict:
    for bone, entry in clip.items():
        for prop, timeline in entry.items():
            keys = sorted(timeline, key=float)
            if len(keys) < 2:
                continue
            timeline[f"{round(length, 4)}"] = list(timeline[keys[0]])
    return clip


def legs(clip: dict, length: float, crouch: float, lunge: float = 0.0, stomp: float = 0.0) -> None:
    """Plant the stance so the walk cycle cannot drive the legs any more.

    ``crouch`` bends both knees, ``lunge`` pushes one leg forward and the other back (attack
    stances), ``stomp`` lifts the front foot briefly on impact beats.
    """
    end = round(length, 4)
    mid = round(length * 0.45, 4)
    overlay(clip, "legs", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [crouch * .35, 0, 0]), (end, [crouch * .15, 0, 0])]))
    # torso2 belongs to the stance too: it is static in Cobblemon's battle clips, so without an
    # authored key here the spine would keep following the locomotion bob.
    overlay(clip, "torso2", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [crouch * .30, 0, 0]), (end, [crouch * .10, 0, 0])]))
    # left leg: forward when lunging
    overlay(clip, "leg_left", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [-crouch * .55 + lunge, 0, 0]),
                      (end, [-crouch * .2 + lunge * .5, 0, 0])]))
    overlay(clip, "leg_left2", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [crouch * 1.25 - stomp, 0, 0]),
                      (end, [crouch * .45, 0, 0])]))
    overlay(clip, "leg_left3", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [-crouch * .35 + stomp * .6, 0, 0]),
                      (end, [-crouch * .1, 0, 0])]))
    overlay(clip, "foot_left", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [-crouch * .45 + stomp, 0, 0]),
                      (end, [-crouch * .18, 0, 0])]))
    # right leg: back leg, drives the lunge
    overlay(clip, "leg_right", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [crouch * .45 - lunge * .8, 0, 0]),
                      (end, [crouch * .2, 0, 0])]))
    overlay(clip, "leg_right2", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [crouch * 1.0, 0, 0]), (end, [crouch * .4, 0, 0])]))
    overlay(clip, "leg_right3", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [-crouch * .3, 0, 0]), (end, [-crouch * .1, 0, 0])]))
    overlay(clip, "foot_right", "rotation",
            keyframe([(0.0, [0, 0, 0]), (mid, [-crouch * .4, 0, 0]), (end, [-crouch * .15, 0, 0])]))


def build(source: dict) -> dict:
    animations = source["animations"]
    stance = animations["animation.lucario.battle_idle"]
    quirk = animations["animation.lucario.battle_quirk"]
    cry = animations["animation.lucario.cry"]
    run = animations["animation.lucario.ground_run"]

    # --- Q: Aura Sphere. Cobblemon's battle_quirk is the aura arm flourish; the palms gather at
    # its first beat, and cry supplies the torso lunge on release.
    q_windup = bake(quirk, 0.0, 1.10, 0.45)
    q_cast = bake(cry, 0.0, 0.70, 0.50)
    q_melee = bake(stance, 0.0, 1.40, 0.60)

    # --- W: Bone Rush. Authored club swings layered over the guard stance, second swing mirrored.
    w_windup = bake(stance, 0.0, 1.20, 0.35)
    w_melee = bake(stance, 0.0, 1.00, 0.26)
    w_melee2 = mirror(w_melee)
    w_cast = bake(quirk, 0.4, 1.40, 0.50)

    # --- E: Extreme Speed. Reuse the sprint cycle so the dash strides instead of sliding.
    e_windup = bake(cry, 0.0, 0.40, 0.25)
    e_cast = bake(run, 0.0, 0.40, 0.60)
    e_sustain = loopify(bake(run, 0.0, 0.50, 1.00), 1.00)
    e_melee = bake(run, 0.0, 0.30, 0.50)

    # --- R: Close Combat. battle_quirk compressed into a crouch, then a full punch through cry.
    r_windup = bake(quirk, 0.0, 1.60, 0.70)
    r_cast = bake(cry, 0.0, 0.90, 0.60)
    r_melee = bake(cry, 0.2, 1.10, 0.60)

    # --- Hand-authored PKMOBA layer: blue aura gathering at the palms, the bone club arcs and the
    # punch compression, so the borrowed motion reads as our skill and not a plain idle replay.
    def guard(clip: dict, length: float, crouch: float = 14.0) -> None:
        a, b, c = 0.0, round(length * 0.45, 4), round(length, 4)
        overlay(clip, "torso", "rotation",
                keyframe([(a, [0, 0, 0]), (b, [crouch, 0, 0]), (c, [-crouch * .4, 0, 0])]))
        overlay(clip, "arm_left", "rotation",
                keyframe([(a, [0, 0, 0]), (b, [-38, 0, 16]), (c, [-14, 0, 6])]))
        overlay(clip, "arm_right", "rotation",
                keyframe([(a, [0, 0, 0]), (b, [-38, 0, -16]), (c, [-14, 0, -6])]))
        overlay(clip, "head", "rotation",
                keyframe([(a, [0, 0, 0]), (b, [-10, 0, 0]), (c, [2, 0, 0])]))
        legs(clip, length, crouch)

    for clip, length in ((q_windup, .45), (q_cast, .50), (q_melee, .60),
                         (w_windup, .35), (w_melee, .26), (w_melee2, .26), (w_cast, .50),
                         (r_melee, .60)):
        guard(clip, length)
    for clip, length in ((r_windup, .70), (r_cast, .60)):
        legs(clip, length, 22.0, lunge=6.0)
    for clip, length in ((e_windup, .25), (e_cast, .60), (e_melee, .50)):
        legs(clip, length, 16.0, lunge=14.0)
    legs(e_sustain, 1.00, 18.0, lunge=16.0)

    # Q: palms come together at the waist, then thrust forward on release.
    for side, sign in (("left", 1), ("right", -1)):
        overlay(q_windup, "arm_" + side, "rotation", keyframe(
            [(0.0, [0, 0, 0]), (0.18, [-30, 0, 18 * sign]), (0.45, [-58, 0, 30 * sign])]))
        overlay(q_cast, "arm_" + side, "rotation", keyframe(
            [(0.0, [-58, 0, 30 * sign]), (0.16, [-92, 0, 8 * sign]), (0.50, [-18, 0, 4 * sign])]))
    for side in ("left", "right"):
        overlay(q_melee, "arm_" + side, "rotation", keyframe(
            [(0.0, [-70, 0, 12 if side == "left" else -12]), (0.22, [-20, 0, 6 if side == "left" else -6]),
             (0.60, [-14, 0, 6 if side == "left" else -6])]))
    overlay(q_windup, "torso", "rotation",
            keyframe([(0.0, [0, 0, 0]), (0.20, [12, 0, 0]), (0.45, [4, 0, 0])]))

    # W: bone club — a wide horizontal sweep, then the reverse sweep on the mirrored clip.
    # 0.56: Bone Rush beats fire every 5 ticks (0.25s), so one swing must fit in 0.25s or the
    # next beat would cut the previous arc in half.
    sweep_start, sweep_hit, sweep_end = 0.0, 0.07, 0.26
    overlay(w_melee, "arm_right", "rotation", keyframe(
        [(sweep_start, [0, 0, 0]), (sweep_hit, [-84, -52, -20]), (sweep_end, [-26, 46, -6])]))
    overlay(w_melee, "wrist_right", "rotation", keyframe(
        [(sweep_start, [0, 0, 0]), (sweep_hit, [0, -34, 0]), (sweep_end, [0, 30, 0])]))
    overlay(w_melee, "torso", "rotation", keyframe(
        [(sweep_start, [0, 0, 0]), (sweep_hit, [6, -26, 0]), (sweep_end, [4, 28, 0])]))
    overlay(w_melee, "arm_left", "rotation", keyframe(
        [(sweep_start, [0, 0, 0]), (sweep_hit, [-34, 22, 12]), (sweep_end, [-16, 6, 6])]))
    overlay(w_windup, "arm_right", "rotation", keyframe(
        [(0.0, [0, 0, 0]), (0.35, [-30, -40, -10])]))

    # R: fists compress at the waist, then one straight punch with the whole torso behind it.
    for side, sign in (("left", 1), ("right", -1)):
        overlay(r_windup, "arm_" + side, "rotation", keyframe(
            [(0.0, [0, 0, 0]), (0.35, [-60, 0, 28 * sign]), (0.70, [-72, 0, 14 * sign])]))
        overlay(r_cast, "arm_" + side, "rotation", keyframe(
            [(0.0, [-72, 0, 14 * sign]), (0.18, [-96, 0, 4 * sign]), (0.60, [-22, 0, 4 * sign])]))
    overlay(r_windup, "torso", "rotation",
            keyframe([(0.0, [0, 0, 0]), (0.36, [24, 0, 0]), (0.70, [28, 0, 0])]))
    overlay(r_windup, "head", "rotation", keyframe([(0.0, [0, 0, 0]), (0.70, [-16, 0, 0])]))
    overlay(r_cast, "torso", "rotation",
            keyframe([(0.0, [26, 0, 0]), (0.18, [-18, 0, 0]), (0.60, [0, 0, 0])]))
    overlay(r_melee, "arm_right", "rotation",
            keyframe([(0.0, [-96, 0, -4]), (0.20, [-30, 0, -10]), (0.60, [-14, 0, -6])]))
    overlay(r_melee, "arm_left", "rotation",
            keyframe([(0.0, [-60, 0, 10]), (0.20, [-24, 0, 8]), (0.60, [-14, 0, 6])]))

    overlay(e_cast, "torso", "rotation",
            keyframe([(0.0, [0, 0, 0]), (0.20, [22, 0, 0]), (0.60, [14, 0, 0])]))
    overlay(e_sustain, "torso", "rotation",
            keyframe([(0.0, [16, 0, 0]), (0.50, [22, 0, 0]), (1.00, [16, 0, 0])]))
    for side in ("left", "right"):
        sign = 1 if side == "left" else -1
        overlay(e_cast, "arm_" + side, "rotation",
                keyframe([(0.0, [0, 0, 0]), (0.30, [30, 0, -18 * sign]), (0.60, [24, 0, -14 * sign])]))
        overlay(e_sustain, "arm_" + side, "rotation",
                keyframe([(0.0, [26, 0, -16 * sign]), (0.50, [32, 0, -20 * sign]), (1.00, [26, 0, -16 * sign])]))

    clips = {
        "q_windup": (0.45, q_windup), "q_cast": (0.50, q_cast), "q_melee": (0.60, q_melee),
        "w_windup": (0.35, w_windup), "w_cast": (0.50, w_cast),
        "w_melee": (0.26, w_melee), "w_melee2": (0.26, w_melee2),
        "e_windup": (0.25, e_windup), "e_cast": (0.60, e_cast), "e_sustain": (1.00, e_sustain),
        "e_melee": (0.50, e_melee),
        "r_windup": (0.70, r_windup), "r_cast": (0.60, r_cast), "r_melee": (0.60, r_melee),
    }
    # Aggregated clips keep the legacy one-shot names working (HUD cosmetics, older call sites).
    clips["q"] = (1.15, merge(q_windup, shift(q_melee, 0.46)))
    clips["w"] = (0.62, merge(w_windup, shift(w_melee, 0.34)))
    clips["e"] = (1.25, merge(e_windup, shift(e_melee, 0.24)))
    clips["r"] = (1.30, merge(r_windup, shift(r_melee, 0.70)))

    out = {}
    for name, (length, bones) in clips.items():
        missing = [bone for bone in STANCE_BONES if bone not in bones]
        if missing:
            # Any stance bone without a channel falls back to the locomotion cycle, which is the
            # "skill plays while the legs keep walking" bug. Fill the gap with a neutral hold.
            for bone in missing:
                overlay(bones, bone, "rotation",
                        keyframe([(0.0, [0, 0, 0]), (round(length * .45, 4), [6, 0, 0]),
                                  (round(length, 4), [2, 0, 0])]))
        entry = {"animation_length": round(length, 4), "bones": bones}
        if name.endswith("_sustain"):
            entry["loop"] = True
        out[f"animation.pkmoba_lucario.{name}"] = entry
    return out


def bone_names(path: Path) -> set[str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    names: set[str] = set()

    def walk(bone: dict) -> None:
        names.add(bone["name"])
        for child in bone.get("children") or []:
            walk(child)

    for geometry in data["minecraft:geometry"]:
        for bone in geometry["bones"]:
            walk(bone)
    return names


def validate(animations: dict, allowed: set[str]) -> list[str]:
    problems = []
    for name, entry in animations.items():
        length = entry.get("animation_length")
        if not length or length <= 0:
            problems.append(f"{name}: bad animation_length {length}")
        unknown = set(entry.get("bones", {})) - allowed
        if unknown:
            problems.append(f"{name}: unknown bones {sorted(unknown)}")
        for bone, channel in entry.get("bones", {}).items():
            for prop, timeline in channel.items():
                if not timeline:
                    problems.append(f"{name}/{bone}/{prop}: empty timeline")
                for key, frame in timeline.items():
                    t = float(key)
                    if t < -1e-6 or t > length + 0.02:
                        problems.append(f"{name}/{bone}/{prop}: key {t} outside 0..{length}")
                    if not isinstance(frame, list) or len(frame) != 3:
                        problems.append(f"{name}/{bone}/{prop}: bad values {frame}")
                    elif not all(math.isfinite(v) for v in frame):
                        problems.append(f"{name}/{bone}/{prop}: non-finite {frame}")
    return problems


def main() -> int:
    source = json.loads(SOURCE_ANIM.read_text(encoding="utf-8"))
    animations = build(source)
    allowed = set.intersection(*(bone_names(path) for path in GEO_FILES))
    problems = validate(animations, allowed)
    required = {"q_windup", "q_cast", "q_melee", "w_windup", "w_melee", "w_melee2",
                "e_cast", "e_melee", "r_windup", "r_cast", "r_melee"}
    missing = {f"animation.pkmoba_lucario.{name}" for name in required} - set(animations)
    if missing:
        problems.append(f"missing clips {sorted(missing)}")
    if problems:
        print("FAILED:")
        for problem in problems[:40]:
            print("  -", problem)
        return 1
    payload = {"format_version": "1.8.0", "animations": animations}
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    keys = sum(len(t) for entry in animations.values() for b in entry["bones"].values() for t in b.values())
    print(f"wrote {TARGET.relative_to(ROOT)}: {len(animations)} clips, {keys} keyframes, "
          f"{TARGET.stat().st_size} bytes")
    for name in sorted(animations):
        entry = animations[name]
        print(f"  {name.split('.')[-1]:<12} len={entry['animation_length']:<5} bones={len(entry['bones'])}"
              f"{' loop' if entry.get('loop') else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

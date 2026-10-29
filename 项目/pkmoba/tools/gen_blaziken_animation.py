"""Bake Blaziken skill clips out of Cobblemon's own action animations.

The Prism-scale goal: Blaziken's four PKMOBA skills must move the real body, not just
spawn particles. Cobblemon 1.8.2 already ships a fully animated Blaziken rig
(``physical`` = the kick flurry, ``special`` = the charge-up, ``ground_run`` = the sprint),
so instead of hand-authoring 200 keyframes we lift those channels out of the project file,
retime them into each skill window and then layer our own flame flourish on top.

Run from the repository root:
    python 项目/pkmoba/tools/gen_blaziken_animation.py
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COBBLEMON = ROOT / "项目/宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon"
SOURCE_ANIM = COBBLEMON / "animations/0257_blaziken/blaziken.animation.json"
GEO_FILES = [
    COBBLEMON / "models/0257_blaziken/blaziken_male.geo.json",
    COBBLEMON / "models/0257_blaziken/blaziken_female.geo.json",
]
TARGET = ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_blaziken.animation.json"

# Bones copied from the Cobblemon rig. Small set on purpose: the silhouette bones only, so a
# retimed window can never tear the model (fingers, eyelids and locators are left alone).
COPY_BONES = [
    "body", "waist", "torso", "torso2", "abdomen", "chest", "neck", "head",
    "arm_left", "arm_left2", "arm_left3", "arm_right", "arm_right2", "arm_right3",
    "hand_left", "hand_right", "wrist_left", "wrist_right",
    "leg_left", "leg_left2", "leg_right", "leg_right2", "foot_left", "foot_right",
    "tail1", "tail2", "tail3",
]
MIRROR = {"leg_left": "leg_right", "leg_right": "leg_left", "leg_left2": "leg_right2",
          "leg_right2": "leg_left2", "foot_left": "foot_right", "foot_right": "foot_left",
          "arm_left": "arm_right", "arm_right": "arm_left", "arm_left2": "arm_right2",
          "arm_right2": "arm_left2", "hand_left": "hand_right", "hand_right": "hand_left"}
PROPS = ("rotation", "position", "scale")


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
            # Molang-driven channel (e.g. "math.sin(q.anim_time*90*8)"): cannot be retimed by
            # sampling, so the whole property is dropped instead of faking a flat pose.
            return []
        out.append((time, vec))
    out.sort(key=lambda kv: kv[0])
    return out


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
    return keys[-1][1][:]


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
            timeline = {}
            # A channel whose whole range is flat in this window carries no information; keeping
            # it would only bloat the json (the original file is 29 KB, not 1.8 MB).
            spread = max(max(vec[i] for _, vec in keys) - min(vec[i] for _, vec in keys)
                         for i in range(3))
            if spread < 1.5:
                continue
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
    """Mirror a baked clip left<->right so Double Kick really alternates legs."""
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
    """Add or replace one bone/property timeline (the hand-authored PKMOBA layer)."""
    clip.setdefault(bone, {}).setdefault(prop, {}).update(timeline)


def keyframe(pairs: list[tuple[float, list[float]]]) -> dict:
    return {f"{round(t, 4)}": [round(v, 4) for v in vec] for t, vec in pairs}


def loopify(clip: dict, length: float) -> dict:
    """Make the last frame identical to the first so a sustain clip cannot snap."""
    for bone, entry in clip.items():
        for prop, timeline in entry.items():
            keys = sorted(timeline, key=float)
            if len(keys) < 2:
                continue
            timeline[f"{round(length, 4)}"] = list(timeline[keys[0]])
    return clip


def build(source: dict) -> dict:
    animations = source["animations"]
    physical = animations["animation.blaziken.physical"]
    special = animations["animation.blaziken.special"]
    run = animations["animation.blaziken.ground_run"]

    # --- Q: Blaze Kick. The real kick swing lives at 0.30-0.72s of Cobblemon's physical clip.
    q_windup = bake(physical, 0.0, 0.28, 0.20)
    q_cast = bake(physical, 0.28, 0.55, 0.30)
    q_melee = bake(physical, 0.30, 0.74, 0.42)

    # --- W: Double Kick. Same kick, mirrored for the second leg, played in sequence.
    w_windup = bake(physical, 0.0, 0.28, 0.20)
    w_kick_right = bake(physical, 0.30, 0.74, 0.36)
    w_kick_left = mirror(w_kick_right)
    w_cast = bake(physical, 0.30, 0.58, 0.40)
    w_melee = merge(w_kick_right, shift(mirror(w_kick_right), 0.38))

    # --- E: Flame Charge. Reuse the sprint cycle so the dash gallops instead of sliding.
    e_windup = bake(physical, 0.0, 0.20, 0.15)
    e_cast = bake(run, 0.0, 0.40, 0.40)
    e_sustain = loopify(bake(run, 0.0, 0.50, 0.60), 0.60)
    e_melee = bake(run, 0.0, 0.30, 0.34)

    # --- R: Flare Blitz. ``special`` is the charge-up pose, physical the launched shoulder charge.
    r_windup = bake(special, 0.0, 1.50, 0.90)
    r_cast = bake(physical, 0.70, 1.10, 0.50)
    r_sustain = loopify(bake(run, 0.0, 0.50, 0.50), 0.50)
    r_melee = bake(physical, 0.75, 1.35, 0.60)

    # --- Hand-authored PKMOBA layer: flames that gather on the limbs, a deeper crouch on the
    # charge and a wider recovery, so the borrowed motion reads as *our* skill and not a reuse.
    def stance(clip: dict, length: float) -> None:
        """Slight crouch + arms flaring back across the whole clip (fractions of its length)."""
        a, b, c = 0.0, round(length * 0.42, 4), round(length, 4)
        overlay(clip, "waist", "rotation", keyframe([(a, [0, 0, 0]), (b, [14, 0, 0]), (c, [-8, 0, 0])]))
        overlay(clip, "arm_left", "rotation", keyframe([(a, [0, 0, 0]), (b, [-42, 0, 18]), (c, [-14, 0, 6])]))
        overlay(clip, "arm_right", "rotation", keyframe([(a, [0, 0, 0]), (b, [-42, 0, -18]), (c, [-14, 0, -6])]))

    for name, clip, length in (
            ("q_windup", q_windup, 0.20), ("q_cast", q_cast, 0.30), ("q_melee", q_melee, 0.42),
            ("w_windup", w_windup, 0.20), ("w_cast", w_cast, 0.40),
            ("w_kick_right", w_kick_right, 0.36), ("w_kick_left", w_kick_left, 0.36),
            ("r_melee", r_melee, 0.60)):
        stance(clip, length)
    overlay(r_windup, "waist", "rotation", keyframe([(0.0, [0, 0, 0]), (0.45, [26, 0, 0]), (0.9, [30, 0, 0])]))
    overlay(r_windup, "arm_left", "rotation", keyframe([(0.0, [0, 0, 0]), (0.35, [-58, 0, 24]), (0.9, [-72, 0, 30])]))
    overlay(r_windup, "arm_right", "rotation", keyframe([(0.0, [0, 0, 0]), (0.35, [-58, 0, -24]), (0.9, [-72, 0, -30])]))
    overlay(r_windup, "head", "rotation", keyframe([(0.0, [0, 0, 0]), (0.5, [-16, 0, 0]), (0.9, [-22, 0, 0])]))
    overlay(r_cast, "arm_left", "rotation", keyframe([(0.0, [-72, 0, 30]), (0.18, [-18, 0, 8]), (0.5, [-6, 0, 0])]))
    overlay(r_cast, "arm_right", "rotation", keyframe([(0.0, [-72, 0, -30]), (0.18, [-18, 0, -8]), (0.5, [-6, 0, 0])]))
    overlay(e_cast, "waist", "rotation", keyframe([(0.0, [0, 0, 0]), (0.2, [16, 0, 0]), (0.4, [10, 0, 0])]))
    overlay(e_sustain, "waist", "rotation", keyframe([(0.0, [12, 0, 0]), (0.3, [16, 0, 0]), (0.6, [12, 0, 0])]))

    clips = {
        "q_windup": (0.20, q_windup), "q_cast": (0.30, q_cast), "q_melee": (0.42, q_melee),
        "w_windup": (0.20, w_windup), "w_cast": (0.40, w_cast), "w_melee": (0.76, w_melee),
        "w_kick_left": (0.36, w_kick_left), "w_kick_right": (0.36, w_kick_right),
        "e_windup": (0.15, e_windup), "e_cast": (0.40, e_cast), "e_sustain": (0.60, e_sustain),
        "e_melee": (0.34, e_melee),
        "r_windup": (0.90, r_windup), "r_cast": (0.50, r_cast), "r_melee": (0.60, r_melee),
        "r_sustain": (0.50, r_sustain),
    }
    clips["q"] = (0.80, merge(q_windup, shift(q_melee, 0.24)))
    clips["w"] = (1.20, merge(w_windup, shift(w_melee, 0.22)))
    clips["e"] = (0.95, merge(e_windup, shift(e_melee, 0.16)))
    clips["r"] = (1.85, merge(r_windup, shift(r_melee, 0.92)))

    out = {}
    for name, (length, bones) in clips.items():
        entry = {"animation_length": round(length, 4), "bones": bones}
        if name.endswith("_sustain"):
            entry["loop"] = True
        out[f"animation.pkmoba_blaziken.{name}"] = entry
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
                    values = frame
                    if not isinstance(values, list) or len(values) != 3:
                        problems.append(f"{name}/{bone}/{prop}: bad values {values}")
                    elif not all(math.isfinite(v) for v in values):
                        problems.append(f"{name}/{bone}/{prop}: non-finite {values}")
    return problems


def main() -> int:
    source = json.loads(SOURCE_ANIM.read_text(encoding="utf-8"))
    animations = build(source)
    allowed = set.intersection(*(bone_names(path) for path in GEO_FILES))
    problems = validate(animations, allowed)
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
        bones = len(entry["bones"])
        print(f"  {name.split('.')[-1]:<12} len={entry['animation_length']:<5} bones={bones}"
              f"{' loop' if entry.get('loop') else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

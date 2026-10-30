"""Author Lucario's 波导弹 (Aura Sphere) throw in Blockbench format and export it to the mod.

What it does
------------
1. Converts Cobblemon's ``lucario.geo.json`` (bedrock geometry) into a ``.bbmodel`` project so the
   exact in-game model, its 122 bones and the 128x128 texture open in Blockbench with the same
   pivots the game uses.
2. Builds the hand-authored Aura Sphere choreography (charge at the hip, two-handed push, settle)
   on that rig and stores it as a Blockbench animation inside the project.
3. Resamples the same choreography into the runtime clips the mod plays
   (``q_windup`` / ``q_cast`` / ``q_melee`` / legacy ``q``) inside
   ``assets/pkmoba/bedrock/pokemon/animations/pkmoba_lucario.animation.json``, so what Blockbench
   shows and what the game plays are the same keyframes.

Run from the repository root:
    python 项目/pkmoba/tools/make_lucario_q_bbmodel.py
"""

from __future__ import annotations

import base64
import json
import math
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COBBLEMON = (ROOT / "项目/宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources"
             / "assets/cobblemon")
GEO = COBBLEMON / "bedrock/pokemon/models/0448_lucario/lucario.geo.json"
TEXTURE = COBBLEMON / "textures/pokemon/0448_lucario/lucario.png"
OUT_DIR = ROOT / "项目/pkmoba/blockbench"
BBMODEL = OUT_DIR / "lucario_aura_sphere.bbmodel"
RUNTIME = (ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations"
           / "pkmoba_lucario.animation.json")

NAMESPACE = uuid.UUID("6f9d1c52-0d7c-4a3f-9f7e-1a2b3c4d5e6f")


def uid(*parts: str) -> str:
    return str(uuid.uuid5(NAMESPACE, "|".join(parts)))


# --------------------------------------------------------------------------------------------------
# Choreography
#
# Lucario 1.8.2 rotation conventions (read off Cobblemon's own battle_quirk):
#   arm_*   X negative  raises the arm forward/up, Y swings it across the chest, Z opens the elbow out
#   arm_*2  Z positive  bends the elbow (large values = hand in front of the chest)
#   torso   X positive  leans forward,  Y negative twists the shoulders away from the target
#   torso2  counter-rotation so the chest still faces the target while the hips wind up
#   tail    X positive  lifts the tail
#
# Times are seconds on a 1.0 s timeline; every single value below was authored for this move.
CHARGE_ARM = {"X": -52.0, "Y": -14.0, "Z": 16.0, "ELBOW": 52.0, "WRIST": -12.0, "HAND": -8.0}
PUSH_ARM = {"X": -78.0, "Y": -6.0, "Z": 8.0, "ELBOW": 26.0, "WRIST": 4.0, "HAND": 0.0}
THRUST_ARM = {"X": -96.0, "Y": 2.0, "Z": 4.0, "ELBOW": 8.0, "WRIST": 12.0, "HAND": 12.0}
LOWER_ARM = {"X": -40.0, "Y": 0.0, "Z": 8.0, "ELBOW": 22.0, "WRIST": 2.0, "HAND": 0.0}
REST_ARM = {"X": -12.0, "Y": 0.0, "Z": 4.0, "ELBOW": 6.0, "WRIST": 0.0, "HAND": 0.0}

# legs: (legs, leg_fwd, knee_fwd, foot_fwd, leg_back, knee_back, foot_back) — degrees.
CHARGE_LEGS = (4.0, -6.0, 16.0, -7.0, 7.0, 13.0, -5.0)
PUSH_LEGS = (2.0, -3.0, 10.0, -4.0, 3.0, 9.0, -4.0)
THRUST_LEGS = (3.0, -11.0, 12.0, -4.0, 11.0, 6.0, -9.0)
LOWER_LEGS = (3.0, -6.0, 8.0, -3.0, 5.0, 6.0, -3.0)
REST_LEGS = (1.0, -2.0, 3.0, -1.0, 2.0, 2.0, -1.0)


def arm_frames(times, specs):
    """Build (time, [x,y,z]) pairs for one arm/side from a list of pose dicts."""
    frames = []
    for t, s in zip(times, specs):
        frames.append((t, s))
    return frames


def side(values, sign: float) -> list[float]:
    return [values[0], values[1] * sign, values[2] * sign]


# (time, torso, torso2, head, head_angle, tail, ears, arm_spec, legs)
TIMELINE = [
    (0.00, [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], None, None),
    (0.12, [4, -6, 0], [3, 5, 0], [-3, 5, 0], [-2, 0, 0], [4, 0, 0], [-2, 0, 0],
     dict(CHARGE_ARM, SCALE=.45), CHARGE_LEGS),
    (0.30, [8, -16, 0], [6, 14, 0], [-6, 12, 0], [-4, 0, 0], [10, 6, 0], [-4, 0, 0],
     CHARGE_ARM, CHARGE_LEGS),
    (0.45, [3, -6, 0], [3, 5, 0], [-2, 8, 0], [-2, 0, 0], [6, 3, 0], [-2, 0, 0],
     PUSH_ARM, PUSH_LEGS),
    (0.55, [-2, 10, 0], [-2, -8, 0], [2, 2, 0], [1, 0, 0], [-6, -3, 0], [2, 0, 0],
     THRUST_ARM, THRUST_LEGS),
    (0.70, [1, 4, 0], [1, -3, 0], [0, 3, 0], [0, 0, 0], [-2, 0, 0], [0, 0, 0],
     LOWER_ARM, LOWER_LEGS),
    (0.85, [0, 1, 0], [0, -1, 0], [0, 1, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0],
     REST_ARM, REST_LEGS),
    (1.00, [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], None, None),
]
TOTAL = 1.0


def scaled(spec: dict, factor: float) -> dict:
    if spec is None:
        return None
    return {k: v * factor for k, v in spec.items()}


def choreography() -> dict[str, dict[str, list[tuple[float, list[float]]]]]:
    """bone -> property -> [(time, [x,y,z])] on the 0..1 s timeline."""
    out: dict[str, dict[str, list[tuple[float, list[float]]]]] = {}

    def put(bone: str, prop: str, time: float, values: list[float]) -> None:
        out.setdefault(bone, {}).setdefault(prop, []).append((round(time, 4), [round(v, 3) for v in values]))

    for time, torso, torso2, head, head_angle, tail, ears, arm, legs in TIMELINE:
        put("torso", "rotation", time, torso)
        put("torso2", "rotation", time, torso2)
        put("head", "rotation", time, head)
        put("head_angle", "rotation", time, head_angle)
        put("tail", "rotation", time, tail)
        put("tail2", "rotation", time, [tail[0] * .45, 0, 0])
        put("ears", "rotation", time, ears)
        if arm is None:
            for bone in ("arm_left", "arm_left2", "wrist_left", "hand_left"):
                put(bone, "rotation", time, [0, 0, 0])
            for bone in ("arm_right", "arm_right2", "wrist_right", "hand_right"):
                put(bone, "rotation", time, [0, 0, 0])
        else:
            f = arm.get("SCALE", 1.0)
            put("arm_left", "rotation", time,
                [arm["X"] * f, arm["Y"] * f, arm["Z"] * f])
            put("arm_left2", "rotation", time, [0, arm["Y"] * .35 * f, arm["ELBOW"] * f])
            put("wrist_left", "rotation", time, [arm["WRIST"] * f, 0, arm["Z"] * .35 * f])
            put("hand_left", "rotation", time, [arm["HAND"] * f, 0, 0])
            put("arm_right", "rotation", time,
                [arm["X"] * f, -arm["Y"] * f, -arm["Z"] * f])
            put("arm_right2", "rotation", time, [0, -arm["Y"] * .35 * f, -arm["ELBOW"] * f])
            put("wrist_right", "rotation", time, [arm["WRIST"] * f, 0, -arm["Z"] * .35 * f])
            put("hand_right", "rotation", time, [arm["HAND"] * f, 0, 0])
        if legs is None:
            for bone in ("legs", "leg_left", "leg_left2", "foot_left",
                         "leg_right", "leg_right2", "foot_right"):
                put(bone, "rotation", time, [0, 0, 0])
        else:
            hips, lf, lk, lfoot, rb, rk, rfoot = legs
            put("legs", "rotation", time, [hips, 0, 0])
            put("leg_left", "rotation", time, [lf, 0, 0])
            put("leg_left2", "rotation", time, [lk, 0, 0])
            put("leg_left3", "rotation", time, [-lk * .28, 0, 0])
            put("foot_left", "rotation", time, [lfoot, 0, 0])
            put("leg_right", "rotation", time, [rb, 0, 0])
            put("leg_right2", "rotation", time, [rk, 0, 0])
            put("leg_right3", "rotation", time, [-rk * .28, 0, 0])
            put("foot_right", "rotation", time, [rfoot, 0, 0])
    return out


def resample(frames: list[tuple[float, list[float]]], start: float, end: float,
             length: float, fps: int = 20) -> dict[str, list[float]]:
    """Slice [start, end] out of an authored curve into a clip of ``length`` seconds."""
    steps = max(2, round(length * fps) + 1)
    timeline: dict[str, list[float]] = {}
    for index in range(steps):
        f = index / (steps - 1)
        time = start + (end - start) * f
        timeline[f"{round(length * f, 4)}"] = [round(v, 2) for v in sample(frames, time)]
    return timeline


def sample(frames: list[tuple[float, list[float]]], time: float) -> list[float]:
    if time <= frames[0][0]:
        return frames[0][1]
    if time >= frames[-1][0]:
        return frames[-1][1]
    for index in range(1, len(frames)):
        t1, v1 = frames[index]
        if time <= t1:
            t0, v0 = frames[index - 1]
            span = t1 - t0
            f = 0.0 if span <= 0 else (time - t0) / span
            return [v0[i] + (v1[i] - v0[i]) * f for i in range(3)]
    return frames[-1][1]


# --------------------------------------------------------------------------------------------------
# Bedrock geometry -> Blockbench project


def build_bbmodel(geo: dict, texture: Path) -> dict:
    geometry = geo["minecraft:geometry"][0]
    description = geometry["description"]
    bones = geometry["bones"]
    by_name = {bone["name"]: bone for bone in bones}
    children: dict[str, list[str]] = {}
    roots: list[str] = []
    for bone in bones:
        parent = bone.get("parent")
        if parent and parent in by_name:
            children.setdefault(parent, []).append(bone["name"])
        else:
            roots.append(bone["name"])

    elements = []
    bone_cubes: dict[str, list[str]] = {}
    for bone in bones:
        name = bone["name"]
        for index, cube in enumerate(bone.get("cubes") or []):
            origin = cube["origin"]
            size = cube["size"]
            element_uuid = uid("element", name, str(index))
            element = {
                "name": name if len(bone["cubes"]) == 1 else f"{name}_{index}",
                "box_uv": isinstance(cube.get("uv"), list),
                "rescale": False,
                "locked": False,
                "light_emission": 0,
                "render_order": "default",
                "allow_mirror_modeling": True,
                "from": [round(origin[i], 3) for i in range(3)],
                "to": [round(origin[i] + size[i], 3) for i in range(3)],
                "autouv": 0,
                "color": 0,
                "origin": [round(v, 3) for v in (cube.get("pivot") or bone.get("pivot") or [0, 0, 0])],
                "faces": {},
                "type": "cube",
                "uuid": element_uuid,
            }
            if cube.get("rotation"):
                element["rotation"] = [round(v, 3) for v in cube["rotation"]]
            if cube.get("inflate"):
                element["inflate"] = round(cube["inflate"], 3)
            uv = cube.get("uv")
            if isinstance(uv, list):
                element["uv_offset"] = [uv[0], uv[1]]
            elif isinstance(uv, dict):
                for face, entry in uv.items():
                    element["faces"][face] = {
                        "uv": list(entry["uv"]) + [entry["uv"][0] + entry["uv_size"][0],
                                                   entry["uv"][1] + entry["uv_size"][1]],
                        "texture": 0,
                    }
            elements.append(element)
            bone_cubes.setdefault(name, []).append(element_uuid)

    def group(name: str) -> dict:
        bone = by_name[name]
        entry = {
            "name": name,
            "origin": [round(v, 3) for v in bone.get("pivot") or [0, 0, 0]],
            "uuid": uid("bone", name),
            "export": True,
            "mirror_uv": bool(bone.get("mirror")),
            "isOpen": True,
            "locked": False,
            "visibility": True,
            "autouv": 0,
            "children": list(bone_cubes.get(name, [])),
        }
        if bone.get("rotation"):
            entry["rotation"] = [round(v, 3) for v in bone["rotation"]]
        for child in children.get(name, []):
            entry["children"].append(group(child))
        return entry

    outliner = [group(name) for name in roots]

    texture_id = uid("texture", texture.name)
    source = "data:image/png;base64," + base64.b64encode(texture.read_bytes()).decode("ascii")

    animation = {
        "name": "q_aura_sphere",
        "loop": "once",
        "override": False,
        "length": TOTAL,
        "snapping": 20,
        "selected": True,
        "anim_time_update": "",
        "uuid": uid("animation", "q_aura_sphere"),
        "animators": {},
    }
    for bone, props in choreography().items():
        keyframes = []
        for prop, frames in props.items():
            for time, values in frames:
                keyframes.append({
                    "channel": prop,
                    "time": round(time, 4),
                    "interpolation": "catmullrom",
                    "data_points": [{"x": values[0], "y": values[1], "z": values[2]}],
                    "uuid": uid("key", bone, prop, str(time)),
                })
        keyframes.sort(key=lambda k: (k["channel"], k["time"]))
        animation["animators"][uid("bone", bone)] = {
            "name": bone,
            "type": "bone",
            "keyframes": keyframes,
        }

    return {
        "meta": {
            "format_version": "4.10",
            "model_format": "bedrock_entity",
            "box_uv": False,
        },
        "name": "lucario_aura_sphere",
        "model_identifier": "",
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {
            "width": description.get("texture_width", 128),
            "height": description.get("texture_height", 128),
        },
        "elements": elements,
        "outliner": outliner,
        "textures": [{
            "path": "",
            "name": texture.name,
            "folder": "",
            "namespace": "",
            "id": "0",
            "particle": False,
            "render_mode": "default",
            "visible": True,
            "mode": "bitmap",
            "saved": False,
            "uuid": texture_id,
            "relative_path": "",
            "source": source,
        }],
        "display": {},
        "animations": [animation],
    }


# --------------------------------------------------------------------------------------------------
# Runtime clips


def runtime_clips() -> dict[str, dict]:
    """Slice the authored 1 s choreography into the clips the mod asks for."""
    curves = choreography()
    windows = {
        # clip -> (start, end, length) on the authored timeline
        "q_windup": (0.00, 0.45, 0.45),
        "q_cast": (0.42, 0.72, 0.40),
        "q_melee": (0.50, 0.90, 0.42),
        "q": (0.00, 0.95, 0.95),
    }
    clips = {}
    for name, (start, end, length) in windows.items():
        bones: dict[str, dict] = {}
        for bone, props in curves.items():
            for prop, frames in props.items():
                bones.setdefault(bone, {})[prop] = resample(frames, start, end, length)
        clips[name] = {"animation_length": round(length, 4), "bones": bones}
    return clips


def main() -> int:
    geo = json.loads(GEO.read_text(encoding="utf-8"))
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    project = build_bbmodel(geo, TEXTURE)
    BBMODEL.write_text(json.dumps(project, ensure_ascii=False), encoding="utf-8")

    runtime = json.loads(RUNTIME.read_text(encoding="utf-8"))
    for name, clip in runtime_clips().items():
        runtime["animations"][f"animation.pkmoba_lucario.{name}"] = clip
    RUNTIME.write_text(json.dumps(runtime, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    bones = project["outliner"]
    def count(nodes):
        total = 0
        for node in nodes:
            total += 1 + count([c for c in node["children"] if isinstance(c, dict)])
        return total
    print(f"wrote {BBMODEL.relative_to(ROOT)}: {len(project['elements'])} cubes, "
          f"{count(bones)} bones, {len(project['animations'][0]['animators'])} animated bones, "
          f"{BBMODEL.stat().st_size} bytes")
    print(f"updated {RUNTIME.relative_to(ROOT)}: "
          + ", ".join(f"{n} {c['animation_length']}s" for n, c in runtime_clips().items()))
    for name in ("q_windup", "q_cast", "q_melee"):
        clip = runtime["animations"][f"animation.pkmoba_lucario.{name}"]
        missing = [b for b in ("leg_left", "foot_right", "torso2") if b not in clip["bones"]]
        if missing:
            print(f"  WARNING {name} missing stance bones {missing}")
    keys = sum(len(t) for c in runtime_clips().values() for b in c["bones"].values() for t in b.values())
    print(f"runtime keyframes for the four Q clips: {keys}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

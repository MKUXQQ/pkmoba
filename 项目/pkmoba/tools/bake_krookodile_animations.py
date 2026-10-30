# -*- coding: utf-8 -*-
"""Bake Krookodile's clips out of Cobblemon's own rig animations, then add the jaw.

Cobblemon 1.8.3 ships ``physical``, ``special``, ``battle_cry``, ``recoil`` and ``angry`` for
Krookodile. Those already move the whole body the way the species actually moves in game, so the
clips below only retime them; the jaw keyframes (the mouth open / snap shut beat the design doc
asks for) are added on top because no Cobblemon clip drives the jaw for attacks.

Run from the repository root:
    python 项目/pkmoba/tools/bake_krookodile_animations.py
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAR = Path(r"E:/我的世界/.minecraft/versions/兴年宝可梦/mods/[方块宝可梦] Cobblemon-fabric-1.8.3+1.21.1.jar")
SOURCE = "assets/cobblemon/bedrock/pokemon/animations/0553_krookodile/krookodile.animation.json"
TARGET = (ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations"
          / "pkmoba_krookodile.animation.json")

# Silhouette bones only: locators, eyelids, sunglasses and mouth-shape overlays stay untouched so
# the gesture can never tear the model apart.
COPY_BONES = {
    "body", "torso", "torso2", "neck", "head", "jaw",
    "arm_left", "arm_left2", "hand_left",
    "arm_right", "arm_right2", "hand_right",
    "leg_left", "leg_left2", "foot_left",
    "leg_right", "leg_right2", "foot_right",
    "tail", "tail2", "tail3",
}
MIRROR = {"arm_left": "arm_right", "arm_right": "arm_left",
          "arm_left2": "arm_right2", "arm_right2": "arm_left2",
          "hand_left": "hand_right", "hand_right": "hand_left",
          "leg_left": "leg_right", "leg_right": "leg_left",
          "leg_left2": "leg_right2", "leg_right2": "leg_left2",
          "foot_left": "foot_right", "foot_right": "foot_left"}
PROPS = ("rotation", "position")


def load_source() -> dict:
    with zipfile.ZipFile(JAR) as jar:
        payload = json.loads(jar.read(SOURCE).decode("utf-8"))
    return {key.rsplit(".", 1)[-1]: value for key, value in payload["animations"].items()}


def _is_time(key) -> bool:
    try:
        float(key)
        return True
    except (TypeError, ValueError):
        return False


def _vec(value):
    if isinstance(value, dict):
        try:
            return [round(float(v), 2) for v in value.get("post", value.get("pre", [0, 0, 0]))]
        except (TypeError, ValueError):
            return None
    if isinstance(value, (int, float)):
        return [round(float(value), 2)] * 3
    try:
        return [round(float(v), 2) for v in value][:3]
    except (TypeError, ValueError):
        return None  # Molang-driven channel: leave it alone.


def scale_keys(channel: dict, factor: float) -> dict:
    out = {}
    for prop in PROPS:
        raw = channel.get(prop)
        if raw is None:
            continue
        if isinstance(raw, dict) and any(_is_time(k) for k in raw):
            keys = {}
            for key, value in raw.items():
                if not _is_time(key):
                    continue
                vec = _vec(value)
                if vec is None:
                    break
                keys["%.3f" % (float(key) * factor)] = vec
            else:
                if keys:
                    out[prop] = keys
    return out


def bake(source: dict, length: float, mirror: bool = False, sink: float = 0.0,
         jaw: float = 0.0) -> dict:
    """Retime one source clip into `length` seconds, optionally mirrored, sunk or jaw-driven."""
    src_length = float(source.get("animation_length") or _duration(source))
    factor = length / src_length
    bones = {}
    for name, channel in source.get("bones", {}).items():
        if name not in COPY_BONES or name == "jaw":
            continue
        target = MIRROR.get(name, name) if mirror else name
        if target in bones:
            continue
        scaled = scale_keys(channel, factor)
        if scaled:
            bones[target] = scaled
    if sink:
        channel = bones.setdefault("body", {})
        channel["position"] = {"0": [0, 0, 0], "%.3f" % (length * .45): [0, -sink, 0],
                               "%.3f" % (length * .80): [0, -sink, 0], "%.3f" % length: [0, 0, 0]}
    if jaw:
        # Positive X rotation hinges the lower jaw downwards on this rig.
        bones["jaw"] = {"rotation": {"0": [0, 0, 0], "%.3f" % (length * .35): [jaw, 0, 0],
                                     "%.3f" % (length * .70): [-1.5, 0, 0],
                                     "%.3f" % length: [0, 0, 0]}}
    return {"animation_length": round(length, 2), "loop": False, "bones": bones}


def _duration(clip: dict) -> float:
    longest = 0.0
    for channel in clip.get("bones", {}).values():
        for prop in PROPS:
            raw = channel.get(prop)
            if isinstance(raw, dict):
                for key in raw:
                    if _is_time(key):
                        longest = max(longest, float(key))
    return longest or 1.0


def main():
    src = load_source()
    physical, special = src["physical"], src["special"]
    cry, recoil, angry = src["battle_cry"], src["recoil"], src["angry"]
    clips = {
        # Three stage bite chain: lunge, mirrored lunge, then the full-body heavy bite.
        "q_bite_l": bake(physical, .30, jaw=16),
        "q_bite_r": bake(physical, .30, mirror=True, jaw=16),
        "q_bite_heavy": bake(physical, .44, jaw=26),
        # Q Crunch: head rears back with the throat charge, then the jaws slam shut.
        "q_windup": bake(angry, .16, jaw=22),
        "q_cast": bake(physical, .55, jaw=30),
        "q_melee": bake(recoil, .30),
        # W Dig: the body sinks underground, travels, then bursts back up.
        "w_windup": bake(special, .22, sink=4),
        "w_cast": bake(special, .45, sink=6),
        "w_melee": bake(cry, .40),
        # E Earthquake: crouch, slam both arms down, then hold the recoil.
        "e_windup": bake(cry, .30),
        "e_cast": bake(special, .60),
        "e_melee": bake(recoil, .40),
        # R Outrage: the roar, then five different swings.
        "r_windup": bake(angry, .30, jaw=18),
        "r_cast": bake(cry, .50),
        "r_melee": bake(physical, .30, jaw=14),
        "r_a": bake(physical, .30, jaw=18),
        "r_b": bake(special, .35, mirror=True),
        "r_c": bake(physical, .40, jaw=24),
        "r_d": bake(special, .35),
        "r_e": bake(cry, .50, jaw=30),
    }
    payload = {"format_version": "1.8.0",
               "animations": {f"animation.pkmoba_krookodile.{name}": body
                              for name, body in clips.items()}}
    TARGET.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(clips)} baked clips -> {TARGET.name}")


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""Bake Armarouge's clips out of Cobblemon's own rig animations.

Cobblemon 1.8.3 ships ``physical``, ``special``, ``battle_cry``, ``recoil``, ``angry`` and
``tap_quirk`` for Armarouge. Those already swing the shoulders and the twin arm cannons the way the
species actually moves in game, so the clips below only retime them and mirror the left/right beats;
nothing is invented from scratch, which keeps the silhouette intact.

Run from the repository root:
    python 项目/pkmoba/tools/bake_armarouge_animations.py
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAR = Path(r"E:/我的世界/.minecraft/versions/兴年宝可梦/mods/[方块宝可梦] Cobblemon-fabric-1.8.3+1.21.1.jar")
SOURCE = "assets/cobblemon/bedrock/pokemon/animations/0936_armarouge/armarouge.animation.json"
TARGET = (ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations"
          / "pkmoba_armarouge.animation.json")

# Silhouette bones only: eyes, eyelids, pupils, locators and the flame overlays stay untouched so the
# gesture can never tear the model apart (and never fights the eye-flame Molang channels).
COPY_BONES = {
    "body", "torso", "chest", "chest_armor", "chest_armor_front", "waist",
    "neck", "head", "helmet", "cheek_spikes", "head_flame",
    "arms", "arm_left", "shoulder_left", "shoulder_left_rotation", "arm_left2", "hand_left",
    "arm_right", "shoulder_right", "shoulder_right_rotation", "arm_right2", "hand_right",
    "legs", "leg_left", "leg_left2", "foot_left",
    "leg_right", "leg_right2", "foot_right",
}
MIRROR = {"arm_left": "arm_right", "arm_right": "arm_left",
          "shoulder_left": "shoulder_right", "shoulder_right": "shoulder_left",
          "shoulder_left_rotation": "shoulder_right_rotation",
          "shoulder_right_rotation": "shoulder_left_rotation",
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


def bake(source: dict, length: float, mirror: bool = False, recoil: float = 0.0) -> dict:
    """Retime one source clip into `length` seconds, optionally mirrored / leaned back."""
    src_length = float(source.get("animation_length") or _duration(source))
    factor = length / src_length
    bones = {}
    for name, channel in source.get("bones", {}).items():
        if name not in COPY_BONES:
            continue
        target = MIRROR.get(name, name) if mirror else name
        if target in bones:
            continue
        scaled = scale_keys(channel, factor)
        if scaled:
            bones[target] = scaled
    if recoil:
        # Cannon kick: the torso leans back on release and settles again.
        channel = bones.setdefault("torso", {})
        channel["rotation"] = {"0": [0, 0, 0], "%.3f" % (length * .25): [recoil, 0, 0],
                               "%.3f" % (length * .70): [recoil * .4, 0, 0],
                               "%.3f" % length: [0, 0, 0]}
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
    tap = src["tap_quirk"]
    clips = {
        # Three stage fire bolt: right arm, left arm, then both cannons together.
        "q_fire_l": bake(physical, .26),
        "q_fire_r": bake(physical, .26, mirror=True),
        "q_fire_heavy": bake(special, .40, recoil=6),
        # Q Psyshock: one arm draws back, snaps forward, then settles.
        "q_windup": bake(physical, .10),
        "q_cast": bake(special, .35, recoil=4),
        "q_melee": bake(recoil, .25),
        # W Flame Charge: crouch, ignite the thrusters, burst forward.
        "w_windup": bake(tap, .15),
        "w_cast": bake(physical, .35),
        "w_melee": bake(cry, .30),
        # E Armor Cannon: both shoulder plates open, the barrels charge, then fire as one.
        "e_windup": bake(tap, .20),
        "e_cast": bake(special, .45, recoil=9),
        "e_melee": bake(recoil, .35),
        # R Expanding Force: arms lift, the field detonates five times, then drops.
        "r_windup": bake(tap, .40),
        "r_cast": bake(cry, .50, recoil=5),
        "r_pulse": bake(special, .25, recoil=3),
        "r_melee": bake(recoil, .35),
    }
    payload = {"format_version": "1.8.0",
               "animations": {f"animation.pkmoba_armarouge.{name}": body
                              for name, body in clips.items()}}
    TARGET.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(clips)} baked clips -> {TARGET.name}")


if __name__ == "__main__":
    main()

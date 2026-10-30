# -*- coding: utf-8 -*-
"""Bake Ceruledge's clips out of Cobblemon's own rig animations.

Cobblemon 1.8.3 ships a full 3s ``physical`` swing, a 3s ``special`` cast, ``battle_cry`` and
``recoil`` for Ceruledge. Hand-authored keyframes never looked right because they only nudged
two bones; lifting the real channels (and only retiming them) makes the whole body move the way
the species actually moves in game.

Run from the repository root:
    python 项目/pkmoba/tools/bake_ceruledge_animations.py
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAR = Path(r"E:/我的世界/.minecraft/versions/兴年宝可梦/mods/[方块宝可梦] Cobblemon-fabric-1.8.3+1.21.1.jar")
SOURCE = "assets/cobblemon/bedrock/pokemon/animations/0937_ceruledge/ceruledge.animation.json"
TARGET = (ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations"
          / "pkmoba_ceruledge.animation.json")

# Silhouette bones only: locators, eyelids and flame overlays stay untouched so the gesture
# can never tear the model.
COPY_BONES = {
    "body", "waist", "torso", "chest", "neck", "head", "head_flame",
    "arm_left", "arm_left2", "arm_left3", "arm_right", "arm_right2", "arm_right3",
    "leg_left", "leg_left2", "leg_right", "leg_right2",
    "blade_left", "blade_right",
}
MIRROR = {"arm_left": "arm_right", "arm_right": "arm_left",
          "arm_left2": "arm_right2", "arm_right2": "arm_left2",
          "arm_left3": "arm_right3", "arm_right3": "arm_left3",
          "leg_left": "leg_right", "leg_right": "leg_left",
          "leg_left2": "leg_right2", "leg_right2": "leg_left2",
          "blade_left": "blade_right", "blade_right": "blade_left"}
PROPS = ("rotation", "position")


def load_source() -> dict:
    with zipfile.ZipFile(JAR) as jar:
        payload = json.loads(jar.read(SOURCE).decode("utf-8"))
    return {key.rsplit(".", 1)[-1]: value for key, value in payload["animations"].items()}


def scale_keys(channel: dict, factor: float) -> dict:
    """Scale every keyframe time by `factor` so a 3s source clip fits a 0.3s attack window."""
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
        return None  # Molang-driven channel (flame overlays) - leave it to Cobblemon's own clips.


def bake(source: dict, length: float, length_of_source: float | None = None, mirror: bool = False) -> dict:
    """Retime one source clip into a `length`-second clip for the PKMOBA rig."""
    src_length = length_of_source or _duration(source)
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
    return {"animation_length": round(length, 2), "loop": False, "bones": bones}


def _duration(clip: dict) -> float:
    declared = clip.get("animation_length")
    if isinstance(declared, (int, float)) and declared > 0:
        return float(declared)
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
    cry, recoil = src["battle_cry"], src["recoil"]
    clips = {
        # Twin-blade basic chain: the real swing, mirrored for the off hand.
        "q_swing_l": bake(physical, .26),
        "q_swing_r": bake(physical, .26, mirror=True),
        "q_cross": bake(physical, .38),
        # Q blade wave: a physical swing into a short recoil hold.
        "q_windup": bake(cry, .15),
        "q_cast": bake(physical, .45),
        "q_melee": bake(recoil, .30),
        # W shadow dash: the special cast, which already crouches and drives forward.
        "w_windup": bake(special, .20),
        "w_cast": bake(special, .35),
        "w_melee": bake(recoil, .30),
        # E ruin blade combo: special for the wind-up, physical for the three cuts.
        "e_windup": bake(special, .20),
        "e_cast": bake(physical, .50),
        "e_melee": bake(recoil, .35),
        # R sword dance: the battle cry raises both blades, then holds.
        "r_windup": bake(cry, .30),
        "r_cast": bake(cry, .50),
        "r_melee": bake(physical, .30),
    }
    payload = {"format_version": "1.8.0",
               "animations": {f"animation.pkmoba_ceruledge.{name}": body for name, body in clips.items()}}
    TARGET.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(clips)} baked clips -> {TARGET.name}")


if __name__ == "__main__":
    main()

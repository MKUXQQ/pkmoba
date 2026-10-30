"""生成路卡利欧 Q（波导弹）动画：按参考图的非对称姿态。

参考图：
- 蓄力图：一侧手臂向前下方伸出、手掌在腰腹前握着波导弹（肘约 135°），另一侧手臂横过胸前靠近对侧肩；
  宽站姿、前腿屈膝。
- 投掷图：前手沿正前方向推出（手臂近乎伸直），另一手向后收到腰侧，弓步前压。

姿态角度来自 tools/bb_solve_asym.py 的离线 FK 逆解（.scratch/solved_q_asym.json），
写回 mod 资源 pkmoba_lucario.animation.json，并用 bb_pose_preview 渲染检查帧。

python tools/gen_lucario_q.py [--write]
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from bb_pose_preview import load_geo, render  # noqa: E402

ANIM_JSON = (ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/bedrock/pokemon/animations/"
                    "pkmoba_lucario.animation.json")
SOLVED = ROOT / ".scratch/solved_q_asym.json"
OUTDIR = ROOT / ".scratch/render"

LEGS_NEUTRAL = {"legs": [0, 0, 0], "leg_left": [0, 0, 0], "leg_left2": [0, 0, 0], "leg_left3": [0, 0, 0],
                "leg_right": [0, 0, 0], "leg_right2": [0, 0, 0], "leg_right3": [0, 0, 0],
                "foot_left": [0, 0, 0], "foot_right": [0, 0, 0]}
# 宽站姿、前腿（leg_left）微屈
LEGS_STANCE = {"legs": [9, 0, 0], "leg_left": [-14, 0, 0], "leg_left2": [17, 0, 0], "leg_left3": [-6, 0, 0],
               "leg_right": [7, 0, 0], "leg_right2": [13, 0, 0], "leg_right3": [-5, 0, 0],
               "foot_left": [-3, 0, 0], "foot_right": [-6, 0, 0]}
# 弓步：前腿压得更低，后腿蹬直
LEGS_LUNGE = {"legs": [6, 0, 0], "leg_left": [-20, 0, 0], "leg_left2": [12, 0, 0], "leg_left3": [-4, 0, 0],
              "leg_right": [15, 0, 0], "leg_right2": [7, 0, 0], "leg_right3": [-3, 0, 0],
              "foot_left": [-2, 0, 0], "foot_right": [-10, 0, 0]}

BODY_IDLE = {"torso": [0, 0, 0], "torso2": [0, 0, 0], "head": [0, 0, 0], "head_angle": [0, 0, 0],
             "ears": [0, 0, 0]}
BODY_GATHER = {"torso": [5, -9, 0], "torso2": [2, -4, 0], "head": [-9, 7, 0],
               "head_angle": [-2, 0, 0], "ears": [-6, 0, 0]}
BODY_THRUST = {"torso": [-5, 11, 0], "torso2": [-2, 5, 0], "head": [4, -7, 0],
               "head_angle": [2, 0, 0], "ears": [13, 0, 0]}
BODY_SETTLE = {"torso": [2, 3, 0], "torso2": [1, 1, 0], "head": [-3, 2, 0],
               "head_angle": [0, 0, 0], "ears": [2, 0, 0]}


def lerp(a, b, t):
    return [round(a[i] + (b[i] - a[i]) * t, 1) for i in range(3)]


def blend(p0, p1, t):
    out = {}
    for k in set(p0) | set(p1):
        out[k] = lerp(p0.get(k, [0, 0, 0]), p1.get(k, [0, 0, 0]), t)
    return out


def arms(entry):
    pose = {}
    for side in ("left", "right"):
        for bone, v in entry[side].items():
            pose[bone] = [0.0, float(v[0]), float(v[1])]
    return pose


def main():
    write = "--write" in sys.argv
    solved = json.loads(SOLVED.read_text(encoding="utf-8"))
    relax = arms(solved["relax"])
    gather = arms(solved["gather"])
    hold = arms(solved["hold"])
    thrust = arms(solved["thrust"])
    settle = arms(solved["settle"])

    P_RELAX = {**BODY_IDLE, **LEGS_NEUTRAL, **relax}
    P_GATHER = {**BODY_GATHER, **LEGS_STANCE, **gather}
    P_HOLD = {**BODY_GATHER, **LEGS_STANCE, **hold}
    P_THRUST = {**BODY_THRUST, **LEGS_LUNGE, **thrust}
    P_SETTLE = {**BODY_SETTLE, **LEGS_STANCE, **settle}

    P_IDLE = P_RELAX

    wind = [
        (0.00, P_IDLE),
        (0.14, blend(P_IDLE, P_GATHER, 0.6)),
        (0.26, P_GATHER),
        (0.38, P_HOLD),
        (0.48, P_HOLD),
    ]
    cast = [
        (0.00, P_HOLD),
        (0.07, P_THRUST),
        (0.14, P_THRUST),
        (0.24, blend(P_THRUST, P_SETTLE, 0.55)),
        (0.42, P_SETTLE),
    ]

    def to_json(rows):
        return {f"{t:.2f}": {b: [round(float(x), 1) for x in v] for b, v in pose.items()} for t, pose in rows}

    clips = {
        "animation.pkmoba_lucario.q_windup": (0.48, to_json(wind)),
        "animation.pkmoba_lucario.q_cast": (0.42, to_json(cast)),
    }

    if write:
        data = json.loads(ANIM_JSON.read_text(encoding="utf-8"))
        for name, (length, frames) in clips.items():
            bones = {}
            for tkey, pose in frames.items():
                for bone, rot in pose.items():
                    bones.setdefault(bone, {"rotation": {}})["rotation"][tkey] = rot
            data["animations"][name] = {"animation_length": length, "bones": bones}
        ANIM_JSON.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print("wrote", ANIM_JSON)

    OUTDIR.mkdir(parents=True, exist_ok=True)
    load_geo()
    for tag, pose in (("relax", P_RELAX), ("gather", P_GATHER), ("hold", P_HOLD),
                      ("thrust", P_THRUST), ("settle", P_SETTLE)):
        imgs = render({k: list(v) for k, v in pose.items()}, None)
        for view, img in zip(("front", "left"), imgs):
            img.save(OUTDIR / f"q_{tag}_{view}.png")
    print("rendered q_* frames to", OUTDIR)


if __name__ == "__main__":
    main()

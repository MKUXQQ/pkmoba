"""路卡利欧 Q 的非对称姿态求解（按官方立绘：右侧出手、左侧收胸前）。

左右臂各自独立求解，不做镜像。姿态目标来自参考图：
- gather（蓄力）：右臂前下伸出，手掌在腰前握球（肘约 135°）；左臂横过胸前，手靠近右肩。
- thrust（投掷）：右臂沿正前方向前推到胸前；左臂收到身体左后侧腰边。
- settle（收势）：右臂回收，左臂回到肩侧。

python tools/bb_solve_asym.py
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bb_pose_preview import bone_world, load_geo, render  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]

CTRL = {
    "left": ["arm_left", "arm_left2", "wrist_left"],
    "right": ["arm_right", "arm_right2", "wrist_right"],
}
# 分档限幅：上臂 ±140，前臂 ±125，手腕只允许小幅扭转（±55），避免求解出“反折手”那种畸形角度
LIMITS = {"arm": 140.0, "forearm": 125.0, "wrist": 55.0}


def limits_for(bone):
    if bone.startswith("arm_") and bone[-1].isdigit():
        return LIMITS["forearm"]
    if bone.startswith("arm_"):
        return LIMITS["arm"]
    return LIMITS["wrist"]


def fk(pose, bones, name):
    return bone_world(name, bones, pose, "ZYX", {})[:3, 3]


def solve_side(bones, side, target, elbow_target, seed=None, straight=0.0, spike_dir=None,
               spike_w=1.5, reg=0.05):
    ctrl = CTRL[side]
    hand_name = f"hand_{side}"
    elbow_name = f"arm_{side}2"

    def build(vals):
        return {n: [0.0, vals[n][0], vals[n][1]] for n in ctrl}

    def cost(v):
        pose = build(v)
        hand = fk(pose, bones, hand_name)
        elbow = fk(pose, bones, elbow_name)
        c = float(np.linalg.norm(hand - np.array(target, dtype=float))) * 2.0
        c += float(np.linalg.norm(elbow - np.array(elbow_target, dtype=float))) * 0.8
        if straight:
            c += straight * float(np.hypot(elbow[0] - hand[0], elbow[1] - hand[1]))
        if spike_dir:
            axis = bone_world(hand_name, bones, pose, "ZYX", {})[:3, 0]
            d = np.array(spike_dir, dtype=float)
            d /= np.linalg.norm(d)
            c += spike_w * float(np.linalg.norm(axis - d))
        c += reg * sum(abs(a) for p in v.values() for a in p) / 100.0
        c += 0.03 * (abs(v[ctrl[2]][0]) + abs(v[ctrl[2]][1]))  # 手腕尽量少动
        # 上臂尽量不要越过 100°，避免求解器走到“绕远路”的等价解（手位对但手臂拧成麻花）
        uy, uz = v[ctrl[0]]
        c += 0.06 * (max(0.0, abs(uy) - 100.0) + max(0.0, abs(uz) - 100.0))
        return c

    starts = [dict((n, [0.0, 0.0]) for n in ctrl)]
    if seed:
        starts.insert(0, {n: list(seed[n]) for n in ctrl})
    for y, z in ((60.0, -60.0), (-60.0, -60.0), (80.0, 40.0), (-80.0, 40.0), (0.0, -110.0),
                 (0.0, 110.0), (100.0, -100.0), (-100.0, 100.0),
                 (90.0, 0.0), (-90.0, 0.0), (45.0, -20.0), (70.0, 20.0)):
        starts.append({ctrl[0]: [y, z], ctrl[1]: [0.0, -45.0], ctrl[2]: [0.0, 0.0]})
    for y, z in ((90.0, 0.0), (70.0, 10.0), (90.0, 20.0)):
        starts.append({ctrl[0]: [y, z], ctrl[1]: [0.0, 0.0], ctrl[2]: [0.0, 0.0]})

    best_g, best_v = None, None
    for start in starts:
        vals = {k: list(v) for k, v in start.items()}
        best = cost(vals)
        step = 45.0
        while step > 0.2:
            improved = False
            for n in ctrl:
                for i in range(2):
                    for d in (step, -step):
                        trial = {k: list(x) for k, x in vals.items()}
                        trial[n][i] += d
                        lim = limits_for(n)
                        if not (-lim <= trial[n][i] <= lim):
                            continue
                        c = cost(trial)
                        if c < best - 1e-4:
                            best, vals, improved = c, trial, True
            if not improved:
                step *= 0.5
        if best_g is None or best < best_g:
            best_g, best_v = best, vals
    return {k: [round(x, 1) for x in v] for k, v in best_v.items()}, best_g


# 目标点（Blockbench / 游戏世界坐标；-Z 为面朝方向，+X 为模型自己的右侧）
# 解剖事实（已用 Blockbench getWorldPosition 核对）：arm_right 肩点 x=+4.65，arm_left 肩点 x=-4.65；
# 肩到手约 10.7，肩到肘约 5.7。
# 参考图姿态：右手前下伸出、在腰腹前握着波导弹（肘约 135°）；左臂横过胸前靠近右肩。
POSES = {
    # 起手前的自然站姿：手臂垂在身体两侧（geo 原点是 T-pose，直接给 0 会让手臂瞬间张开）
    "relax": {
        "right": dict(hand=[5.6, 22.6, -1.8], elbow=[6.8, 27.2, -1.0], straight=0.2,
                      spike_dir=[-0.55, -0.8, -0.2]),
        "left": dict(hand=[-5.6, 22.6, -1.8], elbow=[-6.8, 27.2, -1.0], straight=0.2,
                     spike_dir=[0.55, -0.8, -0.2]),
    },
    # 蓄力：双手都收到腰前、掌心相对夹住波导弹（掌心间距约 4 格）
    "gather": {
        "right": dict(hand=[2.6, 23.6, -5.2], elbow=[6.6, 26.4, -1.4], straight=0.0,
                      spike_dir=[-0.85, -0.35, -0.35]),
        "left": dict(hand=[-2.6, 23.6, -5.2], elbow=[-6.6, 26.4, -1.4], straight=0.0,
                     spike_dir=[0.85, -0.35, -0.35]),
    },
    # 蓄力定格：手掌再收一点点（与 gather 同一解支，避免求出反折前臂）
    "hold": {
        "right": dict(hand=[2.4, 23.9, -5.5], elbow=[6.6, 26.5, -1.3], straight=0.0,
                      spike_dir=[-0.85, -0.33, -0.38]),
        "left": dict(hand=[-2.4, 23.9, -5.5], elbow=[-6.6, 26.5, -1.3], straight=0.0,
                     spike_dir=[0.85, -0.33, -0.38]),
    },
    # 投掷：右手沿正前方推出（近伸直）；左手后拉到腰侧
    "thrust": {
        "right": dict(hand=[2.6, 28.0, -10.4], elbow=[4.8, 30.0, -5.2], straight=0.6,
                      spike_dir=[0.1, 0.15, -0.98]),
        "left": dict(hand=[-4.6, 22.4, 1.6], elbow=[-6.4, 26.6, 1.2], straight=0.2,
                     spike_dir=[-0.4, -0.8, 0.45]),
    },
    # 收势：右手回收、左手回到身侧
    "settle": {
        "right": dict(hand=[4.2, 25.0, -8.2], elbow=[7.4, 27.6, -2.4], straight=0.15,
                      spike_dir=[-0.2, -0.9, -0.4]),
        "left": dict(hand=[-5.2, 24.6, -1.6], elbow=[-8.0, 27.4, -0.8], straight=0.15,
                     spike_dir=[0.45, -0.85, -0.25]),
    },
}


def main():
    bones, _ = load_geo()
    solved = {}
    for tag, spec in POSES.items():
        entry = {}
        for side in ("left", "right"):
            s = spec[side]
            vals, cost = solve_side(bones, side, s["hand"], s["elbow"],
                                    straight=s.get("straight", 0.0),
                                    spike_dir=s.get("spike_dir"))
            pose = {n: [0.0, vals[n][0], vals[n][1]] for n in CTRL[side]}
            hand = fk(pose, bones, f"hand_{side}")
            entry[side] = vals
            entry[f"{side}_hand"] = [round(v, 2) for v in hand]
            entry[f"{side}_cost"] = round(cost, 2)
            print(f"{tag}/{side}: {vals} hand={np.round(hand, 2)} cost={cost:.2f}")
        solved[tag] = entry

    out = ROOT / ".scratch" / "solved_q_asym.json"
    out.write_text(json.dumps(solved, indent=2), encoding="utf-8")
    print("wrote", out)

    outdir = ROOT / ".scratch" / "render"
    outdir.mkdir(parents=True, exist_ok=True)
    for tag, entry in solved.items():
        pose = {}
        for side in ("left", "right"):
            for n, v in entry[side].items():
                pose[n] = [0.0, v[0], v[1]]
        imgs = render(pose, None, size=520)
        for view, img in zip(("front", "left"), imgs):
            img.save(outdir / f"asym_{tag}_{view}.png")
    print("rendered asym_* to", outdir)


if __name__ == "__main__":
    main()

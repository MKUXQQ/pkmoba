"""在 lucario 骨骼上做姿态求解：左臂求解 + 镜像到右臂（离线 FK）。

python tools/bb_solve_pose.py
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bb_pose_preview import bone_world, load_geo, render  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
CTRL = ["arm_left", "arm_left2", "wrist_left"]


def fk(pose, bones, name):
    return bone_world(name, bones, pose, "ZYX", {})[:3, 3]


def build(pose_vals, legs=None):
    pose = {}
    for n, (y, z) in pose_vals.items():
        pose[n] = [0.0, y, z]
    if legs:
        pose.update(legs)
    return pose


def mirror(pose_vals):
    out = {}
    for n, v in pose_vals.items():
        y, z = (v if len(v) == 2 else (v[1], v[2]))
        rn = n.replace("_left", "_right")
        out[rn] = [0.0, -y, -z]
    return out


BOUNDS = {"arm_left": (-110.0, 70.0), "arm_left2": (-80.0, 80.0), "wrist_left": (-45.0, 45.0)}


def solve(bones, target, elbow_target, seed=None, reg=0.05, straight=0.0, spike_dir=None, spike_w=1.6):
    starts = []
    if seed:
        starts.append(seed)
    starts += [
        {n: [0.0, 0.0] for n in CTRL},
        {"arm_left": [65.0, -55.0], "arm_left2": [0.0, -45.0], "wrist_left": [0.0, 0.0]},
        {"arm_left": [80.0, -20.0], "arm_left2": [0.0, -60.0], "wrist_left": [0.0, 0.0]},
        {"arm_left": [40.0, 20.0], "arm_left2": [0.0, -70.0], "wrist_left": [0.0, 0.0]},
        {"arm_left": [95.0, -80.0], "arm_left2": [0.0, -30.0], "wrist_left": [0.0, 0.0]},
    ]

    def cost_of(v):
        pose = build(v)
        hand = fk(pose, bones, "hand_left")
        elbow = fk(pose, bones, "arm_left2")
        c = float(np.linalg.norm(hand - np.array(target))) * 2.0
        c += float(np.linalg.norm(elbow - np.array(elbow_target))) * 0.8
        if straight:
            c += straight * float(np.hypot(elbow[0] - hand[0], elbow[1] - hand[1]))
        if spike_dir:
            M = bone_world("hand_left", bones, pose, "ZYX", {})
            axis = M[:3, 0]
            d = np.array(spike_dir, dtype=float)
            d = d / np.linalg.norm(d)
            c += spike_w * float(np.linalg.norm(axis - d))
        c += reg * sum(abs(a) for p in v.values() for a in p) / 100.0
        return c

    best_global, best_vals = None, None
    for start in starts:
        vals = {k: list(v) for k, v in start.items()}
        best = cost_of(vals)
        step = 40.0
        while step > 0.25:
            improved = False
            for n in CTRL:
                lo, hi = BOUNDS[n]
                for i in range(2):
                    for d in (step, -step):
                        trial = {k: list(x) for k, x in vals.items()}
                        trial[n][i] += d
                        if not (lo <= trial[n][i] <= hi):
                            continue
                        c = cost_of(trial)
                        if c < best - 1e-4:
                            best, vals, improved = c, trial, True
            if not improved:
                step *= 0.5
        if best_global is None or best < best_global:
            best_global, best_vals = best, vals
    return {k: [round(x, 1) for x in v] for k, v in best_vals.items()}, best_global


TARGETS = {
    # 收腰凝能：双掌在腰前、掌心相对，手背尖刺朝下（不要向两侧张开）
    "gather": dict(hand=[-3.0, 21.6, -5.2], elbow=[-8.5, 24.5, 1.2], straight=0.0,
                   spike_dir=[0.15, -0.95, -0.25]),
    "hold": dict(hand=[-2.7, 22.0, -5.6], elbow=[-8.0, 25.0, 1.0], straight=0.0,
                 spike_dir=[0.15, -0.95, -0.25]),
    # 发射：双掌前推（手臂沿 z 轴伸出，尖刺朝前上方）
    "thrust": dict(hand=[-3.0, 27.5, -14.0], elbow=[-5.0, 28.5, -5.5], straight=0.45,
                   spike_dir=[0.0, 0.55, -0.84]),
    # 收势
    "settle": dict(hand=[-6.5, 25.5, -8.0], elbow=[-9.5, 28.0, -1.5], straight=0.15,
                   spike_dir=[0.25, -0.9, -0.35]),
}


def main():
    bones, _ = load_geo()
    solved = {}
    prev = None
    for tag, spec in TARGETS.items():
        left, cost = solve(bones, spec["hand"], spec["elbow"], seed=prev,
                           straight=spec.get("straight", 0.0),
                           spike_dir=spec.get("spike_dir"))
        prev = left
        pose = build(left)
        pose.update(mirror(left))
        solved[tag] = {"left": left, "right": mirror(left), "cost": round(cost, 2),
                       "hand": [round(v, 2) for v in fk(pose, bones, "hand_left")]}
        print(tag, solved[tag])

    (ROOT / ".scratch" / "solved_arms.json").write_text(json.dumps(solved, indent=2), encoding="utf-8")

    outdir = ROOT / ".scratch" / "render"
    outdir.mkdir(parents=True, exist_ok=True)
    legs = {"legs": [6.0, 0, 0], "leg_left": [-4.0, 0, 0], "leg_left2": [10.0, 0, 0],
            "leg_right": [-4.0, 0, 0], "leg_right2": [10.0, 0, 0]}
    for tag in TARGETS:
        pose = build(solved[tag]["left"], legs)
        pose.update(mirror(solved[tag]["left"]))
        pose.update({"torso": [3.0, -8.0, 0.0], "head": [-6.0, 5.0, 0.0]})
        imgs = render(pose, None)
        for view, img in zip(("front", "left"), imgs):
            img.save(outdir / f"solved_{tag}_{view}.png")
    print("rendered to", outdir)


if __name__ == "__main__":
    main()

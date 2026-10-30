"""离线渲染 lucario 模型（不依赖 Blockbench），用于验证骨骼姿态。

用法:
    python tools/bb_pose_preview.py              # 渲染 rest 姿态
    python tools/bb_pose_preview.py pose.json    # 渲染给定姿态（bone -> [rx,ry,rz]）
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

GEO = Path(__file__).resolve().parents[2] / (
    "宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/"
    "bedrock/pokemon/models/0448_lucario/lucario.geo.json"
)
OUT = Path(__file__).resolve().parents[3] / ".scratch" / "render"

BONE_COLORS = {
    "arm_left": (60, 110, 200), "arm_left2": (80, 140, 230), "arm_right": (60, 110, 200),
    "arm_right2": (80, 140, 230), "hand_left": (240, 240, 120), "hand_right": (240, 240, 120),
    "wrist_left": (230, 200, 90), "wrist_right": (230, 200, 90),
    "head": (150, 200, 255), "torso": (120, 170, 240), "body": (120, 170, 240),
    "leg_left": (90, 90, 120), "leg_right": (90, 90, 120),
}


def load_geo():
    """读 lucario 的 bedrock geo，并把 X 轴翻转成 Blockbench / 游戏世界坐标。

    bedrock geo 文件里的 x 与 Blockbench 导入后的世界 x 相反（Blockbench 导入时镜像 X）。
    不翻转就会渲染出左右颠倒的镜像图，姿态方向也会判反（这一点已用 Blockbench 内
    getWorldPosition 数值探针核实：geo 里 arm_left pivot x=+4.65，而 Blockbench 世界
    里 arm_right 的肩点在 x=+4.65）。
    """
    data = json.loads(GEO.read_text(encoding="utf-8"))
    geo = data["minecraft:geometry"][0]
    out = dict(geo)
    bones = {}
    for b in geo["bones"]:
        nb = dict(b)
        nb["pivot"] = [-float(b["pivot"][0])] + [float(v) for v in b["pivot"][1:]]
        if "rotation" in b:
            rx, ry, rz = [float(v) for v in b["rotation"]]
            nb["rotation"] = [rx, -ry, -rz]
        cubes = []
        for c in b.get("cubes", []):
            nc = dict(c)
            ox, oy, oz = [float(v) for v in c["origin"]]
            sx, sy, sz = [float(v) for v in c["size"]]
            nc["origin"] = [-ox - sx, oy, oz]
            cubes.append(nc)
        nb["cubes"] = cubes
        bones[nb["name"]] = nb
    return bones, out


def rot_matrix(deg, order="ZYX"):
    rx, ry, rz = (math.radians(a) for a in deg)
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return {"ZYX": Rz @ Ry @ Rx, "XYZ": Rx @ Ry @ Rz}[order]


def bone_world(name, bones, pose, order="ZYX", cache=None):
    cache = {} if cache is None else cache
    if name in cache:
        return cache[name]
    b = bones[name]
    pivot = np.array(b.get("pivot", [0, 0, 0]), dtype=float)
    parent = b.get("parent")
    rot = pose.get(name, b.get("rotation", [0, 0, 0]))
    local = np.eye(4)
    if parent and parent in bones:
        ppivot = np.array(bones[parent].get("pivot", [0, 0, 0]), dtype=float)
        local[:3, 3] = pivot - ppivot
        M = bone_world(parent, bones, pose, order, cache) @ local
    else:
        local[:3, 3] = pivot
        M = local.copy()
    R = np.eye(4)
    R[:3, :3] = rot_matrix(rot, order)
    M = M @ R
    cache[name] = M
    return M


def render(pose, path, order="ZYX", views=("front", "left"), size=260):
    bones, geo = load_geo()
    cache = {}
    quads = []
    for name, b in bones.items():
        M = bone_world(name, bones, pose, order, cache)
        pivot = np.array(b.get("pivot", [0, 0, 0]), dtype=float)
        color = BONE_COLORS.get(name, (170, 170, 170))
        for cube in b.get("cubes", []):
            o = np.array(cube["origin"], dtype=float)
            s = np.array(cube["size"], dtype=float)
            corners = []
            for dx in (0, 1):
                for dy in (0, 1):
                    for dz in (0, 1):
                        v = o + s * np.array([dx, dy, dz])
                        corners.append((M @ np.append(v - pivot, 1.0))[:3])
            idx = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
            for q in idx:
                pts = [corners[i] for i in q]
                quads.append((pts, color))

    imgs = []
    for view in views:
        img = Image.new("RGB", (size, size), (24, 26, 32))
        drw = ImageDraw.Draw(img)
        center = np.array([0.0, 26.0, 0.0])
        scale = size / 78.0

        def project(p):
            if view == "front":
                x, y, depth = p[0], p[1], p[2]
            else:
                x, y, depth = p[2], p[1], -p[0]
            return (size / 2 + (x - 0) * scale, size / 2 - (y - center[1]) * scale), depth

        faces = []
        for pts, color in quads:
            n = np.cross(np.subtract(pts[1], pts[0]), np.subtract(pts[2], pts[0]))
            norm = np.linalg.norm(n)
            if norm < 1e-6:
                continue
            n = n / norm
            if view == "front":
                if n[2] > 0.02:
                    continue
                lam = max(0.0, float(np.dot(n, np.array([-0.35, 0.45, -0.82]))))
            else:
                if n[0] < -0.02:
                    continue
                lam = max(0.0, float(np.dot(n, np.array([0.82, 0.45, -0.35]))))
            shade = tuple(int(min(255, c * (0.55 + 0.55 * lam))) for c in color)
            proj = []
            depth = 0.0
            for p in pts:
                (px, py), d = project(p)
                proj.append((px, py))
                depth += d
            faces.append((depth / len(pts), proj, shade))
        faces.sort(key=lambda f: -f[0])
        for _, proj, shade in faces:
            drw.polygon(proj, fill=shade, outline=(18, 20, 26))
        img.save(path) if isinstance(path, Path) else None
        imgs.append(img)
    return imgs


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    order = "ZYX"
    pose = {}
    tag = "rest"
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg in ("ZYX", "XYZ"):
            order = arg
        else:
            pose = json.loads(Path(arg).read_text(encoding="utf-8"))
            tag = Path(arg).stem
    imgs = render(pose, None, order=order)
    for view, img in zip(("front", "left"), imgs):
        p = OUT / f"{tag}_{order}_{view}.png"
        img.save(p)
        print(p)


if __name__ == "__main__":
    main()

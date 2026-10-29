"""修复特效贴图的「灰蒙蒙」暗边（premultiplied-by-blur 缺陷）。

成因：生成器把一张手绘板先做高斯模糊（`im.filter(GaussianBlur)`，模糊会连同透明像素的
黑色 RGB 一起混进来），再把清晰图叠上去保存。PIL 存的是 straight alpha，但模糊后的 RGB
其实已经被 alpha 乘过一次 —— 于是所有半透明像素的 RGB 变成 `color * alpha`，
在游戏里用 entityTranslucentEmissive 混合时就显示成「发灰/发暗的雾」。

修法只动 RGB、不动 alpha、不动形状：把半透明像素的 RGB 除回去（unpremultiply），
可选再对高亮核心做一点点提亮（--lift）。

    python tools/fix_effect_glow.py --check        # 只体检，列出可疑贴图
    python tools/fix_effect_glow.py                # 修复全部可疑贴图
    python tools/fix_effect_glow.py pikachu_bolt.png pikachu_web.png
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image

EFFECT = os.path.join("src", "main", "resources", "assets", "pkmoba", "textures", "effect")
# 半透明区亮度明显低于不透明区，就是被 alpha 乘过的特征
SUSPECT_RATIO = 0.72
MIN_SEMI = 200
MIN_OPAQUE = 200


def load(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGBA")).astype(np.float32)


def fringe_ratio(a: np.ndarray) -> tuple[float, int, int]:
    """返回 (半透明区亮度 / 不透明区亮度, 半透明像素数, 不透明像素数)。"""
    al = a[..., 3]
    lum = a[..., :3].mean(axis=2)
    semi = (al > 16) & (al < 200)
    opaque = al >= 200
    if semi.sum() < MIN_SEMI or opaque.sum() < MIN_OPAQUE:
        return 1.0, int(semi.sum()), int(opaque.sum())
    return float(lum[semi].mean() / max(lum[opaque].mean(), 1e-3)), int(semi.sum()), int(opaque.sum())


def unpremultiply(a: np.ndarray, lift: float) -> np.ndarray:
    """把 RGB 里被乘进去的 alpha 除掉；alpha 与图形轮廓完全不动。"""
    out = a.copy()
    al = out[..., 3]
    rgb = out[..., :3]

    alpha = np.clip(al / 255.0, 0.0, 1.0)
    # 只处理部分透明像素：a=255 的像素本来就是 straight，不用除
    partial = (al > 0) & (al < 255)
    alpha_safe = np.where(partial, np.maximum(alpha, 1.0 / 255.0), 1.0)
    rgb = np.where(partial[..., None], rgb / alpha_safe[..., None], rgb)
    rgb = np.clip(rgb, 0.0, 255.0)

    if lift > 0.0:
        # 高亮核心往白里提一点，让叠在暗背景上的特效更「自发光」
        weight = np.clip(alpha, 0.0, 1.0)[..., None] * lift
        rgb = rgb + (255.0 - rgb) * weight

    out[..., :3] = np.clip(rgb, 0.0, 255.0)
    return out


def save(path: str, a: np.ndarray) -> None:
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def scan(root: str) -> list[tuple[str, float]]:
    found = []
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".png"):
                continue
            path = os.path.join(dirpath, name)
            ratio, semi, opaque = fringe_ratio(load(path))
            if ratio < SUSPECT_RATIO and semi >= MIN_SEMI:
                found.append((os.path.relpath(path, root), ratio))
    return found


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("targets", nargs="*", help="只处理这些文件名（默认处理整个 effect 目录里的可疑贴图）")
    parser.add_argument("--check", action="store_true", help="只体检不写盘")
    parser.add_argument("--lift", type=float, default=0.10, help="高亮核心提亮权重，0 表示不提亮")
    parser.add_argument("--root", default=EFFECT)
    args = parser.parse_args(argv)

    root = args.root
    if not os.path.isdir(root):
        print("目录不存在:", os.path.abspath(root))
        return 2

    if args.targets:
        paths = []
        for t in args.targets:
            p = t if os.path.isabs(t) else os.path.join(root, t)
            if not os.path.exists(p):
                print("找不到:", p)
                return 2
            paths.append(p)
    else:
        paths = [os.path.join(root, rel) for rel, _ in scan(root)]

    if not paths:
        print("没有需要修复的贴图")
        return 0

    print(f"{'texture':40s} {'before':>7s} {'after':>7s}")
    for p in paths:
        a = load(p)
        before, _, _ = fringe_ratio(a)
        fixed = unpremultiply(a, args.lift)
        after, _, _ = fringe_ratio(fixed)
        rel = os.path.relpath(p, root)
        print(f"{rel:40s} {before:7.2f} {after:7.2f}")
        if not args.check:
            save(p, fixed)

    if args.check:
        print("（--check：未写入任何文件）")
    else:
        print(f"已修复 {len(paths)} 张贴图（alpha 与轮廓未改动）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

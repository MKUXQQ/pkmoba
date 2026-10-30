"""Blaziken effect sheets V7 (m16260): the in-world VFX textures, redrawn as fire.

The skill icons stay as they are; this file only replaces the six *effect* sheets.  Each sheet is
one irregular flame silhouette with a white-hot core and a soft dithered halo, so when it is
stretched across a quad in game it reads as fire instead of a flat sticker.
"""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
EFFECTS = ROOT / "src/main/resources/assets/pkmoba/textures/effect"

OUT = (72, 18, 6, 255)
DEEP = (206, 48, 4, 255)
FIRE = (255, 112, 12, 255)
GOLD = (255, 190, 56, 255)
HOT = (255, 250, 232, 255)

S = 64
random.seed(20261030)


def tongue(d, base, tip, width, colour):
    """One curved flame tongue from a wide base to a sharp tip."""
    (bx, by), (tx, ty) = base, tip
    mx = (bx + tx) / 2 + (ty - by) * 0.16
    my = (by + ty) / 2 - (tx - bx) * 0.16
    pts = []
    for i in range(9):
        f = i / 8
        x = (1 - f) ** 2 * bx + 2 * (1 - f) * f * mx + f * f * tx
        y = (1 - f) ** 2 * by + 2 * (1 - f) * f * my + f * f * ty
        w = width * (1 - f) ** 1.4
        pts.append((x - w, y))
    for i in range(9):
        f = 1 - i / 8
        x = (1 - f) ** 2 * bx + 2 * (1 - f) * f * mx + f * f * tx
        y = (1 - f) ** 2 * by + 2 * (1 - f) * f * my + f * f * ty
        w = width * (1 - f) ** 1.4
        pts.append((x + w, y))
    d.polygon(pts, fill=colour)


def finish(shape: Image.Image) -> Image.Image:
    """Warm dithered halo behind a saturated fire body and a white-hot heart."""
    mask = shape.getchannel("A")
    halo = mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(4))
    halo = halo.point(lambda v: 0 if v < 30 else int(v * .42))
    noise = Image.effect_noise((S, S), 40).point(lambda v: 255 if v > 150 else 0)
    halo = Image.composite(halo, Image.new("L", (S, S), 0), noise)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    tint = Image.new("RGBA", (S, S), (255, 122, 34, 0))
    tint.putalpha(halo)
    out.alpha_composite(tint)
    out.alpha_composite(shape)
    heart = mask.filter(ImageFilter.MinFilter(11))
    if heart.getbbox() is not None:
        hot = Image.new("RGBA", (S, S), HOT[:3] + (0,))
        hot.putalpha(heart.point(lambda v: int(v * .7)))
        out.alpha_composite(hot)
    return out


def blank():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def slash():
    img, d = blank()
    tongue(d, (6, 52), (44, 12), 13, OUT)
    tongue(d, (8, 51), (43, 13), 10, DEEP)
    tongue(d, (10, 50), (41, 15), 7, FIRE)
    tongue(d, (22, 44), (48, 8), 6, FIRE)
    tongue(d, (14, 47), (36, 21), 3, GOLD)
    tongue(d, (18, 43), (34, 24), 2, HOT)
    return finish(img)


def crescent():
    img, d = blank()
    for width, colour in ((26, OUT), (21, DEEP), (15, FIRE), (8, GOLD)):
        d.arc([-16, -10, 80, 78], 205, 335, fill=colour, width=width)
    for i in range(7):
        a = math.radians(206 + i * 18)
        bx, by = 32 + 46 * math.cos(a), 33 + 40 * math.sin(a)
        tongue(d, (bx, by), (bx + 9 * math.cos(a - .5), by + 9 * math.sin(a - .5)), 4, FIRE)
    d.arc([-6, 0, 70, 68], 208, 332, fill=HOT, width=3)
    return finish(img)


def ring():
    img, d = blank()
    for width, colour in ((13, OUT), (10, DEEP), (6, FIRE), (2, GOLD)):
        d.ellipse([5, 19, 59, 45], outline=colour, width=width)
    for i in range(10):
        a = i * math.pi / 5
        x, y = 32 + 27 * math.cos(a), 32 + 13 * math.sin(a)
        tongue(d, (x, y), (x + 4 * math.cos(a), y + 7 * math.sin(a) + 3), 4, FIRE if i % 2 else GOLD)
    return finish(img)


def streak():
    img, d = blank()
    for y, h in ((17, 9), (30, 11), (43, 8)):
        tongue(d, (6, y), (56, y), h, OUT)
        tongue(d, (8, y), (54, y), h - 3, DEEP)
        tongue(d, (10, y), (52, y), h - 6, FIRE)
        tongue(d, (14, y), (46, y), 2, HOT)
    return finish(img)


def star():
    img, d = blank()
    # irregular explosive burst: eight tongues of different lengths
    for i in range(8):
        a = i * math.pi / 4 + .12
        length = 27 if i % 2 == 0 else 17
        tongue(d, (32, 32), (32 + length * math.cos(a), 32 + length * math.sin(a)), 7, DEEP)
    d.ellipse([22, 22, 42, 42], fill=FIRE)
    d.ellipse([25, 25, 39, 39], fill=GOLD)
    d.ellipse([27, 27, 37, 37], fill=HOT)
    return finish(img)


def pillar():
    img, d = blank()
    tongue(d, (32, 61), (32, 3), 15, OUT)
    tongue(d, (32, 60), (32, 5), 12, DEEP)
    tongue(d, (32, 59), (32, 7), 8, FIRE)
    tongue(d, (24, 56), (20, 16), 6, FIRE)
    tongue(d, (40, 56), (44, 18), 6, FIRE)
    tongue(d, (32, 52), (32, 16), 4, GOLD)
    tongue(d, (32, 46), (32, 24), 2, HOT)
    return finish(img)


def main():
    EFFECTS.mkdir(parents=True, exist_ok=True)
    for fn in (slash, crescent, ring, streak, star, pillar):
        fn().save(EFFECTS / f"bz5_{fn.__name__}.png")
    print("wrote 6 V7 fire effect sheets")


if __name__ == "__main__":
    main()

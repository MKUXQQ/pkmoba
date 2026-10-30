"""Blaziken v2 textures + icons in the Pikachu pixel language.

Chunky opaque bands, white-hot cores and crisp stepped edges - no dark rims, no soft blur.
Run from the project root:  python tools/generate_blaziken_v2.py
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
FX = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"

HOT = (255, 252, 224, 255)
CORE = (255, 232, 120, 255)
GOLD = (255, 186, 48, 255)
ORANGE = (246, 124, 26, 255)
DEEP = (198, 62, 14, 255)
DARK = (86, 24, 8, 255)


def band(img: Image.Image, y: int, thickness: int, hot: int) -> None:
    d = ImageDraw.Draw(img)
    d.rectangle([0, y, 63, y + thickness - 1], fill=ORANGE)
    d.rectangle([0, y + 1, 63, y + thickness - 2], fill=GOLD)
    inner = max(1, hot)
    mid = y + thickness // 2
    d.rectangle([0, mid - inner // 2, 63, mid + (inner + 1) // 2], fill=CORE)
    d.rectangle([0, mid - inner // 4, 63, mid + max(0, inner // 4)], fill=HOT)
    # stepped glints along the band so a stretched beam still shows pixel rhythm
    for x in range(2, 64, 9):
        d.rectangle([x, y + 2, x + 2, y + 3], fill=HOT)
        d.rectangle([x + 4, y + thickness - 4, x + 6, y + thickness - 3], fill=CORE)


def make_band() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    band(img, 20, 24, 8)
    return img


def make_arc() -> Image.Image:
    """Vertical hot-to-deep gradient; the crescent slicer maps it across the slash width."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(64):
        t = 1 - abs(y - 32) / 32.0
        if t > .92:
            c = HOT
        elif t > .7:
            c = CORE
        elif t > .45:
            c = GOLD
        elif t > .2:
            c = ORANGE
        else:
            c = DEEP
        a = 255 if t > .18 else int(255 * t / .18)
        d.rectangle([0, y, 63, y], fill=(c[0], c[1], c[2], a))
    for x in range(0, 64, 7):
        d.rectangle([x, 6, x + 2, 12], fill=HOT)
        d.rectangle([x + 3, 50, x + 5, 57], fill=CORE)
    return img


def make_orb() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for r, c in ((30, DEEP), (27, ORANGE), (22, GOLD), (15, CORE), (8, HOT), (4, (255, 255, 255, 255))):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=c)
    for i in range(10):
        a = i * math.tau / 10
        x, y = 32 + math.cos(a) * 26, 32 + math.sin(a) * 26
        d.rectangle([x - 2, y - 2, x + 1, y + 1], fill=HOT)
    return img


def make_star() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(8):
        a = i * math.tau / 8
        r0, r1 = 8, 30 if i % 2 == 0 else 21
        x0, y0 = 32 + math.cos(a) * r0, 32 + math.sin(a) * r0
        x1, y1 = 32 + math.cos(a) * r1, 32 + math.sin(a) * r1
        d.line([(x0, y0), (x1, y1)], fill=ORANGE, width=7)
        d.line([(x0, y0), (x1, y1)], fill=GOLD, width=4)
        d.line([(x0, y0), (x1, y1)], fill=HOT, width=2)
    d.ellipse([24, 24, 40, 40], fill=CORE)
    d.ellipse([28, 28, 36, 36], fill=(255, 255, 255, 255))
    return img


def make_ring() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    band(img, 26, 12, 4)
    return img


# ----------------------------------------------------------------------------- icons

def frame() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(58, 40, 22, 255))
    d.rectangle([1, 1, 30, 30], fill=(24, 18, 12, 255))
    for x0, y0, x1, y1 in ((0, 0, 31, 1), (0, 30, 31, 31), (0, 0, 1, 31), (30, 0, 31, 31)):
        d.rectangle([x0, y0, x1, y1], fill=(232, 176, 72, 255))
    d.rectangle([2, 2, 3, 3], fill=(255, 226, 148, 255))
    d.rectangle([28, 2, 29, 3], fill=(255, 226, 148, 255))
    return img, d


def _boot(angle: int, scale: int = 1) -> Image.Image:
    """A chunky boot sprite, rotated so it reads as a kick at 32 px."""
    tile = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    # shin
    d.polygon([(6, 2), (12, 2), (13, 13), (7, 13)], fill=DARK)
    d.polygon([(7, 3), (11, 3), (12, 12), (8, 12)], fill=(246, 214, 150, 255))
    # foot
    d.polygon([(4, 12), (16, 12), (20, 18), (19, 20), (4, 20)], fill=DARK)
    d.polygon([(5, 13), (16, 13), (19, 18), (18, 19), (5, 19)], fill=(255, 238, 190, 255))
    d.rectangle([5, 18, 18, 20], fill=ORANGE)
    if scale != 1:
        tile = tile.resize((24 * scale, 24 * scale), Image.NEAREST)
    return tile.rotate(angle, resample=Image.NEAREST)


def _flames(d: ImageDraw.ImageDraw, x: int, y: int, n: int, size: int, flip: int = 1) -> None:
    for i in range(n):
        h = size + (i % 2) * 2
        bx = x + i * 3 * flip
        d.polygon([(bx, y), (bx + 2 * flip, y - h), (bx + 4 * flip, y)], fill=ORANGE)
        d.polygon([(bx + flip, y), (bx + 2 * flip, y - h + 2), (bx + 3 * flip, y)], fill=GOLD)


def icon_kick() -> Image.Image:
    img, d = frame()
    _flames(d, 5, 26, 4, 9, 1)
    boot = _boot(-32)
    img.alpha_composite(boot.crop((4, 2, 22, 22)), (8, 6))
    d.rectangle([22, 6, 24, 8], fill=HOT)
    d.rectangle([25, 10, 26, 11], fill=CORE)
    return img


def icon_double_kick() -> Image.Image:
    img, d = frame()
    _flames(d, 3, 28, 3, 7, 1)
    _flames(d, 22, 26, 3, 6, -1)
    img.alpha_composite(_boot(-28).crop((4, 2, 22, 22)), (2, 5))
    img.alpha_composite(_boot(152).crop((4, 2, 22, 22)), (12, 10))
    d.rectangle([15, 4, 17, 6], fill=HOT)
    d.rectangle([25, 8, 27, 10], fill=HOT)
    return img


def icon_charge() -> Image.Image:
    img, d = frame()
    for i, y in enumerate((24, 18, 12)):
        d.rectangle([3, y, 20 - i * 2, y + 3], fill=DARK)
        d.rectangle([4, y, 19 - i * 2, y + 2], fill=ORANGE)
        d.rectangle([4, y, 15 - i * 2, y + 1], fill=GOLD)
    d.polygon([(20, 9), (27, 16), (20, 23), (22, 16)], fill=HOT)
    d.rectangle([26, 15, 29, 17], fill=CORE)
    return img


def icon_blitz() -> Image.Image:
    img, d = frame()
    d.polygon([(16, 4), (24, 12), (21, 13), (27, 22), (18, 19), (19, 28), (10, 20), (13, 19),
               (7, 12), (14, 13)], fill=DARK)
    d.polygon([(16, 6), (22, 13), (19, 14), (24, 21), (17, 18), (18, 26), (11, 20), (14, 19),
               (9, 13), (15, 14)], fill=ORANGE)
    d.polygon([(16, 9), (20, 14), (18, 15), (21, 19), (16, 17), (17, 23), (12, 19), (15, 18),
               (11, 14), (15, 15)], fill=GOLD)
    d.rectangle([15, 14, 18, 17], fill=HOT)
    return img


def main() -> None:
    FX.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "blaziken_band": make_band,
        "blaziken_arc": make_arc,
        "blaziken_orb": make_orb,
        "blaziken_star": make_star,
        "blaziken_ring": make_ring,
    }
    for name, fn in sheets.items():
        fn().save(FX / f"{name}.png")
    for name, fn in (("q", icon_kick), ("w", icon_double_kick), ("e", icon_charge), ("r", icon_blitz)):
        fn().save(ICONS / f"blaziken_{name}.png")
    print(f"wrote {len(sheets)} sheets and 4 icons")


if __name__ == "__main__":
    main()

"""Blaziken V6 art (m16229): the same moves, but the sheets are cleaned up.

The complaint was that the textures themselves looked odd and cluttered, so this pass keeps the
visual plan (flame shapes only) and redraws each sheet as one simple, symmetrical, readable shape:
a flame slash, a smooth crescent, a clean ring, three speed wedges, a four-point star and a
teardrop flame column.  Nothing is layered with spikes, teeth or stray pixels.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
EFFECTS = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"

OUT = (58, 14, 6, 255)
DEEP = (196, 58, 12, 255)
FIRE = (255, 132, 28, 255)
GOLD = (255, 200, 84, 255)
HOT = (255, 248, 226, 255)

SIZE = 64


def canvas():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def slash() -> Image.Image:
    """A single diagonal flame slash, thick body tapering to a point."""
    img, d = canvas()
    # body: one bold parallelogram rotated 45 degrees, tapering towards the top right
    pts = [(4, 44), (18, 30), (26, 22), (34, 14), (44, 8), (48, 14), (40, 24), (30, 34), (20, 44), (12, 52)]
    d.polygon(pts, fill=OUT)
    d.polygon([(8, 44), (20, 32), (28, 24), (36, 16), (43, 12), (44, 16), (36, 22), (26, 32), (16, 42), (12, 48)], fill=FIRE)
    d.polygon([(13, 43), (24, 32), (31, 25), (37, 19), (40, 17), (39, 20), (31, 27), (21, 37), (16, 44)], fill=GOLD)
    d.polygon([(17, 41), (27, 32), (33, 26), (36, 23), (34, 26), (26, 34), (20, 41)], fill=HOT)
    return img


def crescent() -> Image.Image:
    """A smooth, even crescent (the sweep of a kick)."""
    img, d = canvas()
    for width, colour in ((24, OUT), (20, DEEP), (15, FIRE), (9, GOLD), (4, HOT)):
        d.arc([-14, -14, 78, 78], 200, 340, fill=colour, width=width)
    return img


def ring() -> Image.Image:
    """A clean elliptical ring with even thickness - the spin-kick footprint."""
    img, d = canvas()
    for width, colour in ((12, OUT), (9, DEEP), (5, FIRE), (2, GOLD)):
        d.ellipse([4, 18, 60, 46], outline=colour, width=width)
    return img


def streak() -> Image.Image:
    """Three parallel speed wedges - the rush trail."""
    img, d = canvas()
    for y, w in ((18, 44), (28, 50), (38, 40)):
        d.polygon([(2, y), (2 + w, y + 4), (2 + w, y + 9), (2, y + 7)], fill=OUT)
        d.polygon([(4, y + 2), (1 + w, y + 5), (1 + w, y + 8), (4, y + 5)], fill=FIRE)
        d.polygon([(6, y + 3), (w - 2, y + 5), (w - 2, y + 7), (6, y + 5)], fill=HOT)
    return img


def star() -> Image.Image:
    """A symmetrical four-point fire star for impacts."""
    img, d = canvas()
    for scale, colour in ((1.0, OUT), (.78, FIRE), (.5, GOLD), (.24, HOT)):
        pts = [(32, 32 - 30 * scale), (32 + 9 * scale, 32 - 9 * scale), (32 + 30 * scale, 32),
               (32 + 9 * scale, 32 + 9 * scale), (32, 32 + 30 * scale), (32 - 9 * scale, 32 + 9 * scale),
               (32 - 30 * scale, 32), (32 - 9 * scale, 32 - 9 * scale)]
        d.polygon(pts, fill=colour)
    return img


def pillar() -> Image.Image:
    """A symmetrical teardrop flame column for the uppercut / blitz."""
    img, d = canvas()
    for scale, colour in ((1.0, OUT), (.82, DEEP), (.64, FIRE), (.42, GOLD), (.20, HOT)):
        h = 58 * scale
        w = 16 * scale
        pts = [(32, 32 - h / 2), (32 + w, 32 - h * .1), (32 + w * .8, 32 + h / 2),
               (32 - w * .8, 32 + h / 2), (32 - w, 32 - h * .1)]
        d.polygon(pts, fill=colour)
    return img


def frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    д = ImageDraw.Draw(img)
    д.rectangle([0, 0, 31, 31], fill=(196, 92, 22, 255))
    д.rectangle([1, 1, 30, 30], fill=(34, 16, 8, 255))
    д.rectangle([1, 1, 30, 30], outline=(238, 152, 62, 255))
    for x, y in ((1, 1), (27, 1), (1, 27), (27, 27)):
        д.rectangle([x, y, x + 3, y + 3], fill=(255, 214, 130, 255))
    return img, д


def _icon_base():
    return frame()


def icon_kick():
    img, d = _icon_base()
    d.line([(6, 26), (18, 16), (26, 8)], fill=OUT, width=8)
    d.line([(6, 26), (18, 16), (26, 8)], fill=FIRE, width=5)
    d.line([(8, 25), (18, 17), (25, 10)], fill=HOT, width=2)
    for y in (20, 25):
        d.line([(4, y), (13, y)], fill=GOLD, width=2)
    return img


def icon_uppercut():
    img, d = _icon_base()
    for scale, colour in ((1.0, OUT), (.8, DEEP), (.6, FIRE), (.36, GOLD), (.16, HOT)):
        h = 24 * scale
        w = 7 * scale
        d.polygon([(16, 30 - h), (16 + w, 30 - h * .25), (16 + w * .7, 30), (16 - w * .7, 30), (16 - w, 30 - h * .25)], fill=colour)
    return img


def icon_jump():
    img, d = _icon_base()
    for width, colour in ((6, OUT), (4, DEEP), (2, FIRE)):
        d.arc([3, 6, 29, 32], 200, 340, fill=colour, width=width)
    d.line([(3, 28), (29, 28)], fill=OUT, width=3)
    d.line([(4, 27), (28, 27)], fill=GOLD, width=1)
    d.line([(21, 8), (26, 14)], fill=HOT, width=2)
    return img


def icon_blitz():
    img, d = _icon_base()
    for y in (10, 16, 22, 28):
        d.line([(3, y), (20, y)], fill=FIRE, width=4)
        d.line([(3, y), (20, y)], fill=HOT, width=1)
    d.polygon([(18, 6), (30, 16), (18, 26)], fill=OUT)
    d.polygon([(20, 9), (27, 16), (20, 23)], fill=FIRE)
    d.polygon([(21, 12), (24, 16), (21, 20)], fill=HOT)
    return img


def main():
    EFFECTS.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    for fn in (slash, crescent, ring, streak, star, pillar):
        fn().save(EFFECTS / f"bz5_{fn.__name__}.png")
    for fn, name in ((icon_kick, "q"), (icon_uppercut, "w"), (icon_jump, "e"), (icon_blitz, "r")):
        fn().save(ICONS / f"blaziken_{name}.png")
    print("wrote 6 clean V6 blaziken sheets and 4 icons")


if __name__ == "__main__":
    main()

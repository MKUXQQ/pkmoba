"""Tyranitar V5 art: a completely different visual language from v4.

V5 idea - "sandstone monolith": everything is built out of flat, chiselled stone
shapes instead of organic blobs.  Isometric cubes for boulders, octagonal rings
for shock waves, straight lattice cracks for the ground, rectangular pillars for
the quake, and stacked rectangle dust slabs.  Palette is warm sandstone with a
single ember accent, so the whole kit reads as one material.
"""
from __future__ import annotations

import random
from pathlib import Path

from PIL import Image, ImageDraw

OUT = (34, 24, 18, 255)
STONE = (170, 142, 104, 255)
STONE_HI = (222, 196, 152, 255)
STONE_LO = (118, 94, 68, 255)
SAND = (232, 196, 130, 255)
SAND_HI = (255, 232, 186, 255)
EMBER = (236, 120, 44, 255)
PALE = (255, 246, 220, 255)

ROOT = Path(__file__).resolve().parent.parent
EFFECTS = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"


def canvas(size=64):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def save(img, name):
    img.save(EFFECTS / f"{name}.png")


# ---------------------------------------------------------------- cube
def tt5_cube():
    """Isometric sandstone cube - the core 'boulder' shape of V5."""
    img, d = canvas()
    top = [(32, 6), (58, 20), (32, 34), (6, 20)]
    left = [(6, 20), (32, 34), (32, 60), (6, 46)]
    right = [(58, 20), (32, 34), (32, 60), (58, 46)]
    d.polygon(top, fill=OUT)
    d.polygon(left, fill=OUT)
    d.polygon(right, fill=OUT)
    d.polygon([(32, 9), (55, 21), (32, 33), (9, 21)], fill=STONE_HI)
    d.polygon([(9, 22), (32, 35), (32, 57), (9, 44)], fill=STONE)
    d.polygon([(55, 22), (32, 35), (32, 57), (55, 44)], fill=STONE_LO)
    for pts in ([(16, 26), (26, 31)], [(18, 36), (28, 41)], [(38, 42), (48, 36)]):
        d.line(pts, fill=STONE_LO, width=2)
    d.rectangle([24, 14, 28, 18], fill=EMBER)
    return img


def tt5_grid():
    """Loose square grit - flat 3px pebbles, no blobs."""
    img, d = canvas()
    rnd = random.Random(5)
    for _ in range(26):
        x, y = rnd.randrange(4, 56), rnd.randrange(4, 56)
        s = rnd.choice((3, 3, 4, 5))
        d.rectangle([x, y, x + s, y + s], fill=OUT)
        d.rectangle([x, y, x + s - 1, y + s - 1], fill=rnd.choice((STONE, SAND, STONE_HI)))
    return img


def tt5_dust():
    """Stacked horizontal dust bars - reads as a sand puff, not a cloud."""
    img, d = canvas()
    for i, (y, w, a) in enumerate(((18, 44, 230), (26, 52, 210), (34, 46, 235), (42, 34, 200))):
        x0 = (64 - w) // 2 + (i % 2) * 2
        d.rectangle([x0, y, x0 + w, y + 5], fill=(SAND[0], SAND[1], SAND[2], a))
        d.rectangle([x0, y, x0 + w, y + 1], fill=(SAND_HI[0], SAND_HI[1], SAND_HI[2], a))
    return img


def tt5_maw():
    """Angular obsidian jaw: two straight bars of triangular teeth."""
    img, d = canvas()
    # upper jaw
    d.polygon([(2, 8), (62, 8), (62, 22), (2, 22)], fill=OUT)
    d.polygon([(4, 10), (60, 10), (60, 20), (4, 20)], fill=STONE_LO)
    for x in range(6, 58, 10):
        d.polygon([(x, 20), (x + 5, 32), (x + 9, 20)], fill=OUT)
        d.polygon([(x + 1, 20), (x + 5, 30), (x + 8, 20)], fill=PALE)
    # lower jaw
    d.polygon([(2, 56), (62, 56), (62, 42), (2, 42)], fill=OUT)
    d.polygon([(4, 54), (60, 54), (60, 44), (4, 44)], fill=STONE_LO)
    for x in range(10, 58, 10):
        d.polygon([(x, 44), (x + 5, 32), (x + 9, 44)], fill=OUT)
        d.polygon([(x + 1, 44), (x + 5, 34), (x + 8, 44)], fill=PALE)
    d.rectangle([28, 28, 36, 34], fill=EMBER)
    return img


def tt5_octa():
    """Flat octagonal shock ring - the V5 replacement for round rings."""
    img, d = canvas()
    pts = [(32, 4), (54, 14), (60, 32), (54, 50), (32, 60), (10, 50), (4, 32), (10, 14)]
    d.polygon(pts, fill=(0, 0, 0, 0))
    d.line(pts + [pts[0]], fill=OUT, width=7)
    d.line(pts + [pts[0]], fill=STONE_HI, width=5)
    d.line(pts + [pts[0]], fill=SAND, width=2)
    return img


def tt5_crack():
    """Straight lattice cracks (right angles only) - sand slab splitting open."""
    img, d = canvas()
    rnd = random.Random(11)
    for _ in range(9):
        x, y = rnd.randrange(6, 58), rnd.randrange(6, 58)
        for _ in range(3):
            dx, dy = rnd.choice(((1, 0), (0, 1), (-1, 0), (0, -1)))
            nx, ny = x + dx * rnd.randrange(6, 14), y + dy * rnd.randrange(6, 14)
            d.line([(x, y), (nx, ny)], fill=OUT, width=4)
            d.line([(x, y), (nx, ny)], fill=STONE_LO, width=2)
            x, y = nx, ny
    return img


def tt5_pillar():
    """A rectangular stone pillar rising out of the ground."""
    img, d = canvas()
    d.rectangle([18, 10, 46, 62], fill=OUT)
    d.rectangle([21, 13, 43, 62], fill=STONE)
    d.rectangle([21, 13, 32, 62], fill=STONE_HI)
    d.rectangle([35, 13, 43, 62], fill=STONE_LO)
    d.rectangle([18, 40, 46, 45], fill=OUT)
    d.rectangle([21, 41, 43, 44], fill=SAND)
    for y in (20, 30, 52):
        d.rectangle([24, y, 40, y + 2], fill=STONE_LO)
    d.rectangle([26, 6, 38, 12], fill=EMBER)
    return img


def tt5_spark():
    """Four-point spark + two square glints."""
    img, d = canvas()
    pts = [(32, 2), (38, 26), (62, 32), (38, 38), (32, 62), (26, 38), (2, 32), (26, 26)]
    d.polygon(pts, fill=OUT)
    pts2 = [(32, 8), (36, 28), (56, 32), (36, 36), (32, 56), (28, 36), (8, 32), (28, 28)]
    d.polygon(pts2, fill=SAND_HI)
    d.rectangle([28, 28, 36, 36], fill=PALE)
    d.rectangle([6, 8, 10, 12], fill=SAND_HI)
    d.rectangle([52, 48, 56, 52], fill=SAND_HI)
    return img


def tt5_wall():
    """Vertical dust slab used for the sandstorm walls (rectangular edges)."""
    img, d = canvas()
    for x in range(2, 62, 8):
        h = random.Random(x).randrange(26, 54)
        d.rectangle([x, 62 - h, x + 5, 62], fill=OUT)
        d.rectangle([x + 1, 63 - h, x + 4, 62], fill=SAND)
        d.rectangle([x + 1, 63 - h, x + 4, 66 - h + 3], fill=SAND_HI)
    return img


# ---------------------------------------------------------------- icons
def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(48, 34, 22, 255))
    d.rectangle([1, 1, 30, 30], fill=(30, 22, 16, 255))
    d.rectangle([1, 1, 30, 30], outline=(148, 112, 62, 255))
    for x, y in ((1, 1), (27, 1), (1, 27), (27, 27)):
        d.rectangle([x, y, x + 3, y + 3], fill=(200, 164, 96, 255))
    return img, d


def icon_q():
    img, d = icon_frame()
    d.polygon([(16, 5), (27, 11), (16, 17), (5, 11)], fill=STONE_HI)
    d.polygon([(5, 12), (16, 18), (16, 27), (5, 21)], fill=STONE)
    d.polygon([(27, 12), (16, 18), (16, 27), (27, 21)], fill=STONE_LO)
    d.rectangle([13, 24, 19, 28], fill=EMBER)
    return img


def icon_w():
    img, d = icon_frame()
    d.rectangle([4, 6, 27, 12], fill=STONE_LO)
    d.rectangle([4, 20, 27, 26], fill=STONE_LO)
    for x in range(5, 25, 5):
        d.polygon([(x, 12), (x + 2, 17), (x + 4, 12)], fill=PALE)
        d.polygon([(x, 20), (x + 2, 15), (x + 4, 20)], fill=PALE)
    d.rectangle([14, 14, 18, 18], fill=EMBER)
    return img


def icon_e():
    img, d = icon_frame()
    for i, x in enumerate((6, 12, 18)):
        d.rectangle([x, 8 + i * 3, x + 3, 26 - i * 2], fill=SAND if i % 2 == 0 else SAND_HI)
    d.rectangle([24, 12, 28, 22], fill=STONE_LO)
    return img


def icon_r():
    img, d = icon_frame()
    d.rectangle([3, 22, 28, 27], fill=(58, 42, 30, 255))
    d.line([(3, 22), (9, 17), (15, 22), (21, 16), (28, 22)], fill=SAND_HI, width=2, joint="curve")
    for x, h in ((6, 10), (12, 15), (18, 12), (23, 8)):
        d.rectangle([x, 22 - h, x + 4, 23], fill=STONE)
        d.rectangle([x, 22 - h, x + 4, 24 - h + 3], fill=STONE_HI)
    d.rectangle([14, 4, 18, 8], fill=EMBER)
    return img


def main():
    EFFECTS.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    for fn in (tt5_cube, tt5_grid, tt5_dust, tt5_maw, tt5_octa, tt5_crack, tt5_pillar, tt5_spark, tt5_wall):
        save(fn(), fn.__name__)
    for fn, name in ((icon_q, "q"), (icon_w, "w"), (icon_e, "e"), (icon_r, "r")):
        fn().save(ICONS / f"tyranitar_{name}.png")
    print("wrote 9 V5 sheets and 4 icons")


if __name__ == "__main__":
    main()

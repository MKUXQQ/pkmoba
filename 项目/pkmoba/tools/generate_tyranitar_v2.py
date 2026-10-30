"""Tyranitar pixel art 2.0 - rock / jaw / sand / spike, drawn like the Pikachu kit.

Every sheet is a 64x64 chunky pixel sprite with a dark outline so it still reads as Minecraft
pixel art when it is blown up across a boulder or a spike. Icons are 32x32 with a stone frame.
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"

OUTLINE = (34, 26, 22, 255)
STONE = (150, 146, 138, 255)
STONE_HI = (206, 202, 190, 255)
STONE_SH = (98, 94, 88, 255)
SAND = (216, 186, 128, 255)
SAND_HI = (246, 232, 190, 255)
SAND_SH = (166, 134, 86, 255)
JAW = (86, 62, 88, 255)
JAW_HI = (146, 118, 152, 255)
TOOTH = (240, 238, 226, 255)
FRAME = (168, 132, 84, 255)
FRAME_DARK = (62, 42, 26, 255)
BG = (26, 22, 18, 255)


def canvas():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def poly(d, pts, fill):
    d.polygon(pts, fill=OUTLINE)
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    inner = []
    for x, y in pts:
        dx, dy = x - cx, y - cy
        length = max(1.0, math.hypot(dx, dy))
        inner.append((x - dx / length * 1.8, y - dy / length * 1.8))
    d.polygon(inner, fill=fill)


def sheet_rock():
    """A single boulder: faceted, lit from the top-left."""
    img, d = canvas()
    poly(d, [(32, 10), (48, 18), (54, 34), (46, 50), (28, 54), (16, 42), (14, 24)], STONE)
    d.polygon([(32, 14), (44, 20), (48, 32), (32, 30)], fill=STONE_HI)
    d.polygon([(32, 30), (48, 32), (44, 46), (30, 48)], fill=STONE_SH)
    d.polygon([(22, 22), (30, 18), (28, 32), (18, 34)], fill=STONE_SH)
    d.line([(32, 30), (30, 48)], fill=OUTLINE, width=2)
    d.line([(32, 30), (48, 32)], fill=OUTLINE, width=2)
    for x, y in ((24, 27), (40, 24), (37, 41), (26, 43)):
        d.rectangle([x, y, x + 2, y + 2], fill=STONE_HI)
    return img


def sheet_dust():
    """Tan sand cloud: soft squares, no outline (it is a gas)."""
    img, d = canvas()
    rnd = random.Random(5)
    for i in range(46):
        cx = 32 + rnd.uniform(-22, 22)
        cy = 34 + rnd.uniform(-16, 14)
        r = rnd.randint(2, 7)
        shade = SAND_HI if cy < 34 else (SAND if cy < 44 else SAND_SH)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=shade[:3] + (rnd.randint(70, 150),))
    return img


def sheet_jaw():
    """Dark jaws, mouth open: upper teeth pointing down, lower teeth pointing up."""
    img, d = canvas()
    # upper jaw
    poly(d, [(8, 16), (56, 14), (60, 26), (8, 28)], JAW)
    d.polygon([(12, 18), (54, 17), (56, 21), (12, 22)], fill=JAW_HI)
    for i in range(5):
        x = 12 + i * 9
        poly(d, [(x, 26), (x + 4, 38), (x + 8, 26)], TOOTH)
    # lower jaw
    poly(d, [(10, 50), (58, 48), (60, 38), (8, 40)], JAW)
    d.polygon([(12, 46), (56, 45), (57, 41), (12, 42)], fill=JAW_HI)
    for i in range(5):
        x = 14 + i * 9
        poly(d, [(x, 40), (x + 4, 30), (x + 8, 40)], TOOTH)
    # throat glow
    d.rectangle([18, 28, 48, 38], fill=(126, 78, 132, 255))
    return img


def sheet_sand():
    """Horizontal sand streaks, used for the sandstorm swirl."""
    img, d = canvas()
    rnd = random.Random(9)
    for band in range(6):
        y0 = 6 + band * 9
        x = 2
        while x < 60:
            w = rnd.randint(8, 22)
            d.rectangle([x, y0, min(62, x + w), y0 + 2], fill=SAND[:3] + (200,))
            d.rectangle([x, y0, min(62, x + w // 2), y0], fill=SAND_HI[:3] + (230,))
            x += w + rnd.randint(2, 6)
    for i in range(14):
        x = rnd.randint(2, 60)
        y = rnd.randint(2, 60)
        d.rectangle([x, y, x + 1, y + 1], fill=SAND_HI[:3] + (220,))
    return img


def sheet_spike():
    """A stone spike pointing up."""
    img, d = canvas()
    poly(d, [(32, 4), (44, 30), (42, 58), (22, 58), (20, 30)], STONE)
    d.polygon([(32, 8), (38, 30), (36, 54), (28, 54), (26, 30)], fill=STONE_HI)
    d.polygon([(36, 30), (42, 30), (42, 56), (36, 56)], fill=STONE_SH)
    for y in (24, 34, 44):
        d.line([(24, y), (40, y - 2)], fill=OUTLINE, width=1)
    return img


def sheet_star():
    """Impact flash in warm stone dust."""
    img, d = canvas()
    for reach, colour, width in ((30, SAND, 7), (24, SAND_HI, 5), (16, (255, 250, 240, 255), 3)):
        for i in range(8):
            ang = i * math.pi / 4
            d.line([(32, 32), (32 + math.cos(ang) * reach, 32 + math.sin(ang) * reach)],
                   fill=colour[:3] + (235,), width=width)
    d.ellipse([24, 24, 40, 40], fill=(255, 252, 244, 255))
    d.rectangle([30, 30, 34, 34], fill=SAND_HI)
    return img


def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=FRAME_DARK)
    d.rectangle([1, 1, 30, 30], fill=FRAME)
    d.rectangle([3, 3, 28, 28], fill=BG)
    d.rectangle([4, 4, 27, 27], outline=FRAME_DARK)
    for x, y in ((0, 0), (29, 0), (0, 29), (29, 29)):
        d.rectangle([x, y, x + 2, y + 2], fill=SAND_HI)
    return img, d


def icon_rocks():
    img, d = icon_frame()
    poly(d, [(8, 10), (18, 8), (22, 18), (18, 24), (8, 22)], STONE)
    d.polygon([(10, 12), (17, 11), (19, 17), (10, 18)], fill=STONE_HI)
    poly(d, [(20, 16), (28, 14), (29, 23), (22, 26)], STONE_SH)
    for x, y in ((12, 24), (20, 28), (25, 20)):
        d.rectangle([x, y, x + 2, y + 2], fill=SAND)
    return img


def icon_crunch():
    img, d = icon_frame()
    for i in range(4):
        x = 5 + i * 6
        poly(d, [(x, 8), (x + 3, 15), (x + 6, 8)], TOOTH)
        poly(d, [(x, 24), (x + 3, 17), (x + 6, 24)], TOOTH)
    d.rectangle([6, 14, 26, 18], fill=JAW)
    d.rectangle([8, 15, 24, 16], fill=JAW_HI)
    return img


def icon_sandstorm():
    img, d = icon_frame()
    for i, y in enumerate((8, 13, 18, 23)):
        off = 4 + (i % 2) * 3
        d.rectangle([off, y, 27 - (i % 3) * 2, y + 2], fill=SAND)
        d.rectangle([off, y, 16, y], fill=SAND_HI)
    d.ellipse([12, 12, 20, 20], outline=SAND_HI)
    return img


def icon_stone_edge():
    img, d = icon_frame()
    for i, x in enumerate((7, 14, 21, 26)):
        h = 8 + (i % 2) * 6
        poly(d, [(x, 26 - h), (x + 5, 26), (x - 4, 26)], STONE)
        d.polygon([(x, 27 - h), (x + 2, 25), (x, 25)], fill=STONE_HI)
    d.rectangle([3, 26, 28, 28], fill=SAND_SH)
    return img


def sheet_crack():
    """A ground crack: dark fissure with glowing hot edges, reads as broken earth."""
    img, d = canvas()
    path = [(6, 58), (18, 44), (14, 34), (26, 26), (22, 14), (34, 6)]
    d.line(path, fill=OUTLINE, width=9, joint="curve")
    d.line(path, fill=(58, 40, 30, 255), width=6, joint="curve")
    d.line(path, fill=SAND_SH, width=3, joint="curve")
    d.line(path, fill=SAND, width=1, joint="curve")
    branches = [
        [(18, 44), (30, 40), (38, 28)],
        [(26, 26), (40, 30), (54, 22)],
        [(14, 34), (8, 22)],
        [(34, 6), (46, 12), (58, 8)],
        [(22, 14), (12, 8)],
    ]
    for b in branches:
        d.line(b, fill=OUTLINE, width=6, joint="curve")
        d.line(b, fill=(58, 40, 30, 255), width=4, joint="curve")
        d.line(b, fill=SAND_SH, width=2, joint="curve")
    for x, y in ((20, 46), (28, 30), (35, 12), (10, 24), (44, 26)):
        d.rectangle([x, y, x + 3, y + 3], fill=STONE)
        d.rectangle([x, y, x + 1, y + 1], fill=STONE_HI)
    return img


def icon_earthquake():
    """A wide black fissure across the ground with boulders thrown up out of it."""
    img, d = icon_frame()
    # ground
    d.rectangle([3, 19, 28, 27], fill=SAND_SH)
    d.rectangle([3, 19, 28, 20], fill=SAND)
    # fissure: a fat dark V running across the frame
    d.polygon([(3, 21), (11, 25), (16, 22), (21, 26), (28, 22), (28, 27), (3, 27)], fill=(26, 19, 14, 255))
    d.line([(3, 21), (11, 25), (16, 22), (21, 26), (28, 22)], fill=SAND_HI, width=1, joint="curve")
    # boulders jumping out
    poly(d, [(6, 10), (13, 6), (16, 13), (9, 16)], STONE)
    d.polygon([(8, 11), (12, 9), (13, 12), (9, 13)], fill=STONE_HI)
    poly(d, [(17, 8), (24, 5), (27, 12), (20, 14)], STONE_SH)
    d.polygon([(19, 9), (23, 7), (24, 11), (20, 12)], fill=STONE)
    # shock ticks
    for x, y, dx, dy in ((4, 18, -2, 2), (28, 17, 2, 2), (14, 4, 0, -2), (22, 15, 1, 1)):
        d.line([(x, y), (x + dx, y + dy)], fill=SAND_HI, width=2)
    return img


def sheet_plate():
    """A lifted earth slab: stone top with a hot crack and a chipped edge, used for the
    earthquake's bouncing-ground illusion (the real blocks never move)."""
    img, d = canvas()
    d.rectangle([4, 10, 59, 52], fill=OUTLINE)
    d.rectangle([6, 12, 57, 50], fill=STONE_SH)
    d.rectangle([6, 12, 57, 22], fill=STONE)
    d.rectangle([6, 12, 57, 17], fill=STONE_HI)
    # chipped bottom edge so it reads as a broken slab, not a box
    for x in range(8, 56, 7):
        d.rectangle([x, 50, x + 4, 53], fill=OUTLINE)
        d.rectangle([x + 1, 50, x + 3, 52], fill=STONE_SH)
    # glowing crack across the top face
    crack = [(8, 20), (18, 27), (26, 22), (34, 30), (44, 24), (54, 32)]
    d.line(crack, fill=(46, 30, 22, 255), width=4, joint="curve")
    d.line(crack, fill=SAND_HI, width=2, joint="curve")
    d.line([(26, 22), (24, 36), (18, 46)], fill=(46, 30, 22, 255), width=3)
    d.line([(26, 22), (24, 36), (18, 46)], fill=SAND_SH, width=1)
    for x, y in ((14, 16), (33, 18), (48, 38), (24, 42)):
        d.rectangle([x, y, x + 2, y + 2], fill=STONE_HI)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "tt_rock": sheet_rock(),
        "tt_dust": sheet_dust(),
        "tt_jaw": sheet_jaw(),
        "tt_sand": sheet_sand(),
        "tt_spike": sheet_spike(),
        "tt_star": sheet_star(),
        "tt_crack": sheet_crack(),
        "tt_plate": sheet_plate(),
    }
    for name, img in sheets.items():
        img.save(EFFECT / f"{name}.png")
    icons = {
        "tyranitar_q": icon_rocks(),
        "tyranitar_w": icon_crunch(),
        "tyranitar_e": icon_sandstorm(),
        "tyranitar_r": icon_earthquake(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    print(f"wrote {len(sheets)} sheets and {len(icons)} icons")


if __name__ == "__main__":
    main()

"""Tyranitar v3 art: chunky angular pixel rocks, sand jets and a fault-line quake - no round glows."""
from pathlib import Path
from PIL import Image, ImageDraw
import math, random

OUT = (18, 14, 12, 255)
DEEP = (58, 44, 34, 255)
MID = (110, 88, 66, 255)
LIT = (168, 140, 104, 255)
HOT = (222, 196, 150, 255)
SAND = (214, 178, 120, 255)
SAND_D = (150, 116, 72, 255)
SAND_L = (246, 224, 176, 255)
EMBER = (236, 132, 56, 255)

TEX = Path('src/main/resources/assets/pkmoba/textures/effect')
ICON = Path('src/main/resources/assets/pkmoba/textures/gui/skills')


def canvas():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def jag(d, pts, outer, inner, width=3):
    d.line(pts, fill=OUT, width=width + 2, joint='curve')
    d.line(pts, fill=inner, width=width, joint='curve')


def rock(d, cx, cy, r, seed):
    rnd = random.Random(seed)
    pts = []
    for i in range(7):
        a = i / 7 * math.tau
        rr = r * (0.72 + rnd.random() * 0.45)
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
    d.polygon(pts, fill=OUT)
    inner = [(cx + (x - cx) * 0.8, cy + (y - cy) * 0.8) for x, y in pts]
    d.polygon(inner, fill=MID)
    # lit top-left facet + cracks
    top = [(inner[0][0], inner[0][1]), (inner[1][0], inner[1][1]), (cx, cy)]
    d.polygon(top, fill=LIT)
    d.line([(cx, cy), (inner[3][0], inner[3][1])], fill=DEEP, width=2)
    d.line([(inner[5][0], inner[5][1]), (cx - r * .2, cy + r * .3)], fill=DEEP, width=2)
    d.point((cx - r * .25, cy - r * .2), fill=HOT)


def sheet_boulder():
    img, d = canvas()
    rock(d, 32, 34, 24, 1)
    rock(d, 14, 46, 9, 2)
    rock(d, 50, 44, 7, 3)
    return img


def sheet_chips():
    img, d = canvas()
    rnd = random.Random(7)
    for i in range(9):
        x = rnd.randint(10, 54); y = rnd.randint(12, 54); r = rnd.randint(3, 7)
        d.polygon([(x, y - r), (x + r, y), (x, y + r), (x - r, y)], fill=OUT)
        d.polygon([(x, y - r + 1), (x + r - 1, y), (x, y + r - 1), (x - r + 1, y)], fill=MID if i % 2 else LIT)
    return img


def sheet_jet():
    img, d = canvas()
    for i in range(7):
        x = 4 + i * 9
        jag(d, [(x, 62), (x + 3, 40), (x - 2, 26), (x + 2, 8)], 3, SAND if i % 2 else SAND_D, 2)
    rnd = random.Random(3)
    for i in range(12):
        x = rnd.randint(2, 56); y = rnd.randint(6, 56)
        d.rectangle([x, y, x + 2, y + 2], fill=SAND_L)
    return img


def sheet_grit():
    img, d = canvas()
    rnd = random.Random(11)
    for i in range(22):
        x = rnd.randint(4, 58); y = rnd.randint(4, 58); r = rnd.randint(2, 5)
        d.rectangle([x, y, x + r, y + r], fill=rnd.choice([SAND, SAND_D, SAND_L]))
    return img


def sheet_fang():
    img, d = canvas()
    # upper and lower rows of fangs biting toward the middle
    for i in range(5):
        x = 6 + i * 11
        d.polygon([(x, 8), (x + 8, 8), (x + 4, 30)], fill=OUT)
        d.polygon([(x + 1, 10), (x + 7, 10), (x + 4, 27)], fill=HOT)
        d.polygon([(x, 56), (x + 8, 56), (x + 4, 34)], fill=OUT)
        d.polygon([(x + 1, 54), (x + 7, 54), (x + 4, 37)], fill=HOT)
    d.rectangle([2, 30, 62, 34], fill=DEEP)
    return img


def sheet_fault():
    img, d = canvas()
    rnd = random.Random(5)
    for k in range(3):
        pts = [(2, 32)]
        x = 2
        while x < 62:
            x += rnd.randint(6, 12)
            pts.append((x, 32 + rnd.randint(-14, 14)))
        jag(d, pts, 4, OUT if k == 0 else DEEP, 3 if k == 0 else 2)
    for i in range(10):
        x = rnd.randint(6, 58); y = rnd.randint(24, 48)
        d.rectangle([x, y, x + 3, y + 3], fill=LIT)
    return img


def sheet_slab():
    img, d = canvas()
    pts = [(6, 10), (58, 8), (60, 40), (34, 56), (8, 44)]
    d.polygon(pts, fill=OUT)
    inner = [(x + (32 - x) * 0.04, y + (30 - y) * 0.04) for x, y in pts]
    d.polygon(inner, fill=MID)
    d.polygon([(6, 10), (58, 8), (58, 18), (6, 22)], fill=LIT)
    jag(d, [(12, 40), (26, 30), (40, 42), (54, 28)], 2, DEEP, 2)
    return img


def sheet_shock():
    img, d = canvas()
    for r, w, col in ((26, 4, OUT), (26, 2, SAND_L), (18, 3, OUT), (18, 1, SAND)):
        d.ellipse([32 - r, 32 - r * .55, 32 + r, 32 + r * .55], outline=col, width=w)
    return img


def sheet_star3():
    img, d = canvas()
    for i in range(8):
        a = i / 8 * math.tau
        x, y = 32 + math.cos(a) * 27, 32 + math.sin(a) * 27
        d.line([(32, 32), (x, y)], fill=OUT, width=6)
        d.line([(32, 32), (x, y)], fill=(SAND_L if i % 2 else EMBER), width=2)
    d.ellipse([25, 25, 39, 39], fill=OUT)
    d.ellipse([27, 27, 37, 37], fill=HOT)
    return img


def frame():
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([1, 1, 30, 30], radius=3, fill=OUT)
    d.rounded_rectangle([3, 3, 28, 28], radius=2, fill=(38, 28, 22, 255))
    for x, y in ((2, 2), (27, 2), (2, 27), (27, 27)):
        d.rectangle([x, y, x + 2, y + 2], fill=HOT)
    return img, d


def icon_q():
    img, d = frame()
    d.line([(8, 18), (14, 10), (18, 14), (24, 8)], fill=DEEP, width=3)
    rock(d, 13, 20, 7, 4)
    rock(d, 23, 18, 5, 5)
    d.line([(22, 8), (23, 13), (21, 15)], fill=EMBER, width=2)
    return img


def icon_w():
    img, d = frame()
    for i in range(3):
        x = 6 + i * 7
        d.polygon([(x, 8), (x + 5, 8), (x + 2, 18)], fill=OUT)
        d.polygon([(x + 1, 9), (x + 4, 9), (x + 2, 16)], fill=HOT)
        d.polygon([(x, 24), (x + 5, 24), (x + 2, 14)], fill=OUT)
        d.polygon([(x + 1, 23), (x + 4, 23), (x + 2, 16)], fill=(236, 214, 176, 255))
    d.rectangle([4, 15, 27, 17], fill=DEEP)
    return img


def icon_e():
    img, d = frame()
    for i in range(4):
        x = 5 + i * 6
        jag(d, [(x, 6), (x + 3, 14), (x - 1, 20), (x + 2, 27)], 3, SAND if i % 2 else SAND_D, 2)
    for x, y in ((24, 8), (18, 24), (26, 20)):
        d.rectangle([x, y, x + 2, y + 2], fill=SAND_L)
    return img


def icon_r():
    img, d = frame()
    jag(d, [(3, 20), (10, 26), (16, 20), (22, 27), (28, 20)], 5, (26, 19, 14, 255), 3)
    jag(d, [(3, 20), (10, 26), (16, 20), (22, 27), (28, 20)], 2, SAND_L, 1)
    rock(d, 11, 12, 6, 8)
    rock(d, 22, 13, 5, 9)
    for x, y in ((4, 16), (28, 15)):
        d.line([(x, y), (x + (2 if x < 16 else -2), y - 4)], fill=EMBER, width=2)
    return img


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    ICON.mkdir(parents=True, exist_ok=True)
    sheets = {
        'tt_boulder': sheet_boulder(),
        'tt_chips': sheet_chips(),
        'tt_jet': sheet_jet(),
        'tt_grit': sheet_grit(),
        'tt_fang': sheet_fang(),
        'tt_fault': sheet_fault(),
        'tt_slab': sheet_slab(),
        'tt_shock': sheet_shock(),
        'tt_star3': sheet_star3(),
    }
    for name, img in sheets.items():
        img.save(TEX / f'{name}.png')
    icons = {'tyranitar_q': icon_q(), 'tyranitar_w': icon_w(), 'tyranitar_e': icon_e(), 'tyranitar_r': icon_r()}
    for name, img in icons.items():
        img.save(ICON / f'{name}.png')
    print('wrote', len(sheets), 'sheets and', len(icons), 'icons')


if __name__ == '__main__':
    main()

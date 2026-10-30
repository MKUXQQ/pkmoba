"""v4 art pass for Tyranitar (rock/sand) and Blaziken (fire).

Both sets are redrawn from scratch with a bolder palette, thicker silhouettes and hard dark
outlines so they read at gameplay distance instead of looking like noise. File names are unchanged,
so the client FX code picks them up without edits.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import math
import random

TEX = Path('src/main/resources/assets/pkmoba/textures/effect')
ICON = Path('src/main/resources/assets/pkmoba/textures/gui/skills')

# --- Tyranitar 4.0 palette: obsidian + lit stone + amber sand + ember accent
TT_OUT = (22, 18, 22, 255)
TT_DARK = (86, 66, 48, 255)
TT_STONE = (146, 120, 92, 255)
TT_LIT = (206, 178, 138, 255)
TT_SAND = (238, 200, 124, 255)
TT_SAND_D = (172, 132, 74, 255)
TT_HOT = (255, 244, 206, 255)
TT_EMBER = (255, 138, 58, 255)

# --- Blaziken 2.0 palette: crimson body + molten orange + gold + white hot
BL_OUT = (74, 30, 12, 255)
BL_DEEP = (255, 146, 48, 255)
BL_ORANGE = (255, 186, 64, 255)
BL_GOLD = (255, 232, 138, 255)
BL_HOT = (255, 248, 222, 255)


def canvas(w=64, h=64):
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def poly(d, pts, fill, outline=None, width=3):
    if outline:
        d.polygon(pts, fill=outline)
        cx = sum(p[0] for p in pts) / len(pts)
        cy = sum(p[1] for p in pts) / len(pts)
        inner = []
        for x, y in pts:
            dx, dy = x - cx, y - cy
            length = max(1.0, math.hypot(dx, dy))
            inner.append((x - dx / length * width, y - dy / length * width))
        d.polygon(inner, fill=fill)
    else:
        d.polygon(pts, fill=fill)


def rock(d, cx, cy, r, seed, palette=(TT_STONE, TT_LIT, TT_DARK)):
    rnd = random.Random(seed)
    n = 8
    pts = []
    for i in range(n):
        a = i / n * math.tau + rnd.uniform(-.12, .12)
        rr = r * (0.78 + rnd.random() * 0.34)
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
    d.polygon(pts, fill=TT_OUT)
    inner = [(cx + (x - cx) * .82, cy + (y - cy) * .82) for x, y in pts]
    d.polygon(inner, fill=palette[0])
    # lit facet top-left
    d.polygon([inner[0], inner[1], (cx, cy), inner[7]], fill=palette[1])
    # cracks
    d.line([(cx, cy), inner[4]], fill=palette[2], width=2)
    d.line([inner[2], (cx - r * .15, cy + r * .42)], fill=palette[2], width=2)
    d.point((cx - r * .3, cy - r * .25), fill=TT_HOT)


def tt_boulder():
    img, d = canvas()
    rock(d, 32, 33, 25, 3)
    rock(d, 12, 48, 9, 5)
    rock(d, 51, 46, 8, 7)
    for x, y in ((22, 18), (40, 26), (30, 44)):
        d.rectangle([x, y, x + 2, y + 2], fill=TT_EMBER)
    return img


def tt_chips():
    img, d = canvas()
    rnd = random.Random(9)
    for i in range(7):
        x = rnd.randint(12, 52)
        y = rnd.randint(12, 52)
        r = rnd.randint(5, 9)
        poly(d, [(x, y - r), (x + r, y), (x, y + r), (x - r, y)],
             TT_LIT if i % 2 else TT_STONE, TT_OUT, 2)
    return img


def tt_jet():
    img, d = canvas()
    for i in range(6):
        x = 5 + i * 10
        pts = [(x, 62), (x + 3, 44), (x - 2, 30), (x + 2, 8)]
        d.line(pts, fill=TT_OUT, width=7, joint='curve')
        d.line(pts, fill=TT_SAND if i % 2 else TT_SAND_D, width=4, joint='curve')
        d.line([(x + 1, 54), (x + 2, 40)], fill=TT_HOT, width=1)
    rnd = random.Random(4)
    for i in range(10):
        x = rnd.randint(2, 56)
        y = rnd.randint(8, 54)
        d.rectangle([x, y, x + 3, y + 3], fill=TT_HOT)
    return img


def tt_grit():
    img, d = canvas()
    rnd = random.Random(13)
    for i in range(6):
        cx = rnd.randint(12, 52)
        cy = rnd.randint(12, 52)
        for j in range(4):
            x = cx + rnd.randint(-7, 7)
            y = cy + rnd.randint(-7, 7)
            r = rnd.randint(2, 4)
            d.rectangle([x, y, x + r, y + r], fill=rnd.choice([TT_SAND, TT_SAND_D, TT_HOT]))
    return img


def tt_fang():
    img, d = canvas()
    for i in range(4):
        x = 5 + i * 14
        poly(d, [(x, 6), (x + 11, 6), (x + 5, 31)], TT_HOT, TT_OUT, 2)
        poly(d, [(x, 58), (x + 11, 58), (x + 5, 33)], (244, 228, 200, 255), TT_OUT, 2)
    d.rectangle([1, 30, 63, 34], fill=TT_SAND_D)
    d.rectangle([1, 31, 63, 32], fill=TT_HOT)
    return img


def tt_fault():
    img, d = canvas()
    rnd = random.Random(6)
    for k in range(3):
        pts = [(1, 30 + k * 3)]
        x = 1
        while x < 62:
            x += rnd.randint(7, 12)
            pts.append((x, 30 + k * 3 + rnd.randint(-13, 13)))
        d.line(pts, fill=TT_OUT, width=8 if k == 0 else 5, joint='curve')
        d.line(pts, fill=TT_SAND if k == 0 else TT_SAND_D, width=5 if k == 0 else 2, joint='curve')
    rnd = random.Random(8)
    for i in range(9):
        x = rnd.randint(6, 56)
        y = rnd.randint(22, 48)
        d.rectangle([x, y, x + 4, y + 4], fill=TT_LIT)
    return img


def tt_slab():
    img, d = canvas()
    pts = [(5, 12), (57, 9), (61, 38), (34, 57), (7, 45)]
    d.polygon(pts, fill=TT_OUT)
    inner = [(x + (32 - x) * .06, y + (32 - y) * .06) for x, y in pts]
    d.polygon(inner, fill=TT_STONE)
    d.polygon([(6, 13), (56, 10), (57, 20), (7, 24)], fill=TT_LIT)
    d.line([(13, 42), (27, 30), (41, 43), (55, 27)], fill=TT_OUT, width=3, joint='curve')
    d.line([(15, 43), (27, 33), (41, 45)], fill=TT_SAND, width=1, joint='curve')
    return img


def tt_shock():
    img, d = canvas()
    for r, w, col in ((27, 5, TT_OUT), (27, 3, TT_SAND), (27, 1, TT_HOT), (18, 4, TT_OUT), (18, 2, TT_SAND_D)):
        d.ellipse([32 - r, 32 - r * .52, 32 + r, 32 + r * .52], outline=col, width=w)
    return img


def tt_star3():
    img, d = canvas()
    for i in range(6):
        a = i / 6 * math.tau + .2
        x, y = 32 + math.cos(a) * 28, 32 + math.sin(a) * 28
        d.line([(32, 32), (x, y)], fill=TT_OUT, width=9)
        d.line([(32, 32), (x, y)], fill=TT_SAND if i % 2 else TT_EMBER, width=4)
    d.ellipse([23, 23, 41, 41], fill=TT_OUT)
    d.ellipse([26, 26, 38, 38], fill=TT_HOT)
    return img


# --- Blaziken 2.0
def tongue(d, x, y, w, h, colours):
    """Vertical flame tongue in three bands, used by almost every Blaziken sheet."""
    deep, mid, hot = colours
    poly(d, [(x, y), (x + w, y), (x + w * .5, y - h)], mid, BL_OUT, 2)
    poly(d, [(x + w * .22, y), (x + w * .78, y), (x + w * .5, y - h * .72)], hot, None)


def bl_foot():
    img, d = canvas()
    tongue(d, 8, 52, 12, 22, (BL_DEEP, BL_ORANGE, BL_GOLD))
    tongue(d, 18, 54, 10, 16, (BL_DEEP, BL_ORANGE, BL_HOT))
    # shin + foot, kicking right
    poly(d, [(30, 12), (43, 12), (43, 42), (30, 42)], (250, 228, 182, 255), BL_OUT, 3)
    poly(d, [(43, 32), (58, 32), (58, 46), (43, 46)], (250, 228, 182, 255), BL_OUT, 3)
    for x in (45, 50, 55):
        poly(d, [(x, 46), (x + 4, 46), (x + 2, 53)], BL_GOLD, BL_OUT, 1)
    tongue(d, 34, 24, 7, 12, (BL_DEEP, BL_ORANGE, BL_HOT))
    return img


def bl_wall():
    img, d = canvas()
    d.rectangle([0, 40, 63, 60], fill=BL_DEEP)
    for i in range(9):
        tongue(d, 2 + i * 7, 44, 6, 20 + (i % 3) * 5, (BL_DEEP, BL_ORANGE, BL_GOLD))
    d.rectangle([0, 58, 63, 63], fill=BL_OUT)
    return img


def bl_flame():
    img, d = canvas()
    tongue(d, 18, 58, 28, 50, (BL_DEEP, BL_ORANGE, BL_GOLD))
    tongue(d, 26, 58, 12, 32, (BL_ORANGE, BL_GOLD, BL_HOT))
    return img


def bl_band():
    img, d = canvas()
    d.rectangle([0, 22, 63, 42], fill=BL_DEEP)
    d.rectangle([0, 26, 63, 38], fill=BL_ORANGE)
    d.rectangle([0, 30, 63, 34], fill=BL_HOT)
    for i in range(8):
        d.rectangle([2 + i * 8, 20, 5 + i * 8, 44], fill=BL_OUT)
    return img


def bl_arc():
    img, d = canvas()
    for k, col in ((0, BL_OUT), (4, BL_ORANGE), (8, BL_GOLD), (11, BL_HOT)):
        d.arc([6 - k, 6 - k, 70 + k, 70 + k], 200, 340, fill=col, width=4)
    return img


def bl_star():
    img, d = canvas()
    for i in range(8):
        a = i / 8 * math.tau
        x, y = 32 + math.cos(a) * 28, 32 + math.sin(a) * 28
        d.line([(32, 32), (x, y)], fill=BL_OUT, width=8)
        d.line([(32, 32), (x, y)], fill=BL_GOLD if i % 2 else BL_ORANGE, width=3)
    d.ellipse([24, 24, 40, 40], fill=BL_OUT)
    d.ellipse([27, 27, 37, 37], fill=BL_HOT)
    return img


def icon_frame(accent):
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([1, 1, 30, 30], radius=3, fill=(26, 16, 12, 255))
    d.rounded_rectangle([2, 2, 29, 29], radius=3, outline=accent, width=1)
    for x, y in ((2, 2), (27, 2), (2, 27), (27, 27)):
        d.rectangle([x, y, x + 2, y + 2], fill=accent)
    return img, d


def bl_icons():
    out = {}
    img, d = icon_frame(BL_GOLD)
    d.polygon([(6, 26), (10, 10), (15, 18), (20, 8), (26, 26)], fill=BL_OUT)
    d.polygon([(8, 25), (11, 13), (15, 20), (20, 11), (24, 25)], fill=BL_ORANGE)
    d.polygon([(13, 24), (15, 18), (18, 24)], fill=BL_HOT)
    out['blaziken_q'] = img

    img, d = icon_frame(BL_GOLD)
    for x in (7, 17):
        d.polygon([(x, 8), (x + 7, 8), (x + 3, 26)], fill=BL_OUT)
        d.polygon([(x + 1, 9), (x + 6, 9), (x + 3, 23)], fill=BL_GOLD)
    d.rectangle([5, 15, 26, 18], fill=BL_DEEP)
    out['blaziken_w'] = img

    img, d = icon_frame(BL_GOLD)
    for x in (20, 22, 24):
        d.line([(x, 25), (x - 8, 12)], fill=BL_OUT, width=4)
        d.line([(x, 24), (x - 7, 13)], fill=BL_ORANGE, width=2)
    d.polygon([(4, 26), (11, 8), (17, 26)], fill=BL_GOLD)
    out['blaziken_e'] = img

    img, d = icon_frame(BL_GOLD)
    d.polygon([(8, 26), (12, 6), (17, 20), (22, 5), (26, 26)], fill=BL_OUT)
    d.polygon([(10, 25), (13, 10), (17, 22), (21, 9), (24, 25)], fill=BL_ORANGE)
    d.polygon([(15, 24), (17, 16), (19, 24)], fill=BL_HOT)
    out['blaziken_r'] = img
    return out


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    ICON.mkdir(parents=True, exist_ok=True)
    tt = {
        'tt_boulder': tt_boulder(),
        'tt_chips': tt_chips(),
        'tt_jet': tt_jet(),
        'tt_grit': tt_grit(),
        'tt_fang': tt_fang(),
        'tt_fault': tt_fault(),
        'tt_slab': tt_slab(),
        'tt_shock': tt_shock(),
        'tt_star3': tt_star3(),
    }
    bl = {
        'blaziken_foot': bl_foot(),
        'blaziken_wall': bl_wall(),
        'blaziken_flame': bl_flame(),
        'blaziken_band': bl_band(),
        'blaziken_arc': bl_arc(),
        'blaziken_star': bl_star(),
    }
    for name, img in {**tt, **bl}.items():
        img.save(TEX / f'{name}.png')
    for name, img in bl_icons().items():
        img.save(ICON / f'{name}.png')
    print('wrote', len(tt) + len(bl), 'sheets and 4 icons')


if __name__ == '__main__':
    main()

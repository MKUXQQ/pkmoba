import random
"""Alolan Ninetales v3 kit art - Freeze-Dry / Aurora Veil / Blizzard / Tera Blast.

Same pixel language as the other species: 64x64 chunky sheets with a white-hot core, a
saturated mid tone and a pale rim, plus 32x32 skill icons that share one ice-blue frame.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

OUT = Path("src/main/resources/assets/pkmoba")
EFFECT = OUT / "textures/effect"
ICONS = OUT / "textures/gui/skills"

PALE = (238, 252, 255, 255)
WHITE = (255, 255, 255, 255)
ICE = (150, 226, 252, 255)
CYAN = (86, 186, 240, 255)
DEEP = (40, 96, 168, 255)
NAVY = (20, 40, 82, 255)
MINT = (128, 240, 216, 255)
PINK = (250, 156, 226, 255)
VIOLET = (176, 150, 250, 255)
FRAME = (206, 232, 250, 255)
FRAME_DARK = (36, 58, 96, 255)
BG = (12, 24, 44, 255)


def canvas(size=64):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def glowify(img, amount=0.5, colour=(150, 226, 252, 255)):
    halo = Image.new("RGBA", img.size, colour)
    alpha = img.getchannel("A").filter(ImageFilter.GaussianBlur(4)).point(lambda v: int(v * amount))
    halo.putalpha(alpha)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(halo)
    out.alpha_composite(img)
    return out


def shard(d, points, outer=DEEP, mid=ICE, core=PALE):
    """A crystal splinter: dark rim, tinted body, bright centre line."""
    d.polygon(points, fill=outer)
    cx = sum(p[0] for p in points) / len(points)
    cy = sum(p[1] for p in points) / len(points)
    inner = [(x + (cx - x) * .22, y + (cy - y) * .22) for x, y in points]
    d.polygon(inner, fill=mid)
    core_pts = [(x + (cx - x) * .55, y + (cy - y) * .55) for x, y in points]
    d.polygon(core_pts, fill=core)


def sheet_freeze_dry():
    """A frozen droplet: water trapped inside a splintering ice crystal."""
    img, d = canvas()
    # ice splinters radiating out of the droplet
    for ang, length in ((0, 26), (60, 22), (120, 24), (180, 18), (240, 22), (300, 20)):
        import math
        a = math.radians(ang)
        tip = (32 + math.cos(a) * length, 32 + math.sin(a) * length)
        mid = (32 + math.cos(a) * length * .45, 32 + math.sin(a) * length * .45)
        perp = (math.cos(a + 1.57) * 4, math.sin(a + 1.57) * 4)
        shard(d, [(32 + perp[0], 32 + perp[1]), tip, (32 - perp[0], 32 - perp[1])])
    # droplet core, frozen over
    d.ellipse([18, 18, 46, 46], fill=DEEP)
    d.ellipse([21, 21, 43, 43], fill=ICE)
    d.ellipse([25, 25, 39, 39], fill=PALE)
    d.ellipse([28, 28, 36, 36], fill=WHITE)
    # frozen crackle across the droplet
    for pts in ([(22, 30), (32, 26), (42, 32)], [(26, 40), (34, 34), (30, 24)]):
        d.line(pts, fill=CYAN, width=2)
    return glowify(img, .8)


def sheet_veil():
    """An aurora curtain: vertical ribbons in three colours that tile sideways."""
    img, d = canvas()
    bands = [(8, MINT, .30), (22, CYAN, .55), (34, VIOLET, .45), (46, PINK, .40), (56, MINT, .25)]
    for x, colour, alpha in bands:
        for y in range(0, 64, 2):
            wave = 3 * ((y // 2) % 4) / 3.0
            xx = int(x + wave)
            shade = colour[:3] + (int(190 * alpha + 90),)
            d.rectangle([xx, y, xx + 7, y + 1], fill=shade)
    for x in (18, 40, 52):
        d.line([(x, 2), (x + 2, 30), (x - 1, 62)], fill=(255, 255, 255, 210), width=2)
    return glowify(img, .55, (170, 220, 255, 255))


def sheet_blizzard():
    """A snow vortex band: concentric swirls plus drifting flakes."""
    img, d = canvas()
    for r, colour, width in ((28, DEEP, 5), (22, CYAN, 5), (16, ICE, 4), (10, PALE, 3)):
        d.arc([32 - r, 32 - r, 32 + r, 32 + r], 200, 520, fill=colour, width=width)
    for r in (26, 18, 11):
        d.arc([32 - r, 32 - r, 32 + r, 32 + r], 20, 150, fill=PALE, width=2)
    import math
    for i in range(14):
        a = i * (math.pi * 2 / 14)
        rr = 12 + (i % 3) * 8
        x, y = 32 + math.cos(a) * rr, 32 + math.sin(a) * rr
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=WHITE if i % 2 else PALE)
    return glowify(img, .7)


def sheet_tera():
    """A tera crystal: cut gemstone with rainbow edge glints."""
    img, d = canvas()
    facets = [
        [(32, 4), (52, 22), (44, 54), (20, 54), (12, 22)],
    ]
    shard(d, facets[0], NAVY, CYAN, PALE)
    # inner facets
    d.polygon([(32, 10), (47, 24), (40, 48), (24, 48), (18, 24)], fill=(70, 150, 220, 255))
    d.polygon([(32, 16), (42, 26), (38, 42), (26, 42), (22, 26)], fill=ICE)
    d.polygon([(32, 24), (37, 30), (35, 38), (29, 38), (27, 30)], fill=WHITE)
    for start, colour in (((32, 4), PINK), ((52, 22), VIOLET), ((44, 54), MINT), ((12, 22), CYAN)):
        d.rectangle([start[0] - 1, start[1] - 1, start[0] + 1, start[1] + 1], fill=colour)
    d.line([(32, 6), (32, 50)], fill=(255, 255, 255, 160), width=1)
    return glowify(img, .75, (200, 220, 255, 255))


def icon_frame(size=32):
    img, d = canvas(size)
    d.rectangle([0, 0, size - 1, size - 1], fill=FRAME)
    d.rectangle([1, 1, size - 2, size - 2], fill=BG)
    d.rectangle([0, 0, 2, 2], fill=WHITE)
    d.rectangle([size - 3, 0, size - 1, 2], fill=WHITE)
    d.rectangle([0, size - 3, 2, size - 1], fill=WHITE)
    d.rectangle([size - 3, size - 3, size - 1, size - 1], fill=WHITE)
    return img, d


def icon_freeze_dry():
    img, d = icon_frame()
    for ang in (0, 90, 180, 270):
        import math
        a = math.radians(ang)
        shard(d, [(16 + math.cos(a + 1.57) * 3, 16 + math.sin(a + 1.57) * 3),
                  (16 + math.cos(a) * 13, 16 + math.sin(a) * 13),
                  (16 + math.cos(a - 1.57) * 3, 16 + math.sin(a - 1.57) * 3)])
    d.ellipse([8, 8, 24, 24], fill=DEEP)
    d.ellipse([10, 10, 22, 22], fill=ICE)
    d.ellipse([13, 13, 19, 19], fill=WHITE)
    d.line([(10, 16), (16, 13), (22, 17)], fill=CYAN, width=1)
    return img


def icon_veil():
    img, d = icon_frame()
    # a raised barrier panel: aurora waves behind a bright shield outline
    for i, colour in enumerate((MINT, CYAN, VIOLET, PINK)):
        y = 7 + i * 5
        d.rectangle([4, y, 27, y + 3], fill=colour[:3] + (200,))
        d.rectangle([4, y, 27, y], fill=PALE)
    d.polygon([(16, 2), (28, 7), (26, 22), (16, 30), (6, 22), (4, 7)], outline=WHITE)
    d.polygon([(16, 4), (26, 8), (24, 21), (16, 28), (8, 21), (6, 8)], outline=ICE)
    d.rectangle([15, 8, 17, 12], fill=WHITE)
    d.rectangle([14, 14, 18, 16], fill=PALE)
    return img


def icon_blizzard():
    img, d = icon_frame()
    import math
    for i in range(6):
        a = math.radians(i * 60)
        d.line([(16, 16), (16 + math.cos(a) * 12, 16 + math.sin(a) * 12)], fill=DEEP, width=3)
        d.line([(16, 16), (16 + math.cos(a) * 11, 16 + math.sin(a) * 11)], fill=PALE, width=1)
        bx, by = 16 + math.cos(a) * 7, 16 + math.sin(a) * 7
        d.line([(bx, by), (bx + math.cos(a + .9) * 3, by + math.sin(a + .9) * 3)], fill=CYAN, width=1)
        d.line([(bx, by), (bx + math.cos(a - .9) * 3, by + math.sin(a - .9) * 3)], fill=CYAN, width=1)
    d.ellipse([13, 13, 19, 19], fill=WHITE)
    return img


def icon_tera():
    img, d = icon_frame()
    shard(d, [(16, 3), (27, 12), (24, 27), (8, 27), (5, 12)], NAVY, CYAN, PALE)
    d.polygon([(16, 8), (23, 14), (21, 24), (11, 24), (9, 14)], fill=ICE)
    d.polygon([(16, 13), (20, 16), (18, 22), (14, 22), (12, 16)], fill=WHITE)
    for x, y, c in ((16, 3, PINK), (27, 12, VIOLET), (24, 27, MINT), (5, 12, CYAN)):
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=c)
    return img


def sheet_funnel():
    """Slanted wind streaks: wraps into a convincing tornado wall, no plate look."""
    img, d = canvas()
    rnd = random.Random(11)
    for band in range(6):
        x = -6 + band * 11
        pts = []
        y = 64
        while y >= -4:
            pts.append((x + 8 - (64 - y) * 0.32, y))
            y -= 8
        d.line(pts, fill=CYAN[:3] + (150,), width=4)
        d.line(pts, fill=PALE[:3] + (210,), width=2)
        x += rnd.randint(1, 3)
    for i in range(10):
        x = rnd.randint(2, 60); y = rnd.randint(2, 60)
        d.rectangle([x, y, x + 1, y + 1], fill=PALE[:3] + (190,))
    for i in range(4):
        x = 6 + i * 15
        d.line([(x, 60), (x + 6, 34)], fill=ICE[:3] + (120,), width=1)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "nt_funnel": sheet_funnel(),
        "nt_fdry": sheet_freeze_dry(),
        "nt_veilc": sheet_veil(),
        "nt_blizzard": sheet_blizzard(),
        "nt_tera": sheet_tera(),
    }
    for name, img in sheets.items():
        img.save(EFFECT / f"{name}.png")
    icons = {
        "alolan_ninetales_q": icon_freeze_dry(),
        "alolan_ninetales_w": icon_veil(),
        "alolan_ninetales_e": icon_blizzard(),
        "alolan_ninetales_r": icon_tera(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    print(f"wrote {len(sheets)} sheets and {len(icons)} icons")


if __name__ == "__main__":
    main()

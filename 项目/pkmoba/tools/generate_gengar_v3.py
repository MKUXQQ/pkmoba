"""Gengar v3 textures - same drawing language as the Pikachu pass.

Only six sheets are produced, because Gengar now reuses the exact primitives the Pikachu set uses
(continuous ribbon, hot-core band, pixel star, orb, ground splat, bullseye). Colours are the deep
violet / lavender family with a white-hot core, and no shape carries a dark outline.
"""

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

FONT = None
S = 64
ROOT = Path(__file__).resolve().parent.parent
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"

VIOLET = (150, 92, 236, 255)
LILAC = (196, 156, 255, 255)
PALE = (236, 220, 255, 255)
WHITE = (255, 252, 255, 255)
DEEP = (74, 40, 128, 255)
PLUM = (108, 60, 170, 255)
SLIME = (172, 108, 236, 255)
SLIME_LIGHT = (214, 176, 255, 255)


def canvas():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img, glow=0.28, tint=(180, 120, 255, 255), gain=1.18, add=18):
    """Light bloom baked in, then quantise so the sheet keeps a pixel-art feel."""
    wide = img.filter(ImageFilter.GaussianBlur(2.6))
    tight = img.filter(ImageFilter.GaussianBlur(0.9))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    glow_layer = wide.copy()
    px = glow_layer.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (int(r * glow * tint[0] / 255), int(g * glow * tint[1] / 255),
                            int(b * glow * tint[2] / 255), int(a * glow))
    out.alpha_composite(glow_layer)
    tight_layer = tight.copy()
    px = tight_layer.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (int(r * .55), int(g * .55), int(b * .55), int(a * .55))
    out.alpha_composite(tight_layer)
    out.alpha_composite(img)
    px = out.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            r = min(255, int(r * gain) + add)
            g = min(255, int(g * gain) + add)
            b = min(255, int(b * gain) + add)
            a = min(255, int(a * 1.15) + 14)
            if a > 96:
                r, g, b = min(255, r + 12), min(255, g + 10), min(255, b + 16)
            px[x, y] = (r, g, b, a)
    small = out.resize((32, 32), Image.LANCZOS)
    return small.resize((S, S), Image.NEAREST)


def band(d, h, tint, hot):
    """Horizontal energy band: hot core line, tinted body, pixel glints drifting on the edges."""
    top = (S - h) // 2
    d.rectangle([0, top, S, top + h - 1], fill=tint)
    d.rectangle([0, top + 2, S, top + h - 3], fill=tuple(min(255, c + 22) for c in tint[:3]) + (255,))
    core = max(2, h // 4)
    cy = S // 2 - core // 2
    d.rectangle([0, cy, S, cy + core - 1], fill=hot)
    d.rectangle([0, cy + core // 3, S, cy + core - 1 - core // 3], fill=WHITE)
    rnd = random.Random(7)
    for i in range(0, S, 8):
        y = top + rnd.randint(0, 2)
        d.rectangle([i + rnd.randint(0, 2), y, i + rnd.randint(2, 4), y + 1], fill=PALE)
        y2 = top + h - 2 - rnd.randint(0, 2)
        d.rectangle([i + rnd.randint(0, 2), y2, i + rnd.randint(2, 4), y2 + 1], fill=PALE)


def bolt_shape(d, pts, thickness, tint, core_tint):
    d.line(pts, fill=tint, width=thickness, joint="curve")
    d.line(pts, fill=tuple(min(255, c + 26) for c in tint[:3]) + (255,), width=max(3, thickness - 4),
           joint="curve")
    d.line(pts, fill=core_tint, width=max(2, thickness // 4), joint="curve")


def star(d, cx, cy, radius, tint):
    for i in range(8):
        ang = i * math.pi / 4
        length = radius if i % 2 == 0 else radius * .6
        x, y = cx + math.cos(ang) * length, cy + math.sin(ang) * length
        w = 3 if i % 2 == 0 else 2
        d.line([(cx, cy), (x, y)], fill=tint, width=w)
        d.line([(cx, cy), (x, y)], fill=WHITE, width=1)
    d.ellipse([cx - radius * .3, cy - radius * .3, cx + radius * .3, cy + radius * .3], fill=WHITE)


def tex_thunder():
    img, d = canvas()
    band(d, 26, (146, 92, 232), (240, 224, 255))
    return finish(img, .3, (170, 120, 255, 255))


def tex_band():
    img, d = canvas()
    band(d, 34, (128, 74, 214), (232, 210, 255))
    return finish(img, .32, (160, 110, 255, 255))


def tex_bolt():
    img, d = canvas()
    pts = [(2, 40), (12, 30), (22, 44), (32, 22), (44, 36), (54, 18), (62, 26)]
    bolt_shape(d, pts, 13, (158, 100, 240), (246, 232, 255))
    return finish(img, .3, (180, 128, 255, 255))


def tex_star():
    img, d = canvas()
    star(d, 32, 32, 26, (168, 110, 250))
    for i in range(4):
        ang = i * math.pi / 2 + math.pi / 4
        d.rectangle([32 + math.cos(ang) * 27 - 2, 32 + math.sin(ang) * 27 - 2,
                     32 + math.cos(ang) * 27 + 2, 32 + math.sin(ang) * 27 + 2], fill=PALE)
    return finish(img, .32, (190, 140, 255, 255))


def tex_orb():
    img, d = canvas()
    for r, colour in ((29, DEEP), (25, PLUM), (20, VIOLET), (15, (176, 122, 248, 255)),
                      (10, LILAC), (5, PALE), (2, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    for i in range(10):
        ang = i * math.tau / 10
        x, y = 32 + math.cos(ang) * 22, 32 + math.sin(ang) * 22
        d.rectangle([x - 2, y - 2, x + 1, y + 1], fill=(226, 206, 255, 200))
    return finish(img, .26, (180, 130, 255, 255))


def tex_splat():
    img, d = canvas()
    rnd = random.Random(3)
    d.ellipse([8, 12, 56, 52], fill=PLUM)
    d.ellipse([11, 15, 53, 49], fill=VIOLET)
    d.ellipse([17, 20, 47, 44], fill=(172, 118, 246, 255))
    for i in range(9):
        ang = rnd.random() * math.tau
        dist = 20 + rnd.random() * 10
        x, y = 32 + math.cos(ang) * dist, 32 + math.sin(ang) * dist
        r = rnd.randint(2, 5)
        d.ellipse([x - r, y - r, x + r, y + r], fill=LILAC)
    for i in range(6):
        x = 12 + i * 8
        y = 44 + rnd.randint(0, 6)
        d.rectangle([x, y, x + 2, y + rnd.randint(2, 5)], fill=(196, 148, 255, 220))
    return finish(img, .26, (170, 120, 255, 255))


def tex_hypno():
    img, d = canvas()
    for r, colour in ((30, DEEP), (26, PLUM), (22, VIOLET), (17, (176, 124, 246, 255)),
                      (12, LILAC), (7, PALE), (3, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], outline=colour, width=2 if r < 30 else 1)
    return finish(img, .3, (185, 135, 255, 255))


def tex_ring():
    """Clean expanding shock ring: hot rim, dark inner gap, pixel notches - no glow bleed."""
    img, d = canvas()
    for r, w, colour in ((30, 5, DEEP), (29, 4, VIOLET), (29, 2, LILAC), (29, 1, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], outline=colour, width=w)
    for i in range(12):
        ang = i * math.tau / 12
        x, y = 32 + math.cos(ang) * 29, 32 + math.sin(ang) * 29
        d.rectangle([x - 2, y - 2, x + 1, y + 1], fill=PALE)
    return finish(img, .22, (190, 140, 255, 255))


def tex_moon():
    """Big crescent for 食梦: solid violet moon with a clean pixel edge."""
    img, d = canvas()
    d.ellipse([6, 6, 58, 58], fill=DEEP)
    d.ellipse([8, 8, 56, 56], fill=VIOLET)
    d.ellipse([18, 4, 64, 50], fill=(0, 0, 0, 0))
    # bright inner rim where the light side is
    for i in range(3):
        d.arc([8 + i, 8 + i, 56 - i, 56 - i], start=100, end=260, fill=(214, 186, 255, 255), width=2)
    for i in range(5):
        ang = math.radians(120 + i * 22)
        x, y = 32 + math.cos(ang) * 21, 32 + math.sin(ang) * 21
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=WHITE)
    return finish(img, .24, (185, 140, 255, 255))


def tex_swirl():
    """Dark vortex disc: three arms spiralling into a bright core (no particle dots)."""
    img, d = canvas()
    d.ellipse([4, 4, 60, 60], fill=(28, 20, 44, 255))
    for arm in range(3):
        pts = []
        for step in range(26):
            t = step / 25.0
            ang = arm * math.tau / 3 + t * math.tau * 1.15
            rad = 27 * (1 - t * .82)
            pts.append((32 + math.cos(ang) * rad, 32 + math.sin(ang) * rad))
        d.line(pts, fill=PLUM, width=6, joint="curve")
        d.line(pts, fill=LILAC, width=2, joint="curve")
    d.ellipse([26, 26, 38, 38], fill=PALE)
    d.ellipse([29, 29, 35, 35], fill=WHITE)
    return finish(img, .26, (180, 130, 255, 255))


def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(26, 22, 40, 255))
    d.rectangle([1, 1, 30, 30], outline=(124, 88, 196, 255), width=1)
    d.rectangle([2, 2, 29, 29], outline=(64, 46, 108, 255), width=1)
    for cx, cy in ((3, 3), (28, 3), (3, 28), (28, 28)):
        d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=(206, 176, 255, 255))
    return img, d


def icon_from(tex_name, scale=1.0):
    src = Image.open(EFFECT / f"{tex_name}.png").convert("RGBA").resize((26, 26), Image.LANCZOS)
    if scale != 1.0:
        inner = max(6, int(26 * scale))
        src = src.resize((inner, inner), Image.LANCZOS)
    img, _ = icon_frame()
    pos = ((32 - src.width) // 2, (32 - src.height) // 2)
    dframe = ImageDraw.Draw(img)
    # dark rounded well so the glyph reads on the frame
    dframe.ellipse([2, 2, 29, 29], fill=(22, 18, 36, 255))
    img.alpha_composite(src, pos)
    return img


def icon_shadow_ball():
    img, d = icon_frame()
    d.ellipse([3, 3, 28, 28], fill=(30, 22, 48, 255))
    for r, colour in ((12, DEEP), (10, PLUM), (7, VIOLET), (4, LILAC), (1, WHITE)):
        d.ellipse([16 - r, 16 - r, 16 + r, 16 + r], fill=colour)
    for i in range(6):
        ang = i * math.tau / 6
        x, y = 16 + math.cos(ang) * 13, 16 + math.sin(ang) * 13
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=(226, 206, 255, 220))
    return img


def icon_sludge():
    img, d = icon_frame()
    d.ellipse([3, 3, 28, 28], fill=(30, 22, 48, 255))
    d.ellipse([8, 8, 24, 24], fill=VIOLET)
    d.ellipse([10, 10, 22, 22], fill=(178, 124, 246, 255))
    d.ellipse([13, 11, 18, 15], fill=LILAC)
    d.rectangle([15, 23, 17, 27], fill=(200, 156, 255, 255))
    d.rectangle([11, 22, 13, 25], fill=(188, 140, 255, 255))
    d.rectangle([20, 22, 22, 26], fill=(188, 140, 255, 255))
    return img


def icon_hypno():
    img, d = icon_frame()
    d.ellipse([3, 3, 28, 28], fill=(30, 22, 48, 255))
    for r, colour in ((12, VIOLET), (9, LILAC), (6, PALE), (2, WHITE)):
        d.ellipse([16 - r, 16 - r, 16 + r, 16 + r], outline=colour, width=1 if r > 9 else 2)
    return img


def icon_dream():
    img, d = icon_frame()
    d.ellipse([3, 3, 28, 28], fill=(34, 24, 54, 255))
    # big crescent: two overlapping discs leave a bright moon sliver
    d.ellipse([8, 6, 26, 24], fill=(206, 170, 255, 255))
    d.ellipse([12, 5, 29, 22], fill=(34, 24, 54, 255))
    for x, y in ((22, 8), (25, 13), (20, 17), (24, 20)):
        d.rectangle([x, y, x + 2, y + 2], fill=LILAC)
    d.rectangle([25, 6, 26, 7], fill=WHITE)
    d.rectangle([15, 20, 16, 21], fill=PALE)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "gengar_orb": tex_orb,
        "gengar_ring": tex_ring,
        "gengar_star": tex_star,
        "gengar_splat": tex_splat,
        "gengar_hypno": tex_hypno,
        "gengar_moon": tex_moon,
        "gengar_swirl": tex_swirl,
    }
    for name, fn in sheets.items():
        fn().save(EFFECT / f"{name}.png")
    icons = {
        "gengar_q": icon_shadow_ball(),
        "gengar_w": icon_sludge(),
        "gengar_e": icon_hypno(),
        "gengar_r": icon_dream(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    # preview strip for review
    preview = Image.new("RGBA", (7 * 96 + 8, 96 + 8), (18, 16, 26, 255))
    for i, name in enumerate(sheets):
        cut = Image.open(EFFECT / f"{name}.png").convert("RGBA").resize((96, 96), Image.NEAREST)
        tinted = Image.new("RGBA", cut.size, (40, 34, 58, 255))
        tinted.alpha_composite(cut)
        preview.alpha_composite(tinted, (8 + i * 96, 8))
    preview.save(ROOT.parent / "gengar_v3_sheets.png")
    icon_preview = Image.new("RGBA", (4 * 80, 80), (18, 16, 26, 255))
    for i, name in enumerate(icons):
        icon_preview.alpha_composite(icons[name].resize((64, 64), Image.NEAREST), (8 + i * 80, 8))
    icon_preview.save(ROOT.parent / "gengar_v3_icons.png")
    print("wrote", len(sheets), "sheets and", len(icons), "icons")


if __name__ == "__main__":
    main()

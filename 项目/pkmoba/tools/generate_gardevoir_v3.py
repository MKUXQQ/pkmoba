"""Gardevoir v3 art: shockwave arc, moon sphere surface, verdict burst and the new icons.

Run from the mod folder: python tools/generate_gardevoir_v3.py
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"
SIZE = 64

PINK = (255, 150, 214, 255)
ROSE = (238, 92, 176, 255)
LILAC = (216, 178, 255, 255)
WHITE = (255, 255, 255, 255)
PALE = (255, 236, 250, 255)
MOON_WHITE = (240, 244, 255, 255)
MOON_SHADE = (206, 214, 240, 255)
MOON_DARK = (176, 186, 222, 255)


def canvas():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def glow(img, distance=2):
    from PIL import ImageFilter
    halo = img.filter(ImageFilter.GaussianBlur(distance * 2.0))
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(halo)
    out.alpha_composite(img)
    return out


def wave_arc():
    """A wide crescent of psychic pressure with rune ticks along it."""
    img, d = canvas()
    for offset, width, colour in ((10, 9, (255, 120, 200, 150)), (0, 7, PINK), (-4, 4, PALE),
                                  (-6, 2, WHITE)):
        d.arc([6 - offset, 4 - offset, 58 + offset, 56 + offset], 200, 340, fill=colour, width=width)
    for i in range(9):
        a = math.radians(205 + i * 16)
        x = 32 + math.cos(a) * 27
        y = 30 + math.sin(a) * 27
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=WHITE if i % 2 == 0 else PALE)
    return glow(img, 2)


def moon_sphere():
    """Opaque full tile: wrapped around a 3D sphere for the falling moon."""
    img = Image.new("RGBA", (SIZE, SIZE), MOON_WHITE)
    d = ImageDraw.Draw(img)
    for y in range(SIZE):
        shade = 1 - abs(y - 32) / 90.0
        colour = (int(MOON_WHITE[0] * shade + MOON_SHADE[0] * (1 - shade)),
                  int(MOON_WHITE[1] * shade + MOON_SHADE[1] * (1 - shade)),
                  int(MOON_WHITE[2] * shade + MOON_SHADE[2] * (1 - shade)), 255)
        d.line([(0, y), (SIZE, y)], fill=colour)
    craters = ((10, 14, 5), (28, 8, 3), (44, 18, 6), (18, 32, 4), (36, 30, 5),
               (52, 40, 4), (24, 46, 6), (44, 52, 3), (8, 52, 4), (56, 28, 3))
    for cx, cy, r in craters:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=MOON_SHADE)
        d.ellipse([cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2], fill=MOON_DARK)
        d.ellipse([cx - r, cy - r, cx + r - 1, cy + r - 1], outline=MOON_WHITE, width=1)
    return img


def verdict_burst():
    img, d = canvas()
    d.ellipse([27, 27, 37, 37], fill=WHITE)
    for i in range(12):
        a = i * math.tau / 12
        length = 31 if i % 3 == 0 else 22
        d.line([(32, 32), (32 + math.cos(a) * length, 32 + math.sin(a) * length)],
               fill=(255, 214, 246, 235), width=3 if i % 3 == 0 else 2)
        d.line([(32, 32), (32 + math.cos(a) * (length - 10), 32 + math.sin(a) * (length - 10))],
               fill=WHITE, width=1)
    for i in range(12):
        a = i * math.tau / 12 + .26
        d.rectangle([32 + math.cos(a) * 34 - 1, 32 + math.sin(a) * 34 - 1,
                     32 + math.cos(a) * 34 + 1, 32 + math.sin(a) * 34 + 1], fill=PALE)
    return glow(img, 1)


def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(28, 20, 40, 255), outline=(236, 200, 130, 255))
    d.rectangle([1, 1, 30, 1], fill=(120, 96, 70, 255))
    d.rectangle([1, 1, 1, 30], fill=(120, 96, 70, 255))
    d.rectangle([30, 1, 30, 30], fill=(86, 66, 48, 255))
    d.rectangle([1, 30, 30, 30], fill=(86, 66, 48, 255))
    for x, y in ((0, 0), (27, 0), (0, 27), (27, 27)):
        d.rectangle([x, y, x + 4, y + 4], fill=(236, 200, 130, 255))
    d.rectangle([1, 1, 26, 26], fill=(40, 28, 56, 255))
    return img, d


def icon_ripple():
    img, d = icon_frame()
    for r, col in ((13, (255, 130, 205, 255)), (9, (255, 190, 240, 255)), (5, WHITE)):
        d.arc([16 - r, 16 - r, 16 + r, 16 + r], 130, 230, fill=col, width=2)
    for x, y in ((4, 20), (26, 20), (7, 7), (23, 7)):
        d.rectangle([x, y, x + 1, y + 1], fill=PALE)
    return img


def icon_moonfall():
    img, d = icon_frame()
    d.ellipse([4, 3, 22, 21], fill=(224, 232, 250, 255))
    for cx, cy, r in ((9, 8, 2), (17, 7, 2), (14, 16, 2)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(186, 196, 226, 255))
    for y in (22, 25, 28):
        d.line([(8, y), (14, y + 3)], fill=(255, 170, 225, 255), width=1)
    d.polygon([(24, 24), (29, 29), (19, 29)], fill=(255, 150, 214, 255))
    d.rectangle([24, 12, 25, 20], fill=PALE)
    return img


def icon_phantom():
    img, d = icon_frame()
    d.arc([5, 5, 26, 26], 0, 360, fill=(255, 190, 240, 255), width=2)
    d.line([(9, 22), (9, 10)], fill=WHITE, width=2)
    d.line([(22, 10), (22, 22)], fill=(255, 214, 246, 255), width=2)
    for y in (10, 16, 22):
        d.line([(11, y), (20, y)], fill=PALE, width=1)
    d.rectangle([15, 13, 16, 19], fill=WHITE)
    for x, y in ((4, 4), (25, 4), (4, 25), (25, 25)):
        d.rectangle([x, y, x + 2, y + 2], fill=(255, 226, 250, 255))
    return img


def icon_verdict():
    img, d = icon_frame()
    for i, (x, y) in enumerate(((7, 6), (16, 3), (23, 8), (12, 13), (21, 14))):
        d.line([(x, y), (x, y + 8)], fill=(255, 214, 246, 220), width=1)
        d.polygon([(x, y + 8), (x + 2, y + 11), (x - 2, y + 11)], fill=WHITE)
    d.ellipse([9, 20, 25, 29], outline=(255, 190, 240, 255), width=1)
    d.rectangle([16, 21, 17, 25], fill=WHITE)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "gardevoir_wave": wave_arc(),
        "gardevoir_moonsphere": moon_sphere(),
        "gardevoir_burst": verdict_burst(),
    }
    for name, img in sheets.items():
        img.save(EFFECT / f"{name}.png")
    icons = {
        "gardevoir_q": icon_ripple(),
        "gardevoir_w": icon_moonfall(),
        "gardevoir_e": icon_phantom(),
        "gardevoir_r": icon_verdict(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    print("wrote", len(sheets), "sheets and", len(icons), "icons")


if __name__ == "__main__":
    main()

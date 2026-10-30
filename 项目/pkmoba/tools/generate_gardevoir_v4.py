"""Gardevoir v4 art: chunky Minecraft pixel sheets (drawn at 32px, upscaled with NEAREST).

Every shape is drawn with hard pixel steps instead of smooth arcs, so when the sheet is stretched
over a big billboard the texels stay visibly blocky - the "MC" look the user kept asking for.

Run from the mod folder: python tools/generate_gardevoir_v4.py
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"
S = 32

PINK = (255, 154, 214, 255)
ROSE = (232, 86, 172, 255)
LILAC = (214, 176, 255, 255)
DEEP = (128, 68, 170, 255)
WHITE = (255, 255, 255, 255)
PALE = (255, 232, 250, 255)
MOON_LIGHT = (242, 246, 255, 255)
MOON_MID = (206, 216, 244, 255)
MOON_DARK = (168, 180, 220, 255)
MOON_SHADE = (132, 146, 194, 255)


def canvas():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def thick_ring(d, cx, cy, radius, width, colour):
    """A ring built from 2px blocks so its edge is stepped, not antialiased."""
    steps = 96
    for i in range(steps):
        angle = i * math.tau / steps
        for offset in range(width):
            r = radius - offset
            x = int(round(cx + math.cos(angle) * r))
            y = int(round(cy + math.sin(angle) * r))
            d.rectangle([x, y, x + 1, y + 1], fill=colour)


def sheet_grow(img):
    return img.resize((S * 2, S * 2), Image.NEAREST)


def ring_sheet():
    img, d = canvas()
    thick_ring(d, 16, 16, 14, 3, ROSE)
    thick_ring(d, 16, 16, 10, 2, PINK)
    thick_ring(d, 16, 16, 6, 1, PALE)
    for i in range(12):
        a = i * math.tau / 12
        x = int(round(16 + math.cos(a) * 14))
        y = int(round(16 + math.sin(a) * 14))
        d.rectangle([x - 1, y - 1, x, y], fill=WHITE)
    return sheet_grow(img)


def shock_sheet():
    """Wide stepped shockwave ring: stretched horizontally it reads as a slam wave."""
    img, d = canvas()
    thick_ring(d, 16, 16, 15, 2, (255, 226, 250, 210))
    thick_ring(d, 16, 16, 12, 2, PINK)
    thick_ring(d, 16, 16, 9, 1, WHITE)
    for i in range(10):
        a = i * math.tau / 10
        x = int(round(16 + math.cos(a) * 15))
        y = int(round(16 + math.sin(a) * 15))
        d.rectangle([x, y, x + 1, y + 1], fill=WHITE)
    return sheet_grow(img)


def moon_sheet():
    """Opaque moon surface for the 3D sphere: blocky maria and craters."""
    img = Image.new("RGBA", (S, S), MOON_LIGHT)
    d = ImageDraw.Draw(img)
    for y in range(S):
        band = MOON_MID if 6 <= y <= 26 else MOON_LIGHT
        d.line([(0, y), (S, y)], fill=band)
    blobs = [(7, 9, 6), (22, 7, 5), (15, 15, 7), (26, 20, 5), (8, 23, 6), (19, 27, 4)]
    for cx, cy, r in blobs:
        for dy in range(-r // 2, r // 2):
            for dx in range(-r, r + 1):
                if (dx / (r * 1.2)) ** 2 + (dy / (r * .7)) ** 2 <= 1:
                    x, y = cx + dx, cy + dy
                    if 0 <= x < S and 0 <= y < S:
                        d.point((x, y), fill=MOON_MID)
    craters = ((9, 11, 3), (21, 9, 2), (14, 18, 4), (24, 22, 3), (7, 22, 2), (17, 26, 2))
    for cx, cy, r in craters:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=MOON_SHADE)
        d.ellipse([cx - r, cy - r, cx + r - 1, cy + r - 1], fill=MOON_DARK)
        d.rectangle([cx - r, cy - r, cx + r - 1, cy - r], fill=MOON_MID)
    return sheet_grow(img)


def rays_sheet():
    """Vertical moonlight shaft band with stepped edges."""
    img, d = canvas()
    for x0, width, colour in ((10, 3, (255, 226, 250, 120)), (14, 4, (255, 244, 255, 190)),
                              (18, 3, (255, 226, 250, 120))):
        d.rectangle([x0, 0, x0 + width, S], fill=colour)
    for y in range(0, S, 4):
        d.rectangle([14, y, 14, y + 1], fill=WHITE)
        d.rectangle([15, y + 2, 15, y + 2], fill=WHITE)
    return sheet_grow(img)


def rune_sheet():
    img, d = canvas()
    thick_ring(d, 16, 16, 14, 1, PINK)
    thick_ring(d, 16, 16, 10, 1, ROSE)
    for i in range(8):
        a = i * math.tau / 8
        x = int(round(16 + math.cos(a) * 12))
        y = int(round(16 + math.sin(a) * 12))
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=PALE)
    for i in range(4):
        a = i * math.tau / 4 + math.pi / 4
        d.line([(16 + math.cos(a) * 4, 16 + math.sin(a) * 4),
                (16 + math.cos(a) * 9, 16 + math.sin(a) * 9)], fill=WHITE, width=1)
    d.rectangle([15, 15, 16, 16], fill=WHITE)
    return sheet_grow(img)


def clock_sheet():
    """Future Sight: a stepped clock face that the prediction hangs under."""
    img, d = canvas()
    thick_ring(d, 16, 16, 14, 2, LILAC)
    thick_ring(d, 16, 16, 11, 1, PINK)
    for i in range(12):
        a = i * math.tau / 12
        r0, r1 = (10, 13) if i % 3 == 0 else (11, 13)
        d.line([(16 + math.cos(a) * r0, 16 + math.sin(a) * r0),
                (16 + math.cos(a) * r1, 16 + math.sin(a) * r1)], fill=WHITE, width=1)
    d.line([(16, 16), (16, 7)], fill=WHITE, width=2)      # minute hand
    d.line([(16, 16), (22, 19)], fill=PALE, width=1)       # hour hand
    d.rectangle([15, 15, 17, 17], fill=WHITE)
    return sheet_grow(img)


def orb_sheet():
    img, d = canvas()
    for y in range(S):
        for x in range(S):
            dx, dy = (x - 16) / 15.0, (y - 16) / 15.0
            r = dx * dx + dy * dy
            if r <= 1:
                if r < .18:
                    colour = WHITE
                elif r < .45:
                    colour = PALE
                elif r < .75:
                    colour = PINK
                else:
                    colour = ROSE
                d.point((x, y), fill=colour)
    for i in range(10):
        a = i * math.tau / 10
        x = int(round(16 + math.cos(a) * 15))
        y = int(round(16 + math.sin(a) * 15))
        d.rectangle([x, y, x + 1, y + 1], fill=WHITE)
    return sheet_grow(img)


def debris_sheet():
    img, d = canvas()
    d.polygon([(16, 2), (25, 14), (20, 29), (10, 24), (7, 12)], fill=DEEP)
    d.polygon([(16, 5), (22, 14), (19, 26), (12, 22), (10, 13)], fill=PINK)
    d.polygon([(16, 8), (20, 15), (17, 22), (13, 19)], fill=PALE)
    d.rectangle([15, 10, 17, 14], fill=WHITE)
    return sheet_grow(img)


def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(26, 18, 38, 255), outline=(236, 200, 130, 255))
    d.rectangle([1, 1, 30, 1], fill=(120, 96, 70, 255))
    d.rectangle([1, 1, 1, 30], fill=(120, 96, 70, 255))
    d.rectangle([30, 1, 30, 30], fill=(86, 66, 48, 255))
    d.rectangle([1, 30, 30, 30], fill=(86, 66, 48, 255))
    for x, y in ((0, 0), (27, 0), (0, 27), (27, 27)):
        d.rectangle([x, y, x + 4, y + 4], fill=(236, 200, 130, 255))
    d.rectangle([1, 1, 26, 26], fill=(38, 26, 54, 255))
    return img, d


def icon_psychic():
    img, d = icon_frame()
    thick_ring(d, 16, 16, 12, 2, PINK)
    thick_ring(d, 16, 16, 7, 1, PALE)
    d.rectangle([15, 15, 17, 17], fill=WHITE)
    for x, y in ((3, 6), (26, 6), (3, 24), (26, 24)):
        d.rectangle([x, y, x + 2, y + 2], fill=LILAC)
    return img


def icon_moon():
    img, d = icon_frame()
    for y in range(6, 27):
        for x in range(5, 26):
            dx, dy = (x - 15) / 10.5, (y - 16) / 10.5
            if dx * dx + dy * dy <= 1:
                d.point((x, y), fill=MOON_LIGHT if dx + dy < .2 else MOON_MID)
    for cx, cy, r in ((12, 12, 2), (19, 14, 2), (14, 21, 2)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=MOON_DARK)
    for x in (2, 28):
        d.line([(x, 7), (x, 25)], fill=(255, 234, 252, 200), width=1)
    return img


def icon_teleport():
    img, d = icon_frame()
    thick_ring(d, 16, 22, 9, 1, ROSE)
    for y in range(6, 22):
        d.line([(14, y), (17, y)], fill=(255, 236, 252, 220))
    d.rectangle([14, 4, 17, 7], fill=WHITE)
    d.rectangle([7, 12, 9, 12], fill=PALE)
    d.rectangle([23, 12, 25, 12], fill=PALE)
    return img


def icon_future():
    img, d = icon_frame()
    thick_ring(d, 16, 18, 11, 1, LILAC)
    for i in range(12):
        a = i * math.tau / 12
        d.rectangle([int(round(16 + math.cos(a) * 9)), int(round(18 + math.sin(a) * 9)),
                     int(round(16 + math.cos(a) * 9)) + 1, int(round(18 + math.sin(a) * 9)) + 1],
                    fill=WHITE)
    d.line([(16, 18), (16, 11)], fill=WHITE, width=1)
    d.line([(16, 18), (20, 20)], fill=PALE, width=1)
    for x, y in ((5, 4), (26, 5)):
        d.rectangle([x, y, x + 2, y + 2], fill=PINK)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "gardevoir_ring": ring_sheet(),
        "gardevoir_shock": shock_sheet(),
        "gardevoir_moon": moon_sheet(),
        "gardevoir_rays": rays_sheet(),
        "gardevoir_rune": rune_sheet(),
        "gardevoir_clock": clock_sheet(),
        "gardevoir_orb": orb_sheet(),
        "gardevoir_debris": debris_sheet(),
    }
    for name, img in sheets.items():
        img.save(EFFECT / f"{name}.png")
    icons = {
        "gardevoir_q": icon_psychic(),
        "gardevoir_w": icon_moon(),
        "gardevoir_e": icon_teleport(),
        "gardevoir_r": icon_future(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    print("wrote", len(sheets), "sheets and", len(icons), "icons")


if __name__ == "__main__":
    main()

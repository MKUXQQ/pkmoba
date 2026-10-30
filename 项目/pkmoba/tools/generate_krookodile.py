# -*- coding: utf-8 -*-
"""Krookodile's pixel art: sand, desert brown, dark red and a little amber.

Everything is 64x64, hard pixels, transparent background and a 1px dark outline drawn by hand
(``outline`` looks at the alpha channel and dilates one pixel), so the sheets sit next to the
existing species without looking like a different art pass.

Run from the repository root:
    python 项目/pkmoba/tools/generate_krookodile.py
"""
from __future__ import annotations

import math
import random
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba"
EFFECT = ASSETS / "textures/effect"
ICONS = ASSETS / "textures/gui/skills"
PORTRAITS = ASSETS / "textures/gui/pokemon"

SAND = (200, 162, 74, 255)
SAND_LIGHT = (232, 198, 116, 255)
SAND_DARK = (140, 106, 48, 255)
BROWN = (139, 107, 61, 255)
BROWN_DARK = (88, 64, 34, 255)
RED = (142, 42, 38, 255)
RED_LIGHT = (196, 74, 62, 255)
AMBER = (232, 178, 60, 255)
CREAM = (245, 235, 208, 255)
INK = (26, 20, 16, 255)


def canvas(size=64) -> Image.Image:
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def poly(draw, points, fill):
    draw.polygon([(round(x, 2), round(y, 2)) for x, y in points], fill=fill)


def outline(img: Image.Image, color=INK) -> Image.Image:
    """Dilate the alpha mask by one pixel and paint the new ring dark."""
    alpha = img.split()[3]
    grown = alpha.filter(ImageFilter.MaxFilter(3))
    ring = Image.new("RGBA", img.size, color)
    ring.putalpha(grown.point(lambda v: 255 if v > 8 else 0))
    ring.alpha_composite(img)
    # Re-mask so the outline never bleeds into the transparent corner.
    ring.putalpha(alpha.point(lambda v: 255 if v > 8 else 0).filter(ImageFilter.MaxFilter(3)))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(ring)
    out.putalpha(alpha.point(lambda v: 255 if v > 8 else 0).filter(ImageFilter.MaxFilter(3)))
    out.alpha_composite(img)
    return out


def finish(img: Image.Image, name: str) -> None:
    img.save(EFFECT / f"{name}.png")


def star(cx, cy, inner, outer, points=4, rot=0.0, step=None):
    step = step or math.tau / points
    out = []
    for i in range(points * 2):
        ang = rot + i * step / 2
        radius = outer if i % 2 == 0 else inner
        out.append((cx + math.cos(ang) * radius, cy + math.sin(ang) * radius))
    return out


def grit(draw, count, palette, rng, spread=58, centre=(32, 32), size=(1, 3)):
    for _ in range(count):
        ang = rng.uniform(0, math.tau)
        dist = rng.uniform(4, spread)
        x = centre[0] + math.cos(ang) * dist
        y = centre[1] + math.sin(ang) * dist * .75
        s = rng.uniform(*size)
        draw.rectangle((x, y, x + s, y + s), fill=rng.choice(palette))


def tooth_row(cy, cx, width, height, flip, count=7):
    """One row of triangular fangs, pointing towards the bite line."""
    out = []
    step = width / count
    for i in range(count):
        x0 = cx - width / 2 + i * step
        tipy = cy + (height if flip else -height)
        out.append((x0, cy))
        out.append((x0 + step * .5, tipy))
        out.append((x0 + step, cy))
    return out


# ---------------------------------------------------------------- effect sheets


def kd_bite() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((6, 10, 58, 54), fill=(0, 0, 0, 0))
    poly(d, [(8, 32), (20, 14), (44, 14), (56, 32)], SAND_DARK)
    poly(d, [(8, 32), (20, 50), (44, 50), (56, 32)], SAND)
    poly(d, [(14, 32), (24, 20), (40, 20), (50, 32)], RED)
    poly(d, [(14, 32), (24, 44), (40, 44), (50, 32)], RED_LIGHT)
    poly(d, tooth_row(32, 32, 34, 11, False), CREAM)
    poly(d, tooth_row(32, 32, 34, 11, True), CREAM)
    d.line((10, 32, 54, 32), fill=(255, 255, 255, 200), width=1)
    for i in range(6):
        x, y = 6 + i * 10, 6 + (i % 2) * 8
        poly(d, star(x, y, 1, 3, 4, i), SAND_LIGHT)
    finish(outline(img), "kd_bite")


def kd_bite_heavy() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, [(4, 32), (18, 8), (46, 8), (60, 32)], SAND_DARK)
    poly(d, [(4, 32), (18, 56), (46, 56), (60, 32)], SAND)
    poly(d, [(12, 32), (22, 16), (42, 16), (52, 32)], RED)
    poly(d, [(12, 32), (22, 48), (42, 48), (52, 32)], RED_LIGHT)
    poly(d, tooth_row(32, 32, 40, 14, False), CREAM)
    poly(d, tooth_row(32, 32, 40, 14, True), CREAM)
    for i in range(10):
        ang = i * math.tau / 10
        x0, y0 = 32 + math.cos(ang) * 24, 32 + math.sin(ang) * 24
        x1, y1 = 32 + math.cos(ang) * 32, 32 + math.sin(ang) * 32
        d.line((x0, y0, x1, y1), fill=SAND_LIGHT, width=2)
    poly(d, star(32, 32, 6, 15, 8, .2), (240, 220, 170, 180))
    finish(outline(img), "kd_bite_heavy")


def kd_dust() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    rng = random.Random(7)
    d.ellipse((4, 30, 60, 56), fill=BROWN_DARK)
    d.ellipse((8, 26, 56, 50), fill=BROWN)
    d.ellipse((12, 22, 52, 44), fill=SAND_DARK)
    d.ellipse((18, 20, 46, 38), fill=SAND)
    for _ in range(26):
        x, y = rng.uniform(6, 58), rng.uniform(14, 48)
        r = rng.uniform(1.5, 4)
        d.ellipse((x, y, x + r, y + r), fill=rng.choice((SAND, SAND_LIGHT, BROWN)))
    grit(d, 18, (SAND, SAND_LIGHT, BROWN), rng, spread=32, centre=(32, 26))
    finish(outline(img), "kd_dust")


def kd_burst() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(12):
        ang = i * math.tau / 12
        length = 20 + (i % 3) * 8
        w = 4
        x0, y0 = 32 + math.cos(ang) * 6, 32 + math.sin(ang) * 6
        x1, y1 = 32 + math.cos(ang) * length, 32 + math.sin(ang) * length
        nx, ny = -math.sin(ang) * w, math.cos(ang) * w
        poly(d, [(x0 + nx, y0 + ny), (x1, y1), (x0 - nx, y0 - ny)],
             SAND if i % 2 else SAND_LIGHT)
    d.ellipse((22, 20, 42, 42), fill=BROWN)
    d.ellipse((26, 24, 38, 38), fill=SAND_DARK)
    grit(d, 22, (SAND, BROWN, SAND_LIGHT), random.Random(3), spread=40)
    finish(outline(img), "kd_burst")


def kd_quake() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((2, 20, 62, 52), fill=(0, 0, 0, 0))
    d.ellipse((2, 20, 62, 52), outline=BROWN_DARK, width=3)
    d.ellipse((8, 24, 56, 48), outline=SAND_DARK, width=2)
    d.ellipse((14, 28, 50, 44), outline=SAND, width=2)
    rng = random.Random(11)
    for i in range(10):
        ang = i * math.tau / 10 + .3
        r0 = 4 + rng.uniform(0, 3)
        r1 = 20 + (i % 3) * 4
        x0, y0 = 32 + math.cos(ang) * r0, 36 + math.sin(ang) * r0 * .55
        x1, y1 = 32 + math.cos(ang) * r1, 36 + math.sin(ang) * r1 * .55
        mx = (x0 + x1) / 2 + math.cos(ang + 1.4) * 3
        my = (y0 + y1) / 2 + math.sin(ang + 1.4) * 2
        d.line((x0, y0, mx, my), fill=SAND_LIGHT, width=2)
        d.line((mx, my, x1, y1), fill=SAND_DARK, width=2)
    for _ in range(14):
        x, y = rng.uniform(10, 54), rng.uniform(24, 50)
        d.rectangle((x, y, x + 2, y + 2), fill=rng.choice((SAND, BROWN)))
    finish(outline(img), "kd_quake")


def kd_shock() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    for i, radius in enumerate((10, 18, 26)):
        colour = (AMBER[0], AMBER[1], AMBER[2], 230 - i * 60)
        d.arc((32 - radius, 32 - radius * .62, 32 + radius, 32 + radius * .62),
              200, 340, fill=colour, width=3 - (0 if i == 0 else 1))
    for i in range(7):
        ang = math.pi + .5 + i * .38
        r0, r1 = 24, 30
        d.line((32 + math.cos(ang) * r0, 34 + math.sin(ang) * r0 * .6,
                32 + math.cos(ang) * r1, 34 + math.sin(ang) * r1 * .6),
               fill=SAND_LIGHT, width=2)
    finish(outline(img), "kd_shock")


def kd_rage() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(9):
        ang = i * math.tau / 9
        r0, r1 = 10, 26 + (i % 3) * 5
        nx, ny = -math.sin(ang) * 3, math.cos(ang) * 3
        x0, y0 = 32 + math.cos(ang) * r0, 32 + math.sin(ang) * r0
        x1, y1 = 32 + math.cos(ang) * r1, 32 + math.sin(ang) * r1
        poly(d, [(x0 + nx, y0 + ny), (x1, y1), (x0 - nx, y0 - ny)],
             RED_LIGHT if i % 2 else RED)
    d.ellipse((18, 18, 46, 46), fill=RED)
    d.ellipse((23, 23, 41, 41), fill=AMBER)
    d.ellipse((27, 27, 37, 37), fill=CREAM)
    for i in range(8):
        ang = i * math.tau / 8 + .4
        poly(d, star(32 + math.cos(ang) * 24, 32 + math.sin(ang) * 22, 1, 3, 4, ang), CREAM)
    finish(outline(img), "kd_rage")


def kd_claw() -> None:
    img = canvas()
    d = ImageDraw.Draw(img)
    for off, tone in ((-18, RED_LIGHT), (0, CREAM), (18, RED)):
        x0, y0 = 6 + off, 58
        x1, y1 = 46 + off, 6
        nx, ny = -(y1 - y0), (x1 - x0)
        ln = math.hypot(nx, ny)
        nx, ny = nx / ln, ny / ln
        for w0, w1, fill in ((6, 1.2, tone), (2.5, .5, (255, 246, 226, 235))):
            poly(d, [(x0 + nx * w0, y0 + ny * w0), (x1 + nx * w1, y1 + ny * w1),
                     (x1 - nx * w1, y1 - ny * w1), (x0 - nx * w0, y0 - ny * w0)], fill)
    for i in range(5):
        x, y = 10 + i * 11, 52 - i * 9
        poly(d, star(x, y, 1, 3, 4, i), SAND_LIGHT)
    finish(outline(img), "kd_claw")


# ---------------------------------------------------------------- icons & portrait


def icon_base():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), fill=(26, 18, 14, 255))
    d.rectangle((0, 0, 31, 31), outline=(200, 162, 74, 255), width=2)
    for x, y in ((1, 1), (28, 1), (1, 28), (28, 28)):
        d.rectangle((x, y, x + 2, y + 2), fill=(232, 198, 116, 255))
    return img, d


def icon_q():
    img, d = icon_base()
    poly(d, [(4, 16), (10, 6), (22, 6), (28, 16)], CREAM)
    poly(d, [(4, 16), (10, 26), (22, 26), (28, 16)], CREAM)
    poly(d, [(6, 16), (11, 11), (21, 11), (26, 16)], RED)
    poly(d, [(6, 16), (11, 21), (21, 21), (26, 16)], RED_LIGHT)
    d.rectangle((4, 15, 28, 17), fill=(255, 255, 255, 210))
    img.save(ICONS / "krookodile_q.png")


def icon_w():
    img, d = icon_base()
    d.rectangle((6, 4, 26, 10), fill=BROWN)
    d.rectangle((8, 6, 24, 8), fill=SAND)
    for i in range(3):
        poly(d, [(10 + i * 6, 10), (13 + i * 6, 22), (16 + i * 6, 10)], SAND_LIGHT)
    d.ellipse((4, 20, 28, 30), fill=BROWN_DARK)
    d.ellipse((7, 21, 25, 27), fill=SAND_DARK)
    img.save(ICONS / "krookodile_w.png")


def icon_e():
    img, d = icon_base()
    d.ellipse((3, 9, 29, 27), outline=BROWN_DARK, width=2)
    d.ellipse((8, 12, 24, 24), outline=SAND, width=2)
    for i in range(5):
        ang = i * math.tau / 5 + .3
        d.line((16 + math.cos(ang) * 3, 18 + math.sin(ang) * 2,
                16 + math.cos(ang) * 11, 18 + math.sin(ang) * 7), fill=SAND_LIGHT, width=2)
    d.ellipse((14, 16, 18, 20), fill=AMBER)
    img.save(ICONS / "krookodile_e.png")


def icon_r():
    img, d = icon_base()
    for i in range(8):
        ang = i * math.tau / 8
        poly(d, [(16 + math.cos(ang) * 6, 16 + math.sin(ang) * 6),
                 (16 + math.cos(ang) * 13, 16 + math.sin(ang) * 13),
                 (16 + math.cos(ang + .35) * 6, 16 + math.sin(ang + .35) * 6)],
             RED if i % 2 else RED_LIGHT)
    poly(d, [(6, 16), (11, 8), (21, 8), (26, 16)], CREAM)
    poly(d, [(6, 16), (11, 24), (21, 24), (26, 16)], CREAM)
    d.rectangle((6, 15, 26, 17), fill=AMBER)
    poly(d, [(13, 8), (16, 3), (19, 8)], AMBER)
    img.save(ICONS / "krookodile_r.png")


def portrait() -> None:
    url = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/553.png"
    try:
        raw = urllib.request.urlopen(url, timeout=20).read()
    except Exception as error:  # noqa: BLE001 - offline is not fatal, the icon still ships.
        print(f"portrait skipped: {error}")
        return
    src = Image.open(__import__("io").BytesIO(raw)).convert("RGBA")
    # Drop the flat white backdrop, then centre the sprite in a 56px box.
    pixels = src.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = pixels[x, y]
            if a and r > 244 and g > 244 and b > 244:
                pixels[x, y] = (0, 0, 0, 0)
    box = src.getbbox()
    sprite = src.crop(box)
    scale = 56 / max(sprite.size)
    sprite = sprite.resize((max(1, round(sprite.width * scale)), max(1, round(sprite.height * scale))),
                           Image.NEAREST)
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    d.rectangle((0, 0, 63, 63), fill=(30, 22, 18, 255))
    d.rectangle((0, 0, 63, 63), outline=(200, 162, 74, 255), width=3)
    out.alpha_composite(sprite, ((64 - sprite.width) // 2, (64 - sprite.height) // 2))
    out.save(PORTRAITS / "krookodile.png")


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    PORTRAITS.mkdir(parents=True, exist_ok=True)
    for fn in (kd_bite, kd_bite_heavy, kd_dust, kd_burst, kd_quake, kd_shock, kd_rage, kd_claw):
        fn()
    for fn in (icon_q, icon_w, icon_e, icon_r):
        fn()
    portrait()
    print("wrote 8 effect sheets, 4 icons and the portrait")


if __name__ == "__main__":
    main()

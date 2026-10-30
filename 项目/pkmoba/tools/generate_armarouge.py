# -*- coding: utf-8 -*-
"""Armarouge (红莲铠骑) art: fire-orange shells and violet psychic force, 64x64 hard pixels.

Every sheet is drawn natively at 64x64 (no downsampling) and gets the 1px dark outline the rest of
the pkmoba effects use. Palette is deliberately limited: flame = deep red edge, orange body, white
hot core; psychic = deep violet edge, violet body, pale lavender core.

Run from the repository root:
    python 项目/pkmoba/tools/generate_armarouge.py
"""
from __future__ import annotations

import math
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / "项目/pkmoba/src/main/resources/assets/pkmoba/textures"
EFFECT = RES / "effect"
ICONS = RES / "gui/skills"
PORTRAITS = RES / "gui/pokemon"
for folder in (EFFECT, ICONS, PORTRAITS):
    folder.mkdir(parents=True, exist_ok=True)

S = 64
DEEP_RED = (150, 36, 26, 255)
ORANGE = (242, 116, 38, 255)
GOLD = (255, 186, 84, 255)
HOT = (255, 244, 214, 255)
VIOLET_DEEP = (78, 44, 150, 255)
VIOLET = (150, 96, 246, 255)
LILAC = (206, 172, 255, 255)
PALE = (244, 236, 255, 255)


def canvas() -> Image.Image:
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def outline(img: Image.Image, colour=(10, 10, 16, 235)) -> Image.Image:
    alpha = img.getchannel("A")
    grown = alpha.filter(ImageFilter.MaxFilter(3))
    edge = Image.new("RGBA", img.size, (0, 0, 0, 0))
    edge.putalpha(grown.point(lambda v: 255 if v > 40 else 0))
    tint = Image.new("RGBA", img.size, colour)
    tint.putalpha(edge.getchannel("A"))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(tint)
    out.alpha_composite(img)
    return out


def finish(img: Image.Image, name: str) -> Image.Image:
    out = outline(img)
    out.save(EFFECT / f"{name}.png")
    return out


def star(cx, cy, points, inner, outer, rot=0.0):
    pts = []
    for i in range(points * 2):
        ang = rot + i * math.pi / points
        r = outer if i % 2 == 0 else inner
        pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
    return pts


def flame(d, cx, cy, w, h, palette=(DEEP_RED, ORANGE, GOLD, HOT)):
    """Teardrop flame: round belly, pointed tip, layered palette."""
    for scale, colour in zip((1.0, .74, .50, .26), palette):
        d.ellipse((cx - w * scale, cy - h * .45 * scale, cx + w * scale, cy + h * .55 * scale), fill=colour)
        d.polygon([(cx - w * .55 * scale, cy - h * .10 * scale), (cx, cy - h * 1.05 * scale),
                   (cx + w * .55 * scale, cy - h * .10 * scale)], fill=colour)


def ar_bolt():
    img = canvas()
    d = ImageDraw.Draw(img)
    flame(d, 32, 40, 15, 26)
    for i in range(6):
        ang = i * math.pi / 3 + .3
        d.ellipse((32 + math.cos(ang) * 22 - 2, 34 + math.sin(ang) * 20 - 2,
                   32 + math.cos(ang) * 22 + 2, 34 + math.sin(ang) * 20 + 2), fill=(255, 208, 140, 210))
    return finish(img, "ar_bolt")


def ar_bolt_heavy():
    img = canvas()
    d = ImageDraw.Draw(img)
    flame(d, 32, 38, 20, 34)
    d.ellipse((18, 24, 46, 52), outline=(255, 150, 70, 200), width=2)
    for i in range(10):
        ang = i * math.pi / 5 + .15
        d.polygon(star(32 + math.cos(ang) * 26, 32 + math.sin(ang) * 26, 4, 2, 5, ang), (255, 196, 110, 225))
    d.ellipse((28, 30, 36, 40), fill=PALE)
    return finish(img, "ar_bolt_heavy")


def ar_psy():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.polygon([(32, 4), (46, 32), (32, 60), (18, 32)], fill=VIOLET_DEEP)
    d.polygon([(32, 10), (42, 32), (32, 54), (22, 32)], fill=VIOLET)
    d.polygon([(32, 18), (38, 32), (32, 46), (26, 32)], fill=LILAC)
    d.polygon(star(32, 32, 4, 3, 9, math.pi / 4), fill=PALE)
    for x, y in ((12, 20), (52, 24), (14, 46), (50, 44)):
        d.rectangle((x - 1, y - 1, x + 1, y + 1), fill=(196, 160, 255, 220))
    return finish(img, "ar_psy")


def ar_flame():
    img = canvas()
    d = ImageDraw.Draw(img)
    for i, (cx, w, h) in enumerate(((22, 11, 30), (32, 14, 40), (42, 11, 28))):
        flame(d, cx, 40 + (i % 2) * 4, w, h)
    for i in range(5):
        y = 52 + i * 2
        d.ellipse((26 - i * 2, y, 38 + i * 2, y + 3), fill=(242, 140, 60, 120 - i * 18))
    return finish(img, "ar_flame")


def ar_cannon():
    img = canvas()
    d = ImageDraw.Draw(img)
    for cx in (22, 42):
        d.ellipse((cx - 9, 22, cx + 9, 44), fill=DEEP_RED)
        d.ellipse((cx - 7, 24, cx + 7, 42), fill=ORANGE)
        d.ellipse((cx - 4, 27, cx + 4, 39), fill=GOLD)
    d.ellipse((20, 22, 44, 46), fill=(255, 170, 70, 220))
    d.ellipse((24, 26, 40, 42), fill=GOLD)
    d.ellipse((28, 30, 36, 38), fill=HOT)
    for i in range(8):
        ang = i * math.pi / 4 + .2
        d.polygon(star(32 + math.cos(ang) * 24, 34 + math.sin(ang) * 22, 4, 2, 5, ang), (255, 200, 120, 210))
    return finish(img, "ar_cannon")


def ar_boom():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.polygon(star(32, 32, 10, 10, 30, .15), fill=DEEP_RED)
    d.polygon(star(32, 32, 10, 8, 24, .15), fill=ORANGE)
    d.polygon(star(32, 32, 8, 6, 16, .4), fill=GOLD)
    d.ellipse((24, 24, 40, 40), fill=HOT)
    for i in range(6):
        ang = i * math.pi / 3 + .5
        d.ellipse((32 + math.cos(ang) * 26 - 2, 32 + math.sin(ang) * 26 - 2,
                   32 + math.cos(ang) * 26 + 2, 32 + math.sin(ang) * 26 + 2), fill=(255, 226, 160, 220))
    return finish(img, "ar_boom")


def ar_rune():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((6, 6, 58, 58), outline=VIOLET_DEEP, width=3)
    d.ellipse((10, 10, 54, 54), outline=VIOLET, width=2)
    d.ellipse((20, 20, 44, 44), outline=LILAC, width=2)
    for i in range(12):
        ang = i * math.pi / 6
        x0, y0 = 32 + math.cos(ang) * 24, 32 + math.sin(ang) * 24
        x1, y1 = 32 + math.cos(ang) * 29, 32 + math.sin(ang) * 29
        d.line([(x0, y0), (x1, y1)], fill=(188, 150, 255, 230), width=2)
    d.polygon(star(32, 32, 4, 4, 13, math.pi / 4), fill=PALE)
    d.ellipse((29, 29, 35, 35), fill=(255, 255, 255, 255))
    return finish(img, "ar_rune")


def ar_pulse():
    img = canvas()
    d = ImageDraw.Draw(img)
    for radius, colour, width in ((26, (108, 66, 190, 200), 2), (20, VIOLET, 3),
                                  (13, LILAC, 2), (7, PALE, 3)):
        d.ellipse((32 - radius, 32 - radius, 32 + radius, 32 + radius), outline=colour, width=width)
    for i in range(8):
        ang = i * math.pi / 4 + .25
        d.polygon(star(32 + math.cos(ang) * 30, 32 + math.sin(ang) * 30, 4, 1.5, 4, ang), (214, 190, 255, 225))
    d.ellipse((30, 30, 34, 34), fill=(255, 255, 255, 240))
    return finish(img, "ar_pulse")



def ar_muzzle():
    """Short-lived star flash that sits on the barrel mouth."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for colour, outer, inner in ((DEEP_RED, 31, 12), (ORANGE, 26, 10), (GOLD, 20, 8), (HOT, 13, 5)):
        d.polygon(star(32, 32, 8, inner, outer, .2), fill=colour)
    d.ellipse((28, 28, 36, 36), fill=(255, 255, 245, 255))
    for i in range(4):
        ang = i * math.pi / 2 + .78
        d.polygon(star(32 + math.cos(ang) * 26, 32 + math.sin(ang) * 26, 4, 1.5, 5, ang),
                  (255, 216, 150, 200))
    return finish(img, "ar_muzzle")


def ar_scorch():
    """Ground scorch: irregular burned ring with cracks and a few live embers."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for radius, colour in ((27, (74, 26, 20, 190)), (22, (108, 38, 24, 210)), (16, (54, 20, 16, 170))):
        d.ellipse((32 - radius, 32 - radius, 32 + radius, 32 + radius), outline=colour, width=3)
    for i in range(10):
        ang = i * math.pi / 5 + .31
        r0 = 8 + (i % 3) * 3
        d.line([(32 + math.cos(ang) * r0, 32 + math.sin(ang) * r0),
                (32 + math.cos(ang) * (r0 + 9 + (i % 2) * 4),
                 32 + math.sin(ang) * (r0 + 9 + (i % 2) * 4))], fill=(70, 24, 18, 200), width=2)
    for i in range(7):
        ang = i * math.pi * 2 / 7 + .9
        d.ellipse((32 + math.cos(ang) * 23 - 2, 32 + math.sin(ang) * 23 - 2,
                   32 + math.cos(ang) * 23 + 2, 32 + math.sin(ang) * 23 + 2), fill=(255, 138, 52, 220))
    return finish(img, "ar_scorch")


def ar_glyph():
    """Expanding Force focus sigil: hex frame, inner star, floating orbit dots."""
    img = canvas()
    d = ImageDraw.Draw(img)
    hexagon = [(32 + math.cos(i * math.pi / 3 - math.pi / 2) * 26,
                32 + math.sin(i * math.pi / 3 - math.pi / 2) * 26) for i in range(6)]
    d.polygon(hexagon, outline=VIOLET_DEEP, width=3)
    hexagon2 = [(32 + math.cos(i * math.pi / 3 - math.pi / 2) * 20,
                 32 + math.sin(i * math.pi / 3 - math.pi / 2) * 20) for i in range(6)]
    d.polygon(hexagon2, outline=VIOLET, width=2)
    d.polygon(star(32, 32, 6, 6, 17, .5), fill=LILAC)
    d.polygon(star(32, 32, 6, 3, 10, .5), fill=PALE)
    for i in range(6):
        ang = i * math.pi / 3 - math.pi / 2
        d.ellipse((32 + math.cos(ang) * 30 - 2, 32 + math.sin(ang) * 30 - 2,
                   32 + math.cos(ang) * 30 + 2, 32 + math.sin(ang) * 30 + 2), fill=(232, 216, 255, 230))
    d.ellipse((30, 30, 34, 34), fill=(255, 255, 255, 255))
    return finish(img, "ar_glyph")


def ar_spark():
    """Loose glint cluster used for hit sparks and dash debris."""
    img = canvas()
    d = ImageDraw.Draw(img)
    spots = ((20, 22, 5, HOT), (40, 18, 4, GOLD), (28, 38, 6, (255, 214, 150, 235)),
             (45, 40, 3, HOT), (15, 42, 3, GOLD), (33, 26, 3, (255, 255, 255, 240)))
    for x, y, r, colour in spots:
        d.polygon(star(x, y, 4, r * .45, r, .4), fill=colour)
    return finish(img, "ar_spark")


def ar_smoke():
    """Hot smoke puff that trails the cannon shell."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for x, y, r, colour in ((24, 30, 13, (86, 40, 30, 150)), (36, 26, 15, (110, 52, 34, 140)),
                            (32, 40, 12, (66, 30, 24, 150)), (28, 22, 9, (140, 74, 44, 120))):
        d.ellipse((x - r, y - r, x + r, y + r), fill=colour)
    d.ellipse((26, 28, 38, 40), fill=(150, 78, 46, 110))
    return finish(img, "ar_smoke")

BORDER = (196, 62, 40, 255)


def icon_base() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (32, 32), (14, 12, 20, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), outline=BORDER, width=2)
    d.rectangle((4, 4, 27, 27), outline=(58, 40, 30, 255), width=1)
    for x, y in ((2, 2), (27, 2), (2, 27), (27, 27)):
        d.rectangle((x, y, x + 2, y + 2), fill=(255, 226, 160, 255))
    return img, d


def icon_q():
    img, d = icon_base()
    d.polygon([(16, 3), (24, 16), (16, 29), (8, 16)], fill=VIOLET_DEEP)
    d.polygon([(16, 6), (21, 16), (16, 26), (11, 16)], fill=VIOLET)
    d.polygon([(16, 10), (19, 16), (16, 22), (13, 16)], fill=LILAC)
    d.polygon(star(16, 16, 4, 1.5, 5, math.pi / 4), fill=PALE)
    img.save(ICONS / "armarouge_q.png")


def icon_w():
    img, d = icon_base()
    d.polygon(star(16, 20, 7, 6, 13, .3), fill=ORANGE)
    d.polygon(star(16, 21, 6, 4, 9, .3), fill=GOLD)
    d.ellipse((12, 14, 20, 26), fill=HOT)
    d.polygon([(16, 4), (20, 14), (12, 14)], fill=ORANGE)
    for i in range(3):
        d.ellipse((9 + i * 5, 26, 14 + i * 5, 29), fill=(242, 140, 60, 160))
    img.save(ICONS / "armarouge_w.png")


def icon_e():
    img, d = icon_base()
    for cx, tone in ((11, DEEP_RED), (21, DEEP_RED)):
        d.ellipse((cx - 4, 9, cx + 4, 24), fill=tone)
        d.ellipse((cx - 3, 11, cx + 3, 22), fill=ORANGE)
        d.ellipse((cx - 1, 14, cx + 1, 19), fill=GOLD)
    d.ellipse((10, 12, 22, 24), fill=(255, 150, 60, 200))
    d.ellipse((12, 14, 20, 22), fill=GOLD)
    d.ellipse((14, 16, 18, 20), fill=HOT)
    img.save(ICONS / "armarouge_e.png")


def icon_r():
    img, d = icon_base()
    d.ellipse((4, 4, 28, 28), outline=VIOLET_DEEP, width=2)
    d.ellipse((7, 7, 25, 25), outline=VIOLET, width=2)
    for i in range(8):
        ang = i * math.pi / 4
        d.rectangle((16 + math.cos(ang) * 11 - 1, 16 + math.sin(ang) * 11 - 1,
                     16 + math.cos(ang) * 11 + 1, 16 + math.sin(ang) * 11 + 1), fill=LILAC)
    d.polygon(star(16, 16, 4, 2, 7, math.pi / 4), fill=PALE)
    img.save(ICONS / "armarouge_r.png")


def portrait():
    cache = ROOT / "项目/pkmoba/tools/_armarouge_sprite.png"
    if not cache.exists():
        urllib.request.urlretrieve(
            "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/936.png", cache)
    sprite = Image.open(cache).convert("RGBA")
    px = sprite.load()
    for y in range(sprite.height):
        for x in range(sprite.width):
            r, g, b, a = px[x, y]
            if a > 20 and r > 240 and g > 240 and b > 240:
                px[x, y] = (0, 0, 0, 0)
    bbox = sprite.getbbox()
    sprite = sprite.crop(bbox)
    scale = 56 / max(sprite.size)
    sprite = sprite.resize((max(1, round(sprite.width * scale)), max(1, round(sprite.height * scale))),
                           Image.NEAREST)
    out = Image.new("RGBA", (64, 64), (12, 10, 16, 255))
    d = ImageDraw.Draw(out)
    d.rectangle((0, 0, 63, 63), outline=BORDER, width=2)
    d.rectangle((3, 3, 60, 60), outline=(72, 30, 24, 255), width=1)
    out.alpha_composite(sprite, (32 - sprite.width // 2, 32 - sprite.height // 2))
    out.save(PORTRAITS / "armarouge.png")


def main():
    for fn in (ar_bolt, ar_bolt_heavy, ar_psy, ar_flame, ar_cannon, ar_boom, ar_rune, ar_pulse,
               ar_muzzle, ar_scorch, ar_glyph, ar_spark, ar_smoke):
        fn()
    icon_q()
    icon_w()
    icon_e()
    icon_r()
    portrait()
    print("wrote 13 effect sheets, 4 icons and the portrait")


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""Hisuian Zoroark (洗翠索罗亚克) art: 9 effect sheets, 4 skill icons, 1 portrait.

Palette is the Pokémon's own: chalk white fur, deep crimson mane, near-black ghost body.
"""
import math
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/pkmoba"
EFFECT = ASSETS / "textures/effect"
ICONS = ASSETS / "textures/gui/skills"
PORTRAITS = ASSETS / "textures/gui/pokemon"
for folder in (EFFECT, ICONS, PORTRAITS):
    folder.mkdir(parents=True, exist_ok=True)

WHITE = (240, 244, 248, 255)
ASH = (198, 204, 214, 255)
RED = (156, 26, 46, 255)
RED_L = (222, 62, 78, 255)
DEEP = (12, 12, 18, 255)
GHOST = (60, 30, 60, 255)
INK = (8, 9, 14, 240)


def canvas(size=128):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def outline(img, color=INK):
    alpha = img.getchannel("A")
    grown = Image.new("L", img.size, 0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if dx == 0 and dy == 0:
                continue
            grown = Image.fromarray(
                __import__("numpy").maximum(
                    __import__("numpy").array(grown),
                    __import__("numpy").array(alpha.transform(img.size, Image.AFFINE, (1, 0, -dx, 0, 1, -dy)))))
    ring = __import__("numpy").array(grown).astype(int) - __import__("numpy").array(alpha).astype(int)
    ring = __import__("numpy").clip(ring, 0, 255).astype("uint8")
    layer = Image.new("RGBA", img.size, color)
    layer.putalpha(Image.fromarray(ring))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(layer)
    out.alpha_composite(img)
    return out


def finish(img, name):
    if img.size != (64, 64):
        img = img.resize((64, 64), Image.NEAREST)
    outline(img).save(EFFECT / f"{name}.png")
    return img


def poly(d, points, fill):
    d.polygon([(round(x), round(y)) for x, y in points], fill=fill)


def star(x, y, points, outer, inner, rot=0.0):
    out = []
    for i in range(points * 2):
        ang = rot + i * math.pi / points
        r = outer if i % 2 == 0 else inner
        out.append((x + math.cos(ang) * r, y + math.sin(ang) * r))
    return out


def slash(d, x0, y0, x1, y1, w0, w1, tone, core):
    nx, ny = -(y1 - y0), (x1 - x0)
    ln = math.hypot(nx, ny) or 1
    nx, ny = nx / ln, ny / ln
    poly(d, [(x0 + nx * w0, y0 + ny * w0), (x1 + nx * w1, y1 + ny * w1),
             (x1 - nx * w1, y1 - ny * w1), (x0 - nx * w0, y0 - ny * w0)], tone)
    poly(d, [(x0 + nx * w0 * .45, y0 + ny * w0 * .45), (x1 + nx * w1 * .45, y1 + ny * w1 * .45),
             (x1 - nx * w1 * .45, y1 - ny * w1 * .45), (x0 - nx * w0 * .45, y0 - ny * w0 * .45)], core)


def claw_set(flip=False):
    img = canvas()
    d = ImageDraw.Draw(img)
    for i, off in enumerate((-18, 0, 18)):
        x0, y0 = 16 + off, 96
        x1, y1 = 78 + off, 22
        if flip:
            x0, x1 = 64 - x0, 64 - x1
        slash(d, x0, y0, x1, y1, 9, 1.5, RED, WHITE)
    for i in range(4):
        d.ellipse((12 + i * 13, 54 - (i % 2) * 10, 16 + i * 13, 58 - (i % 2) * 10), fill=ASH)
    return img


def zo_claw_l():
    finish(claw_set(False), "zo_claw_l")


def zo_claw_r():
    finish(claw_set(True), "zo_claw_r")


def zo_claw_cross():
    img = canvas()
    d = ImageDraw.Draw(img)
    for flip in (False, True):
        for off in (-14, 0, 14):
            x0, y0 = 20 + off, 92
            x1, y1 = 74 + off, 24
            if flip:
                x0, x1 = 64 - x0, 64 - x1
            slash(d, x0, y0, x1, y1, 7, 1.2, RED, WHITE)
    poly(d, star(64, 55, 4, 16, 5, math.pi / 4), WHITE)
    finish(img, "zo_claw_cross")


def zo_ring():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((8, 8, 120, 120), outline=RED, width=10)
    d.ellipse((16, 16, 112, 112), outline=WHITE, width=5)
    d.ellipse((30, 30, 98, 98), outline=RED_L, width=3)
    for i in range(8):
        ang = i * math.pi / 4
        x, y = 64 + math.cos(ang) * 52, 64 + math.sin(ang) * 52
        poly(d, star(x, y, 4, 9, 3, ang), WHITE)
    for i in range(3):
        ang = -math.pi / 2 + i * math.tau / 3
        x, y = 64 + math.cos(ang) * 34, 64 + math.sin(ang) * 34
        d.ellipse((x - 5, y - 3, x + 5, y + 5), fill=WHITE)
        d.ellipse((x - 3, y - 1, x - 1, y + 2), fill=RED)
        d.ellipse((x + 1, y - 1, x + 3, y + 2), fill=RED)
    return finish(img, "zo_ring")


def zo_wisp():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, [(64, 8), (84, 30), (88, 52), (78, 74), (64, 86), (48, 74), (40, 50), (44, 28)], GHOST)
    poly(d, [(64, 14), (78, 32), (80, 52), (70, 70), (64, 78), (56, 68), (48, 50), (50, 30)], WHITE)
    d.ellipse((54, 40, 60, 48), fill=RED)
    d.ellipse((68, 40, 74, 48), fill=RED)
    for i in range(5):
        x = 44 + i * 10
        d.ellipse((x, 78, x + 4, 84), fill=WHITE if i % 2 else ASH)
    return finish(img, "zo_wisp")


def zo_orb():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((4, 4, 124, 124), fill=GHOST)
    d.ellipse((10, 10, 118, 118), fill=DEEP)
    for i in range(3):
        r = 40 - i * 9
        d.arc((64 - r, 64 - r, 64 + r, 64 + r), 20 + i * 40, 300 + i * 20, fill=RED, width=5)
    d.ellipse((48, 48, 80, 80), fill=RED_L)
    d.ellipse((54, 54, 74, 74), fill=WHITE)
    for i in range(8):
        ang = i * math.pi / 4 + .3
        x, y = 64 + math.cos(ang) * 52, 64 + math.sin(ang) * 52
        poly(d, star(x, y, 4, 7, 2, ang), WHITE if i % 2 else RED_L)
    return finish(img, "zo_orb")


def zo_burst():
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(12):
        ang = i * math.tau / 12
        r0, r1 = 16, 56 + (i % 3) * 5
        poly(d, [(64 + math.cos(ang - .07) * r0, 64 + math.sin(ang - .07) * r0),
                 (64 + math.cos(ang) * r1, 64 + math.sin(ang) * r1),
                 (64 + math.cos(ang + .07) * r0, 64 + math.sin(ang + .07) * r0)],
             WHITE if i % 2 else RED_L)
    d.ellipse((44, 44, 84, 84), fill=RED)
    d.ellipse((52, 52, 76, 76), fill=WHITE)
    d.ellipse((28, 28, 100, 100), outline=RED, width=4)
    return finish(img, "zo_burst")


def zo_sigil():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, [(32, 6), (54, 20), (58, 44), (32, 58), (6, 44), (10, 20)], WHITE)
    poly(d, [(32, 14), (47, 24), (50, 42), (32, 50), (14, 42), (17, 24)], ASH)
    d.ellipse((22, 26, 30, 34), fill=RED)
    d.ellipse((34, 26, 42, 34), fill=RED)
    poly(d, [(26, 40), (38, 40), (32, 47)], RED)
    for i in range(4):
        ang = i * math.tau / 4 + .4
        poly(d, star(32 + math.cos(ang) * 26, 32 + math.sin(ang) * 26, 4, 5, 2, ang), WHITE)
    return finish(img, "zo_sigil")


def zo_after():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, [(64, 6), (86, 26), (92, 50), (86, 74), (64, 96), (42, 74), (36, 50), (42, 26)], GHOST)
    poly(d, [(64, 12), (80, 30), (84, 52), (79, 72), (64, 88), (49, 72), (44, 52), (48, 30)], (120, 60, 90, 210))
    d.ellipse((52, 42, 59, 50), fill=WHITE)
    d.ellipse((69, 42, 76, 50), fill=WHITE)
    poly(d, [(56, 62), (72, 62), (64, 72)], WHITE)
    for i in range(6):
        x = 20 + i * 8
        d.ellipse((x, 84 + (i % 2) * 5, x + 4, 88 + (i % 2) * 5), fill=ASH)
    return finish(img, "zo_after")


def icon_base():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), fill=(18, 14, 22, 255))
    d.rectangle((0, 0, 31, 31), outline=(70, 26, 40, 255), width=2)
    d.rectangle((1, 1, 30, 30), outline=(140, 46, 62, 255))
    for x, y in ((2, 2), (29, 2), (2, 29), (29, 29)):
        d.point((x, y), fill=(236, 210, 214, 255))
    return img, d


def icon_claw():
    img, d = icon_base()
    for off in (-6, 0, 6):
        slash(d, 8 + off, 26, 24 + off, 6, 3.2, .6, RED_L, WHITE)
    img.save(ICONS / "hisuian_zoroark_q.png")


def icon_ring():
    img, d = icon_base()
    d.ellipse((5, 5, 27, 27), outline=RED, width=3)
    d.ellipse((8, 8, 24, 24), outline=WHITE)
    d.ellipse((14, 14, 18, 18), fill=RED_L)
    for i in range(4):
        ang = i * math.pi / 2 + .2
        poly(d, star(16 + math.cos(ang) * 9.5, 16 + math.sin(ang) * 9.5, 4, 3.2, 1.4, ang), WHITE)
    img.save(ICONS / "hisuian_zoroark_w.png")


def icon_orb():
    img, d = icon_base()
    d.ellipse((5, 5, 27, 27), fill=GHOST)
    d.ellipse((7, 7, 25, 25), fill=DEEP)
    d.arc((9, 9, 23, 23), 20, 300, fill=RED, width=3)
    d.ellipse((12, 12, 20, 20), fill=RED_L)
    d.ellipse((14, 14, 18, 18), fill=WHITE)
    img.save(ICONS / "hisuian_zoroark_e.png")


def icon_illusion():
    img, d = icon_base()
    for i, (x, y, a) in enumerate(((9, 18, 150), (16, 13, 205), (23, 18, 150))):
        poly(d, [(x, y - 9), (x + 5, y - 4), (x + 5, y + 4), (x, y + 8), (x - 5, y + 4), (x - 5, y - 4)],
             (240, 244, 248, a))
        d.ellipse((x - 3, y - 2, x - 1, y), fill=RED)
        d.ellipse((x + 1, y - 2, x + 3, y), fill=RED)
    img.save(ICONS / "hisuian_zoroark_r.png")


def portrait():
    target = PORTRAITS / "hisuian_zoroark.png"
    urls = [
        "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/10238.png",
        "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/10238.png",
    ]
    for url in urls:
        try:
            data = urllib.request.urlopen(url, timeout=12).read()
        except Exception as error:  # noqa: BLE001
            print("portrait fetch failed", url, error)
            continue
        from io import BytesIO
        sprite = Image.open(BytesIO(data)).convert("RGBA")
        sprite = sprite.crop(sprite.getbbox())
        side = max(sprite.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.alpha_composite(sprite, ((side - sprite.width) // 2, (side - sprite.height) // 2))
        body = square.resize((56, 56), Image.NEAREST)
        frame = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        ImageDraw.Draw(frame).rectangle((0, 0, 63, 63), fill=(26, 18, 26, 255))
        ImageDraw.Draw(frame).rectangle((0, 0, 63, 63), outline=(96, 30, 44, 255), width=3)
        ImageDraw.Draw(frame).rectangle((3, 3, 60, 60), outline=(212, 196, 202, 255))
        frame.alpha_composite(body, (4, 4))
        frame.save(target)
        print("portrait written from", url)
        return
    print("portrait skipped (offline)")


if __name__ == "__main__":
    zo_claw_l()
    zo_claw_r()
    zo_claw_cross()
    zo_ring()
    zo_wisp()
    zo_orb()
    zo_burst()
    zo_sigil()
    zo_after()
    icon_claw()
    icon_ring()
    icon_orb()
    icon_illusion()
    portrait()
    print("wrote 9 effect sheets, 4 icons")

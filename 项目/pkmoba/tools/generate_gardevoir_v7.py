"""Gardevoir v7 art: solid bright 3D moon, travelling psychic vortex, 3D dazzling rays.

v6 replaces the flat "sound arcs" kit with three clearly different pictures:

* W Moonblast is a real 3D sphere again (``gardevoir_moonsphere`` is an equirect moon skin that
  wraps the UV sphere, so the moving light rolls over actual volume instead of a sticker).
* Q Psychic is a telekinetic crush: spiral arms closing into a point, then an implosion.
* R Dazzling Gleam is a pink-white flash: a light dome, radiated rays and sparkles.
"""

from pathlib import Path
from math import cos, sin, tau

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/pkmoba'
TEX = ROOT / 'textures/effect'
ICON = ROOT / 'textures/gui/skills'

WHITE = (255, 250, 255, 255)
PALE = (255, 222, 246, 255)
PINK = (255, 140, 208, 255)
MAGENTA = (226, 88, 176, 255)
DEEP = (110, 36, 100, 255)
FRAME = (232, 196, 96, 255)
FRAME_DARK = (96, 70, 34, 255)
BG = (24, 18, 40, 255)


def canvas():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img):
    out = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    out.alpha_composite(img)
    return out


def ring_sheet():
    img, d = canvas()
    cx = cy = 32
    for radius, width, colour in ((26, 7, MAGENTA), (26, 5, PINK), (26, 2, WHITE)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], outline=colour, width=width)
    for i in range(12):
        a = tau * i / 12
        x = cx + int(round(cos(a) * 26))
        y = cy + int(round(sin(a) * 26))
        d.rectangle([x - 2, y - 2, x + 2, y + 2], fill=WHITE)
    return finish(img)


def moon_sphere_sheet():
    """Bright equirectangular moon skin: near-white body, blue-grey craters, hot highlights.

    v6 used pale lavender, which read as a translucent ghost ball in the shade. v7 keeps the same
    UV layout but pushes the body to white so the emissive sphere looks like a lit solid moon.
    """
    img = Image.new('RGBA', (64, 64), (250, 252, 255, 255))
    d = ImageDraw.Draw(img)
    for y in range(0, 64, 8):
        shade = (255, 255, 255, 255) if (y // 8) % 2 == 0 else (238, 246, 255, 255)
        d.rectangle([0, y, 63, y + 7], fill=shade)
    craters = ((8, 8, 4), (22, 16, 3), (36, 6, 5), (50, 18, 3), (12, 30, 5), (28, 26, 3),
               (44, 32, 4), (56, 40, 3), (6, 46, 4), (20, 52, 5), (34, 44, 3), (48, 56, 4))
    for x, y, r in craters:
        d.ellipse([x - r, y - r, x + r, y + r], fill=(196, 214, 244, 255))
        d.ellipse([x - r + 1, y - r + 1, x + r - 1, y + r - 1], fill=(176, 198, 238, 255))
        d.arc([x - r, y - r, x + r, y + r], 200, 340, fill=(255, 255, 255, 255), width=1)
    for x, y in ((15, 20), (31, 34), (52, 8), (40, 50), (4, 58), (58, 26)):
        d.rectangle([x, y, x + 1, y + 1], fill=(255, 255, 255, 255))
    return img


def crush_sheet():
    """Three spiral arms closing clockwise into a bright point - the Psychic crush."""
    img, d = canvas()
    for arm in range(3):
        base = tau * arm / 3
        points = []
        for i in range(22):
            f = i / 21.0
            radius = 28 * (1 - f) ** 1.25 + 2
            angle = base + f * tau * .55
            points.append((32 + cos(angle) * radius, 32 + sin(angle) * radius))
        d.line(points, fill=(168, 66, 150, 235), width=7, joint='curve')
        d.line(points, fill=PINK, width=5, joint='curve')
        d.line(points, fill=WHITE, width=2, joint='curve')
    d.ellipse([26, 26, 38, 38], fill=(255, 236, 252, 235))
    d.ellipse([29, 29, 35, 35], fill=WHITE)
    return finish(img)


def flash_sheet():
    """Dazzling Gleam: a hot core with eight thick tapered rays."""
    img, d = canvas()
    for i in range(8):
        angle = tau * i / 8
        long_ray = i % 2 == 0
        length = 30 if long_ray else 20
        half = 5 if long_ray else 3
        tipx, tipy = 32 + cos(angle) * length, 32 + sin(angle) * length
        sidex, sidey = cos(angle + 1.5708) * half, sin(angle + 1.5708) * half
        body = [(32 + sidex, 32 + sidey), (tipx, tipy), (32 - sidex, 32 - sidey)]
        d.polygon(body, fill=(255, 168, 224, 225))
        inner = [(32 + sidex * .55, 32 + sidey * .55), (tipx, tipy), (32 - sidex * .55, 32 - sidey * .55)]
        d.polygon(inner, fill=(255, 226, 248, 245))
    d.ellipse([21, 21, 43, 43], fill=(255, 186, 232, 240))
    d.ellipse([25, 25, 39, 39], fill=(255, 236, 252, 250))
    d.ellipse([28, 28, 36, 36], fill=WHITE)
    return finish(img)


def ray_sheet():
    """One long tapered light ray, used in stacks around the gleam."""
    img, d = canvas()
    points = [(32, 2), (40, 34), (32, 62), (24, 34)]
    d.polygon(points, fill=(255, 160, 220, 215))
    d.polygon([(32, 6), (36, 32), (32, 58), (28, 32)], fill=(255, 226, 248, 240))
    d.rectangle([31, 12, 33, 52], fill=WHITE)
    return finish(img)


def spark_sheet():
    """Tiny four-point twinkle used to thicken bursts without adding big stickers."""
    img, d = canvas()
    d.polygon([(32, 8), (36, 30), (32, 56), (28, 30)], fill=(255, 226, 248, 235))
    d.polygon([(8, 32), (30, 28), (56, 32), (30, 36)], fill=(255, 226, 248, 235))
    d.ellipse([27, 27, 37, 37], fill=WHITE)
    return finish(img)


def ghost_sheet():
    img, d = canvas()
    body = [(32, 5), (40, 9), (42, 17), (38, 22), (44, 30), (52, 52), (40, 57), (32, 50),
            (24, 57), (12, 52), (20, 30), (26, 22), (22, 17), (24, 9)]
    d.polygon(body, fill=(255, 236, 250, 235))
    d.polygon([(32, 9), (37, 12), (36, 19), (32, 22), (28, 19), (27, 12)], fill=PINK)
    d.polygon([(21, 8), (26, 12), (25, 20), (20, 17)], fill=MAGENTA)
    d.polygon([(43, 8), (38, 12), (39, 20), (44, 17)], fill=MAGENTA)
    d.rectangle([29, 33, 35, 43], fill=MAGENTA)
    d.polygon([(32, 43), (39, 55), (25, 55)], fill=(255, 200, 236, 220))
    return finish(img)


def glow_sheet():
    img, d = canvas()
    for radius, colour in ((30, (226, 88, 176, 90)), (24, (255, 150, 214, 140)),
                           (17, (255, 210, 240, 190)), (10, (255, 245, 255, 230))):
        d.ellipse([32 - radius, 32 - radius, 32 + radius, 32 + radius], fill=colour)
    return finish(img)


def icon_frame():
    img = Image.new('RGBA', (32, 32), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=FRAME, width=2)
    for x, y in ((0, 0), (29, 0), (0, 29), (29, 29)):
        d.rectangle([x, y, x + 2, y + 2], fill=FRAME)
    d.rectangle([2, 2, 29, 29], outline=FRAME_DARK, width=1)
    return img, d


def icon_psychic():
    img, d = icon_frame()
    for arm in range(3):
        base = tau * arm / 3
        pts = []
        for i in range(14):
            f = i / 13.0
            radius = 12 * (1 - f) ** 1.25 + 1
            angle = base + f * tau * .55
            pts.append((16 + cos(angle) * radius, 16 + sin(angle) * radius))
        d.line(pts, fill=MAGENTA, width=3, joint='curve')
        d.line(pts, fill=WHITE, width=1, joint='curve')
    d.rectangle([14, 14, 17, 17], fill=WHITE)
    return img


def icon_moon():
    img, d = icon_frame()
    d.ellipse([4, 4, 27, 27], fill=PALE)
    d.ellipse([7, 6, 25, 24], fill=WHITE)
    for x, y, r in ((11, 11, 2), (19, 15, 2), (13, 20, 1), (21, 21, 1)):
        d.ellipse([x - r, y - r, x + r, y + r], fill=(216, 204, 240, 255))
    for x, y in ((23, 7), (25, 12), (18, 25)):
        d.rectangle([x, y, x + 1, y + 1], fill=MAGENTA)
    return img


def icon_teleport():
    img, d = icon_frame()
    for i, x in enumerate((5, 9, 13)):
        d.polygon([(x, 12), (x + 4, 16), (x, 20)], fill=(255, 170, 224, 220 - i * 50))
    d.polygon([(17, 4), (22, 7), (23, 13), (20, 15), (25, 21), (29, 27), (22, 29), (17, 25),
               (13, 29), (7, 27), (12, 21), (15, 15), (12, 12), (13, 7)], fill=(255, 240, 252, 245))
    d.rectangle([18, 12, 20, 14], fill=MAGENTA)
    d.rectangle([22, 12, 24, 14], fill=MAGENTA)
    d.rectangle([19, 16, 23, 21], fill=MAGENTA)
    d.polygon([(17, 21), (23, 21), (27, 28), (13, 28)], fill=(255, 214, 244, 235))
    return img


def icon_gleam():
    img, d = icon_frame()
    for i in range(8):
        angle = tau * i / 8
        long_ray = i % 2 == 0
        length = 14 if long_ray else 9
        half = 2.4 if long_ray else 1.6
        tipx, tipy = 16 + cos(angle) * length, 16 + sin(angle) * length
        sidex, sidey = cos(angle + 1.5708) * half, sin(angle + 1.5708) * half
        d.polygon([(16 + sidex, 16 + sidey), (tipx, tipy), (16 - sidex, 16 - sidey)],
                  fill=(255, 176, 226, 240))
    d.ellipse([9, 9, 23, 23], fill=(255, 200, 238, 245))
    d.ellipse([12, 12, 20, 20], fill=WHITE)
    return img


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    ICON.mkdir(parents=True, exist_ok=True)
    for name, image in (('gardevoir_ring', ring_sheet()), ('gardevoir_moonsphere', moon_sphere_sheet()),
                        ('gardevoir_crush', crush_sheet()), ('gardevoir_flash', flash_sheet()),
                        ('gardevoir_ray', ray_sheet()), ('gardevoir_spark', spark_sheet()),
                        ('gardevoir_ghost', ghost_sheet()),
                        ('gardevoir_glow', glow_sheet())):
        image.save(TEX / f'{name}.png')
    for name, image in (('q', icon_psychic()), ('w', icon_moon()), ('e', icon_teleport()),
                        ('r', icon_gleam())):
        image.save(ICON / f'gardevoir_{name}.png')
    # the v5 sound sheets are gone from the kit; remove them so nothing stale ships.
    for stale in ('gardevoir_sound', 'gardevoir_moon'):
        path = TEX / f'{stale}.png'
        if path.exists():
            path.unlink()
    print('wrote gardevoir v7: 8 sheets, 4 icons')


if __name__ == '__main__':
    main()

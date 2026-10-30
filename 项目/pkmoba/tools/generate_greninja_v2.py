# -*- coding: utf-8 -*-
"""Greninja 2.0 (m00465) art: 8 effect sheets (64x64) and 4 skill icons (32x32).

Every sheet is drawn from scratch - the old gn_* set is deleted, not recoloured. Conventions match
the rest of the kit: hard pixels, transparent background, one-pixel dark outline, saturated palette.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba")
EFFECT = ROOT / "textures/effect"
ICONS = ROOT / "textures/gui/skills"
S = 64

OUTLINE = (8, 10, 14, 255)
ICE = (232, 250, 255, 255)
CYAN = (86, 208, 246, 255)
DEEP = (24, 108, 176, 255)
NAVY = (14, 26, 52, 255)
VIOLET = (126, 62, 196, 255)
PLUM = (52, 22, 82, 255)


def canvas():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def poly(d, pts, fill):
    d.polygon([(round(x), round(y)) for x, y in pts], fill=fill)


def star(cx, cy, spikes, outer, inner, rot=0.0):
    pts = []
    for i in range(spikes * 2):
        r = outer if i % 2 == 0 else inner
        a = rot + i * math.pi / spikes
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    return pts


def outline(img):
    """One dark pixel around every opaque pixel, then trim it back inside the canvas."""
    px = img.load()
    w, h = img.size
    out = img.copy()
    dst = out.load()
    for y in range(h):
        for x in range(w):
            if px[x, y][3] > 0:
                continue
            near = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3] > 40:
                    near = True
                    break
            if near:
                dst[x, y] = OUTLINE
    return out


def save(img, name, folder, outlined=True):
    if outlined:
        img = outline(img)
    img.save(folder / (name + ".png"))
    return img


# ───────────────────────────── Q: spiral water shuriken ─────────────────────────────
def gj_star():
    img = canvas()
    d = ImageDraw.Draw(img)
    # four curved blades, each swept back like a pinwheel
    for k in range(4):
        base = k * math.pi / 2 + math.pi / 8
        for i in range(0, 101):
            t = i / 100.0
            ang = base + 1.05 * t
            r = 29.5 * t
            x = 32 + math.cos(ang) * r
            y = 32 + math.sin(ang) * r
            w = 7.4 * (1 - t) ** .62 + .7
            d.ellipse((x - w, y - w, x + w, y + w), fill=CYAN)
        # white cutting edge on the leading flank
        for i in range(0, 88):
            t = i / 100.0
            ang = base + 1.05 * t - .055
            r = 29.5 * t
            x = 32 + math.cos(ang) * r
            y = 32 + math.sin(ang) * r
            w = 2.5 * (1 - t) ** .7 + .5
            d.ellipse((x - w, y - w, x + w, y + w), fill=ICE)
    d.ellipse((26, 26, 38, 38), fill=DEEP)
    d.ellipse((29, 29, 35, 35), fill=(6, 16, 30, 255))
    d.ellipse((30, 30, 32, 32), fill=ICE)
    for i in range(6):
        a = i * 1.047 + .3
        x = 32 + math.cos(a) * 30
        y = 32 + math.sin(a) * 30
        d.ellipse((x - 1, y - 1, x + 1, y + 1), fill=ICE)
    return save(img, "gj_star", EFFECT)


# ───────────────────────────── W: night slash cross ─────────────────────────────
def gj_slash():
    img = canvas()
    d = ImageDraw.Draw(img)
    # two tapered blades crossing at the middle: dark navy body, violet edge, white glint
    for base in (math.pi / 4, -math.pi / 4):
        for pass_i, (w0, w1, col) in enumerate((
                (8.0, 1.4, (26, 40, 78, 255)),
                (6.0, 0.9, (44, 64, 118, 255)),
                (2.1, 0.4, (240, 244, 255, 255)))):
            x0, y0 = 32 - math.cos(base) * 28, 32 - math.sin(base) * 28
            x1, y1 = 32 + math.cos(base) * 28, 32 + math.sin(base) * 28
            nx, ny = -math.sin(base), math.cos(base)
            poly(d, [(x0 + nx * w0, y0 + ny * w0), (x1 + nx * w1, y1 + ny * w1),
                     (x1 - nx * w1, y1 - ny * w1), (x0 - nx * w0, y0 - ny * w0)], col)
        # violet rim along one flank
        for i in range(0, 29):
            t = i / 28.0
            x = 32 + math.cos(base) * (t * 28 - 0) * 1.0 - math.cos(base) * 0
            px = 32 + math.cos(base) * (t * 56 - 28)
            py = 32 + math.sin(base) * (t * 56 - 28)
            nx, ny = -math.sin(base), math.cos(base)
            d.ellipse((px + nx * 7 - 1, py + ny * 7 - 1, px + nx * 7 + 1, py + ny * 7 + 1), fill=VIOLET)
    d.polygon(star(32, 32, 4, 8, 2.6, 0), fill=(250, 252, 255, 255))
    for i in range(8):
        a = i * .785 + .2
        r = 28 + (i % 3) * 2
        x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r
        d.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(168, 108, 226, 235))
    return save(img, "gj_slash", EFFECT)


# ───────────────────────────── E: dark pulse orb ─────────────────────────────
def gj_orb():
    img = canvas()
    d = ImageDraw.Draw(img)
    for r in range(30, 0, -1):
        t = r / 30.0
        col = (int(16 + 30 * (1 - t)), int(34 - 12 * (1 - t)), int(68 - 22 * (1 - t)), 255)
        if r < 17:
            col = (int(26 + 30 * (1 - r / 17)), int(8 + 8 * (1 - r / 17)), int(44 + 20 * (1 - r / 17)), 255)
        d.ellipse((32 - r, 32 - r, 32 + r, 32 + r), fill=col)
    # two real spirals wrapping the sphere
    for phase, col in ((0.0, (150, 88, 220, 255)), (math.pi, (104, 54, 172, 255))):
        steps = 260
        for i in range(steps):
            a = i / steps * 3.6 * math.pi + phase
            r = 2.4 + (i / steps) * 24.0
            x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r * .96
            if (x - 32) ** 2 + (y - 32) ** 2 > 29.4 ** 2:
                continue
            w = 1.35 if i % 4 else .95
            d.ellipse((x - w, y - w, x + w, y + w), fill=col)
    d.ellipse((27, 27, 37, 37), fill=(6, 5, 16, 255))
    d.ellipse((30, 30, 34, 34), fill=VIOLET)
    d.ellipse((31, 31, 33, 33), fill=(246, 240, 255, 255))
    d.arc((4, 4, 60, 60), 200, 285, fill=(120, 190, 250, 200), width=1)
    return save(img, "gj_orb", EFFECT)


# ───────────────────────────── E: violet shock ring ─────────────────────────────
def gj_ring():
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(0, 181):
        a = i / 180.0 * math.pi * 2
        jag = 1.0 + .035 * math.sin(a * 9) + .025 * math.sin(a * 5 + 1.2)
        for r, w, col in ((30 * jag, 1.6, (74, 30, 122, 255)),
                          (26 * jag, 1.8, (126, 62, 196, 255)),
                          (22 * jag, 1.2, (196, 142, 246, 255))):
            x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r * .98
            d.ellipse((x - w, y - w, x + w, y + w), fill=col)
    # outward shards so the burst still reads when the sprite is stretched flat on the ground
    for i in range(16):
        a = i * .3927 + .2
        for k in range(3):
            r = 31 + k * 2.2
            x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r * .98
            w = 1.6 - k * .45
            d.ellipse((x - w, y - w, x + w, y + w), fill=(150 + k * 30, 96 + k * 30, 214, 240 - k * 50))
    d.ellipse((28, 28, 36, 36), fill=(30, 10, 48, 190))
    return save(img, "gj_ring", EFFECT)


# ───────────────────────────── R: high pressure jet column ─────────────────────────────
def gj_plot_seal():
    """Ground seal under the buffed body: two counter-rotating rune rings."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for rad, w, col in ((30, 1.6, (52, 20, 86, 255)), (25, 2.0, (118, 56, 188, 255)),
                        (19, 1.2, (186, 126, 240, 255))):
        for i in range(0, 241):
            a = i / 240.0 * math.pi * 2
            x, y = 32 + math.cos(a) * rad, 32 + math.sin(a) * rad
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=col)
    for i in range(12):
        a = i * (math.pi / 6)
        for k in range(2):
            x = 32 + math.cos(a) * (22 + k * 4)
            y = 32 + math.sin(a) * (22 + k * 4)
            d.ellipse((x - 1.6, y - 1.6, x + 1.6, y + 1.6), fill=(172, 104, 232, 230))
    # inner rune: a pentagon of thin strokes
    for i in range(5):
        a0 = i * (math.pi * 2 / 5) - math.pi / 2
        a1 = (i + 2) * (math.pi * 2 / 5) - math.pi / 2
        d.line([(32 + math.cos(a0) * 14, 32 + math.sin(a0) * 14),
                (32 + math.cos(a1) * 14, 32 + math.sin(a1) * 14)], fill=(96, 44, 156, 235))
    d.ellipse((30, 30, 34, 34), fill=(196, 140, 246, 255))
    return save(img, "gj_plot_seal", EFFECT)


def gj_plot_burst():
    """Outward dark shock: a torn ring with violet spikes, used for the buff to land."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(0, 181):
        a = i / 180.0 * math.pi * 2
        jag = 1.0 + .10 * math.sin(a * 7) + .06 * math.sin(a * 3 + .8)
        for r, w, col in ((29 * jag, 2.2, (44, 16, 74, 255)),
                          (24 * jag, 2.6, (104, 44, 172, 255)),
                          (18 * jag, 1.6, (176, 116, 234, 255))):
            x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=col)
    for i in range(18):
        a = i * .349 + .15
        for k in range(4):
            r = 30 + k * 3.0
            x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r
            w = 1.9 - k * .35
            d.ellipse((x - w, y - w, x + w, y + w), fill=(138 + k * 26, 78 + k * 26, 206, 245 - k * 45))
    d.ellipse((26, 26, 38, 38), fill=(22, 8, 40, 200))
    d.ellipse((30, 30, 34, 34), fill=(214, 168, 250, 255))
    return save(img, "gj_plot_burst", EFFECT)


def gj_plot_mote():
    """One dark energy mote: a soft violet core inside a black-violet diamond."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for r in range(9, 0, -1):
        t = r / 9.0
        col = (int(40 + 150 * (1 - t)), int(14 + 90 * (1 - t)), int(74 + 150 * (1 - t)), int(70 + 185 * (1 - t)))
        d.polygon([(32, 32 - r), (32 + r, 32), (32, 32 + r), (32 - r, 32)], fill=col)
    d.polygon([(32, 24), (40, 32), (32, 40), (24, 32)], fill=(120, 56, 190, 235))
    d.polygon([(32, 27), (37, 32), (32, 37), (27, 32)], fill=(196, 138, 246, 250))
    d.ellipse((30, 30, 34, 34), fill=(248, 238, 255, 255))
    for i in range(4):
        a = i * 1.571 + .4
        x, y = 32 + math.cos(a) * 13, 32 + math.sin(a) * 13
        d.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(160, 96, 226, 210))
    return save(img, "gj_plot_mote", EFFECT)


def gj_bubble():
    img = canvas()
    d = ImageDraw.Draw(img)
    for r in range(15, 0, -1):
        t = r / 15.0
        col = (int(70 + 90 * (1 - t)), int(170 + 70 * (1 - t)), int(210 + 45 * (1 - t)), 255)
        d.ellipse((32 - r, 32 - r, 32 + r, 32 + r), fill=col)
    d.arc((19, 19, 45, 45), 200, 320, fill=(248, 254, 255, 255), width=2)
    d.ellipse((24, 22, 31, 29), fill=(250, 254, 255, 235))
    d.ellipse((38, 38, 44, 44), fill=(24, 96, 150, 200))
    d.arc((14, 14, 50, 50), 20, 160, fill=(200, 240, 255, 180), width=1)
    return save(img, "gj_bubble", EFFECT)


def gj_splash():
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(0, 121):
        a = i / 120.0 * math.pi * 2
        jag = 1.0 + .10 * math.sin(a * 7) + .06 * math.sin(a * 11 + .8)
        for r, w, col in ((29 * jag, 2.0, (56, 150, 206, 255)),
                          (24 * jag, 3.0, CYAN),
                          (18.5 * jag, 1.6, ICE)):
            x, y = 32 + math.cos(a) * r * .95, 32 + math.sin(a) * r * .58
            d.ellipse((x - w, y - w, x + w, y + w), fill=col)
    for i in range(12):
        a = i * .524 + .2
        r = 12 + (i % 4) * 4
        x, y = 32 + math.cos(a) * r, 32 + math.sin(a) * r * .6
        d.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(226, 248, 255, 230))
    return save(img, "gj_splash", EFFECT)


def gj_drop():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, [(32, 8), (44, 30), (46, 40), (40, 52), (32, 55), (24, 52), (18, 40), (20, 30)], CYAN)
    poly(d, [(32, 12), (40, 30), (41, 38), (36, 47), (32, 49), (28, 47), (23, 38), (24, 30)],
         (150, 226, 252, 255))
    d.ellipse((25, 26, 33, 36), fill=ICE)
    d.ellipse((36, 40, 40, 45), fill=(30, 110, 168, 230))
    return save(img, "gj_drop", EFFECT)


# ───────────────────────────── icons ─────────────────────────────
def frame(d, ring):
    d.rectangle((0, 0, 31, 31), fill=(10, 12, 16, 255))
    d.rectangle((1, 1, 30, 30), fill=(26, 30, 44, 255))
    d.rectangle((1, 1, 30, 30), outline=ring, width=2)
    d.rectangle((1, 1, 30, 30), outline=(6, 8, 12, 255), width=1)
    d.rectangle((3, 3, 28, 28), outline=(10, 14, 22, 255), width=1)


def icon(name, draw):
    img = Image.new("RGBA", (32, 32), (10, 12, 16, 255))
    d = ImageDraw.Draw(img)
    draw(d)
    img.save(ICONS / (name + ".png"))


def icon_q(d):
    frame(d, (86, 208, 246, 255))
    for k in range(4):
        base = k * math.pi / 2 + math.pi / 8
        for i in range(0, 41):
            t = i / 40.0
            a = base + 1.05 * t
            r = 12.5 * t
            x, y = 16 + math.cos(a) * r, 16 + math.sin(a) * r
            w = 3.0 * (1 - t) ** .6 + .6
            d.ellipse((x - w, y - w, x + w, y + w), fill=(150, 226, 252, 255))
        for i in range(0, 34):
            t = i / 40.0
            a = base + 1.05 * t - .06
            r = 12.5 * t
            x, y = 16 + math.cos(a) * r, 16 + math.sin(a) * r
            d.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(248, 254, 255, 255))
    d.ellipse((13, 13, 19, 19), fill=(18, 34, 58, 255))
    d.ellipse((15, 15, 17, 17), fill=(248, 254, 255, 255))


def icon_w(d):
    frame(d, (126, 62, 196, 255))
    for base in (math.pi / 4, -math.pi / 4):
        for w0, w1, col in ((4.2, 1.0, (34, 24, 62, 255)),
                            (3.0, .6, (62, 40, 104, 255)),
                            (1.1, .3, (244, 244, 255, 255))):
            x0, y0 = 16 - math.cos(base) * 12.5, 16 - math.sin(base) * 12.5
            x1, y1 = 16 + math.cos(base) * 12.5, 16 + math.sin(base) * 12.5
            nx, ny = -math.sin(base), math.cos(base)
            poly(d, [(x0 + nx * w0, y0 + ny * w0), (x1 + nx * w1, y1 + ny * w1),
                     (x1 - nx * w1, y1 - ny * w1), (x0 - nx * w0, y0 - ny * w0)], col)
        for i in range(0, 13):
            t = i / 12.0
            px = 16 + math.cos(base) * (t * 25 - 12.5)
            py = 16 + math.sin(base) * (t * 25 - 12.5)
            nx, ny = -math.sin(base), math.cos(base)
            d.ellipse((px + nx * 4 - .9, py + ny * 4 - .9, px + nx * 4 + .9, py + ny * 4 + .9),
                      fill=(168, 108, 226, 255))
    d.polygon(star(16, 16, 4, 4.6, 1.4, 0), fill=(250, 252, 255, 255))


def icon_e(d):
    frame(d, (150, 96, 214, 255))
    for r in range(11, 0, -1):
        t = r / 11.0
        col = (int(18 + 26 * (1 - t)), int(8 + 10 * (1 - t)), int(38 + 30 * (1 - t)), 255)
        d.ellipse((16 - r, 16 - r, 16 + r, 16 + r), fill=col)
    for phase, col in ((0.0, (168, 108, 226, 255)), (math.pi, (120, 68, 190, 255))):
        for i in range(90):
            a = i / 90.0 * 3.4 * math.pi + phase
            r = 1.4 + (i / 90.0) * 9.0
            x, y = 16 + math.cos(a) * r, 16 + math.sin(a) * r
            d.ellipse((x - .7, y - .7, x + .7, y + .7), fill=col)
    d.ellipse((13, 13, 19, 19), fill=(6, 5, 14, 255))
    d.ellipse((15, 15, 17, 17), fill=(150, 96, 214, 255))
    d.ellipse((15, 15, 16, 16), fill=(246, 240, 255, 255))


def icon_r(d):
    frame(d, (150, 96, 214, 255))
    # dark rune ring with an upward energy spire = a self buff, not a projectile
    for rad, w, col in ((11.5, 1.4, (96, 44, 156, 255)), (8.5, 1.2, (170, 108, 232, 255))):
        for i in range(0, 121):
            a = i / 120.0 * math.pi * 2
            x, y = 16 + math.cos(a) * rad, 16 + math.sin(a) * rad
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=col)
    for i in range(6):
        a = i * 1.047 + .3
        x, y = 16 + math.cos(a) * 12.2, 16 + math.sin(a) * 12.2
        d.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(206, 156, 250, 255))
    for y in range(5, 27):
        t = (y - 5) / 22.0
        w = 2.6 * math.sin(t * math.pi) + .6
        col = (int(90 + 120 * (1 - t)), int(40 + 70 * (1 - t)), int(150 + 90 * (1 - t)), 255)
        d.line([(16 - w, y), (16 + w, y)], fill=col)
    d.polygon(star(16, 8, 4, 3.6, 1.2, 0), fill=(248, 240, 255, 255))
    d.ellipse((14, 20, 18, 24), fill=(24, 10, 40, 255))


def main():
    for fn in (gj_star, gj_slash, gj_orb, gj_ring, gj_plot_seal, gj_plot_burst, gj_plot_mote,
               gj_bubble, gj_splash, gj_drop):
        fn()
    icon("greninja_q", icon_q)
    icon("greninja_w", icon_w)
    icon("greninja_e", icon_e)
    icon("greninja_r", icon_r)
    print("wrote 10 effect sheets and 4 icons")


if __name__ == "__main__":
    main()

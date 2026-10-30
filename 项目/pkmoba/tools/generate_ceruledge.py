# -*- coding: utf-8 -*-
"""Ceruledge (苍炎刃鬼) art: blue-flame blades, ghost wisps and the Swords Dance ring.

Everything is drawn natively on the final pixel grid (64x64 sheets, 32x32 icons) so the result stays
hard-edged, then a 1 px dark outline is added around the silhouette.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba")
EFFECT = ROOT / "textures/effect"
ICON = ROOT / "textures/gui/skills"
PORTRAIT = ROOT / "textures/gui/pokemon"
N = 64
OUTLINE = (8, 10, 14, 240)

WHITE = (246, 251, 255, 255)
ICE = (176, 238, 255, 255)
BLUE = (92, 158, 246, 255)
DEEP = (44, 76, 190, 255)
VIOLET = (150, 96, 238, 255)
PLUM = (78, 38, 132, 255)
NIGHT = (24, 16, 42, 255)


def finish(img, name):
    px = img.load()
    edge = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    ep = edge.load()
    for y in range(N):
        for x in range(N):
            if px[x, y][3] > 90:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < N and 0 <= ny < N and px[nx, ny][3] > 90:
                    ep[x, y] = OUTLINE
                    break
    out = Image.alpha_composite(edge, img)
    out.save(EFFECT / (name + ".png"))
    return out


def band(cx, cy, r_out, thick, a0, a1, steps=44, taper=.7, a=.55):
    """A crescent arc: constant thickness, both radii shrink at the two tips."""
    outer, inner = [], []
    for i in range(steps + 1):
        u = i / steps
        ang = a0 + (a1 - a0) * u
        k = max(math.sin(math.pi * u) ** taper, .04)
        s = a + (1 - a) * k
        ro, ri = r_out * s, (r_out - thick) * s
        outer.append((cx + math.cos(ang) * ro, cy + math.sin(ang) * ro))
        inner.append((cx + math.cos(ang) * ri, cy + math.sin(ang) * ri))
    return outer + inner[::-1]


def star(x, y, r, inner, n=4, rot=0.0):
    return [(x + math.cos(rot + i * math.pi / n) * (r if i % 2 == 0 else inner),
             y + math.sin(rot + i * math.pi / n) * (r if i % 2 == 0 else inner)) for i in range(n * 2)]


def tongue(cx, cy, length, width, ang, curl=.4):
    pts = []
    for i in range(11):
        u = i / 10
        a = ang + curl * u
        x, y = cx + math.cos(a) * length * u, cy + math.sin(a) * length * u
        w = width * math.sin(math.pi * min(1, u * 1.05)) ** .75
        pts.append((x + math.sin(a) * w, y - math.cos(a) * w))
    for i in range(10, -1, -1):
        u = i / 10
        a = ang + curl * u
        x, y = cx + math.cos(a) * length * u, cy + math.sin(a) * length * u
        w = width * math.sin(math.pi * min(1, u * 1.05)) ** .75
        pts.append((x - math.sin(a) * w, y + math.cos(a) * w))
    return pts


def blade_bar(cx, cy, ang, length, w0, w1, col, d):
    """A tapered straight blade from the centre outward."""
    ca, sa = math.cos(ang), math.sin(ang)
    nx, ny = -sa, ca
    poly = [(cx + nx * w0, cy + ny * w0),
            (cx + ca * length + nx * w1, cy + sa * length + ny * w1),
            (cx + ca * length - nx * w1, cy + sa * length - ny * w1),
            (cx - nx * w0, cy - ny * w0)]
    d.polygon(poly, fill=col)


def slash(name, a0, span, cx, cy, r_out, flip=False):
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for off, thick, col in ((0, 9, DEEP), (-3, 6.5, VIOLET), (-6, 4.5, BLUE), (-8.5, 3, ICE)):
        d.polygon(band(cx, cy, r_out + off, thick, a0, a0 + span, taper=.9), fill=col)
    d.polygon(band(cx, cy, r_out - 9.5, 1.6, a0 + .28, a0 + span - .28, taper=1.1), fill=WHITE)
    for i, u in enumerate((.38, .62)):
        ang = a0 + span * u
        px_ = cx + math.cos(ang) * (r_out - 2)
        py_ = cy + math.sin(ang) * (r_out - 2)
        d.polygon(tongue(px_, py_, 10 - i * 2, 3.2, ang + (-.7 if flip else .7), .8),
                  fill=VIOLET if i % 2 else ICE)
    mid = a0 + span * .5
    d.polygon(star(cx + math.cos(mid) * (r_out - 7), cy + math.sin(mid) * (r_out - 7), 3.6, 1.3), fill=WHITE)
    return finish(img, name)


def ce_cross():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for ang in (.62, math.pi - .62):
        for k in range(2):
            a = ang + k * math.pi
            blade_bar(32, 34, a, 27, 5.5, .8, DEEP, d)
            blade_bar(32, 34, a, 25, 3.6, .6, BLUE, d)
            blade_bar(32, 34, a, 22, 1.8, .4, ICE, d)
    for ang in (.62, math.pi - .62):
        for k in range(2):
            a = ang + k * math.pi
            d.polygon(tongue(32 + math.cos(a) * 20, 34 + math.sin(a) * 20, 8, 2.6, a, .5),
                      fill=VIOLET)
    d.polygon(star(32, 34, 9, 2.8), fill=WHITE)
    d.polygon(star(32, 34, 5, 1.6), fill=ICE)
    return finish(img, "ce_cross")


def ce_blade():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    a0, span = -2.75, 2.0
    for r, col in ((27, DEEP), (25.5, VIOLET), (24, BLUE), (22.5, ICE)):
        d.polygon(band(30, 38, r, 6.5, a0, a0 + span, taper=.85), fill=col)
    d.polygon(band(30, 38, 21, 2.6, a0 + .18, a0 + span - .18, taper=1.0), fill=WHITE)
    for i in range(5):
        ang = a0 + .25 + i * .36
        d.ellipse((26 + math.cos(ang) * 27 - 1, 40 + math.sin(ang) * 27 - 1,
                   26 + math.cos(ang) * 27 + 1, 40 + math.sin(ang) * 27 + 1), fill=ICE)
    for i in range(3):
        d.polygon(star(10 + i * 5, 16 + i * 11, 2.4, .9), fill=VIOLET)
    return finish(img, "ce_blade")


def ce_shadow():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(32, 8), (41, 12), (46, 24), (46, 40), (41, 52), (32, 57), (23, 52), (18, 40),
               (18, 24), (23, 12)], fill=NIGHT)
    d.polygon([(32, 18), (38, 22), (39, 33), (36, 44), (32, 47), (28, 44), (25, 33), (26, 22)], fill=PLUM)
    for ang in (-2.0, -1.6, -1.2):
        d.polygon(tongue(32 + math.cos(ang) * 9, 30 + math.sin(ang) * 6, 16, 3.6, ang - math.pi, .9), fill=VIOLET)
    d.rectangle((27, 26, 29, 30), fill=(206, 240, 255, 255))
    d.rectangle((35, 26, 37, 30), fill=(206, 240, 255, 255))
    for i in range(4):
        d.polygon(star(9 + i * 4, 14 + i * 8, 2, .8), fill=(140, 110, 236, 220))
        d.polygon(star(55 - i * 4, 46 - i * 6, 2.2, .9), fill=(110, 170, 255, 210))
    return finish(img, "ce_shadow")


def ce_dance():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((4, 10, 59, 58), outline=DEEP, width=3)
    d.ellipse((7, 13, 56, 55), outline=BLUE, width=2)
    d.ellipse((10, 16, 53, 52), outline=VIOLET, width=1)
    for i in range(12):
        ang = i * math.tau / 12
        d.polygon(star(32 + math.cos(ang) * 27, 34 + math.sin(ang) * 23, 2.6, 1, 4, ang),
                  fill=ICE if i % 2 else WHITE)
    for i in range(6):
        ang = -1.9 + i * .62
        d.polygon(tongue(32 + math.cos(ang) * 22, 34 + math.sin(ang) * 19, 14 + (i % 3) * 3, 4,
                         ang - math.pi / 2, .9), fill=BLUE if i % 2 else VIOLET)
    d.polygon(star(32, 34, 8, 2.6), fill=(70, 84, 164, 210))
    return finish(img, "ce_dance")


def ce_drain():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for k in range(3):
        for i in range(9):
            t = i / 8
            x = 10 + t * 42 + math.sin(t * 3 + k) * 3
            y = 40 + math.sin(t * math.pi) * -14 + k * 7
            r = 2.6 - t * 1.5
            d.ellipse((x - r, y - r, x + r, y + r), fill=WHITE if t > .65 else (ICE if i % 2 else BLUE))
    for i in range(4):
        d.polygon(star(8 + i * 6, 14 + (i % 2) * 6, 2.2, .9), fill=(150, 96, 238, 210))
    return finish(img, "ce_drain")


def ce_ember():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for length, width, col in ((44, 11, DEEP), (38, 8.5, BLUE), (30, 6, ICE), (20, 3.6, WHITE)):
        d.polygon(tongue(32, 56, length, width, -math.pi / 2, .3), fill=col)
    for i in range(4):
        d.polygon(star(18 + i * 9, 16 + (i % 2) * 7, 2.2, .9), fill=VIOLET)
    return finish(img, "ce_ember")


# ---------------------------------------------------------------- icons (32x32 native)
def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), fill=(10, 12, 16, 255))
    d.rectangle((1, 1, 30, 30), outline=(78, 46, 138, 255))
    d.rectangle((2, 2, 29, 29), outline=(40, 56, 126, 255))
    for x, y in ((2, 2), (29, 2), (2, 29), (29, 29)):
        d.point((x, y), fill=(160, 218, 255, 255))
    return img, d


def icons():
    img, d = icon_frame()
    for r, col in ((14, DEEP), (11.5, VIOLET), (9, BLUE), (7, ICE)):
        d.polygon(band(17, 20, r, 4.5, -2.5, .35, steps=30, taper=.85), fill=col)
    d.polygon(star(22, 12, 3.2, 1.1), fill=WHITE)
    d.polygon(star(9, 9, 2, .8), fill=VIOLET)
    img.save(ICON / "ceruledge_q.png")

    img, d = icon_frame()
    d.polygon([(16, 4), (22, 7), (25, 15), (25, 22), (21, 27), (16, 28), (11, 27), (7, 22),
               (7, 15), (10, 7)], fill=NIGHT)
    d.polygon([(16, 10), (19, 13), (20, 18), (18, 23), (16, 25), (14, 23), (12, 18), (13, 13)], fill=PLUM)
    d.rectangle((13, 16, 14, 19), fill=(206, 240, 255, 255))
    d.rectangle((18, 16, 19, 19), fill=(206, 240, 255, 255))
    d.polygon([(7, 24), (3, 29), (10, 28)], fill=VIOLET)
    d.polygon([(25, 24), (29, 29), (22, 28)], fill=VIOLET)
    img.save(ICON / "ceruledge_w.png")

    img, d = icon_frame()
    for a0 in (-2.45, -.70):
        d.polygon(band(16, 17, 13, 5, a0, a0 + 1.3, steps=26, taper=.8), fill=DEEP)
        d.polygon(band(16, 17, 12, 4, a0 + .03, a0 + 1.27, steps=26, taper=.85), fill=BLUE)
        d.polygon(band(16, 17, 11, 2.5, a0 + .06, a0 + 1.24, steps=26, taper=.9), fill=ICE)
    d.polygon(star(16, 17, 4.5, 1.5), fill=WHITE)
    img.save(ICON / "ceruledge_e.png")

    img, d = icon_frame()
    d.ellipse((3, 3, 28, 28), outline=DEEP, width=2)
    d.ellipse((6, 6, 25, 25), outline=BLUE)
    d.ellipse((9, 9, 22, 22), outline=VIOLET)
    for i in range(8):
        ang = i * math.tau / 8
        d.polygon(star(16 + math.cos(ang) * 10, 16 + math.sin(ang) * 10, 2, .8, 4, ang), fill=ICE)
    d.polygon(star(16, 16, 4, 1.4), fill=(240, 250, 255, 255))
    img.save(ICON / "ceruledge_r.png")


def portrait(src):
    im = Image.open(src).convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a and r > 236 and g > 236 and b > 236:
                px[x, y] = (0, 0, 0, 0)
    im = im.crop(im.getbbox())
    scale = 56 / max(im.size)
    im = im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.NEAREST)
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    out.alpha_composite(im, ((64 - im.width) // 2, (64 - im.height) // 2))
    frame = ImageDraw.Draw(out)
    frame.rectangle((0, 0, 63, 63), outline=(78, 46, 138, 255))
    frame.rectangle((1, 1, 62, 62), outline=(34, 26, 58, 255))
    for x, y in ((2, 2), (61, 2), (2, 61), (61, 61)):
        frame.point((x, y), fill=(160, 218, 255, 255))
    out.save(PORTRAIT / "ceruledge.png")


if __name__ == "__main__":
    slash("ce_flare_l", 2.15, 1.55, 32, 36, 26)
    slash("ce_flare_r", -0.56, 1.55, 32, 36, 26, flip=True)
    ce_cross()
    ce_blade()
    ce_shadow()
    ce_dance()
    ce_drain()
    ce_ember()
    icons()
    portrait(r"D:/dsh项目/兴年小弟弟项目宝可梦/_tmp_937.png")
    print("wrote 8 effect sheets, 4 icons and the portrait")

# -*- coding: utf-8 -*-
"""Meowscarada 2.0 kit art: eight 64x64 sheets and four 32x32 skill icons.

Every sheet is drawn from primitives at 2x, then reduced with NEAREST so the result stays
hard-pixel. A 1px dark outline is added by dilating the alpha mask, matching the rest of the
project's skill art.
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/pkmoba/textures"
EFFECT = ROOT / "effect"
ICON = ROOT / "gui/skills"
S = 64
SS = 128
DEEP = (10, 12, 16, 240)


def canvas():
    return Image.new("RGBA", (SS, SS), (0, 0, 0, 0))


def outline(img):
    alpha = img.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    grown = alpha.filter(ImageFilter.MaxFilter(5))
    edge = Image.eval(Image.merge("L", (grown,)), lambda v: 255 if v > 0 else 0)
    ring = Image.new("L", img.size, 0)
    ring.paste(edge, (0, 0))
    ring = Image.composite(ring, Image.new("L", img.size, 0), alpha.point(lambda v: 255 if v == 0 else 0))
    base = Image.new("RGBA", img.size, (0, 0, 0, 0))
    base.putalpha(ring)
    base.paste(DEEP, (0, 0), ring)
    base.alpha_composite(img)
    return base


def finish(img, name):
    small = img.resize((S, S), Image.NEAREST)
    outlined = outline(small)
    outlined.save(EFFECT / (name + ".png"))
    return outlined


def poly(d, pts, fill):
    d.polygon(pts, fill=fill)


def leaf_points(cx, cy, rx, ry, angle, tip=1.0):
    pts = []
    for i in range(48):
        t = i / 48 * math.tau
        r = 1.0 if math.sin(t) >= 0 else tip
        x, y = math.cos(t) * rx, math.sin(t) * ry * (1 if math.sin(t) >= 0 else .55)
        ca, sa = math.cos(angle), math.sin(angle)
        pts.append((cx + x * ca - y * sa, cy + x * sa + y * ca))
    return pts


def star(cx, cy, outer, inner, points=5, rot=0.0):
    pts = []
    for i in range(points * 2):
        r = outer if i % 2 == 0 else inner
        ang = rot + i * math.pi / points
        pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
    return pts


# ---------------------------------------------------------------- Q 魔法叶
def blade(cx, cy, length, width, ang, teeth=7, serr=.16):
    """Serrated leaf outline: widest near the base, tapering to a sharp tip."""
    ax, ay = math.cos(ang), math.sin(ang)
    px, py = -ay, ax
    bx, by = cx - ax * length / 2, cy - ay * length / 2
    left, right = [], []
    steps = 72
    for i in range(steps + 1):
        u = i / steps
        w = width * math.sin(math.pi * u) ** .5 * (1 - .68 * u)
        w *= 1 + serr * math.sin(u * teeth * math.pi) * math.sin(math.pi * u)
        x, y = bx + ax * u * length, by + ay * u * length
        left.append((x + px * w, y + py * w))
        right.append((x - px * w, y - py * w))
    return left + right[::-1]


def offset_blade(cx, cy, length, width, ang, shift, scale):
    ax, ay = math.cos(ang), math.sin(ang)
    return blade(cx - ay * shift, cy + ax * shift, length, width * scale, ang)


def mw_leaf():
    img = canvas()
    d = ImageDraw.Draw(img)
    ang = math.radians(-38)
    ax, ay = math.cos(ang), math.sin(ang)
    base = (64 - ax * 46, 64 - ay * 46)
    # petiole
    d.line([(base[0] - ax * 12, base[1] - ay * 12), (base[0] + ax * 9, base[1] + ay * 9)],
           fill=(46, 116, 54, 255), width=5)
    poly(d, blade(64, 64, 108, 23, ang), (34, 118, 50, 252))
    poly(d, blade(64, 64, 104, 19.5, ang), (76, 190, 88, 255))
    poly(d, offset_blade(64, 64, 100, 19.5, ang, -6, .62), (146, 232, 132, 255))
    poly(d, offset_blade(64, 64, 92, 19.5, ang, -8, .3), (206, 255, 190, 235))
    # midrib
    d.line([(64 - ax * 44, 64 - ay * 44), (64 + ax * 46, 64 + ay * 46)], fill=(224, 255, 202, 245), width=3)
    # side veins
    for k in range(4):
        u0 = .2 + .17 * k
        for side in (1, -1):
            sx, sy = 64 + ax * (u0 * 96 - 48), 64 + ay * (u0 * 96 - 48)
            u1 = u0 + .13
            w = 21.5 * math.sin(math.pi * u1) ** .55 * (1 - .55 * u1) * .8
            ex, ey = 64 + ax * (u1 * 96 - 48) - ay * w * side, 64 + ay * (u1 * 96 - 48) + ax * w * side
            d.line([(sx, sy), (ex, ey)], fill=(96, 176, 88, 235), width=3)
    for x, y in ((26, 30), (100, 30), (30, 92), (98, 98)):
        poly(d, star(x, y, 5, 2, 4, math.pi / 4), (216, 255, 198, 200))
    return finish(img, "mw2_leaf")


# ---------------------------------------------------------------- W 急速折返
def mw_cut():
    img = canvas()
    d = ImageDraw.Draw(img)
    for a in (math.radians(38), math.radians(-38)):
        poly(d, leaf_points(64, 64, 12, 52, a, .70), (22, 74, 44, 250))
        poly(d, leaf_points(64, 64, 7, 46, a, .70), (74, 196, 96, 255))
        poly(d, leaf_points(64, 64, 2.6, 40, a, .70), (232, 255, 226, 255))
    d.ellipse((54, 54, 74, 74), fill=(255, 255, 240, 245))
    poly(d, star(64, 64, 26, 5, 4, math.pi / 4), (214, 255, 196, 190))
    for i in range(6):
        ang = i * 1.047 + .3
        poly(d, star(64 + math.cos(ang) * 46, 64 + math.sin(ang) * 46, 6, 2, 4, ang), (188, 250, 176, 200))
    return finish(img, "mw2_cut")


# ---------------------------------------------------------------- E 千变万花
def mw_flower():
    img = canvas()
    d = ImageDraw.Draw(img)
    for i in range(5):
        ang = -math.pi / 2 + i * math.tau / 5
        cx, cy = 64 + math.cos(ang) * 26, 64 + math.sin(ang) * 26
        poly(d, leaf_points(cx, cy, 17, 26, ang + math.pi / 2, .85), (232, 92, 168, 250))
        poly(d, leaf_points(cx, cy, 11, 20, ang + math.pi / 2, .85), (255, 150, 204, 255))
        poly(d, leaf_points(cx, cy, 4, 15, ang + math.pi / 2, .85), (255, 214, 236, 250))
    d.ellipse((44, 44, 84, 84), fill=(46, 138, 66, 255))
    d.ellipse((50, 50, 78, 78), fill=(92, 206, 112, 255))
    d.ellipse((56, 56, 72, 72), fill=(255, 214, 96, 255))
    d.ellipse((60, 60, 68, 68), fill=(255, 250, 214, 255))
    for i in range(5):
        ang = i * math.tau / 5 + .4
        poly(d, star(64 + math.cos(ang) * 40, 64 + math.sin(ang) * 40, 6, 2, 4, ang), (226, 255, 204, 200))
    return finish(img, "mw2_flower")


def mw_bloom():
    img = canvas()
    d = ImageDraw.Draw(img)
    # opening bloom: graded rings of petals thrown outward
    for ring, (radius, size, colour, mid) in enumerate((
            (46, 13, (228, 84, 160, 235), (255, 168, 210, 255)),
            (28, 15, (248, 118, 186, 250), (255, 206, 232, 255)))):
        count = 10 if ring == 0 else 7
        for i in range(count):
            ang = i * math.tau / count + ring * .3
            cx, cy = 64 + math.cos(ang) * radius, 64 + math.sin(ang) * radius
            poly(d, leaf_points(cx, cy, size * .62, size, ang + math.pi / 2, .8), colour)
            poly(d, leaf_points(cx, cy, size * .3, size * .7, ang + math.pi / 2, .8), mid)
    d.ellipse((48, 48, 80, 80), fill=(70, 176, 92, 255))
    d.ellipse((56, 56, 72, 72), fill=(255, 232, 150, 255))
    for i in range(8):
        ang = i * math.tau / 8
        poly(d, star(64 + math.cos(ang) * 56, 64 + math.sin(ang) * 56, 5, 2, 4, ang), (240, 255, 214, 210))
    return finish(img, "mw2_bloom")


def mw_petal():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, leaf_points(64, 60, 18, 34, math.radians(30), .7), (244, 118, 184, 250))
    poly(d, leaf_points(64, 60, 11, 27, math.radians(30), .7), (255, 176, 216, 255))
    poly(d, leaf_points(64, 60, 4, 20, math.radians(30), .7), (255, 232, 244, 255))
    for i in range(3):
        d.ellipse((22 + i * 12, 100 - i * 6, 27 + i * 12, 105 - i * 6), fill=(255, 200, 226, 180))
    return finish(img, "mw2_petal")


# ---------------------------------------------------------------- R 落英缤纷
def mw_storm():
    img = canvas()
    d = ImageDraw.Draw(img)
    for arm in range(3):
        base = arm * math.tau / 3
        for i in range(9):
            t = i / 8
            ang = base + t * 2.2
            radius = 9 + t * 54
            cx, cy = 64 + math.cos(ang) * radius, 64 + math.sin(ang) * radius
            size = 5 + t * 5
            pixel = (236, 104, 172, int(140 + 105 * (1 - t))) if (i + arm) % 2                 else (128, 226, 138, int(130 + 100 * (1 - t)))
            poly(d, leaf_points(cx, cy, size * .5, size, ang + math.pi / 2, .78), pixel)
    d.ellipse((52, 52, 76, 76), fill=(158, 238, 144, 170))
    d.ellipse((58, 58, 70, 70), fill=(236, 255, 214, 210))
    for i in range(5):
        ang = i * 1.256 + .2
        poly(d, star(64 + math.cos(ang) * 50, 64 + math.sin(ang) * 50, 5, 2, 4, ang), (242, 255, 214, 200))
    return finish(img, "mw2_storm")


def mw_ring():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((10, 10, 118, 118), outline=(44, 122, 58, 255), width=10)
    d.ellipse((16, 16, 112, 112), outline=(88, 208, 106, 255), width=5)
    d.ellipse((22, 22, 106, 106), outline=(212, 255, 190, 220), width=3)
    for i in range(14):
        ang = i * math.tau / 14
        cx, cy = 64 + math.cos(ang) * 64, 64 + math.sin(ang) * 64
        poly(d, leaf_points(cx, cy, 5, 11, ang + math.pi / 2, .8), (226, 255, 200, 220))
    return finish(img, "mw2_ring")


def mw_glow():
    img = canvas()
    d = ImageDraw.Draw(img)
    poly(d, star(64, 64, 46, 8, 4, 0), (196, 255, 176, 120))
    poly(d, star(64, 64, 30, 6, 4, math.pi / 4), (232, 255, 208, 190))
    d.ellipse((46, 46, 82, 82), fill=(255, 250, 210, 230))
    d.ellipse((56, 56, 72, 72), fill=(255, 255, 245, 255))
    for i in range(6):
        ang = i * 1.047 + .5
        poly(d, star(64 + math.cos(ang) * 50, 64 + math.sin(ang) * 50, 7, 2, 4, ang), (255, 220, 150, 200))
    return finish(img, "mw2_glow")


# ---------------------------------------------------------------- 32x32 icons
def icon_base():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((1, 1, 30, 30), fill=(26, 22, 34, 255), outline=(58, 40, 76, 255))
    d.rectangle((2, 2, 29, 29), outline=(28, 132, 62, 255))
    for x, y, dx, dy in ((2, 2, 1, 1), (29, 2, -1, 1), (2, 29, 1, -1), (29, 29, -1, -1)):
        d.point([(x, y), (x + dx, y), (x, y + dy)], fill=(132, 240, 150, 255))
    return img, d


def icon_q():
    img, d = icon_base()
    for i in range(4):
        ang = -math.pi / 2 + i * math.tau / 4
        cx, cy = 16 + math.cos(ang) * 6, 16 + math.sin(ang) * 6
        poly(d, leaf_points(cx, cy, 3.4, 7.6, ang + math.pi / 2, .8), (74, 200, 96, 255))
        poly(d, leaf_points(cx, cy, 1.5, 5, ang + math.pi / 2, .8), (206, 255, 186, 255))
    d.ellipse((13, 13, 19, 19), fill=(40, 128, 62, 255))
    img.resize((32, 32), Image.NEAREST).save(ICON / "meowscarada_q.png")


def icon_w():
    img, d = icon_base()
    for a in (math.radians(38), math.radians(-38)):
        poly(d, leaf_points(16, 16, 3.2, 12, a, .7), (28, 92, 48, 255))
        poly(d, leaf_points(16, 16, 1.6, 10, a, .7), (196, 255, 180, 255))
    d.ellipse((13, 13, 19, 19), fill=(255, 255, 236, 255))
    img.save(ICON / "meowscarada_w.png")


def icon_e():
    img, d = icon_base()
    for i in range(5):
        ang = -math.pi / 2 + i * math.tau / 5
        cx, cy = 16 + math.cos(ang) * 7.5, 16 + math.sin(ang) * 7.5
        poly(d, leaf_points(cx, cy, 4, 6, ang + math.pi / 2, .8), (238, 110, 176, 255))
        poly(d, leaf_points(cx, cy, 2, 4, ang + math.pi / 2, .8), (255, 208, 234, 255))
    d.ellipse((13, 13, 19, 19), fill=(72, 170, 88, 255))
    d.ellipse((15, 15, 17, 17), fill=(255, 234, 156, 255))
    img.save(ICON / "meowscarada_e.png")


def icon_r():
    img, d = icon_base()
    d.ellipse((7, 7, 25, 25), outline=(36, 104, 50, 255), width=2)
    d.ellipse((9, 9, 23, 23), outline=(118, 224, 124, 170))
    for k in range(5):
        ang = k * math.tau / 5 - math.pi / 2
        cx, cy = 16 + math.cos(ang) * 6.5, 16 + math.sin(ang) * 6.5
        poly(d, leaf_points(cx, cy, 2.1, 4.0, ang + math.pi / 2, .8),
             (250, 170, 210, 255) if k % 2 else (184, 244, 170, 255))
    d.ellipse((14, 14, 18, 18), fill=(255, 246, 208, 255))
    img.save(ICON / "meowscarada_r.png")


if __name__ == "__main__":
    for fn in (mw_leaf, mw_cut, mw_flower, mw_bloom, mw_petal, mw_storm, mw_ring, mw_glow,
               icon_q, icon_w, icon_e, icon_r):
        fn()
    print("wrote 8 sheets and 4 icons")

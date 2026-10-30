# -*- coding: utf-8 -*-
"""pkmoba skill art v3 - polar spirals, shaded water bubbles and clean pixel silhouettes.

Greninja: spiral water shuriken, dark vortex orb, wavy psychic ripple, bubbling water mass,
droplet, splash crown.  Meowscarada: flower bud bomb, night-slash crescent, gust ring,
grass blade wave, petal, sparkle.
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

S = 64
SS = 4
OUT = Path(r"D:/dsh项目/兴年小弟弟项目宝可梦/source/项目/pkmoba/src/main/resources/assets/pkmoba/textures/effect")

_yy, _xx = np.mgrid[0:S, 0:S]
PX = _xx + .5
PY = _yy + .5
R = np.hypot(PX - 32.0, PY - 32.0)
TH = np.arctan2(PY - 32.0, PX - 32.0)
BAYER = np.tile(np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0,
                (S // 4 + 1, S // 4 + 1))[:S, :S]
DARK = np.array([9, 12, 20], np.float32)

WHITE = np.array([255, 255, 255], np.float32)


def dist(cx, cy):
    return np.hypot(PX - cx, PY - cy)


def poly(pts):
    img = Image.new("L", (S * SS, S * SS), 0)
    ImageDraw.Draw(img).polygon([(x * SS, y * SS) for x, y in pts], fill=255)
    return np.asarray(img.resize((S, S), Image.BOX)) >= 110


def ellipse(cx, cy, rx, ry=None, rot=0.0):
    ry = rx if ry is None else ry
    img = Image.new("L", (S * SS, S * SS), 0)
    if rot:
        big = Image.new("L", (S * SS * 2, S * SS * 2), 0)
        ImageDraw.Draw(big).ellipse(((2 * SS + (cx - rx) * SS), (2 * SS + (cy - ry) * SS),
                                     (2 * SS + (cx + rx) * SS), (2 * SS + (cy + ry) * SS)), fill=255)
        big = big.rotate(rot, resample=Image.BICUBIC, center=(S * SS, S * SS))
        img = big.crop((S * SS // 2, S * SS // 2, S * SS * 3 // 2, S * SS * 3 // 2))
    else:
        ImageDraw.Draw(img).ellipse(((cx - rx) * SS, (cy - ry) * SS,
                                     (cx + rx) * SS, (cy + ry) * SS), fill=255)
    return np.asarray(img.resize((S, S), Image.BOX)) >= 110


def ring(cx, cy, r, thick):
    return np.abs(dist(cx, cy) - r) <= thick / 2.0


def capsule(a, b, w):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    ln2 = max(vx * vx + vy * vy, 1e-6)
    t = np.clip(((PX - ax) * vx + (PY - ay) * vy) / ln2, 0, 1)
    return np.hypot(PX - (ax + vx * t), PY - (ay + vy * t)) <= w / 2.0


def star4(cx, cy, r_out, r_in, rot=0.0, arms=4):
    pts = []
    for i in range(arms * 2):
        a = rot + i * math.pi / arms
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    return poly(pts)


def dilate(mask, k=1):
    out = mask.copy()
    for _ in range(k):
        pad = np.zeros_like(out)
        pad[1:, :] |= out[:-1, :]
        pad[:-1, :] |= out[1:, :]
        pad[:, 1:] |= out[:, :-1]
        pad[:, :-1] |= out[:, 1:]
        out = out | pad
    return out


def erode(mask, k=1):
    return ~dilate(~mask, k)


def ramp(t, stops):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,), np.float32)
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        sel = (t >= p0) & (t <= p1 if i == len(stops) - 2 else t < p1)
        f = np.clip((t - p0) / max(p1 - p0, 1e-6), 0, 1)
        for ch in range(3):
            out[..., ch] = np.where(sel, c0[ch] + (c1[ch] - c0[ch]) * f, out[..., ch])
    return out


def shade(t, stops, steps=0):
    tt = np.clip(t, 0, 1)
    if steps:
        tt = np.clip(np.floor(tt * (steps - 1) + BAYER) / (steps - 1), 0, 1)
    return ramp(tt, stops)


class Sprite:
    def __init__(self):
        self.rgb = np.zeros((S, S, 3), np.float32)
        self.a = np.zeros((S, S), np.float32)

    def add(self, mask, rgb, alpha=1.0):
        alpha = np.asarray(alpha, np.float32)
        if alpha.shape != (S, S):
            alpha = np.full((S, S), float(alpha), np.float32)
        m = mask & (alpha > 0)
        rgb = np.asarray(rgb, np.float32)
        if rgb.shape != (S, S, 3):
            rgb = np.broadcast_to(rgb, (S, S, 3)).astype(np.float32)
        self.rgb[m] = rgb[m]
        self.a[m] = np.maximum(self.a[m], 255.0 * alpha[m])

    def fill(self, mask, t, stops, steps=0, alpha=1.0):
        self.add(mask, shade(t, stops, steps), alpha)

    def outline(self, mask, color=DARK, alpha=248.0):
        edge = dilate(mask) & ~mask
        self.rgb[edge] = color
        self.a[edge] = np.maximum(self.a[edge], alpha)

    def save(self, name):
        arr = np.dstack([np.clip(self.rgb, 0, 255), np.clip(self.a, 0, 255)]).astype(np.uint8)
        Image.fromarray(arr, "RGBA").save(OUT / f"{name}.png")
        return name


def spherical(cx, cy, r, lx=-.34, ly=-.36):
    """0..1 value map for a lit sphere: 1 at the specular spot, 0 at the far rim."""
    hx, hy = cx + r * lx, cy + r * ly
    return np.clip(1.0 - np.hypot(PX - hx, PY - hy) / (r * 1.85), 0, 1)


# ───────────────── palettes ─────────────────
WATER = [(0.00, (6, 44, 88)), (0.22, (14, 104, 168)), (0.48, (48, 178, 232)),
         (0.74, (140, 234, 255)), (1.00, (255, 255, 255))]
WATER_DEEP = [(0.00, (4, 26, 62)), (0.30, (10, 78, 136)), (0.60, (34, 150, 208)),
              (0.84, (110, 220, 250)), (1.00, (240, 255, 255))]
DARKP = [(0.00, (6, 4, 18)), (0.26, (28, 12, 62)), (0.55, (72, 30, 132)),
         (0.80, (146, 88, 214)), (1.00, (232, 210, 255))]
PSY = [(0.00, (18, 10, 40)), (0.28, (62, 34, 118)), (0.55, (58, 156, 216)),
       (0.80, (170, 230, 255)), (1.00, (255, 255, 255))]
PINK = [(0.00, (104, 12, 66)), (0.26, (188, 34, 112)), (0.56, (250, 96, 158)),
        (0.82, (255, 178, 208)), (1.00, (255, 252, 253))]
GREEN = [(0.00, (12, 46, 22)), (0.28, (34, 106, 46)), (0.58, (108, 190, 62)),
         (0.84, (188, 240, 116)), (1.00, (252, 255, 208))]
DEEPGREEN = [(0.00, (8, 30, 16)), (0.30, (24, 74, 34)), (0.62, (58, 132, 52)),
             (0.86, (128, 200, 90)), (1.00, (220, 250, 180))]
VIOLET = [(0.00, (12, 6, 26)), (0.28, (44, 18, 84)), (0.58, (104, 56, 168)),
          (0.84, (176, 136, 240)), (1.00, (250, 244, 255))]
GOLD = [(0.00, (120, 66, 6)), (0.34, (214, 146, 22)), (0.68, (255, 214, 96)), (1.00, (255, 252, 220))]


def spiral(cx, cy, r0, r1, count, twist, phase, width_at):
    """Spiral arms; `width_at(r)` returns the half-width in pixels."""
    r = dist(cx, cy)
    th = np.arctan2(PY - cy, PX - cx)
    mask = np.zeros((S, S), bool)
    for k in range(count):
        wrapped = (th - phase - k * math.tau / count - twist * (r - r0)) % math.tau
        wrapped = np.minimum(wrapped, math.tau - wrapped)
        arc = wrapped * np.maximum(r, 1e-3)
        mask |= (arc <= width_at(r)) & (r >= r0) & (r <= r1)
    return mask


# ───────────────── Greninja ─────────────────
def gn_shuriken():
    """Spiral water shuriken: four liquid arms curling clockwise out of a bright core."""
    sp = Sprite()
    cx = cy = 32.0
    wr = lambda r: np.interp(r, [2.5, 9.0, 18.0, 26.0, 30.0], [3.2, 4.6, 3.0, 1.4, .35])
    body = spiral(cx, cy, 2.5, 30.0, 4, .105, .0, wr)
    t = np.clip(1.0 - (dist(cx, cy) - 2.0) / 28.0, 0, 1)
    sp.fill(body, .35 + t * .65, WATER, steps=6)
    # glowing leading edge of every arm
    lead = spiral(cx, cy, 4.0, 29.0, 4, .105, .30, lambda r: np.maximum(wr(r) * .42, .5))
    sp.add(lead & body, np.array([[240, 254, 255]]), .85)
    # thin outer haze ring + rotation arcs
    sp.add(spiral(cx, cy, 8.0, 31.0, 4, .105, -.55, lambda r: np.maximum(wr(r) * .18, .35)) & ~body,
           np.array([[150, 232, 255]]), .45)
    inner = spiral(cx, cy, 3.0, 12.0, 4, .105, .0, lambda r: wr(r) * .55)
    sp.add(inner, np.array([[255, 255, 255]]), .55)
    # core
    core = ellipse(cx, cy, 5.4)
    sp.fill(core, spherical(cx, cy, 5.4), WATER, steps=4)
    sp.add(ellipse(cx - 1.2, cy - 1.4, 2.0), WHITE, .95)
    sp.add(ring(cx, cy, 7.6, 1.2) & ~body, np.array([[255, 255, 255]]), .5)
    sp.fill(ellipse(cx, cy, 3.4), 1.0, WATER, steps=2)
    sp.add(ellipse(cx, cy, 2.2), WHITE, .95)
    for i in range(4):
        a = i * math.pi / 2 + .35
        x, y = cx + math.cos(a) * 33, cy + math.sin(a) * 33
        if 2 < x < 62 and 2 < y < 62:
            sp.fill(ellipse(x, y, 1.6), .9, WATER, steps=3)
    sp.outline(body | core)
    return sp.save("gn_shuriken")


def gn_dark():
    """Dark Pulse: a violet vortex sphere wrapped in spiral bands, trailing black orbs."""
    sp = Sprite()
    cx = cy = 32.0
    sphere = dist(cx, cy) <= 15.5
    sp.fill(sphere, spherical(cx, cy, 15.5) * .8 + .2, DARKP, steps=6)
    for twist, phase, tone, al in ((.17, .4, (196, 150, 255), .85), (.17, .4 + math.pi, (120, 74, 210), .75)):
        band = spiral(cx, cy, 3.0, 15.0, 2, twist, phase,
                      lambda r: np.interp(r, [3, 8, 15], [1.5, 2.5, .7]))
        sp.add(band & sphere, np.array([tone], np.float32), al)
    sp.fill(ellipse(cx, cy, 6.4), spherical(cx, cy, 6.4) * .25, DARKP, steps=3)
    sp.add(ring(cx, cy, 7.0, 1.3), np.array([[210, 172, 255]]), .6)
    sp.add(ring(cx, cy, 15.5 - .9, 1.2) & sphere, np.array([[160, 112, 232]]), .5)
    for i in range(3):
        a = i * math.tau / 3 + .7
        ox, oy = cx + math.cos(a) * 22, cy + math.sin(a) * 22
        orb = ellipse(ox, oy, 3.4)
        sp.fill(orb, spherical(ox, oy, 3.4), DARKP, steps=4)
        sp.add(ring(ox, oy, 3.4 - .6, .8) & orb, np.array([[188, 146, 255]]), .7)
        tail = capsule((ox - math.cos(a) * 3.2, oy - math.sin(a) * 3.2),
                       (ox - math.cos(a) * 7.0, oy - math.sin(a) * 7.0), 2.2) & ~orb
        sp.add(tail, np.array([[70, 34, 120]]), .6)
        sp.outline(orb)
    sp.add(star4(cx, cy, 8.0, 1.6, .5) & sphere, WHITE, .45)
    sp.outline(sphere)
    return sp.save("gn_dark")


def gn_psy():
    """Extrasensory: two broad psychic wavefronts closing on a violet eye."""
    sp = Sprite()
    cx = cy = 32.0
    th = np.arctan2(PY - cy, PX - cx)
    for i, (base, amp, thick) in enumerate(((24.5, .9, 3.0), (18.0, .7, 3.4))):
        mod = base + amp * np.sin(th * 3 + i * 1.1)
        m = np.abs(dist(cx, cy) - mod) <= thick / 2.0
        sp.fill(m, .72 - i * .16, PSY, steps=5)
        sp.add(m & (np.abs(dist(cx, cy) - mod) <= .55), np.array([[236, 250, 255]]), .4)
    lid = ellipse(cx, cy, 13.0, 9.0) & (np.abs(PY - cy) <= 9.0 - np.abs(PX - cx) * .30)
    sp.fill(lid, np.clip(1 - dist(cx - 1, cy - 1) / 15.0, 0, 1), PSY, steps=5)
    sp.add(ellipse(cx, cy + .2, 5.6, 6.6), np.array([[22, 12, 44]]), .95)
    sp.add(ellipse(cx, cy + .2, 5.6, 6.6) & (np.abs(PY - cy) > 3.4), np.array([[72, 168, 226]]), .55)
    sp.add(ellipse(cx - 1.4, cy - 1.8, 2.2), WHITE, 1.0)
    sp.add(ring(cx, cy + .2, 7.4, 1.0) & ~ellipse(cx, cy + .2, 5.6, 6.6),
           np.array([[200, 236, 255]]), .5)
    for i in range(6):
        a = i * math.tau / 6 + .4
        x, y = cx + math.cos(a) * 28, cy + math.sin(a) * 28
        if 2 < x < 62 and 2 < y < 62:
            sp.add(star4(x, y, 2.8, .9, a), np.array([[208, 242, 255]]), .8)
    sp.outline(lid)
    return sp.save("gn_psy")


def _water_sphere(sp, cx, cy, r, deep=0.0, rim=True, spec=True):
    m = dist(cx, cy) <= r
    sp.fill(m, spherical(cx, cy, r, -.3, -.34) * (1 - deep * .25), WATER if not deep else WATER_DEEP, steps=6)
    if rim:
        sp.add(m & (dist(cx, cy) > r - .95), np.array([[196, 246, 255]]), .55)
    if spec and r > 2.0:
        sp.add(ellipse(cx - r * .34, cy - r * .38, max(1.0, r * .2)), WHITE, .92)
        sp.add(ellipse(cx + r * .3, cy + r * .36, max(.8, r * .12)), np.array([[226, 252, 255]]), .45)
    return m


def gn_cannon():
    """Hydro Cannon: a churning mass of water bubbles with foam and spray."""
    sp = Sprite()
    blobs = [(32, 30, 11.5), (24, 36, 8.5), (41, 37, 9.0), (32, 45, 7.5),
             (27, 22, 6.5), (40, 24, 6.0), (33, 16, 4.6), (21, 28, 4.2)]
    body = np.zeros((S, S), bool)
    for cx, cy, r in blobs:
        body |= (dist(cx, cy) <= r)
    sp.fill(body, np.clip(spherical(32, 26, 20) * .9 + .1, 0, 1), WATER, steps=6)
    for cx, cy, r in blobs:
        _water_sphere(sp, cx, cy, r, deep=.15)
    # foam crest along the top and a couple of bubbles leaving the mass
    for i in range(4):
        x = 20 + i * 8
        sp.add(capsule((x, 14 + (i % 2) * 2), (x + 5, 12 + (i % 2) * 3), 1.6), WHITE, .65)
    for x, y, r in ((16, 20, 2.6), (48, 18, 2.2), (52, 30, 1.8), (14, 40, 2.0)):
        sp.outline(_water_sphere(sp, x, y, r))
    for i, (x, y, r) in enumerate(((46, 46, 2.4), (50, 52, 2.0), (43, 54, 1.6),
                                   (18, 48, 2.2), (15, 54, 1.5))):
        sp.outline(_water_sphere(sp, x, y, r))
    sp.add(star4(32, 26, 12.0, 2.4, .2) & body, WHITE, .3)
    sp.outline(body)
    return sp.save("gn_cannon")


def gn_drop():
    sp = Sprite()
    cx, cy = 32.0, 34.0
    body = ellipse(cx, cy, 11.0, 12.5) | poly([(cx, 6), (cx - 7.5, cy - 3), (cx + 7.5, cy - 3)])
    sp.fill(body, np.clip(1 - np.hypot(PX - (cx - 4), PY - (cy - 7)) / 21.0, 0, 1), WATER, steps=6)
    sp.add(ellipse(cx - 4.2, cy - 7.5, 3.0, 3.8), WHITE, .9)
    sp.add(ellipse(cx - 2.6, cy - 9.2, 1.5), WHITE, 1.0)
    sp.add(capsule((cx + 4, cy + 4), (cx + 6, cy + 9), 1.3), np.array([[190, 246, 255]]), .6)
    for i in range(3):
        _water_sphere(sp, cx + (i - 1) * 9, cy + 16 + i * 3, 1.8 + (i % 2) * .7)
    sp.outline(body)
    return sp.save("gn_drop")


def gn_splash():
    sp = Sprite()
    cx = cy = 32.0
    crown = ring(cx, cy, 14.5, 4.0)
    sp.fill(crown, np.clip(1 - np.abs(dist(cx, cy) - 14.5) / 6.0, 0, 1), WATER, steps=5)
    sp.add(ring(cx, cy, 21.5, 1.4), np.array([[126, 222, 252]]), .5)
    sp.add(ring(cx, cy, 25.0, 1.0), np.array([[86, 186, 238]]), .35)
    sp.add(ellipse(cx, cy, 5.6), np.array([[216, 250, 255]]), .38)
    sp.add(star4(cx, cy, 9.5, 2.0, .3), WHITE, .75)
    for i in range(10):
        a = i * math.tau / 10 + .15
        rr = 18 + (i % 3) * 4
        _water_sphere(sp, cx + math.cos(a) * rr, cy + math.sin(a) * rr, 1.6 + (i % 2) * .9)
    sp.outline(crown)
    return sp.save("gn_splash")


# ───────────────── Meowscarada ─────────────────
def _petal_shape(cx, cy, ang, r0, r1, half):
    pts = []
    steps = 9
    for k in range(steps + 1):
        r = r0 + (r1 - r0) * k / steps
        w = half * math.sin(math.pi * k / steps) ** .65
        pts.append((cx + math.cos(ang) * r - math.sin(ang) * w, cy + math.sin(ang) * r + math.cos(ang) * w))
    for k in range(steps, -1, -1):
        r = r0 + (r1 - r0) * k / steps
        w = half * math.sin(math.pi * k / steps) ** .65
        pts.append((cx + math.cos(ang) * r + math.sin(ang) * w, cy + math.sin(ang) * r - math.cos(ang) * w))
    return poly(pts)


def mw_bud():
    """Flower Trick: a seed pod cracking open while petals burst outward."""
    sp = Sprite()
    cx, cy = 32.0, 34.0
    for i in range(5):
        a = i * math.tau / 5 - math.pi / 2
        petal = _petal_shape(cx, cy, a, 5.0, 24.0, 7.2)
        t = np.clip(1 - (dist(cx, cy) - 6) / 24.0, 0, 1)
        sp.fill(petal, .35 + t * .65, PINK, steps=5)
        vein = capsule((cx + math.cos(a) * 8, cy + math.sin(a) * 8),
                       (cx + math.cos(a) * 20, cy + math.sin(a) * 20), 1.0) & petal
        sp.add(vein, np.array([[255, 244, 250]]), .5)
        sp.outline(petal)
    pod = dist(cx, cy) <= 9.5
    sp.fill(pod, spherical(cx, cy, 9.5), DEEPGREEN, steps=6)
    crack = poly([(cx - 1.6, cy - 10), (cx + 2.2, cy - 2), (cx - 1.2, cy + 6), (cx + 1.4, cy + 10)])
    sp.add(crack & pod, np.array([[255, 232, 120]]), .9)
    sp.add(spiral(cx, cy, 2.0, 9.0, 3, .2, .2, lambda r: np.interp(r, [2, 9], [1.3, .5])) & pod,
           np.array([[164, 222, 120]]), .45)
    for i in range(3):
        a = i * math.tau / 3 + .4
        sepal = poly([(cx + math.cos(a) * 8, cy + math.sin(a) * 8),
                      (cx + math.cos(a + .35) * 14, cy + math.sin(a + .35) * 14),
                      (cx + math.cos(a - .15) * 13, cy + math.sin(a - .15) * 13)])
        sp.add(sepal, np.array([[58, 132, 56]]), .9)
        sp.outline(sepal)
    sp.add(ellipse(cx - 3, cy - 3.5, 2.2), np.array([[214, 250, 176]]), .7)
    sp.outline(pod)
    for i in range(9):
        a = i * math.tau / 9 + .5
        x, y = cx + math.cos(a) * (17 + (i % 3) * 4), cy + math.sin(a) * (17 + (i % 3) * 4)
        if 2 < x < 62 and 2 < y < 62:
            sp.add(star4(x, y, 2.6, .8, a), np.array([[255, 226, 240]]), .8)
    return sp.save("mw_bud")


def mw_slash():
    """Night Slash: one heavy black-violet crescent with a white cutting edge."""
    sp = Sprite()
    body = talon(6.0, 60.0, 40.0, 14.0, -1.38, -.02, .95)
    sp.fill(body, np.clip((PY - 20) / 50, 0, 1) * .8 + .1, VIOLET, steps=5)
    edge = body & (np.abs(dist(6, 60) - 40.0) <= 1.6)
    sp.add(edge, np.array([[255, 246, 255]]), .85)
    inner = body & (np.abs(dist(6, 60) - 40.0) <= 4.0) & ~edge
    sp.add(inner, np.array([[198, 166, 250]]), .35)
    for rad, w, al in ((31.0, 4.5, .35), (48.0, 3.5, .22)):
        echo = talon(6.0, 60.0, rad, w, -1.2, .05, .6)
        sp.add(echo, np.array([[132, 96, 210]]), al)
    for i in range(7):
        a = -1.3 + i * .18
        x, y = 6 + math.cos(a) * (54 + (i % 2) * 3), 60 + math.sin(a) * (54 + (i % 2) * 3)
        if 2 < x < 62 and 2 < y < 62:
            sp.add(star4(x, y, 3.0, 1.0, a), np.array([[255, 226, 252]]), .85)
    sp.outline(body)
    return sp.save("mw_slash")


def mw_gust():
    """U-turn: three wind swooshes curling out of the caster, with chevron arrows."""
    sp = Sprite()
    body = np.zeros((S, S), bool)
    for rad, w, a0, a1, tone, al in ((21.0, 10.0, -2.5, -.5, .35, .9),
                                     (30.0, 8.0, -2.2, -.8, .55, .7),
                                     (13.0, 6.5, -2.4, -.7, .75, .6)):
        band = talon(28.0, 44.0, rad, w, a0, a1, .55)
        body |= band
        sp.fill(band, .5 + tone * .5, PSY, steps=4)
        sp.add(band & (np.abs(dist(28, 44) - rad) <= 1.2), WHITE, al * .8)
    for k in range(3):
        a = -2.2 + k * .55
        x, y = 28 + math.cos(a) * 36, 44 + math.sin(a) * 36
        if 6 < x < 60 and 6 < y < 60:
            chev = poly([(x, y), (x + 5.5, y + 1.0), (x + 1.0, y + 5.5)])
            sp.add(chev, np.array([[240, 248, 255]]), .85)
            sp.outline(chev)
    for i in range(8):
        a = -2.4 + i * .25
        r = 34 + (i % 3) * 4
        x, y = 28 + math.cos(a) * r, 44 + math.sin(a) * r
        if 3 < x < 61 and 3 < y < 61:
            sp.add(ellipse(x, y, 1.2), np.array([[214, 226, 250]]), .6)
    sp.outline(body)
    return sp.save("mw_gust")


def mw_blade():
    """Grassy Glide: a serrated leaf-shaped energy wave with fragments behind it."""
    sp = Sprite()
    blade = poly([(4, 52), (12, 30), (30, 12), (58, 6), (62, 12), (44, 32), (22, 50), (10, 56)])
    spikes = np.zeros((S, S), bool)
    for i in range(5):
        bx, by = 14 + i * 8, 44 - i * 7
        spikes |= poly([(bx, by), (bx - 7, by - 5), (bx - 1, by - 11)])
    body = blade | spikes
    sp.fill(body, np.clip((PX + PY) / 110.0, 0, 1) * .55 + np.clip((62 - PY) / 62, 0, 1) * .45, GREEN, steps=6)
    sp.add(capsule((10, 54), (60, 9), 1.8) & body, np.array([[236, 255, 200]]), .7)
    for i in range(4):
        x, y = 18 + i * 9, 46 - i * 9
        sp.add(capsule((x, y), (x + 4, y - 9), 1.1) & body, np.array([[176, 232, 120]]), .45)
    for i in range(4):
        x, y = 6 + i * 5, 22 - i * 6
        sp.add(star4(x, y, 3.0, 1.0, i), np.array([[214, 255, 168]]), .8)
    for i in range(3):
        sp.add(ellipse(50 - i * 3, 46 + i * 4, 1.4), np.array([[176, 226, 128]]), .6)
    sp.outline(body)
    return sp.save("mw_blade")


def mw_petal():
    sp = Sprite()
    cx, cy = 32.0, 32.0
    ca, sa = math.cos(-math.pi / 3), math.sin(-math.pi / 3)

    def p(u, v):
        return (cx + u * ca - v * sa, cy + u * sa + v * ca)

    pts = [p(24, 0)]
    for k in range(1, 7):
        pts.append(p(24 * math.cos(k * math.pi / 12), 12 * math.sin(k * math.pi / 12)))
    for k in range(6, 0, -1):
        pts.append(p(24 * math.cos(k * math.pi / 12), -12 * math.sin(k * math.pi / 12)))
    body = poly(pts)
    sp.fill(body, np.clip(1 - np.hypot(PX - 40, PY - 22) / 42.0, 0, 1) * .6 + .25, PINK, steps=6)
    sp.add(capsule(p(-22, 0), p(20, 0), 1.4) & body, np.array([[255, 244, 250]]), .55)
    sp.add(ellipse(40, 23, 2.2), WHITE, .85)
    sp.outline(body)
    return sp.save("mw_petal")


def mw_spark():
    sp = Sprite()
    cx = cy = 32.0
    body = star4(cx, cy, 27.0, 4.0, .25)
    sp.fill(body, np.clip(1 - dist(cx, cy) / 27.0, 0, 1), PINK, steps=5)
    sp.add(star4(cx, cy, 16.0, 2.2, .25), WHITE, .95)
    sp.add(ellipse(cx, cy, 4.2), WHITE, 1.0)
    for i in range(4):
        a = i * math.pi / 2 + .8
        sp.add(ellipse(cx + math.cos(a) * 23, cy + math.sin(a) * 23, 1.5), np.array([[255, 230, 244]]), .85)
    sp.outline(body)
    return sp.save("mw_spark")


def talon(cx, cy, rad, w, a0, a1, sharp=0.55):
    ang = np.arctan2(PY - cy, PX - cx)
    norm = (ang - a0) / (a1 - a0)
    inside = (norm >= 0) & (norm <= 1)
    prof = np.sin(np.pi * np.clip(norm, 0, 1)) ** sharp
    return inside & (np.abs(dist(cx, cy) - rad) <= np.maximum(w * prof / 2.0, 0.6))


SPRITES = [gn_shuriken, gn_dark, gn_psy, gn_cannon, gn_drop, gn_splash,
           mw_bud, mw_slash, mw_gust, mw_blade, mw_petal, mw_spark]


def contact(names, path, scale=4, cols=4, in_game=False):
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * S * scale, rows * S * scale), (26, 28, 36, 255))
    for i, n in enumerate(names):
        im = Image.open(OUT / f"{n}.png")
        if in_game:  # emulate Fx.sheet(): aura + bloom + core passes
            base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
            for k, sc, al in ((1.62, 1.0, .34), (1.18, 1.0, .30), (1.0, 1.0, 1.0)):
                layer = im.copy()
                if sc != 1.0:
                    layer = layer.resize((int(S * sc), int(S * sc)), Image.NEAREST)
                a = layer.getchannel("A").point(lambda v, al=al: int(v * al))
                layer.putalpha(a)
                ox = (S - layer.width) // 2
                oy = (S - layer.height) // 2
                base.alpha_composite(layer, (ox, oy)) if ox >= 0 and oy >= 0 else None
            im = base
        sheet.alpha_composite(im.resize((S * scale, S * scale), Image.NEAREST),
                              ((i % cols) * S * scale, (i // cols) * S * scale))
    sheet.save(path)
    return path


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    names = [f() for f in SPRITES]
    contact(names, r"D:/dsh项目/兴年小弟弟项目宝可梦/_tmp_v3.png")
    print("wrote", len(names))

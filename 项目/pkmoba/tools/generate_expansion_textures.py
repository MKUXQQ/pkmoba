"""Procedural original textures for the eight expansion species.

Two products:
  * `assets/.../textures/effect/<species>_[q|w|e|r]_{orb,slash,ring,field}.png`
    (128x128 per-move material sheets + species compatibility sheets)
  * `assets/.../textures/gui/skills/<species>_<q|w|e|r>.png`          (32x32 HUD icons)

The HUD blits icons as a native 32x32 region (SkillPresentation.ICON_SIZE == 32), so icons
are authored at 32x32 — the previous 64x64 exports were only showing their top-left quarter.

Iconography is per move, not per slot: each icon draws the actual move concept (aura sphere,
ice shards, jaws, dragon claw, moonblast, ...) in the move's type colour, over the same dark
rounded panel used by the launch species. Everything is drawn from primitives at 4x and
downsampled with LANCZOS, so there is no dependency on system fonts or external art.

Run:
    python tools/generate_expansion_textures.py            # write the 32 HUD icons (default)
    python tools/generate_expansion_textures.py --effects  # also write the 128x128 effect sheets
    python tools/generate_expansion_textures.py --check    # verify only, writes nothing
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageChops
import random

ROOT = Path(__file__).resolve().parents[1]
TEX = ROOT / "src/main/resources/assets/pkmoba/textures"
ICON_DIR = TEX / "gui/skills"
EFFECT_DIR = TEX / "effect"

S = 4                      # supersampling factor
N = 32 * S                 # internal canvas size
C = N // 2                 # centre
ICON = 32

TYPE = {
    "fire": (255, 122, 40),
    "fighting": (214, 74, 56),
    "ice": (154, 226, 255),
    "ghost": (168, 96, 232),
    "poison": (172, 74, 206),
    "psychic": (255, 118, 196),
    "fairy": (255, 172, 235),
    "rock": (206, 176, 104),
    "dark": (124, 94, 72),
    "dragon": (112, 132, 255),
    "ground": (216, 176, 116),
    "bug": (166, 204, 74),
    "steel": (196, 206, 220),
    "normal": (232, 232, 232),
    "water": (92, 166, 255),
    "grass": (104, 200, 120),
}

# species id -> (type colour, Chinese name, English name)
SPECIES = {
    "lucario": ("fighting", "路卡利欧", "Lucario"),
    "abomasnow": ("ice", "暴雪王", "Abomasnow"),
    "blaziken": ("fire", "火焰鸡", "Blaziken"),
    "gengar": ("ghost", "耿鬼", "Gengar"),
    "tyranitar": ("rock", "班基拉斯", "Tyranitar"),
    "garchomp": ("dragon", "烈咬陆鲨", "Garchomp"),
    "scizor": ("steel", "巨钳螳螂", "Scizor"),
    "gardevoir": ("psychic", "沙奈朵", "Gardevoir"),
}

# species id -> 4 moves, in Q/W/E/R order, matching ExpansionRoster.
MOVES = {
    "lucario": [("波导弹", "aura_sphere"), ("发劲", "palm"), ("神速", "speed"), ("近身战", "close_combat")],
    "abomasnow": [("冰砾", "ice_shard"), ("冰冻之风", "icy_wind"), ("白雾", "mist"), ("暴风雪", "blizzard")],
    "blaziken": [("火焰踢", "blaze_kick"), ("二连踢", "double_kick"), ("蓄能焰袭", "flame_charge"), ("闪焰冲锋", "flare_blitz")],
    "gengar": [("暗影球", "shadow_ball"), ("污泥炸弹", "sludge"), ("催眠术", "hypnosis"), ("食梦", "dream_eater")],
    "tyranitar": [("岩崩", "rock_slide"), ("咬碎", "crunch"), ("沙暴", "sandstorm"), ("尖石攻击", "stone_edge")],
    "garchomp": [("龙爪", "dragon_claw"), ("重踏", "stomp"), ("挖洞", "dig"), ("龙之俯冲", "dragon_dive")],
    "scizor": [("子弹拳", "bullet_punch"), ("连斩", "slash_combo"), ("急速折返", "uturn"), ("剑舞", "swords_dance")],
    "gardevoir": [("精神强念", "psychic"), ("月亮之力", "moonblast"), ("瞬间移动", "teleport"), ("预知未来", "future_sight")],
}


# --------------------------------------------------------------------------- primitives
def disc(d, color, cx, cy, r):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=color)


def ring(d, color, cx, cy, r, width):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=color, width=width)


def poly(d, color, pts):
    d.polygon(pts, fill=color)


def polyline(d, color, pts, width):
    d.line(pts, fill=color, width=width, joint="curve")


def arc(d, color, cx, cy, r, start, end, width):
    d.arc((cx - r, cy - r, cx + r, cy + r), start, end, fill=color, width=width)


def star(d, color, cx, cy, r_out, r_in, points=4, rot=-90):
    pts = []
    for i in range(points * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + i * 180.0 / points)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, fill=color)


def sparkle(d, color, cx, cy, r):
    polyline(d, color, [(cx - r, cy), (cx + r, cy)], max(2, r // 2))
    polyline(d, color, [(cx, cy - r), (cx, cy + r)], max(2, r // 2))


def snowflake(d, color, cx, cy, r, width):
    for k in range(6):
        a = math.radians(k * 60)
        x, y = cx + r * math.cos(a), cy + r * math.sin(a)
        polyline(d, color, [(cx, cy), (x, y)], width)
        # side barbs at 60% length
        bx, by = cx + r * 0.62 * math.cos(a), cy + r * 0.62 * math.sin(a)
        for side in (-1, 1):
            ba = a + side * math.radians(40)
            polyline(d, color, [(bx, by), (bx + r * 0.3 * math.cos(ba), by + r * 0.3 * math.sin(ba))], max(2, width // 2))


def crescent(d, color, cx, cy, r, shift):
    """Crescent = disc minus an offset disc (drawn in background colour)."""
    disc(d, color, cx, cy, r)
    bg = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    # caller paints the panel first; carve with the panel colour instead of transparency
    return bg


def swirl(d, color, cx, cy, r, turns, width, start=0.0):
    pts = []
    steps = 90
    for i in range(steps + 1):
        t = i / steps
        a = start + turns * math.tau * t
        rr = r * (0.18 + 0.82 * t)
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    polyline(d, color, pts, width)


def arrow_head(d, color, x, y, dx, dy, size):
    length = math.hypot(dx, dy) or 1.0
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    tip = (x + ux * size, y + uy * size)
    poly(d, color, [tip, (x + px * size * 0.62, y + py * size * 0.62),
                    (x - px * size * 0.62, y - py * size * 0.62)])


def fist(d, color, cx, cy, r, angle=0.0):
    """Simplified clenched fist: palm disc + four knuckles + thumb."""
    ca, sa = math.cos(angle), math.sin(angle)

    def rt(x, y):
        return (cx + x * ca - y * sa, cy + x * sa + y * ca)

    disc(d, color, cx, cy, int(r * 0.82))
    for i in range(4):
        kx = (-0.55 + i * 0.37) * r
        ky = -0.72 * r
        disc(d, color, *rt(kx, ky), int(r * 0.24))
    disc(d, color, *rt(-0.78 * r, 0.15 * r), int(r * 0.28))  # thumb


def boot(d, color, cx, cy, scale=1.0, flip=False):
    """Side-view boot: shin + foot; flip mirrors horizontally."""
    sx = -1 if flip else 1

    def pt(x, y):
        return (cx + sx * x * scale, cy + y * scale)

    poly(d, color, [pt(-2.2, -9.0), pt(1.4, -9.6), pt(2.2, -1.4), pt(2.6, 0.6),
                    pt(9.4, 1.0), pt(9.8, 4.6), pt(-2.6, 4.6), pt(-3.0, -2.0)])


def claw_slash(d, color, cx, cy, r, rot_deg, width):
    pts = []
    for i in range(13):
        t = i / 12
        a = math.radians(rot_deg + 60 * t)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    polyline(d, color, pts, width)
    # taper: small tip accent
    sparkle(d, color, pts[-1][0], pts[-1][1], width)


def frame_panel(type_key):
    """Shared rounded panel: dark navy body + type-coloured rim, matching launch icons."""
    tint = TYPE[type_key]
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((4, 4, N - 5, N - 5), radius=26, fill=(10, 14, 22, 233))
    d.rounded_rectangle((5, 5, N - 6, N - 6), radius=25, outline=(255, 255, 255, 34), width=2)
    d.rounded_rectangle((3, 3, N - 4, N - 4), radius=27, outline=(*tint, 150), width=4)
    return img, d


def shade(color, factor):
    return tuple(max(0, min(255, int(ch * factor))) for ch in color[:3])


def light(color, amount=0.55):
    return tuple(int(ch + (255 - ch) * amount) for ch in color[:3])


# --------------------------------------------------------------------------- glyphs
def g_aura_sphere(d, t):
    c = light(t, 0.35)
    for r, a in ((40, 40), (31, 70), (22, 110)):
        ring(d, (*t, a), C, C, r, 3)
    disc(d, (*t, 235), C, C, 16)
    disc(d, (255, 255, 255, 240), C, C, 8)
    for k in range(4):
        a = math.radians(k * 90 + 45)
        disc(d, light(t, 0.6), C + 34 * math.cos(a), C + 34 * math.sin(a), 3)


def g_palm(d, t):
    burst = light(t, 0.5)
    star(d, (*burst, 220), C, C, 46, 20, points=8, rot=-90)
    fist(d, (*light(t, 0.7), 255), C, C, 20)
    for i in range(3):  # motion streaks behind the palm
        polyline(d, (*t, 190), [(6, 26 + i * 9), (26, 22 + i * 9)], 4)


def g_speed(d, t):
    for i, y in enumerate((C - 22, C, C + 22)):
        polyline(d, (*light(t, 0.55), 255), [(14 + i * 3, y), (66, y)], 6)
        arrow_head(d, (*t, 255), 66, y, 1, 0, 16)
    for i in range(3):
        polyline(d, (*t, 120), [(20, C - 34 + i * 4), (44, C - 34 + i * 4)], 3)


def g_close_combat(d, t):
    star(d, (*light(t, 0.55), 235), C, C, 48, 22, points=6, rot=0)
    fist(d, (*t, 255), C - 14, C + 4, 17, angle=math.radians(35))
    fist(d, (*t, 255), C + 14, C - 4, 17, angle=math.radians(-145))
    sparkle(d, (255, 240, 210, 230), C, C - 26, 10)


def g_ice_shard(d, t):
    white = light(t, 0.6)
    shards = [((18, 96), (44, 30), (60, 92)), ((52, 100), (78, 34), (96, 96)), ((34, 108), (56, 62), (74, 108))]
    for i, pts in enumerate(shards):
        poly(d, (*white, 255), pts)
        poly(d, (*t, 220), [pts[0], pts[1], ((pts[1][0] + pts[2][0]) // 2, pts[2][1])])
    polyline(d, (*t, 180), [(12, 112), (116, 112)], 4)


def g_icy_wind(d, t):
    snowflake(d, (*light(t, 0.5), 255), C, C, 30, 5)
    for i, y in enumerate((C - 30, C - 10, C + 12, C + 32)):
        pts = [(10, y)]
        for k in range(1, 8):
            pts.append((10 + k * 16, y + (6 if k % 2 else -6)))
        polyline(d, (*t, 200 - i * 20), pts, 4)


def g_mist(d, t):
    pale = light(t, 0.55)
    for i, (cx, cy, rx, ry) in enumerate(((C - 12, C - 12, 34, 18), (C + 12, C + 2, 34, 18), (C - 4, C + 20, 30, 16))):
        d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=(*pale, 150 - i * 25))
    polyline(d, (*t, 90), [(18, C - 26), (46, C - 34)], 3)
    polyline(d, (*t, 70), [(84, C + 30), (108, C + 24)], 3)


def g_blizzard(d, t):
    snowflake(d, (*light(t, 0.6), 255), C, C, 40, 7)
    snowflake(d, (*t, 200), C, C, 22, 4)
    swirl(d, (*t, 120), C, C, 44, 1.1, 4, start=0.6)
    for k in range(8):
        a = math.radians(k * 45)
        disc(d, (*light(t, 0.7), 220), C + 42 * math.cos(a), C + 42 * math.sin(a), 3)


def g_blaze_kick(d, t):
    boot(d, (*t, 255), C - 6, C - 6, scale=2.1, flip=True)
    for i in range(5):
        y = C + 14 + i * 4
        polyline(d, (*light(t, 0.3), 220 - i * 30), [(10 + i * 3, y), (38 + i * 6, y - 6)], 4)
    sparkle(d, (255, 236, 170, 230), C + 30, C - 34, 9)


def g_double_kick(d, t):
    boot(d, (*t, 255), 28, C - 6, scale=1.5)
    boot(d, (*light(t, 0.35), 235), 92, C - 6, scale=1.5, flip=True)
    for i in range(2):
        arc(d, (*light(t, 0.5), 220), C, C, 26 + i * 12, 200 + i * 12, 340 - i * 12, 5)


def g_flame_charge(d, t):
    flame = (*t, 255)
    core = light(t, 0.6)
    poly(d, flame, [(C + 22, C - 34), (C + 40, C - 6), (C + 30, C + 30), (C + 6, C + 34), (C - 2, C + 10)])
    poly(d, (*core, 255), [(C + 22, C - 12), (C + 30, C + 6), (C + 24, C + 24), (C + 12, C + 20), (C + 12, C + 2)])
    for i in range(3):
        y = C - 20 + i * 18
        polyline(d, (*light(t, 0.2), 200), [(12, y), (C - 6, y)], 4)
        arrow_head(d, (*light(t, 0.2), 200), C - 6, y, 1, 0, 10)


def g_flare_blitz(d, t):
    flame = (*t, 255)
    core = light(t, 0.55)
    poly(d, flame, [(C - 40, C + 26), (C - 8, C - 34), (C + 20, C + 6), (C + 44, C - 20), (C + 22, C + 32)])
    poly(d, (*core, 255), [(C - 4, C + 18), (C - 2, C - 12), (C + 14, C + 10), (C + 22, C - 2), (C + 12, C + 22)])
    for i in range(4):
        polyline(d, (*light(t, 0.25), 220 - i * 35), [(8, C + 20 + i * 8), (C - 38, C + 16 + i * 6)], 5)


def g_shadow_ball(d, t):
    dark = shade(t, 0.45)
    for k in range(10):  # jagged shadow aura
        a = math.radians(k * 36)
        r1, r2 = 18, 34 + (6 if k % 2 else 0)
        poly(d, (*dark, 200), [(C + r1 * math.cos(a - .18), C + r1 * math.sin(a - .18)),
                               (C + r2 * math.cos(a), C + r2 * math.sin(a)),
                               (C + r1 * math.cos(a + .18), C + r1 * math.sin(a + .18))])
    disc(d, (*t, 255), C, C, 18)
    disc(d, (*dark, 255), C + 4, C + 3, 10)
    for i in range(3):
        a = math.radians(200 + i * 55)
        polyline(d, (*light(t, 0.3), 200), [(C + 22 * math.cos(a), C + 22 * math.sin(a)),
                                            (C + 40 * math.cos(a), C + 40 * math.sin(a))], 3)


def g_sludge(d, t):
    dark = shade(t, 0.55)
    disc(d, (*t, 255), C - 4, C - 6, 20)
    disc(d, (*dark, 255), C + 6, C, 14)
    for dx, dy, r in ((-14, 26, 7), (2, 32, 9), (18, 24, 6)):
        disc(d, (*light(t, 0.2), 240), C + dx, C + dy, r)
        polyline(d, (*light(t, 0.2), 220), [(C + dx, C + dy - r), (C + dx, C + dy - r - 10)], 3)
    for dx, dy in ((-26, -22), (26, -20), (34, 4)):
        disc(d, (*t, 200), C + dx, C + dy, 4)


def g_hypnosis(d, t):
    for i in range(4, 0, -1):
        swirl(d, (*t, 90 + i * 30), C, C, 10 + i * 9, 1.0, 4, start=i * 0.7)
    disc(d, (*light(t, 0.5), 230), C, C, 5)
    polyline(d, (*light(t, 0.4), 200), [(C + 28, 24), (C + 34, 52)], 3)
    disc(d, (*light(t, 0.4), 235), C + 34, 52, 6)


def g_dream_eater(d, t):
    # crescent moon
    disc(d, (*light(t, 0.55), 255), C - 10, C - 10, 24)
    disc(d, (10, 14, 22, 255), C + 2, C - 20, 22)
    # devouring mouth
    poly(d, (*shade(t, 0.4), 255), [(C - 2, C + 12), (C + 34, C + 4), (C + 34, C + 30), (C + 2, C + 28)])
    for k in range(4):
        x = C + 6 + k * 8
        poly(d, (245, 245, 255, 255), [(x, C + 8), (x + 5, C + 16), (x + 10, C + 8)])
    for k in range(3):
        disc(d, (*light(t, 0.6), 210), C - 26 + k * 8, C + 26 - k * 4, 3)


def g_rock_slide(d, t):
    polyline(d, (*shade(t, 0.6), 200), [(12, 22), (26, 12), (40, 24), (54, 10)], 3)
    for cx, cy, r in ((34, 40, 11), (62, 30, 14), (92, 44, 10)):
        poly(d, (*t, 255), [(cx - r, cy), (cx - r // 2, cy - r), (cx + r // 2, cy - r),
                            (cx + r, cy), (cx + r // 2, cy + r), (cx - r // 2, cy + r)])
        polyline(d, (*light(t, 0.2), 220), [(cx - r + 3, cy + 2), (cx + r - 4, cy + 2)], 2)
    for k in range(3):
        y = C - 26 + k * 10
        polyline(d, (*light(t, 0.3), 170), [(4, y), (22, y)], 4)
    for k in range(5):
        disc(d, (*light(t, 0.2), 130), 20 + k * 22, 108 - (k % 2) * 6, 4)


def g_crunch(d, t):
    dark = shade(t, 0.5)
    for flip in (-1, 1):  # upper and lower jaws, teeth face inward
        poly(d, (*dark, 255), [(C-39,C+flip*9),(C-24,C+flip*34),
                              (C+24,C+flip*34),(C+39,C+flip*9)])
        polyline(d, (*light(t,.35),255), [(C-39,C+flip*9),(C-24,C+flip*34),
                                           (C+24,C+flip*34),(C+39,C+flip*9)],3)
        for k in range(4):
            x = C - 28 + k * 16
            poly(d, (250, 246, 235, 255), [(x,C+flip*18),(x+10,C+flip*18),
                                                          (x+5,C+flip*3)])
    star(d, (*light(t, 0.4), 200), C, C, 14, 6, points=6)


def g_sandstorm(d, t):
    for i, r in enumerate((40, 30, 20)):
        swirl(d, (*t, 110 + i * 50), C, C, r, 0.9, 5, start=i * 0.8)
    for k in range(12):
        a = math.radians(k * 30)
        rr = 18 + (k % 4) * 8
        disc(d, (*light(t, 0.35), 200), C + rr * math.cos(a), C + rr * math.sin(a), 2 + k % 2)


def g_stone_edge(d, t):
    polyline(d, (*shade(t, 0.6), 230), [(8, 104), (118, 104)], 5)
    spikes = [(20, 104, 10, 58), (44, 104, 9, 40), (66, 104, 11, 30), (92, 104, 9, 46)]
    for x, base, w, h in spikes:
        poly(d, (*t, 255), [(x - w, base), (x, base - h), (x + w, base)])
        poly(d, (*light(t, 0.35), 255), [(x - w // 2, base), (x, base - h + 6), (x, base)])
    for k in range(3):
        disc(d, (*light(t, 0.4), 180), 16 + k * 38, 24 - k * 4, 3)


def g_dragon_claw(d, t):
    for i, rot in enumerate((196, 214, 232)):
        claw_slash(d, (*t, 255 - i * 20), C + 6, C + 4, 40 + i * 2, rot, 7)
    for i in range(3):
        sparkle(d, (*light(t, 0.5), 210), 24 + i * 10, 92 - i * 16, 4)


def g_stomp(d, t):
    boot(d, (*t, 255), C + 4, C - 14, scale=1.6)
    ring(d, (*light(t, 0.3), 200), C, C + 18, 34, 4)
    ring(d, (*light(t, 0.3), 120), C, C + 18, 44, 3)
    for i in range(4):
        a = math.radians(30 + i * 40)
        polyline(d, (*light(t, 0.2), 190), [(C + 30 * math.cos(a), C + 18 + 30 * math.sin(a)),
                                            (C + 48 * math.cos(a), C + 18 + 48 * math.sin(a))], 4)


def g_dig(d, t):
    polyline(d, (*shade(t, 0.55), 240), [(10, 78), (30, 62), (54, 70), (78, 56)], 6)
    d.ellipse((C - 30, C + 4, C + 30, C + 34), fill=(20, 16, 12, 255))
    d.ellipse((C - 26, C + 8, C + 26, C + 26), fill=(8, 7, 6, 255))
    for i in range(3):
        arrow_head(d, (*t, 230), C - 6 + i * 14, 44, 0, 1, 12)
    for k in range(4):
        disc(d, (*light(t, 0.2), 200), 28 + k * 22, 96 - (k % 2) * 8, 4)


def g_dragon_dive(d, t):
    poly(d, (*t, 255), [(C - 26, C - 34), (C + 10, C - 22), (C + 26, C - 2), (C + 4, C + 6),
                        (C + 22, C + 14), (C - 8, C + 18), (C - 18, C - 2)])
    poly(d, (*shade(t, 0.5), 255), [(C + 2, C - 12), (C + 14, C - 4), (C + 4, C + 4)])  # eye socket
    disc(d, (255, 250, 220, 255), C + 6, C - 8, 3)
    for i in range(3):
        polyline(d, (*light(t, 0.35), 200 - i * 40), [(8 + i * 10, C + 22 + i * 10), (44 + i * 8, C + 6 + i * 8)], 5)


def g_bullet_punch(d, t):
    fist(d, (*light(t, 0.5), 255), C - 4, C + 2, 19)
    for i in range(4):
        y = C - 26 + i * 15
        polyline(d, (*t, 220 - i * 20), [(6, y), (30, y)], 4)
    disc(d, (*t, 255), 98, C - 28, 7)
    polyline(d, (*light(t, 0.5), 200), [(88, C - 26), (70, C - 20)], 3)


def g_slash_combo(d, t):
    claw_slash(d, (*t, 255), C - 6, C - 6, 42, 190, 7)
    claw_slash(d, (*light(t, 0.35), 255), C + 6, C + 4, 38, 205, 7)
    for i in range(4):
        sparkle(d, (*light(t, 0.3), 220), 22 + i * 24, 30 + i * 18, 4)


def g_uturn(d, t):
    pts = [(24, 40), (24, 78), (74, 82), (96, 62)]
    polyline(d, (*t, 255), pts, 7)
    arrow_head(d, (*light(t, 0.4), 255), 96, 62, 1, -1, 16)
    polyline(d, (*light(t, 0.3), 200), [(12, 26), (36, 26)], 4)
    polyline(d, (*light(t, 0.3), 160), [(16, 16), (36, 16)], 4)


def g_swords_dance(d, t):
    steel = light(t, 0.35)
    for flip in (-1, 1):
        x = C + flip * 16
        poly(d, (*steel, 255), [(x - 4, C + 30), (x + 4, C + 30), (x + 8, C - 30), (x, C - 38)])
        poly(d, (*shade(t, 0.4), 255), [(x - 12, C + 34), (x + 12, C + 34), (x, C + 26)])
    for k in range(4):
        sparkle(d, (*light(t, 0.7), 230), 22 + k * 22, 26 + (k % 2) * 8, 4)


def g_psychic(d, t):
    # eye
    d.ellipse((C - 34, C - 18, C + 34, C + 18), fill=(255, 250, 255, 245))
    disc(d, (*t, 255), C, C, 13)
    disc(d, (20, 10, 30, 255), C, C, 6)
    disc(d, (255, 255, 255, 255), C - 4, C - 4, 3)
    for i, r in enumerate((26, 34, 42)):
        arc(d, (*light(t, 0.2), 200 - i * 45), C, C, r, 200, 340, 4)
        arc(d, (*light(t, 0.2), 200 - i * 45), C, C, r, 20, 160, 4)


def g_moonblast(d, t):
    disc(d, (*light(t, 0.5), 245), C - 10, C - 12, 26)
    disc(d, (10, 14, 22, 255), C + 4, C - 20, 24)
    for k in range(5):
        a = math.radians(-40 + k * 62)
        r = 34
        sparkle(d, (*t, 230), C + r * math.cos(a), C + r * math.sin(a), 6)
    ring(d, (*t, 90), C, C, 46, 2)


def g_teleport(d, t):
    for i, off in enumerate((0, 14, 26)):  # afterimages fading
        alpha = 220 - i * 65
        disc(d, (*t, max(30, alpha)), 40 + off, C - 4, 24 - i * 4)
    for k in range(14):  # dissolving pixels
        x = 20 + (k * 13) % 84
        y = 16 + (k * 29) % 84
        s = 3 + (k % 3) * 2
        d.rectangle((x, y, x + s, y + s), fill=(*light(t, 0.5), 200))
    for k in range(3):
        sparkle(d, (255, 240, 255, 220), 24 + k * 30, 96 - k * 8, 4)


def g_future_sight(d, t):
    # eye of foresight + future clock arc
    arc(d, (*light(t, 0.5), 255), C, C, 38, 0, 360, 4)
    polyline(d, (*t, 220), [(C, C), (C, C - 24)], 4)
    polyline(d, (*light(t, 0.3), 220), [(C, C), (C + 18, C + 10)], 3)
    for k in range(8):
        a = math.radians(k * 45)
        disc(d, (*light(t, 0.4), 180), C + 46 * math.cos(a), C + 46 * math.sin(a), 3)
    sparkle(d, (*t, 235), C + 28, C - 30, 8)


GLYPHS = {key: value for key, value in globals().items() if key.startswith("g_")}
MOVE_GLYPH = {move[1]: move[0] for moves in MOVES.values() for move in moves}


# --------------------------------------------------------------------------- outputs
# Move typing, not species typing: aura is blue, sludge poison, moonblast fairy,
# ground attacks ochre, and Scizor's cutting moves green against silver steel.
MOVE_TYPES = {
    'lucario': ('water', 'fighting', 'normal', 'fighting'),
    'abomasnow': ('ice', 'ice', 'ice', 'ice'),
    'blaziken': ('fire', 'fighting', 'fire', 'fire'),
    'gengar': ('ghost', 'poison', 'psychic', 'psychic'),
    'tyranitar': ('rock', 'dark', 'ground', 'rock'),
    'garchomp': ('dragon', 'ground', 'ground', 'dragon'),
    'scizor': ('steel', 'bug', 'bug', 'steel'),
    'gardevoir': ('psychic', 'fairy', 'psychic', 'psychic'),
}


def render_icon(species: str, index: int) -> Image.Image:
    type_key = MOVE_TYPES[species][index]
    tint = TYPE[type_key]
    img, _ = frame_panel(type_key)
    # Original semantic silhouettes become lit relief illustrations rather than flat signs.
    glyph = Image.new('RGBA', (N, N))
    GLYPHS['g_' + MOVES[species][index][1]](ImageDraw.Draw(glyph), tint)
    mask = glyph.getchannel('A')
    glow = Image.new('RGBA', (N, N), (*tint, 0))
    glow.putalpha(mask.filter(ImageFilter.GaussianBlur(9)).point(lambda a: int(a * .48)))
    img = Image.alpha_composite(img, glow)
    # Finely grained, directional lighting preserves the icon's dark negative space.
    pixels = glyph.load()
    rng = random.Random(species + str(index))
    for y in range(N):
        for x in range(N):
            r, g, b, a = pixels[x, y]
            if a:
                v = .72 + .38 * (1 - y / N) + .10 * math.sin((x + y) * .17) + rng.uniform(-.035, .035)
                pixels[x, y] = (min(255, int(r*v)), min(255, int(g*v)), min(255, int(b*v)), a)
    shadow = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    shadow.putalpha(ImageChops.offset(mask, 3, 5).point(lambda a: int(a * .70)))
    img = Image.alpha_composite(img, shadow)
    img = Image.alpha_composite(img, glyph)
    # A top-left bevel only on the silhouette edges, never a universal slot symbol.
    edge = ImageChops.subtract(mask, ImageChops.offset(mask, 2, 3))
    bevel = Image.new('RGBA', (N, N), (*light(tint, .8), 0))
    bevel.putalpha(edge.point(lambda a: int(a * .65)))
    img = Image.alpha_composite(img, bevel)
    return img.resize((ICON, ICON), Image.Resampling.LANCZOS)


def render_effect_sheet(species: str, kind: str) -> Image.Image:
    """Material-painted transparent sheets, distinct silhouettes for all eight species.

    Also accepts <q|w|e|r>_<kind>: per-move colour avoids pink moonlight/sludge
    inheriting the wrong species palette. All noise is deterministic and edge-faded.
    """
    slot = None
    if '_' in kind:
        prefix, kind = kind.split('_', 1)
        slot = 'qwer'.index(prefix)
    base_type = MOVE_TYPES[species][slot] if slot is not None else SPECIES[species][0]
    if species == 'lucario' and slot is None:
        base_type = 'water'
    color = TYPE[base_type]
    img = Image.new('RGBA', (128, 128))
    rng = random.Random(species + str(slot) + kind)
    pix = img.load()
    for y in range(128):
        for x in range(128):
            dx, dy = (x-63.5)/60, (y-63.5)/60
            r, a = math.hypot(dx, dy), math.atan2(dy, dx)
            grain = rng.uniform(-1, 1)
            flow = math.sin(a*7 + r*21) * .5 + math.sin(a*13-r*33)*.25
            if kind == 'orb':
                density = max(0, 1-r)**.75
                if species == 'abomasnow':
                    density *= .25 + .75 * max(0, math.cos(a*6))**5
                elif species in ('tyranitar', 'garchomp'):
                    density = max(0, 1-r/(.77+.10*math.sin(a*5)))**.25
                elif species == 'scizor':
                    density *= .45 + .55 * max(0, math.cos(a*4))
                else:
                    density *= .68 + .22*flow
            elif kind == 'slash':
                bend = .27 * math.sin(dx*2.8)
                density = max(0, 1-abs(dy-bend)/(.09+.16*(1-abs(dx)))) if abs(dx)<.95 else 0
                density *= max(0, 1-abs(dx))**.35
            elif kind == 'ring':
                edge = .78 + .025*math.sin(a*12)
                density = math.exp(-((r-edge)/.045)**2) * (.68+.32*math.sin(a*9)**2)
                density += .18*math.exp(-((r-.63)/.024)**2)
            else:
                density = max(0, 1-r)**.8 * (.25+.18*flow)
                density += .42*math.exp(-((r-.76-.045*math.sin(a*6))/.04)**2)
            fade = min(1, max(0, (1-r)*12))
            alpha = int(255 * min(1, max(0, density*(1+grain*.12))) * fade)
            hot = max(0, min(.88, density*.6 + flow*.12 + grain*.06))
            rgb = tuple(int(c + (255-c)*hot) for c in color)
            pix[x,y] = (*rgb, alpha)
    d = ImageDraw.Draw(img)
    if kind in ('orb', 'field'):
        if species == 'abomasnow':
            snowflake(d, (*light(color,.65),210), 64,64,39 if kind=='orb' else 48,2)
        elif species in ('tyranitar', 'garchomp'):
            for k in range(5):
                a = k*math.tau/5
                cx,cy = 64+25*math.cos(a),64+25*math.sin(a)
                poly(d, (*shade(color,.7),180), [(cx-7,cy+8),(cx-2,cy-12),(cx+8,cy+4)])
                polyline(d, (*light(color,.5),210), [(cx-2,cy-12),(cx+1,cy+1),(cx+8,cy+4)],2)
        elif species == 'gengar':
            swirl(d, (*light(color,.5),180),64,64,35,1.3,3)
        elif species == 'gardevoir':
            star(d, (*light(color,.8),220),64,64,30,7,points=4)
        elif species == 'scizor':
            for k in range(3):
                claw_slash(d, (*light(color,.8),210),60,64,30+k*5,200,2)
        elif species == 'blaziken':
            for k in range(5):
                poly(d, (*light(color,.45),180),[(30+k*13,91),(38+k*10,28+k%2*15),(46+k*10,88)])
        else:
            for k in range(3):
                arc(d, (*light(color,.7),210),64,64,24+k*9,k*90,k*90+180,2)
    return img


def ascii_preview(img: Image.Image, cols: int = 16) -> str:
    """Coarse luminance preview used by --check to prove the glyph is non-empty/legible."""
    small = img.convert("RGBA").resize((cols, cols), Image.Resampling.BOX)
    ramp = " .:-=+*#%@"
    rows = []
    for y in range(cols):
        row = ""
        for x in range(cols):
            r, g, b, a = small.getpixel((x, y))
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255 * (a / 255)
            row += ramp[min(len(ramp) - 1, int(lum * (len(ramp) - 1) * 1.6))]
        rows.append(row)
    return "\n".join(rows)


def verify() -> int:
    ok = True
    seen: dict[bytes, str] = {}
    print(f"icons: {len(MOVES) * 4} expected, canvas {ICON}x{ICON}, supersample {S}x")
    for species in MOVES:
        for index in range(4):
            img = render_icon(species, index)
            assert img.size == (ICON, ICON), (species, index, img.size)
            raw = img.tobytes()
            key = raw
            if key in seen:
                ok = False
                print(f"  DUPLICATE {species}_{'qwer'[index]} == {seen[key]}")
            seen[key] = f"{species}_{'qwer'[index]}"
            opaque = sum(1 for p in img.getdata() if p[3] > 0)
            colors = len(set(img.getdata()))
            if opaque < 200 or colors < 6:
                ok = False
                print(f"  EMPTY/FLAT {species}_{'qwer'[index]} opaque={opaque} colors={colors}")
            elif index == 0:
                print(f"  {species}_{'qwer'[index]}: {MOVES[species][0][0]} ({move_name(species, 0)}) opaque={opaque} colors={colors}")
    print(f"unique icons: {len(seen)}")
    print("\nASCII spot-check (lucario_q 波导弹 / tyranitar_w 咬碎 / gardevoir_w 月亮之力):")
    for species, index in (("lucario", 0), ("tyranitar", 1), ("gardevoir", 1)):
        print(f"-- {species}_{'qwer'[index]} {MOVES[species][index][0]} --")
        print(ascii_preview(render_icon(species, index)))
    effects = [render_effect_sheet(s, k) for s in MOVES for k in ("orb", "slash", "ring", "field")]
    print(f"effect sheets: {len(effects)} x 128x128, all non-empty: {all(e.getbbox() for e in effects)}")
    return 0 if ok else 1


def move_name(species: str, index: int) -> str:
    return MOVES[species][index][1]


def write_all(with_effects: bool = False) -> int:
    """Write the 32 HUD icons; effect sheets only on explicit request.

    Effect sheets belong to the rendering/combat pipeline (they are sampled at a
    different resolution by the client effects), so the default run touches only
    the 32 semantic skill icons this task owns.
    """
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    written = 0
    for species in MOVES:
        for index in range(4):
            render_icon(species, index).save(ICON_DIR / f"{species}_{'qwer'[index]}.png")
            written += 1
        if with_effects:
            EFFECT_DIR.mkdir(parents=True, exist_ok=True)
            for kind in ('orb', 'slash', 'ring', 'field'):
                render_effect_sheet(species, kind).save(EFFECT_DIR / f'{species}_{kind}.png')
                written += 1
                for slot in 'qwer':
                    render_effect_sheet(species, slot + '_' + kind).save(EFFECT_DIR / f'{species}_{slot}_{kind}.png')
                    written += 1
        print(f"{species}: 4 icons" + (" + 20 effect sheets (16 move + 4 compatibility)" if with_effects else ""))
    print(f"wrote {written} textures under {TEX}")
    return 0


def write_mesh_materials(check=False):
    """Tileable opaque UV skins, not alpha silhouettes: 32 hand-painted material maps."""
    directory = EFFECT_DIR / 'expansion'
    if not check:
        directory.mkdir(parents=True, exist_ok=True)
    for species in MOVES:
        for slot in range(4):
            color = TYPE[MOVE_TYPES[species][slot]]
            rng = random.Random(f'mesh:{species}:{slot}')
            image = Image.new('RGBA', (64, 64))
            pixels = image.load()
            for y in range(64):
                for x in range(64):
                    u, v = x / 64, y / 64
                    grain = rng.uniform(-.07, .07)
                    if species == 'abomasnow':
                        # Pale-blue faceted ice with darker frozen fracture lines.
                        f = .65 + .22 * math.sin(u * math.tau * 6) ** 2 + grain
                        if (x + y * 2) % 29 < 2: f = .38
                    elif species in ('tyranitar', 'garchomp') and not (species == 'garchomp' and slot in (0, 3)):
                        f = .52 + .25 * math.sin(u * math.tau * 3 + math.sin(v * math.tau * 4)) ** 2 + grain
                        if (x * 3 + y * 2) % 37 < 2: f *= .56
                    elif species == 'scizor':
                        f = .50 + .45 * abs(math.sin(u * math.tau * 3)) + grain * .35
                        if y % 17 == 0: f = 1.12
                    elif species == 'gengar' and slot == 1:
                        # Wet purple sludge: dense oily marbling, irregular raised bubble highlights.
                        f = .42 + .38 * math.sin(u * math.tau * 3 + math.sin(v * math.tau * 2)) ** 2 + grain
                        if math.sin(u * math.tau * 5) * math.cos(v * math.tau * 4) > .9: f = 1.1
                    else:
                        # Energy skins have continuous luminous currents over a dark body.
                        flow = math.sin(u * math.tau * 4 + math.sin(v * math.tau * 3) * 1.3)
                        f = .42 + .45 * abs(flow) ** 6 + grain
                        if abs(flow) < .055: f = 1.15
                    rgb = tuple(max(0, min(255, int(c * f + max(0, f - .8) * 90))) for c in color)
                    pixels[x, y] = (*rgb, 255)
            assert image.getextrema()[3] == (255, 255)
            assert len(set(image.getdata())) > 30
            if not check:
                image.save(directory / f'{species}_{"qwer"[slot]}_surface.png')
    print('32 opaque 64x64 UV mesh materials validated' + (' (no writes)' if check else ' and written'))
    return 0


def main(argv: list[str]) -> int:
    if '--mesh-materials' in argv:
        return write_mesh_materials(check='--check' in argv)
    if "--check" in argv:
        return verify()
    code = verify()
    if code != 0:
        print("refusing to write: verification failed")
        return code
    return write_all(with_effects="--effects" in argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

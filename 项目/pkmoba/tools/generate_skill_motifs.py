"""Generate the per-skill painted effect textures used by the bespoke PKMOBA visuals.

Every one of the 36 textures (8 expansion species x 4 moves + Pikachu x 4) is a DIFFERENT drawing,
not a recolour: the silhouette, internal lines and accents come from the move itself (claw, jaw,
shard, crescent, spiral, clock, ...). The renderer maps these sheets onto camera-facing quads and
ribbons, so the texture is the art the player sees.

Run:  python tools/generate_skill_motifs.py
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
EXPANSION = ROOT / "src/main/resources/assets/pkmoba/textures/effect/expansion"
PIKACHU = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
S = 256
OUT = 64


def new():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img, glow_color=(255, 255, 255, 90), glow=8, save=None):
    # Three stacked passes: a wide coloured aura, a tight hot rim, then the crisp motif. The old
    # single halo left the sheets reading as dark paint in caves and at night, which is exactly what
    # the user reported ("太暗了"), so the aura is now wider, brighter and drawn twice.
    wide = img.filter(ImageFilter.GaussianBlur(glow * 2.4))
    tight = img.filter(ImageFilter.GaussianBlur(max(1.5, glow * .55)))

    def tinted(src, color, gain):
        layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        layer.paste(src, (0, 0))
        px = layer.load()
        for y in range(S):
            for x in range(S):
                r, g, b, a = px[x, y]
                if a:
                    px[x, y] = (color[0], color[1], color[2], min(255, int(a * gain)))
        return layer

    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.alpha_composite(tinted(wide, glow_color[:3], 1.25))
    out.alpha_composite(tinted(tight, glow_color[:3], 1.0))
    out.alpha_composite(img)
    # Neon palette lift: keeps every sheet readable against dark terrain.
    px = out.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            px[x, y] = (min(255, int(r * 1.18 + 22)), min(255, int(g * 1.18 + 22)),
                        min(255, int(b * 1.18 + 22)), min(255, int(a * 1.32 + 18)))
    # White-hot core pass: anything already opaque gets a bright screen add, so shapes glow from
    # the inside instead of relying on the thin outline only.
    px = out.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            if a < 96:
                continue
            px[x, y] = (min(255, int(r + 20)), min(255, int(g + 20)), min(255, int(b + 20)), a)
    small = out.resize((32, 32), Image.LANCZOS)
    small.resize((OUT, OUT), Image.NEAREST).save(save)


def poly(d, points, fill, outline, width=6):
    d.polygon(points, fill=fill)
    d.line(list(points) + [points[0]], fill=outline, width=width, joint="curve")


def star(d, cx, cy, outer, inner, points, fill, outline, jitter=0.0):
    pts = []
    for i in range(points * 2):
        r = outer if i % 2 == 0 else inner
        a = -math.pi / 2 + i * math.pi / points
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    poly(d, pts, fill, outline)


def crescent(d, cx, cy, radius, thickness, start, end, fill, outline):
    steps = 22
    outer = []
    inner = []
    for i in range(steps + 1):
        a = start + (end - start) * i / steps
        outer.append((cx + math.cos(a) * radius, cy + math.sin(a) * radius))
        inner.append((cx + math.cos(a) * (radius - thickness), cy + math.sin(a) * (radius - thickness)))
    poly(d, outer + list(reversed(inner)), fill, outline)


def bolt(d, x0, y0, x1, y1, width, fill, outline, zig=14):
    steps = 7
    pts = []
    for i in range(steps + 1):
        f = i / steps
        x = x0 + (x1 - x0) * f
        y = y0 + (y1 - y0) * f
        off = 0 if i in (0, steps) else (zig if i % 2 else -zig)
        pts.append((x + off, y + off * 0.35))
    d.line(pts, fill=outline, width=width + 6, joint="curve")
    d.line(pts, fill=fill, width=width, joint="curve")


# ----------------------------------------------------------------------------- Lucario (steel blue)
LUC = ((96, 176, 255), (32, 74, 150), (196, 232, 255), (70, 140, 255, 130))


def lucario_q():
    img, d = new()
    for r, w in ((96, 5), (72, 4), (48, 4)):
        d.ellipse((S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r), outline=LUC[1], width=w)
    for i in range(16):
        a = i * math.tau / 16
        d.line([(S / 2 + math.cos(a) * 40, S / 2 + math.sin(a) * 40),
                (S / 2 + math.cos(a) * 66, S / 2 + math.sin(a) * 66)], fill=LUC[0], width=5)
    d.ellipse((S / 2 - 34, S / 2 - 34, S / 2 + 34, S / 2 + 34), fill=LUC[0], outline=LUC[2], width=6)
    d.ellipse((S / 2 - 16, S / 2 - 16, S / 2 + 16, S / 2 + 16), fill=LUC[2])
    finish(img, LUC[3], 9, EXPANSION / "lucario_q_surface.png")


def lucario_w():
    img, d = new()
    # Open palm: five fingers plus straight impact spokes.
    for i in range(5):
        a = -math.pi / 2 + (i - 2) * 0.36
        x = S / 2 + math.cos(a) * 86
        y = S / 2 + math.sin(a) * 86
        d.line([(S / 2, S / 2), (x, y)], fill=LUC[1], width=18)
        d.line([(S / 2, S / 2), (x, y)], fill=LUC[0], width=10)
    d.ellipse((S / 2 - 46, S / 2 - 40, S / 2 + 46, S / 2 + 52), fill=LUC[0], outline=LUC[2], width=8)
    for i in range(8):
        a = i * math.tau / 8
        d.line([(S / 2 + math.cos(a) * 66, S / 2 + math.sin(a) * 66),
                (S / 2 + math.cos(a) * 104, S / 2 + math.sin(a) * 104)], fill=LUC[2], width=4)
    finish(img, LUC[3], 8, EXPANSION / "lucario_w_surface.png")


def lucario_e():
    img, d = new()
    for i in range(4):
        y = 84 + i * 30
        d.line([(30, y + 12), (150, y - 12)], fill=LUC[1], width=22)
        d.line([(30, y + 12), (150, y - 12)], fill=LUC[0], width=12)
        d.line([(160, y - 13), (206, y - 20)], fill=LUC[2], width=10)
    for i in range(3):
        poly(d, [(210, 60 + i * 50), (246, 78 + i * 50), (210, 96 + i * 50), (222, 78 + i * 50)],
             LUC[2] if i % 2 else LUC[0], LUC[1], 4)
    finish(img, LUC[3], 7, EXPANSION / "lucario_e_surface.png")


def lucario_r():
    img, d = new()
    crescent(d, 128, 150, 104, 34, -2.5, -0.4, LUC[0], LUC[2])
    crescent(d, 128, 110, 108, 28, 0.7, 2.7, LUC[2], LUC[1])
    star(d, 128, 128, 40, 14, 8, LUC[2], LUC[1])
    for i in range(10):
        a = i * math.tau / 10
        d.line([(128 + math.cos(a) * 52, 128 + math.sin(a) * 52),
                (128 + math.cos(a) * 78, 128 + math.sin(a) * 78)], fill=LUC[0], width=5)
    finish(img, LUC[3], 8, EXPANSION / "lucario_r_surface.png")


# ----------------------------------------------------------------------------- Abomasnow (ice)
ABO = ((150, 226, 255), (40, 104, 150), (240, 252, 255), (120, 210, 255, 140))


def abomasnow_q():
    img, d = new()
    for cx, cy, size, rot in ((92, 170, 74, -0.4), (150, 118, 96, 0.2), (196, 176, 58, 0.7)):
        pts = []
        for i in range(6):
            a = rot + i * math.tau / 6
            r = size if i % 2 == 0 else size * 0.42
            pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
        poly(d, pts, ABO[0], ABO[1], 6)
        d.line([(cx, cy), (cx + math.cos(rot) * size * 0.8, cy + math.sin(rot) * size * 0.8)], fill=ABO[2], width=6)
    finish(img, ABO[3], 9, EXPANSION / "abomasnow_q_surface.png")


def abomasnow_w():
    img, d = new()
    for row, w in ((92, 12), (128, 10), (164, 9)):
        pts = [(28 + i * 20, row + math.sin(i * 0.9) * 16) for i in range(11)]
        d.line(pts, fill=ABO[1], width=w + 8, joint="curve")
        d.line(pts, fill=ABO[0], width=w, joint="curve")
    for i in range(12):
        x = 40 + i * 16
        y = 60 + (i % 5) * 26
        d.polygon([(x, y), (x + 7, y + 12), (x, y + 24), (x - 7, y + 12)], fill=ABO[2], outline=ABO[1])
    finish(img, ABO[3], 10, EXPANSION / "abomasnow_w_surface.png")


def abomasnow_e():
    img, d = new()
    for cx, cy, r in ((96, 120, 66), (150, 150, 78), (176, 106, 50), (110, 176, 54)):
        d.ellipse((cx - r, cy - r * 0.72, cx + r, cy + r * 0.72), fill=(190, 226, 245, 150),
                  outline=(225, 248, 255, 220), width=7)
    for i in range(7):
        a = i * 1.1
        d.arc((60 + i * 16, 70 + i * 12, 170 + i * 10, 170 + i * 8), 0, 220, fill=ABO[2], width=5)
    finish(img, ABO[3], 12, EXPANSION / "abomasnow_e_surface.png")


def abomasnow_r():
    img, d = new()
    for i in range(6):
        a = i * math.tau / 6
        ex, ey = 128 + math.cos(a) * 96, 128 + math.sin(a) * 96
        d.line([(128, 128), (ex, ey)], fill=ABO[1], width=16)
        d.line([(128, 128), (ex, ey)], fill=ABO[2], width=8)
        for f in (0.45, 0.7):
            bx, by = 128 + math.cos(a) * 96 * f, 128 + math.sin(a) * 96 * f
            for s in (-0.6, 0.6):
                d.line([(bx, by), (bx + math.cos(a + s) * 26, by + math.sin(a + s) * 26)], fill=ABO[0], width=6)
    d.ellipse((92, 92, 164, 164), fill=ABO[0], outline=ABO[2], width=7)
    d.arc((40, 40, 216, 216), 20, 300, fill=ABO[0], width=9)
    finish(img, ABO[3], 10, EXPANSION / "abomasnow_r_surface.png")


# ----------------------------------------------------------------------------- Blaziken (fire)
BLA = ((255, 158, 56), (150, 44, 12), (255, 240, 190), (255, 130, 40, 150))


def _flame(d, cx, cy, w, h, fill, edge, tongue):
    pts = [(cx, cy - h)]
    for i in range(1, 7):
        f = i / 6
        pts.append((cx + w * (1 - f) * math.sin(f * 6) * 0.9 + w * f, cy - h + h * f))
    pts.append((cx + w * 0.7, cy + h * 0.35))
    pts.append((cx, cy + h * 0.55))
    pts.append((cx - w * 0.7, cy + h * 0.35))
    for i in range(6, 0, -1):
        f = i / 6
        pts.append((cx - w * (1 - f) * 0.8 - w * f * 0.5, cy - h + h * f))
    poly(d, pts, fill, edge, 5)
    poly(d, [(cx, cy - h * 0.45), (cx + w * 0.34, cy + h * 0.28), (cx, cy + h * 0.42),
             (cx - w * 0.34, cy + h * 0.28)], tongue, edge, 3)


def blaziken_q():
    img, d = new()
    _flame(d, 128, 128, 66, 96, BLA[0], BLA[1], BLA[2])
    crescent(d, 128, 140, 104, 22, -2.6, -0.5, BLA[0], BLA[2])
    for i in range(6):
        a = -0.4 + i * 0.35
        d.line([(128 + math.cos(a) * 40, 150 + math.sin(a) * 40),
                (128 + math.cos(a) * 92, 150 + math.sin(a) * 92)], fill=BLA[2], width=6)
    finish(img, BLA[3], 10, EXPANSION / "blaziken_q_surface.png")


def blaziken_w():
    img, d = new()
    crescent(d, 110, 108, 82, 26, -2.7, -0.2, BLA[0], BLA[2])
    crescent(d, 146, 148, 86, 26, 0.5, 2.9, BLA[2], BLA[1])
    d.ellipse((100, 100, 156, 156), outline=BLA[1], width=6)
    for i in range(4):
        _flame(d, 56 + i * 48, 206, 20, 34, BLA[0], BLA[1], BLA[2])
    finish(img, BLA[3], 10, EXPANSION / "blaziken_w_surface.png")


def blaziken_e():
    img, d = new()
    for i in range(4):
        f = i / 4
        _flame(d, 70 + i * 34, 120 + i * 6, 30 - i * 5, 74 - i * 12, BLA[0], BLA[1], BLA[2])
    poly(d, [(168, 128), (238, 96), (214, 128), (238, 160)], BLA[2], BLA[1], 5)
    for i in range(3):
        d.line([(34, 78 + i * 18), (96, 70 + i * 16)], fill=BLA[2], width=7)
    finish(img, BLA[3], 11, EXPANSION / "blaziken_e_surface.png")


def blaziken_r():
    img, d = new()
    d.ellipse((84, 84, 172, 172), fill=BLA[0], outline=BLA[2], width=9)
    d.ellipse((112, 112, 144, 144), fill=BLA[2])
    for i in range(5):
        _flame(d, 60 + i * 36, 190 - abs(i - 2) * 14, 26, 40, BLA[0], BLA[1], BLA[2])
    d.arc((30, 30, 226, 226), 200, 340, fill=BLA[0], width=10)
    finish(img, BLA[3], 12, EXPANSION / "blaziken_r_surface.png")


# ----------------------------------------------------------------------------- Gengar (ghost)
GEN = ((150, 96, 210), (44, 20, 74), (216, 186, 255), (140, 80, 220, 150))


def gengar_q():
    img, d = new()
    spiral = []
    for i in range(70):
        f = i / 70
        a = f * math.tau * 2.4
        r = 26 + f * 78
        spiral.append((128 + math.cos(a) * r, 128 + math.sin(a) * r))
    d.line(spiral, fill=GEN[1], width=16, joint="curve")
    d.line(spiral, fill=GEN[0], width=8, joint="curve")
    d.ellipse((96, 96, 160, 160), fill=GEN[1], outline=GEN[2], width=6)
    for i in range(6):
        a = i * math.tau / 6
        x, y = 128 + math.cos(a) * 96, 128 + math.sin(a) * 96
        d.ellipse((x - 13, y - 13, x + 13, y + 13), fill=GEN[2], outline=GEN[1], width=4)
    finish(img, GEN[3], 10, EXPANSION / "gengar_q_surface.png")


def gengar_w():
    img, d = new()
    d.ellipse((72, 60, 184, 180), fill=GEN[1], outline=GEN[2], width=7)
    for cx, cy, r in ((100, 96, 16), (150, 108, 20), (124, 146, 12), (164, 150, 9)):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=GEN[0], outline=GEN[1], width=4)
    for i in range(5):
        x = 84 + i * 22
        pts = [(x, 172), (x - 6, 208 + (i % 3) * 12), (x + 8, 196)]
        poly(d, pts, GEN[0], GEN[1], 4)
    d.arc((40, 30, 216, 216), 300, 220, fill=GEN[2], width=8)
    finish(img, GEN[3], 11, EXPANSION / "gengar_w_surface.png")


def gengar_e():
    img, d = new()
    for i, r in enumerate((100, 80, 60, 42, 26)):
        col = GEN[2] if i % 2 == 0 else GEN[0]
        d.ellipse((128 - r, 128 - r, 128 + r, 128 + r), outline=col, width=9 - i)
    d.ellipse((110, 116, 146, 140), fill=GEN[1], outline=GEN[2], width=5)
    d.ellipse((120, 124, 136, 134), fill=GEN[2])
    d.line([(128, 40), (128, 88)], fill=GEN[2], width=8)
    d.ellipse((116, 26, 140, 50), fill=GEN[1], outline=GEN[2], width=5)
    finish(img, GEN[3], 9, EXPANSION / "gengar_e_surface.png")


def gengar_r():
    img, d = new()
    d.ellipse((88, 80, 168, 176), fill=GEN[1], outline=GEN[2], width=7)
    for i in range(6):
        a = i * 1.05
        pts = [(110 + math.cos(a) * 30, 120 + math.sin(a) * 26)]
        for k in range(1, 6):
            pts.append((110 + math.cos(a + k * 0.35) * (30 + k * 16), 120 + math.sin(a + k * 0.35) * (26 + k * 14)))
        d.line(pts, fill=GEN[0], width=9, joint="curve")
        ex, ey = pts[-1]
        d.ellipse((ex - 8, ey - 8, ex + 8, ey + 8), fill=GEN[2])
    for i in range(3):
        d.arc((70 + i * 20, 120 + i * 16, 190 - i * 10, 210 - i * 6), 200, 340, fill=GEN[2], width=5)
    finish(img, GEN[3], 11, EXPANSION / "gengar_r_surface.png")


# ----------------------------------------------------------------------------- Tyranitar (rock)
TYR = ((196, 160, 96), (74, 52, 28), (240, 218, 168), (190, 150, 90, 120))


def _rock(d, cx, cy, r, fill, edge):
    pts = []
    for i in range(7):
        a = i * math.tau / 7
        rr = r * (0.72 + (i % 3) * 0.18)
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr * 0.9))
    poly(d, pts, fill, edge, 5)


def tyranitar_q():
    img, d = new()
    _rock(d, 96, 96, 56, TYR[0], TYR[1])
    _rock(d, 168, 130, 44, TYR[0], TYR[1])
    _rock(d, 118, 178, 40, TYR[2], TYR[1])
    for i in range(5):
        d.line([(70 + i * 12, 210), (70 + i * 12, 236)], fill=TYR[2], width=6)
    finish(img, TYR[3], 9, EXPANSION / "tyranitar_q_surface.png")


def tyranitar_w():
    img, d = new()
    for sign in (-1, 1):
        cy = 128 + sign * 46
        pts = [(40, cy), (216, cy), (216, cy + sign * 34), (40, cy + sign * 34)]
        poly(d, pts, TYR[1], TYR[2], 5)
        for i in range(7):
            x = 52 + i * 26
            d.polygon([(x, cy + sign * 30), (x + 12, cy + sign * 30), (x + 6, cy + sign * 52)],
                      fill=TYR[2], outline=TYR[1])
    d.line([(40, 128), (216, 128)], fill=TYR[0], width=8)
    d.ellipse((100, 96, 156, 160), fill=TYR[2], outline=TYR[1], width=4)
    finish(img, TYR[3], 10, EXPANSION / "tyranitar_w_surface.png")


def tyranitar_e():
    img, d = new()
    swirl = []
    for i in range(90):
        f = i / 90
        a = f * math.tau * 2.6
        r = 20 + f * 100
        swirl.append((128 + math.cos(a) * r, 128 + math.sin(a) * r * 0.7))
    d.line(swirl, fill=TYR[1], width=18, joint="curve")
    d.line(swirl, fill=TYR[0], width=9, joint="curve")
    for i in range(16):
        x = 30 + (i * 37) % 196
        y = 34 + (i * 53) % 188
        d.ellipse((x - 4, y - 4, x + 4, y + 4), fill=TYR[2])
    finish(img, TYR[3], 11, EXPANSION / "tyranitar_e_surface.png")


def tyranitar_r():
    img, d = new()
    for i, (x, h) in enumerate(((64, 110), (104, 160), (150, 132), (196, 90))):
        poly(d, [(x - 30, 220), (x, 220 - h), (x + 30, 220)], TYR[0], TYR[1], 6)
        d.line([(x, 220 - h + 20), (x, 214)], fill=TYR[2], width=5)
    d.line([(28, 232), (228, 232)], fill=TYR[1], width=14)
    finish(img, TYR[3], 9, EXPANSION / "tyranitar_r_surface.png")


# ----------------------------------------------------------------------------- Garchomp (dragon)
GAR = ((104, 188, 226), (30, 62, 100), (220, 246, 255), (90, 170, 230, 140))


def garchomp_q():
    img, d = new()
    for i in range(3):
        x = 74 + i * 42
        for k in range(9):
            f = k / 8
            d.line([(x + f * 18, 40 + f * 60), (x + f * 56 - 10, 150 + f * 66)], fill=GAR[1], width=14 - i)
            d.line([(x + f * 18, 40 + f * 60), (x + f * 56 - 10, 150 + f * 66)], fill=GAR[0], width=7 - i)
        d.line([(x - 4, 44), (x + 40, 208)], fill=GAR[2], width=4)
    finish(img, GAR[3], 9, EXPANSION / "garchomp_q_surface.png")


def garchomp_w():
    img, d = new()
    cracks = [[(20, 140), (70, 120), (96, 150), (150, 132), (196, 156), (236, 140)],
              [(70, 120), (84, 70), (120, 52)],
              [(96, 150), (110, 204), (86, 236)],
              [(150, 132), (176, 88), (216, 74)],
              [(196, 156), (214, 206)]]
    for c in cracks:
        d.line(c, fill=GAR[1], width=16, joint="curve")
        d.line(c, fill=GAR[0], width=7, joint="curve")
    d.ellipse((88, 108, 168, 176), outline=GAR[2], width=6)
    finish(img, GAR[3], 10, EXPANSION / "garchomp_w_surface.png")


def garchomp_e():
    img, d = new()
    d.ellipse((64, 96, 192, 184), fill=GAR[1], outline=GAR[2], width=8)
    d.ellipse((88, 114, 168, 166), fill=(18, 26, 40))
    for i in range(7):
        a = i * 0.9
        x, y = 128 + math.cos(a) * 84, 96 + math.sin(a) * 52
        d.polygon([(x, y), (x + 16, y - 14), (x + 22, y + 10)], fill=GAR[0], outline=GAR[1])
    finish(img, GAR[3], 10, EXPANSION / "garchomp_e_surface.png")


def garchomp_r():
    img, d = new()
    poly(d, [(44, 150), (110, 90), (128, 132), (146, 90), (212, 150), (128, 196)], GAR[0], GAR[2], 7)
    d.ellipse((104, 122, 152, 170), fill=GAR[2], outline=GAR[1], width=5)
    for i in range(4):
        d.line([(36 + i * 12, 176 + i * 14), (86 + i * 12, 150 + i * 14)], fill=GAR[0], width=7)
    finish(img, GAR[3], 12, EXPANSION / "garchomp_r_surface.png")


# ----------------------------------------------------------------------------- Scizor (steel)
SCI = ((214, 92, 92), (92, 18, 22), (255, 232, 232), (230, 110, 110, 140))


def scizor_q():
    img, d = new()
    star(d, 118, 128, 72, 26, 10, SCI[0], SCI[2])
    d.ellipse((102, 112, 134, 144), fill=SCI[2])
    for i in range(5):
        y = 44 + i * 12
        d.line([(28, y), (86, y - 6)], fill=SCI[2], width=6)
        d.line([(28, 212 - i * 12), (86, 218 - i * 12)], fill=SCI[2], width=6)
    for i in range(4):
        d.line([(178 + i * 8, 90 + i * 22), (236, 70 + i * 22)], fill=SCI[0], width=8)
    finish(img, SCI[3], 9, EXPANSION / "scizor_q_surface.png")


def scizor_w():
    img, d = new()
    crescent(d, 122, 122, 92, 30, -2.4, -0.2, SCI[0], SCI[2])
    crescent(d, 134, 136, 96, 24, 0.8, 2.9, SCI[2], SCI[1])
    for i in range(5):
        a = i * 0.9
        d.line([(128 + math.cos(a) * 40, 128 + math.sin(a) * 40),
                (128 + math.cos(a) * 72, 128 + math.sin(a) * 72)], fill=SCI[1], width=5)
    finish(img, SCI[3], 9, EXPANSION / "scizor_w_surface.png")


def scizor_e():
    img, d = new()
    for i in range(3):
        d.arc((34, 34, 222, 222), 200 + i * 12, 340 + i * 8, fill=SCI[0], width=14 - i * 3)
    for i in range(4):
        poly(d, [(150 + i * 18, 60 + i * 34), (186 + i * 16, 76 + i * 34), (150 + i * 18, 92 + i * 34),
                 (164 + i * 16, 76 + i * 34)], SCI[2], SCI[1], 4)
    finish(img, SCI[3], 10, EXPANSION / "scizor_e_surface.png")


def scizor_r():
    img, d = new()
    for i in range(7):
        a = i * math.tau / 7 - math.pi / 2
        crescent(d, 128 + math.cos(a) * 70, 128 + math.sin(a) * 70, 42, 15, a - 1.1, a + 1.1, SCI[0], SCI[2])
    d.ellipse((96, 96, 160, 160), outline=SCI[2], width=8)
    for i in range(6):
        a = i * math.tau / 6
        d.line([(128 + math.cos(a) * 24, 128 + math.sin(a) * 24),
                (128 + math.cos(a) * 40, 128 + math.sin(a) * 40)], fill=SCI[2], width=5)
    finish(img, SCI[3], 10, EXPANSION / "scizor_r_surface.png")


# ----------------------------------------------------------------------------- Gardevoir (psychic)
GAR2 = ((226, 148, 230), (86, 30, 110), (255, 236, 255), (220, 140, 235, 150))


def gardevoir_q():
    img, d = new()
    for r in (104, 82, 58):
        d.ellipse((128 - r, 128 - r, 128 + r, 128 + r), outline=GAR2[0], width=6)
    star(d, 128, 128, 46, 16, 6, GAR2[2], GAR2[1])
    for i in range(6):
        a = i * math.tau / 6
        x, y = 128 + math.cos(a) * 92, 128 + math.sin(a) * 92
        d.rectangle((x - 12, y - 16, x + 12, y + 16), fill=GAR2[0], outline=GAR2[1], width=4)
    finish(img, GAR2[3], 10, EXPANSION / "gardevoir_q_surface.png")


def gardevoir_w():
    img, d = new()
    d.ellipse((48, 48, 208, 208), fill=GAR2[2], outline=GAR2[1], width=7)
    d.ellipse((96, 34, 246, 200), fill=(0, 0, 0, 0))
    crescent(d, 128, 128, 104, 42, -2.9, -0.2, GAR2[0], GAR2[2])
    for i in range(9):
        a = i * 0.7
        x, y = 128 + math.cos(a) * 96, 128 + math.sin(a) * 96
        star(d, x, y, 10, 4, 4, GAR2[2], GAR2[1])
    finish(img, GAR2[3], 12, EXPANSION / "gardevoir_w_surface.png")


def gardevoir_e():
    img, d = new()
    for i in range(8):
        a = i * math.tau / 8
        x, y = 128 + math.cos(a) * 74, 128 + math.sin(a) * 74
        star(d, x, y, 26, 9, 4, GAR2[2], GAR2[0])
        d.line([(128 + math.cos(a) * 30, 128 + math.sin(a) * 30),
                (128 + math.cos(a) * 58, 128 + math.sin(a) * 58)], fill=GAR2[0], width=6)
    d.ellipse((110, 110, 146, 146), fill=GAR2[2], outline=GAR2[1], width=5)
    finish(img, GAR2[3], 11, EXPANSION / "gardevoir_e_surface.png")


def gardevoir_r():
    img, d = new()
    d.ellipse((44, 44, 212, 212), outline=GAR2[1], width=10)
    for i in range(12):
        a = i * math.tau / 12
        d.line([(128 + math.cos(a) * 74, 128 + math.sin(a) * 74),
                (128 + math.cos(a) * 92, 128 + math.sin(a) * 92)], fill=GAR2[0], width=7)
    d.line([(128, 128), (128, 62)], fill=GAR2[2], width=8)
    d.line([(128, 128), (176, 150)], fill=GAR2[0], width=8)
    d.ellipse((118, 118, 138, 138), fill=GAR2[2])
    for i in range(5):
        d.line([(64 + i * 32, 20), (64 + i * 32, 46)], fill=GAR2[2], width=6)
    finish(img, GAR2[3], 11, EXPANSION / "gardevoir_r_surface.png")


# ----------------------------------------------------------------------------- Pikachu (electric)
PIK = ((255, 220, 40), (128, 84, 0), (255, 252, 200), (255, 214, 40, 150))


def pikachu_q():
    img, d = new()
    bolt(d, 40, 34, 212, 220, 34, PIK[0], PIK[2], 26)
    bolt(d, 58, 26, 146, 122, 16, PIK[2], PIK[1], 14)
    bolt(d, 92, 96, 226, 176, 14, PIK[2], PIK[1], 16)
    for i in range(5):
        d.line([(196 + i * 8, 60 + i * 10), (228, 44 + i * 12)], fill=PIK[0], width=6)
    finish(img, PIK[3], 10, PIKACHU / "pikachu_q.png")


def pikachu_w():
    img, d = new()
    d.ellipse((62, 62, 194, 194), fill=PIK[0], outline=PIK[2], width=8)
    d.ellipse((92, 92, 164, 164), fill=PIK[2])
    for i in range(12):
        a = i * math.tau / 12
        d.line([(128 + math.cos(a) * 84, 128 + math.sin(a) * 84),
                (128 + math.cos(a) * 112, 128 + math.sin(a) * 112)], fill=PIK[0], width=7)
    d.arc((40, 40, 216, 216), 20, 160, fill=PIK[0], width=10)
    d.arc((40, 40, 216, 216), 200, 340, fill=PIK[0], width=10)
    finish(img, PIK[3], 11, PIKACHU / "pikachu_w.png")


def pikachu_e():
    img, d = new()
    for i in range(3):
        pts = [(36 + j * 26, 178 - j * 14 - i * 26) for j in range(6)]
        d.line(pts, fill=PIK[2], width=20 - i * 5, joint="curve")
        d.line(pts, fill=PIK[0], width=10 - i * 2, joint="curve")
    for i in range(4):
        poly(d, [(190, 52 + i * 42), (236, 70 + i * 42), (190, 88 + i * 42), (206, 70 + i * 42)],
             PIK[0] if i % 2 == 0 else PIK[2], PIK[1], 4)
    finish(img, PIK[3], 10, PIKACHU / "pikachu_e.png")


def pikachu_r():
    img, d = new()
    d.ellipse((34, 34, 222, 222), outline=PIK[1], width=12)
    d.ellipse((52, 52, 204, 204), outline=PIK[0], width=6)
    bolt(d, 128, 40, 118, 214, 26, PIK[0], PIK[2], 18)
    bolt(d, 62, 70, 78, 190, 12, PIK[2], PIK[1], 10)
    bolt(d, 196, 70, 180, 190, 12, PIK[2], PIK[1], 10)
    for i in range(5):
        a = -1.2 + i * 0.6
        d.line([(128, 212), (128 + math.cos(a) * 96, 212 + math.sin(a) * 30)], fill=PIK[0], width=7)
    for i in range(6):
        d.arc((50 + i * 12, 24 + i * 8, 206 - i * 10, 120 + i * 6), 200, 340, fill=PIK[2], width=6)
    finish(img, PIK[3], 12, PIKACHU / "pikachu_r.png")


def main():
    EXPANSION.mkdir(parents=True, exist_ok=True)
    for fn in (lucario_q, lucario_w, lucario_e, lucario_r, abomasnow_q, abomasnow_w, abomasnow_e,
               abomasnow_r, blaziken_q, blaziken_w, blaziken_e, blaziken_r, gengar_q, gengar_w,
               gengar_e, gengar_r, tyranitar_q, tyranitar_w, tyranitar_e, tyranitar_r, garchomp_q,
               garchomp_w, garchomp_e, garchomp_r, scizor_q, scizor_w, scizor_e, scizor_r,
               gardevoir_q, gardevoir_w, gardevoir_e, gardevoir_r, pikachu_q, pikachu_w, pikachu_e,
               pikachu_r):
        fn()
    print("wrote 36 skill motif textures")


if __name__ == "__main__":
    main()

"""Generate the v2 Pikachu kit: HUD icons and textured effect sheets.

Everything is hand-drawn pixel art on a 64x64 grid (icons are a 32x32 framed panel
upscaled x2 for preview only).  Outputs:

  textures/gui/skills/pikachu_thundershock.png   电击 (chain bolt)
  textures/gui/skills/pikachu_thunderbolt.png    十万伏特 (beam)
  textures/gui/skills/pikachu_quick_attack.png   电光一闪 (streaks + afterimage)
  textures/gui/skills/pikachu_thunder.png        打雷 (storm)

  textures/effect/pikachu_thunder_shock.png      bolt ribbon
  textures/effect/pikachu_thunderbolt.png        beam tile
  textures/effect/pikachu_quick_attack.png       speed streaks
  textures/effect/pikachu_afterimage.png         pikachu ghost silhouette
  textures/effect/pikachu_storm_cloud.png        storm cloud
  textures/effect/pikachu_thunder_bolt.png       vertical strike column
  textures/effect/pikachu_ground_ring.png        ground shock ring
  textures/effect/pikachu_spark.png              chunky spark
"""

from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
GUI = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"
FX = ROOT / "src/main/resources/assets/pkmoba/textures/effect"

WHITE = (255, 255, 245, 255)
HOT = (255, 250, 190, 255)
YELLOW = (255, 226, 74, 255)
AMBER = (255, 176, 42, 255)
DEEP = (188, 122, 18, 255)
CYAN = (150, 240, 255, 255)
BLUE = (80, 170, 255, 255)
VOID = (24, 26, 46, 255)


def canvas(size: int = 64) -> Image.Image:
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def glowify(img: Image.Image, strength: float = 1.0, tint=None) -> Image.Image:
    """Composite a soft halo under the crisp art so the sheet never reads as a flat cut-out."""
    art = img.copy()
    alpha = art.getchannel("A")
    halo = Image.new("RGBA", art.size, (0, 0, 0, 0))
    if tint is None:
        halo.paste(art, (0, 0))
    else:
        flat = Image.new("RGBA", art.size, tint)
        halo.paste(flat, (0, 0), alpha)
    pad = Image.new("RGBA", art.size, (0, 0, 0, 0))
    pad.paste(halo, (0, 0))
    wide = pad.filter(ImageFilter.GaussianBlur(4.2))
    tight = pad.filter(ImageFilter.GaussianBlur(1.6))
    out = Image.new("RGBA", art.size, (0, 0, 0, 0))
    out = Image.alpha_composite(out, scale_alpha(wide, 0.55 * strength))
    out = Image.alpha_composite(out, scale_alpha(tight, 0.75 * strength))
    out = Image.alpha_composite(out, art)
    return out


def scale_alpha(img: Image.Image, f: float) -> Image.Image:
    r, g, b, a = img.split()
    a = a.point(lambda v: int(min(255, v * f)))
    return Image.merge("RGBA", (r, g, b, a))


def brighten(img: Image.Image, gain: float = 1.18, add: int = 18) -> Image.Image:
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            px[x, y] = (
                min(255, int(r * gain) + add),
                min(255, int(g * gain) + add),
                min(255, int(b * gain) + add),
                min(255, int(a * 1.3) + 16),
            )
    return img


# --------------------------------------------------------------------------------------
# effect sheets
# --------------------------------------------------------------------------------------


def jag_path(start, end, segments=7, amp=7.0, seed=1, axis="x"):
    """Return a jagged polyline between two points."""
    rnd = random.Random(seed)
    pts = [start]
    for i in range(1, segments):
        f = i / segments
        x = start[0] + (end[0] - start[0]) * f
        y = start[1] + (end[1] - start[1]) * f
        off = rnd.uniform(-amp, amp)
        if axis == "x":
            y += off
        else:
            x += off
        pts.append((x, y))
    pts.append(end)
    return pts


def draw_thick(draw: ImageDraw.ImageDraw, pts, width: int, colour, taper=False):
    for i in range(len(pts) - 1):
        w = width
        if taper:
            f = i / max(1, len(pts) - 2)
            w = max(1, int(width * (1.0 - 0.55 * f)))
        draw.line([pts[i], pts[i + 1]], fill=colour, width=w, joint="curve")


YELLOW = (255, 214, 62, 255)
HOT = (255, 238, 150, 255)
WHITE = (255, 255, 255, 255)
OUTLINE = (24, 20, 38, 255)
ORANGE = (255, 160, 44, 255)
# v3 art direction: chunky Minecraft pixels. Every bolt is a stepped 4px-grid polyline with a
# near-black outline, a solid yellow body and a white-hot core. No bloom, no gradient haze --
# the glow comes from stacked blocky layers, which is what reads as "MC" in game.
STEP = 4


def snap(pts):
    return [(round(x / STEP) * STEP, round(y / STEP) * STEP) for x, y in pts]


def blocky_bolt(d, pts, body=18, core=6, outline=True, tint=YELLOW):
    pts = snap(pts)
    if outline:
        draw_thick(d, pts, body + 6, OUTLINE)
    draw_thick(d, pts, body, tint)
    draw_thick(d, pts, max(4, body - 8), HOT)
    draw_thick(d, pts, core, WHITE)


def diagonal_bolt(d, pts, body=14, core=5, rim=0):
    """A clean zigzag bolt, 8px grid, thick yellow body, white core, dark rim."""
    pts = [(round(x / 8) * 8, round(y / 8) * 8) for x, y in pts]
    draw_thick(d, pts, body, YELLOW)
    draw_thick(d, pts, body - 6, HOT)
    draw_thick(d, pts, core, WHITE)
    for x, y in pts:
        d.rectangle([x - core / 2, y - core / 2, x + core / 2, y + core / 2], fill=WHITE)


def band(d, segments, horizontal=True, rim=0):
    """Tileable energy band: stacked rectangles with a dark rim and a white-hot core line."""
    for offset, thick, colour in ((0, 16, YELLOW), (0, 9, HOT), (0, 4, WHITE)):
        for f, length, shift in segments:
            if horizontal:
                d.rectangle([f * 2, 32 - thick // 2 + shift, (f + length) * 2, 32 + thick // 2 + shift], fill=colour)
            else:
                d.rectangle([32 - thick // 2 + shift, f * 2, 32 + thick // 2 + shift, (f + length) * 2], fill=colour)


def energy_band(d, half: int, tint=YELLOW, hot=HOT) -> None:
    """Seamless horizontal band: outer glow, body, white-hot core and a period-8 glint pattern.

    64 px wide is divisible by the period, so scrolling the band along a 3D bolt repeats without a
    seam. No dark rim: outlines were what made the bolts read as separated chips.
    """
    cx = 32
    tint = (tint[0], tint[1], tint[2], 250)
    hot = (hot[0], hot[1], hot[2], 255)
    d.rectangle([0, cx - half - 5, 63, cx + half + 5], fill=(255, 150, 30, 110))
    d.rectangle([0, cx - half - 4, 63, cx + half + 4], fill=(255, 176, 44, 170))
    d.rectangle([0, cx - half, 63, cx + half], fill=tint)
    d.rectangle([0, cx - half + 3, 63, cx + half - 3], fill=hot)
    d.rectangle([0, cx - 2, 63, cx + 2], fill=(255, 255, 255, 255))
    # pixel glints on a period of 8 px, plus short crackle ticks just outside the body
    for x in range(0, 64, 8):
        d.rectangle([x + 2, cx - half, x + 4, cx - half + 2], fill=(255, 255, 255, 235))
        d.rectangle([x + 5, cx + half - 2, x + 7, cx + half], fill=(255, 255, 255, 235))
        d.rectangle([x + 1, cx - half - 3, x + 2, cx - half - 1], fill=(hot[0], hot[1], hot[2], 220))
        d.rectangle([x + 6, cx + half + 1, x + 7, cx + half + 3], fill=(hot[0], hot[1], hot[2], 220))


def fx_bolt_ribbon() -> Image.Image:
    """Q 电击: a thin continuous spark ribbon (the 3D polyline supplies the zigzag)."""
    img = canvas()
    d = ImageDraw.Draw(img)
    energy_band(d, 7)
    return img


def fx_beam() -> Image.Image:
    """W 十万伏特: a fat continuous thunder band."""
    img = canvas()
    d = ImageDraw.Draw(img)
    energy_band(d, 15, tint=(255, 198, 34), hot=(255, 240, 150))
    return img


def fx_streaks() -> Image.Image:
    """E 电光一闪: two clean speed lines, the bolt itself is drawn in 3D along the dash path."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for y, x0 in ((14, 6), (50, 2)):
        d.rectangle([x0, y, 62, y + 6], fill=OUTLINE)
        d.rectangle([x0 + 2, y + 2, 60, y + 4], fill=(214, 244, 255, 210))
    d.rectangle([0, 28, 63, 30], fill=OUTLINE)
    d.rectangle([0, 29, 63, 29], fill=(255, 255, 255, 235))
    return img


def fx_afterimage() -> Image.Image:
    """Pikachu silhouette (ears, cheeks, bolt tail) rendered as flat blocks with a dark rim."""
    img = canvas()
    d = ImageDraw.Draw(img)
    skin = (255, 214, 62, 235)

    def poly(pts, fill=skin):
        d.polygon(snap(pts), fill=(196, 142, 26, 235))
        inner = [(x + (3 if x < 32 else -3), y + (3 if y < 32 else -3)) for x, y in snap(pts)]
        d.polygon(inner, fill=fill)

    poly([(22, 34), (44, 34), (46, 54), (38, 60), (24, 58), (20, 46)])
    poly([(26, 32), (20, 6), (32, 4), (36, 30)])
    poly([(38, 30), (46, 4), (55, 8), (46, 34)])
    d.rectangle([22, 6, 32, 14], fill=(72, 60, 30, 255))
    d.rectangle([44, 6, 54, 13], fill=(72, 60, 30, 255))
    d.rectangle([26, 38, 32, 43], fill=(255, 110, 96, 255))
    d.rectangle([38, 38, 44, 43], fill=(255, 110, 96, 255))
    d.rectangle([28, 28, 32, 33], fill=OUTLINE)
    d.rectangle([38, 28, 42, 33], fill=OUTLINE)
    blocky_bolt(d, [(44, 50), (54, 50), (54, 40), (63, 40)], body=8, core=3)
    return img


def fx_storm_cloud() -> Image.Image:
    """R 打雷: small chunky cloud with a lit belly and one tooth of lightning below."""
    img = canvas()
    d = ImageDraw.Draw(img)
    puffs = [(20, 26, 14), (34, 20, 17), (48, 27, 13), (27, 36, 13), (43, 36, 12)]
    for cx, cy, r in puffs:
        d.ellipse([cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2], fill=(96, 104, 150, 255))
    for cx, cy, r in puffs:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(62, 68, 104, 255))
    for cx, cy, r in puffs:
        d.arc([cx - r, cy - r, cx + r, cy + r], start=196, end=344, fill=(120, 128, 178, 255), width=4)
    for cx, cy, r in ((26, 36, 7), (40, 37, 8)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 190, 60, 255))
        d.ellipse([cx - r + 3, cy - r + 3, cx + r - 3, cy + r - 3], fill=(255, 226, 150, 255))
    blocky_bolt(d, [(32, 42), (32, 50), (40, 50), (34, 62)], body=10, core=4)
    return img


def fx_thunder_column() -> Image.Image:
    """R 打雷 strike channel: same seamless band, slightly wider and brighter."""
    img = canvas()
    d = ImageDraw.Draw(img)
    energy_band(d, 17)
    return img


def fx_cloud_body() -> Image.Image:
    """UV texture for the solid cloud lumps: bright top, slate belly, blocky noise."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(64):
        t = y / 63.0
        r = int(212 - 92 * t)
        g = int(216 - 92 * t)
        b = int(238 - 78 * t)
        d.rectangle([0, y, 63, y], fill=(r, g, b, 255))
    # blocky cauliflower noise, 4 px grid so the sphere reads as pixel art rather than a smooth ball
    import random
    rnd = random.Random(7)
    for _ in range(150):
        x, y = rnd.randrange(0, 64, 4), rnd.randrange(0, 64, 4)
        shade = rnd.choice((-18, -10, 10, 16))
        r, g, b, _a = img.getpixel((x, y))
        col = (max(0, min(255, r + shade)), max(0, min(255, g + shade)), max(0, min(255, b + shade)), 255)
        d.rectangle([x, y, x + 3, y + 3], fill=col)
    return img


def fx_ring() -> Image.Image:
    """Ground shock annulus authored as a horizontal band: v runs inner -> outer radius."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for top, bottom, colour in ((22, 28, (255, 168, 40, 120)), (28, 36, (255, 226, 96, 235)),
                                (36, 42, (255, 168, 40, 120))):
        d.rectangle([0, top, 63, bottom], fill=colour)
    d.rectangle([0, 31, 63, 33], fill=WHITE)
    for i in range(16):
        x = i * 4 + 2
        d.rectangle([x, 26, x + 1, 38], fill=OUTLINE)
        d.rectangle([x + 1, 29, x + 2, 35], fill=HOT)
    return img


def fx_core() -> Image.Image:
    """White-hot core tile: the solid centre drawn along every bolt."""
    img = Image.new("RGBA", (16, 16), (255, 255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 2, 15, 13], fill=(255, 252, 224, 255))
    d.rectangle([0, 4, 15, 11], fill=(255, 255, 255, 255))
    return img


def fx_spark() -> Image.Image:
    """Impact star: four fat pixel rays and a white core, dark rim so it pops on any background."""
    img = canvas()
    d = ImageDraw.Draw(img)
    cx, cy = 32, 32
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        x0, y0 = cx + dx * 6, cy + dy * 6
        x1, y1 = cx + dx * 30, cy + dy * 30
        d.rectangle([min(x0, x1) - 5, min(y0, y1) - 5, max(x0, x1) + 5, max(y0, y1) + 5], fill=ORANGE)
        d.rectangle([min(x0, x1) - 3, min(y0, y1) - 3, max(x0, x1) + 3, max(y0, y1) + 3], fill=YELLOW)
        d.rectangle([min(x0, x1) - 1, min(y0, y1) - 1, max(x0, x1) + 1, max(y0, y1) + 1], fill=WHITE)
    d.rectangle([cx - 8, cy - 8, cx + 8, cy + 8], fill=ORANGE)
    d.rectangle([cx - 6, cy - 6, cx + 6, cy + 6], fill=YELLOW)
    d.rectangle([cx - 3, cy - 3, cx + 3, cy + 3], fill=WHITE)
    return img


# --------------------------------------------------------------------------------------
# HUD icons: 32x32 framed panels with a dark-rimmed, high-contrast motif
# --------------------------------------------------------------------------------------

FRAME_DARK = (13, 14, 24, 255)


def icon_frame() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([1, 1, 30, 30], radius=3, fill=(26, 28, 34, 255))
    for y in range(3, 29):
        f = (y - 3) / 26
        colour = (int(34 - 8 * f), int(37 - 9 * f), int(44 - 10 * f), 255)
        d.line([(3, y), (28, y)], fill=colour)
    d.rounded_rectangle([1, 1, 30, 30], radius=3, outline=(72, 52, 20, 255), width=1)
    d.rounded_rectangle([2, 2, 29, 29], radius=3, outline=(170, 133, 44, 255), width=1)
    d.line([(3, 3), (28, 3)], fill=(240, 219, 124, 255))
    d.line([(3, 3), (3, 28)], fill=(216, 184, 94, 255))
    d.line([(4, 28), (28, 28)], fill=(84, 60, 22, 255))
    d.line([(28, 4), (28, 28)], fill=(84, 60, 22, 255))
    for cx, cy in ((4, 4), (27, 4), (4, 27), (27, 27)):
        d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=(250, 232, 150, 255))
    return img, d


def bold_bolt(d: ImageDraw.ImageDraw, pts, outer=7, inner=4, core=2) -> None:
    draw_thick(d, pts, outer, FRAME_DARK)
    draw_thick(d, pts, inner, YELLOW)
    draw_thick(d, pts, max(1, core - 1), HOT)
    draw_thick(d, pts, 1, WHITE)


def icon_thundershock() -> Image.Image:
    img, d = icon_frame()
    main = [(8, 23), (14, 14), (11, 14), (19, 7), (16, 13), (21, 12)]
    bold_bolt(d, main, outer=8, inner=5, core=3)
    # chain loop: three beads with arcs, the signature of 电击
    beads = [(24, 21), (20, 26), (14, 25)]
    for i in range(len(beads) - 1):
        a, b = beads[i], beads[i + 1]
        mid = ((a[0] + b[0]) / 2 + 2, (a[1] + b[1]) / 2 + 1)
        arc = [a, mid, b]
        draw_thick(d, arc, 4, FRAME_DARK)
        draw_thick(d, arc, 2, (255, 232, 120, 235))
    for cx, cy in beads:
        d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=FRAME_DARK)
        d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(255, 240, 170, 255))
        d.rectangle([cx, cy, cx + 1, cy + 1], fill=WHITE)
    return brighten(glowify(img, 0.32, (255, 214, 96, 255)))


def icon_thunderbolt() -> Image.Image:
    img, d = icon_frame()
    # a wide discharge with a white-hot core and crackling ends: the move is a sustained beam
    for width, colour in ((13, FRAME_DARK), (10, (255, 168, 40, 255)), (7, YELLOW), (4, HOT), (2, WHITE)):
        d.line([(4, 25), (27, 8)], fill=colour, width=width)
    for x, y, dx, dy in ((8, 22, -3, 3), (13, 19, 3, -1), (18, 15, -3, -2), (23, 11, 3, 1)):
        d.line([(x, y), (x + dx, y + dy)], fill=FRAME_DARK, width=3)
        d.line([(x, y), (x + dx, y + dy)], fill=(255, 242, 180, 245), width=1)
    # spark bursts where the beam leaves and lands
    for cx, cy, r in ((5, 25, 3), (27, 8, 4)):
        d.ellipse([cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1], fill=FRAME_DARK)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 246, 200, 250))
    d.rectangle([15, 17, 17, 19], fill=WHITE)
    return brighten(glowify(img, 0.3, (255, 214, 96, 255)))


def icon_quick_attack() -> Image.Image:
    img, d = icon_frame()
    for y, x0, x1 in ((25, 4, 15), (20, 3, 12), (15, 5, 14)):
        d.line([(x0, y), (x1, y)], fill=FRAME_DARK, width=3)
        d.line([(x0, y), (x1, y)], fill=(226, 247, 255, 245), width=2)
        d.line([(x0, y), (x0 + 2, y)], fill=WHITE, width=2)
    # big head with two tall ears, the silhouette that says Pikachu
    for ear in ([(15, 17), (12, 6), (17, 5), (20, 15)], [(24, 15), (29, 5), (31, 8), (27, 17)]):
        d.polygon(ear, fill=FRAME_DARK)
        d.polygon(ear, fill=(255, 212, 60, 255))
    d.polygon([(12, 6), (14, 6), (15, 11), (13, 12)], fill=(38, 34, 44, 255))
    d.polygon([(29, 5), (31, 8), (30, 12), (28, 10)], fill=(38, 34, 44, 255))
    head = [(15, 14), (26, 14), (27, 21), (22, 25), (16, 22)]
    d.polygon(head, fill=FRAME_DARK)
    d.polygon(head, fill=(255, 212, 60, 255))
    d.rectangle([18, 17, 19, 20], fill=(30, 26, 34, 255))
    d.rectangle([23, 17, 24, 20], fill=(30, 26, 34, 255))
    d.ellipse([17, 19, 19, 21], fill=(255, 116, 106, 255))
    d.ellipse([24, 19, 26, 21], fill=(255, 116, 106, 255))
    tail = [(26, 22), (30, 19), (28, 25), (31, 24), (28, 29)]
    bold_bolt(d, tail, outer=4, inner=3, core=2)
    return brighten(glowify(img, 0.28, (255, 220, 120, 255)))


def icon_thunder() -> Image.Image:
    img, d = icon_frame()
    for cx, cy, r in ((9, 10, 5), (16, 7, 6), (23, 10, 5), (27, 13, 3), (6, 13, 3)):
        d.ellipse([cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1], fill=FRAME_DARK)
    for cx, cy, r in ((9, 10, 5), (16, 7, 6), (23, 10, 5), (27, 13, 3), (6, 13, 3)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(58, 64, 92, 255))
    for cx, cy, r in ((9, 9, 3), (16, 6, 4), (23, 9, 2), (6, 12, 2)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(110, 122, 160, 255))
    # main strike out of the cloud
    bolt = [(17, 13), (13, 21), (17, 20), (12, 29)]
    bold_bolt(d, bolt, outer=7, inner=4, core=2)
    # side fork
    fork = [(21, 15), (25, 22), (22, 21), (26, 28)]
    draw_thick(d, fork, 3, FRAME_DARK)
    draw_thick(d, fork, 2, (255, 238, 160, 245))
    for x, y in ((8, 18), (28, 20), (19, 27)):
        d.rectangle([x - 1, y - 1, x, y], fill=HOT)
    return brighten(glowify(img, 0.32, (255, 214, 96, 255)))


def upscale(img: Image.Image, factor: int = 2) -> Image.Image:
    return img.resize((img.width * factor, img.height * factor), Image.NEAREST)


def save(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print(f"wrote {path.relative_to(ROOT)} {img.size}")


def preview(images, out: Path, columns: int, tile: int = 128) -> None:
    rows = (len(images) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * tile, rows * tile), (26, 28, 38, 255))
    for i, img in enumerate(images):
        scaled = img.resize((tile, tile), Image.NEAREST)
        sheet.alpha_composite(scaled, ((i % columns) * tile, (i // columns) * tile))
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"preview {out}")


def main() -> None:
    fx = {
        "pikachu_thunder_shock": fx_bolt_ribbon(),
        "pikachu_thunderbolt": fx_beam(),
        "pikachu_quick_attack": fx_streaks(),
        "pikachu_afterimage": fx_afterimage(),
        "pikachu_cloud_body": fx_cloud_body(),
        "pikachu_storm_cloud": fx_storm_cloud(),
        "pikachu_thunder_bolt": fx_thunder_column(),
        "pikachu_ground_ring": fx_ring(),
        "pikachu_spark": fx_spark(),
        "pikachu_core": fx_core(),
    }
    for name, img in fx.items():
        save(img, FX / f"{name}.png")

    icons = {
        "pikachu_thundershock": icon_thundershock(),
        "pikachu_thunderbolt": icon_thunderbolt(),
        "pikachu_quick_attack": icon_quick_attack(),
        "pikachu_thunder": icon_thunder(),
    }
    for name, img in icons.items():
        save(img, GUI / f"{name}.png")

    preview(list(fx.values()), ROOT.parent / "pikachu_fx_preview.png", 4, 160)
    preview(list(icons.values()), ROOT.parent / "pikachu_icon_preview.png", 4, 160)


if __name__ == "__main__":
    main()

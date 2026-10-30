"""0.87 art: Lucario v3 (swords dance / meteor mash / thunder punch / extreme speed) and the
Alolan Ninetales ice-fairy kit, plus its HUD portrait.

Everything is drawn as chunky 64x64 pixel sheets with a dark outline and a near-white core, the same
language as the Pikachu / Gengar / Gardevoir kits: one readable silhouette per sheet, no thin
decorative line work.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("src/main/resources/assets/pkmoba/textures")
S = 64

# Lucario: steel-blue aura
NAVY = (12, 22, 52, 255)
DEEP = (28, 58, 132, 255)
COBALT = (44, 104, 224, 255)
BLUE = (92, 168, 250, 255)
CYAN = (140, 224, 255, 255)
PALE = (208, 240, 255, 255)
WHITE = (248, 253, 255, 255)
STEEL = (168, 184, 208, 255)

# Alolan Ninetales: ice + fairy
FROST_DEEP = (32, 70, 122, 255)
FROST = (86, 150, 208, 255)
ICE = (150, 216, 246, 255)
SNOW = (216, 242, 255, 255)
PURE = (250, 253, 255, 255)
PINK = (255, 176, 214, 255)
LILAC = (196, 178, 246, 255)

FRAME = (226, 186, 96, 255)
FRAME_DARK = (96, 66, 26, 255)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def outlined(draw: ImageDraw.ImageDraw, points, fill, width=1):
    draw.polygon(points, fill=NAVY)
    draw.polygon(points, fill=fill)


# ----------------------------------------------------------------------------- Lucario sheets

def lucario_blade() -> Image.Image:
    """One thick crescent blade sweeping up-right; solid body, white cutting edge."""
    img, d = canvas()
    d.arc([2, 30, 62, 70], 200, 340, fill=NAVY, width=16)
    d.arc([2, 30, 62, 70], 200, 340, fill=COBALT, width=11)
    d.arc([2, 30, 62, 70], 200, 340, fill=BLUE, width=6)
    d.arc([2, 30, 62, 70], 200, 340, fill=WHITE, width=3)
    d.polygon([(52, 22), (62, 12), (56, 30)], fill=WHITE)
    d.rectangle([6, 20, 10, 24], fill=CYAN)
    d.rectangle([18, 12, 22, 16], fill=PALE)
    return img


def lucario_bolt() -> Image.Image:
    """Thick three-step lightning bolt - one shape, no branches."""
    img, d = canvas()
    pts = [(30, 2), (46, 2), (38, 24), (52, 24), (26, 62), (34, 34), (18, 34)]
    d.polygon(pts, fill=NAVY)
    inner = [(31, 6), (42, 6), (35, 26), (47, 26), (29, 54), (35, 34), (22, 34)]
    d.polygon(inner, fill=COBALT)
    d.polygon([(33, 9), (39, 9), (33, 25), (42, 25), (32, 40), (34, 32), (28, 32)], fill=WHITE)
    return img


def lucario_star() -> Image.Image:
    """Four fat arms with a white diamond core - chunky hit marker."""
    img, d = canvas()
    arms = [[(32, 0), (40, 24), (32, 32), (24, 24)],
            [(32, 64), (24, 40), (32, 32), (40, 40)],
            [(0, 32), (24, 24), (32, 32), (24, 40)],
            [(64, 32), (40, 24), (32, 32), (40, 40)]]
    for arm in arms:
        d.polygon(arm, fill=NAVY)
        d.polygon([(x + (1 if x < 32 else -1), y + (1 if y < 32 else -1)) for x, y in arm], fill=BLUE)
    d.ellipse([22, 22, 42, 42], fill=NAVY)
    d.ellipse([24, 24, 40, 40], fill=CYAN)
    d.ellipse([27, 27, 37, 37], fill=WHITE)
    for x, y in ((10, 10), (52, 12), (12, 52), (50, 50)):
        d.rectangle([x, y, x + 3, y + 3], fill=PALE)
    return img


def lucario_band() -> Image.Image:
    img, d = canvas()
    d.rectangle([0, 18, 63, 45], fill=DEEP)
    d.rectangle([0, 22, 63, 41], fill=COBALT)
    d.rectangle([0, 26, 63, 37], fill=CYAN)
    d.rectangle([0, 29, 63, 34], fill=WHITE)
    for x in range(1, 64, 9):
        d.rectangle([x, 19, x + 4, 22], fill=PALE)
        d.rectangle([x + 3, 41, x + 6, 44], fill=PALE)
    return img


def lucario_fist() -> Image.Image:
    """A knuckle-first fist seen from the side, with a cyan impact cross behind it."""
    img, d = canvas()
    # impact cross behind the fist
    d.rectangle([26, 2, 38, 16], fill=(CYAN[0], CYAN[1], CYAN[2], 170))
    d.rectangle([2, 28, 16, 40], fill=(CYAN[0], CYAN[1], CYAN[2], 150))
    d.rectangle([46, 40, 62, 52], fill=(CYAN[0], CYAN[1], CYAN[2], 150))
    # forearm / wrist
    d.rectangle([6, 26, 22, 46], fill=NAVY)
    d.rectangle([8, 28, 22, 44], fill=STEEL)
    d.rectangle([8, 33, 22, 38], fill=(230, 240, 252, 255))
    # hand block
    d.rectangle([22, 18, 52, 50], fill=NAVY)
    d.rectangle([24, 20, 50, 48], fill=(232, 241, 252, 255))
    # four knuckles along the striking side
    for i in range(4):
        y = 20 + i * 7
        d.rectangle([50, y, 58, y + 6], fill=NAVY)
        d.rectangle([50, y + 1, 57, y + 5], fill=(250, 253, 255, 255))
        d.rectangle([52, y + 2, 55, y + 4], fill=(206, 228, 250, 255))
    # thumb folded over the front
    d.rectangle([24, 40, 40, 46], fill=NAVY)
    d.rectangle([25, 41, 39, 45], fill=(214, 230, 248, 255))
    d.rectangle([26, 22, 34, 26], fill=(196, 216, 240, 255))
    return img


def lucario_glow() -> Image.Image:
    img, d = canvas()
    for r, colour in ((30, (COBALT[0], COBALT[1], COBALT[2], 90)), (23, (BLUE[0], BLUE[1], BLUE[2], 130)),
                      (15, (CYAN[0], CYAN[1], CYAN[2], 190)), (7, (WHITE[0], WHITE[1], WHITE[2], 235))):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    return img


def lucario_icon(kind: str) -> Image.Image:
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=FRAME)
    d.rectangle([2, 2, 29, 29], fill=FRAME_DARK)
    d.rectangle([3, 3, 28, 28], fill=(16, 20, 40, 255))
    if kind == "q":  # swords dance - two blades crossed over a glowing core
        for flip in (1, -1):
            pts = [(4, 24), (24, 5), (28, 9), (8, 28)]
            if flip < 0:
                pts = [(32 - x, y) for x, y in pts]
            d.polygon(pts, fill=NAVY)
            d.polygon([(x, y + 1) for x, y in pts], fill=CYAN)
            tip = pts[1]
            d.rectangle([tip[0] - 1, tip[1] - 1, tip[0] + 1, tip[1] + 1], fill=WHITE)
        d.ellipse([11, 11, 21, 21], fill=NAVY)
        d.ellipse([12, 12, 20, 20], fill=WHITE)
        d.rectangle([15, 23, 17, 26], fill=PALE)
    elif kind == "w":  # meteor mash - knuckle fist with a comet trail
        for i, (x, y) in enumerate(((2, 20), (5, 24), (3, 16))):
            d.rectangle([x, y, x + 7, y + 2], fill=(BLUE[0], BLUE[1], BLUE[2], 210 - i * 40))
        d.rectangle([11, 12, 26, 27], fill=NAVY)
        d.rectangle([12, 13, 25, 26], fill=(236, 244, 255, 255))
        for i in range(3):
            d.rectangle([25, 14 + i * 4, 29, 17 + i * 4], fill=(252, 254, 255, 255))
        d.rectangle([13, 17, 24, 19], fill=(200, 222, 248, 255))
        d.rectangle([8, 8, 11, 11], fill=CYAN)
    elif kind == "e":  # thunder punch - fist with a bolt
        d.rectangle([17, 14, 28, 26], fill=NAVY)
        d.rectangle([18, 15, 27, 25], fill=(226, 236, 250, 255))
        d.polygon([(11, 5), (7, 16), (11, 16), (8, 27), (17, 13), (12, 13), (15, 5)], fill=WHITE)
        d.rectangle([22, 6, 25, 9], fill=CYAN)
    else:  # extreme speed - arrow with speed bands
        for y in (12, 20, 26):
            d.rectangle([3, y, 14 - (y - 12) // 3, y + 2], fill=(COBALT[0], COBALT[1], COBALT[2], 220))
        d.polygon([(28, 16), (18, 8), (20, 16), (18, 24)], fill=NAVY)
        d.polygon([(26, 16), (19, 10), (20, 16), (19, 22)], fill=CYAN)
        d.rectangle([26, 14, 29, 18], fill=WHITE)
    return img


# ------------------------------------------------------------------ Alolan Ninetales sheets

def ninetales_shard() -> Image.Image:
    img, d = canvas()
    # tall ice crystal: pure white body, cyan rim, one bright sparkle
    d.polygon([(32, 6), (44, 30), (32, 54), (20, 30)], fill=FROST)
    d.polygon([(32, 8), (42, 30), (32, 51), (22, 30)], fill=SNOW)
    d.polygon([(32, 11), (38, 30), (32, 47), (26, 30)], fill=PURE)
    d.rectangle([30, 20, 34, 24], fill=WHITE)
    d.rectangle([44, 14, 46, 16], fill=PURE)
    d.rectangle([47, 22, 48, 23], fill=SNOW)
    d.rectangle([17, 18, 19, 20], fill=SNOW)
    d.rectangle([15, 34, 16, 35], fill=ICE)
    return brighten(img)


def ninetales_wind() -> Image.Image:
    img, d = canvas()
    # three wide frost bands with a snow-dot fringe, mostly white so it never reads dark
    for row, (y, h) in enumerate(((10, 12), (27, 12), (44, 12))):
        d.rectangle([4, y, 59, y + h], fill=FROST)
        d.rectangle([4, y + 1, 59, y + h - 1], fill=SNOW)
        d.rectangle([6, y + 3, 57, y + h - 4], fill=PURE)
        for x in range(6, 58, 7):
            d.rectangle([x, y, x + 2, y + 1], fill=WHITE)
            d.rectangle([x + 3, y + h - 1, x + 5, y + h], fill=ICE)
    return brighten(img)


def ninetales_veil() -> Image.Image:
    img, d = canvas()
    # aurora ribbon: pink outer, lilac middle, near-white core, all brighter than v1
    for y, colour, thick in ((14, PINK, 10), (18, LILAC, 8), (22, (255, 236, 250, 255), 5)):
        d.rectangle([2, y, 61, y + thick], fill=colour)
        d.rectangle([6, y + thick - 2, 57, y + thick], fill=(255, 255, 255, 90))
    d.rectangle([2, 12, 61, 14], fill=(255, 214, 238, 255))
    d.rectangle([2, 30, 61, 32], fill=(214, 196, 255, 255))
    for x in (10, 24, 40, 54):
        d.rectangle([x, 8, x + 2, 10], fill=WHITE)
        d.rectangle([x + 4, 33, x + 6, 35], fill=PURE)
    return brighten(img)


def ninetales_blizzard() -> Image.Image:
    img, d = canvas()
    # fat six arm snowflake, white with cyan tips and a glowing centre
    d.rectangle([30, 30, 34, 34], fill=PURE)
    for i in range(6):
        ang = math.radians(i * 60)
        for step in range(1, 7):
            x = 32 + int(math.cos(ang) * step * 3.9)
            y = 32 + int(math.sin(ang) * step * 3.9)
            half = 3 if step < 5 else 2
            d.rectangle([x - half, y - half, x + half, y + half], fill=SNOW if step > 1 else FROST)
        # barbs
        for side in (-1.0, 1.0):
            for step in (2, 4):
                ba = ang + side * math.radians(38)
                x = 32 + int(math.cos(ang) * step * 3.9 + math.cos(ba) * 5)
                y = 32 + int(math.sin(ang) * step * 3.9 + math.sin(ba) * 5)
                d.rectangle([x - 1, y - 1, x + 2, y + 2], fill=PURE)
    d.rectangle([28, 28, 36, 36], fill=WHITE)
    return brighten(img)


def ninetales_glow() -> Image.Image:
    img, d = canvas()
    for radius, colour in ((30, FROST_DEEP), (24, FROST), (18, ICE), (12, SNOW), (7, PURE)):
        d.ellipse([32 - radius, 32 - radius, 32 + radius, 32 + radius], fill=colour)
    return brighten(img)


def brighten(img: Image.Image) -> Image.Image:
    """Lift the whole sheet so the ice kit never reads as a dark blue blob at night."""
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lift = 1.16
            px[x, y] = (min(255, int(r * lift + 16)), min(255, int(g * lift + 16)),
                        min(255, int(b * lift + 10)), a)
    return img


def ninetales_icon(kind: str) -> Image.Image:
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(150, 196, 236, 255))
    d.rectangle([2, 2, 29, 29], fill=(26, 52, 92, 255))
    d.rectangle([3, 3, 28, 28], fill=(14, 30, 56, 255))
    if kind == "q":  # ice shard
        d.polygon([(16, 4), (23, 13), (19, 28), (13, 28), (9, 13)], fill=ICE)
        d.polygon([(16, 6), (20, 13), (17, 26), (15, 26), (12, 13)], fill=PURE)
        d.rectangle([24, 8, 27, 11], fill=SNOW)
        d.rectangle([5, 20, 8, 23], fill=SNOW)
    elif kind == "w":  # icy wind: three slanted wind lines with hooked heads
        for i, (y, colour) in enumerate(((9, ICE), (16, SNOW), (23, PURE))):
            d.line([(4 + i, y + 5), (20 + i, y - 3)], fill=colour, width=3)
            d.line([(20 + i, y - 3), (26 + i, y - 1)], fill=colour, width=3)
            d.rectangle([26 + i, y - 4, 28 + i, y - 2], fill=colour)
        d.rectangle([6, 25, 9, 28], fill=SNOW)
        d.rectangle([24, 6, 27, 9], fill=SNOW)
    elif kind == "e":  # aurora veil
        d.arc([4, 6, 28, 26], 190, 350, fill=ICE, width=5)
        d.arc([6, 9, 26, 29], 190, 350, fill=LILAC, width=4)
        d.arc([8, 12, 24, 32], 190, 350, fill=PINK, width=3)
        d.rectangle([14, 22, 18, 27], fill=PURE)
    else:  # blizzard
        for angle in (0, 60, 120):
            import math
            rad = math.radians(angle)
            dx = int(math.cos(rad) * 12)
            dy = int(math.sin(rad) * 12)
            d.line([(16 - dx, 16 - dy), (16 + dx, 16 + dy)], fill=SNOW, width=4)
            d.line([(16 - dx, 16 - dy), (16 + dx, 16 + dy)], fill=PURE, width=2)
        d.ellipse([13, 13, 19, 19], fill=PURE)
    return img


def ninetales_portrait() -> Image.Image:
    """64x64 HUD portrait: white ice fox, blue mane, pink eyes, fanned icy tails."""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # tails fanned behind the head
    for angle, colour in ((-40, (196, 232, 250, 255)), (-20, (222, 244, 255, 255)), (0, ICE),
                          (20, (222, 244, 255, 255)), (40, (196, 232, 250, 255))):
        import math
        rad = math.radians(angle - 90)
        tip = (32 + int(math.cos(rad) * 30), 34 + int(math.sin(rad) * 30))
        d.line([(32, 44), tip], fill=colour, width=6)
        d.ellipse([tip[0] - 5, tip[1] - 5, tip[0] + 5, tip[1] + 5], fill=PURE)
    # mane
    d.polygon([(20, 40), (14, 24), (26, 28), (32, 18), (38, 28), (50, 24), (44, 40)], fill=ICE)
    # head
    d.polygon([(22, 30), (42, 30), (46, 44), (32, 54), (18, 44)], fill=PURE)
    # ears
    d.polygon([(20, 32), (16, 16), (28, 27)], fill=PURE)
    d.polygon([(44, 32), (48, 16), (36, 27)], fill=PURE)
    d.polygon([(20, 30), (18, 21), (25, 27)], fill=ICE)
    d.polygon([(44, 30), (46, 21), (39, 27)], fill=ICE)
    # eyes + nose
    d.rectangle([25, 38, 29, 42], fill=(60, 92, 150, 255))
    d.rectangle([35, 38, 39, 42], fill=(60, 92, 150, 255))
    d.rectangle([26, 39, 28, 41], fill=PINK)
    d.rectangle([36, 39, 38, 41], fill=PINK)
    d.rectangle([31, 45, 33, 47], fill=(46, 74, 122, 255))
    d.rectangle([24, 48, 40, 49], fill=(206, 232, 250, 255))
    return img


def write(path: Path, img: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    effects = OUT / "effect"
    icons = OUT / "gui/skills"
    write(effects / "lucario_blade.png", lucario_blade())
    write(effects / "lucario_bolt.png", lucario_bolt())
    write(effects / "lucario_star.png", lucario_star())
    write(effects / "lucario_band.png", lucario_band())
    write(effects / "lucario_fist.png", lucario_fist())
    write(effects / "lucario_glow.png", lucario_glow())
    for key, kind in (("q", "q"), ("w", "w"), ("e", "e"), ("r", "r")):
        write(icons / f"lucario_{key}.png", lucario_icon(kind))
    write(effects / "ninetales_shard.png", ninetales_shard())
    write(effects / "ninetales_wind.png", ninetales_wind())
    write(effects / "ninetales_veil.png", ninetales_veil())
    write(effects / "ninetales_blizzard.png", ninetales_blizzard())
    write(effects / "ninetales_glow.png", ninetales_glow())
    for key in "qwer":
        write(icons / f"alolan_ninetales_{key}.png", ninetales_icon(key))
    write(OUT / "gui/pokemon/alolan_ninetales.png", ninetales_portrait())
    print("wrote lucario v3 + alolan ninetales sheets, icons, portrait")

"""Gardevoir v2 pixel art: psychic pink / lilac, no outlines, symmetric like the Pikachu kit.

Writes 64x64 effect sheets plus four 32x32 HUD icons. Run from the mod folder:

    python tools/generate_gardevoir_v2.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"
SIZE = 64

PINK = (255, 150, 214, 255)
ROSE = (240, 96, 180, 255)
LILAC = (214, 176, 255, 255)
DEEP = (120, 60, 160, 255)
WHITE = (255, 255, 255, 255)
PALE = (255, 236, 250, 255)
GOLD = (255, 224, 140, 255)


def canvas():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def glow(img, keep, distance=3):
    """Additive halo behind `keep` pixels: bright core keeps the glow from swallowing detail."""
    halo = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    keep_layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    keep_layer.paste(img, (0, 0), keep)
    halo = keep_layer.filter(__import__("PIL.ImageFilter", fromlist=["GaussianBlur"]).GaussianBlur(distance * 2.2))
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(halo)
    out.alpha_composite(keep_layer)
    return out, keep_layer


def orb(fill=(255, 150, 214), size=27):
    img, d = canvas()
    for i, colour in enumerate((ROSE, PINK, (255, 196, 236, 255), PALE, WHITE)):
        r = size - i * (size // 7)
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    d.ellipse([27, 27, 37, 37], fill=WHITE)
    for x, y in ((18, 20), (44, 22), (22, 44), (43, 42)):
        d.rectangle([x, y, x + 2, y + 2], fill=PALE)
    return glow(img, img, 2)[0]


def band(segments=15, tint=PINK, hot=PALE):
    """Symmetric horizontal energy band; maps across the width of any stretched quad."""
    img, d = canvas()
    for y in range(SIZE):
        f = abs(y - 32) / 32.0
        if f > .98:
            continue
        if f < .17:
            colour = hot
        elif f < .34:
            colour = tuple(int(hot[i] * .6 + tint[i] * .4) for i in range(3)) + (255,)
        elif f < .62:
            colour = tint
        elif f < .82:
            colour = tuple(int(tint[i] * .55 + 130 * .45) for i in range(3)) + (255,)
        else:
            colour = (int(tint[i] * .35 + 70 * .65) for i in range(3))
            colour = tuple(colour) + (255,)
        d.line([(0, y), (SIZE, y)], fill=colour)
    step = SIZE // segments
    for i in range(segments):
        x = i * step + 2
        if i % 2 == 0:
            d.rectangle([x, 26, x + 1, 27], fill=WHITE)
            d.rectangle([x + 1, 37, x + 2, 38], fill=WHITE)
        else:
            d.rectangle([x, 19, x + 1, 20], fill=PALE)
            d.rectangle([x, 44, x + 2, 45], fill=ROSE)
    return img


def ring_tex(thickness=5):
    img, d = canvas()
    for r in range(29, 22, -1):
        alpha = int(255 * (1 - (r - 22) / 8.0))
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], outline=(255, 190, 240, alpha), width=1)
    for i in range(8):
        import math
        a = i * math.tau / 8
        x = 32 + math.cos(a) * 26
        y = 32 + math.sin(a) * 26
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=WHITE)
    return glow(img, img, 1)[0]


def star_tex():
    import math
    img, d = canvas()
    d.ellipse([26, 26, 38, 38], fill=WHITE)
    for i in range(8):
        a = i * math.tau / 8
        length = 30 if i % 2 == 0 else 19
        d.line([(32, 32), (32 + math.cos(a) * length, 32 + math.sin(a) * length)],
               fill=(255, 205, 240, 255), width=2 if i % 2 else 3)
        d.line([(32, 32), (32 + math.cos(a) * (length - 8), 32 + math.sin(a) * (length - 8))],
               fill=WHITE, width=1)
    for i in range(8):
        a = i * math.tau / 8 + .4
        d.rectangle([32 + math.cos(a) * 34 - 1, 32 + math.sin(a) * 34 - 1,
                     32 + math.cos(a) * 34 + 1, 32 + math.sin(a) * 34 + 1], fill=PALE)
    return glow(img, img, 1)[0]


def moon_tex():
    import math
    img, d = canvas()
    for r in range(30, 21, -1):
        shade = 255 - (30 - r) * 3
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=(shade, shade, min(255, shade + 10), 255))
    for cx, cy, rr in ((24, 24, 5), (40, 20, 3), (38, 40, 4), (22, 41, 3), (32, 30, 6)):
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(214, 220, 240, 255))
        d.ellipse([cx - rr + 2, cy - rr + 2, cx + rr - 2, cy + rr - 2], fill=(236, 240, 252, 255))
    for i in range(6):
        a = i * math.tau / 6
        d.rectangle([32 + math.cos(a) * 29 - 1, 32 + math.sin(a) * 29 - 1,
                     32 + math.cos(a) * 29 + 1, 32 + math.sin(a) * 29 + 1], fill=(255, 220, 250, 255))
    return glow(img, img, 2)[0]


def rune_tex():
    import math
    img, d = canvas()
    for r, colour in ((28, (255, 180, 236, 220)), (24, (255, 210, 246, 180)), (14, (255, 200, 240, 200))):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], outline=colour, width=2)
    for i in range(12):
        a = i * math.tau / 12
        d.line([(32 + math.cos(a) * 16, 32 + math.sin(a) * 16),
                (32 + math.cos(a) * 22, 32 + math.sin(a) * 22)], fill=(255, 226, 250, 230), width=1)
    for i in range(4):
        a = i * math.tau / 4 + .4
        cx, cy = 32 + math.cos(a) * 26, 32 + math.sin(a) * 26
        d.polygon([(cx, cy - 3), (cx + 3, cy), (cx, cy + 3), (cx - 3, cy)], fill=WHITE)
    d.rectangle([31, 31, 33, 33], fill=WHITE)
    # clock hand: 预知未来 reads as a slow clock
    d.line([(32, 32), (32, 18)], fill=(255, 255, 255, 235), width=2)
    d.line([(32, 32), (43, 36)], fill=(255, 214, 246, 220), width=1)
    return glow(img, img, 1)[0]


def shard_tex():
    img, d = canvas()
    d.polygon([(32, 4), (44, 32), (32, 60), (20, 32)], fill=(255, 200, 242, 255))
    d.polygon([(32, 12), (38, 32), (32, 52), (26, 32)], fill=WHITE)
    for x, y in ((14, 18), (48, 20), (16, 44), (49, 45)):
        d.rectangle([x, y, x + 2, y + 2], fill=PALE)
    return glow(img, img, 1)[0]


# ---------------------------------------------------------------- HUD icons


def icon_frame():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(28, 20, 40, 255), outline=(236, 200, 130, 255))
    d.rectangle([1, 1, 30, 1], fill=(120, 96, 70, 255))
    d.rectangle([1, 1, 1, 30], fill=(120, 96, 70, 255))
    d.rectangle([30, 1, 30, 30], fill=(86, 66, 48, 255))
    d.rectangle([1, 30, 30, 30], fill=(86, 66, 48, 255))
    for x, y in ((0, 0), (27, 0), (0, 27), (27, 27)):
        d.rectangle([x, y, x + 4, y + 4], fill=(236, 200, 130, 255))
    d.rectangle([1, 1, 26, 26], fill=(40, 28, 56, 255))
    return img, d


def icon_psychic():
    img, d = icon_frame()
    for i, r in enumerate((12, 9, 6)):
        colour = (255, 190, 240, 255) if i % 2 == 0 else (236, 120, 200, 255)
        d.ellipse([16 - r, 16 - r, 16 + r, 16 + r], outline=colour, width=2)
    d.ellipse([12, 12, 20, 20], fill=WHITE)
    d.rectangle([4, 6, 5, 7], fill=PALE)
    d.rectangle([26, 25, 27, 26], fill=PALE)
    return img


def icon_moon():
    img, d = icon_frame()
    d.ellipse([4, 4, 27, 27], fill=(214, 222, 245, 255))
    for cx, cy, r in ((11, 11, 3), (21, 9, 2), (19, 21, 3), (9, 20, 2)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(236, 240, 252, 255))
    d.arc([2, 2, 29, 29], 300, 60, fill=(255, 206, 244, 255), width=2)
    d.rectangle([25, 5, 26, 6], fill=PALE)
    return img


def icon_blink():
    img, d = icon_frame()
    for y in (10, 15, 20):
        d.line([(3, y), (13 - y // 4, y)], fill=(236, 248, 255, 255), width=2)
    d.ellipse([12, 12, 24, 24], outline=(255, 190, 240, 255), width=2)
    d.polygon([(28, 4), (30, 8), (26, 8)], fill=WHITE)
    d.polygon([(18, 7), (20, 11), (16, 11)], fill=PALE)
    d.rectangle([17, 16, 19, 19], fill=WHITE)
    return img


def icon_future():
    img, d = icon_frame()
    d.ellipse([5, 5, 27, 27], outline=(255, 190, 240, 255), width=2)
    for i in range(8):
        import math
        a = i * math.tau / 8
        d.rectangle([16 + math.cos(a) * 9 - 1, 16 + math.sin(a) * 9 - 1,
                     16 + math.cos(a) * 9 + 1, 16 + math.sin(a) * 9 + 1], fill=(255, 226, 250, 255))
    d.line([(16, 16), (16, 7)], fill=WHITE, width=2)
    d.line([(16, 16), (23, 19)], fill=(255, 214, 246, 255), width=1)
    d.rectangle([15, 19, 17, 20], fill=PALE)
    return img


def main():
    EFFECT.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {
        "gardevoir_orb": orb(),
        "gardevoir_band": band(),
        "gardevoir_ring": ring_tex(),
        "gardevoir_star": star_tex(),
        "gardevoir_moon": moon_tex(),
        "gardevoir_rune": rune_tex(),
        "gardevoir_shard": shard_tex(),
    }
    for name, img in sheets.items():
        img.save(EFFECT / f"{name}.png")
    icons = {
        "gardevoir_q": icon_psychic(),
        "gardevoir_w": icon_moon(),
        "gardevoir_e": icon_blink(),
        "gardevoir_r": icon_future(),
    }
    for name, img in icons.items():
        img.save(ICONS / f"{name}.png")
    print("wrote", len(sheets), "sheets and", len(icons), "icons")


if __name__ == "__main__":
    main()

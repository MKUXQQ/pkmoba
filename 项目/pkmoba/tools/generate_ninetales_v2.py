"""0.94 — Alolan Ninetales kit art: cold, crisp pixel sheets with no muddy outlines.

Run from the project root:
    python tools/generate_ninetales_v2.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
TEX = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
ICONS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/skills"
PORTRAITS = ROOT / "src/main/resources/assets/pkmoba/textures/gui/pokemon"

DEEP = (26, 44, 84, 255)
BLUE = (52, 96, 170, 255)
CYAN = (96, 190, 236, 255)
PALE = (186, 234, 252, 255)
WHITE = (250, 255, 255, 255)
PINK = (246, 170, 226, 255)
ROSE = (222, 120, 206, 255)
MINT = (168, 246, 226, 255)


def canvas(size=64):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img, glow=0.0, colour=CYAN):
    """Optional soft bloom so the sheet reads at night, then quantise for a pixel look."""
    if glow > 0:
        halo = img.filter(ImageFilter.GaussianBlur(2.2))
        tinted = Image.new("RGBA", img.size, colour[:3] + (255,))
        tinted.putalpha(halo.getchannel("A").point(lambda v: int(v * glow)))
        out = Image.new("RGBA", img.size, (0, 0, 0, 0))
        out.alpha_composite(tinted)
        out.alpha_composite(img)
        img = out
    return img


def sheet_shard():
    img, d = canvas()
    # one long ice shard with a white-hot core, leaning slightly so it reads as a projectile
    d.polygon([(32, 2), (43, 26), (40, 58), (24, 58), (21, 26)], fill=DEEP)
    d.polygon([(32, 5), (40, 27), (37, 55), (27, 55), (24, 27)], fill=CYAN)
    d.polygon([(32, 9), (37, 28), (34, 51), (30, 51), (27, 28)], fill=PALE)
    d.polygon([(32, 14), (35, 30), (33, 47), (31, 47), (29, 30)], fill=WHITE)
    for x, y in ((22, 18), (43, 36), (20, 44), (44, 12), (31, 52)):
        d.rectangle([x, y, x + 2, y + 2], fill=PALE)
    return finish(img, .10)


def sheet_beam():
    img, d = canvas()
    d.rectangle([0, 22, 63, 42], fill=DEEP)
    d.rectangle([0, 24, 63, 40], fill=CYAN)
    d.rectangle([0, 27, 63, 37], fill=PALE)
    d.rectangle([0, 30, 63, 34], fill=WHITE)
    for x in range(0, 64, 7):
        d.rectangle([x, 20, x + 3, 23], fill=PALE)
        d.rectangle([x + 3, 41, x + 6, 44], fill=CYAN)
        d.rectangle([x + 4, 26, x + 5, 28], fill=WHITE)
    for x in range(3, 64, 11):
        d.rectangle([x, 34, x + 1, 36], fill=MINT)
    return finish(img, .06)


def sheet_veil():
    img, d = canvas()
    # three stacked ribbons, pink over cyan over mint: the aurora curtain
    for y, colour, off in ((18, ROSE, 0), (28, CYAN, 4), (38, MINT, -3)):
        d.polygon([(0, y + 4), (16 + off, y - 4), (32 + off, y + 2), (48 - off, y - 5), (63, y + 1),
                   (63, y + 10), (48 - off, y + 4), (32 + off, y + 11), (16 + off, y + 5), (0, y + 13)],
                  fill=DEEP)
        d.polygon([(0, y + 5), (16 + off, y - 3), (32 + off, y + 3), (48 - off, y - 4), (63, y + 2),
                   (63, y + 8), (48 - off, y + 2), (32 + off, y + 9), (16 + off, y + 3), (0, y + 11)],
                  fill=colour)
    for x, y in ((10, 12), (28, 8), (48, 14), (20, 46), (40, 50), (55, 44)):
        d.rectangle([x, y, x + 1, y + 2], fill=WHITE)
    return finish(img, .12, (200, 240, 255, 255))


def sheet_storm():
    img, d = canvas()
    centre = (32, 32)
    # two spiral arms plus a ring: a small cyclone rather than a flat disc
    for radius, width, colour in ((26, 3, BLUE), (20, 3, CYAN), (14, 3, PALE)):
        d.arc([centre[0] - radius, centre[1] - radius, centre[0] + radius, centre[1] + radius],
              10, 300, fill=colour, width=width)
    for i in range(10):
        import math
        a = math.radians(i * 36)
        r = 8 + (i % 3) * 7
        x = centre[0] + math.cos(a) * r
        y = centre[1] + math.sin(a) * r
        d.rectangle([x - 1, y - 1, x + 2, y + 2], fill=(WHITE if i % 2 else PALE))
    d.ellipse([27, 27, 37, 37], fill=WHITE)
    d.ellipse([29, 29, 35, 35], fill=(210, 246, 255, 255))
    return finish(img, .14, (150, 220, 255, 255))


def sheet_flake():
    img, d = canvas()
    import math
    for i in range(6):
        a = math.radians(i * 60)
        x = 32 + math.cos(a) * 28
        y = 32 + math.sin(a) * 28
        d.line([(32, 32), (x, y)], fill=DEEP, width=6)
        d.line([(32, 32), (x, y)], fill=CYAN, width=4)
        d.line([(32, 32), (x, y)], fill=WHITE, width=2)
        bx = 32 + math.cos(a) * 18
        by = 32 + math.sin(a) * 18
        for side in (-1, 1):
            b = a + side * math.radians(40)
            d.line([(bx, by), (bx + math.cos(b) * 7, by + math.sin(b) * 7)], fill=CYAN, width=3)
            d.line([(bx, by), (bx + math.cos(b) * 7, by + math.sin(b) * 7)], fill=PALE, width=1)
    d.ellipse([28, 28, 36, 36], fill=WHITE)
    return finish(img, .16, (190, 240, 255, 255))


def sheet_glint():
    img, d = canvas()
    for radius, colour in ((30, (60, 120, 200, 90)), (22, (110, 190, 240, 130)),
                           (14, (180, 230, 255, 190)), (8, (240, 252, 255, 240))):
        d.ellipse([32 - radius, 32 - radius, 32 + radius, 32 + radius], fill=colour)
    return finish(img, .18, (170, 230, 255, 255))


def sheet_puff():
    """A soft snow puff: dithered white cloud for impacts and ground drifts."""
    img, d = canvas()
    import random
    rnd = random.Random(7)
    for i in range(26):
        cx = 32 + rnd.randint(-20, 20)
        cy = 34 + rnd.randint(-14, 14)
        r = rnd.randint(6, 12)
        shade = (255, 255, 255, 150) if rnd.random() < .65 else (240, 252, 255, 200)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=shade)
    for i in range(70):
        x, y = rnd.randint(2, 61), rnd.randint(6, 61)
        d.point((x, y), fill=(255, 255, 255, 220))
    return finish(img, .05)


def icon_frame(size=32):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    frame = (176, 216, 244, 255)
    dark = (18, 32, 62, 255)
    d.rectangle([0, 0, size - 1, size - 1], fill=dark)
    d.rectangle([1, 1, size - 2, size - 2], outline=(96, 150, 200, 255))
    d.rectangle([2, 2, size - 3, size - 3], fill=(14, 24, 48, 255))
    for x, y in ((0, 0), (size - 4, 0), (0, size - 4), (size - 4, size - 4)):
        d.rectangle([x, y, x + 3, y + 3], fill=frame)
    d.rectangle([13, 0, 18, 1], fill=frame)
    d.rectangle([13, size - 2, 18, size - 1], fill=frame)
    d.rectangle([0, 13, 1, 18], fill=frame)
    d.rectangle([size - 2, 13, size - 1, 18], fill=frame)
    return img, d


def icon_shard():
    img, d = icon_frame()
    d.polygon([(20, 4), (25, 14), (23, 27), (16, 27), (13, 14)], fill=(30, 52, 96, 255))
    d.polygon([(19, 6), (23, 15), (21, 25), (17, 25), (15, 15)], fill=CYAN)
    d.polygon([(19, 9), (21, 16), (20, 23), (18, 23), (17, 16)], fill=WHITE)
    for x, y in ((8, 8), (24, 8), (7, 21), (26, 20)):
        d.rectangle([x, y, x + 1, y + 1], fill=PALE)
    d.line([(6, 26), (12, 26)], fill=(120, 200, 244, 255))
    return img


def icon_beam():
    img, d = icon_frame()
    d.line([(5, 24), (26, 8)], fill=DEEP, width=7)
    d.line([(5, 24), (26, 8)], fill=CYAN, width=5)
    d.line([(5, 24), (26, 8)], fill=PALE, width=3)
    d.line([(5, 24), (26, 8)], fill=WHITE, width=1)
    for x, y in ((8, 20), (14, 16), (20, 12)):
        d.rectangle([x, y - 3, x + 1, y - 2], fill=PALE)
    return img


def icon_veil():
    img, d = icon_frame()
    for y, colour in ((12, ROSE), (18, CYAN), (24, MINT)):
        d.arc([4, y - 6, 28, y + 10], 200, 340, fill=colour, width=3)
    for x, y in ((6, 8), (26, 12), (10, 25)):
        d.rectangle([x, y, x + 1, y + 1], fill=WHITE)
    return img


def icon_storm():
    img, d = icon_frame()
    for radius, colour in ((11, BLUE), (8, CYAN), (5, PALE)):
        d.arc([16 - radius, 14 - radius, 16 + radius, 14 + radius], 20, 330, fill=colour, width=2)
    d.ellipse([14, 12, 18, 16], fill=WHITE)
    for x, y in ((7, 22), (14, 26), (22, 23), (25, 17)):
        d.rectangle([x, y, x + 1, y + 1], fill=PALE)
    return img


def portrait_from_sprite(sprite, out):
    """Official sprite -> 64px portrait with the same dark outline the other portraits have."""
    src = Image.open(sprite).convert("RGBA")
    px = src.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = px[x, y]
            if r > 248 and g > 248 and b > 248:
                px[x, y] = (0, 0, 0, 0)
    small = src.resize((64, 64), Image.NEAREST)
    alpha = small.getchannel("A").point(lambda v: 255 if v > 60 else 0)
    edge = Image.new("RGBA", small.size, (0, 0, 0, 0))
    edge.paste(Image.new("RGBA", small.size, (24, 34, 56, 255)),
               (0, 0), alpha.filter(ImageFilter.MaxFilter(3)))
    out_img = Image.new("RGBA", small.size, (0, 0, 0, 0))
    out_img.alpha_composite(edge)
    out_img.alpha_composite(small)
    out_img.save(out)


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)
    sheets = {"nt_shard": sheet_shard(), "nt_beam": sheet_beam(), "nt_veil": sheet_veil(),
              "nt_storm": sheet_storm(), "nt_flake": sheet_flake(), "nt_glint": sheet_glint(), "nt_puff": sheet_puff()}
    for name, img in sheets.items():
        img.save(TEX / f"{name}.png")
    icons = {"q": icon_shard(), "w": icon_beam(), "e": icon_veil(), "r": icon_storm()}
    for slot, img in icons.items():
        img.save(ICONS / f"alolan_ninetales_{slot}.png")
    sprite = Path("D:/dsh项目/兴年小弟弟项目宝可梦/ninetales_sprite.png")
    if sprite.exists():
        portrait_from_sprite(sprite, PORTRAITS / "alolan_ninetales.png")
    print("wrote", len(sheets), "sheets and", len(icons), "icons")


if __name__ == "__main__":
    main()

"""Re-draws Alolan Ninetales (new moveset) and Lucario (new look, same kit).

Every sheet is 64x64 with a white-hot core, saturated body and crisp pixel steps, matching the
Pikachu sheets: no thin outlines, no soft blobs, no grey mud.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ASSETS = Path('src/main/resources/assets/pkmoba/textures')
FX = ASSETS / 'effect'
ICONS = ASSETS / 'gui' / 'skills'

WHITE = (255, 255, 255, 255)
HOT = (226, 250, 255, 255)
PALE = (170, 224, 250, 255)
CYAN = (86, 190, 240, 255)
DEEP = (30, 96, 170, 255)
NAVY = (14, 40, 86, 255)
PINK = (255, 158, 208, 255)
MAGENTA = (236, 92, 168, 255)
STEEL = (196, 220, 246, 255)
STEEL_MID = (120, 160, 214, 255)
STEEL_DARK = (54, 84, 138, 255)
GOLD = (255, 214, 96, 255)
FRAME = (250, 214, 120, 255)
FRAME_DARK = (32, 26, 40, 255)
BG = (12, 16, 28, 255)


def canvas(w=64, h=64):
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def halo(img, colour, radius=2):
    mask = img.getchannel('A').point(lambda v: 255 if v > 40 else 0)
    grow = mask.filter(ImageFilter.MaxFilter(radius * 2 + 1)).filter(ImageFilter.GaussianBlur(1.6))
    glow = Image.new('RGBA', img.size, colour)
    glow.putalpha(grow.point(lambda v: int(v * .55)))
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    return img if radius <= 0 else out


def save(img, name, glow_colour=(150, 220, 255, 255), radius=2):
    out = halo(img, glow_colour, radius)
    out.save(FX / f'{name}.png')


# ---------------------------------------------------------------- Alolan Ninetales sheets
def nt_wind():
    img, d = canvas()
    for i, y in enumerate((20, 32, 44)):
        colour = PALE if i == 1 else CYAN
        d.polygon([(4, y), (60, y - 3), (60, y + 3), (4, y + 6)], fill=colour)
        d.polygon([(4, y + 1), (58, y - 1), (58, y + 2), (4, y + 4)], fill=WHITE)
        for x in range(6 + i * 6, 58, 12):
            d.polygon([(x, y - 5), (x + 4, y), (x, y + 2)], fill=HOT)
    for x, y in ((10, 10), (46, 12), (30, 54)):
        d.rectangle([x, y, x + 2, y + 2], fill=WHITE)
    save(img, 'nt_wind')


def nt_beam():
    img, d = canvas()
    d.rectangle([0, 22, 63, 42], fill=CYAN)
    d.rectangle([0, 26, 63, 38], fill=HOT)
    d.rectangle([0, 29, 63, 35], fill=WHITE)
    for y in (18, 46):
        for x in range(0, 64, 8):
            d.polygon([(x, y), (x + 5, y - 4), (x + 8, y)], fill=PALE)
    for x in range(2, 62, 9):
        d.rectangle([x, 24, x + 2, 26], fill=WHITE)
        d.rectangle([x + 4, 39, x + 6, 41], fill=WHITE)
    save(img, 'nt_beam')


def nt_aurora():
    img, d = canvas()
    bands = ((6, PINK), (16, MAGENTA), (26, (140, 200, 255, 255)), (36, CYAN), (46, (150, 255, 220, 255)))
    for y, colour in bands:
        d.polygon([(2, y + 8), (20, y), (40, y + 6), (62, y - 2), (62, y + 4), (40, y + 12), (20, y + 6), (2, y + 14)],
                  fill=colour)
    for x, y in ((10, 8), (30, 4), (50, 10), (22, 46), (46, 44)):
        d.rectangle([x, y, x + 2, y + 2], fill=WHITE)
    save(img, 'nt_aurora', (255, 190, 230, 255))


def nt_storm():
    img, d = canvas()
    for i in range(16):
        a = i * 3.14159 * 2 / 16
        import math
        x0, y0 = 32, 32
        x1, y1 = 32 + math.cos(a) * 26, 32 + math.sin(a) * 26
        d.line([(x0, y0), (x1, y1)], fill=CYAN, width=3)
        d.line([(x0, y0), (x1, y1)], fill=HOT, width=1)
    for r, colour in ((22, WHITE), (16, PALE), (9, HOT)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], outline=colour, width=2)
    d.rectangle([30, 30, 34, 34], fill=WHITE)
    save(img, 'nt_storm')


def nt_flake():
    img, d = canvas()
    import math
    for i in range(6):
        a = i * 3.14159 / 3
        dx, dy = math.cos(a), math.sin(a)
        d.line([(32, 32), (32 + dx * 28, 32 + dy * 28)], fill=CYAN, width=6)
        d.line([(32, 32), (32 + dx * 28, 32 + dy * 28)], fill=WHITE, width=3)
        for k in (12, 20):
            px, py = 32 + dx * k, 32 + dy * k
            d.line([(px, py), (px + math.cos(a + .9) * 8, py + math.sin(a + .9) * 8)], fill=HOT, width=3)
            d.line([(px, py), (px + math.cos(a - .9) * 8, py + math.sin(a - .9) * 8)], fill=HOT, width=3)
    d.rectangle([28, 28, 36, 36], fill=WHITE)
    save(img, 'nt_flake')


def nt_glow():
    img, d = canvas()
    for r, colour in ((30, (40, 110, 190, 90)), (24, (70, 150, 220, 140)), (17, PALE), (11, HOT), (6, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    return save(img, 'nt_glow')


# ---------------------------------------------------------------- Lucario sheets
def lu_blade():
    img, d = canvas()
    pts = [(6, 40), (20, 32), (40, 20), (58, 6)]
    d.line(pts, fill=STEEL_DARK, width=11)
    d.line(pts, fill=STEEL, width=7)
    d.line(pts, fill=WHITE, width=3)
    d.line([(14, 46), (34, 34)], fill=STEEL_MID, width=5)
    d.rectangle([8, 42, 14, 48], fill=GOLD)
    for x, y in ((46, 10), (52, 20), (26, 44)):
        d.rectangle([x, y, x + 2, y + 2], fill=WHITE)
    save(img, 'lu_blade', (180, 220, 255, 255))


def lu_rock():
    img, d = canvas()
    d.polygon([(14, 40), (26, 12), (46, 16), (54, 40), (36, 54)], fill=STEEL_DARK)
    d.polygon([(18, 38), (28, 18), (44, 22), (48, 38), (34, 48)], fill=STEEL_MID)
    d.polygon([(24, 34), (30, 22), (40, 26), (36, 36)], fill=STEEL)
    d.rectangle([30, 28, 34, 32], fill=WHITE)
    save(img, 'lu_rock', (150, 200, 255, 255))


def lu_bolt():
    img, d = canvas()
    zig = [(26, 2), (16, 26), (30, 26), (20, 62)]
    d.line(zig, fill=STEEL_DARK, width=14, joint='curve')
    d.line(zig, fill=STEEL, width=9, joint='curve')
    d.line(zig, fill=WHITE, width=4, joint='curve')
    for x, y in ((40, 12), (44, 34), (36, 50)):
        d.rectangle([x, y, x + 3, y + 3], fill=HOT)
    save(img, 'lu_bolt', (170, 220, 255, 255))


def lu_core():
    img, d = canvas()
    for r, colour in ((30, (40, 90, 170, 90)), (23, (80, 150, 225, 150)), (16, STEEL), (10, HOT), (5, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    return save(img, 'lu_core')


def lu_star():
    img, d = canvas()
    for angle in range(0, 360, 45):
        import math
        a = math.radians(angle)
        w = 5 if angle % 90 == 0 else 3
        d.line([(32, 32), (32 + math.cos(a) * 30, 32 + math.sin(a) * 30)], fill=STEEL, width=w)
        d.line([(32, 32), (32 + math.cos(a) * 30, 32 + math.sin(a) * 30)], fill=WHITE, width=max(1, w // 2))
    for r, colour in ((9, HOT), (5, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    save(img, 'lu_star')


def lu_streak():
    img, d = canvas()
    for i, y in enumerate((18, 32, 46)):
        d.polygon([(2, y), (50, y - 2), (50, y + 2), (2, y + 5)], fill=STEEL_MID)
        d.polygon([(2, y + 1), (48, y), (48, y + 2), (2, y + 4)], fill=WHITE)
    d.polygon([(50, 20), (62, 32), (50, 44)], fill=HOT)
    save(img, 'lu_streak', (180, 220, 255, 255))


# ---------------------------------------------------------------- icons
def icon(name, draw, glow=(150, 220, 255, 255)):
    img = Image.new('RGBA', (32, 32), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=FRAME_DARK, width=1)
    d.rectangle([1, 1, 30, 30], outline=FRAME, width=1)
    draw(d)
    ICONS.mkdir(parents=True, exist_ok=True)
    img.save(ICONS / f'{name}.png')


def ic_wind(d):
    for y in (10, 16, 22):
        d.rectangle([5, y, 24, y + 2], fill=PALE)
        d.rectangle([7, y, 20, y], fill=WHITE)
    d.rectangle([24, 7, 26, 9], fill=WHITE)
    d.rectangle([9, 25, 11, 27], fill=WHITE)


def ic_beam(d):
    d.rectangle([4, 13, 27, 19], fill=CYAN)
    d.rectangle([4, 15, 27, 17], fill=WHITE)
    d.polygon([(4, 13), (9, 8), (13, 13)], fill=HOT)
    d.polygon([(4, 19), (9, 24), (13, 19)], fill=HOT)


def ic_aurora(d):
    for y, colour in ((9, PINK), (14, MAGENTA), (19, (140, 200, 255, 255)), (24, CYAN)):
        d.polygon([(3, y + 4), (12, y), (21, y + 3), (29, y), (29, y + 3), (21, y + 6), (12, y + 3), (3, y + 7)],
                  fill=colour)


def ic_storm(d):
    d.ellipse([6, 6, 26, 26], outline=WHITE, width=2)
    for y in (11, 15, 19):
        d.polygon([(4, y), (13, y + 3), (22, y - 1), (28, y + 2)], fill=CYAN)
    d.rectangle([14, 22, 18, 26], fill=WHITE)


def ic_sword(d):
    d.line([(7, 26), (25, 7)], fill=STEEL_DARK, width=7)
    d.line([(7, 26), (25, 7)], fill=STEEL, width=4)
    d.line([(8, 25), (24, 8)], fill=WHITE, width=1)
    d.line([(5, 22), (11, 28)], fill=GOLD, width=2)


def ic_comet(d):
    d.ellipse([16, 6, 30, 20], fill=STEEL)
    d.ellipse([19, 9, 27, 17], fill=HOT)
    d.ellipse([21, 11, 25, 15], fill=WHITE)
    for x, y in ((4, 20), (7, 24), (2, 26), (9, 28)):
        d.rectangle([x, y, x + 3, y + 3], fill=STEEL_MID)


def ic_boltfist(d):
    d.rectangle([8, 16, 22, 27], fill=STEEL)
    for x in (9, 13, 17):
        d.rectangle([x, 12, x + 3, 17], fill=STEEL)
    d.line([(15, 2), (10, 12), (16, 12), (12, 21)], fill=WHITE, width=3)
    d.rectangle([20, 8, 23, 11], fill=HOT)


def ic_speed(d):
    for y in (10, 16, 22):
        d.rectangle([4, y, 18, y + 2], fill=PALE)
        d.rectangle([4, y, 14, y], fill=WHITE)
    d.polygon([(19, 8), (29, 16), (19, 24)], fill=HOT)


def main():
    FX.mkdir(parents=True, exist_ok=True)
    nt_wind(); nt_beam(); nt_aurora(); nt_storm(); nt_flake(); nt_glow()
    lu_blade(); lu_rock(); lu_bolt(); lu_core(); lu_star(); lu_streak()
    icon('alolan_ninetales_q', ic_wind)
    icon('alolan_ninetales_w', ic_beam)
    icon('alolan_ninetales_e', ic_aurora, (255, 190, 230, 255))
    icon('alolan_ninetales_r', ic_storm)
    icon('lucario_q', ic_sword)
    icon('lucario_w', ic_comet)
    icon('lucario_e', ic_boltfist)
    icon('lucario_r', ic_speed)
    print('wrote 12 sheets and 8 icons')


if __name__ == '__main__':
    main()

"""0.91 Lucario: every move is drawn with pixel sheets again (no 3D meshes).

Sheets are 64x64, white-hot cores, cobalt rims and cyan accents so they stay readable
against grass, stone and sky. Icons are 32x32 inside the same gold frame as the rest of
the roster.
"""
from pathlib import Path

from PIL import Image, ImageDraw

W = 64
WHITE = (255, 255, 255, 255)
PALE = (214, 240, 255, 255)
ICE = (120, 214, 255, 255)
CYAN = (54, 170, 240, 255)
COBALT = (26, 78, 176, 255)
DEEP = (12, 32, 92, 255)
GOLD = (246, 200, 78, 255)
GOLD_DARK = (150, 96, 24, 255)
DARK = (12, 16, 30, 255)

OUT = Path('src/main/resources/assets/pkmoba/textures/effect')
ICONS = Path('src/main/resources/assets/pkmoba/textures/gui/skills')
OUT.mkdir(parents=True, exist_ok=True)
ICONS.mkdir(parents=True, exist_ok=True)


def canvas(size=W):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def bolt(d, pts, widths, colours):
    for width, colour in zip(widths, colours):
        d.line(pts, fill=colour, width=width, joint='curve')


# ------------------------------------------------------------------ sheets
def sheet_sword():
    """A vertical sword drawn 1:1 so it can stand upright in the world."""
    img, d = canvas()
    # dark silhouette first: at range the sword must not melt into a glowing bar
    d.polygon([(31, 2), (36, 10), (37, 40), (29, 40), (30, 10)], fill=DEEP)
    d.polygon([(31, 5), (35, 12), (35, 39), (31, 39), (31, 12)], fill=CYAN)
    d.polygon([(32, 6), (34, 13), (34, 38), (32, 38)], fill=PALE)
    d.line([(33, 9), (33, 37)], fill=WHITE, width=1)
    # ricasso + cross guard
    d.rectangle([29, 40, 37, 44], fill=DEEP)
    d.rectangle([30, 40, 36, 43], fill=GOLD_DARK)
    d.polygon([(20, 42), (46, 42), (46, 47), (20, 47)], fill=DEEP)
    d.polygon([(21, 43), (45, 43), (45, 46), (21, 46)], fill=GOLD)
    d.rectangle([22, 43, 44, 44], fill=(255, 236, 168, 255))
    # grip and pommel
    d.rectangle([30, 47, 36, 57], fill=DEEP)
    d.rectangle([31, 47, 35, 56], fill=COBALT)
    for y in (49, 52, 55):
        d.rectangle([31, y, 35, y + 1], fill=ICE)
    d.rectangle([28, 57, 38, 61], fill=DEEP)
    d.rectangle([29, 57, 37, 60], fill=GOLD)
    # sparkle on the blade
    d.rectangle([36, 16, 37, 17], fill=WHITE)
    d.rectangle([28, 24, 29, 25], fill=PALE)
    return img


def sheet_comet():
    img, d = canvas()
    # tail first: three widening streaks fading to the left
    for i, (x0, x1, half, colour) in enumerate(((0, 34, 7, CYAN), (0, 28, 5, ICE), (2, 26, 2, WHITE))):
        d.polygon([(x0, 32 - half), (x1, 24), (x1, 40), (x0, 32 + half)], fill=colour)
    # rocky head on the right
    head = [(34, 18), (52, 20), (58, 32), (52, 44), (36, 46), (30, 32)]
    d.polygon(head, fill=DEEP)
    d.polygon([(35, 20), (51, 22), (56, 32), (50, 42), (37, 44), (32, 32)], fill=COBALT)
    d.polygon([(37, 23), (47, 25), (50, 31), (44, 35), (37, 32)], fill=CYAN)
    d.ellipse([40, 26, 52, 38], fill=PALE)
    d.ellipse([43, 28, 50, 35], fill=WHITE)
    for x, y in ((36, 36), (44, 40), (49, 38), (33, 27)):
        d.rectangle([x, y, x + 2, y + 2], fill=ICE)
    for x, y in ((0, 20), (0, 42), (6, 14), (6, 48)):
        d.rectangle([x, y, x + 1, y + 1], fill=PALE)
    return img


def sheet_bolt():
    img, d = canvas()
    pts = [(26, 2), (34, 22), (24, 24), (36, 62), (40, 36), (30, 34), (40, 2)]
    d.polygon(pts, fill=DEEP)
    inner = [(27, 6), (33, 22), (25, 25), (36, 56), (38, 36), (29, 33), (38, 7)]
    d.polygon(inner, fill=CYAN)
    d.line([(30, 10), (33, 22), (28, 26), (35, 52)], fill=WHITE, width=3)
    for x, y in ((20, 28), (42, 30), (22, 44), (44, 46)):
        d.rectangle([x, y, x + 2, y + 2], fill=ICE)
    return img


def sheet_punch():
    img, d = canvas()
    # wrist cuff
    d.rectangle([4, 24, 16, 44], fill=DEEP)
    d.rectangle([6, 26, 15, 42], fill=COBALT)
    d.rectangle([8, 28, 13, 40], fill=CYAN)
    # hand block with four knuckle ridges facing right
    d.rectangle([16, 18, 38, 48], fill=DEEP)
    d.rectangle([17, 20, 36, 46], fill=COBALT)
    d.rectangle([19, 22, 32, 44], fill=CYAN)
    for y in (20, 27, 34, 41):
        d.rectangle([34, y, 42, y + 6], fill=DEEP)
        d.rectangle([35, y + 1, 41, y + 5], fill=ICE)
        d.rectangle([36, y + 2, 40, y + 4], fill=PALE)
    # thumb across the top
    d.rectangle([20, 14, 32, 19], fill=DEEP)
    d.rectangle([21, 15, 31, 18], fill=ICE)
    # contact flash
    d.ellipse([40, 22, 58, 44], fill=(255, 255, 255, 110))
    d.ellipse([43, 25, 55, 41], fill=(255, 255, 255, 200))
    d.ellipse([46, 28, 52, 38], fill=WHITE)
    bolt(d, [(44, 10), (52, 18), (46, 24)], (5, 2), (COBALT, WHITE))
    bolt(d, [(44, 46), (52, 40), (46, 34)], (5, 2), (COBALT, WHITE))
    return img


def sheet_star():
    img, d = canvas()
    cx = cy = 32
    for i in range(8):
        import math
        a = math.radians(i * 45)
        long_ = 28 if i % 2 == 0 else 17
        d.polygon([(cx + math.cos(a) * long_, cy + math.sin(a) * long_),
                   (cx + math.cos(a + .32) * 5, cy + math.sin(a + .32) * 5),
                   (cx + math.cos(a - .32) * 5, cy + math.sin(a - .32) * 5)], fill=COBALT)
    for i in range(8):
        import math
        a = math.radians(i * 45)
        long_ = 24 if i % 2 == 0 else 13
        d.polygon([(cx + math.cos(a) * long_, cy + math.sin(a) * long_),
                   (cx + math.cos(a + .24) * 4, cy + math.sin(a + .24) * 4),
                   (cx + math.cos(a - .24) * 4, cy + math.sin(a - .24) * 4)], fill=WHITE)
    d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=PALE)
    return img


def sheet_streak():
    img, d = canvas()
    for y, half, colour in ((20, 3, CYAN), (32, 4, PALE), (44, 3, CYAN)):
        d.polygon([(2, y - half), (44, y - half), (52, y), (44, y + half), (2, y + half)], fill=colour)
    d.polygon([(44, 18), (60, 32), (44, 46)], fill=COBALT)
    d.polygon([(46, 22), (57, 32), (46, 42)], fill=WHITE)
    return img


def sheet_glow():
    img, d = canvas()
    for r, colour in ((30, (54, 170, 240, 40)), (24, (120, 214, 255, 70)),
                      (17, (214, 240, 255, 110)), (11, (255, 255, 255, 170)), (6, (255, 255, 255, 230))):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    return img


# ------------------------------------------------------------------ icons
def icon_frame():
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(246, 200, 78, 255))
    d.rectangle([1, 1, 30, 30], fill=(186, 138, 44, 255))
    d.rectangle([2, 2, 29, 29], fill=(14, 20, 36, 255))
    d.rectangle([3, 3, 28, 28], fill=(24, 34, 58, 255))
    for x, y in ((1, 1), (28, 1), (1, 28), (28, 28)):
        d.rectangle([x, y, x + 2, y + 2], fill=(255, 232, 150, 255))
    return img, d


def icon_sword():
    img, d = icon_frame()
    d.polygon([(8, 26), (23, 5), (25, 7), (10, 27)], fill=CYAN)
    d.polygon([(9, 25), (23, 6), (24, 7), (10, 26)], fill=WHITE)
    d.polygon([(6, 20), (13, 24), (10, 27), (4, 23)], fill=GOLD)
    d.rectangle([4, 25, 9, 28], fill=COBALT)
    return img


def icon_comet():
    img, d = icon_frame()
    for y, half, colour in ((16, 4, CYAN), (16, 2, PALE)):
        d.polygon([(3, y - half), (17, y - half), (22, y), (17, y + half), (3, y + half)], fill=colour)
    d.ellipse([14, 8, 28, 22], fill=COBALT)
    d.ellipse([16, 10, 26, 20], fill=CYAN)
    d.ellipse([18, 12, 23, 17], fill=WHITE)
    d.rectangle([26, 4, 28, 6], fill=PALE)
    return img


def icon_thunder():
    img, d = icon_frame()
    # gauntlet with a bolt crackling off the knuckles
    d.rectangle([4, 15, 15, 25], fill=COBALT)
    d.rectangle([6, 17, 13, 23], fill=CYAN)
    d.rectangle([15, 12, 24, 28], fill=DEEP)
    d.rectangle([16, 13, 22, 27], fill=CYAN)
    for y in (13, 18, 23):
        d.rectangle([22, y, 25, y + 3], fill=ICE)
    d.polygon([(24, 4), (28, 11), (25, 12), (29, 20), (27, 13), (30, 10), (26, 4)], fill=(255, 246, 180, 255))
    d.line([(26, 6), (28, 11), (26, 12), (28, 18)], fill=WHITE, width=1)
    return img


def icon_speed():
    img, d = icon_frame()
    for y in (10, 16, 22):
        d.rectangle([4, y, 18, y + 3], fill=PALE if y == 16 else CYAN)
    d.polygon([(18, 7), (29, 16), (18, 25)], fill=COBALT)
    d.polygon([(20, 10), (27, 16), (20, 22)], fill=WHITE)
    return img


def main():
    for name, fn in (('lu_sword', sheet_sword), ('lu_comet', sheet_comet), ('lu_bolt', sheet_bolt),
                     ('lu_punch', sheet_punch), ('lu_star', sheet_star), ('lu_streak', sheet_streak),
                     ('lu_glow', sheet_glow)):
        fn().save(OUT / f'{name}.png')
    for name, fn in (('q', icon_sword), ('w', icon_comet), ('e', icon_thunder), ('r', icon_speed)):
        fn().save(ICONS / f'lucario_{name}.png')
    print('wrote 7 sheets and 4 icons')


if __name__ == '__main__':
    main()

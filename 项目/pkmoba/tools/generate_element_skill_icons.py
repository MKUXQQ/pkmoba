"""Generate polished Fire/Water Pokemon skill icons matching Venusaur's style."""
from pathlib import Path
from PIL import Image, ImageDraw
import math

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/pkmoba/textures/gui/skills"
ROOT.mkdir(parents=True, exist_ok=True)
S = 128
INK = (20, 31, 39, 255)
WHITE = (255, 255, 239, 255)


def poly(d, pts, fill, outline=INK, width=4):
    d.polygon(pts, fill=fill)
    if outline:
        d.line(pts + [pts[0]], fill=outline, width=width, joint="curve")


def line(d, pts, fill, width):
    d.line(pts, fill=INK, width=width + 8, joint="curve")
    d.line(pts, fill=fill, width=width, joint="curve")


def frame(d, palette):
    deep, mid, bright, glow = palette
    d.rounded_rectangle((4, 4, 124, 124), radius=25, fill=(5, 13, 21, 235), outline=(8, 19, 27, 255), width=5)
    d.rounded_rectangle((9, 9, 119, 119), radius=20, fill=deep, outline=bright, width=3)
    d.rounded_rectangle((14, 14, 114, 114), radius=16, fill=mid)
    d.ellipse((18, 16, 110, 108), fill=deep)
    d.arc((15, 14, 113, 112), 202, 333, fill=glow, width=4)
    d.line((22, 105, 38, 111, 91, 111, 106, 104), fill=bright, width=2)
    for x, y in ((23, 25), (104, 25), (22, 98), (105, 98)):
        d.rectangle((x-2, y-2, x+2, y+2), fill=glow)


FIRE = ((91, 30, 23, 255), (160, 47, 22, 255), (255, 158, 48, 255), (255, 232, 128, 255))
WATER = ((14, 48, 86, 255), (19, 94, 139, 255), (75, 207, 235, 255), (214, 255, 255, 255))


def icon(name, palette, art):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    frame(d, palette)
    art(d, *palette)
    im.resize((32, 32), Image.Resampling.LANCZOS).save(ROOT / f"{name}.png", optimize=True)


def flame(d, deep, mid, bright, glow):
    # A tapered jet with layered tongues and a hot white center.
    poly(d, [(28, 77), (57, 66), (74, 45), (100, 28), (91, 59), (109, 72), (78, 77), (56, 94)], (255, 106, 25, 255))
    poly(d, [(36, 75), (66, 61), (84, 44), (95, 37), (84, 64), (101, 72), (71, 73), (53, 87)], (255, 190, 48, 255), width=3)
    poly(d, [(47, 75), (70, 63), (84, 51), (76, 70), (88, 73), (68, 76), (57, 83)], WHITE, width=2)
    poly(d, [(35, 85), (24, 94), (45, 90)], bright, width=3)
    for x, y in ((43, 47), (58, 37), (76, 29), (92, 84)):
        d.ellipse((x-3, y-3, x+3, y+3), fill=glow)


def fire_spin(d, deep, mid, bright, glow):
    # Spiral flame ring around a dark, readable center.
    d.ellipse((31, 29, 98, 96), fill=(36, 24, 26, 255), outline=bright, width=5)
    d.ellipse((45, 43, 84, 82), fill=deep, outline=glow, width=3)
    for a in range(0, 360, 45):
        r = math.radians(a)
        cx, cy = 64 + math.cos(r)*37, 62 + math.sin(r)*32
        dx, dy = math.cos(r+0.9), math.sin(r+0.9)
        poly(d, [(cx-dy*7, cy+dx*7), (cx+dx*15, cy+dy*15), (cx+dy*7, cy-dx*7)], (255, 112 + (a%90), 30, 255), width=2)
    d.arc((38, 34, 93, 89), 205, 32, fill=WHITE, width=5)
    d.arc((46, 42, 85, 81), 30, 205, fill=glow, width=3)


def fly(d, deep, mid, bright, glow):
    # Charizard-like wing silhouette lifting through a bright sky.
    d.ellipse((54, 61, 76, 88), fill=(255, 183, 59, 255), outline=INK, width=4)
    poly(d, [(64, 68), (28, 48), (20, 25), (43, 36), (49, 21), (66, 40), (80, 28), (104, 25), (92, 50), (74, 70)], (255, 132, 35, 255))
    poly(d, [(57, 52), (39, 39), (45, 57), (60, 68)], (255, 205, 87, 255), width=3)
    poly(d, [(69, 51), (87, 37), (81, 56), (69, 69)], (255, 205, 87, 255), width=3)
    line(d, [(64, 80), (61, 96), (52, 104)], bright, 5)
    line(d, [(67, 80), (75, 94), (86, 98)], bright, 5)
    d.line((28, 102, 51, 97), fill=glow, width=3)
    d.line((78, 106, 102, 100), fill=glow, width=3)


def fire_blast(d, deep, mid, bright, glow):
    # Exploding solar fireburst with a layered central core.
    cx = cy = 64
    for i in range(12):
        a = math.tau*i/12
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        r0, r1 = 28, 51 if i % 2 == 0 else 42
        poly(d, [(cx+ux*r0+px*7, cy+uy*r0+py*7), (cx+ux*r1, cy+uy*r1), (cx+ux*r0-px*7, cy+uy*r0-py*7)], (255, 107 + (i%3)*36, 25, 255), width=2)
    d.ellipse((38, 38, 90, 90), fill=(255, 154, 32, 255), outline=INK, width=5)
    d.ellipse((46, 46, 82, 82), fill=(255, 222, 91, 255), outline=glow, width=3)
    d.ellipse((55, 55, 73, 73), fill=WHITE)
    for x, y in ((31, 35), (97, 42), (34, 90), (93, 94)):
        d.rectangle((x-3, y-3, x+3, y+3), fill=glow)


def water_cannon(d, deep, mid, bright, glow):
    # Twin nozzles feed a converging, pressurized beam.
    poly(d, [(23, 43), (52, 47), (99, 58), (108, 65), (98, 72), (52, 83), (23, 87), (32, 67)], (31, 158, 207, 255))
    poly(d, [(26, 48), (51, 53), (96, 62), (102, 65), (96, 68), (51, 77), (26, 82), (34, 65)], (94, 223, 244, 255), width=3)
    line(d, [(35, 65), (98, 65)], WHITE, 7)
    for y in (48, 81):
        d.rounded_rectangle((18, y-6, 41, y+6), radius=4, fill=(18, 61, 91, 255), outline=glow, width=3)
        d.ellipse((29, y-3, 37, y+3), fill=WHITE)
    for x, y in ((57, 42), (69, 84), (85, 49), (90, 79)):
        d.ellipse((x-3, y-3, x+3, y+3), fill=glow)


def water_spin(d, deep, mid, bright, glow):
    # Blastoise shell embraced by a fast rotating spiral of water.
    d.ellipse((37, 35, 91, 89), fill=(39, 109, 119, 255), outline=INK, width=5)
    d.polygon([(64, 40), (82, 51), (81, 73), (64, 84), (46, 73), (46, 51)], fill=(57, 139, 128, 255), outline=glow)
    d.line((64, 42, 64, 82), fill=(196, 231, 177, 255), width=3)
    d.line((47, 52, 80, 73), fill=(196, 231, 177, 255), width=3)
    d.line((81, 52, 47, 73), fill=(196, 231, 177, 255), width=3)
    d.arc((20, 19, 108, 107), 198, 345, fill=bright, width=10)
    d.arc((23, 22, 105, 104), 198, 345, fill=WHITE, width=3)
    poly(d, [(96, 32), (112, 24), (107, 43)], glow, width=2)
    poly(d, [(33, 99), (16, 105), (25, 87)], bright, width=2)


def surf(d, deep, mid, bright, glow):
    # A curling crest with layered blue planes and foam highlights.
    poly(d, [(18, 83), (31, 65), (45, 72), (59, 54), (73, 62), (91, 36), (107, 48), (97, 57), (113, 63), (101, 80), (111, 93), (76, 87), (47, 99)], (30, 130, 189, 255))
    poly(d, [(20, 82), (39, 73), (54, 77), (71, 63), (90, 44), (102, 50), (91, 61), (108, 66), (93, 75), (75, 80), (51, 91)], (78, 205, 231, 255), width=3)
    line(d, [(25, 83), (42, 76), (57, 80), (73, 66), (92, 50), (102, 53)], WHITE, 5)
    d.arc((55, 29, 105, 71), 205, 330, fill=glow, width=5)
    for x, y in ((29, 58), (44, 47), (78, 34), (108, 37)):
        d.ellipse((x-3, y-5, x+3, y+5), fill=WHITE)


def water_storm(d, deep, mid, bright, glow):
    # A deep whirlpool viewed from above with a raised central spout.
    for box, col, width in [((21, 23, 107, 105), (31, 141, 191, 255), 8), ((32, 34, 96, 94), (83, 210, 231, 255), 7), ((43, 45, 85, 83), (19, 79, 128, 255), 6)]:
        d.arc(box, 28, 330, fill=col, width=width)
    d.ellipse((51, 53, 77, 79), fill=(14, 53, 95, 255), outline=glow, width=3)
    poly(d, [(60, 65), (64, 31), (69, 65)], (87, 218, 237, 255), width=3)
    d.ellipse((60, 28, 69, 36), fill=WHITE)
    poly(d, [(91, 30), (109, 22), (103, 42)], glow, width=2)
    poly(d, [(35, 95), (18, 104), (24, 84)], bright, width=2)
    for x, y in ((35, 38), (92, 82), (47, 26), (84, 102)):
        d.ellipse((x-3, y-3, x+3, y+3), fill=glow)


icons = [
    ("flamethrower", FIRE, flame), ("fire_spin", FIRE, fire_spin),
    ("fly", FIRE, fly), ("fire_blast", FIRE, fire_blast),
    ("blastoise_0", WATER, water_cannon), ("blastoise_1", WATER, water_spin),
    ("blastoise_2", WATER, surf), ("blastoise_3", WATER, water_storm),
]
for name, palette, art in icons:
    icon(name, palette, art)
print("Generated 8 polished Fire/Water skill icons at 32x32.")

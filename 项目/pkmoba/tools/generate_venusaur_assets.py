"""Generate polished Venusaur skill icons and bright, layered effect textures."""
from pathlib import Path
from PIL import Image, ImageDraw
import math

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/pkmoba"
EFFECT = ROOT / "textures/effect"
SKILLS = ROOT / "textures/gui/skills"
EFFECT.mkdir(parents=True, exist_ok=True)
SKILLS.mkdir(parents=True, exist_ok=True)
S = 128
INK = (17, 43, 31, 255)
DEEP = (23, 72, 43, 255)
LEAF = (62, 156, 61, 255)
LIME = (164, 231, 70, 255)
GOLD = (255, 222, 92, 255)
WHITE = (255, 255, 220, 255)
PINK = (255, 112, 166, 255)
TOXIC = (149, 65, 194, 255)
TOXIC_LIGHT = (221, 151, 250, 255)
TOXIC_DEEP = (53, 25, 77, 255)


def make_icon(name, draw_art):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((4, 4, 124, 124), radius=25, fill=(10, 27, 19, 220), outline=(7, 22, 16, 255), width=5)
    d.rounded_rectangle((9, 9, 119, 119), radius=20, fill=(27, 83, 46, 255), outline=(102, 171, 67, 255), width=3)
    d.ellipse((18, 15, 111, 108), fill=(39, 108, 54, 255))
    draw_art(d)
    im.resize((32, 32), Image.Resampling.LANCZOS).save(SKILLS / f"{name}.png", optimize=True)


def leaf(d, x, y, angle, length, color=LIME):
    ux, uy = math.cos(angle), math.sin(angle)
    px, py = -uy, ux
    tip = (x + ux * length, y + uy * length)
    base = (x - ux * length * .22, y - uy * length * .22)
    d.polygon([base, (x + px * length * .34, y + py * length * .34), tip,
               (x - px * length * .34, y - py * length * .34)], fill=color, outline=INK)
    d.line((base[0], base[1], tip[0], tip[1]), fill=(225, 255, 144, 255), width=3)


def icon_vine(d):
    pts = [(20, 104), (39, 92), (53, 75), (55, 56), (72, 43), (94, 39), (108, 24)]
    d.line(pts, fill=INK, width=27, joint="curve")
    d.line(pts, fill=(44, 130, 56, 255), width=20, joint="curve")
    d.line(pts, fill=LIME, width=7, joint="curve")
    d.polygon([(98, 27), (121, 13), (112, 39), (105, 36)], fill=(55, 150, 58, 255), outline=INK)
    for x, y, a in [(36, 91, -2.4), (53, 69, -1.1), (74, 44, .2), (93, 39, 1.6)]:
        leaf(d, x, y, a, 17, (173, 234, 78, 255))
    for x, y, a in [(42, 85, -2.1), (63, 53, .5), (84, 41, 1.8)]:
        ux, uy = math.cos(a), math.sin(a)
        d.polygon([(x-4, y-4), (x+ux*16, y+uy*16), (x+5, y+3)], fill=(236, 255, 133, 255), outline=INK)
    d.ellipse((103, 17, 117, 31), fill=WHITE, outline=INK, width=2)


def icon_seed(d):
    d.arc((17, 20, 111, 115), 197, 331, fill=(140, 211, 82, 255), width=5)
    d.arc((23, 25, 104, 107), 201, 326, fill=(224, 250, 151, 255), width=2)
    d.ellipse((42, 36, 89, 93), fill=INK)
    d.ellipse((46, 34, 84, 86), fill=(161, 86, 36, 255), outline=GOLD, width=5)
    d.ellipse((53, 41, 67, 70), fill=(229, 155, 59, 255))
    d.arc((51, 42, 80, 82), 270, 95, fill=(255, 215, 115, 255), width=4)
    d.line((65, 45, 69, 76), fill=(94, 48, 29, 255), width=4)
    d.ellipse((63, 73, 70, 80), fill=(255, 231, 127, 255))
    d.line((66, 37, 67, 22), fill=LEAF, width=5)
    leaf(d, 67, 27, -2.7, 17, LIME)
    leaf(d, 67, 27, -.35, 14, (93, 194, 67, 255))
    for x, y in [(25, 88), (100, 61), (98, 93)]:
        d.ellipse((x-3, y-3, x+3, y+3), fill=WHITE)


def icon_solar(d):
    d.ellipse((15, 32, 59, 76), fill=GOLD, outline=INK, width=4)
    d.ellipse((25, 42, 49, 66), fill=WHITE)
    for a in range(0, 360, 45):
        x1, y1 = 37 + math.cos(math.radians(a))*25, 54 + math.sin(math.radians(a))*25
        x2, y2 = 37 + math.cos(math.radians(a))*37, 54 + math.sin(math.radians(a))*37
        d.line((x1, y1, x2, y2), fill=LIME, width=5)
    d.polygon([(48, 46), (94, 34), (117, 24), (104, 52), (119, 71), (93, 63), (49, 60)], fill=(83, 183, 55, 255), outline=INK)
    d.polygon([(50, 50), (96, 42), (113, 34), (101, 52), (113, 63), (94, 57), (50, 57)], fill=GOLD)
    d.line((52, 53, 108, 49), fill=WHITE, width=6)
    leaf(d, 28, 87, -1.0, 20, (87, 191, 63, 255))
    leaf(d, 25, 89, 2.25, 17, LIME)
    d.polygon([(102, 78), (111, 69), (108, 82), (120, 88), (105, 87), (98, 99)], fill=WHITE)


def icon_bloom(d):
    d.polygon([(18, 97), (34, 75), (50, 92), (66, 67), (82, 91), (105, 72), (117, 104), (22, 108)], fill=(38, 125, 54, 255), outline=INK)
    for pts in [[(30, 92), (27, 62), (42, 81)], [(55, 89), (61, 52), (70, 81)], [(86, 90), (101, 60), (98, 88)]]:
        d.polygon(pts, fill=LIME, outline=INK)
    d.line((64, 86, 64, 58), fill=(185, 239, 81, 255), width=8)
    cx, cy = 64, 43
    for a in range(0, 360, 60):
        dx, dy = math.cos(math.radians(a)), math.sin(math.radians(a))
        d.ellipse((cx+dx*19-13, cy+dy*19-10, cx+dx*19+13, cy+dy*19+10), fill=PINK, outline=INK, width=3)
        d.ellipse((cx+dx*19-8, cy+dy*19-6, cx+dx*19+6, cy+dy*19+7), fill=(255, 178, 192, 255))
    d.ellipse((52, 31, 76, 55), fill=GOLD, outline=INK, width=3)
    d.ellipse((58, 37, 70, 49), fill=WHITE)
    for x, y in [(35, 26), (93, 31), (42, 57), (88, 58)]:
        d.ellipse((x-3, y-3, x+3, y+3), fill=WHITE)


for name, art in [("venusaur_vine", icon_vine), ("venusaur_seed", icon_seed),
                  ("venusaur_solar", icon_solar), ("venusaur_bloom", icon_bloom)]:
    make_icon(name, art)


def save_strip(name, painter, size=(128, 32)):
    im = Image.new("RGBA", size, (0, 0, 0, 0))
    painter(ImageDraw.Draw(im), *size)
    im.save(EFFECT / f"venusaur_{name}.png", optimize=True)


def vine_texture(d, w, h):
    d.line((0, 16, w, 16), fill=(20, 67, 36, 180), width=24)
    d.line((0, 15, w, 15), fill=(49, 145, 57, 235), width=16)
    d.line((0, 13, w, 13), fill=(167, 231, 76, 235), width=5)
    d.line((0, 19, w, 19), fill=(223, 255, 139, 210), width=2)
    for x in range(8, w, 16):
        d.polygon([(x-5, 6), (x+3, 13), (x+1, 2)], fill=(213, 250, 113, 255))
        d.polygon([(x+3, 26), (x+9, 18), (x+11, 29)], fill=(89, 184, 60, 255))
    for x in range(12, w, 32): d.ellipse((x-2, 10, x+2, 14), fill=WHITE)


def leaf_texture(d, w, h):
    for x in range(-8, w+16, 24):
        d.polygon([(x-13, 27), (x+2, 3), (x+13, 16), (x+1, 28)], fill=(36, 122, 49, 235), outline=(18, 66, 35, 255))
        d.line((x-10, 26, x+2, 7), fill=(207, 249, 112, 255), width=2)
        d.polygon([(x+1, 16), (x+17, 8), (x+11, 24)], fill=(133, 211, 68, 240))


save_strip("vine_strip", vine_texture)
save_strip("leaf_strip", leaf_texture)

seed = Image.new("RGBA", (64, 64), (0, 0, 0, 0)); sd = ImageDraw.Draw(seed)
sd.ellipse((11, 5, 53, 59), fill=(46, 30, 24, 255), outline=(255, 220, 99, 255), width=4)
sd.ellipse((17, 9, 46, 53), fill=(180, 96, 38, 255))
sd.ellipse((21, 12, 30, 37), fill=(255, 199, 79, 255))
sd.arc((17, 10, 48, 54), 260, 95, fill=(255, 242, 151, 255), width=4)
sd.line((32, 14, 34, 48), fill=(90, 45, 26, 255), width=3)
sd.polygon([(31, 10), (33, 1), (37, 11)], fill=(163, 227, 75, 255))
seed.save(EFFECT / "venusaur_seed_projectile.png", optimize=True)

beam = Image.new("RGBA", (128, 32), (0, 0, 0, 0)); bd = ImageDraw.Draw(beam)
bd.rectangle((0, 3, 127, 28), fill=(72, 187, 55, 185))
bd.rectangle((0, 7, 127, 24), fill=(255, 236, 101, 235))
bd.rectangle((0, 11, 127, 20), fill=(255, 255, 220, 255))
bd.line((0, 8, 127, 8), fill=(207, 255, 130, 255), width=2)
for x in range(3, 128, 18): bd.polygon([(x, 16), (x+8, 8), (x+13, 16), (x+8, 24)], fill=(255, 255, 202, 230))
beam.save(EFFECT / "venusaur_solar_beam.png", optimize=True)


def radial_texture(name, flower=False):
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    cx = cy = 64
    d.ellipse((8, 8, 120, 120), fill=(20, 67, 34, 95 if flower else 140), outline=(121, 205, 61, 230), width=4)
    d.ellipse((18, 18, 110, 110), outline=(207, 247, 106, 235), width=3)
    d.ellipse((30, 30, 98, 98), outline=(66, 151, 53, 245), width=6)
    for i in range(12):
        a = math.tau * i / 12
        x, y = cx + math.cos(a)*48, cy + math.sin(a)*48
        if flower:
            for j in range(5):
                aa = math.tau*j/5
                px, py = x+math.cos(aa)*5, y+math.sin(aa)*5
                d.ellipse((px-4, py-4, px+4, py+4), fill=(255, 115, 166, 240), outline=(255, 232, 133, 255))
            d.ellipse((x-3, y-3, x+3, y+3), fill=(255, 236, 122, 255))
        else:
            d.polygon([(cx+math.cos(a)*18, cy+math.sin(a)*18),
                       (cx+math.cos(a-.2)*52, cy+math.sin(a-.2)*52),
                       (cx+math.cos(a+.2)*52, cy+math.sin(a+.2)*52)], fill=(101, 190, 59, 210), outline=(222, 250, 121, 255))
            d.line((cx+math.cos(a)*19, cy+math.sin(a)*19, x, y), fill=(255, 230, 126, 240), width=2)
    im.save(EFFECT / f"venusaur_{name}.png", optimize=True)


def sludge_pool_texture():
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    # Broken lobed silhouette keeps the decal organic instead of a flat green target.
    lobes = []
    for i in range(64):
        a = math.tau * i / 64
        r = 55 + 4 * math.sin(a * 3 + .4) + 3 * math.sin(a * 7)
        lobes.append((64 + math.cos(a) * r, 64 + math.sin(a) * r * .78))
    d.polygon(lobes, fill=(67, 18, 103, 235), outline=(210, 112, 255, 255))
    d.ellipse((18, 29, 110, 99), fill=(116, 40, 168, 238), outline=(164, 74, 211, 255), width=3)
    d.ellipse((29, 37, 99, 91), fill=(82, 25, 132, 245))
    d.ellipse((40, 43, 81, 73), fill=(179, 91, 221, 235))
    d.ellipse((47, 46, 69, 56), fill=(245, 204, 255, 245))
    # Bubbles and hanging drops make the pool readable at ground scale.
    for x, y, r in [(27, 55, 5), (93, 65, 7), (53, 82, 4), (76, 35, 4), (34, 76, 3)]:
        d.ellipse((x-r, y-r, x+r, y+r), fill=(193, 108, 231, 230), outline=(242, 201, 255, 240), width=2)
        d.ellipse((x-r//2, y-r//2, x, y), fill=(255, 233, 255, 230))
    for x, y, r in [(25, 48, 7), (99, 53, 6), (70, 88, 5)]:
        d.ellipse((x-r//2, y, x+r//2, y+14), fill=(127, 43, 173, 235), outline=(213, 134, 238, 230), width=2)
        d.ellipse((x-r//2, y-3, x+r//2, y+5), fill=(193, 108, 231, 240))
    im.save(EFFECT / "venusaur_sludge_bomb.png", optimize=True)


radial_texture("ground_roots", False)
radial_texture("flower_sea", True)
sludge_pool_texture()
print("generated referenced Venusaur skill icons and vivid layered effect textures")

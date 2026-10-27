"""Regenerate Venusaur Power Whip, Razor Leaf and Sludge Bomb assets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/pkmoba/textures/gui/skills"
ROOT.mkdir(parents=True, exist_ok=True)
SIZE = 128
INK = (22, 25, 32, 255)
DARK = (32, 47, 42, 255)


def base():
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((4, 4, 124, 124), radius=25, fill=(10, 27, 19, 230), outline=(7, 22, 16, 255), width=5)
    d.rounded_rectangle((9, 9, 119, 119), radius=20, fill=(27, 83, 46, 255), outline=(102, 171, 67, 255), width=3)
    return im, d


def vine_icon():
    im, d = base()
    pts = [(18, 107), (31, 95), (45, 81), (48, 65), (62, 52), (78, 48), (91, 35), (104, 20)]
    d.line(pts, fill=(12, 38, 23, 255), width=29, joint="curve")
    d.line(pts, fill=(48, 137, 57, 255), width=21, joint="curve")
    d.line(pts, fill=(191, 239, 92, 255), width=7, joint="curve")
    d.polygon([(95, 27), (118, 10), (111, 39), (103, 35)], fill=(48, 137, 57, 255), outline=INK)
    # The broad, thorn-edged lash makes the move read as Power Whip at HUD size.
    for x, y, a in [(33, 93, -2.2), (48, 72, -1.0), (70, 49, .2), (91, 36, 1.7)]:
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        tip = (x + ux * 20, y + uy * 20)
        d.polygon([(x-ux*6, y-uy*6), (x+px*9, y+py*9), tip,
                   (x-px*9, y-py*9)], fill=(139, 210, 68, 255), outline=(19, 63, 34, 255))
        d.line((x, y, tip[0], tip[1]), fill=(225, 255, 144, 255), width=3)
    for x, y in [(32, 88), (54, 63), (78, 43)]:
        d.polygon([(x, y), (x-19, y-8), (x-7, y+7), (x+1, y+3)], fill=(107, 197, 68, 255), outline=(16, 53, 32, 255))
        d.line((x-15, y-6, x, y+2), fill=(221, 251, 134, 255), width=2)
    d.ellipse((102, 14, 116, 28), fill=(255, 255, 224, 255), outline=INK, width=2)
    im.resize((32, 32), Image.Resampling.LANCZOS).save(ROOT / "venusaur_vine.png", optimize=True)


def sludge_icon():
    im, d = base()
    # Toxic burst silhouette and thick, glossy purple sludge splash.
    cx, cy = 67, 69
    for i in range(11):
        a = math.tau * i / 11 - math.pi / 2
        inner = 31 + (i % 3) * 4
        outer = 52 + (i % 2) * 5
        p1 = (cx + math.cos(a - .16) * inner, cy + math.sin(a - .16) * inner)
        p2 = (cx + math.cos(a) * outer, cy + math.sin(a) * outer)
        p3 = (cx + math.cos(a + .16) * inner, cy + math.sin(a + .16) * inner)
        d.polygon([p1, p2, p3], fill=(125, 57, 177, 255), outline=(37, 23, 55, 255))
    d.ellipse((29, 35, 101, 101), fill=(47, 27, 63, 255), outline=(26, 18, 37, 255), width=4)
    d.ellipse((34, 31, 96, 91), fill=(133, 65, 184, 255), outline=(213, 155, 244, 255), width=4)
    d.ellipse((43, 40, 87, 81), fill=(172, 101, 213, 255))
    d.ellipse((48, 41, 61, 53), fill=(237, 203, 255, 255))
    d.arc((41, 35, 91, 82), 205, 332, fill=(255, 232, 255, 255), width=4)
    # Splash droplets, with contrasting pale highlights to stay legible when reduced.
    for x, y, r in [(27, 50, 8), (101, 43, 7), (104, 78, 9), (38, 100, 7), (82, 105, 6)]:
        d.ellipse((x-r, y-r, x+r, y+r), fill=(167, 89, 211, 255), outline=(39, 24, 55, 255), width=3)
        d.ellipse((x-r//2, y-r//2, x-r//2+4, y-r//2+4), fill=(246, 213, 255, 255))
    # Clear type-signaling toxic-green bubbles, not flowers.
    for x, y, r in [(47, 26, 5), (87, 27, 4), (23, 78, 4), (91, 96, 4)]:
        d.ellipse((x-r, y-r, x+r, y+r), fill=(184, 236, 87, 255), outline=(39, 69, 39, 255), width=2)
    im.resize((32, 32), Image.Resampling.LANCZOS).save(ROOT / "venusaur_bloom.png", optimize=True)


def razor_assets():
    im, d = base()
    for x, y, length in [(38, 89, 53), (61, 67, 60), (88, 49, 48)]:
        d.line((x-29, y+23, x-8, y+7), fill=(198, 246, 118, 255), width=5)
        d.line((x-27, y+13, x-8, y+1), fill=(91, 188, 74, 255), width=3)
        tip = (x+length*.46, y-length*.62)
        base_point = (x-length*.25, y+length*.30)
        d.polygon([base_point, (x-15, y-10), (x-8, y-14), (x-7, y-24), tip,
                   (x+17, y-6), (x+11, y+1), (x+12, y+9)],
                  fill=(124, 219, 65, 255), outline=(15, 55, 27, 255))
        d.line((base_point[0], base_point[1], tip[0], tip[1]), fill=(249, 255, 181, 255), width=4)
    icon = im.resize((32, 32), Image.Resampling.LANCZOS)
    for name in ["venusaur_razor", "venusaur_seed", "venusaur_1"]:
        icon.save(ROOT / (name + ".png"), optimize=True)
    effect = Image.new("RGBA", (96, 48), (0, 0, 0, 0))
    ed = ImageDraw.Draw(effect)
    ed.polygon([(3, 24), (22, 9), (28, 13), (42, 3), (48, 9), (70, 5), (94, 24),
                (70, 43), (61, 38), (45, 46), (37, 38), (22, 40)],
               fill=(109, 212, 60, 255), outline=(17, 64, 31, 255))
    ed.line((8, 24, 89, 24), fill=(244, 255, 175, 255), width=3)
    for x in [26, 42, 59, 73]:
        ed.line((x-9, 24, x+4, 12), fill=(199, 247, 115, 255), width=2)
        ed.line((x-9, 24, x+4, 36), fill=(52, 143, 50, 255), width=2)
    effect.save(ROOT.parents[1] / "effect/venusaur_razor_leaf.png", optimize=True)


vine_icon()
razor_assets()
sludge_icon()
print("updated Power Whip, Razor Leaf and Sludge Bomb assets")

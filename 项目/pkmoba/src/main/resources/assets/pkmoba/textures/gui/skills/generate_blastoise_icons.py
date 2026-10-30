"""生成水箭龟四技能图标（0.38 槽位：0 加农水炮 / 1 高速旋转 / 2 冲浪术 / 3 喷水）。

用法：python generate_blastoise_icons.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent
PALETTE = {
    "outline": (18, 51, 74, 255), "frame": (36, 81, 111, 255),
    "water": (58, 158, 198, 255), "light": (190, 241, 247, 255),
    "foam": (230, 246, 242, 255), "deep": (31, 105, 151, 255),
}


def base():
    image = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((1, 1, 30, 30), radius=3, fill=PALETTE["frame"], outline=PALETTE["outline"])
    draw.rectangle((3, 3, 28, 28), fill=(207, 233, 236, 255))
    return image, draw


def cannon(draw):
    """0 加农水炮：炮口 + 横向高压水柱（沿用旧版加农水炮图案）。"""
    draw.polygon([(4,12),(10,12),(13,9),(18,9),(21,12),(28,12),(27,18),(22,18),(19,22),(13,22),(10,18),(5,18)],
                 fill=PALETTE["deep"], outline=PALETTE["outline"])
    draw.polygon([(9,15),(14,13),(21,15),(16,17)], fill=PALETTE["light"])
    draw.rectangle((23,13,28,17), fill=(162,222,235,255))
    draw.line((26,10,29,8), fill=PALETTE["foam"], width=1)
    draw.line((29,14,31,14), fill=PALETTE["light"], width=1)


def spin(draw):
    """1 高速旋转：环形水流 + 旋转箭头（沿用旧版高速旋转图案）。"""
    draw.ellipse((7,7,25,25), outline=PALETTE["outline"], width=2)
    draw.arc((8,8,24,24), 205, 520, fill=PALETTE["water"], width=5)
    draw.polygon([(21,7),(27,8),(23,13)], fill=(181,235,241,255), outline=PALETTE["outline"])
    draw.ellipse((13,13,19,19), fill=PALETTE["foam"], outline=PALETTE["outline"])


def surf(draw):
    """2 冲浪术：卷起的浪头 + 泡沫 + 冲浪板。"""
    # 浪身
    draw.polygon([(3,26),(4,20),(8,15),(13,11),(19,10),(24,12),(27,16),(24,19),(20,20),(18,23),(13,24),(9,26)],
                 fill=PALETTE["water"], outline=PALETTE["outline"])
    # 浪尖卷曲
    draw.arc((15,9,27,21), 120, 320, fill=PALETTE["deep"], width=2)
    draw.arc((17,11,25,19), 130, 300, fill=PALETTE["light"], width=2)
    # 泡沫冠
    draw.polygon([(5,19),(8,15),(12,12),(17,10),(19,12),(15,13),(11,16),(8,19)], fill=PALETTE["foam"])
    draw.ellipse((6,15,9,18), fill=PALETTE["foam"])
    draw.ellipse((17,9,20,12), fill=PALETTE["foam"])
    # 冲浪板
    draw.line((10,22,22,26), fill=(207,233,236,255), width=2)
    draw.line((11,22,21,26), fill=PALETTE["deep"], width=1)
    # 底部水花
    draw.line((4,27,9,27), fill=PALETTE["light"], width=1)
    draw.line((14,28,21,28), fill=PALETTE["light"], width=1)


def water_gun(draw):
    """3 喷水：炮口喷出扇形水花与水滴（由旧版冰山图改造而来）。"""
    # 炮管
    draw.rounded_rectangle((4,13,12,20), radius=2, fill=PALETTE["deep"], outline=PALETTE["outline"])
    draw.rectangle((12,14,15,19), fill=PALETTE["water"], outline=PALETTE["outline"])
    draw.ellipse((5,14,10,19), fill=PALETTE["light"])
    # 水柱
    draw.polygon([(15,14),(22,11),(28,9),(23,14),(27,14),(21,18),(24,20),(16,19)],
                 fill=PALETTE["water"], outline=PALETTE["outline"])
    draw.polygon([(16,15),(21,13),(25,12),(20,16),(23,17),(17,18)], fill=PALETTE["light"])
    # 水滴
    for x, y in ((20, 7), (25, 6), (19, 22), (25, 22), (28, 18)):
        draw.ellipse((x, y, x + 2, y + 2), fill=PALETTE["foam"], outline=PALETTE["outline"])


ICONS = (
    ("加农水炮", cannon),
    ("高速旋转", spin),
    ("冲浪术", surf),
    ("喷水", water_gun),
)

for index, (name, painter) in enumerate(ICONS):
    image, draw = base()
    painter(draw)
    image.save(OUT / f"blastoise_{index}.png", optimize=True)
    print(f"blastoise_{index}.png <- {name}")

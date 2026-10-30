"""生成 0.30 新增资源：三件新道具贴图 + 两种宝可梦 32×32 头像。

用法：python tools/generate_030_assets.py
输出：
  src/main/resources/assets/pkmoba/textures/item/{species_switch,player_marker,pokemon_marker}.png
  src/main/resources/assets/pkmoba/textures/gui/pokemon/{charizard,blastoise}.png
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ITEM_OUT = ROOT / "src/main/resources/assets/pkmoba/textures/item"
MON_OUT = ROOT / "src/main/resources/assets/pkmoba/textures/gui/pokemon"
ITEM_OUT.mkdir(parents=True, exist_ok=True)
MON_OUT.mkdir(parents=True, exist_ok=True)

S = 32
OUTLINE = (76, 48, 35, 255)
SHADOW = (112, 76, 47, 255)
CREAM = (248, 224, 167, 255)
RED = (183, 57, 39, 255)
DARK_RED = (128, 38, 28, 255)
WHITE = (255, 244, 211, 255)
BAND = (45, 32, 24, 255)
GOLD = (238, 167, 58, 255)
DARK_GOLD = (150, 100, 30, 255)
BLUE = (93, 137, 182, 255)
DARK_BLUE = (52, 86, 122, 255)
ORANGE = (222, 118, 46, 255)
DARK_ORANGE = (152, 74, 26, 255)


def frame(d):
    d.rounded_rectangle((1, 1, 30, 30), radius=3, fill=SHADOW, outline=OUTLINE, width=1)
    d.rectangle((3, 3, 28, 28), fill=CREAM)


def blank():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def species_switch():
    """两只宝可梦头像 + 循环箭头。"""
    im, d = blank()
    frame(d)
    d.ellipse((6, 7, 14, 15), fill=ORANGE, outline=DARK_ORANGE, width=1)
    d.ellipse((17, 7, 25, 15), fill=BLUE, outline=DARK_BLUE, width=1)
    # 循环箭头
    d.arc((7, 16, 25, 26), 200, 340, fill=BAND, width=2)
    d.polygon([(23, 17), (27, 21), (22, 22)], fill=BAND)
    d.arc((7, 16, 25, 26), 20, 160, fill=BAND, width=2)
    d.polygon([(9, 25), (5, 21), (10, 20)], fill=BAND)
    return im


def player_marker():
    """玩家点位：红色小人 + 底座。"""
    im, d = blank()
    frame(d)
    d.ellipse((13, 6, 19, 12), fill=WOOD, outline=OUTLINE, width=1)
    d.rectangle((13, 13, 19, 21), fill=RED, outline=DARK_RED, width=1)
    d.rectangle((10, 13, 12, 20), fill=RED, outline=DARK_RED, width=1)
    d.rectangle((20, 13, 22, 20), fill=RED, outline=DARK_RED, width=1)
    d.rectangle((13, 22, 15, 27), fill=BAND)
    d.rectangle((17, 22, 19, 27), fill=BAND)
    return im


def pokemon_marker():
    """宝可梦点位：精灵球底座 + 爪印。"""
    im, d = blank()
    frame(d)
    d.ellipse((6, 12, 26, 28), fill=RED, outline=OUTLINE, width=1)
    d.chord((6, 12, 26, 28), 0, 180, fill=WHITE)
    d.rectangle((5, 19, 27, 21), fill=BAND)
    d.ellipse((13, 17, 19, 23), fill=WHITE, outline=BAND, width=1)
    d.ellipse((8, 5, 12, 9), fill=ORANGE, outline=OUTLINE, width=1)
    d.ellipse((14, 3, 18, 7), fill=ORANGE, outline=OUTLINE, width=1)
    d.ellipse((20, 5, 24, 9), fill=ORANGE, outline=OUTLINE, width=1)
    return im


WOOD = (214, 178, 128, 255)


def charizard_portrait():
    """喷火龙 32×32 头像：橙色龙脸 + 角 + 眼睛 + 鼻孔。"""
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # 头部
    d.ellipse((4, 6, 28, 30), fill=ORANGE, outline=DARK_ORANGE, width=2)
    # 头上双角
    d.polygon([(7, 9), (10, 1), (14, 8)], fill=(247, 205, 122, 255), outline=DARK_ORANGE)
    d.polygon([(18, 8), (22, 1), (25, 9)], fill=(247, 205, 122, 255), outline=DARK_ORANGE)
    # 腹部浅色
    d.ellipse((11, 17, 21, 29), fill=(248, 216, 140, 255))
    # 眼睛
    d.ellipse((9, 12, 14, 17), fill=WHITE, outline=OUTLINE)
    d.ellipse((18, 12, 23, 17), fill=WHITE, outline=OUTLINE)
    d.ellipse((11, 13, 13, 16), fill=(38, 28, 22, 255))
    d.ellipse((19, 13, 21, 16), fill=(38, 28, 22, 255))
    # 鼻孔
    d.rectangle((13, 20, 14, 21), fill=DARK_ORANGE)
    d.rectangle((18, 20, 19, 21), fill=DARK_ORANGE)
    # 嘴
    d.arc((11, 20, 21, 28), 20, 160, fill=DARK_ORANGE, width=2)
    return im


def blastoise_portrait():
    """水箭龟 32×32 头像：蓝色龟头 + 双炮管。"""
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # 双炮管
    d.rounded_rectangle((1, 13, 9, 18), radius=2, fill=(176, 190, 198, 255), outline=(84, 96, 104, 255))
    d.rounded_rectangle((23, 13, 31, 18), radius=2, fill=(176, 190, 198, 255), outline=(84, 96, 104, 255))
    # 头部
    d.ellipse((6, 6, 26, 28), fill=BLUE, outline=DARK_BLUE, width=2)
    # 龟壳边
    d.arc((4, 2, 28, 22), 200, 340, fill=(140, 96, 58, 255), width=3)
    # 眼睛
    d.ellipse((10, 13, 15, 18), fill=WHITE, outline=OUTLINE)
    d.ellipse((17, 13, 22, 18), fill=WHITE, outline=OUTLINE)
    d.ellipse((12, 14, 14, 17), fill=(38, 28, 22, 255))
    d.ellipse((18, 14, 20, 17), fill=(38, 28, 22, 255))
    # 嘴
    d.arc((11, 18, 21, 26), 20, 160, fill=DARK_BLUE, width=2)
    return im


species_switch().save(ITEM_OUT / "species_switch.png")
player_marker().save(ITEM_OUT / "player_marker.png")
pokemon_marker().save(ITEM_OUT / "pokemon_marker.png")
charizard_portrait().save(MON_OUT / "charizard.png")
blastoise_portrait().save(MON_OUT / "blastoise.png")
print("wrote", ITEM_OUT, MON_OUT)

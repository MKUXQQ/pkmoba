from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/pkmoba/textures/item"
OUT.mkdir(parents=True, exist_ok=True)
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


def frame(d):
    d.rounded_rectangle((1, 1, 30, 30), radius=3, fill=SHADOW, outline=OUTLINE, width=1)
    d.rectangle((3, 3, 28, 28), fill=CREAM)


def pokeball():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    frame(d)
    # top red half
    d.pieslice((8, 8, 24, 24), 180, 360, fill=RED, outline=OUTLINE, width=1)
    # bottom white half
    d.pieslice((8, 8, 24, 24), 0, 180, fill=WHITE, outline=OUTLINE, width=1)
    # dark horizontal band
    d.rectangle((7, 15, 25, 17), fill=BAND)
    # center button
    d.ellipse((13, 13, 19, 19), fill=BAND, outline=WHITE, width=1)
    d.ellipse((15, 15, 17, 17), fill=WHITE)
    return im


def crown():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    frame(d)
    # crown base band
    d.rectangle((7, 19, 25, 25), fill=GOLD, outline=DARK_GOLD, width=1)
    # three spikes
    d.polygon([(7, 20), (7, 10), (10, 19)], fill=GOLD, outline=DARK_GOLD)
    d.polygon([(11, 19), (16, 6), (21, 19)], fill=GOLD, outline=DARK_GOLD)
    d.polygon([(22, 20), (25, 10), (25, 19)], fill=GOLD, outline=DARK_GOLD)
    # jewels
    d.ellipse((14, 21, 18, 25), fill=RED, outline=DARK_GOLD, width=1)
    d.rectangle((9, 13, 11, 15), fill=BLUE)
    d.rectangle((21, 13, 23, 15), fill=BLUE)
    return im


pokeball().save(OUT / "battle_menu.png")
crown().save(OUT / "admin_duel.png")
print("wrote", OUT)

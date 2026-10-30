"""重建皮卡丘立体网格（球/环/闪电管）用的 UV 贴图。

原来的 64x64 贴图四条色带里有一条是暗棕 (181,109,19)：orb/net/ring 这些闭合网格把整张
UV 铺满球面，那条暗色带在低分辨率下平均成「灰褐色」，看起来就是灰蒙蒙的一坨。
这里把暗色带换成饱和琥珀色，整体保持电系黄→白的高亮阶梯，网格才有自发光感。

    python tools/rebuild_pikachu_mesh_texture.py
"""

from pathlib import Path

import math
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/pkmoba/textures/effect/pikachu_mesh.png"

# 由亮到暗，全部都是电系黄系；最暗的一档也要饱和，不能是灰褐
BANDS = [
    (255, 255, 236, 255),   # 白热核心
    (255, 240, 138, 255),   # 亮黄
    (252, 205, 46, 255),    # 金黄
    (250, 166, 26, 255),    # 琥珀（原来的暗棕位置）
]


def main() -> None:
    im = Image.new("RGBA", (64, 64))
    for y in range(64):
        for x in range(64):
            stripe = (x + int(3 * math.sin(y * 0.18))) % 16
            if stripe < 3:
                color = BANDS[0]
            elif stripe < 7:
                color = BANDS[1]
            elif stripe < 13:
                color = BANDS[2]
            else:
                color = BANDS[3]
            im.putpixel((x, y), color)
    im.save(OUT, optimize=True)
    print("已重建", OUT.relative_to(ROOT), im.size)


if __name__ == "__main__":
    main()

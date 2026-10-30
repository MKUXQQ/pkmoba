"""从 Windows 自带的 Noto Sans SC（Android/手机默认中文黑体同族）生成 mod 用的静态 UI 字体。

做三件事：
1. 把可变字体（VF）实例化到 Regular 400 —— Minecraft 的 TTF 字体加载器只取默认实例，
   留 VF 会浪费体积且有渲染异常风险。
2. 子集化：只保留游戏里真正用得到的码位（ASCII / 拉丁补充 / 通用标点 / 箭头几何图形 /
   CJK 标点 / 常用 CJK 统一表意 / 全角形式）。体积从 17.7MB 降到可随 mod 分发的大小。
3. 输出到 mod 资源目录 `assets/pkmoba/font/pkmoba_ui.ttf`。

用法：python tools/build_ui_font.py
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SRC = r"C:\Windows\Fonts\NotoSansSC-VF.ttf"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "pkmoba", "font", "pkmoba_ui.ttf")

UNICODES = ",".join([
    "U+0020-007E",   # ASCII
    "U+00A0-00FF",   # 拉丁补充（· × ° 等）
    "U+2000-206F",   # 通用标点（— – ‘ ’ “ ” … ‰）
    "U+20A0-20BF",   # 货币
    "U+2190-21FF",   # 箭头
    "U+2200-22FF",   # 数学符号（≤ ≥ ≠ ∞）
    "U+2460-24FF",   # ① ② ③
    "U+2500-257F",   # 制表符
    "U+25A0-25FF",   # 几何图形（● ○ ■ □ ▲ ▼）
    "U+2600-26FF",   # 杂项符号（★ ☆ ☠）
    "U+3000-303F",   # CJK 标点
    "U+4E00-9FFF",   # CJK 统一表意文字
    "U+FF00-FFEF",   # 全角形式
])


def main() -> int:
    if not os.path.exists(SRC):
        print("missing source font: " + SRC)
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    print("loading " + SRC)
    font = TTFont(SRC)

    # 轴名在不同版本里是大写属性，直接按 wght 定位。
    if "fvar" in font:
        print("instancing wght=400")
        font = instancer.instantiateVariableFont(font, {"wght": 400}, inplace=False)

    options = subset.Options()
    options.layout_features = ["kern", "liga", "ccmp", "locl", "mark", "mkmk"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.notdef_outline = True
    options.recalc_bounds = True
    options.drop_tables += ["DSIG"]
    options.hinting = True

    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=subset.parse_unicodes(UNICODES))
    subsetter.subset(font)

    font.flavor = None
    font.save(OUT)
    size = os.path.getsize(OUT)
    print("wrote %s (%.2f MB)" % (OUT, size / 1024.0 / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())

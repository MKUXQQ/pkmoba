"""抓取官方 Q 版/官方绘图并裁成 64x64 头像。

用法：
    python tools/fetch_pokemon_portraits.py

来源：PokeAPI/sprites 仓库的 official-artwork（官方绘图，CC-BY 由宝可梦公司持有），
只做「裁掉身体、保留头部」的方形缩放，不重绘。生成结果用于 PKMOBA 的
选择界面与对战 HUD 头像，属于运行时读取的装饰性资源。

若网络不可用，脚本会退化为本地绘制的简易像素头像，并把来源写进 SOURCE.md。
"""

from __future__ import annotations

import io
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/pkmoba/textures/gui/pokemon"
RAW = Path(__file__).resolve().parent / ".cache_portraits"

SIZE = 64
BORDER_OUT = (74, 58, 40)
BORDER_IN = (243, 226, 190)

SPECIES = {
    "charizard": 6,
    "blastoise": 9,
}

ART = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/{}.png"


def download(dex: int) -> bytes | None:
    url = ART.format(dex)
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            return resp.read()
    except Exception as exc:  # noqa: BLE001
        print(f"  下载失败 {url}: {exc}")
        return None


def crop_head(img: Image.Image) -> Image.Image:
    """从全身图里裁出头部区域，缩放成正方形。"""
    img = img.convert("RGBA")
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    w, h = img.size
    # 顶部 62% 通常是头/上半身，再取其中居中的正方形
    head_h = max(1, int(h * 0.62))
    head = img.crop((0, 0, w, head_h))
    side = min(head.width, head.height)
    left = (head.width - side) // 2
    head = head.crop((left, 0, left + side, side))
    return head.resize((SIZE, SIZE), Image.LANCZOS)


def fallback(name: str) -> Image.Image:
    """离线兜底：画一个简单的圆脸像素头像。"""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    base = (214, 90, 42, 255) if name == "charizard" else (78, 138, 196, 255)
    d.ellipse((4, 4, SIZE - 5, SIZE - 5), fill=base, outline=BORDER_OUT, width=3)
    d.ellipse((18, 22, 30, 36), fill=(255, 255, 255, 255))
    d.ellipse((36, 22, 48, 36), fill=(255, 255, 255, 255))
    d.ellipse((24, 27, 31, 34), fill=(30, 25, 20, 255))
    d.ellipse((41, 27, 48, 34), fill=(30, 25, 20, 255))
    d.arc((22, 36, 44, 54), start=15, end=165, fill=(40, 30, 24, 255), width=3)
    return img


def frame(img: Image.Image) -> Image.Image:
    """加米黄内描边 + 深棕外描边，和面板风格统一。"""
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    inner = img.resize((SIZE - 8, SIZE - 8), Image.LANCZOS)
    canvas.paste(inner, (4, 4), inner)
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((1, 1, SIZE - 2, SIZE - 2), radius=6, outline=BORDER_OUT, width=3)
    d.rounded_rectangle((4, 4, SIZE - 5, SIZE - 5), radius=4, outline=BORDER_IN, width=1)
    return canvas


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    RAW.mkdir(parents=True, exist_ok=True)
    sources = []
    for name, dex in SPECIES.items():
        data = download(dex)
        if data:
            raw = Image.open(io.BytesIO(data))
            raw.save(RAW / f"{dex}.png")
            head = crop_head(raw)
            head = head.filter(ImageFilter.SMOOTH)
            sources.append((name, ART.format(dex)))
            print(f"{name}: 使用官方绘图 {ART.format(dex)}")
        else:
            head = fallback(name)
            sources.append((name, "本地生成的兜底像素头像"))
            print(f"{name}: 使用兜底头像")
        frame(head).save(OUT / f"{name}.png")

    lines = [
        "# 宝可梦头像资源来源",
        "",
        "本目录下的 64×64 PNG 由 `tools/fetch_pokemon_portraits.py` 生成，仅做头部裁剪与描边，未重绘。",
        "",
    ]
    for name, src in sources:
        lines.append(f"- `{name}.png` ← {src}")
    lines += [
        "",
        "原始图案版权归 Pokémon / Nintendo / Game Freak 所有，仅供本地整合包内展示使用。",
    ]
    (OUT / "SOURCE.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("完成：", OUT)


if __name__ == "__main__":
    main()

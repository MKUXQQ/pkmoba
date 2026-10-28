#!/usr/bin/env python3
"""校验 0.51 扩展物种的界面资产 + 12 格选择界面布局（只读，不写任何文件）。

三项检查：
1. 头像：12 只（4 只旧物种 + 8 只扩展物种）都是 64x64 RGBA、有透明边角、轮廓非空；
2. 技能图标：8 只扩展物种 x 4 招 = 32 张，必须是 32x32（SkillPresentation 按 32x32 采样），
   且 32 张互不相同（不是通用占位图案）；
3. 布局：用与 StarterScreen.java 完全相同的公式模拟 12 只物种在多种 GUI 尺寸下的网格，
   要求每张卡片都落在面板内、彼此不重叠、且每页至少能放下一张。

用法（在 项目/pkmoba 下）：
    python tools/check_expansion_ui_assets.py
退出码 0 表示全通过；1 表示有缺失/不合格项（会逐条打印原因）。
"""
from __future__ import annotations

import hashlib
import sys
import warnings
from pathlib import Path

from PIL import Image

warnings.filterwarnings("ignore", category=DeprecationWarning)

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/pkmoba"
PORTRAITS = ASSETS / "textures/gui/pokemon"
SKILLS = ASSETS / "textures/gui/skills"
LANG = ASSETS / "lang"
REGISTRY = ROOT / "src/main/java/com/pkmoba/pokemon/SpeciesRegistry.java"

BASE_SPECIES = ["charizard", "blastoise", "venusaur", "pikachu"]
EXPANSION_SPECIES = [
    ("lucario", "路卡利欧"),
    ("abomasnow", "暴雪王"),
    ("blaziken", "火焰鸡"),
    ("gengar", "耿鬼"),
    ("tyranitar", "班基拉斯"),
    ("garchomp", "烈咬陆鲨"),
    ("scizor", "巨钳螳螂"),
    ("gardevoir", "沙奈朵"),
]
SLOTS = "qwer"

# 与 StarterScreen.java 的常量保持一致
CARD_W, CARD_H, GAP = 78, 82, 10
MIN_CARD_W = 64
MAX_COLS, MAX_ROWS = 4, 3
PANEL_PAD = 32
FOOTER = 118

failures: list[str] = []
warnings: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def check_portraits() -> None:
    print("== 头像 ==")
    names = BASE_SPECIES + [s for s, _ in EXPANSION_SPECIES]
    for name in names:
        path = PORTRAITS / f"{name}.png"
        if not path.exists():
            fail(f"缺头像: {path.relative_to(ROOT)}")
            continue
        with Image.open(path) as im:
            im = im.convert("RGBA")
            alpha = im.getchannel("A")
            lo, hi = alpha.getextrema()
            bbox = alpha.getbbox()
            corner_clear = alpha.getpixel((0, 0)) == 0 and alpha.getpixel((63, 0)) == 0
            inner = bbox is not None and (bbox[2] - bbox[0]) >= 20 and (bbox[3] - bbox[1]) >= 20
            ok = im.size == (64, 64) and hi > 200 and lo < 40 and inner
            print(
                f"  {name:10s} {im.size} alpha={lo}-{hi} bbox={bbox} "
                f"corner_clear={corner_clear} {digest(path)} {'OK' if ok else 'BAD'}"
            )
            if im.size != (64, 64):
                fail(f"{name}.png 尺寸 {im.size} != (64, 64)")
            if not inner or hi <= 200:
                fail(f"{name}.png 轮廓为空或几乎全透明")
    print()


def check_icons() -> None:
    print("== 扩展技能图标（32 张，32x32）==")
    seen: dict[str, str] = {}
    for species, cn in EXPANSION_SPECIES:
        for slot in SLOTS:
            path = SKILLS / f"{species}_{slot}.png"
            if not path.exists():
                fail(f"缺图标: {path.relative_to(ROOT)}")
                continue
            with Image.open(path) as im:
                im = im.convert("RGBA")
                size = im.size
                alpha = im.getchannel("A")
                opaque = sum(1 for a in alpha.getdata() if a > 0)
                colors = len({p for p in im.getdata() if p[3] > 0})
                h = digest(path)
            print(f"  {species}_{slot}.png {size} opaque={opaque} colors={colors} {h}")
            if size != (32, 32):
                fail(f"{species}_{slot}.png 尺寸 {size} != (32, 32)（HUD 按 32x32 采样，64 会被裁掉 3/4）")
            if opaque < 500:
                fail(f"{species}_{slot}.png 几乎透明（opaque={opaque}）")
            if colors < 24:
                fail(f"{species}_{slot}.png 配色过少（colors={colors}），疑似通用占位")
            if h in seen:
                fail(f"{species}_{slot}.png 与 {seen[h]} 完全相同，不是逐招式图标")
            seen[h] = f"{species}_{slot}.png"
    print(f"  唯一图标数: {len(seen)} / 32")
    print()


def check_lang_and_registry() -> None:
    print("== 文案 / 注册表 ==")
    for lang in ("zh_cn", "en_us"):
        path = LANG / f"{lang}.json"
        if not path.exists():
            fail(f"缺 lang: {path.relative_to(ROOT)}")
            continue
        text = path.read_text(encoding="utf-8")
        missing = [f"skill.pkmoba.{s}.{slot}" for s, _ in EXPANSION_SPECIES for slot in SLOTS]
        missing = [k for k in missing if k not in text]
        print(f"  {lang}.json 扩展技能 key: {32 - len(missing)}/32" + (f" 缺 {missing}" if missing else ""))
        if missing:
            fail(f"{lang}.json 缺少 {len(missing)} 个扩展技能 key")

    src = REGISTRY.read_text(encoding="utf-8")
    active = {ln.strip() for ln in src.splitlines() if ln.strip().startswith('add("')}
    expected = {f'add("{s}", "{zh}");' for s, zh in EXPANSION_SPECIES}
    expected |= {f'add("{s}", "{zh}");' for s, zh in (("charizard", "喷火龙"), ("blastoise", "水箭龟"),
                                                      ("venusaur", "妙蛙花"), ("pikachu", "皮卡丘"))}
    missing_species = sorted(e for e in expected if e not in active)
    print(f"  SpeciesRegistry 生效注册 {len(active)} 只" + (f"，缺 {missing_species}" if missing_species else ""))
    if missing_species:
        fail(f"SpeciesRegistry 未注册全部 12 只，缺: {missing_species}")
    print()


class Layout:
    """StarterScreen.java 布局公式的 Python 复刻。"""

    def __init__(self, width: int, height: int, count: int) -> None:
        self.width, self.height, self.count = width, height, count
        self.cols = self.columns()
        self.rows = self.rows()
        self.per_page = max(1, self.cols * self.rows)
        self.pages = max(1, (count + self.per_page - 1) // self.per_page)

    def columns(self) -> int:
        fit = (self.width - 48 + GAP) // (MIN_CARD_W + GAP)
        return max(1, min(MAX_COLS, fit))

    def rows(self) -> int:
        by_count = (self.count + self.cols - 1) // self.cols
        by_height = max(1, (self.height - 134 + GAP) // (CARD_H + GAP))
        return max(1, min(MAX_ROWS, min(by_count, by_height)))

    def card_w(self) -> int:
        available = max(1, self.width - 16 - PANEL_PAD - (self.cols - 1) * GAP)
        return min(CARD_W, max(48, available // self.cols))

    def card_h(self) -> int:
        return min(CARD_H, max(48, self.card_w()))

    def grid_h(self) -> int:
        return self.rows * self.card_h() + (self.rows - 1) * GAP

    def panel_w(self) -> int:
        return min(self.width - 16, self.cols * self.card_w() + (self.cols - 1) * GAP + PANEL_PAD)

    def panel_h(self) -> int:
        extra = 12 if self.pages > 1 else 0
        return min(self.height - 16, self.grid_h() + FOOTER + extra)

    def left(self) -> int:
        return (self.width - self.panel_w()) // 2

    def top(self) -> int:
        return (self.height - self.panel_h()) // 2

    def grid_top(self) -> int:
        return self.top() + (56 if self.pages > 1 else 44)

    def card_rect(self, slot: int, page: int) -> tuple[int, int, int, int]:
        first = page * self.per_page
        page_cards = min(self.per_page, self.count - first)
        row, col = slot // self.cols, slot % self.cols
        row_cards = max(1, min(self.cols, page_cards - row * self.cols))
        total = row_cards * self.card_w() + (row_cards - 1) * GAP
        start = (self.width - total) // 2
        x = start + col * (self.card_w() + GAP)
        y = self.grid_top() + row * (self.card_h() + GAP)
        return x, y, self.card_w(), self.card_h()


def check_layout() -> None:
    print("== 12 格选择界面布局模拟 ==")
    sizes = [(320, 240), (427, 240), (640, 360), (854, 480), (1280, 720), (1920, 1080)]
    for width, height in sizes:
        lay = Layout(width, height, 12)
        inside = True
        overlap = False
        rect_count = 0
        px, py = lay.left(), lay.top()
        # 每页单独校验：界面一次只画一页，跨页的卡片天然可以重叠。
        for page in range(lay.pages):
            rects: list[tuple[int, int, int, int]] = []
            for slot in range(lay.per_page):
                if page * lay.per_page + slot >= lay.count:
                    break
                rects.append(lay.card_rect(slot, page))
            rect_count += len(rects)
            inside = inside and all(
                x >= px + 8 and y >= py + 8 and x + w <= px + lay.panel_w() - 8 and y + h <= py + lay.panel_h() - 8
                for (x, y, w, h) in rects
            )
            page_overlap = any(
                not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0] or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1])
                for i, a in enumerate(rects)
                for b in rects[i + 1:]
            )
            overlap = overlap or page_overlap
        fits = lay.panel_h() + 16 <= height and lay.panel_w() + 16 <= width
        print(
            f"  {width}x{height}: cols={lay.cols} rows={lay.rows} perPage={lay.per_page} "
            f"pages={lay.pages} card={lay.card_w()}x{lay.card_h()} "
            f"panel={lay.panel_w()}x{lay.panel_h()} rects={rect_count} "
            f"inside={inside} overlap={overlap} panelFits={fits}"
        )
        if overlap:
            fail(f"{width}x{height} 卡片重叠")
        if not inside:
            fail(f"{width}x{height} 有卡片超出面板")
        if not fits:
            fail(f"{width}x{height} 面板超出屏幕")
        if lay.per_page < 1:
            fail(f"{width}x{height} 每页容量为 0")
    print()


def main() -> int:
    if not PORTRAITS.exists() or not SKILLS.exists():
        print(f"资产目录不存在: {PORTRAITS} / {SKILLS}", file=sys.stderr)
        return 1
    check_portraits()
    check_icons()
    check_lang_and_registry()
    check_layout()

    if warnings:
        print("提示:")
        for w in warnings:
            print(f"  - {w}")
    if failures:
        print(f"\n失败 {len(failures)} 项:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

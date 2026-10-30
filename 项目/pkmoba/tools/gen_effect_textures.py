"""生成 pkmoba 的两张程序化贴图（无外部依赖，只用 numpy + Pillow）。

1. range_ring.png   —— 普攻/技能范围环。做成"整张圆盘"贴图，绘制时只画一个四边形，
   所以在游戏里是平滑的圆，不会出现折线锯齿（用户明确要求"平滑的而不是线条"）。
2. click_marker.png —— 点地移动的落点标记：宝可梦精灵球的地面投影 + 淡青气环，
   用来替代之前的十字箭头（用户嫌那个太丑）。

运行：python tools/gen_effect_textures.py
输出：src/main/resources/assets/pkmoba/textures/effect/
"""

import os

import numpy as np
from PIL import Image

OUT = os.path.join("src", "main", "resources", "assets", "pkmoba", "textures", "effect")


def make_range_ring(size: int = 512) -> None:
    yy, xx = np.mgrid[0:size, 0:size]
    center = (size - 1) / 2.0
    radius = np.sqrt((xx - center) ** 2 + (yy - center) ** 2) / (size / 2.0)  # 0..1

    rim = 0.93
    # 圈内极淡的填充：越靠近边缘略浓，到边缘收住，不和描边糊在一起
    fill = np.clip((radius - 0.10) / (rim - 0.10), 0.0, 1.0) ** 1.6
    fill *= np.clip((rim - radius) / 0.05, 0.0, 1.0)
    alpha_fill = fill * 46.0

    # 描边：以 rim 为中心的高斯带（软边，放大看也不是硬阶梯）
    band = np.exp(-((radius - rim) / 0.028) ** 2)
    alpha_rim = band * 235.0
    # 外侧一点点辉光
    glow = np.exp(-((radius - rim) / 0.085) ** 2) * 52.0

    alpha = np.clip(alpha_fill + alpha_rim + glow, 0.0, 255.0)
    blend = np.clip(band, 0.0, 1.0)
    red = 150.0 + (238.0 - 150.0) * blend
    green = 225.0 + (250.0 - 225.0) * blend
    blue = np.full_like(red, 255.0)
    rgba = np.dstack([red, green, blue, alpha]).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(os.path.join(OUT, "range_ring.png"))


def make_click_marker(size: int = 256) -> None:
    yy, xx = np.mgrid[0:size, 0:size]
    center = (size - 1) / 2.0
    dx = (xx - center) / (size * 0.42)
    dy = (yy - center) / (size * 0.42)
    dist = np.sqrt(dx ** 2 + dy ** 2)

    ball_radius = 0.86
    inside = dist <= ball_radius
    # 左上打光，做出球体的立体感
    light = np.clip(0.55 + 0.55 * (-0.65 * dx - 0.75 * dy), 0.0, 1.35)

    red = np.array([224.0, 62.0, 62.0])
    white = np.array([242.0, 242.0, 246.0])
    dark = np.array([26.0, 26.0, 30.0])
    color = np.where((dy < 0.0)[..., None], red[None, None, :], white[None, None, :])
    color = color * light[..., None]
    # 中间黑带
    color = np.where((np.abs(dy) < 0.085)[..., None], dark[None, None, :] * light[..., None], color)
    # 中心按钮
    color = np.where((dist < 0.235)[..., None], dark[None, None, :] * light[..., None], color)
    color = np.where((dist < 0.165)[..., None],
                     (white * 1.02)[None, None, :] * light[..., None], color)

    edge = np.clip((ball_radius - dist) / 0.035, 0.0, 1.0)
    ball = np.zeros((size, size, 4), dtype=np.float32)
    ball[..., :3] = np.clip(color, 0.0, 255.0)
    ball[..., 3] = edge * 255.0

    # 外圈淡青气环：像精灵球刚落地时那圈气
    halo = np.exp(-((dist - ball_radius) / 0.10) ** 2) * 0.22
    halo_img = np.zeros((size, size, 4), dtype=np.float32)
    halo_img[..., 0] = 120.0
    halo_img[..., 1] = 230.0
    halo_img[..., 2] = 210.0
    halo_img[..., 3] = np.clip(halo * 255.0, 0.0, 255.0)

    ball_layer = Image.fromarray(np.clip(ball, 0, 255).astype(np.uint8), "RGBA")
    halo_layer = Image.fromarray(np.clip(halo_img, 0, 255).astype(np.uint8), "RGBA")
    Image.alpha_composite(halo_layer, ball_layer).save(os.path.join(OUT, "click_marker.png"))


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    make_range_ring()
    make_click_marker()
    print("ok:", sorted(os.listdir(OUT)))


if __name__ == "__main__":
    main()

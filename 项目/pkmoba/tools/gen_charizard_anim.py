"""从 Cobblemon 自带的 charizard.special 切出喷火龙专用施法动画。

为什么不用 Cobblemon 的 special：
  special 是一条 3 秒的整段动作（起手 0~1.25s / 喷吐保持 1.25~2.0s / 收势 2.0~3s）。
  我们每次施法都整段播，于是出现用户反馈的两个问题：
    1) “喷了之后还播放一个的” —— 收势段被当成了第二个动作；
    2) “嘴没往前就喷” —— 起手段太长，出特效时模型还没前倾。
  这里把同一套骨骼关键帧按阶段切出来，重新配时，交给客户端按引导状态驱动。

输出：src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_charizard.animation.json
用法：python tools/gen_charizard_anim.py <cobblemon.jar>
"""

import json
import sys
import zipfile

JAR = sys.argv[1]
OUT = "src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_charizard.animation.json"
SRC = "assets/cobblemon/bedrock/pokemon/animations/0006_charizard/charizard.animation.json"

GROUP = "pkmoba_charizard"

# (新动画名, 源区间起, 源区间止, 新时长秒, 是否循环, 备注)
SLICES = [
    ("q_windup", 0.0, 1.25, 0.55, False, "喷射火焰前摇：吸气下蹲 -> 颈背前探、张口，到位即出火"),
    ("q_sustain", 1.25, 2.0, 1.0, True, "喷射火焰引导：保持前倾喷吐姿态（循环）"),
    ("r_charge", 0.0, 0.9167, 1.0, False, "大字炎爆蓄力：后仰吸气、双翼张开、张口最满"),
    ("r_blast", 1.0, 1.7917, 0.7, False, "大字炎爆出手：整段前扑 + 甩头，喷完立刻收"),
]

FPS = 15.0
# 通道取值全部等于这个容差内时只保留首尾两帧（姿态常量，不需要逐帧数据）。
FLAT_EPSILON = 0.01


def key_time(raw):
    return float(raw)


def key_value(raw):
    """返回 (pre, post)：普通数组两者相同。"""
    if isinstance(raw, list):
        return raw, raw
    pre = raw.get("pre") if isinstance(raw.get("pre"), list) else None
    post = raw.get("post") if isinstance(raw.get("post"), list) else None
    v = post if post is not None else (pre if pre is not None else [0, 0, 0])
    return (pre if pre is not None else v), (post if post is not None else v)


def smooth(t):
    # 三次平滑：把线性重采样拉成接近原 catmull-rom 的观感，避免节奏发硬。
    return t * t * (3.0 - 2.0 * t)


def sample_channel(keys, t):
    items = sorted(((key_time(k), key_value(v)) for k, v in keys.items()), key=lambda kv: kv[0])
    if not items:
        return None
    if t <= items[0][0]:
        return items[0][1][0]
    if t >= items[-1][0]:
        return items[-1][1][1]
    for i in range(len(items) - 1):
        t0, (_, v0post) = items[i]
        t1, (v1pre, _) = items[i + 1]
        if t0 <= t <= t1:
            span = t1 - t0
            if span <= 1e-9:
                return v1pre
            w = smooth((t - t0) / span)
            return [round(a + (b - a) * w, 4) for a, b in zip(v0post, v1pre)]
    return items[-1][1][1]


def main():
    z = zipfile.ZipFile(JAR)
    src = json.loads(z.read(SRC))["animations"]["animation.charizard.special"]

    out = {
        "format_version": "1.8.0",
        "animations": {},
    }

    for name, a, b, length, loop, note in SLICES:
        anim = {"animation_length": length}
        if loop:
            anim["loop"] = True
        anim["bones"] = {}
        # 采样点：固定 20fps + 两端闭合，保证首尾姿态精确
        times = [a + (b - a) * i / max(1.0, round((b - a) * FPS)) for i in range(int(round((b - a) * FPS)) + 1)]
        for bone, channels in src["bones"].items():
            if not isinstance(channels, dict):
                # 少数骨骼含 sound_effects/particle_effects 之类的列表通道，直接跳过
                continue
            bone_out = {}
            for channel, keys in channels.items():
                if channel not in ("rotation", "position", "scale"):
                    continue
                if not isinstance(keys, dict):
                    continue
                frames = {}
                sampled = []
                for t in times:
                    v = sample_channel(keys, t)
                    if v is None:
                        continue
                    sampled.append((round((t - a) / (b - a) * length, 4), v))
                if not sampled:
                    continue
                flat = all(
                    all(abs(c - sampled[0][1][i]) <= FLAT_EPSILON for i, c in enumerate(v))
                    for _, v in sampled
                )
                if flat:
                    # 常量通道：只写首尾两帧，避免动画文件被固定姿态撑爆
                    frames[f"{sampled[0][0]:.4f}"] = sampled[0][1]
                    if sampled[-1][0] != sampled[0][0]:
                        frames[f"{sampled[-1][0]:.4f}"] = sampled[-1][1]
                else:
                    for out_t, v in sampled:
                        frames[f"{out_t:.4f}"] = [round(c, 3) for c in v]
                if frames:
                    bone_out[channel] = frames
            if bone_out:
                anim["bones"][bone] = bone_out
        out["animations"][f"animation.{GROUP}.{name}"] = anim
        print(f"  {name}: {length}s loop={loop} bones={len(anim['bones'])}  # {note}")

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("写入", OUT)


if __name__ == "__main__":
    main()

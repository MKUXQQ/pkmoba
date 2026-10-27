# PKMOBA —— 基于 Cobblemon 的宝可梦实时对战 Mod

[![Release](https://img.shields.io/github/v/release/MKUXQQ/pkmoba?color=blue&label=release)](https://github.com/MKUXQQ/pkmoba/releases/latest)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![Cobblemon](https://img.shields.io/badge/Cobblemon-1.8.2-orange)](https://cobblemon.com/)
[![License](https://img.shields.io/badge/license-All%20Rights%20Reserved-lightgrey)](LICENSE)

> Minecraft **1.21.1** · Fabric · **Cobblemon 1.8.2** 附属模组
> 当前源码版本：**0.48.4**（喷火龙 / 水箭龟 / 妙蛙花 / 皮卡丘 · 房间对战与人机挑战）
>
> **下载**：[已发布版本](https://github.com/MKUXQQ/pkmoba/releases/latest)。源码版本可能领先于 Release；0.48.4 可按下文自行构建。

## 0.48.4 更新

- 妙蛙花与皮卡丘技能豆恢复由每 4 秒 1 豆提高至每 1 秒 1 豆（20 tick），恢复速度为原来的 4 倍。
- 技能消耗与 10 豆上限保持不变，保留 0.48.3 的大字爆炎弹道修复。

## 0.48.3 更新

- 新增妙蛙花、皮卡丘技能、动画、头像及特效，移除普通攻击。
- 更新队伍界面、技能 HUD、房间大厅与房间详情；双方头像和宝可梦队伍由服务端同步。
- 房间默认为空，加入后可手动召唤人机；对局结束后移除人机，开战按钮统一为“开始挑战”。
- 皮卡丘 Q/W/E/R 消耗 2/3/2/4 豆，妙蛙花消耗 3/3/2/4 豆，施法后每 4 秒恢复 1 豆；豆数不足时服务端拒绝施法。
- 皮卡丘 R 前摇缩短为 0.8 秒；调整水箭龟人机高速旋转的技能选择和追击逻辑。
- 更新四点场地与换宠区编辑、收回表现、生命值同步和上帝视角音效。
- 修复喷火龙大字爆炎从嘴部水平飞过皮卡丘的问题：玩家地面瞄准保留俯仰角，命中预览同步使用分段碰撞检测；弹道发射后保持直线，不追踪目标。

验证环境：Minecraft 1.21.1、Java 21、Cobblemon Fabric 1.8.3。Gradle 构建通过，并完成本地世界启动检查；技能命中与房间交互仍需完整游戏实测。

---

## 这是什么

PKMOBA 不是又一个宝可梦 UI 模组，而是把 Cobblemon 的宝可梦当成 **MOBA 英雄**来操作的战斗原型：

- 玩家本人不参战，以**上帝视角**（RTS 镜头）操控自己队伍里的一只宝可梦；
- 宝可梦有 **Q / W / E / R** 四个技能、资源点与冷却；
- 可以召唤、收回、切换队伍中的宝可梦，血量归零即濒死；
- 已有 **1v1 挑战对局**雏形：挑战 → 同意 → 传送到场地 → 对打 → 结算横幅。

**目前还没有**兵线、防御塔、基地、经济与装备 —— 当前阶段聚焦"单只宝可梦的操作手感与对战闭环"。

## 技术栈

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21（toolchain 21，release 21） |
| Fabric Loader / API | 0.17.2 / 0.116.6+1.21.1 |
| Cobblemon | 1.8.2+1.21.1（fabric） |
| Gradle / Loom | 8.14.3 wrapper / fabric-loom 1.10.5 |
| 映射 | Mojang official mappings |

模组 id：`pkmoba`，入口 `com.pkmoba.PkmobaMod`（服务端）与 `com.pkmoba.client.PkmobaClient`（客户端）。

## 目录结构

```
pkmoba/                     模组主工程（Gradle）
├─ src/main/java/com/pkmoba/
│  ├─ combat/               战斗核心：CharizardCombat、BlastoiseCombat、BasicAttack、Skill
│  ├─ pokemon/              宝可梦档案与队伍：PlayerProfile、ProfileStore、TeamService、SpeciesRegistry
│  ├─ duel/                 1v1 对局：DuelManager、ArenaStore、MenuActions
│  ├─ client/               客户端表现：上帝视角、HUD、技能特效、选人界面、结算横幅
│  ├─ network/              32 个 payload（C2S 11 / S2C 21）
│  ├─ command/              /mo 系列指令
│  ├─ mixin/                8 个客户端 Mixin（镜头、输入、渲染、HUD）
│  └─ world/ item/ lock/ …  场地、道具、玩家锁定、粒子
├─ src/main/resources/      fabric.mod.json、mixin 配置、语言文件、贴图
└─ tools/                   贴图生成、头像抓取等开发脚本
设计文档/                   每个版本的设计方案（0.30 / 0.31 / 0.38 等）
理解/                       源码、Cobblemon、构建环境的调研笔记
产物/                       历史发布 jar（不入库，见 Releases）
```

## 构建

需要 **Java 21**（JDK 21）。本工程在开发机上验证过的配方：

```powershell
$env:JAVA_HOME   = "E:\宝可梦项目 (1)\项目\tools\jdk21"
$env:GRADLE_USER_HOME = "E:\宝可梦项目 (1)\项目\tools\gradle-home"   # 可选：离线依赖缓存
cd 项目\pkmoba
.\gradlew.bat -Dorg.gradle.java.home="$env:JAVA_HOME" --offline --console=plain build -x test
```

> 注意：`gradle.properties` 里写死的 `org.gradle.java.home` 是开发机专用路径，换机器请删掉该行或改用上面的 `-D` 覆盖。
> 首次构建去掉 `--offline`，让 Gradle 拉取 Fabric / Cobblemon 依赖。

产物：`build/libs/pkmoba-<版本>.jar`（另出 `-dev.jar` 与 `-sources.jar`）。

## 安装

把 `pkmoba-<版本>.jar` 丢进客户端/服务端的 `mods/` 目录，并确保同时装有：

- Fabric Loader ≥ 0.17.2
- Fabric API ≥ 0.116.6+1.21.1
- Cobblemon 1.8.2+1.21.1

## 操作

| 按键 | 作用 |
| --- | --- |
| `Z` | 开关上帝视角（操控自家宝可梦） |
| 按住 `空格` | 锁定镜头 |
| `Q` `W` `E` `R` | 技能 1–4（需在上帝视角且宝可梦存活） |
| 鼠标左键 | 界面操作（已移除普通攻击） |
| 鼠标右键 | 移动指令 |
| 滚轮 | 镜头缩放 |
| `X` | 召唤宝可梦到准星地面（≤32 格）；对局中为换人上场 |
| `C` | 打开队伍界面，选择切换或收回 |
| `H` | 打开房间大厅，加入房间或召唤人机 |

指令：`/mo summon`、`/mo pokemon`（选择界面）、`/mo pokemon add <种>`、`/mo revive`、`/mo arena list|create|delete`。对局入口改用 H 房间大厅，已移除 `/mo duel`。

## 历史数值（0.37.0，非当前配置）

| 宝可梦 | 血量 | Q | W | E | R | 普攻 |
| --- | --- | --- | --- | --- | --- | --- |
| 喷火龙 | 280 | 喷射火焰 7×5=35 | 火焰旋涡 1.2×10=12 | 飞翔 16 | 大字爆炎 34→24（30/22） | 5 |
| 水箭龟 | 340 | 水之刃 14 | 冰山坠落 16 | 高速旋转 2×10=20 | 加农水炮 7×5=35 | 5 |

技能耗点 3/3/2/4（水箭龟同），资源 10 点、每 20 tick 回 1 点，技能 CD 40 tick。目标 TTK 约 35–45 秒。

## 历史限制记录（0.37，部分已在后续版本解决）

- 对战还没有阶段机 / 倒计时 / 限时判定 / 投降 / 断线宽限；
- `MoveOrderService`、`BasicAttack` 等只覆盖"对局内实体"，普通世界伤害仍会混入；
- 水箭龟部分技能缺少客户端命中提示（`Targeting` 恒 false）；
- 服务端场地状态是全局静态的，同一时间只支持一场对局；
- 没有音效资源（`sounds.json` 未建）。

## 路线图

- [x] 0.27 双宝可梦（喷火龙 + 水箭龟）
- [x] 0.30 玩家宝可梦、队伍、选人界面、头像 HUD
- [x] 0.31 濒死动画、结算横幅、召唤/换人
- [x] 0.36 世界自然生成/掉落拦截
- [x] 0.37 平衡：TTK 5–8s → 35–45s，按物种独立血量
- [ ] **0.38 水箭龟技能重做**（方案见 `设计文档/方案-0.38-水箭龟技能重做.md`）
      Q 加农水炮（原 R，射程下调）· W 高速旋转（走路速度、可中断、清钉子）·
      E 冲浪术（踏浪位移）· R 喷水（前摇 + 扇形 160 颗水滴密集砸落 + 减速）；删除水之刃
- [ ] 0.39 清钉子机制落地（HazardService 数据模型 + 钉子技能/道具 + 钉子伤害）
- [ ] 喷火龙平衡与技能手感复调；技能音效
- [ ] 对局阶段机：倒计时、限时、投降、断线宽限、观战
- [ ] 多宝可梦队伍、属性克制、结算统计
- [ ] GitHub Actions 自动构建 + Release

## 设计文档

- `设计文档/1v1对战系统方案-v1.md` — 1v1 对战系统完整设计
- `设计文档/方案-0.30-玩家宝可梦与对战UI.md` — 玩家宝可梦 / 队伍 / 头像 UI
- `设计文档/方案-0.31-指令精简与对战收尾.md` — HUD 一体化、濒死、结算
- `设计文档/方案-0.38-水箭龟技能重做.md` — **最新**：水箭龟四技能重做方案

## 许可

本项目代码以 **All Rights Reserved** 保留（见 `fabric.mod.json` 的 `license` 字段）。
如需开源，请同时修改 `fabric.mod.json` 与仓库根 `LICENSE`。

## 致谢

- [Cobblemon](https://cobblemon.com/) —— 宝可梦实体、模型、动画与战斗基础
- [Fabric](https://fabricmc.net/) —— 模组加载器与 API
- [PokeAPI sprites](https://github.com/PokeAPI/sprites) —— 队伍/HUD 头像素材

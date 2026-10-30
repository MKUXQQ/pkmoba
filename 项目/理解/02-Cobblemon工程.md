# 02 · Cobblemon 1.8.2 工程结构与 pkmoba 集成点

> 分析对象：`项目\宝可梦工程文件\cobblemon-1.8.2`（Cobblemon 1.8.2 完整源码，Architectury 三模块）
> 与 `项目\pkmoba\src`（Java 编写的 Fabric 附属模组 0.19.2）。
> 方法：以 pkmoba 的 `import com.cobblemon.*` 与字符串资源 id 为线索反查 Cobblemon 源码；只读，未运行 gradle。
> 标注约定：**【源码可见】**= 已在仓库文件中读到并给出行号；**【推断】**= 由代码/资源约定推得但未运行验证。

---

## 0. 结论速览

1. pkmoba 对 Cobblemon 的**代码依赖面极小**：6 个 Java 文件、约 10 个类（`Pokemon` / `PokemonEntity` / `PokemonSpecies` / `Species` / `PoseType` / `CobblemonMemories` / `PokemonBehaviourFlag` / `PosableEntity` / `BedrockParticleOptionsRepository` / `ParticleStorm`）。**【源码可见】**（见 §3）
2. pkmoba **不复制任何 Cobblemon 资源**：本地 `assets/pkmoba` 下只有自制火焰/字形贴图，喷火龙的 geo/动画/贴图/叫声全部按 `cobblemon:` 命名空间在运行时读取。**【源码可见】**（见 §2.5）
3. 喷火龙全套资源在 Cobblemon 的 `common` 模块内，通过 `species json → resolvers → poser/model/texture` 三级解析链与物种绑定，附属模组只要生成 `cobblemon:charizard` 物种实体就自动获得全套表现。**【源码可见】**（见 §2.2）
4. 招式特效是"数据驱动 + 客户端粒子仓库"两层：服务端只发 `effectId` 字符串，客户端 `BedrockParticleOptionsRepository.getEffect(id)` 查表后用 `ParticleStorm` 生成。pkmoba 正是照这个模式写的。**【源码可见】**（见 §4）
5. ⚠️ **已发现一处疑似资源 id 错误**：pkmoba 使用 `cobblemon:firespin_actorcinder`（`CharizardCombat.java:562`），但 1.8.2 的 firespin 粒子文件中没有该 id（只有 `firespin_actor` / `firespin_actorburst` / `firespin_actormiss` / `firespin_target` / `firespin_targetburst` / `firespin_targetcinder`）。**【源码可见 + 推断】** 详见 §4.4。

---

## 1. 仓库结构与构建版本事实

### 1.1 顶层结构

`项目\宝可梦工程文件\cobblemon-1.8.2\` 关键项：

| 路径 | 说明 |
| --- | --- |
| `common/` | 跨加载器主体：Kotlin 源码约 2988 个 `.kt/.java`，`src/main/resources` 下 32817 个文件（含 assets/data），是 99% 逻辑与全部资源所在 |
| `fabric/` | Fabric 平台壳：22 个代码文件，依赖 `common` 并做 `bundle` + 发布 |
| `neoforge/` | NeoForge 平台壳：20 个代码文件，同上（pkmoba 不用） |
| `gradle/build-logic/` | 约定插件：`cobblemon.base/platform/publish/root-conventions.gradle.kts`、`utilities/Versioning.kt` |
| `gradle/libs.versions.toml` | 版本目录（依赖版本真源） |
| `build.gradle.kts` | 根：`version = mod_version + mc_version`，snapshot 时追加分支/短 commit |
| `settings.gradle.kts` | 注册 `common`/`fabric`/`neoforge` 三工程，`includeBuild("gradle/build-logic")` |
| `deps/` | 本地 maven 仓库（`maven(url = "${rootProject.projectDir}/deps")`），放 molang 等私有件 |
| `common/src/main/resources/{assets,data,datapacks,resourcepacks}/` | 资源与数据包 |

**【源码可见】** 文件计数由 `Get-ChildItem -Recurse` 统计（仅统计，未逐文件遍历内容）。

### 1.2 三模块职责（Architectury 标准切法）

- `common`：`architectury { common("neoforge", "fabric") }`，产出 "namedElements/transformProductionXxx" 给平台模块。所有实体、渲染、AI、数据包、资源都在这里。**【源码可见】** `common/build.gradle.kts` 第 1 段。
- `fabric`：`architectury { platformSetupLoomIde(); fabric() }`，`implementation(project(":common", "namedElements"))` + `bundle(project(":common","transformProductionFabric"))`，即**把 common 的 class 与资源打包进 fabric jar**。**【源码可见】** `fabric/build.gradle.kts`。
- `neoforge`：同构（pkmoba 无关）。
- 发布：`common`/`fabric` 均应用 `cobblemon.publish-conventions`。**【源码可见】**

### 1.3 构建版本事实

| 事实 | 值 | 位置 |
| --- | --- | --- |
| MC 版本 | `1.21.1` | `gradle.properties`（`mc_version = 1.21.1`） |
| Java | `21`（`java_version = 21`；`options.release = 21` 在 pkmoba 侧） | `gradle.properties`、`gradle/libs.versions.toml` |
| mod 版本 | `1.8.2`，`snapshot=false` | `gradle.properties`（`mod_version`、`snapshot`） |
| group（Gradle 工程） | `com.cobblemon.mod` | `gradle.properties` |
| Gradle | `8.14.3`（wrapper） | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | `2.2.20` | `gradle/libs.versions.toml` |
| Architectury Loom | `1.11-SNAPSHOT` | `libs.versions.toml`（`loom`） |
| architectury-plugin | `3.4-SNAPSHOT` | `libs.versions.toml` |
| Fabric Loader | `0.17.2` | `libs.versions.toml`（`fabric-loader`） |
| Fabric API | `0.116.6+1.21.1` | `libs.versions.toml`（`fabric-api`） |
| Fabric Kotlin | `1.13.6+kotlin.2.2.20` | `libs.versions.toml` |
| NeoForge | `21.1.182` | `libs.versions.toml` |
| Mappings | `parchment 2024.07.28@zip` + 平台默认 | `gradle.properties`（`parchment_version`）、`base-conventions` |
| JVM 内存 | `-Xmx8G` | `gradle.properties` |

> 注意：pkmoba 用 `loom.officialMojangMappings()`（`pkmoba/build.gradle:27`），与 Cobblemon 的 parchment 映射**运行时无冲突**（模组间只认 intermediary），但 **开发期编译 Cobblemon API 时方法名需按 Mojang 名**；pkmoba 注释也说明了这点。**【源码可见 + 推断】**

### 1.4 maven 坐标 `com.cobblemon:fabric:1.8.2+1.21.1` 是怎么来的

三步拼出来，全部在 build-logic 里：

1. `groupId = "com.cobblemon"` —— 覆盖 Gradle 工程的 `com.cobblemon.mod`。**【源码可见】** `gradle/build-logic/src/main/kotlin/cobblemon.publish-conventions.gradle.kts:51`
2. `artifactId = project.findProperty("maven.artifactId") ?: project.name` —— fabric 模块无覆盖属性，故为 `fabric`。**【源码可见】** 同文件 `:52`
3. `version = project.writeVersion(VersionType.PUBLISHING)`，而 `writeVersion` 在非 snapshot 下返回 `"${mod_version}+${mc_version}"` = `1.8.2+1.21.1`。**【源码可见】** `gradle/build-logic/src/main/kotlin/utilities/Versioning.kt:13-18`
4. 发布目标仓库 `https://artefacts.cobblemon.com/releases`（snapshot 为 `/snapshots`）。**【源码可见】** `publish-conventions.gradle.kts:31-34`

pkmoba 侧完全对齐：

- 仓库声明 `maven { name='Cobblemon'; url='https://artefacts.cobblemon.com/releases' }` —— `pkmoba/build.gradle:19`
- 依赖 `modImplementation "com.cobblemon:fabric:1.8.2+1.21.1"` —— `pkmoba/build.gradle:33`
- `fabric.mod.json` 声明 `"cobblemon": ">=1.8.2"` —— `pkmoba/src/main/resources/fabric.mod.json:22`

**【源码可见】**；`>=1.8.2` 的宽松写法与"固定 1.8.2 编译"之间存在版本风险，见 §6。

---

## 2. 喷火龙全套资源与"无需复制"的证据链

命名根：`common/src/main/resources/`（下称 `R/`）。全部为 **`cobblemon`** 命名空间。**【源码可见】**

### 2.1 文件清单（实测大小/行数）

| 类别 | 路径 | 规模 |
| --- | --- | --- |
| geo 模型 | `R/assets/cobblemon/bedrock/pokemon/models/0006_charizard/charizard.geo.json` | 29922 B / 1085 行 |
| 动画 | `R/assets/cobblemon/bedrock/pokemon/animations/0006_charizard/charizard.animation.json` | 877789 B / 38060 行 |
| poser | `R/assets/cobblemon/bedrock/pokemon/posers/0006_charizard/charizard.json` | 11130 B / 315 行 |
| resolver（变体表） | `R/assets/cobblemon/bedrock/pokemon/resolvers/0006_charizard/0_charizard_base.json` | 60 行 |
| 贴图 | `R/assets/cobblemon/textures/pokemon/0006_charizard/charizard.png`（52 行=9406 B）+ `charizard_shiny.png` + `charizard_alpha.png` + 火焰层 `charizard_flame1..4.png` + `charizard_shiny_flame1..4.png` | — |
| 叫声/环境音 | `R/assets/cobblemon/sounds/pokemon/charizard/charizard_cry.ogg`、`charizard_ambient.ogg` | — |
| 声音事件 | `R/assets/cobblemon/sounds.json:5257`（`pokemon.charizard.cry` → `cobblemon:pokemon/charizard/charizard_cry`）、`:2182`（`pokemon.charizard.ambient`） | — |
| 物种数据 | `R/data/cobblemon/species/generation1/charizard.json` | 14287 B / 618 行 |
| 图鉴条目 | `R/data/cobblemon/dex_entries/pokemon/kanto/charizard.json` | — |
| 刷怪池 | `R/data/cobblemon/spawn_pool_world/0006_charizard.json`、`.../herds/0006_charizard_alpha.json` | — |
| 招式特效 | `R/assets/cobblemon/bedrock/particles/moves/flamethrower/*.particle.json` 等 | 见 §4 |

### 2.2 引用链：species → resolver → poser/model/texture

```text
data/cobblemon/species/generation1/charizard.json
  └─ name=Charizard, nationalPokedexNumber=6, primaryType=fire/flying   ← 物种数值与类型（:2-7）
       └─ 运行时 species.resourceIdentifier = cobblemon:charizard
assets/cobblemon/bedrock/pokemon/resolvers/0006_charizard/0_charizard_base.json
  └─ species: "cobblemon:charizard"                                       ← :2
     variations[0].poser   = "cobblemon:charizard"                        ← :7
     variations[0].model   = "cobblemon:charizard.geo"                    ← :8
     variations[0].texture = "cobblemon:textures/pokemon/0006_charizard/charizard.png"  ← :9
     variations[0].layers  = 火焰层 4 帧，fps 10，emissive+translucent     ← :10-26
     variations[1] shiny → charizard_shiny.png；variations[2] alpha_eyes → charizard_alpha.png
assets/cobblemon/bedrock/pokemon/posers/0006_charizard/charizard.json
  └─ animations 键：cry / recoil / physical / special / air_physical / air_special / status  ← :18-26
     poses 键：portrait / battle-standing / battle-flying / standing / walking / hover / fly / sleep … ← :27-315
assets/cobblemon/bedrock/pokemon/animations/0006_charizard/charizard.animation.json
  └─ 具体骨骼动画："animation.charizard.special"（:10984，长度 3s）、
     "animation.charizard.ground_walk"（:243）、"animation.charizard.air_fly"（:19400）、
     "animation.charizard.air_special"（:28021）、air_fly_quirk（:20685）、ride_air_fly（:37308）等
```

关键点（**【源码可见】**）：

- resolver 用**字符串物种名**绑定，不依赖物种 id 硬编码：`"species": "cobblemon:charizard"`。
- poser 的 `animations` 是**逻辑动画名 → MoLang 表达式**的映射；表达式里再写 bedrock 动画名，例如 `"special": "q.bedrock_primary('charizard', 'special', q.curve('symmetrical_wide'))"`（`posers/0006_charizard/charizard.json:22`），而 `walking` pose 用 `q.bedrock('charizard','ground_walk')`（`:169`），`fly` pose 用 `q.bedrock('charizard','air_fly')`（`:210`），`sleep` pose 用 `q.bedrock('charizard','sleep')`（`:304`）。
- 因此**动画名是 poser 的逻辑名**，不是 animation json 的键名；两者通过 MoLang 表达式间接关联。

### 2.3 客户端如何按物种找到这套资源

`common/src/main/kotlin/com/cobblemon/mod/common/client/render/models/blockbench/repository/VaryingModelRepository.kt`：

- 资源目录注册：`"bedrock/pokemon/posers"`（`:87`）、`"bedrock/pokemon/resolvers"`（`:97`）。
- 查询入口：`getPoser(name, state)`（`:603`）、`getTexture(...)`（`:615`）、`getLayers(...)`（`:635`），`name` 即 `species.resourceIdentifier`；查不到则回退 `variations[fallback]`（`:612/:622/:642`）。

`common/src/main/kotlin/com/cobblemon/mod/common/client/render/pokemon/PokemonRenderer.kt`：

- 只有一个 `PokemonRenderer`（`:69`），**全局按 species 分发**：`getTextureLocation` 用 `entity.pokemon.species.resourceIdentifier`（`:96`），`render` 里 `model.posableModel = VaryingModelRepository.getPoser(entity.pokemon.species.resourceIdentifier, clientDelegate)`（`:109`）。
- 结论：**不存在"喷火龙专用渲染器"**，也就没有"给附属模组单独注册渲染器"的入口；附属模组生成的 `PokemonEntity` 自动复用同一渲染路径。**【源码可见 + 推断】**

### 2.4 pkmoba 实际读取的喷火龙资源

| 资源 | pkmoba 用法 | 位置 |
| --- | --- | --- |
| 物种 | `ResourceLocation.fromNamespaceAndPath("cobblemon","charizard")` + `PokemonSpecies.getByIdentifier` | `CharizardManager.java:37-38,61` |
| 模型/贴图/动画 | 不直接引用，由 `sendOut` 生成的 `PokemonEntity` 自动带出 | `CharizardManager.java:73` |
| mouth locator | 客户端从 `PokemonClientDelegate.getLocatorStates().get("mouth")` 取喷火起点 | `PkmobaClient.java:167-171` |
| 叫声 | 未主动播放（`sendOut` 非 `sendOutWithAnimation` 不会自动 cry）**【推断】** | — |

### 2.5 "直接引用、不复制"的证据

1. **pkmoba 资源目录没有任何 Cobblemon 资源副本**：`pkmoba/src/main/resources/` 全量为 `fabric.mod.json`、`pkmoba.mixins.json`、`assets/pkmoba/textures/particle/*.png`、`assets/pkmoba/textures/effect/*.png`、`assets/pkmoba/particles/flame_puff*.json`。文件清单由 glob 全量枚举，**无 charizard/geo/ogg/cobblemon 前缀文件**。**【源码可见】**
2. **代码里只有命名空间字符串**：`cobblemon:charizard`、`cobblemon:move.flamethrower.actor`、`cobblemon:flamethrower_targetburst` 等，全部在运行时解析。**【源码可见】**（`CharizardManager.java:38`、`CharizardCombat.java:450/552/562/614/635`）
3. **机制上无需复制**：`Pokemon` 只持有 `Species`，渲染/动画/音效全部由 species 的 resolver/poser 在客户端解析（§2.2、§2.3）。只要 Cobblemon 1.8.2 在场，`cobblemon:charizard` 资源必然可用。**【源码可见 + 推断】**
4. 代价：pkmoba 的表现**完全受 Cobblemon 版本内容支配**——升级 Cobblemon 后动画名/poser 结构/粒子 id 可能变（§6）。

---

## 3. pkmoba 实际用到的 Cobblemon API（逐条定位）

Cobblemon 源码根：`common/src/main/kotlin/com/cobblemon/mod/common/`（下称 `K/`）。
pkmoba 源码根：`src/main/java/com/pkmoba/`（下称 `P/`）。

| # | API | pkmoba 位置 | Cobblemon 位置 | 说明 / 风险 |
| --- | --- | --- | --- | --- |
| 1 | `Pokemon.sendOut(level, pos, illusion, mutation)` | `P/charizard/CharizardManager.java:73-81` | `K/pokemon/Pokemon.kt:799-813` | 返回 `PokemonEntity?`；内部 `CobblemonEvents.POKEMON_SENT_PRE.postThen(...)`（`:800`），事件被取消则返回 `null`。pkmoba 的 `return null` 判断（`CharizardManager.java:83`）与源码完全对应。mutation 回调在 `addFreshEntity` 之前执行（`:806-808`），所以 pkmoba 在里面 `setPersistenceRequired` / `setInvulnerable` 是安全的。 |
| 2 | `Pokemon`（构造 / `setSpecies` / `setLevel`） | `CharizardManager.java:67-69` | `K/pokemon/Pokemon.kt`（类头；`sendOut` 在其后） | 【推断】无参构造 + setter 在 Kotlin 侧为 `var`；pkmoba 已编译通过，说明可用。`setLevel(50)` 未做能力值/招式初始化，属于"只有外观与移动"的最小实体。 |
| 3 | `PokemonEntity` | `CharizardManager.java:4,73,112`；`CharizardCombat.java:3-5`；`WalkAnimator.java:3`；`CharizardSleepSuppressor.java:5` | `K/entity/pokemon/PokemonEntity.kt` | 核心实体类，约 2745 行。`getNavigation()` override（`:1081`，返回 `OmniPathNavigation`）；`setBehaviourFlag`（`:1380`）；`getCurrentPoseType()`（`:2031`）。 |
| 4 | `PokemonSpecies.getByIdentifier(ResourceLocation)` | `CharizardManager.java:61` | `K/api/pokemon/PokemonSpecies.kt:195`（`object PokemonSpecies : JsonDataRegistry<Species>` 在 `:82`；索引表 `speciesByIdentifier` 在 `:144`） | 数据包加载完成后才有值；pkmoba 的"找不到物种就报错返回"是正确防御。 |
| 5 | `Species` | `CharizardManager.java:6,61` | `K/pokemon/Species.kt` | `species.resourceIdentifier` 是渲染分发键。 |
| 6 | `PoseType` / `PokemonEntity.POSE_TYPE` | `CharizardSleepSuppressor.java:48-49` | `K/entity/PoseType.kt:21-52`（`STAND/WALK/SLEEP/HOVER/FLY/FLOAT/SWIM/GLIDE/SHOULDER_*/PROFILE/PORTRAIT/OPEN/NONE`；分组 `FLYING_POSES=FLY,HOVER` 等 `:44-52`）；`PokemonEntity.kt:205`（`POSE_TYPE` SynchedEntityData） | pkmoba 用 `entityData.set(POSE_TYPE, STAND)` 强改姿态；对 `SynchedEntityData` 直接写是"绕过 delegate 计算"的兜底，服务端 tick 顺序决定能否被下一帧覆盖（§5.3）。 |
| 7 | `CobblemonMemories.POKEMON_DROWSY` | `CharizardSleepSuppressor.java:45` | `K/CobblemonMemories.kt:44`（`POKEMON_DROWSY`）；`POKEMON_SLEEPING` 在 `:46` | 注册名分别为 `pokemon_drowsy` / `pokemon_sleeping`（`:44/:46`）。**这是 1.8.2 的公开 `@JvmField` 常量**，Java 可直接访问。 |
| 8 | `Brain.eraseMemory` | 同上 | 原版 `Brain`（MC 1.21.1） | pkmoba 用 try/catch 包住，注释理由"跨版本可能变"——合理。 |
| 9 | `DrowsySensor` | 间接（清记忆） | `K/entity/pokemon/ai/sensors/DrowsySensor.kt:26-57` | 每 20 tick 感测：夜间时段 `world.dayTime%24000 in rest.times` 且未受伤、非愤怒，按 `rest.drowsyChance` 概率置 `POKEMON_DROWSY`（`:38/:48`）；非战斗下还会按 `rouseChance` 清除（`:52`）。 |
| 10 | `GoToSleepTask` | 间接（清记忆） | `K/entity/pokemon/ai/tasks/GoToSleepTask.kt:22-52` | 条件：无 `WALK_TARGET` + 有 `POKEMON_DROWSY` + `behaviour.resting.canSleep` + `canSleepAt(below)`；成功时给 `Statuses.SLEEP`、停 navigation、切 `POKEMON_SLEEPING_ACTIVITY`、`setMemory(POKEMON_SLEEPING,true)`（`:33-44`）。 |
| 11 | `BedrockParticleOptionsRepository` | `PkmobaClient.java:3,278` | `K/client/particle/BedrockParticleEffectRepository.kt:27` 的 `object BedrockParticleOptionsRepository`（文件名≠类名！）；`loadEffects` 扫描 `bedrock/particles/**.particle.json`（`:31-48`）；`getEffect(id)`（`:50`） | pkmoba 用 `BedrockParticleOptionsRepository.INSTANCE.getEffect(id)`（Java 访问 Kotlin object）——**代码注释里没提文件名不一致的坑**，排查时容易搜不到。 |
| 12 | `ParticleStorm` / `ParticleStorm.Companion.createAtPosition` | `PkmobaClient.java:4,283` | `K/client/particle/ParticleStorm.kt:47`（类，继承 `NoRenderParticle`）；`companion object` 在 `:150`，`createAtPosition(world,effect,pos)` 在 `:153`，`createAtEntity(...)` 在 `:164` | 纯客户端类；pkmoba 在客户端接收包后生成，符合设计。粒子随镜头/实体绑定与 `attachedMatrix`、`getParticleColor` 等参数有关（`:47-60`），pkmoba 只用了最简单的"世界坐标 + 默认参数"。 |
| 13 | `PosableEntity.playAnimation(animation, expressions)` | `CharizardCombat.java:3,785-790` | `K/entity/PosableEntity.kt:22`（接口）、`:32`（`fun playAnimation(animation: String, expressions: List<String> = emptyList())`） | pkmoba 强转 `(PosableEntity) entity` 后调用。**源码注释说明这是"播放动画"，实际是发 `PlayPosableAnimationPacket`**（见 §4.3）。 |
| 14 | `PokemonBehaviourFlag.FLYING` | `CharizardCombat.java:385` | `K/entity/pokemon/PokemonBehaviourFlag.kt:21`（`enum class`）、`:24`（`FLYING`） | 设置后 `PokemonServerDelegate.updatePoseType` 会给出 `FLY`/`HOVER`（`:396-397`），从而驱动 `fly`/`hover` pose 与 `air_fly` 动画。pkmoba 每 tick 重设（`CharizardCombat.java:397`）以压住姿态重算。 |
| 15 | `CobblemonEntities.POKEMON` | **未直接 import**（pkmoba 只用 `PokemonEntity` 类型/`instanceof`） | `K/CobblemonEntities.kt:36-43`：`POKEMON_KEY = cobblemonResource("pokemon")`，`POKEMON: EntityType<PokemonEntity>` | 说明 pkmoba 不需要注册实体类型；`sendOut` 内部 `new PokemonEntity(level, this)`（`Pokemon.kt:802`）后 `addFreshEntity`（`:808`）。若将来需要 `EntityType` 判断，用 `CobblemonEntities.POKEMON`。 |
| 16 | `PokemonClientDelegate` / locator 状态 | `PkmobaClient.java:167-171` | `K/client/entity/PokemonClientDelegate.kt`（`getLocatorStates()` 来自 `PosableState`/delegate 体系） | 取 `"mouth"` locator 得喷火原点。locator 名由 poser/模型决定，**属于易碎契约**：模型换绑点名字会变。 |
| 17 | `CameraMixin` 参考实现 | `P/CameraMixin.java` | `common/src/main/java/com/cobblemon/mod/common/mixin/client/CameraMixin.java:29-88` | Cobblemon 在 `Camera.setup` 上对 `setPosition(DDD)` 用 `@WrapOperation` 接管骑乘相机（`:63-88`），并 `implements CameraDuck` 暴露 forwards/up/left（`:43-61`）。pkmoba 改在 `setup` 的 `@Inject(TAIL)` 覆盖 `setRotation` + `setPosition`（`P/CameraMixin.java:24-45`），注释明确"避免和 Cobblemon 同一方法的 @WrapOperation 打架"。**【源码可见】两者可共存，但执行顺序依赖注入点。 |
| 18 | `MouseHandlerMixin` 参考实现 | `P/MouseHandlerMixin.java` | `.../mixin/client/MouseHandlerMixin.java:43-95` | Cobblemon 在 `onScroll` 上 `@Inject(at=FIELD accumulatedScrollY, cancellable=true)` 拦截队伍轮盘（`:70-95`）；pkmoba 在 `onScroll`/`onPress` 打 `@Inject(HEAD, cancellable)`（`P/MouseHandlerMixin.java:32-71`）。**同一方法多 Mixin 时 HEAD 先于 Cobblemon 的 FIELD 注入点**，pkmoba 在上帝视角下 cancel 后 Cobblemon 收不到滚动——属于可接受但需知晓的行为耦合。**【源码可见 + 推断】** |

---

## 4. 招式特效资源清单（flamethrower / firespin / fireblast）

### 4.1 服务端 JSON：`data/cobblemon/action_effects/`

| 文件 | 关键内容（源码可见） |
| --- | --- |
| `moves/flamethrower.json` | `animation: ["flamethrower","fire","spray","special"]`（`:9`，带回退链）→ `entity_particles: cobblemon:flamethrower_actor`，locators `["special","target"]`（`:13-17`）→ `q.sound('move.flamethrower.actor')`（`:21`）→ 命中帧 `cobblemon:flamethrower_target`（`:30`）→ 受击 `cobblemon:flamethrower_targetburst`（`:36`、`:51`）→ `q.sound('move.flamethrower.target')`（`:57`） |
| `moves/firespin.json` | `animation: ["firespin","special"]`（`:9`）→ `firespin_actorburst`（`:14`）→ `q.sound('move.firespin.actor')`（`:21`）→ `firespin_actor`（`:30`）→ 目标 `firespin_targetburst`（`:41`）、`firespin_target`（`:48`）→ `q.sound('move.firespin.target')`（`:54`） |
| `moves/fireblast.json` | `animation: ["fireblast","fire","special"]`（`:9`）→ `fireblast_actorshoot`（`:14`）、`fireblast_actor1`（`:20`）、`fireblast_actor2`（`:26`）→ 目标 `fireblast_target`（`:37`）→ `daimonji1..4`（`:47-66`）。**注意：fireblast.json 里没有 `q.sound(...)`**——其音效走别处（战斗层），pkmoba 直接调 `cobblemon:move.fireblast.actor` 是可行的，因为该声音事件在 `sounds.json:1195` 存在。 |
| `damages/damage_firespin.json` | 持续伤害阶段：`firespin_target`（`:10`）+ `q.sound('move.firespin.target')`（`:16`） |

### 4.2 粒子 id ↔ 文件 ↔ 声音 id 对照

**粒子**（id = 去掉 `cobblemon:bedrock/particles/` 前缀与 `.particle.json` 后缀；目录 `R/assets/cobblemon/bedrock/particles/`）**【源码可见】**：

| 招式 | 粒子文件目录 | 可用 id |
| --- | --- | --- |
| flamethrower | `moves/flamethrower/` | `flamethrower_actor`、`flamethrower_target`、`flamethrower_targetburst`、`flamethrower_targetcinder`、`flamethrower_target_linger` |
| firespin | `moves/firespin/` | `firespin_actor`、`firespin_actorburst`、`firespin_actormiss`、`firespin_target`、`firespin_targetburst`、`firespin_targetcinder` —— **没有 `firespin_actorcinder`** |
| fireblast | `moves/fireblast/` | `fireblast_actor1`、`fireblast_actor2`、`fireblast_actorshoot`、`fireblast_target`、`fireblast_targetcinder`；另有 `daimonji1..4`（json 引用，资源可检索到，未逐条记录路径） |

**声音**（事件名 → ogg，`R/assets/cobblemon/sounds.json`）**【源码可见】**：

| 事件 | sounds.json 行 | ogg 路径 |
| --- | --- | --- |
| `move.flamethrower.actor` | `:1324-1326` | `cobblemon:move/flamethrower/flamethrower_actor` |
| `move.flamethrower.target` | `:1327-1329` | `.../flamethrower_target` |
| `move.firespin.actor` | `:1207-1209` | `cobblemon:move/firespin/firespin_actor` |
| `move.firespin.target` | `:1210-1212` | `.../firespin_target` |
| `move.fireblast.actor` | `:1195-1197` | `cobblemon:move/fireblast/fireblast_actor` |
| `move.fireblast.target` | `:1198-1200` | `.../fireblast_target` |
| `impact.flying`（含 `..._flying_1..`） | `:1764-1770` | `cobblemon:move/impact_generic/impact_generic_flying_1` |
| `pokemon.charizard.cry` | `:5257-5259` | `cobblemon:pokemon/charizard/charizard_cry` |

对应 ogg 文件位于 `R/assets/cobblemon/sounds/`（如 `sounds/move/flamethrower/flamethrower_actor.ogg` 53742 B）。

### 4.3 `animation` 回退链是怎么生效的

- `moves/flamethrower.json:9` 的 `"animation": ["flamethrower","fire","spray","special"]` 是**候选集合**，不是数组索引。
- 服务端 `AnimationActionEffectKeyframe`（`K/api/moves/animations/keyframes/AnimationActionEffectKeyframe.kt:22-53`）把 `animation: Set<String>` 原样（按 MoLang 解析字符串后）打包进 `PlayPosableAnimationPacket(entity.id, animation, expressions)`（`:37-49`）。
- `PlayPosableAnimationPacket` 类注释直接写明语义：**"Packet sent to arbitrarily play the first animation of the given set that exists on the given entity"**（`K/net/messages/client/animation/PlayPosableAnimationPacket.kt:18-19`）。
- 客户端解析时按 poser 的 `animations` 逻辑名匹配：喷火龙 poser 只有 `cry/recoil/physical/special/air_physical/air_special/status`（`posers/0006_charizard/charizard.json:18-26`），**没有 `flamethrower`/`fire`/`spray`**，因此最终必然落到 `special`。**【源码可见 + 推断：客户端匹配代码未逐行读过，但包注释与 poser 键集合足以确定结果】**
- 这也解释了为什么 pkmoba 直接调 `playAnim(entity,"special")`（`CharizardCombat.java:451/553/564/585`）就能播"喷火动作"：它抄的就是回退链的终点。

### 4.4 pkmoba 实际使用的特效 id 与一处错误

| 技能 | pkmoba 代码 | 使用的 cobblemon 资源 | 状态 |
| --- | --- | --- | --- |
| Q 喷火 | `CharizardCombat.java:552-553`、命中 `:614` | 声音 `move.flamethrower.actor`；动画 `special`；粒子 `flamethrower_targetburst` | ✅ 全部存在 |
| W 龙卷 | `:562-564` | 粒子 `cobblemon:firespin_actorcinder`；声音 `move.flamethrower.actor`；动画 `special` | ❌ **粒子 id 不存在**：firespin 目录无 `firespin_actorcinder`（有 `firespin_actor`、`firespin_actormiss`、`firespin_targetcinder`）。客户端 `getEffect` 返回 null，`PkmobaClient.java:280` 打 warn 后什么都不生成。**【源码可见 + 推断：未运行验证】** 建议改为 `cobblemon:firespin_actor` 或 `cobblemon:firespin_targetcinder`（前者更接近"龙卷本体"语义）。 |
| E 冲刺 | `:578` | 声音 `move.impact_generic.flying_1` | ⚠️ 事件名应为 `impact.flying`（`sounds.json:1764`），**不是** `move.impact_generic.flying_1`。该字符串作为 `SoundEvent.createVariableRangeEvent` 的 id 会解析成不存在的声音事件 → 静音。**【源码可见 + 推断】** |
| R 大字 | `:584-585`、命中 `:635` | 声音 `move.fireblast.actor`、`move.fireblast.target`；动画 `special` | ✅ 存在 |
| Q 自定义火流 | `PkmobaClient.java:154-203`（JetPayload）、`CharizardCombat.java:286-308` | 不用 Cobblemon 粒子，改用自制 `pkmoba:flame_puff*`；原因见 `CharizardCombat.java:289-290`（`flamethrower_actor` 喷射方向写死、不认实体朝向） | 设计取舍，非 bug |

---

## 5. 关键机制

### 5.1 `PokemonEntity.tick` / `travel` 与 walk/fly 动画判定

- `K/entity/pokemon/PokemonEntity.kt:639-654`：`tick()` 先 `super.tick()`（`:651`），然后立刻重算
  - `isPokemonFlying = flyDist - flyDistO > 0.005F`（`:653`）
  - `isPokemonWalking = walkDist - walkDistO > 0.005F`（`:654`）
  - 之后再 `delegate.tick(this)`（`:704`）。
- `travel(movementInput)` 在 `:1913-1967`：非召回状态（`beamMode != 3`）下，无骑乘时直接 `super.travel`（`:1919`），有骑乘时走 riding behaviour 逆向速度/惯性（`:1921-1962`）。
- 渲染侧关键：`isPokemonWalking` 是**实体字段**（声明在 `:421`），每 tick 被重算；而 pkmoba 的服务端驱动 + 客户端插值会让客户端 `walkDist` 增量为 0，于是动画一直播 idle（"滑行"）。
- pkmoba 的对策 `P/client/WalkAnimator.java:12-19, 50-66`：在 `END_CLIENT_TICK` 里比较 `position` 位移（阈值 `4.0E-4`），位移 > 阈值就 `setPokemonWalking(true)`；理由注释里直接引用了 `PokemonEntity.kt:654`，与源码一致。**【源码可见】该方案依赖"渲染发生在 END_CLIENT_TICK 之后"这一原版时序，属推断但合理。**
- 飞行姿态：`setBehaviourFlag(FLYING, true)` → `updatePoseType` 给 `FLY`/`HOVER`（`PokemonServerDelegate.kt:389,396-397`）→ poser 的 `fly`/`hover` pose → `air_fly` 动画（`charizard.json:201-222`）。pkmoba 在冲刺期间每 tick 重设（`CharizardCombat.java:383-385, 397`）。

### 5.2 `isNoAi` 与 navigation：pkmoba 为什么不用 NoAI

- pkmoba 的决策（`P/charizard/CharizardManager.java:30-31` 注释）：**不设 `isNoAi=true`**，因为会让 navigation 停止 tick，连绕障都没有；改为"保留 AI + 每 tick 覆盖 navigation + 清空攻击目标"。
- 实际实现：`P/move/MoveOrderService.java:109-143`
  - `entity.setTarget(null)`（`:116`）——保留 AI 但不许它自己打人；
  - 无指令时 `if (!getNavigation().isDone()) getNavigation().stop()`（`:119-121`）；
  - 有指令时每 `REPATH_INTERVAL` 才 `getNavigation().moveTo(x,y,z,NAV_SPEED)`（`:141`），到点后 `stop()` + 归零水平速度（`:129-133`）。
- Cobblemon 侧 navigation 是 `OmniPathNavigation`（`PokemonEntity.kt:1081` `override fun getNavigation() = navigation as OmniPathNavigation`），支持全向移动；pkmoba 直接复用。
- **【推断】** "`isNoAi=true` → navigation 不 tick" 属 Minecraft 原版 `Mob.aiStep` 行为（`noAi` 时跳过 goal/目标选择，navigation 由 Goal 推进），Cobblemon 仓库内没有额外改写；pkmoba 注释是经验结论，未在仓库内找到直接源码行。
- 另一条约束：**绝不设 `ownerUUID`**（`CharizardManager.java:27-29`）。源码依据：`PokemonEntity.stopSeenByPlayer`（`PokemonEntity.kt:2206-2210`）在 `ownerUUID == player.uuid && tethering == null` 时直接 `remove(DISCARDED)`——认主后玩家离开视距会被当"召回"删除。pkmoba 的类注释与源码一致。**【源码可见】**

### 5.3 睡眠三步链路

```text
① DrowsySensor（每 20 tick）
   dayTime%24000 ∈ behaviour.resting.times && 未受伤 && 非 ANGRY_AT
   → 按 resting.drowsyChance 置 CobblemonMemories.POKEMON_DROWSY = true
   （DrowsySensor.kt:26-57，判定 :38，置位 :48-49）

② GoToSleepTask（Behavior，OneShot）
   无 WALK_TARGET && 有 POKEMON_DROWSY && resting.canSleep && canSleepAt(below)
   → 设 Statuses.SLEEP、navigation.stop()、切 POKEMON_SLEEPING_ACTIVITY、
      setMemory(POKEMON_SLEEPING, true)
   （GoToSleepTask.kt:22-52，条件 :33，动作 :38-44）

③ PokemonServerDelegate.updatePoseType
   isSleeping = POKEMON_SLEEPING || status==SLEEP，且 resting.canSleep
   → entityData.set(POSE_TYPE, PoseType.SLEEP)
   （PokemonServerDelegate.kt:376-403；isSleeping :384；when→SLEEP :393）
   → 客户端选中 poser 的 "sleep" pose（poseTypes:[SLEEP]，charizard.json:299-311）
   → 播放 animation.charizard.sleep
```

pkmoba 的两层抑制（`P/charizard/CharizardSleepSuppressor.java`，注册于 `PkmobaMod.java:63`）：

- **A 根部**：每 tick `getBrain().eraseMemory(CobblemonMemories.POKEMON_DROWSY)`（`:45`）——切断 ①②之间。
- **C 兜底**：若 `getCurrentPoseType()==PoseType.SLEEP` 就 `entityData.set(POKEMON_TYPE, STAND)`（`:48-50`）——直接改③的结果。
- 风险：只清 DROWSY 时，若 `pokemon.status == SLEEP`（战斗/外部状态）仍可能睡；直接写 `entityData` 可能与 delegate 的同 tick 重算竞争（`updatePoseType` 由 `delegate.tick` 触发，`PokemonEntity.kt:704`），实际表现取决于 tick 顺序。**【源码可见 + 推断】**

### 5.4 渲染：全局按 species 分发（详见 §2.3）

- 单例 `PokemonRenderer`（`PokemonRenderer.kt:69`），对任意 `PokemonEntity` 都用 `species.resourceIdentifier` 查 poser/texture/layers（`:96/:109`），查不到回退默认。
- 因此 pkmoba **不需要也不应该**注册自己的渲染器；`sendOut` 出来的实体自动走这条链路。**【源码可见 + 推断】**

### 5.5 Camera / MouseHandler 样板对照

| 维度 | Cobblemon | pkmoba |
| --- | --- | --- |
| 目标 | 骑乘相机 | 上帝视角相机 |
| 注入 | `@WrapOperation` on `Camera.setup` 内 `setPosition(DDD)`（`CameraMixin.java:63`） | `@Inject(method="setup", at=TAIL)`（`CameraMixin.java:32`） |
| 朝向 | 不改 rotation，只换 position（`MountedCameraRenderer`） | 同时 `setRotation(yaw,pitch)` + `setPosition(...)`（`:43-44`） |
| 附加 | `implements CameraDuck` 暴露 forwards/up/left（`:43-61`） | 无 |
| 鼠标 | `onScroll` 的 FIELD 注入点 + cancel（`MouseHandlerMixin.java:70-95`） | `onScroll`/`onPress` 的 HEAD + cancel（`MouseHandlerMixin.java:32-71`） |
| 共存性 | pkmoba 注释已考虑"不要和 Cobblemon 的 @WrapOperation 打架"；两者注入点不同（TAIL vs setPosition 调用处），理论上可共存 |  |

**【源码可见】**；实际共存效果需运行验证（未跑游戏）。pkmoba 的 mixins 清单见 `pkmoba.mixins.json:8-14`（仅 client：Camera/MouseHandler/KeyboardInput/Gui/Hud，`mixins: []`），**没有对 Cobblemon 类做 Mixin**——这是耦合度较低的好设计。**【源码可见】**

---

## 6. 许可与版本风险

### 6.1 许可

| 项 | 事实 | 位置 |
| --- | --- | --- |
| Cobblemon 代码许可 | **MPL-2.0**（Mozilla Public License 2.0） | `cobblemon-1.8.2/LICENSE:1-3` |
| 源文件头 | 每个 `.kt/.java` 由 blossom 自动生成 MPL 头（`@author/@since` 格式） | `common/build.gradle.kts` 的 `blossom { kotlinSources { generateLicenseHeader() } }` |
| 发布元数据 | `fabric.mod.json` 的 `"license": "MPL-2.0"` | `fabric/src/main/resources/fabric.mod.json:15` |
| pkmoba 许可 | `"license": "All-Rights-Reserved"` | `pkmoba/src/main/resources/fabric.mod.json:8` |

风险点（**【推断】**，需法务/项目决策，非代码问题）：

1. **MPL-2.0 是文件级 copyleft**：pkmoba 目前只是**编译期链接 + 运行时调用** Cobblemon 的公开 API，未修改/未内联其源码，通常不触发 MPL 的源码公开义务；但若将来把 Cobblemon 源码复制进 pkmoba（例如抄 mixin 或工具类），被复制的文件必须继续 MPL-2.0 并公开。
2. **资源版权与代码许可分离**：MPL-2.0 覆盖仓库内文件（含 assets 源码），但宝可梦角色形象/名称的权利属于任天堂/Game Freak/The Pokémon Company；Cobblemon 自身也是在非商业 fan project 框架下运行。pkmoba 直接复用其模型/贴图/音效，**不具备独立授权**，分发时依赖 Cobblemon 本体存在（幸好不打包资源）。**【推断】**
3. pkmoba 的 `All-Rights-Reserved` 与"依赖 MPL 模组"不冲突；但 pkmoba 自己的贴图/字形资源被 Cobblemon 反向引用则无此问题（无反向依赖）。

### 6.2 版本风险（重点）

| 风险 | 证据 | 影响 | 建议 |
| --- | --- | --- | --- |
| **硬锁 1.8.2 编译、运行期只要求 `>=1.8.2`** | `pkmoba/build.gradle:33` vs `fabric.mod.json:22` | 玩家装 1.8.3+/1.9 时 Fabric 允许启动，但 Kotlin 内部 API（`PokemonEntity.POSE_TYPE`、`PokemonClientDelegate.getLocatorStates()`、`PosableEntity.playAnimation`）可能已变 → `NoSuchMethodError`/`ClassNotFound`。pkmoba 已在关键处 try/catch（`PkmobaClient.java:284`、`CharizardCombat.java:788`、`CharizardSleepSuppressor.java:51`、`PlacementService.java:75-76` 注释提到"Cobblemon 缺失时 ClassNotFound 会打崩 server thread"），但 `CharizardManager.spawn` 的 `sendOut` 调用无 catch。 | 把 `depends.cobblemon` 收紧为 `~1.8.2` 或 `>=1.8.2 <1.9`；或补一层 `Class.forName` 探测并降级为"无喷火龙"模式。 |
| **资源 id 无编译期校验** | `firespin_actorcinder`（不存在）、`move.impact_generic.flying_1`（事件名错，应为 `impact.flying`） | 静默失效：粒子 warn、声音静默。 | 建立"id 白名单"测试（对 Cobblemon 资源目录做存在性断言），或启动时自检并日志告警。 |
| **Kotlin 元数据/开发环境** | `pkmoba/build.gradle:3-6` 注释：加 Kotlin 插件只为给 Loom 提供 kotlin-metadata；注释还提到"并不能解决 Cobblemon 在开发环境的元数据错位问题（见 README 开发环境限制）" | IDE/编译期 `Cannot access class` 类问题；pkmoba 的 workaround 是运行期全 try/catch + 字符串/反射兜底 | 保留 Kotlin 插件；新加 Cobblemon API 时优先考虑"调用点 try/catch + 可缺省功能"模式（项目已形成惯例）。注：项目根 `README.md` 未检索到"开发环境限制"章节（可能已迁移/删除），此注释属陈旧引用。**【源码可见】** |
| **maven 仓库在线依赖** | `build.gradle:19` 指向 `artefacts.cobblemon.com`，离线/私有网络构建会失败 | 构建不可复现 | 本地缓存 jar 或镜像到 `deps/`；CI 里固定校验和。 |
| **Mixin 与 Cobblemon 同方法共存** | pkmoba `CameraMixin`/`MouseHandlerMixin` vs Cobblemon 同名 mixin | 注入顺序变化可能导致上帝视角/轮盘行为异常（尤其 Cobblemon 版本调整注入点时） | 保持 pkmoba 注入点尽量"边缘化"（TAIL/HEAD），并做游戏内回归清单。 |
| **API 面虽小但都是深耦合** | §3 的 #1/#6/#7/#13/#16：`sendOut` 回调时序、`POSE_TYPE` SynchedEntityData、Brain 记忆名、locator 名、动画逻辑名 | 这些都不是稳定契约（可能随版本改名） | 在笔记/代码中标注"契约脆弱点"；升级前先跑 §7 清单。 |

---

## 7. 待运行验证清单（本次只读分析未执行）

1. `cobblemon:firespin_actorcinder` 是否真的失效：装 1.8.2 后按 W，看客户端日志有无 `[pkmoba] 找不到粒子特效 cobblemon:firespin_actorcinder`。
2. `cobblemon:move.impact_generic.flying_1` 作为 `SoundEvent` id 是否静默（应为 `cobblemon:impact.flying`）。
3. 睡眠抑制：夜间连续观察 3 个游戏日，确认不出现 SLEEP pose；同时观察是否出现"站着不动但闭眼"的中间态（说明 A 层生效、C 层未被触发）。
4. 走路动画兜底：命令移动时看是否仍有"滑行"（`WalkAnimator` 阈值 `4.0E-4` 是否合适）。
5. 与 Cobblemon 骑乘相机共存：骑乘状态下进出上帝视角，检查相机是否打架。
6. 升级演练：用 1.8.3/1.9 的 jar 替换后启动，验证 `sendOut`/`POSE_TYPE`/`ParticleStorm` 是否仍可用（预期有 `NoSuchMethodError` 风险）。

---

## 8. 一页速查（给后续开发者）

```text
模组坐标：com.cobblemon:fabric:1.8.2+1.21.1
  来源：publish-conventions(groupId=com.cobblemon, artifactId=project.name)
      + Versioning.kt(mod_version+mc_version) + artefacts.cobblemon.com/releases

喷火龙资源根：common/src/main/resources/
  species : data/cobblemon/species/generation1/charizard.json
  resolver: assets/cobblemon/bedrock/pokemon/resolvers/0006_charizard/0_charizard_base.json
  poser   : assets/cobblemon/bedrock/pokemon/posers/0006_charizard/charizard.json
  geo     : assets/cobblemon/bedrock/pokemon/models/0006_charizard/charizard.geo.json
  anim    : assets/cobblemon/bedrock/pokemon/animations/0006_charizard/charizard.animation.json
  texture : assets/cobblemon/textures/pokemon/0006_charizard/charizard*.png
  cry     : sounds.json -> pokemon.charizard.cry -> sounds/pokemon/charizard/charizard_cry.ogg

生成喷火龙：PokemonSpecies.getByIdentifier(cobblemon:charizard) -> new Pokemon() -> sendOut()
渲染分发  ：PokemonRenderer 单类 + VaryingModelRepository.getPoser(species.resourceIdentifier)
动画播放  ：PosableEntity.playAnimation("special") == PlayPosableAnimationPacket(候选名集合)
特效播放  ：服务端发 effectId -> 客户端 BedrockParticleOptionsRepository.getEffect(id) -> ParticleStorm.createAtPosition
姿态驱动  ：PokemonBehaviourFlag.FLYING -> updatePoseType -> POSE_TYPE -> poser pose -> bedrock animation
睡眠链路  ：DrowsySensor -> POKEMON_DROWSY -> GoToSleepTask -> POKEMON_SLEEPING/SLEEP -> POSE_TYPE.SLEEP
```

---

*本文所有行号基于 `cobblemon-1.8.2` 源码快照与 pkmoba 0.19.2 工作区；引用格式 `文件:行`，可直接 Ctrl+G 跳转核验。未运行 gradle、未启动游戏、未修改任何文件。*

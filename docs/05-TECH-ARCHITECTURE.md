# 05 · 技术架构

> 上位文档：`00-OVERVIEW.md`。
> 本文档描述**已经落地在 `core/` 与 `lwjgl3/` 里的实际结构**，不是设想。

---

## 1. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 引擎 | **LibGDX 1.13.5** | 跨桌面/Android/iOS/HTML |
| 语言 | **Java 17** | 无 Kotlin 依赖；17 是 AGP 8 的 D8 能稳妥消化的上限，桌面端同样跑得动 |
| 构建 | **Gradle 8.13**（wrapper 已锁定） | AGP 8.11 要求 8.13 |
| 模块 | `core`（全部逻辑）+ `lwjgl3`（桌面）+ `android`（安卓） | 标准 LibGDX 布局，`core` 一行不改同时服务两个平台 |
| Android | AGP **8.11.1** · compileSdk **36** · minSdk **26** · targetSdk 36 | 与 8.13 严格配套 |
| 3D | `gdx` 自带 `ModelBatch` + `Renderable` | 不引入第三方 3D 库，也不要 `ModelBuilder`（见 §5.4） |
| UI | **纯 ShapeRenderer + SpriteBatch**，不用 Scene2D | 不需要 `uiskin.json`，且触屏与键盘能映射到同一个 `RollInput` |
| 物理 | **自研确定性固定步长**（见 §3） | 见下 |
| 资源 | **零外部美术文件**，全部程序化生成；唯一随包二进制是裁剪后的中文字体（见 §5.3 / D30） | 见 §5 |

---

## 2. 目录结构

```
gunmu party/
├── docs/                     规范文档（本目录）
├── assets/                   随包分发的资源（字体 game.ttf + charset.txt，见 §5.3）
├── tools/                    不依赖 Gradle 的构建脚本（见 §9）
│   ├── fetch_libs.py         按构件元数据抓全依赖 → libs/ + libs-mobile/
│   ├── fetch_natives.py      从 natives 构件解出 .so → android/libs/<abi>/
│   ├── make_font.py          扫描源码 → 裁剪中文字体 + 字形清单
│   ├── build_jar.py          打自包含桌面 fat jar
│   ├── build_apk.py          直连 SDK 工具链打 APK\n│   ├── quick_route.py        只重编 core 并单跑一个无头测试（调地图/平衡用）
│   └── check_shot.py         解 PNG 统计画面里到底画出了什么
├── core/
│   └── src/main/java/top/gunmu/party/
│       ├── GunmuPartyGame.java       Game 入口，用 mobile 开关区分平台
│       ├── GameConfig.java           全部数值常量（唯一来源）
│       ├── core/                     Roll 实体、RollInput、Combat、枚举
│       ├── physics/                  HeightField / StaticBody / PhysicsWorld
│       ├── map/                      MapDef / Route / MapRegistry / MapValidator /
│                                     Obstacle / Zone / TrackProgress / MapNav
│       ├── items/                    ItemType / ItemSystem / Effect / Summon / Rarity
│       ├── ultimates/                UltimateType / UltimateSystem / Projectile
│       ├── match/                    MatchState / LevelDirector
│       ├── ai/                       BotBrain
│       ├── render/                   SceneRenderer / CameraRig / MeshFactory / TextureFactory / Fonts / Hud / Screenshot
│       ├── screens/                  UiScreen / Lobby / Level / LevelResult / UltimateSelect / Champion
│       ├── console/                  ConsoleProbe（无窗口整局回放）
│       └── tools/                    SmokeTest / PrimitiveTest / TerrainAudit /
│                                     InputMappingTest / JumpTest / MapPickTest /
│                                     RouteAudit / StartBarrierTest / TextWrapTest /
│                                     DashTest / ItemPoolTest / MapNavTest /\n│                                     RouteOverlapTest / RaceRunTest / MoveControlTest
│                                     （全部不碰 GL，命令行可直接跑）
├── lwjgl3/
│   └── src/main/java/top/gunmu/party/lwjgl3/Lwjgl3Launcher.java
└── android/
    ├── AndroidManifest.xml
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── java/top/gunmu/party/android/AndroidLauncher.java
        └── res/                     主题 / 字符串 / 纯矢量自适应图标
```

### 2.1 平台差异只有一处开关 🧭

桌面与安卓**共用同一份 `core`**，差别全部收敛到 `GunmuPartyGame(boolean mobile)`：

| 项 | 桌面 | Android | 实现在哪 |
|---|---|---|---|
| 触屏摇杆 / 按钮 | 隐藏 | 默认显示 | `Hud.touchControlsVisible` |
| UI 缩放 | 1.0× | 按屏高放大（1080p ≈ 1.33×） | `Hud.resize()` + `Fonts.setUiScale()` |
| 地形**渲染**网格 | 每格 | 隔一格（顶点数 ≈ 1/4） | `GameConfig.terrainRenderStep` → `MeshFactory.terrain(f, step)` |
| 地形**物理** | 完整 heightfield | **完全相同** | 不受 `terrainRenderStep` 影响 |
| 返回键 | — | 大厅双击退出；比赛中吞掉 | `Gdx.input.setCatchKey(BACK)` + 各 Screen 的 `onKey` |
| 全屏 | 窗口 | 沉浸式 + 刘海铺满 + 常亮 | `AndroidLauncher` |

**关键约束**：`terrainRenderStep` 只能影响画出来的三角形，绝不能进入 `HeightField`。
否则桌面与安卓的碰撞结果会不一致，将来做联机时是灾难。

---

## 3. 为什么自研物理（决策 D2）

**候选方案对比：**

| 方案 | 优点 | 否决理由 |
|---|---|---|
| `gdx-bullet` | 功能全、真圆柱/真地形 | ① 原生库（`.dll`）跨平台分发麻烦；② **不确定**——Bullet 的多体求解不保证跨机器同结果，联机时无法用"输入回放"做校验；③ 高速度下隧道效应需要额外 CCD 配置 |
| Box2D 2D | 简单 | 需要的"下坡提速 / 高度 / 跳跃 / 3D 视线"它给不了 |
| **自研 heightfield + 球体** | 完全确定性、零原生依赖、代码量可控（~700 行）、为联机输入回放铺路 | 需要自己写碰撞（已写） |

**确定性保障措施：**

1. 固定步长 `1/60`，禁可变 dt。
2. 实体遍历顺序 = **ID 升序**，永不依赖 `HashMap` 迭代顺序。
3. 所有浮点运算顺序固定（同一段代码路径，不做"优化分支"）。
4. 随机数使用**每局一条种子**的 `java.util.Random`，AI 抖动、掉落、抽道具全部走它。
5. 物理解算**不读渲染状态**（插值只在渲染时做）。

---

## 4. 主循环

```
render():
    accumulate(真实 deltaTime, 上限 0.25s)          // 防 spiral of death
    while (accumulator >= 1/60):
        stepPhysics(1/60)                           // 确定性
        accumulator -= 1/60
        if (++steps > 5) { accumulator = 0; break; } // 单帧最多补 5 步
    renderInterpolated(accumulator / (1/60))        // 渲染插值，不动物理
```

`LevelScreen` 每帧的顺序：

```
1. 采样输入（玩家 + 29 个 AI）→ RollInput[]
2. 物理步进 → 位置/速度更新、碰撞、伤害
3. 存档点检测 → 更新 respawnPoint
4. 道具系统 tick → 刷新点计时、拾取、效果倒计时
5. 决战技 tick → CD、前摇、投射物、召唤物
6. 机关 tick → 旋转锤角度、锯盘位置、压板相位
7. LevelDirector tick → 晋级名额、死亡计数、超时
8. 渲染
```

**顺序不可换**：输入必须先于物理，物理必须先于伤害，否则同帧内的结果会随机器速度变化。

---

## 5. 渲染方案

### 5.1 场景

- **3D**：`PerspectiveCamera` FOV 55°，俯视 35°，跟随本地玩家，平滑跟随（指数插值）。
- 地形：按地面材质分成若干 `MeshPart`，各自一份 `Material`（贴图 + 顶点色）；法线由 heightfield 差分求出，光照才看得出坡。
- 滚木：自写的圆柱网格，长轴朝向每帧由速度方向决定（横向滚动），视觉滚转角 `dθ/dt = v⊥ / r`。
- 机关 / 道具 / 投射物 / 召唤物：程序化的方块 / 圆柱 / 球 / 八面体，靠 diffuse 颜色区分。
- 天空：清屏色 + 一盏平行光 `DirectionalLight` + 环境光。

### 5.2 程序化纹理

`TextureFactory` 用 `Pixmap` 生成木材（年轮 + 噪点）、草地 / 岩面 / 冰面 / 泥坑 / 沙地 / 熔岩 / 深渊，
以及道具底座光斑，全部用固定 `Random(seed)`。**不使用任何外部图片文件。**

### 5.3 关于美术资源

🔒 **所有可见物均由代码程序化生成**，`assets/` 里只有字体。

这是刻意的工程决策，不是"占位美术"：
- 程序化生成是**确定性**的（固定种子），不引入二进制文件、不污染仓库、不影响跨平台一致性。
- 接入正式美术时 `TextureFactory` 会优先读取 `assets/textures/<name>.png`，存在即用、不存在才回退到程序化生成——**接入点已经预留，不需要重构**。

命名约定（供后续美术接入）：

```
assets/textures/roll_wood.png           滚木主体（侧面）
assets/textures/roll_wood_end.png       滚木端面（年轮）
assets/textures/item_shell.png          道具外壳
assets/textures/terrain_grass.png       草地
assets/textures/terrain_rock.png        岩面
assets/textures/terrain_ice.png         冰面
assets/textures/terrain_mud.png         泥坑
assets/textures/terrain_sand.png        沙地
assets/textures/terrain_lava.png        熔岩
assets/textures/terrain_void.png        深渊
assets/fonts/game.ttf                   中文字体（裁剪产物，见下）
assets/fonts/charset.txt                字体字形清单（与 game.ttf 同源）
```

#### 字体为什么随包分发（决策 D30）

中文界面必须有字体。靠系统字体有三处不可控：Android 各机型 `/system/fonts/*`
的文件名与可读性都不统一；macOS 是 `.ttc` 集合；找不到就只剩 ASCII 兜底。

`tools/make_font.py` 的做法：

1. 扫 `core/lwjgl3/android` 下**全部 `.java` 源文件**，取出用到的每一个 CJK 字符
   （加上完整 ASCII、CJK 标点、全角形式）——而不是人工维护一份字符表，那必然会漏。
2. 用 `pyftsubset` 从 Noto Sans SC（SIL OFL，可再分发）裁出实际用到的字形。
3. **同时**产出 `charset.txt`。运行时 `Fonts.charset()` 优先读它，
   保证"FreeType 烘焙的字"和"字体文件里有的字"永远同一份清单——
   否则漏一个字的后果是该处渲染成空白，且极难发现。

结果：**260 KB / 1262 字形**，桌面与安卓完全一致。

> 回退链仍保留：`assets/fonts/game.ttf` → 系统字体若干条 → ASCII 兜底。
> 全程找不到中文字体时**不会崩**，只是文案退化成英文。

### 5.4 为什么不用 `ModelBuilder` / `ModelInstance`（决策 D29）

当前 libGDX 版本里 `Model` 只能由 `ModelData` 或 `ModelBuilder` 构造，
`ModelInstance.model` 是 `final`，`Renderable.meshPart` 也是 `final`——
这意味着"同一份几何、多种颜色"要么复制网格，要么绕开 `Model`。

本作的实体数量（30 滚木 + 14 道具点 + 25 机关 + 投射物/召唤物）如果每种颜色都复制一份网格，
内存会翻好几倍。因此几何层直接产出 `MeshPart + Material`，渲染层把它塞进池化的 `Renderable`，
逐帧改写 `meshPart` / `material` / `worldTransform`。

### 5.5 相机与操作

> 相机的几何契约与三个不变量见 §10.20；输入必须先过相机 yaw 才能当世界方向用。
> 这一版之前的相机把玩家顶到画面外、且 W 键在往后退，都是没有契约导致的（D33）。

| 输入 | 动作 |
|---|---|
| 左半屏拖动（轮盘） | 移动（全向） |
| 右下「跳」/「道具」/「决战技」/「加速」 | 对应动作 |
| 右下「攻击」 | 普通攻击（**只在第 5 关出现**） |
| 键盘 `WASD` / 方向键 | 移动 |
| 键盘 `Space` | 跳跃 |
| 键盘 `E` | 使用道具 |
| 键盘 `Q` | 施放决战技 |
| 键盘 `Shift` | 加速 |
| 键盘 `J` | 普通攻击（**只在第 5 关生效**） |
| 安卓返回键 | 大厅连按两次退出；比赛中被吞掉 |

触屏与键盘写入的是**同一个 `RollInput`**，`Hud.applyInput()` 是唯一的合成点。

---

## 6. 关键类职责

| 类 | 职责 | 不做什么 |
|---|---|---|
| `GameConfig` | 全部数值常量的唯一来源 | 不含逻辑 |
| `HeightField` | 高度/材质网格的采样与梯度 | 不知道 Roll 存在 |
| `PhysicsWorld` | 步进、碰撞、冲量、伤害判定 | 不产生视觉效果 |
| `Roll` | 一个玩家实体的全部状态 | 不自己读输入（输入从外部喂） |
| `RollInput` | 抽象输入（移动向量 + 三个按钮边沿） | 不知道来源是人是 AI |
| `BotBrain` | 生成 `RollInput` | 不做任何作弊性的直接改状态 |
| `ItemSystem` | 刷新点、拾取、效果、召唤物 | 不关心谁赢了 |
| `UltimateSystem` | 决战技 CD、前摇、投射物 | 同上 |
| `LevelDirector` | 名额、死亡计数、超时、结算 | 不碰物理与渲染 |
| `SceneRenderer` | 把世界画出来 | 不修改世界 |
| `Hud` | 屏幕 UI | 同上 |

---

## 7. 联机预留（当前不实现）

自研物理与 `RollInput` 抽象就是为这一步铺的路。将来的形态：

```
客户端：只发 RollInput（每帧 6 字节），本地**预测执行**
服务端：权威跑同一份 PhysicsWorld，广播输入与关键帧
校验  ：比对同一 tick 的世界哈希，不一致则回滚重放
```

因此**现在就不能写任何依赖渲染帧率或平台差异的物理代码**。
这条约束在 §3 的确定性保障里已经落实，也是 §2.1 里
"`terrainRenderStep` 只能影响渲染"这条红线的来源。

---

## 8. 无窗口冒烟测试

`core/src/main/java/top/gunmu/party/tools/SmokeTest.java` + `console/ConsoleProbe.java`
组成一条**不需要 OpenGL、不需要显示器**的自检链路：

```bash
gradlew smoke          # 等价于 :core:smokeTest
```

它做两件事：

1. **构建并校验全部 10 张地图**，跑 `MapValidator` 的全部硬断言
   （数量基线、路径长度、下坡占比、存档点安全区、道具点可行走、出生点间距、机关伤害预算）。
2. **无头跑完整局 5 关淘汰赛**，用同一个 `BotBrain` 驱动"玩家"，
   统计并断言：死亡发生了、道具被捡起并被用出去、存档点被激活、
   决战技被施放、流程真的推进了关卡。

之所以能这么做，是因为**物理与流程层不引用任何 GL 类**——
`LevelDirector → PhysicsWorld → Combat / ItemSystem / UltimateSystem` 这条链
纯 CPU + `com.badlogic.gdx.math`，所以能在命令行里跑。

`ConsoleProbe` 还会打印每关的分布诊断（场上人数、进度分布、死因累计、结束原因），
调平衡时非常有用。**实测数据直接驱动了 D26（走廊放宽）与 D27（AI 走廊保持）两次调参。**

---

## 9. 构建：两条路（决策 D31）

### 9.1 主路径：Gradle

```bash
gradlew.bat runGame    # 直接启动桌面版
gradlew.bat dist       # 打桌面 fat jar
gradlew.bat smoke      # 无窗口冒烟测试
gradlew.bat apk        # 打 Android debug APK
```

Gradle 是标准路径。但它的 `native-platform` 在部分受限环境里会因
**创建 `.lock` 文件失败**而连启动都进不去：

```
Could not initialize native services.
> Failed to load native library 'native-platform.dll' for Windows 10 amd64.
Caused by: java.io.FileNotFoundException: ...\native-platform.dll.lock (拒绝访问)
```

注意这条报错的层级：它拦住的是 Gradle 的**调度层**（守护进程、缓存、文件锁），
不是编译本身。所以工程里同时准备了下面这条不依赖 Gradle 的路。

### 9.2 降级路径：`tools/*.py` 直连工具链

```bash
python tools/fetch_libs.py     # 抓全依赖 → libs/ + libs-mobile/（--verify 只校验）
python tools/fetch_natives.py  # 解 .so → android/libs/<abi>/（4 个 ABI）
python tools/make_font.py      # 扫描源码 → assets/fonts/game.ttf + charset.txt
python tools/build_jar.py      # → dist/gunmu-party-<版本>.jar（+ 别名 gunmu-party.jar）
python tools/build_apk.py      # → build/apk/gunmu-party-<版本>.apk（+ 别名 gunmu-party-debug.apk）
```

**版本号**：唯一来源是 `GameConfig.VERSION` / `VERSION_CODE`（命名规范 `a`=alpha 内测版 /
`b`=beta 测试版 / `v`=release 正式版），统一读取入口 `tools/version.py`。
两个构建脚本都读它来命名产物并注入安卓清单的 `versionName`，游戏大厅也显示同一份；
读不到直接抛异常（不猜），并会核对 `build.gradle` / `android/build.gradle` 有没有漂移。
每次构建产出**两份内容相同的产物**：带版本号的用于归档，不带版本号的是"当前最新"的固定路径。

**固定签名**（a0.0.1 起）：签名本体 `keystore/gunmu-party.jks`（**入库目录**），
别名 `gunmu-party`，密码与指纹见 `keystore/README.txt`。
`build_apk.py` 用它签（找不到直接失败、**不回退 debug key**）；`android/build.gradle` 的
`signingConfigs.fixed` 同样指向它，debug / release 都用。
为什么不用 `~/.android/debug.keystore`：那是每台机器各自生成的（别人打的包与你打的包签名不同、
互相装不上），而我们拷到 `build/` 下的那份还在 `.gitignore` 里 —— 换机器或清理目录后就没了。
⚠️ **丢签 = 已安装用户永远无法升级**，所以它必须进版本控制并另行备份。

### 9.2.1 安卓运行期依赖：不止"pom 那一层"

`gdx-backend-android` 的元数据里除了 gdx 本体，还有一条 **`androidx.core:core`** ——
这是 libGDX 1.13.5 的 `AndroidApplication.init()` 处理 edge-to-edge 时用的
（`ViewCompat.setOnApplyWindowInsetsListener`）。走 Gradle 时 AGP 会自动拉全，
而直连工具链的路必须自己拉，否则就是"装到手机上一打开就闪退"：

```
java.lang.NoClassDefFoundError: Failed resolution of:
    Landroidx/core/view/OnApplyWindowInsetsListener;
```

**为什么编译与打包都不会报**：javac 不需要解析 `AndroidApplication.init()` 方法体里
引用的类（`AndroidLauncher` 只调它的公开 API），所以漏依赖 → 编译通过、dex 生成成功、
签名校验通过，只有真机启动那一瞬间才炸。

`tools/fetch_libs.py` 因此做了三件事（全部按元数据，不手抄）：

1. **闭包来源是 `.module` 的 `java-runtime` variant**，不是 pom —— pom 里混着 lint /
   annotationProcessor / compileOnly 的条目，照它递归会拉出 `lifecycle-compiler`、
   `lifecycle-livedata-core-ktx-lint`（这个构件根本不存在，直接 404）、guava、rxjava……
2. **白名单闸门**（`androidx.*` + 少数几个 artifact）：否则 `.module` 会一路带出
   kotlinx-coroutines 的 debug/rx/javaFx/slf4j 变体、play-services、support-v4 等 60 个构件 24 MB。
   名单不是猜的 —— 每一行都是 `verify_dex.py` 报出缺类之后补的。
3. **下载前就做版本对齐 + 补 `-jvm` 变体**：`annotation-1.8.1.jar` / `collection-1.4.2.jar` /
   `kotlinx-coroutines-core-1.6.4.jar` 这几个本体**一个 class 都没有**，实现全在同版本的
   `*-jvm` 里（Gradle 靠 `available-at` 取，我们手动补）。对齐放在下载前而不是下载后清理，
   是因为受限环境会拦批量删除（见下），"下完再筛"会留下重复版本的同一个库，
   而 d8 遇到两个 jar 定义同一个类是**直接报错**的。

### 9.2.2 库的 R 类：AGP 3.0 之后不再随 AAR 分发

AGP 3.0 起，**库的 R 类不再打进 AAR 的 `classes.jar`** —— 应用构建时由 AGP 按 AAR 的
`res/` 生成对应包名的 `R.java`。直连工具链没有这一步，于是 dex 里对
`Landroidx/core/R$id;`、`Landroidx/lifecycle/runtime/R$id;` 的引用全是空的。

`build_apk.py` 的 `compile_library_resources()` 照着 AGP 的做法补上：
把每个 AAR 的 `res/` 解出来 → `aapt2 compile` → 与自己的资源一起 `link`，
并用 `--extra-packages` 让 aapt2 为那些包各生成一份 `R.java`
（实测 8 个包：`androidx.core`、`androidx.lifecycle.runtime`、`androidx.arch.core`、
`com.badlogic.gdx.backends.android` …），生成的 `R.java` 会自动被 javac 一起编译。

APK 那一步就是把 `aapt2 compile → aapt2 link → javac → d8 → 打包 → zipalign → apksigner`
七步串起来——**没有守护进程、没有增量缓存、没有后台状态**，因此结果可复现，
也不会被调度层的环境问题波及。

### 9.2.3 类路径资源：jar 里的非 class 文件也是运行期资源（D64）

打包时只编 `.class` 是**错的**。libGDX 的 `DefaultShader` 是这么读着色器的：

```java
Gdx.files.classpath("com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl");
```

还有 `com/badlogic/gdx/utils/lsans-15.fnt` / `.png`（`new BitmapFont()` 的默认字体）、
`depth.*.glsl`（阴影）、`particles.*.glsl`（粒子）。Gradle 打 APK 时会把 jar 里的资源
铺到 APK 根部（`mergeJavaResource`），安卓的 `ClassLoader` 就能按原路径取到；
直连工具链的路只编 `.class`，于是：

```
GdxRuntimeException: File not found:
    com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl (Classpath)
```

**桌面端看不出来** —— 桌面 fat jar 是整包合并的（`tools/build_jar.py`），
所以这个 bug 只在安卓炸，且炸在**进比赛那一帧**（`SceneRenderer` 里第一次 `ModelBatch.render`）。

`build_apk.py` 的 `classpath_resources()` 铺资源，口径是**黑名单**而不是白名单
（除了明确是编译期元数据的一律铺）—— 白名单漏一个新 jar 的新资源，就是又一次"只有真机才炸"：

| 跳过 | 理由 |
|---|---|
| `META-INF/**` | 依赖自带的签名文件与 MANIFEST 不该进 APK |
| `.class` | 已经进 dex 了 |
| `.gwt.xml` / `.rl` | GWT 模块描述符 / ragel 源码，只有改 libGDX 时才用 |
| `.kotlin_builtins` `.kotlin_metadata` `.kotlin_module` `.knm` | Kotlin / KMP 的**编译期**元数据 |
| 无扩展名的条目 | KMP 的 `commonMain/default/linkdata/module` 之类，同上 |

实测：gdx + androidx 的 23 + 3 个 jar 里，真正要铺的是 **8 个文件 / 49.3 KB**
（6 个 glsl + `lsans-15.fnt` + `lsans-15.png`），其余全是元数据。

### 9.2.4 原生库的压缩方式必须与清单声明自洽（D65）

`.so` 是**压缩**进包的（`zipfile` 用 DEFLATED），而 Android 只在
`android:extractNativeLibs="true"` 时才会在安装阶段把它解到应用目录；
否则系统按"必须未压缩 + 页对齐"（AGP 的现代默认：`ZIP_STORED` + `zipalign -p`）处理，
`System.loadLibrary` 直接失败 —— 表现是**一点就退**，比着色器那次更早、堆栈里连 libGDX 都看不到。

`build_apk.py` 的 `check_native_packaging()` 在打包后立刻断言这件事：
压缩 + 没写 `extractNativeLibs="true"` → **中止构建**；反过来（不压缩却写了 true）只提示。
清单里那句 `android:extractNativeLibs="true"` 也因此是**显式**写的，不靠"缺省即 true"。

### 9.4 两个必须记住的坑

**① 依赖清单不许手抄（决策 D32）。**
libGDX 1.13.x 把 `SharedLibraryLoader` 从 `gdx.jar` 拆到了独立构件
`com.badlogicgames.gdx:gdx-jnigen-loader`。`GdxNativesLoader` **编译期不引用、
运行期才引用**它，所以漏掉的后果是：`javac` 通过、`gradle build` 通过、
游戏一启动就

```
java.lang.NoClassDefFoundError: com/badlogic/gdx/utils/SharedLibraryLoader
```

`tools/fetch_libs.py` 里维护了完整清单，并带 `REQUIRED` 关键类断言防复发。

**② 工程路径含空格。**
本工程名是 `gunmu party`，而 `javac` 的 `@argfile` 与 `-cp` 一旦拿到含空格的绝对路径
就会被拆碎（报 `无效的标记: D:/Downloads/gunmu`）。
脚本统一把传给工具链的路径换成 **Windows 8.3 短路径**（`D:/DOWNLO~1/GUNMUP~1`）。
注意短路径**只能对已存在的路径取**——对输出文件取会把 `gunmu-party.jar`
变成 `GUNMU-~1.JAR`，所以产物一律先落在工作区里再拷到最终位置。

### 9.4 产物验收基线

| 产物 | 大小 | 验收方式 |
|---|---|---|
| `dist/gunmu-party-a0.0.2.jar`（别名 `dist/gunmu-party.jar`） | ≈ 9.9 MB | 从**任意目录** `java -jar` 启动；日志出现 `已加载中文字体: fonts/game.ttf`；jar 内含 22 个原生库与 6 个 `.glsl` |
| `build/apk/gunmu-party-a0.0.2.apk`（别名 `build/apk/gunmu-party-debug.apk`） | ≈ 5.0 MB | `aapt2 dump badging` 包名/标签/启动活动正确，**`versionName='a0.0.2'`**；4 ABI 的 `.so` + `classes.dex` + `assets/fonts/` + **根部 8 个类路径资源（6 个 `.glsl` + `lsans-15.fnt/.png`）** 齐全；`apksigner verify` 通过，**证书 SHA-256 = `9378eae8…c7f5`（固定签名）** |

体积从 a0.0.1 的 3.2 MB 涨到 5.0 MB，是因为打进了 androidx 的运行期依赖与 Kotlin 运行时
（Gradle 打的 debug 包同样是这个量级，只有 release 经 R8 收缩后才会变小）。

---

## 10. 渲染自检：把"看着不对"变成会 fail 的断言

这一节是补的，因为**上一版带着"整片地面不显示"交付过**——
编译器通过、10 张地图硬校验通过、整局 5 关冒烟测试通过、帧率正常，
唯一的症状是画面里 83% 的像素是清屏色。

结论：**渲染这一类问题，只要不能用断言描述，就一定会漏到用户面前。**
所以补了三个不需要眼睛的检查。

### 10.1 程序化几何自检 `PrimitiveTest`

对每个由 `MeshFactory` 生成的凸几何，逐三角形算几何法线（边的叉积），断言：

1. **法线朝外**：`dot(几何法线, 面心 − 形心) > 0`，且顶点法线与几何法线同向；
2. **包围盒尺寸正确**：单位立方体必须三轴都张开 1.0，球/圆柱/八面体必须张开 2r。

这一条同时抓两类 bug：

| 曾经的 bug | 被哪条断言抓住 |
|---|---|
| 地形/球面三角形绕序反了 → 几何法线 −Y、顶点法线 +Y → 背面剔除后整片消失 | 「法线朝外」 |
| `box()` 顶点漏了沿面法线的一项 → 六个面全部落在过原点的平面上，**不是立方体而是六张纸片** | 「包围盒尺寸」（X/Y/Z 跨度会有一个是 0） |

### 10.2 选图自检 `MapPickTest`

玩家报过"每关地图都是固定的"。这条断言用 **48 个种子**各建一局，统计第 1 关抽到几种图：

```
48 个种子共抽到 4 张不同的第 1 关地图（竞速池共 4 张）
选图多样性正常
```

修复前它输出的是 **`48 个种子共抽到 1 张`** —— 断言直接抓到了 bug（见 D39）。

### 10.3 输入映射自检 `InputMappingTest`

屏幕空间的方向必须按相机 yaw 转到世界空间，而**横向与纵向的符号很容易只写对一个**：

| 输入 | 世界里对应 | 公式 |
|---|---|---|
| W / ↑（屏幕上） | 相机水平朝向 | `f = (sin yaw, cos yaw)` |
| D / →（屏幕右） | `up × (−f)` | `(−cos yaw, 0, sin yaw)` |

判据不是"公式等于公式"，而是拿 `PerspectiveCamera` **真实构造出的视图矩阵**当参照
（libGDX 把屏幕右写在第 0 行、屏幕上写在第 1 行），对 10 个朝向分别算点积，必须 ≈ +1。

实测：把符号改回错误版本，10 个朝向的 `D→右` 点积全部变成 **−1.0000** 而 `W→上` 仍是 +1.0000 ——
正好复现"只有左右反了"。所以这条断言真的能抓住它。

> 注：`PerspectiveCamera.update()` 会走 `Frustum.update()`，其中 `Matrix4.prj` 是 native 的，
> 无头环境要先 `GdxNativesLoader.load()`（不需要窗口/GL）。

### 10.4 跳跃自检 `JumpTest`

玩家报"怎么还是没法跳跃"。查明 `input.jump` **只有两处写入、全仓零处读取** ——
`GameConfig.JUMP_VELOCITY` 定义了也从没被用过，跳跃整条链路是断的，
而编译、10 张地图硬校验、整局冒烟测试**全部通过**。

于是补一条端到端断言：在**平地上**跑真实物理，验证

| 检查 | 判据 |
|---|---|
| 能起跳 | 按一次后 `vel.y ≈ JUMP_VELOCITY − GRAVITY·dt` |
| 当帧离地 | `grounded == false`（否则会被 `resolveGround` 按回去） |
| 边沿被消费 | `input.jump` 读后即为 `false`，不会变成"一直跳" |
| 无二段跳 | 空中再按，`vel.y` 必须继续减小 |
| 高度对得上抛体公式 | `maxY − 地面 ≈ v²/(2g)`，误差 < 15% |
| 跳得看得见 | 腾空高度 > 一个滚木半径 |
| 不是一次性的 | 落地后第二次起跳同样成功 |

反证（把消费点临时屏蔽）会输出 9 项失败，其中
`vel.y=0.000`、`腾空高度 0.000m（误差 100%）`、`滞空 1 帧` —— 这条断言确实抓得住。

整局冒烟里再加一条累计量断言：`PhysicsWorld.jumpCount` 必须 > 0
（实测一整局 AI 起跳 **352 次**），防止链路再次静默断掉。

### 10.5 进度判定审计 `RouteAudit`

玩家报：「之字断崖跳到虚空里时能直接读到下面的存档点和通关区域，因为销毁不是瞬间销毁的」。

进度原来是 `route.nearest(x, z)` 算的 —— **只看水平坐标、完全不看高度**。折返型赛道
在俯视图上两段路靠得很近，脚下一空掉进虚空时最近点会跳到"更靠后"的那一段，弧长瞬间跳几百米；
而坠落判定要等掉够几米才生效，这段时间足够把存档点与终点判定全部误触发。
**地形也是用同一个 `nearest` 裁出来的**，所以连几何本身都带着这个歧义。

判据收敛到 `map/TrackProgress`（运行时与审计共用，见 D36）：弧长只在
「脚下可行走 + 横向不出走廊 `|lat| ≤ hw + sh` + 高度对得上路面（含路肩每往外 1 米降 0.42）」
三条同时成立时才算；并且分别用「水平最近段」与「含高度最近段」（`Route.nearestTrack`）
各试一次，前者与地形口径一致、后者解决叠层赛道。读不到就**冻结上一次弧长**。

审计做两个方向：

| 方向 | 做法 | 判据 |
|---|---|---|
| 虚空不许偷进度 | 每个虚空格子，从旁边路面高度**往下落 20 米**，逐步询问弧长 | 读到的弧长不得超过旁边路面弧长 + 8m |
| 路面必须可读 | 每个可行走格子按正常站姿（贴地）询问弧长 | 必须全部可读，否则闸门太紧会把人卡在半路 |

实测：

| | 修复前 | 修复后 |
|---|---|---|
| 之字断崖 | **2021 个虚空格子能偷进度，最多偷 356.1m** | 最大可疑差 0.0m |
| 青丘缓坡 / 冰隙峡谷 / 崩落长阶 | 588 / 544 / 539 格，最多约 58m | 0.0m |
| 可行走格可读率 | — | **100%**（4 张图共 13.9 万格） |

整局侧证据：之字断崖从「均 604m · 最好 **620m**（= 全图长度，作弊特征）」变成
「均 471m · 最好 598m」；而**没有虚空可偷的崩落长阶完全没变**（均 611m · 最好 618m · 死亡 0），
说明闸门没有误伤正常玩法。

### 10.6 输入消费点必须齐全

与上一条同源的一类 bug：**信号写进去了，没人读**。

| 输入 | 消费点 | 状态 |
|---|---|---|
| `input.jump` | `PhysicsWorld.stepOne`（读后立即置 false） | 曾经**全仓零处读取**，跳跃整条链路是断的 |
| `input.useItem` | `ItemSystem` | 正常 |
| `input.useUltimate` | `UltimateSystem` | 正常 |
| `input.dash` | `PhysicsWorld.stepOne`（读后立即置 false） | 正常（D48） |
| `input.attack` | `AttackSystem.tick`（读后立即置 false） | 正常（D60）；**无论本关是否开放普攻都会清掉**，否则会攒到进决赛关那一刻 |
| `RollInput.clearEdges()` | — | 定义了但从未被调用 |

判据：`JumpTest` 端到端跑一遍（见 §10.4），整局冒烟再断言 `jumpCount > 0`。

### 10.7 地形绕序审计 `TerrainAudit`

地形是高度场，**所有面的几何法线必须朝上**。审计直接复用渲染器同一条代码路径
（`MeshFactory.collectTerrainTile`，已抽成不碰 GL 的纯计算），对 10 张地图逐面统计：

```
[OK] race_ice_canyon    三角  76451  朝上 100.00%  退化 0  单块最多 15832 顶点
```

朝上占比低于 98% 直接判失败。**这是同一份代码，不是另写一份验算**，
所以它不会因为"验算写对了、渲染写错了"而漏判。

### 10.8 地形必须分块

`Mesh.setIndices` 只接受 `short[]`，因此单个网格的顶点索引上限是 65535。
冰隙峡谷的 ICE 材质组一度有 **67960** 个顶点 —— 索引回绕之后三角形连到错误的顶点上，
地形被撕成碎片。现在按 96 格一块切，单块最多约 3.7 万个顶点，并在超限时**直接抛异常**
而不是悄悄画错。

### 10.9 画面自检 `tools/check_shot.py`

看不了屏幕时的兜底：解 PNG（环境里装不上 PIL，脚本自己实现了解码）统计

| 指标 | 健康 | 异常信号 |
|---|---|---|
| 清屏色占比 | 35~50% | > 55% 说明地形整片没画出来 |
| 下半屏非背景率 | 65~80% | < 30% 说明玩家脚下没有地面 |
| 颜色种类 | 上万 | < 40 说明几何异常 |

实测对比（同一张图、同一个种子）：

| | 修复前 | 修复后 |
|---|---|---|
| 清屏色占比 | **83.4%** | 41.8% |
| 下半屏非背景 | **17.9%** | 78.3% |
| 结论 | 画面有问题 | 画面正常 |

### 10.10 怎么看实际画面（不用手工开局）

> ⚠️ **这几个开关是给无人值守抓帧用的，不是正常玩法。**
> 正常玩就是 `java -jar dist/gunmu-party.jar`，不要带任何 `-Dgunmu.*`。
> 带 `autoplay` 时你会**控制不了自己那根滚木** —— HUD 顶部会有橙色横幅提示，
> 按 `F2`、或随便按一下方向键 / 拖一下摇杆，控制权立刻还给你（见下）。

```bash
# 仅用于自动抓帧：
java -Dgunmu.autostart=true -Dgunmu.autoplay=true \
     -Dgunmu.shotdir=D:/tmp/shots -Dgunmu.shotcount=8 -Dgunmu.shotinterval=1.5 \
     -jar dist/gunmu-party.jar
python tools/check_shot.py D:/tmp/shots/shot-0.png
```

| 开关 | 作用 |
|---|---|
| `gunmu.autostart` | 跳过大厅直接开赛；结算/选技界面自动推进 |
| `gunmu.autoplay` | 让 `BotBrain` 接管玩家那根滚木（与其它滚木同源，不给它开小灶） |
| `gunmu.shotdir` / `shotcount` / `shotinterval` | 逐帧落 PNG |

**调试开关绝不能把玩家锁死**（D37）。所以 autoplay 有三重出口：

1. HUD 顶部橙色横幅明示"自动演示中"，不会被误当成"游戏坏了"；
2. `F2` 随时开关；
3. **玩家一碰方向键或摇杆就立刻交还控制权** —— 不需要知道有这个开关存在。

进入每关时还会打一行日志说明当前控制方式，方便从日志直接定位
"为什么我控制不了"。

### 10.11 起点屏障自检 `StartBarrierTest`

需求是一句话：「8 秒之前所有人都被困在起点区域内，8 秒后这层屏障消失，被困住的期间可以随便移动」。
这句话里藏着**两条互相拉扯**的约束，缺一条就算做错：

| 约束 | 只做这一条的后果 |
|---|---|
| 出不去 | — |
| **随便动** | 只做第一条很容易写成"开局把所有人钉死原地" |

所以正反都要断言：

| 检查 | 判据 | 实测 |
|---|---|---|
| 里面能自由移动 | 屏障内直线跑 1.5 s 位移 ≥ 3 m | 10.50 m |
| 冲不出去 | 朝外冲 **屏障时长 + 4 s**（12 s），全程 ≤ `半径 − 贴地半径` | 最远 11.45 m = 上限 |
| 真的贴到屏障上了 | 最远距离必须**接近**上限 | 11.45 vs 上限 11.45 |
| 跳不出去 | 冲的过程中每 0.5 s 跳一次，仍不出圈 | 通过 |
| 关掉之后出得去 | 屏障消失后 3 s 必须跑到半径外 | 35.38 m |
| 真实地图裹得住 | 10 张图 × 3 个出生点（含首尾）朝外冲 8 s | 半径 13.26~39.50 m，全部冲不出去 |

最后一条必须在**真实地形**上跑：屏障半径是从出生点推出来的，只在合成平地上测证明不了它裹得住真出生点。

整局层面再加两条累计断言（`ConsoleProbe`）：

- `barrierTicks` ≥ 400（8 秒应为 480 帧/子图）—— 实测 958 帧 / 2 个子图；
- `barrierEscapes == 0` **且** `barrierDeaths == 0` —— 屏障期间既没人出圈、也没人死
  （起点区域里不该有深渊或致命机关）。

> ⚠️ **探针的每关帧预算必须把屏障补回来。** 屏障期间比赛时钟不走，
> 所以探针的"每关 3600 帧"上限会导致实际比赛时间少 8 秒，
> 表现为平均进度从 611 m 掉到 554 m、死亡从 0 涨到 9 —— 看起来像玩法被改坏了，
> 其实只是**测量口径**变了。加上 `START_BARRIER_TIME / FIXED_DT` 之后数据回到 604 m / 618 m。

### 10.12 道具属性折行自检 `TextWrapTest`

玩家报"道具属性的显示范围太小了，应该要显示完整属性"。
根因有两层：旧实现是**一行且不折行**；而中英文混排的折行**不能交给 `GlyphLayout`** ——
它按**空格**断词，中文整句没有空格，会被当成一个超长单词原样溢出屏幕。

所以自己按字符折，并把算法抽成纯函数 `Fonts.wrapText(text, width, advance)`：
只依赖"每个码点多宽"这一个函数，因此**不需要 FreeType 与纹理就能无头验证**。

断言的是三个可证伪的性质：

| 性质 | 判据 |
|---|---|
| **无损** | 折出来的各行首尾相接**一字不差**等于原文（折行不许吃掉任何字符） |
| **不溢出** | 每行像素宽度 ≤ 给定宽度 |
| **真的折了** | 超长中文句必须折成多行，且**除末行外**每行都接近满宽（否则是"每行一个字"，同样是错的） |

外加收尾标点不落行首、空串与零宽度的边界。实测：27 个道具的说明全部无损不溢出；
120 个字折成 3 行（38~41 字/行，上限 41）。

### 10.13 加速自检 `DashTest`

需求「添加加速，shift，冷却 15 秒，加速后移速提升 50%」里三件事都要能被证伪：

| 检查 | 判据 | 实测 |
|---|---|---|
| 真的快了 50% | 平地上直线冲 3 s，加速组 / 对照组峰值速度比 ≈ 1.5（±8%） | 9.00 → 13.50 m/s，比值 **1.500** |
| 冷却真的是 15 秒 | 16 秒内连按（每帧都置位）只能触发 **2** 次 | 通过 |
| 倍率会收回 | 加速结束后 `speedMultiplier()` 回到 1.0 | 通过 |
| 边沿被消费 | 触发那一帧 `input.dash` 即为 false；冷却中按 3 秒无效 | 通过 |

> ⚠️ **测试必须自己驱动计时器。** 真实游戏里 `Roll.tickTimers` 是 `LevelDirector` 每帧调的，
> `PhysicsWorld.step` 自己不管。直接调 `step` 而不调 `tickTimers`，冷却永远不会走完 ——
> 这个自检的第一版就是这么写错的（报"16 秒内只触发 1 次"）。

整局层面再加一条累计断言：`dashCount > 0`（实测一整局 **338** 次）。

### 10.13.1 普通攻击自检 `AttackTest`（D60）

需求「添加攻击技能，5 秒冷却对扇形内的敌人造成 15 点伤害，只在决赛关可用」里
有五件事要能被证伪：

| 检查 | 判据 | 实测 |
|---|---|---|
| 扇形几何 | 正前方命中；背后 / 正侧方 / 超距 / 落差超容差都不命中；偏 30° 命中、偏 40° 不命中 | 通过（纯函数逐条断言） |
| 伤害 = 15 | 命中后目标从 100 → 85 | 通过 |
| 冷却 = 5 秒 | 挥出后 `attackCd = 5.00`；立刻再按无效；走满 300 帧后可再挥 | 通过 |
| 只在第 5 关 | 第 1~4 关 `attacks.enabled == false`，第 4 关按了**不掉血也不吃冷却**；第 5 关为 true | 通过 |
| 输入不悬空 | `input.attack` 被消费；**第 4 关按了同样会被清掉** | 通过 |

> ⚠️ **测试要把开局那 8 秒起点屏障推完再打人。** 屏障期间圈内免伤（D50），
> 而测试的两个滚木摆在出生点附近 —— 不推过去的话，挥击明明命中了
> （`attackCount` 会 +1），但 `Combat.deal` 在"免疫检查"那一步就返回了，血一滴不掉。
> 这是"断言看起来莫名其妙地红"的典型来源：机制没错，是测试站错了时间。

整局层面再加两条：进了决赛关就 `attackCount > 0`（实测 **29** 次），
**没进决赛关则必须为 0**（这条反过来守"关卡限定"没被写成"全局开放"）。

### 10.13.2 类与资源完整性校验 `verify_dex.py`（D62）

真事故：APK 装到手机上**一点就退**——

```
java.lang.NoClassDefFoundError: Failed resolution of:
    Landroidx/core/view/OnApplyWindowInsetsListener;
  at com.badlogic.gdx.backends.android.AndroidApplication.init
```

而编译、打包、`apksigner verify` **全部正常**（原因见 §9.2.1）。

判据不需要真机、不需要反编译、不需要联网：

```
dex 里被引用的每个类，必须满足三者之一：
  ① dex 自己定义了它；② android.jar（框架）提供；③ java 运行时提供（Ljava/ 等前缀）
```

实现是直接解析 dex 的 `type_ids`（所有被引用的类型描述符）与 `class_defs`（所有已定义的类），
再与 `android.jar` 的类列表求差集。实测：

- 对**修好之前**那个 APK：精确报出 5 个缺类，头两个正是
  `Landroidx/core/view/OnApplyWindowInsetsListener;` 与 `Landroidx/core/view/ViewCompat;`；
- 对修好之后的 APK：**0 缺类**，另有 2 条**豁免**且各自写明理由。

两条使用规矩：

1. **豁免必须写理由**（脚本里是 `EXEMPT` 字典）——"什么条件下才会用到它"说不清就不许豁免。
   目前只有两条：`androidx.fragment.*`（只有 `AndroidFragmentApplication` 用，
   我们的启动器继承的是 `AndroidApplication`）与 `com.badlogic.gdx.backends.android.R$*`
   （只在软键盘输入对话框的内部类里用，本作没有文本输入）。
2. **正向断言也要有**：`MUST_DEFINE` 钉住启动路径上的必需类 —— 缺类扫描是"减法"，
   万一哪天引用被删掉、扫描"无话可说"，这条正向断言还能红。

它已经是 `build_apk.py` 的第 7 步：**校验不过 → 构建中止且不产包**（宁可打不出包，也不发一个会闪退的）。

### 10.13.3 类路径资源完整性（同一个校验器，D64/D65）

同一个脚本还查第二类"编译期看不出来、只有真机炸"的问题：**jar 里的非 class 文件**。
真事故见 §9.2.3（`File not found: .../default.vertex.glsl (Classpath)`）。

判据同样是"从 dex 反查"，不需要维护清单：

```
dex 里出现的每个"按路径读文件"的字符串，必须满足三者之一：
  ① APK 根部有这个条目（Gdx.files.classpath）；
  ② assets/ 下有这个条目（Gdx.files.internal）；
  ③ 它在豁免表里且写明了理由
```

"按路径读文件"的判定宁可漏也不要误（误报会让构建无故变红，然后有人开始往豁免表里塞东西，
这条检查就废了）：必须带 `/`、带已知的资源扩展名（`.glsl .fnt .png .ttf .atlas .dat .ser
.properties .txt .json .ogg …`）、不含空白/引号/通配符/冒号；**以 `/` 开头的跳过** ——
那是设备上的绝对路径（`Fonts.CANDIDATES` 里那串系统字体走 `Gdx.files.absolute`），
Windows 盘符路径也靠冒号一起被排除。

实测（a0.0.2）：

| 阶段 | 结果 |
|---|---|
| 修好之前的 APK | 报出 **14** 条，其中 8 条正是缺的 libGDX 资源（6 个 `.glsl` + `lsans-15.fnt/.png`），另 8 条是系统字体（断言规则补上"绝对路径跳过"后消失） |
| 修好之后 | **0 条缺失**；dex 里全部"资源型字符串"共 11 条，全部在包内 |
| 覆盖率抽查 | 把 dex 里所有"含 `/` + 扩展名"的字符串按扩展名分组统计，只有 `.prof`/`.profm`（基线配置文件）与 `text/vnd.android.intent`（MIME 类型）三类不在清单里，且都不是被读的文件 —— 说明这张网没漏 |

另有一条**不在**这个脚本里、但在同一步构建里跑的断言：`build_apk.py` 的
`check_native_packaging()`（§9.2.4），守 `.so` 的压缩方式与 `extractNativeLibs` 自洽。

**怎么证明这张网是活的**（不需要真机、不需要等下一次踩坑）：把成品包里的类路径资源抽掉，
再跑一次校验器，它必须红：

```bash
python - <<'EOF'
import zipfile
src, dst = "build/apk/gunmu-party-a0.0.2.apk", "build/apk/selfcheck-no-res.apk"
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for it in zi.infolist():
        if it.filename.endswith((".glsl", ".fnt")) or it.filename.endswith("lsans-15.png"):
            continue
        zo.writestr(it, zi.read(it.filename))
EOF
python tools/verify_dex.py build/apk/selfcheck-no-res.apk ; echo "退出码 $?"
```

实测：退出码 1，列出 8 条，头两条是 `default.fragment.glsl` / `default.vertex.glsl` ——
与真机上报的那条 `File not found` 完全对上。

### 10.14 道具池自检 `ItemPoolTest`
竞速图不许刷出"战斗专用"道具（见 D49）。断言写成**穷举**而不是抽样：

| 检查 | 判据 |
|---|---|
| 竞速图池子精确 | 每个刷新点刷上万次，出现过的道具集合 **== 全集 − 战斗专用**（既不许多也不许少） |
| 生存/竞技图正常 | 6 个战斗专用道具全部会出现 —— 否则可能是"全局关闭"那种错 |
| 保底逻辑不被卡死 | 战斗专用里**没有普通品质**，否则竞速图的"连续 3 次普通 → 强制稀有以上"无解 |
| 零权重抽不到 | 浮点边界：`pick` 恰好归零时不许抽中权重为 0 的道具 |

实测：4 张竞速图各出现 **17** 种、与期望集合完全一致；生存/竞技图 6 种全部出现。

### 10.15 可空字段的护栏：`route` 只有竞速图才有

**真崩溃**（进程当场死掉，不是画面不对）：

```
[Terrain] 地图 涨水盆地 · 高度场 119x119 · 6 块，单块最多 22920 顶点
[GunmuParty] 进入第 3 关 · 涨水盆地 · 控制方式：玩家
Exception in thread "main" java.lang.NullPointerException:
    Cannot read field "totalLength" because "<local3>.route" is null
  at LevelScreen.logDiagnostics(LevelScreen.java:181)
```

`MapDef.route` 是**折线赛道路径**，只有竞速图有；生存图与竞技图是找路/环形场地，
`route == null`。诊断行里写了一句裸的 `route.totalLength`，一进生存图就炸。

**麻烦的地方在于它在 `LevelScreen` 里 —— 要开窗口才跑得到，单元测试够不着。**
所以做了三件事：

| 措施 | 内容 |
|---|---|
| ① 唯一判空口径 | `MapDef.hasRoute()` / `routeLength()`；`MapNav` 收拢"按模式有条件成立"的查询 |
| ② 纯函数化 | 连**那行诊断文本**都搬进 `MapNav.diagLine(...)`，于是能被无头自检对 10 张图逐一跑 |
| ③ 源码级检查 | `tools/run_smoke.py` 在编译前扫描：任何 `.route.` 解引用必须同行带守卫（`hasRoute(` / `routeLength(` / 空判断）或挂 `// route-ok: 原因` |

措施 ③ 立刻抓出 5 处（3 处真该改，1 处是注释里的字样误报）——
**这就是它存在的意义：把"记得判空"这件靠自觉的事变成会让构建失败的事。**

审计（`tools/MapNavTest`）对 10 张图 × 三种玩家状态（正常/出局/未出生）断言：

| 图 | 相机 | 进度文案 |
|---|---|---|
| 竞速（4 张） | 跟赛道切线 | `进度 137/620` |
| 生存（3 张） | 默认视角 | `生存 13s` |
| 竞技（3 张） | 默认视角 | `击杀 0` |

顺带纠正一处误导：非竞速图以前也会被说成"进度 x/y"，而那个数字对它们毫无意义
（生存图填的是存活秒数×10）。现在按模式给各自的量。

### 10.16 路面不许相交 `RouteOverlapTest`

地形是「按离最近路段逐格定高」生成的，而**一个格子只能存一个高度**。于是：

| 情形 | 后果 |
|---|---|
| 两条路水平相交、**高度接近** | 无害。地形连成一片，本来就是一个平台（起点广场、终点缓冲台就是这么加宽的） |
| 两条路水平相交、**高度差大** | **有害**。高的那条盖住低的，玩家走在低的那条上会被 `resolveGround` 抬到高的那条 —— **瞬移** |

判据因此是两条一起看：**水平圆面相交（`d < hw_i + hw_j`）且高度差 > 12 米**。
（滚木半径 0.55、地形噪声 ±0.45，十几米的高度差绝不可能靠滚动跨越。）

实测（修复前后）：

| 地图 | 修复前 | 修复后 |
|---|---|---|
| 之字断崖 | 8 处相交，最严重的是**终点层与中段水平距 0.0m、高度差 114m** | **0 处** |
| 青丘缓坡 / 冰隙峡谷 / 崩落长阶 | 各 1~3 处 | **0 处**（最小余量 8.5m） |

> 调试这条审计时用 `tools/quick_route.py`：它只重编 core 再跑一个测试类，
> 几秒钟出结果；整包冒烟要跑完整局淘汰赛，约十分钟。

### 10.17 进度判定：段滞后 + 弧长外推

`TrackProgress.arcAt` 有两处非平凡的处理，都是为了"弧长必须连续"：

**① 段滞后（hysteresis）。** 弯道处两条边的距离几乎相等，纯全局最近点搜索会在两者之间
来回切，弧长跟着**一帧跳十几米** —— 而滚木一帧最多走 `22/60 ≈ 0.37m`。
所以 `arcAt` 接一个 `hint`，**优先沿用上一帧命中的段及其左右邻居**。

**② 弧长按未钳制的 `t` 外推。** 投影点要用钳制后的 `t`（算横向距离），
但**弧长必须用原始 `t`**：

```java
float t      = 投影比例;               // 可以 < 0 或 > 1，只要在 ±0.15 内就接受
float tProj  = clamp(t, 0, 1);
投影点 = a + (b − a) · tProj;           // 算横向距离
弧长   = route.arcAt(seg, t);           // ← 外推，不要用 tProj
```

曾经拿 `tProj` 算弧长，于是玩家走到段末之外时弧长**卡在段末端不动**，
一直卡到越界超过余量才切下一段、一次性把 `0.25 × 段长 ≈ 9 米` 补回来 ——
表现就是"单帧进度跳 10 米"。两处修完：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 之字断崖 冻结帧 | **964** | **9** |
| 之字断崖 单帧跳变 | **382m** | **22m** |
| 4 张竞速图能否跑完 | 之字断崖跑不完 | **全部 55~65 秒跑完**（时限 300s） |

### 10.18 赛跑审计 `RaceRunTest`

`RouteAudit` 是**静态**的（逐格问"这里读到的弧长对不对"），证明不了"真的跑一遍能不能到终点"。
`RaceRunTest` 跑两种驾驶员：

| 驾驶员 | 测什么 |
|---|---|
| **理想驾驶员**（直接朝赛程前方 20 米处开） | **路 + 判定**本身通不通。它跑不完 ⇒ 问题一定在几何或判定上 |
| **BotBrain** | AI 的驾驶水平。它跑不完 ⇒ 调 AI，不是改路 |

断言两条：**时限内能到终点**、**单帧弧长增量不超过 30 米**（存档点间距 124 米，
跳 30 米不足以跨过一个存档点；而真正的"跨段偷鸡"是 380 米量级的，一定抓得住）。

> 写探针时踩过的坑：理想驾驶员原来用 `progress` 算目标点，而 `progress` 只增不减，
> 判定一旦短暂滞后它就定住 → 目标点落到身后 → 车在原地打转。**假探针会伪造出"地图跑不完"。**
> 目标点改成"上一帧读到的弧长"之后，同一张图 55 秒跑完。

### 10.19 操控自检 `MoveControlTest`

玩家报过「在生存关卡我无法操控自己」。这类问题的可疑面很宽，所以这里走**和游戏完全同一条链路**：
屏幕输入 → `InputMapping.screenToWorld`（相机 yaw 取 `MapNav.cameraYaw`）→ `Roll.input.move`
→ `PhysicsWorld.step` → 位置变化。三种模式各挑一张图，三条断言：

| 断言 | 实测 |
|---|---|
| 给 6 秒输入 → 真的走得动（≥12m） | 51~54m |
| 移动方向与"屏幕上方"一致（误差 < 25°） | 0.1°~9.0° |
| 松开输入 → 不再持续加速 | 见下 |

> ⚠️ 第三条**只提示不判失败**：生存图是碗形场地，滚木松开输入后本来就还在坡上往下滚
> （实测 1 秒后仍有 8.3 m/s，而摩擦是正常生效的、材质是 GRASS 摩擦 1.0）。
> 一开始把它当成"输入没被消费"的证据，纯属误伤 ——
> **"在坡上继续滚"是物理正确的，"输入有效"已经由前两条覆盖。**

### 10.20 相机契约（D33）

```
看点   = 玩家 + 赛道切线 × LOOKAHEAD + 上方向 × LOOK_LIFT
相机位置 = 看点 − 赛道切线 × (DISTANCE·cos PITCH) + 上方向 × (DISTANCE·sin PITCH)
相机朝向 = normalize(看点 − 相机位置)
```

三个不变量，改参数时必须保持：

1. **看点永远在屏幕正中**（朝向由位置反算，不是另设一个方向）；
2. **俯角与距离解耦**，不允许再出现"再叠加一个高度"这种写法；
3. **屏幕方向 → 世界方向必须过一次相机 yaw**：
   屏幕上方 = `(sin yaw, cos yaw)`，屏幕右方 = `(cos yaw, −sin yaw)`。
   直接拿键盘输入当世界方向用，就会出现"按 W 往后退"。

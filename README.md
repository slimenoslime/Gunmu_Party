# 滚木派对 · Gunmu Party

30 根滚木从山坡滚下去。摇杆控向、能跳、下坡越滚越快；速度够快时撞到别人会把他撞碎。
五关淘汰赛：**30 → 25 → 20 → 15 → 10 → 1**。随机道具与决战技全部取材自 2026 年的网络热梗。

- 引擎：**LibGDX 1.13.5**
- 平台：**Windows / Linux / macOS（LWJGL3）+ Android**（共用同一份 `core`）
- 语言 / 构建：Java 17 · Gradle 8.13（wrapper）· Android Gradle Plugin 8.11.1
- 美术：**全部程序化生成**，不依赖任何外部图片文件（详见 `docs/05-TECH-ARCHITECTURE.md §5`）
- 当前版本：**`a0.0.2`（alpha 内测版）** —— 版本号唯一来源是 `GameConfig.VERSION`，见 [`CHANGELOG.md`](CHANGELOG.md)

## 版本命名规范

| 前缀 | 含义 |
|---|---|
| **`a`** | alpha / **内测版** —— 功能还在加，可能崩、数值随时会改 |
| **`b`** | beta / **测试版** —— 功能已定型，主要在修 bug、调平衡 |
| **`v`** | release / **正式版** |

改版本号**只改 `core/src/main/java/top/gunmu/party/GameConfig.java` 一处**（`VERSION` + `VERSION_CODE`），
然后重打产物：安卓清单的 `versionName`、桌面 jar 的文件名、游戏大厅里显示的版本号都跟着它走
（统一读取入口 `tools/version.py`，读不到直接失败、不猜）。

⚠️ **同一个版本号下不允许出现两份不同的包。** 每次修复/改动都要升版本号、`VERSION_CODE` 只增不减 ——
a0.0.1 期间被打过三次不同的包（首个构建 → 修闪退 → 修着色器），内测的人报 bug 时说不清装的是哪一个，
而版本号是排查 bug 的唯一把手。

## 产物（已验证）

| 产物 | 大小 | 说明 |
|---|---|---|
| `dist/gunmu-party-a0.0.2.jar` | 9.9 MB | **自包含**桌面 fat jar，含 22 个原生库、字体与 6 个 `.glsl`，拷到任意目录 `java -jar` 即可玩 |
| `dist/gunmu-party.jar` | 9.9 MB | 上面那份的**别名**（内容完全相同），"当前最新"的固定路径 |
| `build/apk/gunmu-party-a0.0.2.apk` | 5.0 MB | **固定签名**（见下），含 4 个 ABI 的原生库 + androidx 运行期依赖 + 8 个类路径资源，可直接 `adb install` |
| `build/apk/gunmu-party-debug.apk` | 5.0 MB | 上面那份的**别名**（内容完全相同） |

> 每次构建都产出两份：带版本号的那份用于归档/分发，不带版本号的那份保证
> README、脚本、习惯里的固定路径永远指向"当前最新"。
>
> APK 里为什么带着 androidx / kotlin / coroutines：libGDX 1.13.5 的安卓后端
> **运行期依赖 `androidx.core`**（`AndroidApplication.init()` 处理 edge-to-edge 用），
> 而 androidx.core 的 Kotlin 实现又依赖 coroutines。走 Gradle 时 AGP 会自动拉全，
> 我们这条直连工具链的路由 `tools/fetch_libs.py` 按 `.module` 元数据拉全，
> 并由 `tools/verify_dex.py` 在打包阶段断言"没有缺类"。
>
> APK 根部为什么有 `com/badlogic/…/*.glsl`：**jar 里的非 class 文件也是运行期资源** ——
> libGDX 的 `DefaultShader` 用 `Gdx.files.classpath(...)` 读着色器源码，`BitmapFont` 读
> `lsans-15.fnt/.png`。Gradle 打 APK 会把这些铺到根部（`mergeJavaResource`），
> 我们由 `build_apk.py` 的 `classpath_resources()` 铺，并由同一个校验脚本断言"没有缺资源"。

## 签名（固定）

从 **a0.0.1** 起，所有 APK 都用**项目自己的固定签名**签，不再用 Android 工具链的 debug key
—— 那个 key 是每台机器各自生成的，别人拿到源码打出来的包和你打出来的包**互相装不上**
（同包名、不同签名，覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

| 项 | 值 |
|---|---|
| 签名文件 | `keystore/gunmu-party.jks`（**在版本控制里，别删别改名**） |
| 别名 / 密码 | `gunmu-party` / `gunmu-party` |
| 证书 SHA-256 | `93:78:EA:E8:89:74:39:64:24:FB:F2:26:F8:6C:FF:63:F2:1E:01:4B:10:50:38:D9:CB:C3:50:54:C4:F1:C7:F5` |
| 有效期 | 2026-09-21 → 2054-02-06（10000 天） |

完整参数、核对命令与重建方法见 [`keystore/README.txt`](keystore/README.txt)。

> ⚠️ **这把钥匙丢了，已安装的用户就永远无法升级** —— 安卓不允许同包名换签名覆盖安装，
> 只能卸载重装（存档全丢）。请把 `keystore/` 整个目录备份到至少两个地方。
>
> 之前用 Android debug key 签的包（a0.0.1 之前的构建）在设备上**要先卸载**才能装新的：
> `adb uninstall top.gunmu.party`

## 打包阶段的三条硬校验

apk 不是"能装"就算完 —— 有三条断言在**打包时**就会红掉：

| 校验 | 守的是什么 |
|---|---|
| `tools/verify_dex.py`（类） | **dex 类完整性**：dex 里被引用的每个类，必须"dex 内有定义 / 框架提供 / java 运行时提供"三者之一。守的是 `NoClassDefFoundError`（真机一点就闪退），这类问题编译、打包、签名全都看不出来 |
| `tools/verify_dex.py`（资源） | **类路径资源完整性**：dex 里出现的每个"按路径读文件"的字符串，必须在 APK 根部或 `assets/` 下存在。守的是 `File not found: …default.vertex.glsl (Classpath)`（一进比赛就崩），桌面端同样看不出来 |
| `tools/fetch_libs.py --verify` | **依赖关键类**：gdx 的 `SharedLibraryLoader`、androidx 的 `ViewCompat` / `OnApplyWindowInsetsListener` 必须在 jar 里 |

外加一条原生库自洽断言（`build_apk.py` 的 `check_native_packaging()`）：`.so` 是压缩进包的，
所以清单必须写 `android:extractNativeLibs="true"`，否则 `System.loadLibrary` 会失败。

以上都在 `python tools/build_apk.py` 的流程里自动跑；任何一条失败，构建直接中止且**不产出包**。

---

## 1. 目录结构

```
gunmu party/
├── docs/            设计规范（权威文档，务必先读）
├── core/            全部游戏逻辑与渲染（平台无关，桌面与安卓共用）
├── lwjgl3/          桌面入口
├── android/         安卓入口（只在检测到 Android SDK 时才纳入构建）
├── assets/          随包资源：fonts/game.ttf + fonts/charset.txt（见 §5）
├── tools/           不依赖 Gradle 的构建脚本（见 §3.2）
├── libs/            离线编译用的依赖 jar
├── build.gradle     根构建脚本
├── settings.gradle  模块声明 + Android SDK 探测
└── gradlew(.bat)    Gradle wrapper
```

---

## 2. 环境要求

| 项 | 版本 | 什么时候需要 |
|---|---|---|
| JDK | 17 或更高（实测 JDK 21） | 总是 |
| Gradle | 用自带的 `gradlew`，无需另装（wrapper 锁定 8.13） | 走 §3.1 / §4.1 时 |
| Android SDK | platform 36 + build-tools 36.0.0 | 构建 APK 时 |
| Python | 3.8+ | 走 §3.2 / §4.2 的脚本时 |
| `fonttools` | 任意版本 | 只在重新生成字体时需要（`pip install fonttools`） |

Android SDK 的定位顺序（`settings.gradle` 与 `tools/build_apk.py` 各实现一份）：

1. 环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`
2. 项目根目录的 `local.properties` 里的 `sdk.dir`
3. `~/AppData/Local/Android/Sdk`、`C:/Android/Sdk` 等常见位置

> **没有 Android SDK 也能正常构建桌面版**——`:android` 会被自动跳过，并打印一行提示。

---

## 3. Windows 桌面版

### 3.1 用 Gradle（标准路径）

```bash
gradlew.bat runGame    # 直接跑起来
gradlew.bat dist       # 打成独立的 jar
```

### 3.2 不用 Gradle（`tools/` 脚本）

受限环境里 Gradle 的 `native-platform` 可能因创建 `.lock` 失败而连启动都进不去
（`Could not initialize native services`）。它拦的是调度层，不是编译本身，
所以工程里另有一条直连工具链的路，产出的 jar **完全等价**：

```bash
python tools/fetch_libs.py     # 抓全依赖（含容易漏的 gdx-jnigen-loader）
python tools/make_font.py      # 生成 assets/fonts/game.ttf + charset.txt（改了中文文案就要重跑！）
python tools/build_jar.py      # → dist/gunmu-party-a0.0.2.jar（+ 别名 dist/gunmu-party.jar）

java -jar dist/gunmu-party.jar
```

> jar 是**自包含**的：原生库和字体都在里面，拷到任何目录、任何机器都能跑。
> 启动日志里出现 `已加载中文字体: fonts/game.ttf` 就说明字体链路正常。

> 用 IDE 直接跑时，主类是 `top.gunmu.party.lwjgl3.Lwjgl3Launcher`。

> **不带任何 `-D` 参数就直接玩。** 下面这些是排障用的，平时不要加。

### 3.3 固定随机种子（仅用于复现问题）

```bash
java -Dgunmu.seed=20260921 -jar dist/gunmu-party.jar
```

种子决定每关抽到哪张地图与全部随机数。

⚠️ **固定种子 = 每一局完全一模一样**（同样的地图顺序、同样的道具、同样的 AI 行为）。
这是"复现某个 bug"用的，**不是正常玩法**。正常玩别带这个参数，否则你会觉得
"每关地图都是固定的"。

---

## 4. Android

### 4.1 用 Gradle

```bash
# 1) 告诉 Gradle 你的 SDK 在哪（一次即可，这个文件不要提交）
echo sdk.dir=C:/path/to/android-sdk > local.properties

# 2) 构建 + 安装
gradlew.bat apk
adb install -r android/build/outputs/apk/debug/android-debug.apk
```

### 4.2 不用 Gradle

```bash
python tools/fetch_natives.py  # 解 4 个 ABI 的 .so → android/libs/
python tools/build_apk.py      # → build/apk/gunmu-party-a0.0.2.apk（+ 别名 gunmu-party-debug.apk）

adb install -r build/apk/gunmu-party-debug.apk
```

`build_apk.py` 就是 `aapt2 compile → link → javac → d8 → 打包 → zipalign → apksigner`
七步串起来，可复现、不联网、无后台状态。指定 `--abi arm64-v8a` 可以只打一个 ABI 加快速度。

Android 端的差异（全部在 `core` 里由 `GunmuPartyGame.mobile` 开关控制，不是两套代码）：

| 项 | 桌面 | Android |
|---|---|---|
| 触屏摇杆 / 按钮 | 隐藏 | **默认显示** |
| UI 缩放 | 1.0× | 按屏幕高度放大（720p ≈ 1.25×，1080p ≈ 1.33×） |
| 地形渲染网格 | 每格都画 | 隔一格画（顶点数 ≈ 1/4，**物理精度不变**） |
| 返回键 | — | 大厅里连按两次退出；比赛中被吞掉，避免误触丢局 |
| 全屏 | 窗口 | 沉浸式全屏 + 刘海屏铺满 + 保持常亮 |

---

## 5. 美术资源与字体

`assets/` 里**没有美术文件**。所有可见物都是代码画出来的：地形网格、滚木、机关、道具、纹理全部由 `render/MeshFactory` 与 `render/TextureFactory` 程序化生成（固定种子，跨平台一致）。

这不是"占位美术"，而是刻意的工程决策：不引入二进制文件、不污染仓库、结果确定性。
接入正式美术时**不需要改架构**——命名约定见 `docs/05-TECH-ARCHITECTURE.md §5.3`，
把同名 PNG 丢进 `assets/textures/` 即可覆盖。

唯一随包的二进制是**裁剪后的中文字体**：

| 文件 | 大小 | 说明 |
|---|---|---|
| `assets/fonts/game.ttf` | 260 KB | Noto Sans SC（SIL OFL）裁出的 1262 个字形 |
| `assets/fonts/charset.txt` | 3.6 KB | 与字体同源的字形清单，运行时按它烘焙 |

`tools/make_font.py` 会扫全部 `.java` 源码取出用到的字符再裁剪——**加了新文案就重跑一次**，
否则新字会渲染成空白。详见 `docs/05-TECH-ARCHITECTURE.md §5.3`。

---

## 6. 无窗口冒烟测试

不需要 OpenGL、不需要显示器，纯命令行跑：

```bash
gradlew.bat smoke        # 或见下方"不用 Gradle"
```

它做三件事（第三件是编译前的源码检查）：

0. **`route` 解引用检查**：只有竞速图有 `route`（折线路径），生存/竞技图没有。
   任何 `.route.` 解引用必须同行带守卫（`hasRoute()` / `routeLength()` / 空判断），
   或在行尾挂 `// route-ok: 原因`。**曾经有一处漏判让进程一进生存图就直接崩**，
   所以这条检查是构建门禁，不通过就不编译。


1. **构建并校验全部 10 张地图**，逐条检查 `docs/02-LEVELS-AND-MAPS.md §8` 的硬性约束
   （存档点数、刷新点数、出生点数、路径长度、下坡占比、存档点周边安全区、
   道具点落在可行走面、出生点间距、机关伤害预算）。任何一项不过都直接抛异常。
2. **跑完整局 5 关淘汰赛**（AI 驱动），统计死亡、拾取、道具与决战技使用、名次推进，
   并断言这些链路真的发生了。

不用 Gradle 时（推荐，一步到位）：

```bash
python tools/run_smoke.py 20260921
```

`ConsoleProbe` 会打印每关的分布诊断（场上人数、进度分布、死因累计、结束原因），调平衡时很有用。
**实测数据直接驱动了 D26（走廊放宽）与 D27（AI 走廊保持）两次调参。**

---

## 7. 操作

| 动作 | 键盘 | 手柄 | 触屏 |
|---|---|---|---|
| 移动 | `WASD` / 方向键 | 左摇杆 | 左半屏拖动（轮盘） |
| 跳跃 | `空格` | `A` | 右下「跳」 |
| 使用道具 | `E` | `X` | 右下「道具」 |
| 施放决战技 | `Q` | `Y` | 右下「决战」 |
| **加速**（+50% 移速 / 3 s / 冷却 15 s） | `Shift` | `B` | 右下「加速」 |
| **普通攻击**（70° 扇形 / 15 伤 / 冷却 5 s，**只在第 5 关**） | `J` | — | 右下「攻击」（第 5 关才出现） |

---

## 8. 设计文档

| 文件 | 内容 |
|---|---|
| `docs/00-OVERVIEW.md` | **总纲（权威）**：关卡结构、晋级表、核心规则概要 |
| `docs/01-CORE-RULES.md` | 物理、移动、跳跃、加速、**普通攻击**、碰撞、伤害、存档点 |
| `docs/02-LEVELS-AND-MAPS.md` | 5 关流程 + 3 种模式 + 10 张地图 + 机关表 |
| `docs/03-ITEMS.md` | 27 个 2026 热梗道具（含 4 个竞速向速度道具）+ 固定刷新点机制 |
| `docs/04-ULTIMATE-SKILLS.md` | 8 个决战技的逐条精确数值（每个都能造成伤害） |
| `docs/05-TECH-ARCHITECTURE.md` | 模块划分、确定性物理、tick、渲染、输入、字体、构建（§9） |
| `docs/06-DECISIONS.md` | 决策编号表 D1..D61 + 未决项索引 |
| `CHANGELOG.md` | 版本命名规范（a/b/v）与各版本变更记录 —— 当前 **a0.0.2 内测版** |

**改设计先改文档。** 文档与代码冲突时以 `docs/00-OVERVIEW.md` 为准。

---

## 9. 工具脚本一览

| 脚本 | 作用 |
|---|---|
| `tools/fetch_libs.py` | 按构件元数据抓全依赖 → `libs/` + `libs-mobile/`；`--verify` 只做关键类校验 |
| `tools/fetch_natives.py` | 从 natives 构件解出 `.so` → `android/libs/<abi>/` |
| `tools/make_font.py` | 扫源码 → 裁剪中文字体 + 字形清单 |
| `tools/build_jar.py` | 打自包含桌面 fat jar |
| `tools/build_apk.py` | 直连 SDK 工具链打 APK |
| `tools/run_smoke.py` | 无窗口冒烟测试 |

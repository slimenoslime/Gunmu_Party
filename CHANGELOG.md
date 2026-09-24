# 更新日志（CHANGELOG）

## 版本命名规范（用户定的）

| 前缀 | 含义 | 什么时候用 |
|---|---|---|
| **`a`** | **alpha / 内测版** | 功能还在加，可能崩、数值随时会改 |
| **`b`** | **beta / 测试版** | 功能已定型，主要在修 bug、调平衡 |
| **`v`** | **release / 正式版** | 对外发布 |

**版本号的唯一来源是 `core/src/main/java/top/gunmu/party/GameConfig.java` 的 `VERSION` / `VERSION_CODE`。**

- 安卓清单的 `versionName`（`tools/build_apk.py` 读）、桌面 jar 的文件名（`tools/build_jar.py` 读）、
  以及**游戏大厅里显示的版本号**都取这一份 —— 本工程不走 Gradle 打包（D31），没有"一处配置两边生效"，
  不这么做迟早会漂移成"APK 里写着 1.0.0、游戏里写着 a0.0.1"。
- 统一读取入口：`tools/version.py`（读不到直接抛异常，不猜）。它还会核对 `build.gradle` /
  `android/build.gradle` 里的版本有没有漂移，不一致就告警。
- **改版本号只改 `GameConfig.java` 一处**，然后重打产物。

产物命名（每次都产出两份，内容完全相同）：

```
dist/gunmu-party-<版本>.jar         ← 归档用（例如 gunmu-party-a0.0.1.jar）
dist/gunmu-party.jar                ← "当前最新"别名，文档与命令都按它引用
build/apk/gunmu-party-<版本>.apk   ← 归档用（例如 gunmu-party-a0.0.1.apk）
build/apk/gunmu-party-debug.apk     ← "当前最新"别名
```

---

## a0.0.2 · 内测版（2026-09-22）· 修安卓项「一进比赛就崩」

### 修复：安卓版一进比赛就报 `File not found: .../default.vertex.glsl (Classpath)`

```
com.badlogic.gdx.utils.GdxRuntimeException: File not found:
    com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl (Classpath)
  at com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.getDefaultVertexShader
  at com.badlogic.gdx.graphics.g3d.ModelBatch.render
  at top.gunmu.party.render.SceneRenderer.render
```

真因：**jar 里的非 class 文件也是运行期资源**，而我们的打包只编了 `.class`。
libGDX 的 `DefaultShader` 用 `Gdx.files.classpath("com/badlogic/gdx/graphics/g3d/shaders/
default.vertex.glsl")` 读着色器源码（`lsans-15.fnt/.png` 是 `new BitmapFont()` 的默认字体，
`depth.*.glsl` 是阴影、`particles.*.glsl` 是粒子）。Gradle 打 APK 时会把 jar 资源铺到 APK 根部
（`mergeJavaResource`），安卓的 `ClassLoader` 就能按原路径取到；直连工具链这条路没有这一步。
**桌面端看不出来** —— 桌面 fat jar 是整包合并的，所以只有安卓炸，且炸在进比赛那一帧。

改了两处：

1. **`tools/build_apk.py` 新增 `classpath_resources()`**：把各 jar 里的非 class 文件铺到 APK 根部。
   口径是**黑名单**（除"编译期元数据"外一律铺），不是白名单 —— 白名单漏一个新 jar 的新资源，
   就是又一次"只有真机才炸"。实测 26 个 jar 里真正要铺的只有 **8 个文件 / 49.3 KB**
   （6 个 `.glsl` + `lsans-15.fnt` + `lsans-15.png`）。
2. **`tools/verify_dex.py` 增加资源完整性检查**（与类检查同一个脚本、同一步构建）：
   从 dex 的字符串表里反查"按路径读文件"的路径，断言它在 APK 根部或 `assets/` 下存在，
   **不过就中止构建、不产包**。对修复前的包它报出 14 条（8 条正是缺的 libGDX 资源）。

### 顺手排查的同类隐患（都没爆，但已上闸门）

| 项 | 结论 |
|---|---|
| **`.so` 的压缩方式** | 现在是**压缩**进包的，只有 `android:extractNativeLibs="true"` 时安装阶段才会解出来。清单里原本没写这句（靠"缺省即 true"）。已**显式**写上，并新增 `check_native_packaging()`：压缩 + 没写 true → **中止构建**。反向（改成 AGP 的不压缩 + 页对齐）也会提示 |
| **代码里按路径读的文件** | 把 dex 里所有"含 `/` + 扩展名"的字符串按扩展名分组统计过一遍：只有 `.prof`/`.profm`（基线配置文件）与 `text/vnd.android.intent`（MIME 类型）不在检查清单里，且都不是被读的文件 —— 说明这张网没漏 |
| **assets** | `fonts/game.ttf`、`fonts/charset.txt` 都在包内（新检查会一并守） |
| **系统字体** | `Fonts.CANDIDATES` 里那串 `/system/fonts/...` 走 `Gdx.files.absolute`（设备上的真实路径，不是包内资源），检查规则里按"绝对路径跳过"处理 |
| **GL 上下文丢失** | 反编译确认 libGDX 的 `AndroidGraphics.preserveEGLContextOnPause()` 会调 `setPreserveEGLContextOnPause(true)`，切后台再回来不会丢上下文（驱动强行丢的情况仍无法覆盖，见待观察项） |
| **权限 / 截图落盘** | 不申请任何权限；`Screenshot` 靠系统属性开启，安卓上取不到属性即关闭 |

### 版本号策略调整（重要）

a0.0.1 期间**同一个版本号被打过三次不同的包**（首个可玩构建 → 修闪退 → 修着色器），
内测的人报 bug 时说不清自己装的是哪一个。因此：

- 本版起 **每次修复/改动都升版本号**（`GameConfig.VERSION` 一处），`VERSION_CODE` 只增不减；
- 固定签名不变，所以**直接覆盖安装即可**，不用卸载。

### 两端产物

| 产物 | 大小 | 说明 |
|---|---|---|
| `build/apk/gunmu-party-a0.0.2.apk` | ≈ 5.0 MB | 四个 ABI + androidx 运行期依赖 + 8 个类路径资源，固定签名 |
| `dist/gunmu-party-a0.0.2.jar` | ≈ 9.9 MB | 自包含桌面 fat jar（含 22 个原生库与 6 个 `.glsl`） |

---

## a0.0.1 · 内测版（2026-09-21）· 首个可玩构建

### 玩法总览

30 根滚木从山坡滚下去，五关淘汰 **30 → 25 → 20 → 15 → 10 → 1**。三种模式：

- **竞速**：沿赛道滚到终点，按名次锁名额（第 1/2 关各一张竞速图）；
- **生存**：留在收缩场地里，死亡满阈值或只剩一人即结束；
- **竞技**：互相撞碎，L4 无限复活、**L5（决战）永久淘汰**。

**第 3 关 = 竞速 / 生存二选一，第 4 关 = 竞速 / 生存 / 竞技三选一**，整关只跑抽到的那一张图。

### 操作

| 动作 | 键盘 | 触屏 |
|---|---|---|
| 移动 | `WASD` / 方向键 | 左半屏拖动（轮盘） |
| 跳跃 | `空格` | 右下「跳」 |
| 使用道具 | `E` | 右下「道具」 |
| 施放决战技（第 4 关起） | `Q` | 右下「决战」 |
| 加速（+50% 移速 / 3 s / 冷却 15 s） | `Shift` | 右下「加速」 |
| **普通攻击（第 5 关起）** | `J` | 右下「攻击」 |

开局 8 秒**起点屏障**：所有人被关在起点区域内，可以自由移动但出不去（圈内免伤），
比赛时钟不在这 8 秒里推进。

### 本版内容

- **27 个道具**（全部取材自 2026 热梗）+ **8 个决战技** + **10 张地图**（竞速 4 / 生存 3 / 竞技 3）。
- 道具刷新点位置固定、刷出物随机；同一个点连续 3 次普通则保底稀有以上。
  **竞速图不刷战斗专用道具**（纯防御 / 纯输出 / 反隐 6 个），可刷池 21 个。
- **每个决战技都能造成伤害**：原本 8 个里只有 2 个能打人，现在其余 6 个各补了 8~12 点的
  附加伤害（刀盾/扭腰/春秋肠的施放震击、皓宸的顺手一击、忘情牛肉面的破隐一击、蛛网的命中附带）。
- **4 个竞速向速度道具**：「遥遥领先」（下坡上限 22→28）、「早八」（起步冲量 + 加速度 ×1.5）、
  「闪现」（向前瞬移 ≤12 m，落点必须在路面上）、「一路绿灯」（身前铺一条加速带）。
  四张竞速图各偏置一个 ×2。
- **决赛关普通攻击**：70° 扇形 / 5 m / **15 伤** / 冷却 **5 秒**，`J` 或触屏「攻击」。
  只在第 5 关开放 —— 那是唯一"所有人挤在同一个场地里互撞"的关卡。
- 加速（`Shift`，移速 ×1.5 / 3 s / 冷却 15 s）。
- 相机绕看点环绕 + 手动转视角（桌面鼠标右键拖动 / 触屏右半屏拖动）；竞速图会自动回中。
- 确定性固定步长物理（1/60），桌面与安卓结果一致，为将来联机留路。

### 两端产物

| 产物 | 大小 | 说明 |
|---|---|---|
| `dist/gunmu-party-a0.0.1.jar` | ≈ 9.9 MB | 自包含桌面 fat jar，含 22 个原生库与中文字体 |
| `build/apk/gunmu-party-a0.0.1.apk` | ≈ 5.0 MB | 含 armeabi-v7a / arm64-v8a / x86 / x86_64 四个 ABI 与 androidx 运行期依赖 |

### 从本版开始：固定签名

APK 一律用**项目自己的固定签名**签（`keystore/gunmu-party.jks`，别名 `gunmu-party`，
证书 SHA-256 `93:78:EA:E8:…:C7:F5`，有效期至 2054-02-06），不再用 Android 工具链的 debug key。

- 为什么必须固定：debug key 是每台机器各自生成的，别人拿到源码打出来的包和你的**互相装不上**
  （同包名不同签名）；而且它原来放在 `build/` 下（构建产物目录、不入库），清理一次就没了。
- `keystore/` 目录**必须入库并另行备份** —— 这把钥匙丢了，已安装的用户就永远无法升级
  （安卓不允许同包名换签名覆盖安装，只能卸载重装、存档全丢）。完整说明见 `keystore/README.txt`。
- ⚠️ **a0.0.1 之前用 debug key 签的那几个测试包，在设备上要先卸载**才能装本版：

  ```
  adb uninstall top.gunmu.party
  ```

### 修复：安卓版一打开就闪退

内测第一版装到手机上**一点就退**，堆栈是：

```
java.lang.NoClassDefFoundError: Failed resolution of:
    Landroidx/core/view/OnApplyWindowInsetsListener;
  at com.badlogic.gdx.backends.android.AndroidApplication.init
```

真因：libGDX 1.13.5 的安卓后端**运行期依赖 `androidx.core`**（`AndroidApplication.init()`
处理 edge-to-edge 用），而这个工程的打包走的是自建工具链（Gradle 在受限环境里起不来），
依赖清单漏了这一条。**编译、打包、签名全都正常** —— javac 不需要解析 `init()` 方法体里
引用的类，所以只有真机启动那一瞬间才炸。

修了三件事：

1. `tools/fetch_libs.py` 现在按 **`.module` 元数据的 `java-runtime` variant** 拉安卓运行期依赖闭包
   （含 androidx.core 及其 Kotlin 实现需要的 coroutines、arch.core、guava 的 ListenableFuture
   接口等），并且在下载前就做版本对齐、补上 `-jvm` 实现变体；
2. `tools/build_apk.py` 照 AGP 的做法，把各 AAR 的 `res/` 一起编译进 APK，并用
   `--extra-packages` 为 8 个库包（`androidx.core`、`androidx.lifecycle.runtime`、
   `com.badlogic.gdx.backends.android`…）生成 `R` 类 —— AGP 3.0 之后库的 R 类不再随 AAR 分发；
3. 新增 **`tools/verify_dex.py`（打包阶段硬断言）**：解析 dex 的引用与定义，断言每个被引用的类
   都能在 dex / 框架 / java 运行时里找到。**校验不过就中止构建、不产包**。
   它对修复前的包精确报出了那 5 个缺类，对修复后的包报 0 缺类。

APK 因此从 3.2 MB 变成 **5.0 MB**（多出来的是 androidx + kotlin + coroutines 的运行期依赖；
走 Gradle 打的 debug 包同样包含它们，只有 release 经 R8 收缩后才会变小）。

> ⚠️ 如果你装过上一版（那个会闪退的包），**直接覆盖安装即可**（同签名、同 versionCode）。

### 已知的待观察项（需要内测反馈）

- AI 使用普通攻击的概率是 `0.45 + 0.25 × 难度`，实战里是否偏高/偏低要看手感；
- 「闪现」的落点回退规则（落在虚空就退到上一个安全点）在贴边行驶时的手感；
- 决战关（L5）的时长与人数（10 人 / 240 s 上限）是否需要收紧；
- 道具「一路绿灯」铺出的加速带是对所有人有效的，竞速图里可能出现"堵路"玩法。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把桌面端与安卓端需要的全部 jar 抓到 libs/ 与 libs-mobile/。

为什么不能靠"手抄一份依赖清单"：
  libGDX 1.13.x 把 SharedLibraryLoader 从 gdx.jar 拆到了独立构件
  com.badlogicgames.gdx:gdx-jnigen-loader，而 GdxNativesLoader **编译期不引用、
  运行期才引用**它。少抄一行，javac 一切正常、游戏一启动就
  NoClassDefFoundError。所以清单必须从构件的 module 元数据推出来，不能凭记忆写。

依赖树（1.13.5）：
    gdx               → gdx-jnigen-loader
    gdx-freetype      → gdx
    gdx-backend-lwjgl3→ gdx, lwjgl{,-glfw,-jemalloc,-openal,-opengl,-stb},
                        jlayer, jorbis
  运行时还需要 gdx-platform / gdx-freetype-platform 的 natives-desktop。

用法：
    python tools/fetch_libs.py            # 缺什么补什么
    python tools/fetch_libs.py --force    # 全部重下
    python tools/fetch_libs.py --verify   # 只校验，不下载
"""

import argparse
import io
import json
import os
import re
import subprocess
import sys
import urllib.request
import zipfile

# 中文 Windows 的控制台默认是 GBK，直接 print 中文会抛 UnicodeEncodeError。
# 所有脚本统一把标准输出切到 UTF-8（失败就算了，不影响逻辑）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "https://repo1.maven.org/maven2"
# androidx 的东西只在 Google Maven 上（不是 Maven Central）
GOOGLE = "https://dl.google.com/dl/android/maven2"

# ---------------------------------------------------------------- 依赖清单
# (group, artifact, version, classifier, 目标目录)
GC = "com/badlogicgames.gdx"
DESKTOP = [
    (GC, "gdx", "1.13.5", None, "libs"),
    (GC, "gdx-jnigen-loader", "2.5.2", None, "libs"),   # ← 漏了就是启动即崩
    (GC, "gdx-freetype", "1.13.5", None, "libs"),
    (GC, "gdx-backend-lwjgl3", "1.13.5", None, "libs"),
    (GC, "gdx-platform", "1.13.5", "natives-desktop", "libs"),
    (GC, "gdx-freetype-platform", "1.13.5", "natives-desktop", "libs"),
    ("org.lwjgl", "lwjgl", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl", "3.3.3", "natives-windows", "libs"),
    ("org.lwjgl", "lwjgl-glfw", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl-glfw", "3.3.3", "natives-windows", "libs"),
    ("org.lwjgl", "lwjgl-jemalloc", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl-jemalloc", "3.3.3", "natives-windows", "libs"),
    ("org.lwjgl", "lwjgl-openal", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl-openal", "3.3.3", "natives-windows", "libs"),
    ("org.lwjgl", "lwjgl-opengl", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl-opengl", "3.3.3", "natives-windows", "libs"),
    ("org.lwjgl", "lwjgl-stb", "3.3.3", None, "libs"),
    ("org.lwjgl", "lwjgl-stb", "3.3.3", "natives-windows", "libs"),
    ("com.badlogicgames.jlayer", "jlayer", "1.0.1-gdx", None, "libs"),
    ("org.jcraft", "jorbis", "0.0.17", None, "libs"),
]

# 安卓端的 gdx-backend-android 只发 aar，需要把里面的 classes.jar 取出来
ANDROID_AAR = (GC, "gdx-backend-android", "1.13.5")

# ---------------------------------------------------------------- androidx（安卓运行期必需）
#
# ⚠️ 真事故：手机上一打开就闪退
#     java.lang.NoClassDefFoundError: Failed resolution of:
#         Landroidx/core/view/OnApplyWindowInsetsListener;
#       at com.badlogic.gdx.backends.android.AndroidApplication.init
#
# libGDX 1.13.5 的 `AndroidApplication.init()` 用 `ViewCompat.setOnApplyWindowInsetsListener`
# 处理 edge-to-edge（Android 15+ 强制），而这两个类在 **androidx.core:core** 里 ——
# `gdx-backend-android` 的 pom 把它声明为 runtime 依赖，走 Gradle 时会被自动拉进来，
# 而我们这条"直连工具链"的路没有依赖解析器，于是就漏了：javac 不需要解析
# `AndroidApplication.init()` 方法体里引用的类（`AndroidLauncher` 只调它的公共 API），
# 编译全绿、dex 也生成成功，只有真机一启动才炸。
#
# 所以这里也**不手抄版本**（同 D32 的理由）：androidx 的根依赖与版本从
# gdx-backend-android 的 pom 里读，再按 pom 的依赖递归求闭包。
BACKEND_POM = (GC, "gdx-backend-android", "1.13.5")

# 这些构件的后缀说明它是**编译期**的东西（lint 规则 / 注解处理器 / 测试件），
# 运行期一个字都用不上 —— 对齐时直接剔掉。
BUILD_ONLY_SUFFIX = ("-lint", "-compiler", "-processor", "-testing")

# 递归依赖时**只进这些**。
#
# 为什么要设这道闸：`.module` 的 runtime 依赖会一路带出 guava、jna、rxjava/reactor、
# play-services、support-v4、kotlinx-coroutines 的 debug/rx/javaFx/slf4j 变体……
# （实测 60 个构件、24 MB），那些是工具库与测试件的传递依赖，运行期走不到。
#
# 这份名单**不是猜的**：每一行都是 `tools/verify_dex.py` 报出"缺这个类"之后补的
# （androidx.core 的 Kotlin 实现要用 coroutines，lifecycle 要用 arch.core，
# concurrent-futures 要用 guava 的 ListenableFuture 接口）。
# 需要再加时，同样先让校验器报，再往这里加一行。
ALLOWED_GROUPS = ("androidx.",)
ALLOWED_ARTIFACTS = (
    "kotlin-stdlib",
    "kotlinx-coroutines-core",
    "kotlinx-coroutines-core-jvm",
    "kotlinx-coroutines-android",
    "annotations",
    "listenablefuture",
)


def _dep_allowed(group, artifact):
    if group.startswith("androidx.test"):
        return False
    if group.startswith(ALLOWED_GROUPS):
        return True
    return artifact in ALLOWED_ARTIFACTS


def _base_artifact(artifact):
    """`annotation` 与 `annotation-jvm` 是同一个逻辑构件（后者才是真正的实现）。"""
    return artifact[:-4] if artifact.endswith("-jvm") else artifact

# poms 里这些 scope / 标记的依赖不进成品
POM_SKIP_SCOPES = ("test", "provided", "system")

# 安卓成品里**必须存在**的类（不许写死 jar 名：版本升级后文件名会变，
# 断言就会变成"假失败"）。在 libs-mobile/*.jar 里任意一个找到即可。
MOBILE_REQUIRED_CLASSES = [
    "androidx/core/view/OnApplyWindowInsetsListener.class",
    "androidx/core/view/ViewCompat.class",
]

# 这些类必须在成品里出现，否则就是依赖抄漏了
REQUIRED = [
    "libs/gdx-1.13.5.jar::com/badlogic/gdx/utils/GdxNativesLoader.class",
    "libs/gdx-jnigen-loader-2.5.2.jar::com/badlogic/gdx/utils/SharedLibraryLoader.class",
    "libs/gdx-backend-lwjgl3-1.13.5.jar::com/badlogic/gdx/backends/lwjgl3/Lwjgl3Application.class",
    "libs/gdx-freetype-1.13.5.jar::com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator.class",
    "libs-mobile/gdx-backend-android-1.13.5.jar::com/badlogic/gdx/backends/android/AndroidApplication.class",
]


def jar_name(group, artifact, version, classifier):
    return "%s-%s%s.jar" % (artifact, version, "-" + classifier if classifier else "")


def url_of(group, artifact, version, classifier):
    g = group.replace(".", "/")
    return "%s/%s/%s/%s/%s" % (REPO, g, artifact, version,
                               jar_name(group, artifact, version, classifier))


def http_get(url):
    """取一个 URL 的内容（bytes）。

    ⚠️ 为什么不能只用 `urllib`：某些受限环境**只放行常见命令行工具（curl）的网络**，
    而 Python 自己创建的 socket 会被拦掉（`WinError 10013` 或直接超时）。
    实测同一台机器上 curl 拿 Google Maven 是 200、urllib 超时 —— 依赖下载不该
    因为"解释器不被允许联网"而失败，所以这里带一条 curl 回退。
    """
    try:
        with urllib.request.urlopen(url, timeout=120) as r:
            return r.read()
    except Exception as e:
        first = e
    tmp = os.path.join(ROOT, "build", "natives-cache", "_http.tmp")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    r = subprocess.run(["curl", "-fsSL", "--max-time", "600", "-o", tmp, url],
                       capture_output=True, text=True, timeout=660)
    if r.returncode != 0:
        raise RuntimeError("urllib 失败(%s)，curl 也失败(%s): %s"
                           % (first, (r.stderr or "").strip()[:200], url))
    with open(tmp, "rb") as f:
        return f.read()


def download(url, dest, force):
    if os.path.exists(dest) and os.path.getsize(dest) > 0 and not force:
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    sys.stdout.write("  下载 %s\n" % os.path.basename(dest))
    sys.stdout.flush()
    data = http_get(url)
    tmp = dest + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)
    return True


def fetch_android_aar(force):
    group, artifact, version = ANDROID_AAR
    g = group.replace(".", "/")
    aar = os.path.join(ROOT, "build", "natives-cache",
                      "%s-%s.aar" % (artifact, version))
    download("%s/%s/%s/%s/%s-%s.aar" % (REPO, g, artifact, version, artifact, version),
             aar, force)
    dest = os.path.join(ROOT, "libs-mobile", "%s-%s.jar" % (artifact, version))
    if os.path.exists(dest) and os.path.getsize(dest) > 0 and not force:
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with zipfile.ZipFile(aar) as z:
        data = z.read("classes.jar")
    with open(dest, "wb") as f:
        f.write(data)
    sys.stdout.write("  取出 %s (%.1f MB)\n"
                     % (os.path.basename(dest), len(data) / 1048576.0))
    return True


# ==================================================================== androidx
#
# 安卓端**运行期**的依赖闭包。不手抄清单，也不手抄版本：
# 从 gdx-backend-android 的 pom 读出它声明的 androidx 根依赖，再按各家的 pom
# 递归求闭包（跳过 test / provided / optional / 测试件）。

def repo_get(url):
    return http_get(url)


def pom_text(group, artifact, version):
    """在 Google Maven 与 Central 里找这个构件的 pom，返回 (仓库基址, pom 文本)。"""
    g = group.replace(".", "/")
    for base in (GOOGLE, REPO):
        url = "%s/%s/%s/%s/%s-%s.pom" % (base, g, artifact, version, artifact, version)
        try:
            return base, repo_get(url).decode("utf-8", "replace")
        except Exception:
            continue
    raise SystemExit("找不到 %s:%s:%s 的 pom（Google Maven 与 Central 都试过了）"
                     % (group, artifact, version))


def module_runtime_deps(group, artifact, version):
    """取一个构件的 **运行期依赖**（权威来源：Gradle module 元数据）。

    ⚠️ 为什么不能解析 pom：androidx 的 pom 里混着 lint / annotationProcessor /
    compileOnly 之类的条目，照它递归会拉出 `lifecycle-compiler`、
    `lifecycle-livedata-core-ktx-lint`（这个构件**根本不存在**，直接 404）、
    kotlin 1.7.10、guava…… 那些都不是运行期需要的东西。Gradle 自己用的是
    `.module` 里的 `java-runtime` variant —— 那才是"打包该带什么"的答案。

    没有 `.module`（老构件 / 纯 Maven 库）时才退化为打 pom，并跳过编译期件。
    """
    g = group.replace(".", "/")
    for base in (GOOGLE, REPO):
        url = "%s/%s/%s/%s/%s-%s.module" % (base, g, artifact, version, artifact, version)
        try:
            meta = json.loads(repo_get(url).decode("utf-8", "replace"))
        except Exception:
            continue
        for v in meta.get("variants", []):
            usage = v.get("attributes", {}).get("org.gradle.usage")
            if usage != "java-runtime":
                continue
            out = []
            for d in v.get("dependencies", []):
                req = d.get("version", {}).get("requires")
                if req:
                    out.append((d["group"], d["module"], req))
            if out:
                return out
        return []
    # 退化：pom
    try:
        _, text = pom_text(group, artifact, version)
    except SystemExit:
        return []
    out = []
    for dg, da, dv, scope, optional in parse_pom_deps(text):
        if scope in POM_SKIP_SCOPES or optional:
            continue
        if da.endswith(BUILD_ONLY_SUFFIX + ("-ktx",)):
            continue
        out.append((dg, da, dv))
    return out


def parse_pom_deps(text):
    """粗略解析 pom 的 <dependency> 列表。

    这是**故意**的轻量解析：pom 里真正影响打包的只有 groupId/artifactId/version/
    scope/optional 五个字段（version 缺失的情况在 androidx 的 pom 里不存在）。
    """
    out = []
    for m in re.finditer(r"<dependency>(.*?)</dependency>", text, re.S):
        d = m.group(1)

        def tag(name):
            x = re.search("<%s>(.*?)</%s>" % (name, name), d)
            return x.group(1).strip() if x else ""

        g = tag("groupId")
        a = tag("artifactId")
        v = tag("version")
        scope = tag("scope") or "compile"
        optional = tag("optional").lower() == "true"
        if not (g and a and v and "[" not in v and "$" not in v):
            continue
        out.append((g, a, v, scope, optional))
    return out


_CLOSURE_CACHE = None


def android_closure():
    """安卓运行期依赖闭包（带缓存，避免重复联网解析）。

    一律走 `.module` 的 `java-runtime` variant（见 module_runtime_deps 的注释），
    并且**在返回前就做版本对齐**：同一个逻辑构件只留最高版本、剔掉编译期件。

    为什么对齐要放在"下载之前"而不是"下载之后清理"：受限环境给删除调用加了闸
    （一次超过约 50 个删除请求就整体拒绝），下载完再删就会被拦下一半，
    目录里留下重复版本的同一个库 —— 而 d8 遇到两个 jar 定义同一个类是**直接报错**的。
    所以正确做法是：**只下该下的**，而不是下完再筛。
    """
    global _CLOSURE_CACHE
    if _CLOSURE_CACHE is not None:
        return _CLOSURE_CACHE
    roots = [d for d in module_runtime_deps(*BACKEND_POM)
             if not d[0].startswith("com.badlogicgames.gdx")]
    if not roots:
        raise SystemExit("从 %s 的元数据里读不到运行期依赖（androidx 那一条）" % (BACKEND_POM,))
    seen = set()
    queue = list(roots)
    while queue:
        g, a, v = queue.pop(0)
        if (g, a, v) in seen or g.startswith("com.badlogicgames.gdx"):
            continue
        if not _dep_allowed(g, a):
            continue
        seen.add((g, a, v))
        for dep in module_runtime_deps(g, a, v):
            if dep not in seen:
                queue.append(dep)

    best = {}
    # `.module` 的依赖列表里是**逻辑构件名**，真正的实现放在 `-jvm` 变体里：
    # `annotation-1.8.1.jar` / `collection-1.4.2.jar` / `kotlinx-coroutines-core-1.6.4.jar`
    # 本体一个 class 都没有（实测 0 个），类全在 `*-jvm` 里。
    # Gradle 靠 available-at 自动取到，我们手动补上：同版本 + "-jvm"，
    # 远端不存在就在下载时跳过（404 只告警）。
    with_jvm = set(seen) | {(g, a + "-jvm", v) for (g, a, v) in seen}
    for g, a, v in with_jvm:
        if any(a.endswith(sfx) for sfx in BUILD_ONLY_SUFFIX):
            continue
        base = _base_artifact(a)
        cur = best.get(base)
        if cur is None or _ver_tuple(v) > _ver_tuple(cur):
            best[base] = v
    _CLOSURE_CACHE = sorted((g, a, v) for (g, a, v) in with_jvm
                            if not any(a.endswith(sfx) for sfx in BUILD_ONLY_SUFFIX)
                            and best.get(_base_artifact(a)) == v)
    return _CLOSURE_CACHE


def align_mobile_jars():
    """把 libs-mobile/ 里**已经不该留着的** jar 清掉（正常情况下一个都没有）。

    版本对齐已经前移到 `android_closure()`（只下该下的），所以这里主要是兜底：
    清理早期版本跑出来的残留（历史遗留、换过解析方式之后的旧文件）。
    受限环境会拦批量删除，所以**失败只告警**；真正保证目录干净的是"下载前就对齐"。
    """
    keep = {"gdx-backend-android-1.13.5.jar"}
    for g, a, v in android_closure():
        keep.add("%s-%s.jar" % (a, v))

    d = os.path.join(ROOT, "libs-mobile")
    if not os.path.isdir(d):
        return 0
    removed = 0
    stale = []
    for n in sorted(os.listdir(d)):
        if not n.endswith(".jar") or n in keep:
            continue
        try:
            os.remove(os.path.join(d, n))
            removed += 1
        except OSError:
            stale.append(n)
    if stale:
        sys.stdout.write("  警告: 有 %d 个不属于依赖闭包的 jar 删不掉（宿主拦了批量删除）：\n"
                         % len(stale))
        for n in stale[:6]:
            sys.stdout.write("    libs-mobile/%s\n" % n)
        sys.stdout.write("    重复版本的库会被 d8 判成 duplicate class —— 请手动删掉，"
                         "或换个空目录重跑本脚本。\n")
    else:
        sys.stdout.write("  libs-mobile/ 与依赖闭包一致（%d 个 jar）\n" % len(keep))
    return removed


def _ver_tuple(v):
    out = []
    for part in v.split("."):
        num = ""
        for ch in part:
            if ch.isdigit():
                num += ch
            else:
                break
        out.append(int(num) if num else 0)
    return tuple(out)


def resolve_android_closure(force):
    """下载安卓运行期依赖闭包到 libs-mobile/。

    下载失败（构件在别的仓库 / 确实不存在）只**告警并跳过** —— 由打包阶段的
    dex 类完整性校验做最终裁决：它按"dex 里引用了、但没定义"来报缺类，
    比在这里猜准得多。
    """
    sys.stdout.write("解析安卓运行期依赖（androidx 闭包）...\n")
    n = 0
    for g, a, v in android_closure():
        try:
            if download_mobile_artifact(g, a, v, force):
                n += 1
        except SystemExit as e:
            sys.stdout.write("  跳过 %s:%s:%s（%s）\n" % (g, a, v, e))
    sys.stdout.write("  闭包 %d 个构件，新下载 %d 个\n" % (len(android_closure()), n))
    return n


def download_mobile_artifact(group, artifact, version, force):
    """下载一个构件到 libs-mobile/：AAR 取里面的 classes.jar，普通 jar 直接落盘。"""
    dest = os.path.join(ROOT, "libs-mobile", "%s-%s.jar" % (artifact, version))
    if os.path.exists(dest) and os.path.getsize(dest) > 0 and not force:
        return False
    g = group.replace(".", "/")
    last = None
    for base in (GOOGLE, REPO):
        for ext in ("aar", "jar"):
            url = "%s/%s/%s/%s/%s-%s.%s" % (base, g, artifact, version,
                                            artifact, version, ext)
            try:
                data = repo_get(url)
            except Exception as e:      # 404 就试下一个（aar / jar / 另一个仓库）
                last = e
                continue
            if ext == "aar":
                tmp = os.path.join(ROOT, "build", "natives-cache",
                                   "%s-%s.aar" % (artifact, version))
                os.makedirs(os.path.dirname(tmp), exist_ok=True)
                with open(tmp, "wb") as f:
                    f.write(data)
                with zipfile.ZipFile(tmp) as z:
                    if "classes.jar" not in z.namelist():
                        last = "aar 里没有 classes.jar"
                        continue
                    data = z.read("classes.jar")
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
            sys.stdout.write("  %s:%s:%s%s -> %s (%.0f KB)\n"
                             % (group, artifact, version, " (aar)" if ext == "aar" else "",
                                os.path.basename(dest), len(data) / 1024.0))
            return True
    raise SystemExit("下载失败 %s:%s:%s（%s）" % (group, artifact, version, last))


def verify():
    ok = True
    for spec in REQUIRED:
        jar, entry = spec.split("::")
        p = os.path.join(ROOT, jar)
        if not os.path.exists(p):
            sys.stdout.write("  缺失 jar : %s\n" % jar)
            ok = False
            continue
        try:
            with zipfile.ZipFile(p) as z:
                if entry not in z.namelist():
                    sys.stdout.write("  缺类     : %s 里没有 %s\n" % (jar, entry))
                    ok = False
        except zipfile.BadZipFile:
            sys.stdout.write("  损坏     : %s\n" % jar)
            ok = False
    ok = verify_mobile_classes() and ok
    return ok


def verify_mobile_classes():
    """libs-mobile/*.jar 里必须能找到 androidx 的关键类。

    这条断言是"打开就闪退"那个 bug 的守卫：`AndroidApplication.init()` 依赖
    androidx.core，缺了它编译与打包全都正常，只有真机启动才崩 ——
    所以必须有一条能在**打包阶段**就红掉的检查。
    """
    d = os.path.join(ROOT, "libs-mobile")
    jars = ([n for n in sorted(os.listdir(d)) if n.endswith(".jar")]
            if os.path.isdir(d) else [])
    if not jars:
        sys.stdout.write("  缺失目录 : libs-mobile/ 里一个 jar 都没有\n")
        return False
    found = set()
    for n in jars:
        try:
            with zipfile.ZipFile(os.path.join(d, n)) as z:
                names = set(z.namelist())
        except zipfile.BadZipFile:
            sys.stdout.write("  损坏     : libs-mobile/%s\n" % n)
            continue
        for c in MOBILE_REQUIRED_CLASSES:
            if c in names:
                found.add(c)
    ok = True
    for c in MOBILE_REQUIRED_CLASSES:
        if c not in found:
            sys.stdout.write("  缺类     : libs-mobile/ 里找不到 %s\n" % c)
            sys.stdout.write("             （androidx.core 没拉全 —— 装到手机上会一启动就闪退）\n")
            ok = False
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    if args.verify:
        sys.stdout.write("校验依赖完整性...\n")
        sys.exit(0 if verify() else 1)

    sys.stdout.write("拉取桌面端依赖 -> libs/\n")
    n = 0
    for group, artifact, version, classifier, outdir in DESKTOP:
        dest = os.path.join(ROOT, outdir, jar_name(group, artifact, version, classifier))
        if download(url_of(group, artifact, version, classifier), dest, args.force):
            n += 1
    sys.stdout.write("拉取安卓端后端 -> libs-mobile/\n")
    if fetch_android_aar(args.force):
        n += 1
    n += resolve_android_closure(args.force)
    align_mobile_jars()

    sys.stdout.write("\n新下载 %d 个构件。校验：\n" % n)
    if verify():
        sys.stdout.write("全部关键类到位。\n")
    else:
        sys.exit("依赖不完整，见上。")


if __name__ == "__main__":
    main()

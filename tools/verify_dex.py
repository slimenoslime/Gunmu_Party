#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APK 类与资源完整性校验：把"打开就闪退 / 一进比赛就报错"拦在打包阶段。

两类问题，共同点是**编译、打包、签名全都正常**，只有真机才炸：

一、缺类。真事故：

    java.lang.NoClassDefFoundError: Failed resolution of:
        Landroidx/core/view/OnApplyWindowInsetsListener;
      at com.badlogic.gdx.backends.android.AndroidApplication.init

    libGDX 1.13.5 的安卓后端运行期依赖 androidx.core，而我们这条"直连工具链"的路没有
    依赖解析器，漏了它。javac 不需要解析 `AndroidApplication.init()` 方法体里引用的类，
    所以一路绿到手机上，然后一点就退。
    判据：dex 里**被引用**的每一个类，要么在 dex 里自己定义了，要么由 Android 框架
    （android.jar）提供，要么由 java 运行时提供。三者都不是 → 运行期必然 NoClassDefFoundError。

二、缺资源。真事故：

    GdxRuntimeException: File not found:
        com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl (Classpath)

    jar 里的**非 class 文件也是运行期资源**（libGDX 用 `Gdx.files.classpath(...)` 读着色器
    源码、读默认字体）。打包时只编了 `.class`，资源没铺进 APK，于是安卓端一进比赛那一帧
    就炸；桌面端看不出来（fat jar 是整包合并的）。
    判据：dex 里出现的**按路径读文件**的字符串，必须能在 APK 里找到 ——
    要么在根部（类路径资源），要么在 `assets/` 下（`Gdx.files.internal`）。

实现：直接解析 dex 的 type_ids / class_defs / string_ids 与 APK 的条目表做差集。
不需要反编译，也不需要联网。

用法：
    python tools/verify_dex.py <apk 或 classes.dex> [--android-jar <路径>]
退出码非 0 表示发现了缺类或缺资源。传单个 dex 时跳过资源检查（没有包可比对）。
"""

import argparse
import os
import struct
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Android 运行时自己提供、但 android.jar 里查不到的前缀（框架/引导类库的内部实现）。
# 白名单**只放这些**，别拿它来"消掉红字" —— 缺的依赖要补，不要屏蔽。
RUNTIME_PREFIXES = (
    "Ldalvik/",
    "Llibcore/",
    "Lsun/",
    "Lcom/sun/",
    "Ljava/",
    "Ljavax/",
    "Ljdk/",
    "Lorg/apache/harmony/",
    "Lorg/w3c/dom/",
    "Lorg/xml/sax/",
    "Lorg/json/",
)

# 已知「代码路径走不到」的缺失，逐条写清理由。
# ⚠️ 这是**豁免**，不是屏蔽：每加一条都要能说清"什么条件下才会用到它"。
EXEMPT = {
    "Lcom/badlogic/gdx/backends/android/R$drawable;":
        "libGDX 只在自己的软键盘输入对话框（DefaultAndroidInput 的内部类）里引用它，"
        "本作没有任何文本输入，那条路径永远走不到",
    "Lcom/badlogic/gdx/backends/android/R$layout;":
        "同上（软键盘对话框的布局）",
    "Lcom/badlogic/gdx/backends/android/R$string;":
        "同上（软键盘对话框的文案）",
    "Landroidx/fragment/app/Fragment;":
        "只有 AndroidFragmentApplication（把游戏塞进 Fragment 的用法）引它；"
        "我们的启动器继承的是 AndroidApplication",
    "Landroidx/fragment/app/FragmentActivity;":
        "同上",
}

# **必须**在 dex 里被定义的关键类。
#
# 这条是正向断言，专门守「打开就闪退」那个 bug：libGDX 的 AndroidApplication.init()
# 在启动时就会用到它们，缺一个就是真机一点就退，而编译与打包全程看不出来。
MUST_DEFINE = (
    "Landroidx/core/view/OnApplyWindowInsetsListener;",
    "Landroidx/core/view/ViewCompat;",
)

# ---------------------------------------------------------------- 类路径资源
#
# 第二类"编译期看不出来、只有真机才炸"的问题：**jar 里的非 class 文件也是运行期资源**。
#
# 真事故：安卓版进比赛那一帧
#
#     GdxRuntimeException: File not found:
#         com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl (Classpath)
#
# libGDX 的 DefaultShader 用 `Gdx.files.classpath(...)` 读着色器源码；打包时只编了
# `.class`，资源全丢。桌面端看不出来（fat jar 整包合并了这些条目），只有安卓炸。
#
# 判据：dex 里出现的**按路径读文件**的字符串，必须能在 APK 里找到 ——
# 要么在根部（类路径资源），要么在 `assets/` 下（`Gdx.files.internal`）。
RESOURCE_EXT = (
    ".glsl", ".fnt", ".png", ".jpg", ".ttf", ".otf", ".atlas", ".dat", ".ser",
    ".properties", ".txt", ".json", ".ogg", ".mp3", ".wav", ".obj", ".g3db", ".g3dj",
)

# 已知「代码路径走不到 / 由框架提供」的资源，逐条写清理由。
RESOURCE_EXEMPT = {}


def is_descriptor(t):
    """基本类型与数组描述符不是"类"，跳过（数组的元素类型会被单独引用）。"""
    return len(t) > 1 and not t.startswith("[")


def read_uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def dex_strings(buf, off, size):
    """取字符串表（string_data_item 是 MUTF-8，类描述符是纯 ASCII，直接按 latin-1 解）。"""
    out = []
    for i in range(size):
        data_off = struct.unpack_from("<I", buf, off + i * 4)[0]
        n, p = read_uleb128(buf, data_off)
        out.append(buf[p:p + n].decode("utf-8", "replace"))
    return out


def dex_all_strings(buf):
    """整个字符串表（资源检查要用它反查"代码里按路径读的文件"）。"""
    string_ids_size, string_ids_off = struct.unpack_from("<2I", buf, 56)
    return dex_strings(buf, string_ids_off, string_ids_size)


def dex_types(buf):
    """返回 (被引用类型集合, 已定义类型集合)。"""
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     _proto_size, _proto_off, _field_size, _field_off,
     _method_size, _method_off,
     class_defs_size, class_defs_off) = struct.unpack_from("<12I", buf, 56)
    strings = dex_strings(buf, string_ids_off, string_ids_size)

    referenced = set()
    type_names = []
    for i in range(type_ids_size):
        idx = struct.unpack_from("<I", buf, type_ids_off + i * 4)[0]
        name = strings[idx]
        type_names.append(name)
        referenced.add(name)

    defined = set()
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", buf, class_defs_off + i * 32)[0]
        if class_idx < len(type_names):
            defined.add(type_names[class_idx])
    return referenced, defined


def apk_classes(path):
    """从 android.jar 里取它提供的全部类（转成 dex 的描述符形式）。

    zip 里的 `Foo$Bar.class` 与 dex 里的 `LFoo$Bar;` 形式一致，直接转换即可。
    """
    out = set()
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            out.add("L" + n[:-6] + ";")
    return out


def android_jar_classes(android_jar):
    names = apk_classes(android_jar)
    # 内部类在 dex 里是 `LFoo$Bar;`，zip 里也是 `Foo$Bar.class` → 形式一致，直接可用
    return names


def load_dex_files(target):
    """支持传 APK（取里面所有 classes*.dex）或单个 .dex 文件。"""
    if target.endswith(".apk") or zipfile.is_zipfile(target):
        out = []
        with zipfile.ZipFile(target) as z:
            for n in sorted(z.namelist()):
                if n.endswith(".dex"):
                    out.append((n, z.read(n)))
        return out
    with open(target, "rb") as f:
        return [(os.path.basename(target), f.read())]


def looks_like_resource(s):
    """判定一个 dex 字符串是不是"包内按路径读的文件名"。

    宁可漏判也不要误判：误报会让构建无缘无故变红，然后有人开始往豁免表里塞东西 ——
    那这条检查就废了。所以：

      · 以 `/` 开头的**不是**包内资源，是设备上的绝对路径（`Fonts.CANDIDATES` 里那串
        系统字体走 `Gdx.files.absolute`），跳过；
      · Windows 盘符路径（`C:/Windows/Fonts/...`）靠冒号一起被排除；
      · 必须带 `/`、带已知的资源扩展名，且不含空白/引号/通配符
        —— shader 源码那种多行字符串一律排除。
    """
    if not (3 < len(s) < 160) or "/" not in s:
        return False
    if s.startswith("/"):
        return False
    if any(c in s for c in " \t\n\r\"'*?<>|:"):
        return False
    if s.endswith("/"):
        return False
    return ("." + s.rsplit(".", 1)[-1]) in RESOURCE_EXT


def apk_entries(target):
    """APK 里的全部条目名（不是 APK 就返回 None）。"""
    if not (target.endswith(".apk") or zipfile.is_zipfile(target)):
        return None
    with zipfile.ZipFile(target) as z:
        return set(n for n in z.namelist() if not n.endswith("/"))


def check_resources(entries, strings):
    """断言"代码按路径读的文件"确实在 APK 里 → (缺失列表, 豁免命中列表)。

    命中口径：`Gdx.files.classpath("a/b.txt")` → APK 根部 `a/b.txt`；
    `Gdx.files.internal("fonts/game.ttf")` → APK 内 `assets/fonts/game.ttf`。
    """
    if entries is None:
        return None, []
    missing = []
    exempt = []
    for s in sorted(set(strings)):
        if not looks_like_resource(s):
            continue
        cand = s.lstrip("/")
        if cand in entries or ("assets/" + cand) in entries:
            continue
        if cand in RESOURCE_EXEMPT:
            exempt.append(cand)
            continue
        missing.append(cand)
    return missing, exempt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", help="APK 或 classes.dex")
    ap.add_argument("--android-jar", default=None,
                    help="android.jar 路径（缺省时自动找 SDK）")
    ap.add_argument("--show", type=int, default=25, help="最多列多少个缺类")
    args = ap.parse_args()

    android_jar = args.android_jar or find_android_jar()
    if not android_jar or not os.path.exists(android_jar):
        sys.stderr.write("找不到 android.jar（用 --android-jar 指定）\n")
        return 2

    framework = android_jar_classes(android_jar)
    referenced = set()
    defined = set()
    strings = set()
    for name, data in load_dex_files(args.target):
        r, d = dex_types(data)
        referenced |= r
        defined |= d
        strings.update(dex_all_strings(data))

    missing = []
    exempt_hit = []
    for t in referenced:
        if not is_descriptor(t):
            continue
        if t in defined or t in framework:
            continue
        if t.startswith(RUNTIME_PREFIXES):
            continue
        if t in EXEMPT:
            exempt_hit.append(t)
            continue
        missing.append(t)
    missing.sort()

    res_missing, res_exempt = check_resources(apk_entries(args.target), strings)

    sys.stdout.write("dex 类与资源完整性校验\n")
    sys.stdout.write("  引用类型 %d 个 · dex 内定义 %d 个 · 框架提供 %d 个\n"
                     % (len(referenced), len(defined), len(framework)))

    # 正向断言：启动路径上必须要有的类
    bad_must = [c for c in MUST_DEFINE if c not in defined]
    for c in MUST_DEFINE:
        if c in bad_must:
            sys.stdout.write("  [失败] 启动必需类没有被打进 dex：%s\n" % c)
    for t in sorted(set(exempt_hit)):
        sys.stdout.write("  [豁免] %s —— %s\n" % (t, EXEMPT[t]))

    if res_missing is None:
        sys.stdout.write("  （传的是单个 dex，跳过了类路径资源检查）\n")
    else:
        for r in sorted(set(res_exempt)):
            sys.stdout.write("  [豁免] 资源 %s —— %s\n" % (r, RESOURCE_EXEMPT[r]))

    ok_cls = not missing and not bad_must
    ok_res = not res_missing
    if ok_cls:
        sys.stdout.write("  [通过] 没有「被引用却不存在」的类\n")
    if ok_res and res_missing is not None:
        sys.stdout.write("  [通过] 没有「按路径读却不在包里」的资源\n")
    if ok_cls and ok_res:
        return 0
    if missing:
        sys.stdout.write("  [失败] %d 个被引用但找不到定义的类：\n" % len(missing))
        for t in missing[:args.show]:
            sys.stdout.write("    " + t + "\n")
        if len(missing) > args.show:
            sys.stdout.write("    …还有 %d 个\n" % (len(missing) - args.show))
        sys.stdout.write("  → 装到设备上一走到这些代码路径就会 NoClassDefFoundError。\n")
        sys.stdout.write("    补依赖请改 tools/fetch_libs.py（依赖清单的唯一来源），不要手抄。\n")
    if res_missing:
        sys.stdout.write("  [失败] %d 个被代码按路径读、但不在 APK 里的文件：\n"
                         % len(res_missing))
        for r in res_missing[:args.show]:
            sys.stdout.write("    " + r + "\n")
        if len(res_missing) > args.show:
            sys.stdout.write("    …还有 %d 个\n" % (len(res_missing) - args.show))
        sys.stdout.write("  → 安卓端读到它们就是 GdxRuntimeException: File not found。\n")
        sys.stdout.write("    jar 里的非 class 文件由 build_apk.py 的 classpath_resources() 铺进 APK，\n")
        sys.stdout.write("    自己的文件要放进 assets/（internal）或 assets 根（classpath）。\n")
    return 1


def find_android_jar():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    cands = [sdk, "C:/Users/Administrator/WorkBuddy/2026-08-06-15-08-33/android-sdk",
             os.path.expanduser("~/AppData/Local/Android/Sdk")]
    for root in cands:
        if not root or not os.path.isdir(root):
            continue
        p = os.path.join(root, "platforms")
        if not os.path.isdir(p):
            continue
        for v in sorted(os.listdir(p), reverse=True):
            jar = os.path.join(p, v, "android.jar")
            if os.path.exists(jar):
                return jar
    return None


if __name__ == "__main__":
    sys.exit(main())

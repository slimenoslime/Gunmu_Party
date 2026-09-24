#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""不用 Gradle，直接调用 Android SDK 工具链打出可安装的 debug APK。

为什么要有这条路（docs/06 D31）：
  Gradle 的 native-platform 在受限环境里会因创建 .lock 文件失败而拒绝启动，
  整个构建链就卡在"还没开始编译"。而 APK 的构造本身只是几步机械操作：
      aapt2 compile → aapt2 link → javac → d8 → 打包 + zipalign + apksigner
  直连这些工具没有守护进程与缓存状态，产物可复现，也不需要联网。

关于路径里的空格：
  工程名 "gunmu party" 带空格，而 javac 的 `@argfile` 与 `-cp` 一旦拿到含空格的
  绝对路径就会被拆碎（"无效的标记: D:\\Downloads\\gunmu"）。这里统一走 Windows
  8.3 短路径（D:/DOWNLO~1/GUNMUP~1），从根上消掉空格。

用法：
    python tools/build_apk.py                  # 4 个 ABI 全打
    python tools/build_apk.py --abi arm64-v8a  # 只打一个 ABI（更快）
    python tools/build_apk.py --sdk <路径> --jdk <路径> --build-tools 36.0.0

签名：**固定使用项目自己的签名** keystore/gunmu-party.jks（别名 gunmu-party）。
     ⚠️ 丢了它 = 已安装用户永远无法升级，详见 keystore/README.txt。
     需要临时换一把时用 --keystore / --ks-pass / --ks-alias。

产物：build/apk/gunmu-party-<版本>.apk
      （例如 build/apk/gunmu-party-a0.0.1.apk；另存一份不带版本名的
        gunmu-party-debug.apk 作为"当前最新"的别名）
      版本号取自 GameConfig.VERSION，唯一读取入口是 tools/version.py。
"""

import argparse
import io
import os
import re
import subprocess
import sys
import zipfile

# 中文 Windows 的控制台默认是 GBK，直接 print 中文会抛 UnicodeEncodeError。
# 所有脚本统一把标准输出切到 UTF-8（失败就算了，不影响逻辑）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from version import VERSION_NAME, VERSION_CODE, VERSION_TAG, check_sync   # noqa: E402

BUILD = "build/apk"
APP_ID = "top.gunmu.party"

# ---------------------------------------------------------------- 版本号
#
# 版本号的**唯一来源**是 core/.../GameConfig.java 的 VERSION / VERSION_CODE
# （用户的命名规范：a=alpha 内测版 / b=beta 测试版 / v=release 正式版），
# 由 tools/version.py 统一读取。读不到就抛异常，不猜。
#
# 产物名带版本：gunmu-party-a0.0.1.apk。同时再拷一份不带版本名的
# gunmu-party-debug.apk 作为"当前最新"的别名 —— 文档与 adb 脚本按那个名字引用。
APK_NAME = VERSION_TAG + ".apk"
APK_ALIAS = "gunmu-party-debug.apk"

MIN_SDK = "26"
TARGET_SDK = "36"
COMPILE_SDK = "36"
ALL_ABIS = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"]

# ---------------------------------------------------------------- 固定签名
#
# 从 a0.0.1 起，所有 APK 都用**项目自己的固定签名**，不再用 Android 工具链的
# debug key。原因：`~/.android/debug.keystore` 是本机生成的，而且我们拷到
# `build/` 下的那一份在 .gitignore 里（不入库）—— 换机器、清理目录之后就没了，
# 打出来的包签名不同，装不进别人的设备（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
# 也升不了级（安卓不允许同包名换签名覆盖）。
#
# 签名本体在 `keystore/gunmu-party.jks`（**入库目录**），参数与指纹见
# `keystore/README.txt`。⚠️ 这把钥匙丢了 = 已装用户永远无法升级，
# 所以它必须在版本控制里，也必须备份。
#
# 找不到就**直接失败**：静默回退到 debug key 会打出一个"看着正常、装不上"的包，
# 比构建失败难查得多。需要临时换一把时用 --keystore / --ks-pass / --ks-alias。
KEYSTORE = "keystore/gunmu-party.jks"
KS_PASS = "gunmu-party"
KS_ALIAS = "gunmu-party"

SDK_CANDIDATES = [
    os.environ.get("ANDROID_HOME") or "",
    os.environ.get("ANDROID_SDK_ROOT") or "",
    "C:/Users/Administrator/WorkBuddy/2026-08-06-15-08-33/android-sdk",
    os.path.expanduser("~/AppData/Local/Android/Sdk"),
    "C:/Android/Sdk",
    "D:/Android/Sdk",
]

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME") or "",
    "C:/Program Files/BellSoft/LibericaJDK-21",
    "C:/Program Files/Java/jdk-21",
]

# 安卓端需要的 jar。gdx-jnigen-loader 必须在内：GdxNativesLoader 引用它的
# SharedLibraryLoader，缺了编译期无感、运行期直接 NoClassDefFoundError。
CP_JARS = [
    "libs/gdx-1.13.5.jar",
    "libs/gdx-freetype-1.13.5.jar",
    "libs/gdx-jnigen-loader-2.5.2.jar",
]


# ---------------------------------------------------------------- 类路径资源
#
# **jar 里的非 class 文件同样是运行期资源**，必须原样铺到 APK 根部。
#
# 守的是「安卓版一进比赛就 File not found」：libGDX 的 DefaultShader 是这么读着色器的 ——
#
#     Gdx.files.classpath("com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl")
#
# （另有 `lsans-15.fnt` / `lsans-15.png` 是 `new BitmapFont()` 的默认字体、
#   `depth.*.glsl` 是阴影、`particles.*.glsl` 是粒子。）
#
# Gradle 打 APK 时会把 jar 里的资源铺到根部（`mergeJavaResource` + 打包），
# 安卓的 ClassLoader 就能按原路径取到；我们这条"直连工具链"的路只编译了 `.class`，
# 于是资源全丢。**桌面端看不出来** —— 桌面 fat jar 是整包合并的（见 tools/build_jar.py），
# 只有安卓炸，且炸在进比赛那一帧。
#
# 口径：**黑名单**（除了明确是编译期元数据的一律铺），不是白名单 ——
# 白名单漏一个新 jar 的新资源就是又一次"只有真机才炸"。
RESOURCE_SKIP_EXT = (
    ".class",            # 已经进 dex 了
    ".gwt.xml",          # GWT 模块描述符（`com/badlogic/gdx.gwt.xml`）
    ".rl",               # ragel 源码（XmlReader.rl 之类，只有改 libGDX 时才用）
    ".kotlin_builtins",  # Kotlin 编译期元数据
    ".kotlin_metadata",
    ".kotlin_module",
    ".knm",              # KMP linkdata
)
RESOURCE_SKIP_NAMES = (
    "DebugProbesKt.bin",  # kotlinx-coroutines 的调试探针
    "MANIFEST.MF",
)


def classpath_resources(cp_jars):
    """从各 jar 里取"运行期资源" → [(APK 内路径, 字节, 来源 jar)]。

    没扩展名的条目一律跳过（KMP 的 `commonMain/default/linkdata/module`、
    `.../manifest` 这类都是编译期元数据，铺进去只是白占体积）。
    """
    out = []
    owner = {}
    for jar in cp_jars:
        with zipfile.ZipFile(os.path.join(ROOT, jar)) as z:
            for item in z.infolist():
                n = item.filename
                if item.is_dir() or n.startswith("META-INF/"):
                    continue
                base = n.rsplit("/", 1)[-1]
                if base in RESOURCE_SKIP_NAMES:
                    continue
                if any(n.endswith(e) for e in RESOURCE_SKIP_EXT):
                    continue
                if "." not in base:
                    continue
                if n in owner:
                    sys.stdout.write("  提示: %s 同时来自 %s 与 %s，保留先来的\n"
                                     % (n, owner[n], jar))
                    continue
                owner[n] = jar
                out.append((n, z.read(n), jar))
    out.sort()
    return out


def check_native_packaging(manifest_src, apk_path):
    """断言「原生库的压缩方式」与清单里的 `extractNativeLibs` 自洽。

    为什么值得单独设一条：`.so` 是**压缩**进包的（我们现在就是，`zout.write` 用
    DEFLATED），Android 只有在 `android:extractNativeLibs="true"` 时才会在安装阶段
    把它解到应用目录；否则系统按"必须是未压缩且页对齐"处理，`System.loadLibrary` 直接失败 ——
    表现是**一点就退**，比着色器那次更早、堆栈里连 libGDX 都看不到。

    反过来，哪天改成不压缩（Gradle 的现代默认：不压缩 + `zipalign -p` 页对齐），
    这条会提醒把清单声明一起改，而不是留一个"看着对但自相矛盾"的包。
    """
    with zipfile.ZipFile(apk_path) as z:
        so = [(i.filename, i.compress_type) for i in z.infolist()
              if i.filename.startswith("lib/") and i.filename.endswith(".so")]
    if not so:
        raise SystemExit("APK 里一个 .so 都没有 —— 先跑 python tools/fetch_natives.py")
    m = re.search(r'android:extractNativeLibs="(true|false)"', manifest_src)
    declared = m.group(1) if m else None
    compressed = [n for n, method in so if method != zipfile.ZIP_STORED]
    if compressed and declared != "true":
        raise SystemExit(
            "原生库是压缩进包的（%d 个），但清单没有写 android:extractNativeLibs=\"true\"\n"
            "  （当前声明：%s）。这样装到设备上 loadLibrary 会直接失败、应用一点就退。\n"
            "  两条路二选一：① 清单里写明 extractNativeLibs=\"true\"；\n"
            "                 ② 打包时对 .so 用 ZIP_STORED 不压缩，并靠 zipalign -p 页对齐。"
            % (len(compressed), declared or "缺省"))
    if not compressed and declared == "true":
        sys.stdout.write("  提示: .so 未压缩且页对齐，清单其实可以不写 extractNativeLibs"
                         "（写了也不影响，只是多一次安装期解压）\n")
    log("  原生库 %d 个（%s）+ 清单 extractNativeLibs=%s —— 自洽"
        % (len(so), "压缩" if compressed else "不压缩", declared or "缺省(true)"))
    return len(so)


def mobile_jars():
    """libs-mobile/ 里的全部 jar。

    **不手抄这份清单**（本次闪退就是手抄漏了 androidx 导致的）：javac 不需要解析
    `AndroidApplication.init()` 方法体里引用的类，所以漏了依赖照样编译通过、dex 也生成成功，
    只有真机一启动才 `NoClassDefFoundError`。目录里有什么就打包什么，
    而目录的内容由 `tools/fetch_libs.py` 按 pom/module 元数据决定（唯一来源）。
    """
    d = os.path.join(ROOT, "libs-mobile")
    if not os.path.isdir(d):
        raise SystemExit("缺少 libs-mobile/ —— 先跑 python tools/fetch_libs.py")
    names = [n for n in sorted(os.listdir(d)) if n.endswith(".jar")]
    if not names:
        raise SystemExit("libs-mobile/ 里没有 jar —— 先跑 python tools/fetch_libs.py")
    return ["libs-mobile/" + n for n in names]


def compile_library_resources(aapt2, W, logs):
    """把各 AAR 携带的资源编译进 APK，并让 aapt2 为它们的包生成 R 类。

    为什么必须做：**AGP 3.0 之后，库的 R 类不再打进 AAR 的 classes.jar** ——
    应用构建时由 AGP 按 AAR 的 res/ 生成对应包名的 R.java。我们这条直连工具链的
    路没有这一步，于是 dex 里对 `Landroidx/core/R$id;`、`Landroidx/lifecycle/runtime/R$id;`
    之类的引用全是空的（`tools/verify_dex.py` 会直接报出来，本次就是）。

    做法与 AGP 一致：把库的 res 也编译进 link 输入，并用 `--extra-packages`
    让 aapt2 为那些包各生成一份 R.java。

    @return (资源 zip 列表（短路径）, 包名列表)
    """
    cache = os.path.join(ROOT, "build", "natives-cache")
    out_root = os.path.join(ROOT, BUILD, "libres")
    zips = []
    pkgs = []
    for jar in mobile_jars():
        base = os.path.basename(jar)[:-4]           # <artifact>-<version>
        aar = os.path.join(cache, base + ".aar")
        if not os.path.exists(aar):
            continue
        try:
            with zipfile.ZipFile(aar) as z:
                names = z.namelist()
                if not any(n.startswith("res/") for n in names):
                    continue
                pkg = ""
                if "AndroidManifest.xml" in names:
                    mf = z.read("AndroidManifest.xml").decode("utf-8", "replace")
                    m = re.search(r'package="([^"]+)"', mf)
                    if m:
                        pkg = m.group(1)
                if not pkg:
                    continue
                resdir = os.path.join(out_root, base, "res")
                for n in names:
                    if not n.startswith("res/") or n.endswith("/"):
                        continue
                    dest = os.path.join(out_root, base, n.replace("/", os.sep))
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, "wb") as f:
                        f.write(z.read(n))
        except zipfile.BadZipFile:
            logs("  警告: %s 不是有效的 AAR，跳过它的资源" % base)
            continue
        zout = W + "/libres-" + base + ".zip"
        run([aapt2, "compile", "--dir", short_path(resdir), "-o", zout])
        zips.append(zout)
        pkgs.append(pkg)
    if pkgs:
        logs("  库资源 %d 份，额外 R 包：%s" % (len(zips), "、".join(sorted(set(pkgs)))))
    return zips, sorted(set(pkgs))


def short_path(p):
    """取 Windows 8.3 短路径；非 Windows 或失败时原样返回。"""
    if os.name != "nt":
        return p
    try:
        import ctypes
        import ctypes.wintypes as wt
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.GetShortPathNameW.argtypes = [wt.LPCWSTR, wt.LPWSTR, wt.DWORD]
        buf = ctypes.create_unicode_buffer(2048)
        if k.GetShortPathNameW(p, buf, 2048):
            return buf.value.replace("\\", "/")
    except Exception:
        pass
    return p.replace("\\", "/")


def log(msg):
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def rm_rf(path):
    """不用 shutil.rmtree —— 受限环境会把它劫持成"移到回收站"并可能直接失败。
    清不掉就返回 False，由调用方决定要不要报错。"""
    if not os.path.exists(path):
        return True
    ok = True
    for dirpath, dirnames, names in os.walk(path, topdown=False):
        for n in names:
            try:
                os.remove(os.path.join(dirpath, n))
            except OSError:
                ok = False
        for n in dirnames:
            try:
                os.rmdir(os.path.join(dirpath, n))
            except OSError:
                ok = False
    try:
        os.rmdir(path)
    except OSError:
        ok = False
    return ok


def copy_file(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, "rb") as f, open(dst, "wb") as g:
        while True:
            buf = f.read(1 << 20)
            if not buf:
                break
            g.write(buf)


def run(cmd, **kw):
    kw.setdefault("cwd", ROOT)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", **kw)
    if r.returncode != 0:
        sys.stderr.write("命令失败(%d): %s\n" % (r.returncode, " ".join(map(str, cmd))))
        sys.stderr.write(r.stdout + "\n" + r.stderr + "\n")
        raise SystemExit(1)
    return r


def find_dir(candidates, marker, what):
    for c in candidates:
        if not c:
            continue
        p = c.replace("\\", "/").rstrip("/")
        if os.path.exists(os.path.join(p, marker)):
            return p
    raise SystemExit("找不到%s。候选：%s" % (what, [c for c in candidates if c]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sdk")
    ap.add_argument("--jdk")
    ap.add_argument("--build-tools")
    ap.add_argument("--abi", action="append", default=None,
                    help="可重复；缺省打全部 4 个 ABI")
    # 签名：默认就是项目固定签名（keystore/gunmu-party.jks）。
    # 这几个参数只为"临时换一把钥匙"准备 —— 别用它打正式分发包。
    ap.add_argument("--keystore", default=KEYSTORE,
                    help="签名库路径（默认项目固定签名 %s）" % KEYSTORE)
    ap.add_argument("--ks-pass", default=KS_PASS, help="签名库密码")
    ap.add_argument("--ks-alias", default=KS_ALIAS, help="签名别名")
    args = ap.parse_args()

    jdk = find_dir([args.jdk] + JDK_CANDIDATES, "bin/javac.exe", "JDK")
    sdk = find_dir([args.sdk] + SDK_CANDIDATES, "platforms", "Android SDK")
    bt_root = os.path.join(sdk, "build-tools")
    if args.build_tools:
        bt = os.path.join(bt_root, args.build_tools)
    else:
        bt = os.path.join(bt_root, sorted(os.listdir(bt_root))[-1])
    abi_list = args.abi or ALL_ABIS

    exe = ".exe" if os.name == "nt" else ""
    bat = ".bat" if os.name == "nt" else ""
    javac = os.path.join(jdk, "bin", "javac" + exe)
    jar_tool = os.path.join(jdk, "bin", "jar" + exe)
    android_jar = short_path(
        os.path.join(sdk, "platforms", "android-" + COMPILE_SDK, "android.jar"))
    aapt2 = os.path.join(bt, "aapt2" + exe)
    d8 = os.path.join(bt, "d8" + bat)
    zipalign = os.path.join(bt, "zipalign" + exe)
    apksigner = os.path.join(bt, "apksigner" + bat)
    for p, n in [(javac, "javac"), (android_jar, "android.jar"), (aapt2, "aapt2"),
                 (d8, "d8"), (zipalign, "zipalign"), (apksigner, "apksigner")]:
        if not os.path.exists(p):
            raise SystemExit("缺少 %s: %s" % (n, p))

    # 传给工具链的所有文件路径一律换成短路径（消掉工程名里的空格）
    S = short_path(ROOT)
    W = S + "/" + BUILD

    log("JDK        : " + jdk)
    log("SDK        : " + sdk)
    log("build-tools: " + os.path.basename(bt))
    log("工程短路径 : " + S)
    log("ABI        : " + ", ".join(abi_list))
    log("版本       : %s (code %s)" % (VERSION_NAME, VERSION_CODE))
    check_sync(log)

    if not rm_rf(os.path.join(ROOT, BUILD)):
        log("警告: build/apk 未能完全清空（受限环境），继续构建。"
            "若产物异常，请手动删除该目录后重试。")
    for sub in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(ROOT, BUILD, sub), exist_ok=True)

    env = dict(os.environ)
    env["JAVA_HOME"] = jdk

    # ---------------------------------------------------------------- 1) 资源
    res_zip = W + "/res.zip"
    run([aapt2, "compile", "--dir", S + "/android/src/main/res", "-o", res_zip])
    lib_res, lib_pkgs = compile_library_resources(aapt2, W, log)
    log("[1/7] 资源编译完成")

    # ---------------------------------------------------------------- 2) 链接
    # AGP 8 的清单不再写 package（改用 namespace），但 aapt2 link 需要它，
    # 所以生成一份注入了 package 的临时清单，原清单保持 AGP 兼容。
    with io.open(os.path.join(ROOT, "android", "AndroidManifest.xml"),
                 encoding="utf-8") as f:
        manifest_src = f.read()
    if 'package="' not in manifest_src:
        manifest_src = manifest_src.replace(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '          package="%s"' % APP_ID, 1)
    with io.open(os.path.join(ROOT, BUILD, "AndroidManifest.xml"), "w",
                 encoding="utf-8") as f:
        f.write(manifest_src)

    base_apk = W + "/base.apk"
    link_cmd = [aapt2, "link", "-o", base_apk,
                "--manifest", W + "/AndroidManifest.xml",
                "-I", android_jar,
                "--java", W + "/gen",
                "--min-sdk-version", MIN_SDK,
                "--target-sdk-version", TARGET_SDK,
                "--version-code", VERSION_CODE,
                "--version-name", VERSION_NAME,
                "--auto-add-overlay"]
    if lib_pkgs:
        # 为库的包名各生成一份 R.java（AGP 也是这么做的，见 compile_library_resources）
        link_cmd += ["--extra-packages", ":".join(lib_pkgs)]
    link_cmd += [res_zip] + lib_res
    run(link_cmd)
    log("[2/7] 资源链接完成")

    # ---------------------------------------------------------------- 3) 编译
    cp_jars = CP_JARS + mobile_jars()
    for j in cp_jars:
        if not os.path.exists(os.path.join(ROOT, j)):
            raise SystemExit("缺少依赖: %s（先跑 python tools/fetch_libs.py）" % j)
    classpath = ";".join([S + "/" + j for j in cp_jars] + [android_jar])

    def argfile(srcdirs, out_name, extra=None):
        lines = []
        for srcdir in srcdirs:
            for dirpath, _, names in os.walk(os.path.join(ROOT, srcdir)):
                for n in sorted(names):
                    if n.endswith(".java"):
                        rp = os.path.relpath(os.path.join(dirpath, n), ROOT)
                        lines.append(S + "/" + rp.replace("\\", "/"))
        lines += extra or []
        path = os.path.join(ROOT, BUILD, out_name)
        with io.open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        return W + "/" + out_name

    core_list = argfile(["core/src"], "core-sources.txt")
    run([javac, "-encoding", "UTF-8", "-nowarn", "-source", "17", "-target", "17",
         "-d", W + "/classes", "-cp", classpath, "@" + core_list])

    gen = []
    for dirpath, _, names in os.walk(os.path.join(ROOT, BUILD, "gen")):
        for n in sorted(names):
            if n.endswith(".java"):
                gen.append(S + "/" + os.path.relpath(
                    os.path.join(dirpath, n), ROOT).replace("\\", "/"))
    if not gen:
        raise SystemExit("aapt2 没有产出 R.java")
    android_list = argfile(["android/src"], "android-sources.txt", gen)
    run([javac, "-encoding", "UTF-8", "-nowarn", "-source", "17", "-target", "17",
         "-d", W + "/classes",
         "-cp", classpath + ";" + W + "/classes", "@" + android_list])
    log("[3/7] Java 编译完成")

    # ---------------------------------------------------------------- 4) dex
    # d8 只吃 .class / .jar，不吃目录，所以先把 class 打包成 jar。
    core_jar = W + "/core.jar"
    run([jar_tool, "cf", core_jar, "-C", W + "/classes", "."])
    run([d8, "--lib", android_jar, "--min-api", MIN_SDK,
         "--output", W + "/dex", core_jar] + [S + "/" + j for j in cp_jars], env=env)
    dex = os.path.join(ROOT, BUILD, "dex", "classes.dex")
    if not os.path.exists(dex):
        raise SystemExit("d8 没有产出 classes.dex")
    log("[4/7] dex 生成完成 (%.1f MB)" % (os.path.getsize(dex) / 1048576.0))

    # ---------------------------------------------------------------- 5) 打包
    # 类路径资源先算好：jar 里的非 class 文件必须铺到 APK 根部（见 classpath_resources）。
    # 少了它们，安卓端一读着色器就 File not found，而编译/打包/签名全程正常。
    res_list = classpath_resources(cp_jars)
    unsigned = os.path.join(ROOT, BUILD, "unsigned.apk")
    with zipfile.ZipFile(os.path.join(ROOT, BUILD, "base.apk"), "r") as zin, \
            zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            # resources.arsc 必须保持不压缩，zipalign 才能对其做 4 字节对齐
            if item.filename == "resources.arsc":
                zout.writestr(zipfile.ZipInfo(item.filename), data, zipfile.ZIP_STORED)
            else:
                zout.writestr(item.filename, data)
        zout.write(dex, "classes.dex")
        existing = set(zout.namelist())
        for arc, data, _ in res_list:
            if arc in existing:
                sys.stdout.write("  警告: %s 与基础 APK 里的条目重名，类路径资源跳过\n" % arc)
                continue
            zout.writestr(arc, data)
        for abi in abi_list:
            src = os.path.join(ROOT, "android", "libs", abi)
            if not os.path.isdir(src):
                raise SystemExit("缺少原生库目录 %s —— 先跑 python tools/fetch_natives.py"
                                 % src)
            for n in sorted(os.listdir(src)):
                if n.endswith(".so"):
                    zout.write(os.path.join(src, n), "lib/%s/%s" % (abi, n))
        assets = os.path.join(ROOT, "assets")
        for dirpath, _, names in os.walk(assets):
            for n in sorted(names):
                # 仓库里的说明文档不打进成品（charset.txt 是运行时资源，要留）
                if n.lower().endswith(".md") or n.lower() == "readme.txt":
                    continue
                full = os.path.join(dirpath, n)
                arc = "assets/" + os.path.relpath(full, assets).replace("\\", "/")
                zout.write(full, arc)
    log("[5/7] 打包完成（类路径资源 %d 个 / %.1f KB）"
        % (len(res_list), sum(len(d) for _, d, _ in res_list) / 1024.0))

    # 原生库的压缩方式必须与清单声明自洽（见 check_native_packaging）
    check_native_packaging(manifest_src, unsigned)

    aligned = os.path.join(ROOT, BUILD, "aligned.apk")
    run([zipalign, "-f", "-p", "4", unsigned, aligned])

    # ---------------------------------------------------------------- 6) 签名
    # 固定签名：库里的 keystore/gunmu-party.jks（见文件顶部注释与 keystore/README.txt）
    ks = os.path.join(ROOT, args.keystore)
    if not os.path.exists(ks):
        raise SystemExit(
            "找不到固定签名 %s。\n"
            "  它是本项目的**唯一签名**，丢了就无法给已安装的用户升级 ——\n"
            "  从备份里恢复它，或按 keystore/README.txt 里的命令重建一把（注意：重建是另一把钥匙）。\n"
            "  只是临时用别的签名：--keystore <路径> --ks-pass <密码> --ks-alias <别名>" % args.keystore)
    ks_short = short_path(ks)
    final = os.path.join(ROOT, BUILD, APK_NAME)
    run([apksigner, "sign",
         "--ks", ks_short, "--ks-pass", "pass:" + args.ks_pass,
         "--key-pass", "pass:" + args.ks_pass,
         "--ks-key-alias", args.ks_alias,
         "--min-sdk-version", MIN_SDK,
         "--out", W + "/" + APK_NAME, aligned], env=env)

    r = subprocess.run([apksigner, "verify", "--print-certs",
                        W + "/" + APK_NAME],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", cwd=ROOT, env=env)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("签名校验失败")

    # 再落一份不带版本名的别名，供 README / adb 脚本按固定名字引用
    alias = os.path.join(ROOT, BUILD, APK_ALIAS)
    copy_file(final, alias)

    log("[6/7] 签名完成（固定签名 %s，别名 %s）" % (args.keystore, args.ks_alias))

    # ---------------------------------------------------------------- 7) 类与资源完整性
    #
    # 这一条守的是两类「编译期看不出来、只有真机炸」的问题：
    #   ① 类：libGDX 1.13.5 的 AndroidApplication 运行期依赖 androidx.core，而 javac
    #      不需要解析 init() 方法体里引用的类 —— 漏了依赖照样编译通过、dex 生成成功、
    #      签名校验通过，只有真机启动才 NoClassDefFoundError；
    #   ② 资源：jar 里的非 class 文件（着色器源码、默认字体）必须铺进 APK，
    #      否则一进比赛就 `File not found: .../default.vertex.glsl (Classpath)`。
    # 两者都由 tools/verify_dex.py 解析 dex 直接判定，见该文件顶部注释。
    vr = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "verify_dex.py"),
                         final, "--android-jar", android_jar],
                        capture_output=True, text=True, encoding="utf-8",
                        errors="replace", cwd=ROOT)
    sys.stdout.write(vr.stdout)
    if vr.returncode != 0:
        sys.stderr.write(vr.stderr)
        raise SystemExit("类/资源完整性校验失败 —— 这个包装到手机上会闪退/报错，已经拦下不发布")
    log("[7/7] 类与资源完整性校验通过")

    log("")
    log("产物: build/apk/%s  (%.2f MB)   [版本 %s · code %s]"
        % (APK_NAME, os.path.getsize(final) / 1048576.0, VERSION_NAME, VERSION_CODE))
    log("      别名: build/apk/%s（同一份内容的副本，供固定路径引用）" % APK_ALIAS)
    for line in r.stdout.splitlines():
        if "certificate DN" in line or "Signer #1" in line or "digest" in line:
            log("      " + line.strip())


if __name__ == "__main__":
    main()

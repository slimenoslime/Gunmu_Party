#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打一个双击即跑的桌面端 fat jar（Windows / macOS / Linux 通用）。

之所以要 fat jar 而不是 `gradle run`：
  分发时对方机器上没有 Gradle、没有 JDK 依赖清单，双击就能玩是最低门槛。
  fat jar 把所有 class 与原生库（.dll/.so/.dylib）都塞进同一个 jar，
  libGDX 的 SharedLibraryLoader 会自动从 jar 里解出来加载。

  注意：工程名 "gunmu party" 带空格，javac 的 @argfile 遇上含空格的绝对路径会被
  拆碎，所以这里统一走 Windows 8.3 短路径。

用法：
    python tools/build_jar.py
    python tools/build_jar.py --out dist/滚木派对.jar

产物：dist/gunmu-party-<版本>.jar（例如 dist/gunmu-party-a0.0.1.jar）
      另存一份不带版本名的 dist/gunmu-party.jar 作为"当前最新"的别名。
      版本号取自 GameConfig.VERSION，唯一读取入口是 tools/version.py。
"""

import argparse
import io
import os
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

STAGE = "build/fatjar"
MAIN_CLASS = "top.gunmu.party.lwjgl3.Lwjgl3Launcher"

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME") or "",
    "C:/Program Files/BellSoft/LibericaJDK-21",
    "C:/Program Files/Java/jdk-21",
]

# 打进 fat jar 的 jar（相对路径）。必须含 gdx-jnigen-loader，
# 否则运行期 GdxNativesLoader 找不到 SharedLibraryLoader。
FAT_JARS = [
    "libs/gdx-1.13.5.jar",
    "libs/gdx-jnigen-loader-2.5.2.jar",
    "libs/gdx-freetype-1.13.5.jar",
    "libs/gdx-backend-lwjgl3-1.13.5.jar",
    "libs/gdx-platform-1.13.5-natives-desktop.jar",
    "libs/gdx-freetype-platform-1.13.5-natives-desktop.jar",
    "libs/lwjgl-3.3.3.jar",
    "libs/lwjgl-3.3.3-natives-windows.jar",
    "libs/lwjgl-glfw-3.3.3.jar",
    "libs/lwjgl-glfw-3.3.3-natives-windows.jar",
    "libs/lwjgl-jemalloc-3.3.3.jar",
    "libs/lwjgl-jemalloc-3.3.3-natives-windows.jar",
    "libs/lwjgl-openal-3.3.3.jar",
    "libs/lwjgl-openal-3.3.3-natives-windows.jar",
    "libs/lwjgl-opengl-3.3.3.jar",
    "libs/lwjgl-opengl-3.3.3-natives-windows.jar",
    "libs/lwjgl-stb-3.3.3.jar",
    "libs/lwjgl-stb-3.3.3-natives-windows.jar",
    "libs/jlayer-1.0.1-gdx.jar",
    "libs/jorbis-0.0.17.jar",
]

# 必须在成品里出现的类，缺一个就是依赖抄漏了
MUST_HAVE = [
    "com/badlogic/gdx/utils/SharedLibraryLoader.class",
    "com/badlogic/gdx/utils/GdxNativesLoader.class",
    "com/badlogic/gdx/backends/lwjgl3/Lwjgl3Application.class",
    "top/gunmu/party/GunmuPartyGame.class",
    "top/gunmu/party/lwjgl3/Lwjgl3Launcher.class",
]


def short_path(p):
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


def log(m):
    sys.stdout.write(m + "\n")
    sys.stdout.flush()


def rm_rf(path):
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


def run(cmd, **kw):
    kw.setdefault("cwd", ROOT)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", **kw)
    if r.returncode != 0:
        sys.stderr.write("命令失败(%d): %s\n" % (r.returncode, " ".join(map(str, cmd))))
        sys.stderr.write(r.stdout + "\n" + r.stderr + "\n")
        raise SystemExit(1)
    return r


def copy_file(src, dst):
    """不用 shutil.copyfile —— 有的受限环境会拦截 shutil 里的写操作。"""
    with open(src, "rb") as f, open(dst, "wb") as g:
        while True:
            buf = f.read(1 << 20)
            if not buf:
                break
            g.write(buf)


def find_jdk():
    for c in JDK_CANDIDATES:
        if c and os.path.exists(os.path.join(c, "bin", "javac.exe")):
            return c.replace("\\", "/").rstrip("/")
    raise SystemExit("找不到 JDK（需要 javac）。用 JAVA_HOME 指一下。")


def main():
    ap = argparse.ArgumentParser()
    # 版本号取自 GameConfig.VERSION（唯一入口 tools/version.py），产物名带版本；
    # 打完再落一份不带版本名的 dist/gunmu-party.jar 作为"当前最新"的别名。
    ap.add_argument("--out", default="dist/%s.jar" % VERSION_TAG)
    ap.add_argument("--alias", default="dist/gunmu-party.jar")
    args = ap.parse_args()

    jdk = find_jdk()
    exe = ".exe" if os.name == "nt" else ""
    javac = os.path.join(jdk, "bin", "javac" + exe)
    jar_tool = os.path.join(jdk, "bin", "jar" + exe)
    S = short_path(ROOT)
    W = S + "/" + STAGE

    for j in FAT_JARS:
        if not os.path.exists(os.path.join(ROOT, j)):
            raise SystemExit("缺少依赖 %s —— 先跑 python tools/fetch_libs.py" % j)

    log("JDK        : " + jdk)
    log("工程短路径 : " + S)
    log("版本       : %s (code %s)" % (VERSION_NAME, VERSION_CODE))
    check_sync(log)

    rm_rf(os.path.join(ROOT, STAGE))
    os.makedirs(os.path.join(ROOT, STAGE, "classes"), exist_ok=True)

    # ---------------------------------------------------------------- 编译
    classpath = ";".join([S + "/" + j for j in FAT_JARS])
    lines = []
    for srcdir in ("core/src", "lwjgl3/src"):
        for dirpath, _, names in os.walk(os.path.join(ROOT, srcdir)):
            for n in sorted(names):
                if n.endswith(".java"):
                    lines.append(S + "/" + os.path.relpath(
                        os.path.join(dirpath, n), ROOT).replace("\\", "/"))
    argfile = os.path.join(ROOT, STAGE, "sources.txt")
    with io.open(argfile, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    run([javac, "-encoding", "UTF-8", "-nowarn", "-source", "17", "-target", "17",
         "-d", W + "/classes", "-cp", classpath, "@" + W + "/sources.txt"])
    log("[1/3] 编译完成 (%d 个源文件)" % len(lines))

    # ---------------------------------------------------------------- 合并
    stage = os.path.join(ROOT, STAGE, "fat")
    os.makedirs(stage, exist_ok=True)
    for j in FAT_JARS:
        with zipfile.ZipFile(os.path.join(ROOT, j)) as z:
            for item in z.infolist():
                if item.is_dir():
                    continue
                name = item.filename
                if name.startswith("META-INF/") and name.endswith(
                        (".SF", ".RSA", ".DSA", ".EC")) or name == "module-info.class":
                    continue
                # 各依赖自带的 MANIFEST.MF 一律不要：`jar cfe` 会自己生成正确的那个，
                # 混进去反而可能把 Main-Class 覆盖掉。
                if name == "META-INF/MANIFEST.MF":
                    continue
                if name.startswith("META-INF/") \
                        and name.split("/")[-1] in ("LICENSE", "NOTICE", "DEPENDENCIES",
                                                    "INDEX.LIST"):
                    continue
                dest = os.path.join(stage, name)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(z.read(name))
    # 自己的 class 放最后，避免被第三方同名类覆盖
    classes = os.path.join(ROOT, STAGE, "classes")
    for dirpath, _, names in os.walk(classes):
        for n in names:
            if not n.endswith(".class"):
                continue
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, classes)
            dest = os.path.join(stage, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(full, "rb") as f, open(dest, "wb") as g:
                g.write(f.read())
    mf = os.path.join(stage, "META-INF", "MANIFEST.MF")
    if os.path.exists(mf):
        try:
            os.remove(mf)
        except OSError:
            pass

    # assets 也要塞进 jar 根：桌面端的 FileType.Internal 在文件不存在时会回落到
    # classpath 资源，所以字体放进去之后，jar 拷到任何目录双击都能跑。
    assets = os.path.join(ROOT, "assets")
    copied = 0
    for dirpath, _, names in os.walk(assets):
        for n in names:
            # 仓库里的说明文档不打进成品（charset.txt 是运行时资源，要留）
            if n.lower().endswith(".md") or n.lower() == "readme.txt":
                continue
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, assets)
            dest = os.path.join(stage, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(full, "rb") as f, open(dest, "wb") as g:
                g.write(f.read())
            copied += 1
    log("[2/3] 合并依赖完成（含 %d 个 assets 文件）" % copied)

    # ---------------------------------------------------------------- 打包
    out = os.path.join(ROOT, args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    # 先落在无空格的工作区里，再用普通文件拷贝搬到最终位置。
    # 直接对输出路径取短路径是错的：GetShortPathNameW 会把已有的
    # "gunmu-party.jar" 变成 "GUNMU-~1.JAR"，产物文件名就变了。
    staged = os.path.join(ROOT, STAGE, "out.jar")
    run([jar_tool, "cfe", W + "/out.jar", MAIN_CLASS, "-C", W + "/fat", "."])
    copy_file(staged, out)

    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        missing = [c for c in MUST_HAVE if c not in names]
        dlls = sum(1 for n in names if n.endswith((".dll", ".so", ".dylib")))
    if missing:
        sys.stderr.write("成品里缺少关键类：\n  " + "\n  ".join(missing) + "\n")
        raise SystemExit(1)

    log("[3/3] 打包完成")
    if args.alias and os.path.normpath(args.alias) != os.path.normpath(args.out):
        copy_file(out, os.path.join(ROOT, args.alias))
    log("")
    log("产物: %s  (%.1f MB, 原生库 %d 个)   [版本 %s · code %s]"
        % (args.out, os.path.getsize(out) / 1048576.0, dlls,
           VERSION_NAME, VERSION_CODE))
    if args.alias:
        log("      别名: %s（同一份内容的副本，供固定路径引用）" % args.alias)
    log("运行: java -jar %s" % args.out)


if __name__ == "__main__":
    main()

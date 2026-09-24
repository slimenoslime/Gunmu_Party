#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""无窗口冒烟测试：编译 core 并跑完整局淘汰赛。

不需要 OpenGL、不需要显示器、不需要 Gradle。它验证两件事：
  1. 10 张地图全部构建通过 MapValidator 的硬断言；
  2. 一整局 5 关淘汰赛能从 30 根跑到冠军，且道具/存档点/决战技链路真的被触发。

工程路径含空格会让 javac 的 @argfile 失败，所以这里同样走 8.3 短路径。

用法：
    python tools/run_smoke.py                 # 随机种子
    python tools/run_smoke.py 20260921        # 固定种子（回归用）
    python tools/run_smoke.py --quiet 20260921
"""

import io
import os
import subprocess
import sys

# 中文 Windows 的控制台默认是 GBK，直接 print 中文会抛 UnicodeEncodeError。
# 所有脚本统一把标准输出切到 UTF-8（失败就算了，不影响逻辑）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = "build/smoke-classes"
MAIN = "top.gunmu.party.tools.SmokeTest"

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME") or "",
    "C:/Program Files/BellSoft/LibericaJDK-21",
    "C:/Program Files/Java/jdk-21",
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


def find_jdk():
    for c in JDK_CANDIDATES:
        if c and os.path.exists(os.path.join(c, "bin", "javac.exe")):
            return c.replace("\\", "/").rstrip("/")
    raise SystemExit("找不到 JDK（需要 javac 与 java）。")


def jar_cp(short_root, dirs=("libs",)):
    out = []
    for d in dirs:
        full = os.path.join(ROOT, d)
        if not os.path.isdir(full):
            continue
        for n in sorted(os.listdir(full)):
            if n.endswith(".jar"):
                out.append(short_root + "/" + d + "/" + n)
    return out


# ====================================================================== 源码检查
#
# 为什么要有这一步（真事故）：
#   `MapDef.route` 只有竞速图才有 —— 生存图/竞技图没有折线路径。
#   诊断行里写过一句裸的 `m.route.totalLength`，一进「涨水盆地」就
#        NullPointerException: Cannot read field "totalLength" because route is null
#   把整个进程干掉。而这类代码在 Screen 里，**开窗口才能跑到**，单元测试够不着。
#
# 所以加一条源码级检查：任何 `.route.` 解引用都必须自证安全 ——
# 要么同一行上有 hasRoute()/routeLength()/null 判断，要么挂 `// route-ok: 原因`。
# 允许清单里的文件是"建路径 / 校验路径 / 测试路径"的地方，它们本来就是在直接操作 route。

ROUTE_LINT_ALLOW = (
    "map/Route.java",
    "map/MapDef.java",
    "map/MapRegistry.java",
    "map/TerrainBuilder.java",
    "map/MapValidator.java",
    "map/TrackProgress.java",
    "map/MapNav.java",
    "tools/",
)
ROUTE_LINT_GUARDS = ("hasRoute(", "routeLength(", "!= null", "== null", "route-ok")


def lint_route_derefs():
    """返回 (检查过的行数, 违规列表[(文件, 行号, 内容)])。"""
    checked = 0
    bad = []
    roots = ["core/src", "lwjgl3/src", "android/src"]
    for root in roots:
        base = os.path.join(ROOT, root)
        if not os.path.isdir(base):
            continue
        for dirpath, _, names in os.walk(base):
            for n in sorted(names):
                if not n.endswith(".java"):
                    continue
                full = os.path.join(dirpath, n)
                rel = os.path.relpath(full, ROOT).replace("\\", "/")
                if any(d in rel for d in ROUTE_LINT_ALLOW):
                    continue
                for i, line in enumerate(io.open(full, encoding="utf-8"), 1):
                    if ".route." not in line:
                        continue
                    checked += 1
                    if any(g in line for g in ROUTE_LINT_GUARDS):
                        continue
                    bad.append((rel, i, line.strip()))
    return checked, bad


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    seed = args[0] if args else "20260921"

    checked, bad = lint_route_derefs()
    if bad:
        sys.stderr.write("route 解引用检查不通过（%d 处）：\n" % len(bad))
        for rel, ln, txt in bad:
            sys.stderr.write("  %s:%d  %s\n" % (rel, ln, txt))
        sys.stderr.write(
            "  说明：只有竞速图有 route；请改用 hasRoute()/routeLength()，"
            "或在行尾加 `// route-ok: 为什么安全`。\n")
        raise SystemExit("route 解引用检查失败")
    sys.stdout.write("route 解引用检查通过（扫了 %d 处，全部有守卫或标记）\n" % checked)

    jdk = find_jdk()
    exe = ".exe" if os.name == "nt" else ""
    javac = os.path.join(jdk, "bin", "javac" + exe)
    java = os.path.join(jdk, "bin", "java" + exe)
    S = short_path(ROOT)
    jars = jar_cp(S)

    if not jars:
        raise SystemExit("libs/ 里没有 jar —— 先跑 python tools/fetch_libs.py")

    os.makedirs(os.path.join(ROOT, OUT), exist_ok=True)

    sources = []
    for dirpath, _, names in os.walk(os.path.join(ROOT, "core", "src")):
        for n in sorted(names):
            if n.endswith(".java"):
                sources.append(S + "/" + os.path.relpath(
                    os.path.join(dirpath, n), ROOT).replace("\\", "/"))
    argfile = os.path.join(ROOT, "build", "smoke-sources.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("\n".join(sources) + "\n")

    r = subprocess.run([javac, "-encoding", "UTF-8", "-nowarn",
                        "-source", "17", "-target", "17",
                        "-d", S + "/" + OUT, "-cp", ";".join(jars),
                        "@" + S + "/build/smoke-sources.txt"],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", cwd=ROOT)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("编译失败")
    sys.stdout.write("编译完成（%d 个源文件）\n\n" % len(sources))

    cp = ";".join([os.path.join(ROOT, OUT)] + jars)
    sys.stdout.flush()
    r = subprocess.run([java,
                        "-Dfile.encoding=UTF-8",
                        # Java 18+ 的 stdout.encoding 默认跟随控制台代码页，
                        # 重定向到管道时会变成 ms936，中文就乱码了。
                        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                        "-cp", cp, MAIN, seed],
                       cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    sys.stdout.write(r.stdout)
    if r.stderr:
        sys.stderr.write(r.stderr)
    raise SystemExit(r.returncode)


if __name__ == "__main__":
    main()

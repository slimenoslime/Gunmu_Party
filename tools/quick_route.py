# -*- coding: utf-8 -*-
"""只编译 core 并单跑指定的无头测试 —— 调地图/平衡时用。

整包冒烟要跑完整局淘汰赛（约十分钟），而调坐标只需要"重叠审计"这一个结论。
这里复用 run_smoke.py 已经编译好的 classes 目录，重新编译 core 后跑一个测试类。

用法：
    python tools/quick_route.py                                  # 默认跑重叠审计
    python tools/quick_route.py top.gunmu.party.tools.RaceRunTest
"""
import io
import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = "build/smoke-classes"
JDK = r"C:/Program Files/BellSoft/LibericaJDK-21/bin"

# 跑哪个测试（带包名的类名）
TARGET = (sys.argv[1] if len(sys.argv) > 1
          else "top.gunmu.party.tools.RouteOverlapTest")


def collect_sources():
    """core 下全部源码。

    只重编"改过的那几个文件"看起来更快，但 javac 不会沿依赖链自动重编
    （没给 -sourcepath），于是改了 Roll.java 却不重编，跑出来还是旧行为 —— 这种
    "测了个寂寞"最耽误时间。全量编译约 40 秒，比整包冒烟快一个数量级，值得。
    """
    out = []
    for dirpath, _, names in os.walk("core/src"):
        for n in sorted(names):
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n).replace(os.sep, "/"))
    return out


def main():
    # 全程用相对路径：工程目录名 "gunmu party" 含空格，绝对路径传给 javac 会被拆碎。
    # 不用 Windows 8.3 短路径 —— 那会把 .java 压成 .JAV，javac 认不出扩展名。
    jars = ["libs/" + n for n in sorted(os.listdir("libs")) if n.endswith(".jar")]
    cp = ";".join([OUT] + jars)

    src = collect_sources()
    os.makedirs("build", exist_ok=True)
    argfile = "build/quick-src.txt"
    with io.open(argfile, "w", encoding="utf-8") as fp:
        fp.write("\n".join(src) + "\n")

    args = [os.path.join(JDK, "javac.exe"), "-encoding", "UTF-8", "-nowarn",
            "-source", "17", "-target", "17", "-d", OUT, "-cp", cp,
            "@" + argfile]
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", cwd=ROOT)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + "\n" + r.stderr + "\n")
        raise SystemExit("编译失败")

    r = subprocess.run(
        [os.path.join(JDK, "java.exe"), "-Dfile.encoding=UTF-8",
         "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
         "-cp", cp, TARGET],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    sys.stdout.write(r.stdout)
    sys.stderr.write(r.stderr)
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())

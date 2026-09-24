#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Maven 的 natives 构件里把 .so 解到 android/libs/<abi>/，不依赖 Gradle。

libGDX 把各平台的原生库放在单独的 classifier 构件里：
    com.badlogicgames.gdx:gdx-platform:<v>:natives-<abi>
    com.badlogicgames.gdx:gdx-freetype-platform:<v>:natives-<abi>
每个 jar 里的路径形如 lib/<abi>/libgdx.so。

用法：
    python tools/fetch_natives.py
    python tools/fetch_natives.py --abi arm64-v8a
"""

import argparse
import io
import os
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
VERSION = "1.13.5"
REPO = "https://repo1.maven.org/maven2"
ALL_ABIS = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"]
ARTIFACTS = ["gdx-platform", "gdx-freetype-platform"]


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    sys.stdout.write("  下载 %s\n" % os.path.basename(dest))
    with urllib.request.urlopen(url, timeout=180) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", action="append", default=None)
    args = ap.parse_args()
    abis = args.abi or ALL_ABIS

    cache = os.path.join(ROOT, "build", "natives-cache")
    out_root = os.path.join(ROOT, "android", "libs")
    total = 0

    for art in ARTIFACTS:
        for abi in abis:
            name = "%s-%s-natives-%s.jar" % (art, VERSION, abi)
            url = "%s/com/badlogicgames/gdx/%s/%s/%s" % (REPO, art, VERSION, name)
            jar = fetch(url, os.path.join(cache, name))
            dest = os.path.join(out_root, abi)
            os.makedirs(dest, exist_ok=True)
            got = 0
            with zipfile.ZipFile(jar) as z:
                for entry in z.namelist():
                    if not entry.endswith(".so"):
                        continue
                    data = z.read(entry)
                    target = os.path.join(dest, os.path.basename(entry))
                    with open(target, "wb") as f:
                        f.write(data)
                    got += 1
                    total += 1
            sys.stdout.write("  %s / %s -> %d 个 .so\n" % (art, abi, got))

    sys.stdout.write("\n共 %d 个 .so，位于 android/libs/<abi>/\n" % total)


if __name__ == "__main__":
    main()

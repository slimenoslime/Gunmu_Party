#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从源码里扫出全部会被渲染的字符，裁出一份极小的中文字体放进 assets/fonts/game.ttf。

为什么要这么做（docs/05 §5.3）：
  * 桌面端可以借系统字体，Android 端系统字体路径各机型不一，靠不住；
  * 整份 Noto Sans SC 有 8 MB，直接塞进 assets 太重；
  * 本作 UI 用到的汉字是有限集合（几百个），裁完只剩几十 KB，两端表现完全一致。

用法：
    python tools/make_font.py                 # 用默认源字体
    python tools/make_font.py <源字体路径>

源字体默认找 build/fontsrc/NotoSansSC-Regular.otf；找不到就退回系统黑体，
但系统字体**不可再分发**，只建议本地开发时用。
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
OUT = os.path.join(ROOT, "assets", "fonts", "game.ttf")
# 与字体同源的字形清单。运行时 FreeType 只烘焙这份清单里的字，
# 保证"烘焙集"与"字体实际覆盖集"永远一致——否则某个字漏了就是一片空白。
OUT_CHARSET = os.path.join(ROOT, "assets", "fonts", "charset.txt")
SRC_CANDIDATES = [
    os.path.join(ROOT, "build", "fontsrc", "NotoSansSC-Regular.otf"),
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "C:/Windows/Fonts/simhei.ttf",
]

SCAN_DIRS = ["core/src", "lwjgl3/src", "android/src"]

# 强制包含：ASCII 可打印 + 中日韩标点 + 全角形式 + 常用符号
ALWAYS = (
    "".join(chr(c) for c in range(0x20, 0x7F))
    + "".join(chr(c) for c in range(0x3000, 0x3040))
    + "".join(chr(c) for c in range(0xFF00, 0xFF61))
    + "·—…×÷±°←→↑↓■□●○◆◇★☆▲▼"
)

# 只收这些区段的非 ASCII 字符，避免把源码里的杂字符也拉进来
def interesting(ch):
    o = ord(ch)
    return (
        0x4E00 <= o <= 0x9FFF      # 中日韩统一表意
        or 0x3400 <= o <= 0x4DBF   # 扩展 A
        or 0xF900 <= o <= 0xFAFF   # 兼容表意
        or 0x2E80 <= o <= 0x2FDF   # 部首
    )


def scan_chars():
    found = set(ALWAYS)
    files = 0
    for d in SCAN_DIRS:
        base = os.path.join(ROOT, d)
        for dirpath, _, names in os.walk(base):
            for n in names:
                if not n.endswith(".java"):
                    continue
                files += 1
                p = os.path.join(dirpath, n)
                with io.open(p, encoding="utf-8", errors="replace") as f:
                    for ch in f.read():
                        if interesting(ch):
                            found.add(ch)
    # 玩家/滚木名字池、道具与决战技文案都在源码里，已经被上面覆盖
    return found, files


def pick_source(explicit):
    if explicit:
        if not os.path.exists(explicit):
            sys.exit("源字体不存在: " + explicit)
        return explicit
    for p in SRC_CANDIDATES:
        if os.path.exists(p):
            return p
    sys.exit("找不到源字体。请把 Noto Sans SC 放到 build/fontsrc/NotoSansSC-Regular.otf，"
             "或作为参数传入路径。")


def main():
    src = pick_source(sys.argv[1] if len(sys.argv) > 1 else None)
    chars, files = scan_chars()
    text = "".join(sorted(chars))

    tmp = os.path.join(ROOT, "build", "font-charset.txt")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    with io.open(tmp, "w", encoding="utf-8") as f:
        f.write(text)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # 同一份清单也留给运行时用（Fonts.load 会优先读它）
    with io.open(OUT_CHARSET, "w", encoding="utf-8") as f:
        f.write(text)
    cmd = [
        sys.executable, "-m", "fontTools.subset", src,
        "--text-file=" + tmp,
        "--output-file=" + OUT,
        "--layout-features=*",
        "--no-hinting",
        "--desubroutinize",
        "--name-IDs=1,2,3,4,6",
        "--drop-tables+=DSIG",
        "--recalc-bounds",
        "--flavor=",           # 输出裸 TTF/OTF，不要 woff
    ]
    cmd = [c for c in cmd if c != "--flavor="]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + "\n" + r.stderr + "\n")
        sys.exit("字体裁剪失败")

    size = os.path.getsize(OUT)
    print("源字体   : %s (%.1f MB)" % (src, os.path.getsize(src) / 1048576.0))
    print("扫描文件 : %d 个 .java" % files)
    print("字形数   : %d" % len(chars))
    print("输出     : %s (%.1f KB)" % (OUT, size / 1024.0))
    print("字形清单 : %s" % OUT_CHARSET)


if __name__ == "__main__":
    main()

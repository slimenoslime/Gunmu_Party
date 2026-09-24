#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读一张游戏截图，用像素统计判断"画面到底有没有画出东西"。

为什么需要它：看不了图的时候，"地面被背面剔除"和"地面正常"在日志里完全一样 ——
编译通过、冒烟测试全绿、帧率正常，唯一区别是画面里 90% 的像素是清屏色。

这个脚本不依赖 PIL（环境里装不上），自己解 PNG。判断依据：

  * 清屏色占比     地面画出来时应该很低；整片被剔除时会接近 100%
  * 下半屏非背景率 玩家脚下的地面必须占住下半屏
  * 颜色种类数     退化几何 / 碎图会让颜色分布异常

用法：
    python tools/check_shot.py build/s5/shot-0.png [清屏色R,G,B]
清屏色默认 92,112,143（= GameConfig 里 LevelScreen 的 0.36/0.44/0.56）。
"""

import os
import struct
import sys
import zlib

sys.stdout.reconfigure(encoding="utf-8", errors="replace") if hasattr(
    sys.stdout, "reconfigure") else None

CLEAR = (92, 112, 143)


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("不是 PNG: " + path)

    pos = 8
    width = height = None
    bit_depth = color_type = None
    idat = bytearray()
    palette = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"PLTE":
            palette = chunk
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break

    if bit_depth != 8:
        raise SystemExit("只支持 8 位深度，实际 %d" % bit_depth)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    raw = zlib.decompress(bytes(idat))

    stride = width * channels
    out = bytearray(stride * height)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    if color_type == 3:
        rgb = bytearray(width * height * 3)
        for i in range(width * height):
            idx = out[i] * 3
            rgb[i * 3:i * 3 + 3] = palette[idx:idx + 3]
        return width, height, 3, rgb
    return width, height, channels, out


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    clear = CLEAR
    if len(sys.argv) > 2:
        clear = tuple(int(x) for x in sys.argv[2].split(","))

    w, h, ch, px = read_png(path)
    total = w * h
    clear_n = 0
    colors = set()
    lower_total = 0
    lower_nonclear = 0
    for y in range(h):
        lower = y >= h // 2
        row = y * w
        for x in range(w):
            o = (row + x) * ch
            r, g, b = px[o], px[o + 1], px[o + 2]
            if len(colors) < 20000:
                colors.add((r, g, b))
            is_clear = (abs(r - clear[0]) <= 6 and abs(g - clear[1]) <= 6
                        and abs(b - clear[2]) <= 6)
            if is_clear:
                clear_n += 1
            if lower:
                lower_total += 1
                if not is_clear:
                    lower_nonclear += 1

    clear_ratio = clear_n / total
    lower_ratio = lower_nonclear / max(1, lower_total)
    print("图像      : %dx%d" % (w, h))
    print("清屏色占比: %.1f%%" % (clear_ratio * 100))
    print("下半屏非背景: %.1f%%" % (lower_ratio * 100))
    print("颜色种类  : %d" % len(colors))

    ok = True
    if clear_ratio > 0.55:
        print("  [!!] 画面过半是背景色 —— 地形很可能整片没画出来（背面剔除/绕序）")
        ok = False
    if lower_ratio < 0.30:
        print("  [!!] 下半屏几乎空着 —— 玩家脚下没有地面")
        ok = False
    if len(colors) < 40:
        print("  [!!] 颜色种类过少 —— 几何异常")
        ok = False
    print("结论      : " + ("画面正常" if ok else "画面有问题"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

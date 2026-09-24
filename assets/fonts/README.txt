中文字体说明
============

本目录里的两个文件是**构建产物**，但它们同时是**必须入库的源码资产**
（否则没装 fonttools 的人 clone 下来直接就是一片空白字）。

  game.ttf      裁剪后的中文字体，260 KB / 1262 个字形
  charset.txt   与 game.ttf 同源的字形清单，运行时按它烘焙

怎么生成的
----------
    python tools/make_font.py

它做三件事：

  1. 扫 core/src、lwjgl3/src、android/src 下**全部 .java 源文件**，
     取出用到的每一个 CJK 字符（加上完整 ASCII、CJK 标点、全角形式）；
     数据还从 ItemType / UltimateType / Mode / TerrainType / 地图名的字段里收一遍。
  2. 用 pyftsubset 从源字体裁出这些字形（需要 pip install fonttools）。
  3. 同时写出 charset.txt。

源字体默认找 build/fontsrc/NotoSansSC-Regular.otf（Noto Sans SC，SIL OFL，可再分发）。
也可以作为参数传路径：python tools/make_font.py <字体路径>。

为什么不让程序自己去系统里找
----------------------------
Android 各厂商 ROM 的 /system/fonts 路径与可读性都不一致；Windows 上 simhei.ttf
也有可能被精简掉；macOS 是 .ttc 集合。随包带一份才能做到"任何机器上表现完全一致"。

回退链仍然保留（Fonts.java），只是正常路径上不会用到：
  fonts/game.ttf → Windows 系统字体 → macOS 系统字体 → Linux 系统字体
  → Android /system/fonts/* → ASCII 兜底（不会崩，只是文案变英文）

为什么是 charset.txt 而不是在代码里维护字符表
--------------------------------------------
代码里硬编码一份字符表必然漏字，而漏字的后果是那个字渲染成**空白**，
很难一眼看出来。让"扫描源码"和"裁剪字体"用同一份结果，
运行时再读同一个文件去烘焙，三处就永远不会漂移。

新增文案后必须重跑
------------------
改了任何中文文案（道具描述、技能说明、UI 提示、地图名……）都要：

    python tools/make_font.py
    python tools/build_jar.py     # 桌面
    python tools/build_apk.py     # 安卓

否则新出现的字在界面上是空白。这条已经写进 docs/05 §5.3 与决策 D30。

不要做的事
----------
请勿把 Windows / macOS 的系统字体（simhei.ttf、msyh.ttc、PingFang.ttc……）
打进仓库或 APK —— 那些授权不允许再分发。
可再分发的选择：Noto Sans SC / 思源黑体（SIL OFL）、更纱黑体（SIL OFL）、
阿里巴巴普惠体、HarmonyOS Sans SC。

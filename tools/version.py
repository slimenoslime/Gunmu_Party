#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本号的**唯一读取入口**（tools/build_apk.py 与 tools/build_jar.py 共用）。

版本号只写在 `core/src/main/java/top/gunmu/party/GameConfig.java` 的
`VERSION` / `VERSION_CODE` 里。命名规范（用户定的）：

    a 前缀 = alpha / 内测版        b 前缀 = beta / 测试版        v 前缀 = release 正式版

为什么从源码读：本工程的构建**不经过 Gradle**（受限环境里起不来，见 D31），
没有"一处配置两边生效"的机制 —— 要么读源码，要么迟早漂移成
"APK 里写着 1.0.0、游戏大厅里写着 a0.0.1"。

⚠️ 读不到就**抛异常**，绝不回退到某个默认值：静默降级意味着打出一个版本号错误的包，
而版本号错了，内测玩家报的每一个 bug 都无法定位到具体构建。
"""

import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_JAVA = os.path.join(ROOT, "core", "src", "main", "java", "top", "gunmu",
                           "party", "GameConfig.java")


def read_version():
    """返回 (VERSION 字符串, VERSION_CODE 字符串)。读不到就抛。"""
    if not os.path.exists(CONFIG_JAVA):
        raise SystemExit("找不到 GameConfig.java：%s" % CONFIG_JAVA)
    text = io.open(CONFIG_JAVA, encoding="utf-8").read()
    m_name = re.search(r'\bVERSION\b\s*=\s*"([^"]+)"', text)
    m_code = re.search(r'\bVERSION_CODE\b\s*=\s*(\d+)', text)
    if not m_name or not m_code:
        raise SystemExit(
            "在 %s 里读不到 VERSION / VERSION_CODE，构建中止（不猜版本号）。"
            % os.path.relpath(CONFIG_JAVA, ROOT))
    return m_name.group(1), m_code.group(1)


VERSION_NAME, VERSION_CODE = read_version()
#: 产物名里的版本标签，例如 `gunmu-party-a0.0.1`
VERSION_TAG = "gunmu-party-" + VERSION_NAME


def check_sync(say=print):
    """核对 Gradle 配置里的版本有没有跟 GameConfig 漂移，不一致就**告警**。

    Gradle 在本机受限环境里起不来（D31），日常走 `tools/build_*.py`，所以这里不做
    硬失败 —— 但两边版本号不一致意味着"用 Gradle 打出来的包版本号是错的"，
    那不是能悄悄放过去的事，必须每次都提示。
    """
    problems = []
    root_gradle = os.path.join(ROOT, "build.gradle")
    if os.path.exists(root_gradle):
        text = io.open(root_gradle, encoding="utf-8").read()
        m = re.search(r"^\s*version\s*=\s*'([^']+)'", text, re.M)
        if m and m.group(1) != VERSION_NAME:
            problems.append("build.gradle 的 version = '%s' 与 GameConfig.VERSION = '%s' 不一致"
                            % (m.group(1), VERSION_NAME))
    android_gradle = os.path.join(ROOT, "android", "build.gradle")
    if os.path.exists(android_gradle):
        text = io.open(android_gradle, encoding="utf-8").read()
        m = re.search(r"^\s*versionCode\s+(\d+)", text, re.M)
        if m and int(m.group(1)) != int(VERSION_CODE):
            problems.append("android/build.gradle 的 versionCode %s 与 GameConfig.VERSION_CODE %s 不一致"
                            % (m.group(1), VERSION_CODE))
    for p in problems:
        say("[版本告警] " + p)
    return not problems


def bind(obj):
    """把版本相关的属性挂到某个模块（便于调用方少写一行 import）。"""
    obj.VERSION_NAME = VERSION_NAME
    obj.VERSION_CODE = VERSION_CODE
    obj.VERSION_TAG = VERSION_TAG
    return obj

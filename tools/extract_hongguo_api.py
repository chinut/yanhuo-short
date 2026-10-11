# -*- coding: utf-8 -*-
"""抽 libduanju_core.so 里与「红果 App 接口 / 签名 / 媒体密钥」相关的字符串。

关注点：
  1. App 接口的路径（是 /api/app/... 之类，还是第三方中转）
  2. 签名需要的常量（app_id / 密钥 / 盐）
  3. 媒体密钥（红果的付费内容用 key 加密 HLS，拿到 key 才能解）

Go 的字符串表是「长度前缀 + 原始字节」，没有 NUL 终止，
所以这里用「可见字符连续段」切分，并把命中的前后文一起打出来 ——
名字被拼接（如 `methoddelta.ygrwdsgt.cc`）时靠上下文才能还原。
"""
from __future__ import annotations

import re

LIB = r"G:\百度下载神器\各种果\base\resources\lib\arm64-v8a\libduanju_core.so"
OUT = r"G:\android\AndroidGuo\tools\_duanju_hongguo.txt"

data = open(LIB, "rb").read()

# 可见 ASCII 连续段
ASCII_RUN = re.compile(rb"[\x20-\x7e]{6,400}")


def runs_near(keyword: bytes, before: int = 200, after: int = 400, limit: int = 6):
    """关键词附近的所有可见字符段。"""
    out = []
    idx = 0
    while len(out) < limit:
        i = data.find(keyword, idx)
        if i < 0:
            break
        idx = i + 1
        lo = max(0, i - before)
        hi = min(len(data), i + after)
        chunk = data[lo:hi]
        out.append((i, [m.group(0).decode("ascii", "ignore") for m in ASCII_RUN.finditer(chunk)]))
    return out


with open(OUT, "w", encoding="utf-8") as f:
    def w(s=""):
        f.write(s + "\n")

    # ---------- 1. 红果相关关键词的上下文 ----------
    for kw in (b"hongguo", b"series_id", b"vid_list", b"main_url",
               b"playlet", b"app_id", b"appId", b"sign", b"Sign",
               b"x-argus", b"X-Argus", b"device_id", b"deviceId",
               b"aes", b"key_uri", b"hls_key", b"media_key"):
        w("=" * 78)
        w(f"关键词 {kw.decode()}")
        w("=" * 78)
        hits = runs_near(kw, limit=5)
        if not hits:
            w("  （无）")
        for off, strs in hits:
            w(f"  @0x{off:x}")
            for s in strs[:14]:
                if len(s) >= 6:
                    w("     " + s)
        w()

    # ---------- 2. 所有 /api/ 开头的路径（App 接口的候选） ----------
    w("=" * 78)
    w("所有 /api/... 路径")
    w("=" * 78)
    paths = set()
    for m in re.finditer(rb"/api/[A-Za-z0-9_\-/\.%\{\}\$]{2,90}", data):
        paths.add(m.group(0).decode("ascii", "ignore"))
    for p in sorted(paths):
        w("  " + p)

    # ---------- 3. 疑似签名/密钥常量（32~64 位十六进制或 base64 风格） ----------
    w()
    w("=" * 78)
    w("疑似密钥/盐（16 进制 32~128 位）")
    w("=" * 78)
    hexes = set()
    for m in re.finditer(rb"\b[0-9a-fA-F]{32,128}\b", data):
        hexes.add(m.group(0).decode())
    for h in sorted(hexes)[:120]:
        w("  " + h)
    w(f"  （共 {len(hexes)} 个）")

print("已写入", OUT)

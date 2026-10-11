# -*- coding: utf-8 -*-
"""从 libduanju_core.so 抽取中文文案与上下文，用来反推它集成了哪些源。

Go 的字符串表是长度前缀 + 原始字节，没有 NUL 终止，所以按"可见字符连续段"
切分比按 NUL 切分更准。这里把每个候选串**连同前后各 60 字节**一起打印，
方便看出它原本属于哪一段（域名列表、错误文案、接口路径）。
"""
from __future__ import annotations

import re

LIB = r"G:\百度下载神器\各种果\base\resources\lib\arm64-v8a\libduanju_core.so"
OUT = r"G:\android\AndroidGuo\tools\_duanju_cn.txt"

data = open(LIB, "rb").read()
print(f"{LIB}  {len(data)/1024/1024:.2f} MB")

with open(OUT, "w", encoding="utf-8") as out:
    # ---------- 1. 所有含 "果" 的 UTF-8 汉字串 ----------
    # 汉字 = E4..E9 开头 + 2 个 80..BF 续字节
    han = rb"(?:[\xe4-\xe9][\x80-\xbf]{2})"
    pat = re.compile(han + rb"(?:" + han + rb"|[\x20-\x7e]){1,40}")
    seen = set()
    out.write("========== 含「果」的字符串 ==========\n")
    for m in pat.finditer(data):
        try:
            s = m.group(0).decode("utf-8")
        except UnicodeDecodeError:
            continue
        if "果" not in s or s in seen:
            continue
        seen.add(s)
        out.write("  " + s + "\n")
    print("含果串:", len(seen))

    # ---------- 2. 所有含"源 / 接口 / 平台"的句子（能看出它支持哪些源） ----------
    out.write("\n========== 含「源 / 接口 / 平台 / 短剧」的字符串 ==========\n")
    seen2 = set()
    for m in pat.finditer(data):
        try:
            s = m.group(0).decode("utf-8")
        except UnicodeDecodeError:
            continue
        if s in seen2:
            continue
        if re.search(r"[\u4e00-\u9fff]{4,}", s) and re.search(r"源|接口|平台|短剧", s):
            seen2.add(s)
            out.write("  " + s + "\n")
    print("含源/接口串:", len(seen2))

    # ---------- 3. 域名附近的原始上下文（看清完整域名） ----------
    out.write("\n========== 域名附近原始上下文 ==========\n")
    for kw in (b"hongguoduanju", b"qmplaylet", b"9ddm", b"huangju", b"duanju",
               b"xzyx168", b"whjzjx", b"chaojichaoji", b"buxefaex", b"fqnovel"):
        idx = 0
        hits = 0
        while hits < 3:
            i = data.find(kw, idx)
            if i < 0:
                break
            idx = i + 1
            hits += 1
            lo = max(0, i - 90)
            chunk = data[lo:i + 110]
            # 只保留可打印字符
            txt = "".join(chr(c) if 32 <= c < 127 else "." for c in chunk)
            out.write(f"  [{kw.decode()}] …{txt}…\n")
    print("域名上下文已写")

print("输出：", OUT)

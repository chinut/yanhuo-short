# -*- coding: utf-8 -*-
"""从 duanju App 的原生库里抽取接口线索（域名 / 路径 / 中文错误文案）。

这个 App 的数据层在 libduanju_core.so（Go 编译产物）里，字符串表是明文的，
所以可以直接看出它集成了哪些平台、走了哪些接口。

只做只读提取，用来判断"有没有比红果官网更好的源"。
"""
from __future__ import annotations

import os
import re
import sys

LIB = r"G:\百度下载神器\各种果\base\resources\lib\arm64-v8a"

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_duanju_extract.txt")

# 噪声过滤：这些是运行时/框架/编译器产物，不是业务域名
NOISE_PREFIX = (
    "android", "kotlin", "kotlinx", "google", "flutter", "schemas", "w3.",
    "example", "apache", "oracle", "json", "java", "xml", "dart", "plugins.",
    "github.com", "golang", "gopkg", "goog", "gstatic", "w3c", "ietf",
)
# 只保留"看起来像内容平台"的域名
KEEP = re.compile(
    r"(duanju|playlet|hongguo|huangju|huangguo|fqnovel|novel|drama|video|vod|"
    r"zjnovel|bytedance|byteimg|douyin|kuaishou|weibo|bilibili|qmplaylet|"
    r"9ddm|whjzjx|chaojichaoji|xzyx|buxefaex|guo|ju)",
    re.I,
)


def extract(path: str) -> None:
    if not os.path.exists(path):
        print(f"缺失：{path}")
        return
    data = open(path, "rb").read()
    name = os.path.basename(path)

    with open(OUT, "a", encoding="utf-8") as out:
        def w(s=""):
            out.write(s + "\n")

        w("=" * 78)
        w(f"文件 {name}  {len(data) / 1024 / 1024:.2f} MB")
        w("=" * 78)

        # ---------- 域名 ----------
        raw = re.findall(
            rb"[a-z0-9][a-z0-9\-]{0,40}(?:\.[a-z0-9\-]{1,40}){1,4}"
            rb"\.(?:com|cn|net|org|tv|app|xyz|top|cc|io|me|vip|site|fun|live|pro|info)",
            data,
        )
        hosts = set()
        for h in raw:
            s = h.decode("ascii", "ignore")
            low = s.lower()
            if any(low.startswith(p) for p in NOISE_PREFIX):
                continue
            # 只要"整串是一个合理域名"的：不能以点/连字符开头
            if s.startswith((".", "-")) or ".." in s:
                continue
            hosts.add(s)

        content_hosts = sorted(h for h in hosts if KEEP.search(h))
        other_hosts = sorted(h for h in hosts if not KEEP.search(h))

        w(f"\n---- 疑似内容平台域名（{len(content_hosts)} 个）----")
        for h in content_hosts:
            w("  " + h)
        w(f"\n---- 其它域名（{len(other_hosts)} 个，前 60）----")
        for h in other_hosts[:60]:
            w("  " + h)

        # ---------- URL 路径 ----------
        paths = re.findall(rb"/(?:api|v\d|vod|detail|series|play|search|book|drama)[a-zA-Z0-9_\-/\.]{2,60}", data)
        uniq_paths = sorted(set(p.decode("ascii", "ignore") for p in paths))
        w(f"\n---- 疑似接口路径（{len(uniq_paths)} 个，前 80）----")
        for p in uniq_paths[:80]:
            w("  " + p)

        # ---------- 中文错误文案（能反推它支持哪些源） ----------
        cn = re.findall(rb"(?:[\xe4-\xe9][\x80-\xbf]{2}|[\x20-\x7e]){4,60}", data)
        uni = set()
        for x in cn:
            try:
                s = x.decode("utf-8")
            except UnicodeDecodeError:
                continue
            # 至少 4 个连续汉字才算一句
            if re.search(r"[\u4e00-\u9fff]{4,}", s):
                uni.add(s.strip())
        guo = sorted(s for s in uni if "果" in s or "源" in s or "接口" in s)
        w(f"\n---- 含「果 / 源 / 接口」的中文文案（{len(guo)} 条，前 120）----")
        for s in guo[:120]:
            w("  " + s)


if __name__ == "__main__":
    if os.path.exists(OUT):
        os.remove(OUT)
    for f in ("libduanju_core.so", "libapp.so"):
        extract(os.path.join(LIB, f))
    print("已写入", OUT)

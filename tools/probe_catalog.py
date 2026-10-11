#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按上游 Go 源码给出的**真实路由**抓各站列表页，看两件事：
  1. 条目能不能解析出来（详情 id + 标题 + 集数线索）
  2. 列表页/详情页有没有"更新至N集 / 全N集 / 已完结"这类**集数线索**

路由表来源: src-hongguo/native/core/provider_duanju_maccms.go:251-296
—— 照抄，不猜。

用法: python probe_catalog.py
输出: tools/_catalog.txt
"""
import re
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

# (名, base, 列表页 URL 列表)  —— 路由抄自上游
CATALOGS = [
    ("网果",   "https://www.duanju2.com",
     ["/show/duanju-----------.html"]),
    ("伍果",   "https://www.duanju55.com",
     ["/", "/index.php/vod/type/id/1.html"]),
    ("花果",   "https://www.zywest263.com",
     ["/", "/index.php/vod/type/id/1.html"]),
    ("黄豆",   "https://tideember.cc",
     ["/", "/index.php/vod/type/id/1.html"]),
    ("归片",   "https://guipianwu.com",
     ["/", "/list/1.html"]),
    ("韩小圈", "https://www.jennyhow.com",
     ["/", "/hanxiaoquan/1.html"]),
    ("皮果",   "https://ptt.red",
     ["/", "/p/66/c/1"]),
    ("DSD",    "https://www.dsd.com.se",
     ["/", "/index.php/vod/type/id/1.html"]),
]

ID_PATS = [
    r"/index\.php/vod/detail/id/(\d+)\.html",
    r"/vod/(\d+)\.html",
    r"/nv/(\d+)\.html",
    r"/hanxiaoquan/(\d+)\.html",
    r"/hxq/(\d+)\.html",
    r"/p/66/d/(\d+)",
]

# 集数线索
COUNT_PATS = [
    (r"更新至\s*(\d{1,4})\s*集", "更新至"),
    (r"全\s*(\d{1,4})\s*集", "全集"),
    (r"共\s*(\d{1,4})\s*集", "共"),
    (r"已完结\s*(\d{1,4})", "完结"),
    (r"第\s*(\d{1,4})\s*集", "第N集"),
    (r"(\d{1,4})\s*集全", "N集全"),
]


def get(url, limit=900_000, timeout=22):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept": "text/html,application/json,*/*",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return r.status, r.read(limit)
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception:
        return None, b""


def analyze(txt):
    ids = []
    for p in ID_PATS:
        ids += re.findall(p, txt)
    counts = []
    for pat, label in COUNT_PATS:
        for m in re.findall(pat, txt)[:200]:
            try:
                counts.append((label, int(m)))
            except ValueError:
                pass
    # 标题（找 alt= 或 class=xxxName 的文本）
    titles = re.findall(r'alt="([^"]{2,40})"', txt)[:6]
    return ids, counts, titles


def main():
    lines = []
    for name, base, paths in CATALOGS:
        print(f"\n### {name}  {base}")
        best = None
        for path in paths:
            u = base + path
            st, body = get(u)
            txt = body.decode("utf-8", "replace")
            ids, counts, titles = analyze(txt)
            print(f"  {st} {len(body):>7}B  {u}")
            if not body:
                continue
            if ids:
                from collections import Counter
                top = Counter(ids).most_common(3)
                print(f"     详情 id 形态命中 {len(ids)} 个，样本 {top}")
                print(f"     标题样本: {titles[:4]}")
                if counts:
                    mx = max(c for _, c in counts)
                    dist = {}
                    for lb, c in counts:
                        dist[lb] = dist.get(lb, 0) + 1
                    print(f"     ★ 集数线索 {len(counts)} 处，最大 {mx}，种类 {dist}")
                    print(f"       样例: {sorted(set(counts), key=lambda x: -x[1])[:6]}")
                else:
                    print("     集数线索: 无")
                best = (u, len(ids), counts, titles)
                break
            else:
                print("     没解析出详情 id")
        if best:
            lines.append(f"{name:<8} {best[0]:<52} id命中={best[1]:<5} "
                         f"集数线索={len(best[2])} 标题样本={best[3][:2]}")
        else:
            lines.append(f"{name:<8} {base:<52} 未解析出任何条目")

    out = Path(__file__).with_name("_catalog.txt")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n{'=' * 90}\n汇总:")
    for l in lines:
        print("  " + l)
    print(f"\n写入 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

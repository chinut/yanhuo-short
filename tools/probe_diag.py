#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
诊断：把各站的搜索结果页原样抓下来，看**真实**的链接结构。

我在上一版 probe_fullep.py 里是"猜" URL 模板的，结果几乎全部未命中。
不能猜。这个脚本只做一件事：抓下来、列出页面里所有像"详情/播放页"的链接，
让我照着真实结构改解析规则。

用法: python probe_diag.py [关键词]
"""
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

SITES = [
    ("网果",   "https://www.duanju2.com"),
    ("伍果",   "https://www.duanju55.com"),
    ("DSD",    "https://www.dsd.com.se"),
    ("归片",   "https://guipianwu.com"),
    ("韩小圈", "https://www.jennyhow.com"),
    ("皮果",   "https://ptt.red"),
    ("河果",   "https://www.kuaikaw.cn"),
    ("花果",   "https://www.zywest263.com"),
    ("黄豆",   "https://tideember.cc"),
    ("黄果AI", "https://huangguoai.com"),
    ("黄果视频", "https://huangguo.video"),
    ("黄剧",   "https://huangju.net"),
    ("Sorani", "https://www.sorani.net"),
    ("野果",   "https://analyze.buxefaex.cc"),
]


def get(url, limit=400_000, timeout=18):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept": "text/html,application/json,*/*",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return r.status, r.read(limit), None
    except urllib.error.HTTPError as e:
        return e.code, (e.read(4000) or b""), None
    except Exception as e:
        return None, b"", f"{type(e).__name__}: {e}"


def links_in(html):
    """列出页面里所有 href，按出现次数排序，帮我看清 URL 形态。"""
    hrefs = re.findall(r'href=["\']([^"\']+)["\']', html)
    # 归一化成"形态"：数字换成 {n}
    shapes = {}
    for h in hrefs:
        s = re.sub(r"\d+", "{n}", h)
        shapes[s] = shapes.get(s, 0) + 1
    return sorted(shapes.items(), key=lambda kv: -kv[1])[:22]


def search_probe(base, kw):
    """不知道路径，就试一批最常见的，返回第一个非 404 且体积合理的。"""
    q = urllib.parse.quote(kw)
    cands = [
        f"{base}/search.html?searchword={q}",
        f"{base}/index.php/vod/search.html?wd={q}",
        f"{base}/index.php/vod/search/page/1/wd/{q}.html",
        f"{base}/vodsearch/{q}----------.html",
        f"{base}/search?wd={q}",
        f"{base}/search?keyword={q}",
        f"{base}/search/{q}",
        f"{base}/search?q={q}",
    ]
    for u in cands:
        st, body, err = get(u)
        if st == 200 and len(body) > 1500:
            return u, body
    return None, b""


def main():
    kw = sys.argv[1] if len(sys.argv) > 1 else "总裁"
    print(f"搜索关键词: {kw}\n" + "=" * 100)
    report = []
    for name, base in SITES:
        print(f"\n### {name}  {base}")
        home_links = []
        st, home, err = get(base + "/")
        if st == 200:
            home_links = links_in(home.decode("utf-8", "replace"))
        print(f"  首页 {st} {len(home)}B")
        for shape, n in home_links[:10]:
            print(f"    {n:>4}x  {shape}")
        u, body = search_probe(base, kw)
        if u:
            txt = body.decode("utf-8", "replace")
            print(f"  搜索页命中: {u}  ({len(body)}B)")
            for shape, n in links_in(txt)[:12]:
                print(f"    {n:>4}x  {shape}")
            report.append((name, base, u, len(body)))
        else:
            print("  搜索页: 所有候选路径都没命中")
            report.append((name, base, "", 0))

    out = Path(__file__).with_name("_diag.txt")
    with out.open("w", encoding="utf-8") as f:
        for r in report:
            f.write(f"{r[0]:<8} {r[1]:<34} {r[2] or '(未命中)':<60} {r[3]}\n")
    print(f"\n汇总写入 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

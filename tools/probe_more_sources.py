# -*- coding: utf-8 -*-
"""再找几个独立公开接口做覆盖（跟七猫互补，避免只依赖一家）。

判据同前：能不能搜、给不给全集地址、地址能不能播。
"""
from __future__ import annotations

import json
import re
import ssl
import time
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

KW = urllib.parse.quote("总裁")


def get(url, timeout=18, headers=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/json,text/html,*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read().decode("utf-8", "replace")


def head(url, timeout=12):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Range", "bytes=0-255")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return f"HTTP {r.status}"
    except Exception as e:
        return f"HTTP {getattr(e,'code',type(e).__name__)}"


# ---------- 通用：给一个 JSON 响应猜出"分集列表 + 播放地址" ----------
def sniff_playlist(body: str):
    """在 JSON 里找形如 [{..., url/video_url/play_url: 'http...'}] 的数组。"""
    try:
        j = json.loads(body)
    except Exception:
        return None
    found = []

    def walk(node, path=""):
        if isinstance(node, dict):
            keys = {k.lower(): k for k in node.keys()}
            urlk = next((keys[k] for k in ("url", "video_url", "play_url", "m3u8",
                                           "playurl", "src", "video") if k in keys), None)
            if urlk:
                v = node[urlk]
                if isinstance(v, str) and v.startswith("http"):
                    found.append((path, v))
            for k, v in node.items():
                walk(v, f"{path}.{k}")
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f"{path}[{i}]")

    walk(j)
    return found or None


SEARCH_ENDPOINTS = [
    ("gimy 短剧",       "https://gimy.ai/ajax/search?q={kw}"),
    ("短剧top",         "https://www.duanjutop.com/api/search?keyword={kw}"),
    ("duanju 聚合",     "https://api.duanju.vip/api/search?name={kw}"),
    ("短剧库",          "https://www.duanju.la/api/search?wd={kw}"),
    ("yhdm 类",         "https://www.yhmgo.com/api/search?wd={kw}"),
    ("短剧api-9ddm",    "https://api.drama.9ddm.com/api/search/results?keyword={kw}"),
    ("huangju v1",      "https://api.huangju.net/api/v1/playlet/search?keyword={kw}"),
    ("huangju v1 index", "https://api.huangju.net/api/v1/playlet/index"),
    ("whjzjx 帝果",     "https://app.whjzjx.cn/api/v1/playlet/search?keyword={kw}"),
    ("whjzjx 帝果 index", "https://app.whjzjx.cn/api/v1/playlet/index"),
    ("qmplaylet read",  "https://api-read.qmplaylet.com/api/app/playlet/search?keyword={kw}"),
    ("qmplaylet store", "https://api-store.qmplaylet.com/api/app/playlet/search?keyword={kw}"),
]

print("=" * 78)
print("独立接口探测")
print("=" * 78)
for name, tpl in SEARCH_ENDPOINTS:
    u = tpl.format(kw=KW)
    try:
        body = get(u)
        pl = sniff_playlist(body)
        tag = ""
        if pl:
            ep0 = pl[0][1]
            tag = f"  ★分集{len(pl)}条  首集 {head(ep0)}"
            # 检查是否所有条目都有 url（说明是全集）
            tag += f"  末集 {head(pl[-1][1])}"
        print(f"  {name:20} [{len(body):>6} 字符] {body[:110]!r}{tag}")
    except Exception as e:
        print(f"  {name:20} [{getattr(e,'code',type(e).__name__)}] {u}")
    time.sleep(0.3)

print()
print("=" * 78)
print("备选：红果镜像站 hongguoapp.cn（苹果CMS 结构，之前不稳定，再试一次）")
print("=" * 78)
for u in ("https://www.hongguoapp.cn/",
          "https://www.hongguoapp.cn/index.php/ajax/data?mid=1&tid=2&page=1",
          "https://www.hongguoapp.cn/api.php/provide/vod/?ac=detail&wd=%E5%AE%B4%E5%BE%8B"):
    try:
        b = get(u, timeout=25)
        print(f"  [{len(b):>6} 字符] {u}\n       {b[:160]!r}")
    except Exception as e:
        print(f"  [{getattr(e,'code',type(e).__name__)}] {u}")

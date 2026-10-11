# -*- coding: utf-8 -*-
"""探测开源短剧生态里的公开 JSON 接口候选（TVBox 生态 / 公开聚合 API）。

只采信**实测**：能不能搜、能不能给全集地址、地址能不能播。
"""
from __future__ import annotations

import json
import re
import ssl
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url, timeout=18, ua=UA, headers=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", ua)
    req.add_header("Accept", "application/json,text/html,*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read().decode("utf-8", "replace")


def head(url, timeout=15):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Range", "bytes=0-255")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return f"HTTP {r.status}"
    except Exception as e:
        return f"HTTP {getattr(e,'code',type(e).__name__)}"


def try_maccms(base: str, kw: str = "总裁"):
    """苹果CMS 通用：ac=detail&wd=关键词。返回每部剧的分集数与有地址数。"""
    for path in ("/api.php/provide/vod/", "/api.php/provide/vod/at/json/",
                 "/provide/vod/", "/api.php/provide/vod"):
        u = f"{base}{path}?ac=detail&wd={urllib.parse.quote(kw)}"
        try:
            body = get(u)
            if not body.lstrip().startswith("{"):
                continue
            j = json.loads(body)
            lst = j.get("list") or []
            if not lst:
                continue
            first = lst[0]
            pu = first.get("vod_play_url") or ""
            eps = [x for x in pu.split("#") if x]
            withurl = sum(
                1 for x in eps
                if "$" in x and x.split("$", 1)[1].strip().startswith("http")
            )
            sample = ""
            if withurl:
                u0 = eps[0].split("$", 1)[1].strip()
                sample = head(u0)
            return {
                "ok": True, "url": u, "hits": len(lst),
                "title": first.get("vod_name"),
                "episodes": len(eps), "withUrl": withurl, "stream": sample,
            }
        except Exception:
            continue
    return None


CANDIDATES = [
    # TVBox 生态常见的几个公开接口（可能随时失效，所以逐个实测）
    ("TVBox-饭太硬", "http://www.饭太硬.com/tv"),
    ("TVBox-肥猫", "http://肥猫.com"),
    ("TVBox-不太灵", "https://www.252035.xyz"),
    ("TVBox-宝盒", "http://宝盒.m1080p.com"),
    # 友商 App 里出现过、且响应正常的那几个
    ("观果-河马剧场", "https://www.kuaikaw.cn"),
    ("猫果-duanju55", "https://www.duanju55.com"),
    ("猫果-duanju2", "https://www.duanju2.com"),
    ("野果-dsd", "https://www.dsd.com.se"),
    ("帝果-ptt.red", "https://ptt.red"),
]

if __name__ == "__main__":
    print("=" * 76)
    print("A. 苹果CMS 接口探测（ac=detail&wd=总裁）")
    print("=" * 76)
    for name, base in CANDIDATES:
        r = try_maccms(base)
        if r:
            print(f"  {name:16} ✓ {r}")
        else:
            print(f"  {name:16} ✗ 无可用苹果CMS接口")

    print()
    print("=" * 76)
    print("B. TVBox 配置地址是否可下载（含源清单）")
    print("=" * 76)
    for name, base in CANDIDATES[:4]:
        for u in (base, base + "/tv", base + "/config.json", base + "/api.json"):
            try:
                b = get(u, timeout=12)
                n = len(re.findall(r"https?://[^\"'\s]+", b))
                print(f"  {name:12} [{len(b):>7} 字符, {n} 个 URL] {u}")
                break
            except Exception as e:
                print(f"  {name:12} [{getattr(e,'code',type(e).__name__)}] {u}")

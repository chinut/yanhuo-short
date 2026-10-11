#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
付费墙边界扫描（本项目最终采用的核心判据）。

思路：苹果CMS 的播放页 URL 是 .../play/id/<vod>/sid/<线路>/nid/<集>。
     如果站点有"只免费前 N 集"的墙，那么扫 nid 时会看到
     某个集号之后**要么 404、要么 player_aaaa 里 url 为空**。

这正是我在红果官网实测到的那条规律（第 1/2/3 集 200，第 4 集起 404）。
用它扫候选站，就能在没有选集列表的情况下判断"是不是全集源"。

用法:
    python probe_wall.py <base> <vod_id> [--sid N] [--max N]
例:
    python probe_wall.py https://www.duanju55.com 55288 --max 30
"""
import json
import re
import ssl
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url, limit=600_000, timeout=20, referer=None):
    h = {"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"}
    if referer:
        h["Referer"] = referer
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return r.status, r.read(limit)
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception:
        return None, b""


def ep_state(base, vod, sid, nid):
    """
    看一眼第 nid 集：拿到直链了吗？

    三种状态:
      ok     有 m3u8/mp4 直链
      empty  player_aaaa 在，但 url 空（典型的"要会员"）
      gone   404 / 页面异常
    """
    url = f"{base}/index.php/vod/play/id/{vod}/sid/{sid}/nid/{nid}.html"
    st, body = get(url, referer=base + "/")
    if st != 200 or len(body) < 1200:
        return nid, "gone", f"HTTP {st}"
    html = body.decode("utf-8", "replace")
    m = re.search(r"(?s)player_aaaa\s*=\s*(\{.*?\})\s*</script>", html)
    if not m:
        return nid, "nopage", "无 player_aaaa"
    try:
        cfg = json.loads(m.group(1))
    except Exception:
        return nid, "nopage", "配置解析失败"
    u = (cfg.get("url") or "").strip()
    if not u:
        return nid, "empty", f"trysee={cfg.get('trysee')} points={cfg.get('points')}"
    if "http" not in u:
        return nid, "odd", u[:40]
    return nid, "ok", u[:80]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    vod = sys.argv[2]
    sid = 1
    mx = 30
    if "--sid" in sys.argv:
        sid = int(sys.argv[sys.argv.index("--sid") + 1])
    if "--max" in sys.argv:
        mx = int(sys.argv[sys.argv.index("--max") + 1])

    print(f"扫描 {base}  vod={vod} sid={sid}  nid=1..{mx}\n")
    with ThreadPoolExecutor(max_workers=6) as ex:
        rows = list(ex.map(lambda n: ep_state(base, vod, sid, n), range(1, mx + 1)))

    counts = {}
    for nid, state, note in rows:
        counts[state] = counts.get(state, 0) + 1
        mark = {"ok": "✓", "empty": "✗", "gone": "·", "nopage": "?", "odd": "!"}.get(state, "?")
        print(f"  {mark} nid={nid:<4} {state:<7} {note}")

    print(f"\n统计: {counts}")
    ok = [n for n, s, _ in rows if s == "ok"]
    if ok:
        # 找第一个"不 ok"的位置 = 付费墙边界
        wall = None
        for n in range(1, mx + 1):
            if n not in ok:
                wall = n
                break
        print(f"可播集号: {ok[:12]}{' …' if len(ok) > 12 else ''}  共 {len(ok)}")
        if wall:
            print(f"★ 首个取不到直链的集号 = {wall}"
                  f"（若 ok 集数远大于它，说明中间有洞，可疑）")
        else:
            print(f"★ 1..{mx} 全部可播 —— 这个站**没有付费墙**（至少这个区间）")
    else:
        print("★ 一集都没拿到直链")
    return 0


if __name__ == "__main__":
    sys.exit(main())

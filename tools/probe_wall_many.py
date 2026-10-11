#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量付费墙扫描。

对每个活站：
  1. 从首页/列表页取一个真实存在的 vod id（不猜 id，避免把"id 不存在"
     误判成"付费墙"—— 这是我上一轮差点犯的错）；
  2. 扫 nid=1..N 看直链有没有、第几集开始没有；
  3. 同时扫 sid=1..3，确认"线路"是否影响结论。

关键：**一定要先确认 id 与 sid 是真实存在的**，否则 empty 可能只是路由不存在。

用法: python probe_wall_many.py [每个站扫多少集]
输出: tools/_wall.txt
"""
import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

# (名, base, 搜索结果页模板 或 None, 详情id正则)
SITES = [
    ("伍果",   "https://www.duanju55.com",  "/index.php/vod/search.html?wd={q}"),
    ("网果",   "https://www.duanju2.com",   None),
    ("DSD",    "https://www.dsd.com.se",    "/index.php/vod/search.html?wd={q}"),
    ("韩小圈", "https://www.jennyhow.com",  "/index.php/vod/search.html?wd={q}"),
    ("归片",   "https://guipianwu.com",     None),
    ("花果",   "https://www.zywest263.com", None),
    ("黄豆",   "https://tideember.cc",      None),
]

ID_PATTERNS = [
    r"/index\.php/vod/detail/id/(\d+)\.html",
    r"/vod/(\d+)\.html",
    r"/nv/(\d+)\.html",
    r"/hanxiaoquan/(\d+)\.html",
    r"/hxq/(\d+)\.html",
]


def get(url, limit=500_000, timeout=20, referer=None):
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


def pick_vod(base, search_tpl, kw="总裁"):
    """找一个真实存在的 vod id。搜索结果优先，其次首页。"""
    pages = []
    if search_tpl:
        pages.append(base + search_tpl.format(q=urllib.parse.quote(kw)))
    pages.append(base + "/")
    for u in pages:
        st, body = get(u)
        if st != 200 or not body:
            continue
        txt = body.decode("utf-8", "replace")
        for pat in ID_PATTERNS:
            m = re.findall(pat, txt)
            if m:
                # 取出现次数最多的那个（首页推荐位会重复，是真实存在的）
                from collections import Counter
                return Counter(m).most_common(1)[0][0], u
    return None, None


def ep_state(base, vod, sid, nid):
    url = f"{base}/index.php/vod/play/id/{vod}/sid/{sid}/nid/{nid}.html"
    st, body = get(url, referer=base + "/")
    if st != 200 or len(body) < 1200:
        return "gone", f"HTTP {st}"
    html = body.decode("utf-8", "replace")
    m = re.search(r"(?s)player_aaaa\s*=\s*(\{.*?\})\s*</script>", html)
    if not m:
        return "nopage", "-"
    try:
        cfg = json.loads(m.group(1))
    except Exception:
        return "nopage", "-"
    u = (cfg.get("url") or "").strip()
    if "http" in u:
        return "ok", u[:60]
    return "empty", f"url='{u}' from={cfg.get('from')}"


def scan_site(args):
    name, base, tpl = args
    mx = MAX
    vod, via = pick_vod(base, tpl)
    if not vod:
        return {"name": name, "base": base, "err": "没取到 vod id"}

    # ① 先扫 sid=1 的 nid 序列
    seq = []
    with ThreadPoolExecutor(max_workers=5) as ex:
        seq = list(ex.map(lambda n: (n,) + ep_state(base, vod, 1, n), range(1, mx + 1)))
    ok1 = [n for n, s, _ in seq if s == "ok"]

    # ② 如果 sid=1 只有 1 集，试 sid=2/3 排除"线路选错"
    alt = {}
    if len(ok1) <= 1:
        for sid in (2, 3):
            s2, note2 = ep_state(base, vod, sid, 2)
            alt[sid] = (s2, note2)

    return {"name": name, "base": base, "vod": vod, "via": via,
            "seq": seq, "ok1": ok1, "alt": alt}


MAX = 20


def main():
    global MAX
    if len(sys.argv) > 1:
        MAX = int(sys.argv[1])

    rows = []
    with ThreadPoolExecutor(max_workers=3) as ex:
        for r in ex.map(scan_site, SITES):
            rows.append(r)

    lines = []
    for r in rows:
        print(f"\n### {r['name']}  {r['base']}")
        if r.get("err"):
            print(f"   {r['err']}")
            lines.append(f"{r['name']:<8} {r['err']}")
            continue
        print(f"   用 vod id = {r['vod']}  (来自 {r['via']})")
        counts = {}
        for n, s, _ in r["seq"]:
            counts[s] = counts.get(s, 0) + 1
        print(f"   nid=1..{MAX} 状态统计: {counts}")
        ok = r["ok1"]
        print(f"   可播集号: {ok}")
        if len(ok) <= 1 and r["alt"]:
            print(f"   换线路复核(排除'sid选错'): {r['alt']}")
        verdict = "无法判定"
        if len(ok) == 0:
            verdict = "一集都拿不到（可能不是这个路由）"
        elif len(ok) == 1:
            verdict = "★ 只有 1 集 → 与红果同类，付费墙/需登录"
        elif len(ok) >= MAX:
            verdict = f"★ 1..{MAX} 全部可播 → 无墙，值得深挖"
        else:
            verdict = f"★ 可播 {len(ok)}/{MAX} → 部分墙，看边界"
        print(f"   结论: {verdict}")
        lines.append(f"{r['name']:<8} vod={r['vod']:<8} 可播={len(ok)}/{MAX}  {verdict}")

    out = Path(__file__).with_name("_wall.txt")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n汇总写入 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

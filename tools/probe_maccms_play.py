#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从苹果CMS播放页提取分集表。

关键结构（本项目做红果官网时已确认过同一套）：
    播放页 <script> 里的 `player_aaaa = {...}`，
    其中 `url` 形如  "第1集$http://...m3u8#第2集$http://...#..."
    → 按 # 切分、每段按 $ 切分，就得到集数 + 直链。

这就绕开了"选集列表是 AJAX 懒加载"的问题 —— 详情页没有的，播放页有。

用法: python probe_maccms_play.py <站点base> <vod_id> [sid] [nid]
"""
import json
import re
import ssl
import sys
import urllib.error
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url, limit=900_000, timeout=25, referer=None):
    h = {"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"}
    if referer:
        h["Referer"] = referer
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.status, r.read(limit)


def play_config(html):
    """抠出 player_aaaa / MacPlayer 配置。"""
    for pat in (r"(?s)player_aaaa\s*=\s*(\{.*?\})\s*</script>",
                r"(?s)MacPlayerConfig\s*=\s*(\{.*?\})\s*</script>",
                r'(?s)player_aaaa\s*=\s*(\{.*?"url".*?\})\s*;'):
        m = re.search(pat, html)
        if not m:
            continue
        try:
            return json.loads(m.group(1))
        except Exception:
            continue
    return None


def episodes_from_config(cfg):
    """把 maccms 的 url 字段拆成 [(集号或标题, 直链)]。"""
    raw = cfg.get("url") or ""
    out = []
    for part in raw.split("#"):
        part = part.strip()
        if not part:
            continue
        if "$" in part:
            name, addr = part.split("$", 1)
            out.append((name.strip(), addr.strip()))
        else:
            out.append(("", part))
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    vid = sys.argv[2]
    sid = sys.argv[3] if len(sys.argv) > 3 else "1"
    nid = sys.argv[4] if len(sys.argv) > 4 else "1"

    # 各站的播放页路由不一样，挨个试
    cands = [
        f"{base}/index.php/vod/play/id/{vid}/sid/{sid}/nid/{nid}.html",
        f"{base}/vodplay/{vid}-{sid}-{nid}.html",
        f"{base}/play/{vid}-{sid}-{nid}.html",
        f"{base}/index.php/vod/play/id/{vid}.html",
    ]
    for u in cands:
        try:
            st, body = get(u, referer=base + "/")
        except urllib.error.HTTPError as e:
            print(f"  {e.code}  {u}")
            continue
        except Exception as e:
            print(f"  ERR {type(e).__name__}  {u}")
            continue
        if st != 200 or len(body) < 1500:
            print(f"  {st}  {len(body)}B  {u}")
            continue
        html = body.decode("utf-8", "replace")
        cfg = play_config(html)
        print(f"\n命中播放页: {u}   ({len(body)}B)")
        if not cfg:
            print("  但没解析出 player_aaaa")
            for kw in ("player_aaaa", "MacPlayer", "m3u8", "url_next"):
                print(f"    {kw}: {html.count(kw)}")
            continue
        eps = episodes_from_config(cfg)
        real = [e for e in eps if e[1].startswith("http")]
        print(f"  player_aaaa 键: {list(cfg.keys())}")
        print(f"  ★ 分集条目 {len(eps)} 条，其中带 http 直链 {len(real)} 条")
        for name, addr in eps[:3]:
            print(f"      {name or '(无名)'}  →  {addr[:90]}")
        if len(eps) > 3:
            print(f"      … 最后一条: {eps[-1][1][:90]}")
        # 一眼判断是否 m3u8 直链
        kinds = {}
        for _, a in real:
            k = "m3u8" if ".m3u8" in a else ("mp4" if ".mp4" in a else "其它")
            kinds[k] = kinds.get(k, 0) + 1
        print(f"  直链类型: {kinds}")
        return 0
    print("  所有候选播放页路由都没命中")
    return 1


if __name__ == "__main__":
    sys.exit(main())

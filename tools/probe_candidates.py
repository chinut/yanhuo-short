# -*- coding: utf-8 -*-
"""候选短剧源批量实测。

**验收标准只有一条：能不能拿到"全集"的播放地址。**

每个候选都跑同一套检查：
  1. 搜索是否可用（拿得到片单）
  2. 详情是否给出全部分集（对比"标注集数"与"实际返回集数"）
  3. **每一集是否都有非空播放地址**（这才是"能全部看"的判据）
  4. 抽查首/中/末三集，真的能取到流（HTTP 200/206）

输出一张对照表，直接决定用哪个源。
"""
from __future__ import annotations

import json
import re
import ssl
import sys
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

# 用来对齐测试的剧名（多源都能搜到最好；搜不到就用各源自己的第一个结果）
PROBE_TITLES = ["总裁", "战神", "重生"]


def get(url: str, timeout: int = 30, ua: str = UA) -> str:
    req = urllib.request.Request(url)
    req.add_header("User-Agent", ua)
    req.add_header("Accept", "application/json,text/html,*/*")
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read().decode("utf-8", "replace")


def head(url: str, timeout: int = 25) -> str:
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Range", "bytes=0-255")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return f"HTTP {r.status} {r.headers.get('Content-Type','?')[:30]}"
    except Exception as e:
        code = getattr(e, "code", None)
        return f"HTTP {code}" if code else type(e).__name__


# ============================================================ 各候选源

def probe_qimao() -> dict:
    """七猫（当前备用源）—— 基准线。"""
    api = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"
    d = json.loads(get(f"{api}?name={urllib.parse.quote('总裁')}&page=1"))
    lst = (d.get("data") or {}).get("list") or []
    if not lst:
        return {"ok": False, "why": "搜索无结果"}
    sid = lst[0]["id"]
    det = json.loads(get(f"{api}?id={sid}"))["data"]
    pl = det.get("play_list") or []
    urls = [x.get("video_url", "") for x in pl]
    nonempty = sum(1 for u in urls if u)
    checked = []
    for i in (0, len(urls) // 2, len(urls) - 1):
        if 0 <= i < len(urls) and urls[i]:
            checked.append((i + 1, head(urls[i])))
    return {
        "ok": True, "title": det.get("title"),
        "declared": det.get("total_episode_num"), "got": len(pl),
        "withUrl": nonempty, "stream": checked,
    }


def probe_qimao_search_only() -> dict:
    """七猫热词池规模 —— 看内容量够不够当主源。"""
    api = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"
    total = 0
    detail = []
    for kw in ["总裁", "战神", "重生", "穿越", "神医", "赘婿", "甜宠", "逆袭",
               "复仇", "年代", "玄幻", "都市", "闪婚", "豪门", "萌宝", "婆婆"]:
        try:
            d = json.loads(get(f"{api}?name={urllib.parse.quote(kw)}&page=1"))
            n = len((d.get("data") or {}).get("list") or [])
        except Exception:
            n = 0
        total += n
        detail.append(f"{kw}:{n}")
    return {"ok": True, "total": total, "detail": " ".join(detail)}


def probe_huangguo_ai() -> dict:
    """黄果 huangguoai.com —— 友商 App 在用。"""
    try:
        h = get("https://huangguoai.com/", timeout=25)
    except Exception as e:
        return {"ok": False, "why": f"{type(e).__name__}"}
    # 找页面内嵌的接口/路由线索
    apis = sorted(set(re.findall(r'["\'](/(?:api|v\d)/[A-Za-z0-9_\-/\.]{3,60})["\']', h)))
    hosts = sorted(set(re.findall(r'https?://([a-z0-9\-\.]+\.[a-z]{2,10})', h)))[:12]
    return {"ok": True, "apis": apis[:12], "hosts": hosts, "len": len(h)}


def probe_ygdj7() -> dict:
    """野果 ygdj7.com —— 友商 App 在用。"""
    try:
        h = get("https://ygdj7.com/", timeout=25)
    except Exception as e:
        return {"ok": False, "why": f"{type(e).__name__}"}
    apis = sorted(set(re.findall(r'["\'](/(?:api|v\d|index\.php)[A-Za-z0-9_\-/\.\?=&]{3,70})["\']', h)))
    return {"ok": True, "apis": apis[:12], "len": len(h),
            "hasBase64": "Base64" in h or "_keyStr" in h}


def probe_maccms_generic(name: str, base: str) -> dict:
    """苹果CMS 通用探测：/api.php/provide/vod/?ac=list 与 ac=detail。"""
    out = {"ok": False, "why": ""}
    for path in ("/api.php/provide/vod/", "/api.php/provide/vod/at/json/"):
        for ac in ("list", "detail"):
            u = f"{base}{path}?ac={ac}&pg=1"
            try:
                body = get(u, timeout=20)
                if body.lstrip().startswith("{") or body.lstrip().startswith("["):
                    j = json.loads(body)
                    n = len(j.get("list") or [])
                    out = {"ok": True, "url": u, "items": n,
                           "total": j.get("total"), "class": j.get("class") is not None}
                    if n > 0 and ac == "detail":
                        first = (j.get("list") or [{}])[0]
                        # 苹果CMS 的播放地址在 vod_play_url，形如 "第1集$http...#第2集$http..."
                        pu = first.get("vod_play_url") or ""
                        eps = [x for x in pu.split("#") if x]
                        withurl = sum(1 for x in eps if "$" in x and x.split("$", 1)[1].startswith("http"))
                        out["episodes"] = len(eps)
                        out["episodesWithUrl"] = withurl
                    return out
            except Exception as e:
                out = {"ok": False, "why": f"{type(e).__name__}:{str(e)[:40]}"}
    return out


CANDIDATES = {
    "七猫(当前备用源)": probe_qimao,
    "七猫-热词池规模": probe_qimao_search_only,
    "黄果-huangguaai.com": probe_huangguo_ai,
    "野果-ygdj7.com": probe_ygdj7,
}


if __name__ == "__main__":
    only = sys.argv[1:] if len(sys.argv) > 1 else list(CANDIDATES)
    for name in only:
        fn = CANDIDATES.get(name)
        if not fn:
            print(f"-- 未知候选 {name}")
            continue
        print("=" * 74)
        print(name)
        try:
            r = fn()
        except Exception as e:
            r = {"ok": False, "why": f"{type(e).__name__}: {str(e)[:100]}"}
        print(json.dumps(r, ensure_ascii=False, indent=2))
        print()

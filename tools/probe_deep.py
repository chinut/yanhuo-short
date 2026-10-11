# -*- coding: utf-8 -*-
"""深挖两个候选：
  A. 七猫 —— 能否搜出更大片库（翻页深度 / 关键词覆盖）
  B. 黄果 huangguoai.com —— 找出它的内容接口，验证是否给全集

判据依旧是"每一集是否都有可播地址"。
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


def get(url, timeout=25, ua=UA, headers=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", ua)
    req.add_header("Accept", "application/json,text/html,*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read().decode("utf-8", "replace")


def head(url, timeout=20):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Range", "bytes=0-255")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return f"HTTP {r.status} {r.headers.get('Content-Type','?')[:28]}"
    except Exception as e:
        return f"HTTP {getattr(e,'code',type(e).__name__)}"


print("#" * 76)
print("# A. 七猫：分页深度 + 关键词覆盖")
print("#" * 76)
API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"

print("\n-- 单个热词能翻几页 --")
for kw in ["总裁", "战神", "重生"]:
    pages, ids = 0, set()
    for p in range(1, 13):
        try:
            d = json.loads(get(f"{API}?name={urllib.parse.quote(kw)}&page={p}"))
            lst = (d.get("data") or {}).get("list") or []
        except Exception as e:
            print(f"   {kw} 第{p}页异常 {type(e).__name__}")
            break
        if not lst:
            break
        pages += 1
        ids |= {x["id"] for x in lst}
    print(f"   {kw}: 有效页 {pages}，去重 {len(ids)} 部")

print("\n-- 更大关键词集合的覆盖（每词 2 页）--")
KWS = ["总裁", "战神", "重生", "穿越", "神医", "赘婿", "甜宠", "逆袭", "复仇",
       "年代", "玄幻", "都市", "闪婚", "豪门", "萌宝", "婆婆", "千金", "夫人",
       "龙王", "觉醒", "学生", "外卖", "保安", "保洁", "外卖员", "保姆",
       "复仇", "打脸", "虐恋", "悬疑", "宫斗", "玄幻", "武侠", "校园",
       "职场", "异能", "末世", "修仙", "灵异", "军事", "犯罪", "家庭"]
allids = set()
for kw in KWS:
    for p in (1, 2):
        try:
            d = json.loads(get(f"{API}?name={urllib.parse.quote(kw)}&page={p}"))
            for x in (d.get("data") or {}).get("list") or []:
                allids.add(x["id"])
        except Exception:
            pass
print(f"   {len(KWS)} 个关键词 × 2 页 → 去重 {len(allids)} 部")

print("\n-- 详情里的字段（看有没有题材/简介/热度可用）--")
d = json.loads(get(f"{API}?name={urllib.parse.quote('总裁')}&page=1"))
sid = d["data"]["list"][0]["id"]
det = json.loads(get(f"{API}?id={sid}"))["data"]
print("   字段:", sorted(det.keys()))
pl = det.get("play_list") or []
if pl:
    print("   play_list[0] 字段:", sorted(pl[0].keys()))
    print("   集名样例:", [x.get("video_id") for x in pl[:3]])
    print("   时长/清晰度:", pl[0].get("duration"), pl[0].get("width"), "x", pl[0].get("height"))

print()
print("#" * 76)
print("# B. 黄果 huangguoai.com：找内容接口")
print("#" * 76)

# 首页里找 next 的构建产物 JS（接口通常在里面）
home = get("https://huangguoai.com/", timeout=25)
scripts = sorted(set(re.findall(r'src="(/_next/static/[^"]+\.js)"', home)))[:6]
print(f"\n首页 {len(home)} 字符，_next JS {len(scripts)} 个")
for s in scripts:
    print("   ", s)

# 从 JS 里挖接口路径
found = set()
for s in scripts:
    try:
        js = get("https://huangguoai.com" + s, timeout=25)
    except Exception as e:
        print(f"   {s} 取不到 {type(e).__name__}")
        continue
    for m in re.finditer(r'["\'`](/api/[A-Za-z0-9_\-/\.\[\]]{3,70})["\'`]', js):
        found.add(m.group(1))
print(f"\nJS 里挖到 {len(found)} 个 /api/ 路径：")
for f in sorted(found):
    print("   ", f)

# 试几个最像"列表/详情/播放"的
print("\n-- 逐个试接口 --")
for path in sorted(found):
    if not re.search(r"video|drama|play|detail|list|search|series|episode|home|categ", path, re.I):
        continue
    u = "https://huangguoai.com" + path
    for method in ("GET",):
        try:
            body = get(u, timeout=18)
            print(f"   [200] {path}\n         {body[:220]!r}")
        except Exception as e:
            code = getattr(e, "code", None)
            print(f"   [{code or type(e).__name__}] {path}")

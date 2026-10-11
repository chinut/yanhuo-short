# -*- coding: utf-8 -*-
"""探七猫接口还有哪些参数/端点 —— 决定首页推荐流能做成什么样。"""
from __future__ import annotations

import json
import ssl
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 13) Chrome/122 Mobile"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"


def get(q):
    u = f"{API}?{q}" if q else API
    req = urllib.request.Request(u)
    req.add_header("User-Agent", UA)
    with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
        return r.read().decode("utf-8", "replace")


def shape(txt, label):
    try:
        j = json.loads(txt)
    except Exception:
        print(f"  {label:34} 非 JSON（{len(txt)} 字符）{txt[:60]!r}")
        return
    d = j.get("data")
    if isinstance(d, dict):
        keys = sorted(d.keys())[:12]
        lst = d.get("list")
        n = len(lst) if isinstance(lst, list) else "-"
        print(f"  {label:34} dict keys={keys} list={n}")
    elif isinstance(d, list):
        print(f"  {label:34} list len={len(d)}")
    else:
        print(f"  {label:34} {str(j)[:100]}")


print("=== 试探无参 / 其它可能参数 ===")
for q in [
    "", "type=1", "class=1", "cate=1", "category=1", "hot=1", "list=1",
    "all=1", "page=1", "tab=1", "tab_type=11", "tab_type=1",
    "name=热播&page=1", "name=推荐&page=1", "name=短剧&page=1",
    "name=全集&page=1", "id=4576", "id=1",
]:
    try:
        shape(get(q), q or "(无参数)")
    except Exception as e:
        print(f"  {q or '(无参数)':34} {type(e).__name__}: {str(e)[:60]}")

print("\n=== 搜索接口能接受的其他参数（分页/排序）===")
for q in [
    "name=总裁&page=1", "name=总裁&page=2", "name=总裁&p=1", "name=总裁&limit=30",
    "name=总裁&size=30", "name=总裁&sort=hot", "keyword=总裁&page=1",
]:
    try:
        txt = get(q)
        j = json.loads(txt)
        lst = (j.get("data") or {}).get("list") or []
        print(f"  {q:34} list={len(lst)}  首={lst[0]['title'] if lst else '-'}")
    except Exception as e:
        print(f"  {q:34} {type(e).__name__}: {str(e)[:50]}")

print("\n=== 详情里的热度/评分字段能否拿来做榜 ===")
d = json.loads(get("name=" + urllib.parse.quote("总裁") + "&page=1"))
lst = d["data"]["list"]
print("  搜索列表条目全字段:", sorted(lst[0].keys()))
print("  样例:", json.dumps(lst[0], ensure_ascii=False)[:400])

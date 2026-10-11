# -*- coding: utf-8 -*-
"""探测七猫接口有哪些可用的"浏览/榜单"参数（只有 name 搜索的话首页会很单薄）。"""
import json
import ssl
import urllib.parse
import urllib.request

API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"
UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) Chrome/122.0 Mobile"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(q):
    r = urllib.request.Request(f"{API}?{q}" if q else API)
    r.add_header("User-Agent", UA)
    with urllib.request.urlopen(r, timeout=40, context=CTX) as x:
        return json.loads(x.read().decode("utf-8", "replace"))


print("=== 无参数 ===")
try:
    d = get("")
    print(json.dumps(d, ensure_ascii=False)[:600])
except Exception as e:
    print("失败", type(e).__name__, e)

print("\n=== 试探其它参数 ===")
for q in ["type=1", "class=1", "page=1", "hot=1", "list=1", "name=短剧&page=1",
          "name=热播&page=1", "name=推荐&page=1", "name=全网&page=1", "name=短剧&page=2"]:
    try:
        d = get(q)
        data = d.get("data") or {}
        lst = data.get("list") or []
        titles = [x.get("title") for x in lst[:3]]
        print(f"  {q!r:34} -> list={len(lst)} 总数={data.get('total')} 例={titles}")
    except Exception as e:
        print(f"  {q!r:34} -> 失败 {type(e).__name__} {e}")

print("\n=== 单个热词最多能翻几页 ===")
for kw in ["总裁", "战神", "重生"]:
    seen = 0
    for p in range(1, 6):
        try:
            d = get(f"name={urllib.parse.quote(kw)}&page={p}")
            lst = (d.get("data") or {}).get("list") or []
        except Exception:
            break
        if not lst:
            print(f"  {kw}: 第 {p} 页空，停止（累计 {seen}）")
            break
        seen += len(lst)
    else:
        print(f"  {kw}: 5 页都有内容，累计 {seen}")

print("\n=== 详情里有哪些字段（看能否拿到题材/简介）===")
d = get("name=" + urllib.parse.quote("总裁") + "&page=1")
sid = d["data"]["list"][0]["id"]
det = get(f"id={sid}")["data"]
print("顶层字段:", list(det.keys()))
print("play_list[0] 字段:", list((det.get("play_list") or [{}])[0].keys()))
print("title:", det.get("title"), "| tags:", det.get("tags"), "| intro:",
      (det.get("intro") or "")[:60])

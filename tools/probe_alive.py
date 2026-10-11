#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描「各种果」那 26 个源站：谁活着。

只做一件事：对每个 base URL 发一次 GET，记录状态码、体积、标题、以及
是否像苹果CMS。**不碰任何需要签名的官方 App 接口** —— 那属于冒充客户端。

用法: python probe_alive.py
输出: tools/_alive.txt （同时打印到屏幕）
"""
import json
import ssl
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# 从 src-hongguo/native/core/*.go 里抄出来的 base URL
SOURCES = [
    # (内部 id, 中文名, base)
    ("yaguo",       "芽果",   "https://app.whjzjx.cn"),
    ("maoguo",      "猫果",   "https://api-store.qmplaylet.com"),
    ("fanguo",      "饭果",   "https://xifan-api-cn.youlishipin.com"),
    ("guanguo",     "观果",   "https://api.drama.9ddm.com"),
    ("heguo",       "河果",   "https://www.kuaikaw.cn"),
    ("xingguo",     "星果",   "http://read.api.duodutek.com"),
    ("huaguo",      "花果",   "https://www.zywest263.com"),
    ("niuguo",      "牛果",   "https://ccc.chaojichaojichanga.com:35620"),
    ("wangguo",     "网果",   "https://www.duanju2.com"),
    ("faguo",       "发果",   "https://www.xzyx168.com"),
    ("piguo",       "皮果",   "https://ptt.red"),
    ("wuguo",       "伍果",   "https://www.duanju55.com"),
    ("dsd",         "DSD",    "https://www.dsd.com.se"),
    ("guipian",     "归片",   "https://guipianwu.com"),
    ("hanxiaoquan", "韩小圈", "https://www.jennyhow.com"),
    ("hongguo",     "红果",   "https://hongguoduanju.com"),
    ("huangdou",    "黄豆",   "https://tideember.cc"),
    ("huangguoai",  "黄果AI", "https://huangguoai.com"),
    ("huangguov",   "黄果视频", "https://huangguo.video"),
    ("huangju",     "黄剧",   "https://huangju.net"),
    ("sorani",      "Sorani", "https://www.sorani.net"),
    ("yeguo",       "野果",   "https://analyze.buxefaex.cc"),
    # 另外几个 README 提到的（供对照）
    ("qm-public",   "七猫(我在用的)", "https://xiaoqi.icofun.cn"),
]

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")

# 这些站是自签/证书链不全的，为了探测存活放宽校验；**只用于本次探测**
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def fetch(url, timeout=15):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/json,*/*",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read(60000)
            return r.status, body, None
    except urllib.error.HTTPError as e:
        return e.code, (e.read(4000) or b""), None
    except Exception as e:
        return None, b"", f"{type(e).__name__}: {e}"


def title_of(body: bytes) -> str:
    txt = body.decode("utf-8", "replace")
    low = txt.lower()
    i = low.find("<title")
    if i < 0:
        return ""
    j = low.find(">", i)
    k = low.find("</title", j)
    if j < 0 or k < 0:
        return ""
    return txt[j + 1:k].strip()[:70]


def looks_maccms(body: bytes) -> str:
    """苹果CMS 的特征很稳定，命中任意一个就算。"""
    t = body.decode("utf-8", "replace").lower()
    marks = []
    if "maccms" in t:
        marks.append("maccms")
    if "voddetail" in t or "/vod/" in t:
        marks.append("vod路由")
    if "searchtype" in t or "vodsearch" in t:
        marks.append("vod搜索")
    if "苹果cms" in t or "苹果CMS" in body.decode("utf-8", "replace"):
        marks.append("苹果CMS字样")
    return ",".join(marks)


def probe(item):
    sid, name, base = item
    status, body, err = fetch(base + "/")
    return {
        "id": sid, "name": name, "base": base,
        "status": status, "bytes": len(body), "err": err,
        "title": title_of(body), "maccms": looks_maccms(body),
    }


def main():
    rows = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for r in ex.map(probe, SOURCES):
            rows.append(r)
            flag = "✓" if r["status"] and r["status"] < 400 and r["bytes"] > 500 else \
                   ("~" if r["status"] else "✗")
            print(f"  {flag} {r['name']:<14} {str(r['status'] or '-'):>4} "
                  f"{r['bytes']:>7}B  {r['title'][:36]:<38} {r['maccms']}")
            if r["err"]:
                print(f"      └ {r['err'][:100]}")

    out = Path(__file__).with_name("_alive.txt")
    with out.open("w", encoding="utf-8") as f:
        f.write(f"{'状态':<6}{'源':<14}{'域名':<52}{'体积':>9}  标题 / 苹果CMS特征\n")
        f.write("-" * 130 + "\n")
        for r in rows:
            flag = "存活" if r["status"] and r["status"] < 400 and r["bytes"] > 500 else \
                   ("异常" if r["status"] else "不通")
            f.write(f"{flag:<6}{r['name']:<14}{r['base']:<52}{r['bytes']:>9}  "
                    f"{r['title'][:40]} {r['maccms']}\n")
    print(f"\n明细已写入 {out}")


if __name__ == "__main__":
    sys.exit(main())

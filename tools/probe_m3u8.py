#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查七猫返回的 m3u8 到底是什么形态。

这决定离线下载怎么做：
  · 若是**绝对 URL 的 TS 分片列表** → 逐片下载、改写成相对路径，播放器读本地 m3u8 即可
  · 若是**主播放列表**（里面是子 m3u8）→ 要先选码率再下
  · 若是**加密**（#EXT-X-KEY）→ 还要下密钥（多半有时效，离线会失效）

用法: python probe_m3u8.py
"""
import json
import ssl
import sys
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"


def get(url, limit=400_000, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.status, r.read(limit), dict(r.headers)


def main():
    # 1) 搜一部剧拿 id
    st, body, _ = get(f"{API}?name={urllib.parse.quote('总裁')}&page=1")
    data = json.loads(body.decode("utf-8", "replace"))
    lst = (data.get("data") or {}).get("list") or []
    if not lst:
        print("搜索为空"); return 1
    d = lst[0]
    print(f"剧: {d.get('title')}  id={d.get('id')}  声明={d.get('total_num')}")

    # 2) 取详情拿第 1 集地址
    st, body, _ = get(f"{API}?id={d['id']}")
    det = json.loads(body.decode("utf-8", "replace"))
    pl = (det.get("data") or {}).get("play_list") or []
    print(f"play_list {len(pl)} 条")
    if not pl:
        print("无分集"); return 1
    ep = pl[0]
    url = ep.get("video_url") or ""
    print(f"\n第 1 集:")
    print(f"  video_id   = {ep.get('video_id')}")
    print(f"  duration   = {ep.get('duration')}")
    print(f"  尺寸       = {ep.get('width')}x{ep.get('height')}")
    print(f"  url        = {url[:130]}")

    # 3) 拉这个 m3u8，看内容
    st, body, hdrs = get(url)
    print(f"\nm3u8 响应: HTTP {st}  {len(body)}B  Content-Type={hdrs.get('Content-Type')}")
    text = body.decode("utf-8", "replace")
    lines = [l for l in text.splitlines() if l.strip()]
    print(f"行数 {len(lines)}")
    print("--- 前 25 行 ---")
    for l in lines[:25]:
        print("   " + l[:150])

    # 4) 判定形态
    print("\n--- 判定 ---")
    has_key = any(l.startswith("#EXT-X-KEY") for l in lines)
    has_master = any(l.startswith("#EXT-X-STREAM-INF") for l in lines)
    segs = [l for l in lines if l and not l.startswith("#")]
    abs_segs = [s for s in segs if s.startswith("http")]
    rel_segs = [s for s in segs if not s.startswith("http")]
    is_endlist = any(l.startswith("#EXT-X-ENDLIST") for l in lines)

    print(f"  加密(#EXT-X-KEY)  : {has_key}")
    if has_key:
        for l in lines:
            if l.startswith("#EXT-X-KEY"):
                print(f"      {l[:160]}")
    print(f"  主播放列表        : {has_master}")
    print(f"  分片条目          : {len(segs)}（绝对 {len(abs_segs)} / 相对 {len(rel_segs)}）")
    print(f"  含 ENDLIST(已完整): {is_endlist}")
    if segs:
        print(f"  首个分片          : {segs[0][:130]}")
        print(f"  末个分片          : {segs[-1][:130]}")

    # 5) 试下一个分片，确认能取
    if abs_segs:
        st2, b2, h2 = get(abs_segs[0], limit=4096)
        print(f"\n  试拉首个分片: HTTP {st2}  Content-Type={h2.get('Content-Type')}  "
              f"Content-Length={h2.get('Content-Length')}")
        print(f"  前 16 字节: {b2[:16].hex()}")
        print(f"  末尾 16 字节: {b2[-16:].hex()}（188 字节对齐 + 0x47 是 TS；"
              f"若为 ftyp 则是 fMP4）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

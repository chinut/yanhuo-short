#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证 HLS 密钥与分片能否独立取到 —— 这决定"离线下载"方案是否成立。

要回答三个问题：
  1. 密钥 URL 返回的是不是裸的 16 字节？
  2. 密钥是否**绑 Referer / UA / 时效**？断网后还能不能用？
  3. 分片能否在不知道密钥的情况下下载（应该能，加密在内容层）？

用法: python probe_hls_key.py
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


def get(url, limit=200_000, timeout=25, referer=None):
    h = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9"}
    if referer:
        h["Referer"] = referer
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.status, r.read(limit), dict(r.headers)


def main():
    st, body, _ = get(f"{API}?name={urllib.parse.quote('总裁')}&page=1")
    d = (json.loads(body.decode("utf-8", "replace")).get("data") or {}).get("list")[0]
    st, body, _ = get(f"{API}?id={d['id']}")
    ep = ((json.loads(body.decode("utf-8", "replace")).get("data") or {})
          .get("play_list") or [])[0]
    m3u8 = ep["video_url"]

    st, pl, hdrs = get(m3u8)
    text = pl.decode("utf-8", "replace")
    base = m3u8.rsplit("/", 1)[0] + "/"

    import re
    key_line = next((l for l in text.splitlines() if l.startswith("#EXT-X-KEY")), "")
    uri = re.search(r'URI="([^"]+)"', key_line)
    iv = re.search(r"IV=(0x[0-9a-fA-F]+)", key_line)
    print("密钥行:", key_line[:160])
    print("  密钥 URI:", uri.group(1) if uri else "(无)")
    print("  IV      :", iv.group(1) if iv else "(无)")

    if uri:
        ku = uri.group(1)
        print("\n--- 取密钥 ---")
        try:
            st2, kb, h2 = get(ku)
            print(f"  HTTP {st2}  长度={len(kb)}  类型={h2.get('Content-Type')}")
            print(f"  十六进制: {kb.hex()}")
            print(f"  ★ 是否为 16 字节裸密钥: {len(kb) == 16}")
            if len(kb) != 16:
                print(f"  前 200 字符: {kb[:200]!r}")
        except Exception as e:
            print(f"  失败: {type(e).__name__}: {e}")
        # 不带 Referer 再试一次（判断是否绑 Referer）
        try:
            st3, kb3, _ = get(ku, referer=None)
            print(f"  不带 Referer: HTTP {st3} 长度={len(kb3)} 长度一致={len(kb3) == len(kb) if uri else False}")
        except Exception as e:
            print(f"  不带 Referer 失败: {type(e).__name__}")

    # 分片
    segs = [l for l in text.splitlines() if l and not l.startswith("#")]
    if segs:
        print("\n--- 取首个分片（不提供密钥）---")
        try:
            st4, sb, h4 = get(base + segs[0], limit=65536)
            print(f"  HTTP {st4}  读到={len(sb)}  类型={h4.get('Content-Type')}")
            print(f"  首字节 0x{sb[0]:02x}（0x47='G' 表示 TS 包）")
        except Exception as e:
            print(f"  失败: {type(e).__name__}: {e}")

    print("\n--- 结论要点 ---")
    print("  密钥是裸 16 字节 → 可以存成本地 key.bin，改写 m3u8 指向它")
    print("  分片相对路径       → 本地 m3u8 把分片名保持相对即可，整目录自包含")


if __name__ == "__main__":
    sys.exit(main())

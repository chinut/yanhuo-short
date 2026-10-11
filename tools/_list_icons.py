#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""列出 Compose Material 图标里可用的「旋转/横屏」类图标名。"""
import glob
import os
import re
import zipfile

base = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/androidx.compose.material")
cands = glob.glob(base + "/**/*.aar", recursive=True) + glob.glob(base + "/**/*.jar", recursive=True)
names = set()
# 图标在 classes.jar 里（aar 内嵌）或直接 jar 里
inner = []
for p in cands:
    if p.endswith(".aar"):
        try:
            z = zipfile.ZipFile(p)
            if "classes.jar" in z.namelist():
                inner.append(z.read("classes.jar"))
        except Exception:
            pass
    else:
        inner.append(p)

pat = re.compile(r"icons/rounded/([A-Za-z0-9_]+)Kt\.class$")
for src in inner:
    try:
        z = zipfile.ZipFile(io.BytesIO(src)) if isinstance(src, bytes) else zipfile.ZipFile(src)
    except Exception:
        continue
    for n in z.namelist():
        m = pat.search(n)
        if m:
            names.add(m.group(1))

print("rounded 图标总数:", len(names))
if names:
    print("全部:", sorted(names))
for kw in ("Down", "Save", "Cloud", "Arrow", "Offline", "File"):
    hit = sorted(x for x in names if kw.lower() in x.lower())
    if hit:
        print("  含 %-9s %s" % (kw + ":", hit[:12]))

# -*- coding: utf-8 -*-
"""焰火短视频 图形资源生成。

输入（`design/` 目录，由设计稿提供）：
  design/logo.jpg    方形 App 图标（焰火 + 播放键 + 霓虹环）
  design/splash.jpg  竖版开屏主视觉（Logo + slogan「焰火随想 美好随现」）

输出：
  app/src/main/res/mipmap-*/ic_launcher.png               常规图标
  app/src/main/res/mipmap-*/ic_launcher_round.png         圆形图标
  app/src/main/res/mipmap-*/ic_launcher_foreground.png    自适应图标前景
  app/src/main/res/drawable-nodpi/splash_brand.jpg        开屏主视觉（竖屏整图）
  app/src/main/res/drawable-nodpi/logo_yanhuo.png         开屏/加载用 Logo

只依赖 Pillow。设计稿内容不做任何改动，只做裁切 / 缩放 / 格式转换。
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DESIGN = os.path.join(ROOT, "design")

LOGO_SRC = os.path.join(DESIGN, "logo.jpg")
SPLASH_SRC = os.path.join(DESIGN, "splash.jpg")

# Android 8+ 自适应图标：前景只保留中间约 66%，其余会被系统裁掉
ADAPTIVE_SAFE = 0.66

# 设计稿自带的圆角比例（1536 图里圆角约 340px）。裁掉圆角再自绘，
# 避免"图里的圆角 + 系统再裁一刀"出现双层圆角。
SRC_CORNER_RATIO = 0.22
CLEAN_CORNER_RATIO = 0.20

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def load(path: str) -> Image.Image:
    if not os.path.exists(path):
        raise SystemExit(f"缺少设计稿：{path}")
    img = Image.open(path)
    return img.convert("RGBA") if img.mode != "RGBA" else img


def fit_square(img: Image.Image, size: int) -> Image.Image:
    """等比缩放到能盖住 size×size，然后居中裁切。"""
    w, h = img.size
    scale = max(size / w, size / h)
    new = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    left = (new.width - size) // 2
    top = (new.height - size) // 2
    return new.crop((left, top, left + size, top + size))


def rounded(img: Image.Image, ratio: float = CLEAN_CORNER_RATIO) -> Image.Image:
    """按比例切圆角。"""
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w - 1, h - 1), int(min(w, h) * ratio), fill=255
    )
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def circle(img: Image.Image) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def inner_of_rounded_square(img: Image.Image) -> Image.Image:
    """从带圆角的设计稿里取"内接矩形"，把角落的纯白底去掉。

    设计稿是圆角方图、四角是白底。自适应图标的前景会被系统再裁一次，
    如果直接缩进去，四角白底会以直角形式露在系统圆形/圆角遮罩里，很难看。
    这里先按比例内缩取得干净的素材。
    """
    w, h = img.size
    inset = SRC_CORNER_RATIO / 2.0
    box = (int(w * inset), int(h * inset), int(w * (1 - inset)), int(h * (1 - inset)))
    cropped = img.crop(box)
    # 内接区域仍是方的，补齐成正方形（取短边居中）
    s = min(cropped.size)
    left = (cropped.width - s) // 2
    top = (cropped.height - s) // 2
    return cropped.crop((left, top, left + s, top + s))


def main() -> None:
    logo = load(LOGO_SRC)
    splash = load(SPLASH_SRC)
    print("logo", logo.size, "splash", splash.size)

    clean = inner_of_rounded_square(logo)

    # ---------- 常规 / 圆形图标 ----------
    for folder, size in DENSITIES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        square = fit_square(logo, size)
        rounded(square).save(os.path.join(d, "ic_launcher.png"))
        circle(square).save(os.path.join(d, "ic_launcher_round.png"))
        print("icon", folder, size)

    # ---------- 自适应图标前景 ----------
    # 前景内容只占中间 66%，四周透明，避免被系统裁掉边缘。
    # 用已去掉白角的内接素材，这样在系统的圆形/圆角遮罩里不会露白边。
    for folder, size in DENSITIES.items():
        d = os.path.join(RES, folder)
        inner = max(1, int(size * ADAPTIVE_SAFE))
        fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        piece = fit_square(clean, inner)
        piece = rounded(piece, CLEAN_CORNER_RATIO * 0.9)
        off = (size - inner) // 2
        fg.paste(piece, (off, off), piece)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
    print("adaptive foreground ok")

    # ---------- 开屏主视觉 ----------
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    # JPEG 而不是 PNG：这是张有渐变的照片级图，PNG 要 2MB+，JPEG 只要 200KB 左右。
    splash.convert("RGB").save(
        os.path.join(nodpi, "splash_brand.jpg"), quality=92, optimize=True
    )
    print("splash_brand", splash.size)

    # 开屏 / 加载用的独立 Logo（不含文案），圆形裁切便于做动画
    glyph = circle(fit_square(logo, 512))
    glyph.save(os.path.join(nodpi, "logo_yanhuo.png"), optimize=True)
    print("logo_yanhuo 512")


if __name__ == "__main__":
    main()

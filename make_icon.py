# -*- coding: utf-8 -*-
"""生成极速下载器 Windows 版图标：圆角渐变徽标 + 闪电 + 下载箭头，输出多尺寸 ICO + PNG"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def make(size):
    S = 512  # 高分辨率画布，最后缩放
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角矩形背景 + 对角渐变（深蓝 -> 亮蓝）
    r = int(S * 0.22)
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bg)
    c1, c2 = (37, 99, 235), (16, 185, 129)  # 蓝 -> 绿，呼应官网强调色
    for y in range(S):
        for_x = None
        t = y / (S - 1)
        col = lerp(c1, c2, t)
        bd.line([(0, y), (S, y)], fill=col + (255,))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([8, 8, S - 8, S - 8], radius=r, fill=255)
    img.paste(bg, (0, 0), mask)

    # 柔和高光（顶部）
    hi = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hi)
    hd.ellipse([-S*0.3, -S*0.5, S*1.3, S*0.55], fill=(255, 255, 255, 42))
    hi = hi.filter(ImageFilter.GaussianBlur(S * 0.04))
    img = Image.alpha_composite(img, hi)
    d = ImageDraw.Draw(img)

    white = (255, 255, 255, 255)
    # 下载箭头（↓ + 托盘）：粗线条
    lw = int(S * 0.055)
    # 竖杆
    d.rounded_rectangle([S*0.47, S*0.24, S*0.53, S*0.60], radius=lw//2, fill=white)
    # 箭头两翼
    d.line([(S*0.33, S*0.46), (S*0.50, S*0.63), (S*0.67, S*0.46)], fill=white, width=lw, joint="curve")
    # 托盘
    d.rounded_rectangle([S*0.26, S*0.66, S*0.74, S*0.72], radius=lw//2, fill=white)
    # 闪电（叠在上方偏左，黄色）
    yellow = (255, 214, 64, 255)
    flash = [(S*0.60, S*0.16), (S*0.44, S*0.40), (S*0.55, S*0.40),
             (S*0.42, S*0.60), (S*0.66, S*0.34), (S*0.55, S*0.34), (S*0.70, S*0.16)]
    d.polygon(flash, fill=yellow)

    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    sizes = [16, 24, 32, 48, 64, 128, 256]
    ico_path = os.path.join(HERE, "app.ico")
    img = make(256)
    img.save(ico_path, format="ICO", sizes=[(s, s) for s in sizes])
    print("ICO:", ico_path, os.path.getsize(ico_path), "bytes")

    png_path = os.path.join(HERE, "site", "favicon.png")
    os.makedirs(os.path.dirname(png_path), exist_ok=True)
    make(128).save(png_path, format="PNG")
    print("PNG:", png_path, os.path.getsize(png_path), "bytes")

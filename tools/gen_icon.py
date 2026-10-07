#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AlphaSun 声波分析仪 · 彩色声波 LOGO 生成器 (v0.04 新视觉)

设计理念「声波 · 太阳」
  核心图形 = 一组**左右对称的声波柱阵**（代表声波/频谱），
  柱高按 |sin| 型包络衰减，视觉上形成一个**向外辐射的环**（代表太阳/全向感知）。
  颜色取软件主色系：青(#22D3EE) → 蓝(#3B82F6) → 紫(#A855F7) → 品红(#EC4899) → 橙(#FB923C)，
  即「声波从中心向外、能量与频率同步升高」的彩虹频谱感 —— 与 App 内「经典环谱」
  径向彩虹频谱柱同一视觉语言。

为什么柱高用 |sin| 包络而不是随机高度：
  随机高度在 48px 尺寸下会糊成一团；|sin| 包络保证任何缩放下轮廓都干净可辨。

输出（单一视觉源，全平台一致）：
  assets/icon.png                                   1024 满幅主图
  assets/icon.svg                                   矢量（PWA / favicon / 文档插图）
  assets/icon-foreground.png                        1024 透明前景（安卓自适应图标）
  assets/icon.ico                                   Windows 多尺寸
  android/.../mipmap-*/ic_launcher.png              满幅
  android/.../mipmap-*/ic_launcher_round.png        圆形安全区
  android/.../mipmap-*/ic_launcher_foreground.png   透明前景
  android/.../drawable-v24/ic_launcher_foreground.xml  自适应图标矢量前景
  android/.../values/ic_launcher_background.xml     自适应图标背景色

用法：
  python tools/gen_icon.py --preview   # 只出 assets/_logo-preview.png 供肉眼确认
  python tools/gen_icon.py             # 生成全部
依赖：Pillow
"""
import math, os, argparse
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "assets")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# ─────────────────────────── 配色 ───────────────────────────
BG_TOP = (10, 16, 34)      # #0A1022 深空底（上）
BG_BOT = (4, 7, 18)        # #040712 深空底（下）
# 声波彩虹：内圈（低频·青）→ 外圈（高频·橙）
WAVE = [
    (34, 211, 238),   # #22D3EE 青
    (59, 130, 246),   # #3B82F6 蓝
    (139, 92, 246),   # #8B5CF6 靛
    (168, 85, 247),   # #A855F7 紫
    (236, 72, 153),   # #EC4899 品红
    (251, 146, 60),   # #FB923C 橙
]
CORE = (224, 250, 255)     # #E0FAFF 中心核近白青
ADAPT_BG = "#080D1C"       # 安卓自适应背景实心色

# ─────────────────────────── 几何 ───────────────────────────
N_BARS = 24                # 声波柱数量（偶数 → 左右严格对称）
R_OUT = 0.455              # 柱阵外圈半径（占画布比例）
R_IN = 0.150               # 柱阵内圈半径（中心留白给核）
BAR_W = 0.030              # 柱宽
GAP_DEG = 1.30             # 相邻柱角间隙（度）


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def wave_color(t):
    """t: 0(内/低频·青) → 1(外/高频·橙)"""
    t = max(0.0, min(1.0, t))
    seg = t * (len(WAVE) - 1)
    i = int(seg)
    if i >= len(WAVE) - 1:
        return WAVE[-1]
    return lerp(WAVE[i], WAVE[i + 1], seg - i)


def bar_geom(k):
    """第 k 根柱的几何量 → (角度θ, 环向参数 t, 归一化柱长 env, 柱宽 w, 颜色)。

    角度：θ = k × 360/N_BARS，从正上方起顺时针均布整圈。
        N_BARS 取偶数时该点集关于**竖直轴严格镜像对称**
        （θ → 180°−θ 等价于 k → N_BARS/2−k，仍在集合内），
        所以左右两侧柱长天然一致 —— 这是"左右对称"的数学保证。

    柱长：以 e = |cosθ|（到竖直轴的角距离）做缓和调制，竖直方向稍长、左右稍短，
        形成"竖直张开的声场"轮廓，比等长柱阵更有主次。
        幅度刻意压小（0.84~1.0），否则左右两侧看起来像缺口。

    颜色：**整圈连续彩虹**（t = k/N_BARS），与 App 内「经典环谱」的径向
        彩虹频谱柱同一视觉语言 —— 图标就是软件主可视化的缩影。
        试过"按 |cosθ| 上色"让左右同色，但 1024px 下颜色只在上下一跳、
        左右糊成同一坨青，48px 更是只剩两团，故放弃。

    ⚠ 早期版本用 off=(k+1)/2 再对奇数取负造对称，角度被压到 ±(N/4)，
      整圈柱阵只朝正下方辐射；均布 + |cosθ| 调制才正确。
    """
    th = 2.0 * math.pi * k / N_BARS              # 从正上方起顺时针
    t = k / float(N_BARS)                        # 0→1 整圈走完彩虹
    e = abs(math.cos(th))                        # 1=正上/正下，0=正左/正右
    env = 0.84 + 0.16 * e                         # 幅度小，整体饱满不缺口
    w = BAR_W * (1.0 - 0.14 * (1.0 - e))          # 竖直略粗，横向略细
    return th, t, env, w, wave_color(t)


def polar(cx, cy, th, r):
    """极坐标 → 直角坐标。th 从**正上方**起顺时针（与 bar_geom 的定义一致）。"""
    return cx + r * math.sin(th), cy - r * math.cos(th)


def draw_logo(size, supersample=3, with_bg=True, padding=0.0, circular=False):
    """
    with_bg=False → 透明背景（安卓自适应图标前景）
    padding     → 内容外缩比例（自适应图标前景需留安全区）
    circular    → True 时裁进圆形
    """
    S = size * supersample
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    if with_bg:
        # 垂直线性渐变底
        bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        d = ImageDraw.Draw(bg)
        for y in range(S):
            d.line([(0, y), (S, y)], fill=lerp(BG_TOP, BG_BOT, y / max(1, S - 1)) + (255,))
        # 中心青色辉光
        glow = Image.new("L", (S, S), 0)
        gd = ImageDraw.Draw(glow)
        gr, steps = S * 0.62, 46
        for i in range(steps, 0, -1):
            r = gr * i / steps
            gd.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r],
                       fill=int(74 * (1 - i / steps) ** 2.1))
        halo = Image.new("RGBA", (S, S), (56, 200, 255, 0))
        halo.putalpha(glow.filter(ImageFilter.GaussianBlur(S / 90)))
        img = Image.alpha_composite(bg, halo)

    lay = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    cx = cy = S / 2.0
    scale = 1.0 - padding * 2
    r_out, r_in = S * R_OUT * scale, S * R_IN * scale
    span = r_out - r_in

    for k in range(N_BARS):
        th, t, env, w, col = bar_geom(k)
        bw = max(1, int(round(w * S * scale)))
        p0 = polar(cx, cy, th, r_in)
        p1 = polar(cx, cy, th, r_in + span * env)
        d.line([p0, p1], fill=col + (255,), width=bw)
        r = bw / 2.0
        d.ellipse([p1[0] - r, p1[1] - r, p1[0] + r, p1[1] + r], fill=col + (255,))

    # 旋转刻度环（仪器感，呼应 App 内徽标）
    ring_r = r_out + S * 0.052 * scale
    ring_w = max(1, int(S * 0.0055))
    for i in range(72):
        a0 = i * (360 / 72)
        d.arc([cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r],
              a0, a0 + 3.4, fill=(120, 200, 255, 150), width=ring_w)

    # 中心核：实心 + 双环（太阳 / 采集点）
    cr = S * 0.082 * scale
    d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=CORE + (255,))
    cr2 = cr * 1.72
    d.ellipse([cx - cr2, cy - cr2, cx + cr2, cy + cr2],
              outline=(125, 226, 255, 235), width=max(1, int(S * 0.012)))

    if with_bg:
        img = Image.alpha_composite(img, lay.filter(ImageFilter.GaussianBlur(S / 42)))
    img = Image.alpha_composite(img, lay)

    if circular:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
        img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


# ─────────────────────── SVG（矢量输出）───────────────────────
def build_svg():
    W = 1024
    cx = cy = W / 2.0
    r_out, r_in = W * R_OUT, W * R_IN
    span = r_out - r_in
    segs = []
    for k in range(N_BARS):
        th, t, env, w, col = bar_geom(k)
        x0, y0 = polar(cx, cy, th, r_in)
        x1, y1 = polar(cx, cy, th, r_in + span * env)
        segs.append(f'<line x1="{x0:.1f}" y1="{y0:.1f}" x2="{x1:.1f}" y2="{y1:.1f}" '
                    f'stroke="#{col[0]:02X}{col[1]:02X}{col[2]:02X}" '
                    f'stroke-width="{w*W:.1f}" stroke-linecap="round"/>')
    ring_r = r_out + W * 0.052
    ticks = []
    for i in range(72):
        a0, a1 = math.radians(i * 5.0), math.radians(i * 5.0 + 3.4)
        tx0, ty0 = polar(cx, cy, a0, ring_r)
        tx1, ty1 = polar(cx, cy, a1, ring_r)
        ticks.append(f'<line x1="{tx0:.1f}" y1="{ty0:.1f}" x2="{tx1:.1f}" y2="{ty1:.1f}" '
                     'stroke="#78C8FF" stroke-opacity=".6" stroke-width="5.6" stroke-linecap="round"/>')
    cr = W * 0.082
    return "\n".join([
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {W}" width="{W}" height="{W}">',
        '<defs>',
        f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="rgb{BG_TOP}"/><stop offset="1" stop-color="rgb{BG_BOT}"/></linearGradient>',
        '<radialGradient id="halo" cx=".5" cy=".5" r=".5">'
        '<stop offset="0" stop-color="#37C8FF" stop-opacity=".30"/>'
        '<stop offset="1" stop-color="#37C8FF" stop-opacity="0"/></radialGradient>',
        '<filter id="soft" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="12"/></filter>',
        '</defs>',
        f'<rect width="{W}" height="{W}" fill="url(#bg)"/>',
        f'<circle cx="{cx}" cy="{cy}" r="{W*0.31}" fill="url(#halo)"/>',
        '<g filter="url(#soft)" opacity=".5">' + ''.join(segs) + '</g>',
        '<g>' + ''.join(segs) + '</g>',
        '<g>' + ''.join(ticks) + '</g>',
        f'<circle cx="{cx}" cy="{cy}" r="{cr:.1f}" fill="#E0FAFF"/>',
        f'<circle cx="{cx}" cy="{cy}" r="{cr*1.72:.1f}" fill="none" stroke="#7DE2FF" stroke-width="12"/>',
        '</svg>'])


# ─────────────────── 安卓自适应图标前景（矢量）───────────────────
def build_android_fg_vector():
    """108dp 视口；自适应图标安全区为中央 72dp，故内容缩到 72/108。"""
    S, k = 108.0, 72.0 / 108.0
    cx = cy = S / 2.0
    r_out, r_in = R_OUT * S * k, R_IN * S * k
    span = r_out - r_in
    p = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    android:width="108dp" android:height="108dp"',
         '    android:viewportWidth="108" android:viewportHeight="108">']
    for i in range(N_BARS):
        th, t, env, w, col = bar_geom(i)
        x0, y0 = polar(cx, cy, th, r_in)
        x1, y1 = polar(cx, cy, th, r_in + span * env)
        p.append(f'    <path android:pathData="M{x0:.2f},{y0:.2f} L{x1:.2f},{y1:.2f}"')
        p.append(f'        android:strokeColor="#{col[0]:02X}{col[1]:02X}{col[2]:02X}"'
                 f' android:strokeWidth="{w*S*k:.2f}" android:strokeLineCap="round"/>')
    ring_r = r_out + S * 0.052 * k
    for i in range(72):
        a0, a1 = i * 5.0, i * 5.0 + 3.4
        tx0, ty0 = polar(cx, cy, math.radians(a0), ring_r)
        tx1, ty1 = polar(cx, cy, math.radians(a1), ring_r)
        p.append(f'    <path android:pathData="M{tx0:.2f},{ty0:.2f} L{tx1:.2f},{ty1:.2f}"')
        p.append('        android:strokeColor="#78C8FF" android:strokeAlpha="0.6"'
                 f' android:strokeWidth="{2.6:.2f}" android:strokeLineCap="round"/>')
    cr = 0.082 * S * k
    p.append(f'    <path android:pathData="M{cx-cr:.2f},{cy} a{cr:.2f},{cr:.2f} 0 1,0 {2*cr:.2f},0'
             f' a{cr:.2f},{cr:.2f} 0 1,0 {-2*cr:.2f},0" android:fillColor="#E0FAFF"/>')
    p.append(f'    <path android:pathData="M{cx-cr*1.72:.2f},{cy} a{cr*1.72:.2f},{cr*1.72:.2f} 0 1,0'
             f' {2*cr*1.72:.2f},0 a{cr*1.72:.2f},{cr*1.72:.2f} 0 1,0 {-2*cr*1.72:.2f},0"')
    p.append('        android:strokeColor="#7DE2FF" android:strokeWidth="1.5"/>')
    p.append('</vector>')
    return "\n".join(p)


# ─────────────────────────── 输出 ───────────────────────────
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def ensure(d):
    os.makedirs(d, exist_ok=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--preview', action='store_true', help='只出预览图')
    a = ap.parse_args()
    ensure(ASSETS)

    if a.preview:
        sizes = [192, 96, 48]
        W = 1024 + sum(sizes) + 20 * (len(sizes) + 1)
        sheet = Image.new("RGBA", (W, 1064), (18, 22, 34, 255))
        d = ImageDraw.Draw(sheet)
        d.text((20, 22), "1024 (main)", fill=(200, 220, 255, 255))
        sheet.paste(draw_logo(1024), (20, 50))
        x = 1064
        for s in sizes:
            d.text((x, 22), str(s), fill=(200, 220, 255, 255))
            sheet.paste(draw_logo(s), (x, 50))
            x += s + 20
        out = os.path.join(ASSETS, "_logo-preview.png")
        sheet.save(out)
        print("预览 →", out)
        return

    draw_logo(1024).save(os.path.join(ASSETS, "icon.png"))
    print("✓ assets/icon.png")

    fg = draw_logo(1024, with_bg=False, padding=0.145)
    fg.save(os.path.join(ASSETS, "icon-foreground.png"))
    print("✓ assets/icon-foreground.png")

    with open(os.path.join(ASSETS, "icon.svg"), "w", encoding="utf-8") as f:
        f.write(build_svg())
    print("✓ assets/icon.svg")

    draw_logo(256).save(os.path.join(ASSETS, "icon.ico"), format="ICO",
                        sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("✓ assets/icon.ico")

    for dpi, sz in DENS.items():
        d = os.path.join(RES, "mipmap-" + dpi)
        ensure(d)
        draw_logo(sz).save(os.path.join(d, "ic_launcher.png"))
        draw_logo(sz, circular=True).save(os.path.join(d, "ic_launcher_round.png"))
        fg.resize((sz, sz), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"✓ mipmap-{dpi} ({sz}px)")

    vd = os.path.join(RES, "drawable-v24")
    ensure(vd)
    with open(os.path.join(vd, "ic_launcher_foreground.xml"), "w", encoding="utf-8") as f:
        f.write(build_android_fg_vector())
    print("✓ drawable-v24/ic_launcher_foreground.xml")

    vv = os.path.join(RES, "values")
    ensure(vv)
    with open(os.path.join(vv, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="ic_launcher_background">{ADAPT_BG}</color>\n</resources>\n')
    print("✓ values/ic_launcher_background.xml")
    print("\n全部图标已生成（单一视觉源：声波柱阵 · 彩虹渐变 · 中心核）")


if __name__ == "__main__":
    main()

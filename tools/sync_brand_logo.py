#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 gen_icon.py 的 LOGO 几何**内联注入**到 index.html 的顶栏徽标 <svg class="logo">。

为什么用脚本注入而不是手工复制：
  顶栏徽标与安装图标必须**像素级同源**（同一套柱阵坐标/同一套彩虹配色）。
  手工维护两份几何，迟早会漂移 —— 而 validate 只查 DOM id 与版本号，查不出视觉漂移。
  本脚本从 gen_icon 直接算，改配色只需改一处、两端同时生效。

执行：
  python tools/sync_brand_logo.py           # 注入 / 更新 index.html 里的徽标
  python tools/sync_brand_logo.py --svg     # 顺便导出 .workbuddy/_brandlogo.svg 供肉眼比对

幂等：重复执行结果一致（按 <!--LOGO:BEGIN--> 标记整段替换）。
"""
import math, os, sys, importlib.util, argparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HTML = os.path.join(ROOT, 'index.html')
BEGIN = '<!--LOGO:BEGIN-->'
END = '<!--LOGO:END-->'
V = 48.0                 # 顶栏徽标视口（与旧徽标一致，不改变布局尺寸）


def load_gen():
    spec = importlib.util.spec_from_file_location('gi', os.path.join(ROOT, 'tools', 'gen_icon.py'))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def build_svg(gi):
    cx = cy = V / 2
    r_out, r_in = gi.R_OUT * V, gi.R_IN * V
    span = r_out - r_in
    rows = []
    for i in range(gi.N_BARS):
        th, t, env, w, col = gi.bar_geom(i)
        x0, y0 = gi.polar(cx, cy, th, r_in)
        x1, y1 = gi.polar(cx, cy, th, r_in + span * env)
        rows.append(f'          <line x1="{x0:.2f}" y1="{y0:.2f}" x2="{x1:.2f}" y2="{y1:.2f}"'
                    f' stroke="#{col[0]:02X}{col[1]:02X}{col[2]:02X}" stroke-width="{w*V:.2f}"/>')
    ticks = []
    ring_r = r_out + V * 0.052
    for i in range(36):                      # 36 格（安装图标是 72 格，48px 下 36 格更清晰）
        a0, a1 = math.radians(i * 10.0), math.radians(i * 10.0 + 4.2)
        tx0, ty0 = gi.polar(cx, cy, a0, ring_r)
        tx1, ty1 = gi.polar(cx, cy, a1, ring_r)
        ticks.append(f'          <line x1="{tx0:.2f}" y1="{ty0:.2f}" x2="{tx1:.2f}" y2="{ty1:.2f}"'
                     ' stroke="#78C8FF" stroke-opacity=".5" stroke-width="1.1" stroke-linecap="round"/>')
    cr = 0.082 * V
    cr2 = cr * 1.72
    return f'''<svg class="logo" viewBox="0 0 48 48" role="img" aria-label="AlphaSun 声波分析仪徽标">
        <defs>
          <radialGradient id="lgHalo" cx=".5" cy=".5" r=".5">
            <stop offset="0" stop-color="#37C8FF" stop-opacity=".26"/>
            <stop offset="1" stop-color="#37C8FF" stop-opacity="0"/>
          </radialGradient>
        </defs>
        <circle cx="24" cy="24" r="14.5" fill="url(#lgHalo)"/>
        <g class="lg-bars" stroke-linecap="round">
{chr(10).join(rows)}
        </g>
        <g class="lg-tick">
{chr(10).join(ticks)}
        </g>
        <circle cx="24" cy="24" r="{cr2:.2f}" fill="none" stroke="#7DE2FF" stroke-width="1.5"/>
        <circle cx="24" cy="24" r="{cr:.2f}" fill="#E0FAFF"/>
      </svg>'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--svg', action='store_true', help='同时导出独立 svg 供比对')
    a = ap.parse_args()
    gi = load_gen()
    svg = build_svg(gi)

    if a.svg:
        p = os.path.join(ROOT, '.workbuddy', '_brandlogo.svg')
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(svg)
        print('导出 →', p)

    s = open(HTML, encoding='utf-8').read()
    if BEGIN in s and END in s:
        i, j = s.index(BEGIN), s.index(END) + len(END)
        s = s[:i] + f'{BEGIN}\n      {svg}\n      {END}' + s[j:]
        act = '更新'
    else:
        # 首次：替换 <svg class="logo" …> … </svg> 这一整段
        i = s.index('<svg class="logo"')
        j = s.index('</svg>', i) + len('</svg>')
        s = s[:i] + f'{BEGIN}\n      {svg}\n      {END}' + s[j:]
        act = '注入'
    open(HTML, 'w', encoding='utf-8').write(s)
    print(f'✓ 顶栏徽标已{act}（{len(svg)} 字节，与安装图标同源）')


if __name__ == '__main__':
    main()

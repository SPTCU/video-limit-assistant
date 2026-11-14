# -*- coding: utf-8 -*-
"""
「刷视频限时助手」宣传片渲染器
主题：短视频不应限时，而是要懂得休息

用法：
    python make_film.py preview     # 渲染若干关键帧到 promo/preview/
    python make_film.py render      # 渲染整片到 promo/宣传片.mp4

输出：1080x1920 / 30fps / H.264
"""

import os
import sys
import math
import subprocess

from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageFilter, ImageChops

# ---------------------------------------------------------------- 基础参数

W, H = 1080, 1920
FPS = 30
TOTAL = 51.2

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ICON_PATH = os.path.join(ROOT, "app", "src", "main", "res",
                         "mipmap-xxxhdpi", "ic_launcher.png")
OUT_DIR = os.path.join(ROOT, "promo")

# 色板（与 App 内一致的品牌色）
C_BG = (10, 12, 18)
C_BLUE = (78, 110, 242)
C_BLUE2 = (126, 150, 255)
C_ORANGE = (255, 107, 53)
C_WHITE = (255, 255, 255)
C_GRAY = (140, 146, 168)
C_DIM = (72, 78, 96)

FONT_BOLD_PATH = "C:/Windows/Fonts/msyhbd.ttc"
FONT_REG_PATH = "C:/Windows/Fonts/msyh.ttc"

_fonts = {}


def F(size, bold=True):
    key = (size, bold)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_BOLD_PATH if bold else FONT_REG_PATH,
                                         int(size))
    return _fonts[key]


# ---------------------------------------------------------------- 缓动工具


def clamp01(x):
    return 0.0 if x < 0 else (1.0 if x > 1 else x)


def seg(t, a, b):
    """t 在 [a,b] 区间的进度 0..1"""
    if b <= a:
        return 1.0 if t >= b else 0.0
    return clamp01((t - a) / (b - a))


def ease_out(x):
    x = clamp01(x)
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    x = clamp01(x)
    return x * x * (3 - 2 * x)


def lerp_color(c1, c2, k):
    k = clamp01(k)
    return tuple(int(c1[i] + (c2[i] - c1[i]) * k) for i in range(3))


# ---------------------------------------------------------------- 绘图工具


def txt(layer, cx, cy, s, font, color, alpha=255, anchor="mm"):
    if alpha <= 1 or not s:
        return
    d = ImageDraw.Draw(layer)
    d.text((cx, cy), s, font=font, fill=color + (int(min(255, alpha)),), anchor=anchor)


def txt_typed(layer, cx, cy, s, font, color, t, start, per=0.06, fade=0.4, k=1.0,
              end_start=None, end_keep=0.55):
    """逐字淡入，整行水平居中；end_start 之后整体压暗，把视线让给下文"""
    if t < start:
        return
    tail = 1.0
    if end_start is not None:
        tail = 1.0 - (1.0 - end_keep) * ease_out(seg(t, end_start, end_start + 0.7))
    d = ImageDraw.Draw(layer)
    widths = [d.textlength(ch, font=font) for ch in s]
    total = sum(widths)
    x = cx - total / 2
    for i, ch in enumerate(s):
        a = ease_out(clamp01((t - start - i * per) / fade))
        if a > 0.01:
            d.text((x, cy), ch, font=font, fill=color + (int(255 * a * k * tail),), anchor="lm")
        x += widths[i]


_glow_cache = {}


def glow_square(diameter, color, peak=60):
    """
    生成一张方形柔光贴图。注意 Pillow 的 radial_gradient 边缘中点只有 181、
    四角才是 255，直接用会在贴图四周留下可见的方形边框；这里重新归一化，
    让内切圆之外彻底透明。
    """
    key = (diameter, color, peak)
    if key in _glow_cache:
        return _glow_cache[key]

    base = Image.radial_gradient("L")
    edge = base.getpixel((0, base.size[1] // 2))     # 边缘中点原值（通常 181）
    lo = 255 - edge                                  # invert 后该处的值
    span = max(1, 255 - lo)
    mask = ImageOps.invert(base).point(
        lambda v: int(peak * max(0.0, (v - lo)) / span))
    mask = mask.resize((diameter, diameter), Image.BILINEAR)

    g = Image.new("RGBA", (diameter, diameter), color + (255,))
    g.putalpha(mask)
    _glow_cache[key] = g
    return g


def make_background():
    """深色底 + 蓝紫柔光，一次生成全程复用"""
    bg = Image.new("RGB", (W, H), C_BG).convert("RGBA")

    g1 = glow_square(1700, (46, 66, 168), 74)
    bg.alpha_composite(g1, (540 - 850, 620 - 850))

    g2 = glow_square(1100, (96, 60, 140), 46)
    bg.alpha_composite(g2, (760 - 550, 1500 - 550))

    # 极淡的暗角，让中心更聚焦
    vig = Image.new("L", (W, H), 0)
    dv = ImageDraw.Draw(vig)
    dv.ellipse([-W * 0.35, -H * 0.1, W * 1.35, H * 1.1], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(220)).point(lambda v: 255 - int(v * 0.38))
    dark = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    dark.putalpha(vig)
    bg.alpha_composite(dark)

    bg = bg.convert("RGB")
    # 静态细颗粒：深色大面积渐变在 h264 下很容易出现色带，加一层固定抖动可以打散
    grain = Image.effect_noise((W, H), 6).convert("RGB")
    bg = ImageChops.add(bg, grain, scale=1.0, offset=-128)

    return bg


# ---------------------------------------------------------------- 手机界面


UI_BG = (244, 245, 250)
UI_CARD = (255, 255, 255)
UI_TEXT = (25, 28, 43)
UI_SUB = (107, 112, 128)
UI_DIV = (230, 232, 240)


def draw_phone_shell(layer, x, y, w, h, k):
    d = ImageDraw.Draw(layer)
    a = int(255 * k)
    # 机身
    d.rounded_rectangle([x - 4, y - 4, x + w + 4, y + h + 4], radius=int(w * 0.115),
                        fill=(22, 25, 36, a), outline=(58, 64, 84, a), width=3)
    # 屏幕
    d.rounded_rectangle([x, y, x + w, y + h], radius=int(w * 0.10),
                        fill=UI_BG + (a,))
    # 刘海
    d.rounded_rectangle([x + w * 0.38, y + 14, x + w * 0.62, y + 14 + 22],
                        radius=11, fill=(22, 25, 36, a))


def card(layer, box, radius, color=UI_CARD, alpha=255):
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=radius, fill=color + (alpha,))


def draw_home_screen(layer, x, y, w, h, k, progress, remain_text, managed_names):
    """App 首页：正在刷 + 进度条"""
    s = w / 620.0          # 相对 620 宽的缩放
    a = lambda v: int(v * k)
    d = ImageDraw.Draw(layer)

    # 顶部标题
    d.text((x + 44 * s, y + 118 * s), "刷视频限时助手", font=F(27 * s),
           fill=UI_TEXT + (a(255),), anchor="lm")
    d.text((x + 44 * s, y + 156 * s), "刷一会儿就被请出去，休息一下再回来",
           font=F(16 * s, False), fill=UI_SUB + (a(230),), anchor="lm")

    # 主状态卡
    cy = y + 196 * s
    ch = 292 * s
    card(layer, [x + 26 * s, cy, x + w - 26 * s, cy + ch], int(22 * s), UI_CARD, a(255))

    lx = x + 50 * s
    d.text((lx, cy + 38 * s), "当前状态", font=F(15 * s, False),
           fill=UI_SUB + (a(220),), anchor="lm")

    finished = progress >= 0.995
    status = "马上退出" if finished else "正在刷"
    status_col = C_ORANGE if finished else C_BLUE
    d.text((lx, cy + 84 * s), status, font=F(36 * s), fill=status_col + (a(255),), anchor="lm")
    d.text((lx, cy + 130 * s), remain_text, font=F(17 * s, False),
           fill=UI_SUB + (a(235),), anchor="lm")

    # 进度条
    bx0, bx1 = lx, x + w - 50 * s
    by = cy + 184 * s
    bh = 10 * s
    d.rounded_rectangle([bx0, by, bx1, by + bh], radius=bh / 2, fill=UI_DIV + (a(255),))
    pw = (bx1 - bx0) * clamp01(progress)
    if pw > bh:
        d.rounded_rectangle([bx0, by, bx0 + pw, by + bh], radius=bh / 2,
                            fill=status_col + (a(255),))

    d.text((lx, cy + 226 * s), "今日已刷 46 分钟", font=F(16 * s, False),
           fill=UI_SUB + (a(210),), anchor="lm")

    # 节奏设置两行
    ry = cy + ch + 20 * s
    for i, (lab, val) in enumerate([("每刷", "10 分钟"), ("然后休息", "2 分钟")]):
        yy = ry + i * 88 * s
        card(layer, [x + 26 * s, yy, x + w - 26 * s, yy + 74 * s], int(18 * s),
             UI_CARD, a(255))
        d.text((x + 50 * s, yy + 37 * s), lab, font=F(19 * s, False),
               fill=UI_TEXT + (a(255),), anchor="lm")
        d.text((x + w - 50 * s, yy + 37 * s), val, font=F(19 * s),
               fill=UI_TEXT + (a(255),), anchor="rm")

    # 受管应用
    my = ry + 2 * 88 * s + 8 * s
    d.text((x + 50 * s, my), "受管应用", font=F(16 * s, False),
           fill=UI_SUB + (a(220),), anchor="lm")
    chx = x + 50 * s
    for nm in managed_names:
        tw = d.textlength(nm, font=F(15 * s, False)) + 28 * s
        d.rounded_rectangle([chx, my + 22 * s, chx + tw, my + 60 * s],
                            radius=19 * s, fill=(234, 238, 254, a(255)))
        d.text((chx + tw / 2, my + 41 * s), nm, font=F(15 * s, False),
               fill=C_BLUE + (a(255),), anchor="mm")
        chx += tw + 12 * s

    draw_week_card(layer, x, y + 792 * s, w, s, a, k)


def draw_week_card(layer, x, top, w, s, a, k):
    """底部 7 天趋势卡（迷你柱状图）"""
    d = ImageDraw.Draw(layer)
    ch = 168 * s
    card(layer, [x + 26 * s, top, x + w - 26 * s, top + ch], int(18 * s), UI_CARD, a(255))
    d.text((x + 50 * s, top + 34 * s), "最近 7 天", font=F(16 * s, False),
           fill=UI_SUB + (a(225),), anchor="lm")

    vals = [0.36, 0.58, 0.44, 0.78, 0.62, 0.86, 0.40]
    n = len(vals)
    left = x + 74 * s
    right = x + w - 74 * s
    gap = 24 * s
    bw = (right - left - gap * (n - 1)) / n
    base = top + ch - 22 * s
    maxh = 86 * s
    for i, v in enumerate(vals):
        bh = max(8 * s, maxh * v)
        bx = left + i * (bw + gap)
        is_today = (i == n - 2)
        col = C_BLUE if is_today else (216, 223, 249)
        d.rounded_rectangle([bx, base - bh, bx + bw, base], radius=6 * s,
                            fill=col + (a(255 if is_today else 240),))


def draw_rest_screen(layer, x, y, w, h, k, countdown, managed_names):
    """App 首页：休息中 + 倒计时"""
    s = w / 620.0
    a = lambda v: int(v * k)
    d = ImageDraw.Draw(layer)

    d.text((x + 44 * s, y + 118 * s), "刷视频限时助手", font=F(27 * s),
           fill=UI_TEXT + (a(255),), anchor="lm")
    d.text((x + 44 * s, y + 156 * s), "刷一会儿就被请出去，休息一下再回来",
           font=F(16 * s, False), fill=UI_SUB + (a(230),), anchor="lm")

    cy = y + 196 * s
    ch = 292 * s
    card(layer, [x + 26 * s, cy, x + w - 26 * s, cy + ch], int(22 * s), UI_CARD, a(255))

    lx = x + 50 * s
    d.text((lx, cy + 38 * s), "当前状态", font=F(15 * s, False),
           fill=UI_SUB + (a(220),), anchor="lm")
    d.text((lx, cy + 84 * s), "休息中", font=F(36 * s), fill=C_ORANGE + (a(255),), anchor="lm")
    d.text((lx, cy + 130 * s), "抖音 · 还有 " + countdown + " 才能再打开",
           font=F(17 * s, False), fill=UI_SUB + (a(235),), anchor="lm")

    # 静止的进度条（已清空）
    bx0, bx1 = lx, x + w - 50 * s
    by = cy + 184 * s
    bh = 10 * s
    d.rounded_rectangle([bx0, by, bx1, by + bh], radius=bh / 2, fill=UI_DIV + (a(255),))

    d.text((lx, cy + 226 * s), "刚刚连续刷了 10 分钟，先歇一会儿",
           font=F(16 * s, False), fill=UI_SUB + (a(210),), anchor="lm")

    # 冷却进度（橙色，逐渐缩短）
    ry = cy + ch + 20 * s
    card(layer, [x + 26 * s, ry, x + w - 26 * s, ry + 146 * s], int(18 * s), UI_CARD, a(255))
    d.text((x + 50 * s, ry + 42 * s), "冷却剩余", font=F(17 * s, False),
           fill=UI_SUB + (a(235),), anchor="lm")
    d.text((x + w - 50 * s, ry + 42 * s), countdown, font=F(22 * s),
           fill=C_ORANGE + (a(255),), anchor="rm")

    cby = ry + 88 * s
    d.rounded_rectangle([x + 50 * s, cby, x + w - 50 * s, cby + 12 * s],
                        radius=6 * s, fill=UI_DIV + (a(255),))
    frac = 1.0
    try:
        mm, ss = countdown.split(":")
        frac = (int(mm) * 60 + int(ss)) / 120.0
    except Exception:
        frac = 1.0
    cw = (w - 100 * s) * clamp01(frac)
    if cw > 6 * s:
        d.rounded_rectangle([x + 50 * s, cby, x + 50 * s + cw, cby + 12 * s],
                            radius=6 * s, fill=C_ORANGE + (a(255),))

    my = ry + 146 * s + 16 * s
    d.text((x + 50 * s, my), "受管应用", font=F(16 * s, False),
           fill=UI_SUB + (a(220),), anchor="lm")
    chx = x + 50 * s
    for nm in managed_names:
        tw = d.textlength(nm, font=F(15 * s, False)) + 28 * s
        d.rounded_rectangle([chx, my + 22 * s, chx + tw, my + 60 * s],
                            radius=19 * s, fill=(234, 238, 254, a(255)))
        d.text((chx + tw / 2, my + 41 * s), nm, font=F(15 * s, False),
               fill=C_BLUE + (a(255),), anchor="mm")
        chx += tw + 12 * s

    draw_week_card(layer, x, y + 792 * s, w, s, a, k)


# ---------------------------------------------------------------- 场景


def scene_1(layer, t, dur, k):
    """共鸣：深夜停不下来"""
    a1 = ease_out(seg(t, 0.25, 1.15)) * (1 - ease_out(seg(t, 2.95, 3.45)))
    a2 = ease_out(seg(t, 0.75, 1.65)) * (1 - ease_out(seg(t, 2.95, 3.45)))
    txt(layer, 540, 812, "凌晨一点", F(40, False), C_GRAY, 255 * a1 * k)
    txt(layer, 540, 908, "你还清醒着", F(94), C_WHITE, 255 * a2 * k)

    b1 = ease_out(seg(t, 3.5, 4.35))
    b2 = ease_out(seg(t, 3.85, 4.75))
    b3 = ease_out(seg(t, 4.75, 5.75))
    txt(layer, 540, 790, "手指一直往上滑", F(94), C_WHITE, 255 * b1 * k)
    txt(layer, 540, 918, "不是因为好看", F(34, False), C_GRAY, 255 * b2 * k * 0.95)
    txt(layer, 540, 988, "是因为停不下来", F(52, False), C_GRAY, 255 * b3 * k)

    # 底部一条缓慢生长的时间线
    g = ease_out(seg(t, 0.4, 6.2))
    d = ImageDraw.Draw(layer)
    lw = 360 * g
    if lw > 2:
        d.rounded_rectangle([540 - lw / 2, 1452, 540 + lw / 2, 1454],
                            radius=1, fill=(70, 78, 102, int(150 * k)))


def scene_2(layer, t, dur, k):
    """否定：那些办法都没用"""
    a0 = ease_out(seg(t, 0.1, 0.85))
    txt(layer, 540, 610, "我们试过所有办法", F(46, False), C_GRAY, 255 * a0 * k)

    items = ["定闹钟", "删掉 App", "设置每日限额"]
    dimk = ease_out(seg(t, 2.05, 2.90))
    for i, s in enumerate(items):
        aa = ease_out(seg(t, 0.65 + i * 0.46, 1.35 + i * 0.46))
        if aa <= 0.01:
            continue
        col = lerp_color(C_WHITE, (98, 104, 126), dimk)
        y = 760 + i * 138
        txt(layer, 540, y, s, F(70), col, 255 * aa * k)
        # 左侧小圆点
        d = ImageDraw.Draw(layer)
        d.ellipse([540 - 300, y - 7, 540 - 286, y + 7],
                  fill=lerp_color(C_BLUE, (98, 104, 126), dimk) + (int(200 * aa * k),))

    c1 = ease_out(seg(t, 3.15, 3.95))
    txt(layer, 540, 1210, "都没用", F(104), C_ORANGE, 255 * c1 * k)

    c2 = ease_out(seg(t, 4.35, 5.35))
    txt(layer, 540, 1345, "限时只是把瘾按住，没让它消失", F(34, False),
        C_GRAY, 255 * c2 * k)


def scene_3(layer, t, dur, k):
    """立论：短视频不该被限时"""
    txt_typed(layer, 540, 700, "短视频不该被限时", F(82), C_WHITE, t, 0.15, 0.075, 0.45, k,
              end_start=2.55)

    a2 = ease_out(seg(t, 2.15, 2.95))
    txt(layer, 540, 878, "你需要的是", F(42, False), C_GRAY, 255 * a2 * k)

    a3 = ease_out(seg(t, 2.75, 3.65))
    if a3 > 0.01:
        g = glow_square(900, (60, 90, 220), 90)
        gk = g.copy()
        gk.putalpha(gk.getchannel("A").point(lambda v: int(v * a3 * k)))
        layer.alpha_composite(gk, (540 - 450, 1050 - 450))
    txt(layer, 540, 1050, "懂得休息", F(122), C_BLUE2, 255 * a3 * k)

    a4 = ease_out(seg(t, 3.9, 4.8))
    txt(layer, 540, 1210, "把它切成有节奏的小段", F(34, False), C_GRAY, 255 * a4 * k)


ICON = None


def scene_4(layer, t, dur, k):
    """产品登场"""
    global ICON
    a = ease_out(seg(t, 0.05, 0.85))
    if a > 0.01 and ICON is not None:
        size = int(268 * (0.88 + 0.12 * ease_out(seg(t, 0.05, 1.05))))
        ic = ICON.resize((size, size), Image.LANCZOS)
        ic.putalpha(ic.getchannel("A").point(lambda v: int(v * a * k)))
        layer.alpha_composite(ic, (540 - size // 2, 792 - size // 2))

    b = ease_out(seg(t, 0.55, 1.45))
    txt(layer, 540, 1008, "刷视频限时助手", F(62), C_WHITE, 255 * b * k)

    c = ease_out(seg(t, 0.95, 1.85))
    txt(layer, 540, 1104, "不设额度，只给节奏", F(38, False), C_BLUE2, 255 * c * k)


PHONE_W = 620


def phone_pos():
    ph = 1120
    x = (W - PHONE_W) // 2
    y = H - ph - 150
    return x, y, PHONE_W, ph


SWITCH_T = 8.0


def scene_flow(layer, t, dur, k):
    """连续演示：正在刷 -> 到点退出 -> 休息中"""
    if t < SWITCH_T:
        a0 = ease_out(seg(t, 0.1, 0.9))
        a1 = ease_out(seg(t, 0.45, 1.25))
        txt(layer, 540, 300, "连续刷满 10 分钟", F(54), C_WHITE, 255 * a0 * k)
        txt(layer, 540, 372, "到点自动退出，不用你决定", F(34, False), C_GRAY, 255 * a1 * k)
    else:
        tt = t - SWITCH_T
        a0 = ease_out(seg(tt, 0.05, 0.75))
        a1 = ease_out(seg(tt, 0.35, 1.05))
        txt(layer, 540, 300, "然后休息 2 分钟", F(54), C_WHITE, 255 * a0 * k)
        txt(layer, 540, 372, "这期间再点开，会被立刻请出来", F(34, False), C_GRAY, 255 * a1 * k)

    x, y, w, h = phone_pos()
    # 切换瞬间做一次轻微淡出淡入，像界面自己刷新，避免硬跳
    if t < SWITCH_T:
        fade = 1.0 - 0.30 * seg(t, SWITCH_T - 0.24, SWITCH_T)
    else:
        fade = 0.70 + 0.30 * seg(t, SWITCH_T, SWITCH_T + 0.26)
    kk = k * fade

    draw_phone_shell(layer, x, y, w, h, kk)
    names = ["抖音", "快手", "B 站"]
    if t < SWITCH_T:
        p = 0.30 + 0.70 * ease_in_out(seg(t, 1.0, 7.2))
        remain = 612 - int(612 * clamp01(p))
        remain_text = "抖音 · 还剩 %02d:%02d 自动退出" % (remain // 60, remain % 60)
        draw_home_screen(layer, x, y, w, h, kk, clamp01(p), remain_text, names)
    else:
        left = 118 - int(118 * ease_in_out(seg(t, SWITCH_T + 0.1, SWITCH_T + 8.0)))
        left = max(0, left)
        draw_rest_screen(layer, x, y, w, h, kk,
                         "%d:%02d" % (left // 60, left % 60), names)


def scene_7(layer, t, dur, k):
    """能力清单"""
    a0 = ease_out(seg(t, 0.1, 0.8))
    txt(layer, 540, 556, "还有这些", F(48), C_WHITE, 255 * a0 * k)

    rows = [
        ("自选受管应用", "抖音、快手、B 站、小红书…"),
        ("导航方式适配", "三键导航 / 全面屏手势都能退出"),
        ("7 天趋势", "只统计，不评判"),
        ("不联网 · 无广告", "数据只存在你自己的手机里"),
    ]
    for i, (title, sub) in enumerate(rows):
        aa = ease_out(seg(t, 0.45 + i * 0.42, 1.15 + i * 0.42))
        if aa <= 0.01:
            continue
        y = 730 + i * 232
        d = ImageDraw.Draw(layer)
        # 序号条
        d.rounded_rectangle([152, y - 14, 166, y + 58], radius=7,
                            fill=C_BLUE + (int(210 * aa * k),))
        txt(layer, 200, y + 4, title, F(44), C_WHITE, 255 * aa * k, anchor="lm")
        txt(layer, 200, y + 58, sub, F(31, False), C_GRAY, 255 * aa * k * 0.95, anchor="lm")


def scene_8(layer, t, dur, k):
    """收尾"""
    a1 = ease_out(seg(t, 0.1, 0.9))
    txt(layer, 540, 800, "不靠意志力", F(78), C_WHITE, 255 * a1 * k)

    a2 = ease_out(seg(t, 0.7, 1.55))
    txt(layer, 540, 924, "靠节奏", F(78), C_BLUE2, 255 * a2 * k)

    a3 = ease_out(seg(t, 2.05, 2.85))
    if a3 > 0.01 and ICON is not None:
        size = 132
        ic = ICON.resize((size, size), Image.LANCZOS)
        ic.putalpha(ic.getchannel("A").point(lambda v: int(v * a3 * k)))
        layer.alpha_composite(ic, (540 - size // 2, 1226 - size // 2))

    a4 = ease_out(seg(t, 2.45, 3.2))
    txt(layer, 540, 1346, "刷视频限时助手", F(44), C_WHITE, 255 * a4 * k)

    a5 = ease_out(seg(t, 3.0, 3.9))
    txt(layer, 540, 1438, "现在，去休息一下", F(32, False), C_GRAY, 255 * a5 * k)


SCENES = [
    (0.0, 6.6, scene_1),
    (6.6, 12.6, scene_2),
    (12.6, 18.6, scene_3),
    (18.6, 21.8, scene_4),
    (21.8, 38.8, scene_flow),
    (38.8, 45.2, scene_7),
    (45.2, 51.2, scene_8),
]

BG = None


def render_frame(t):
    img = BG.copy()
    for start, end, fn in SCENES:
        if start <= t < end:
            tl = t - start
            dur = end - start
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            # 场景整体淡入淡出（由 k 传给每个元素，避免全图 alpha 运算）
            k = min(ease_out(seg(tl, 0.0, 0.18)),
                    1.0 - ease_in_out(seg(tl, dur - 0.18, dur)))
            if k <= 0.005:
                break
            fn(layer, tl, dur, k)
            img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
            break
    return img


def init():
    global BG, ICON
    if BG is None:
        BG = make_background()
    if ICON is None:
        ICON = Image.open(ICON_PATH).convert("RGBA")


# ---------------------------------------------------------------- 入口


def do_preview():
    init()
    os.makedirs(os.path.join(OUT_DIR, "preview"), exist_ok=True)
    marks = [3.6, 9.0, 14.5, 19.8, 24.0, 29.4, 30.4, 35.0, 41.0, 48.0]
    for t in marks:
        img = render_frame(t)
        p = os.path.join(OUT_DIR, "preview", "t%05.1f.png" % t)
        img.save(p)
        print("saved", p)


def find_ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    for cand in ["ffmpeg",
                 r"C:/ffmpeg/bin/ffmpeg.exe",
                 r"C:/Users/ADMIN/WorkBuddy/新/tools_tmp/ff/node_modules/"
                 r"@ffmpeg-installer/win32-x64/ffmpeg.exe"]:
        try:
            subprocess.run([cand, "-version"], capture_output=True, check=True)
            return cand
        except Exception:
            continue
    return None


def do_render():
    init()
    os.makedirs(OUT_DIR, exist_ok=True)
    final = os.path.join(OUT_DIR, "宣传片-刷视频限时助手.mp4")
    ff = find_ffmpeg()
    if not ff:
        print("找不到 ffmpeg")
        return False

    # 旧版 ffmpeg 对非 ASCII 路径支持不稳，先写到纯英文目录再搬回来
    tmp_dir = "C:/vl_film"
    os.makedirs(tmp_dir, exist_ok=True)
    out = os.path.join(tmp_dir, "out.mp4")

    cmd = [
        ff, "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-",
        "-an",
        "-c:v", "libx264", "-preset", "slow", "-crf", "15",
        "-x264-params", "aq-mode=3:aq-strength=1.1",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        out,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    total = int(TOTAL * FPS)
    for i in range(total):
        t = i / float(FPS)
        proc.stdin.write(render_frame(t).tobytes())
        if i % 300 == 0:
            print("  frame %d/%d" % (i, total), flush=True)
    proc.stdin.close()
    proc.wait()

    if not os.path.exists(out):
        print("渲染失败，未产生输出文件")
        return False

    import shutil
    shutil.copyfile(out, final)
    print("done:", final, "%.1f MB" % (os.path.getsize(final) / 1048576.0))
    return True


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "preview"
    if mode == "render":
        do_render()
    else:
        do_preview()

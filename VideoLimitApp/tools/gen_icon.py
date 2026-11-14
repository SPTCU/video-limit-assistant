# -*- coding: utf-8 -*-
"""生成应用启动图标：圆角方形底 + 白色钟面 + 指针（纯 Python，无第三方库）"""
import zlib
import struct
import math
import os

OUT_DIRS = {
    48: "mipmap-mdpi",
    72: "mipmap-hdpi",
    96: "mipmap-xhdpi",
    144: "mipmap-xxhdpi",
    192: "mipmap-xxxhdpi",
}

PRIMARY = (78, 110, 242)
ACCENT = (255, 107, 53)
WHITE = (255, 255, 255)


def dist_seg(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    c1 = vx * wx + vy * wy
    if c1 <= 0:
        return math.hypot(px - ax, py - ay)
    c2 = vx * vx + vy * vy
    if c2 <= c1:
        return math.hypot(px - bx, py - by)
    t = c1 / c2
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


def in_rounded(x, y, n, r):
    if x < r:
        if y < r:
            return (x - r) ** 2 + (y - r) ** 2 <= r * r
        if y > n - r:
            return (x - r) ** 2 + (y - (n - r)) ** 2 <= r * r
    if x > n - r:
        if y < r:
            return (x - (n - r)) ** 2 + (y - r) ** 2 <= r * r
        if y > n - r:
            return (x - (n - r)) ** 2 + (y - (n - r)) ** 2 <= r * r
    return True


def make_icon(size):
    ss = 4
    n = size * ss
    r_out = n * 0.22
    cx = cy = n / 2.0
    r_ring = n * 0.31
    r_face = n * 0.245
    hand_w = n * 0.020
    # 分针（向上）与时针（指向约 7 点方向）
    min_end = (cx, cy - 0.62 * r_face)
    hour_end = (cx - 0.86 * 0.42 * r_face, cy + 0.50 * 0.42 * r_face)

    pixels = []
    for j in range(size):
        row = []
        for i in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(ss):
                for sx in range(ss):
                    x = (i + (sx + 0.5) / ss) * ss
                    y = (j + (sy + 0.5) / ss) * ss
                    col = (0, 0, 0, 0)
                    if in_rounded(x, y, n, r_out):
                        col = PRIMARY + (255,)
                        d = math.hypot(x - cx, y - cy)
                        if d <= r_ring:
                            col = WHITE + (255,)
                            if d <= r_face:
                                if (dist_seg(x, y, cx, cy, min_end[0], min_end[1]) <= hand_w
                                        or dist_seg(x, y, cx, cy, hour_end[0], hour_end[1]) <= hand_w):
                                    col = PRIMARY + (255,)
                                elif d <= 0.09 * r_face:
                                    col = ACCENT + (255,)
                    for k in range(4):
                        acc[k] += col[k]
            cnt = ss * ss
            row.append(tuple(int(round(a / cnt)) for a in acc))
        pixels.append(row)
    return pixels


def write_png(path, size, pixels):
    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)

    raw = b"".join(
        b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row) for row in pixels
    )
    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9))
    data += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
    for size, d in OUT_DIRS.items():
        out_dir = os.path.join(base, d)
        os.makedirs(out_dir, exist_ok=True)
        px = make_icon(size)
        path = os.path.join(out_dir, "ic_launcher.png")
        write_png(path, size, px)
        print("written", path, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()

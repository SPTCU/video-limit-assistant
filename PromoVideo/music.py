#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
宣传片配乐：42 秒 / 120 BPM / 21 小节，全部由代码合成。
风格取向：有律动、有和声进行、有层次推进；不使用长混响铺底，避免"太空灵"。
时间对齐：S1 0-5.2 / S2 5-10.4 / S3 10.2-25.6 / S4 25.2-31 / S5 30.6-36.6 / S6 36.2-42
动作点：16.0 App 被弹出 / 21.3 点击后被请出 / 25.2 数据段 / 36.2 收尾
"""
import os
import subprocess
import wave
import numpy as np

SR = 44100
DUR = 42.0
N = int(SR * DUR)
BPM = 120.0
BEAT = 60.0 / BPM          # 0.5s
BAR = BEAT * 4             # 2.0s

rng = np.random.default_rng(20261114)


# ------------------------------------------------------------------ 基础工具
def midi(m):
    return 440.0 * (2.0 ** ((m - 69) / 12.0))


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def env_ad(n, attack, decay, curve=3.0):
    """attack-decay 包络"""
    a = max(1, int(attack * SR))
    out = np.ones(n)
    if a < n:
        out[:a] = np.linspace(0, 1, a) ** 0.6
        d = n - a
        out[a:] = np.exp(-np.linspace(0, 1, d) * curve)
    else:
        out = np.linspace(0, 1, n) ** 0.6
    return out


def fft_lp(x, fc, order=2):
    n = len(x)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1.0 / SR)
    H = 1.0 / np.sqrt(1.0 + (f / max(fc, 20.0)) ** (2 * order))
    return np.fft.irfft(X * H, n)


def fft_hp(x, fc, order=2):
    n = len(x)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1.0 / SR)
    H = 1.0 / np.sqrt(1.0 + (max(fc, 20.0) / np.maximum(f, 1.0)) ** (2 * order))
    return np.fft.irfft(X * H, n)


def fft_bp(x, lo, hi, order=2):
    return fft_hp(fft_lp(x, hi, order), lo, order)


def place(buf, sig, at, gain=1.0, pan=0.0):
    """把单声道片段放到立体声总线上，pan: -1 左 / +1 右"""
    i0 = int(at * SR)
    if i0 >= N:
        return
    n = min(len(sig), N - i0)
    if n <= 0:
        return
    s = sig[:n] * gain
    # 等功率声像
    th = (pan + 1.0) * 0.25 * np.pi
    buf[0, i0:i0 + n] += s * np.cos(th)
    buf[1, i0:i0 + n] += s * np.sin(th)


# ------------------------------------------------------------------ 音色
def kick(dur=0.34):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = 128.0 * np.exp(-t * 26.0) + 46.0
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9.0)
    click = fft_hp(rng.normal(0, 1, n), 2200) * np.exp(-t * 180.0) * 0.35
    return np.tanh((body + click) * 1.35) * 0.9


def clap(dur=0.26):
    n = int(SR * dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for k, off in enumerate([0.0, 0.011, 0.022]):
        i = int(off * SR)
        seg = fft_bp(rng.normal(0, 1, n - i), 1100, 3600) * np.exp(-np.arange(n - i) / SR * (34 + k * 6))
        out[i:] += seg * (1.0 - k * 0.22)
    return out / (np.max(np.abs(out)) + 1e-9) * 0.8


def hat(dur=0.06, open_=False):
    n = int(SR * (0.18 if open_ else dur))
    t = np.arange(n) / SR
    nz = fft_hp(rng.normal(0, 1, n), 7200 if open_ else 9000, 3)
    return nz * np.exp(-t * (14 if open_ else 62))


def bass(freq, dur):
    n = int(SR * dur)
    t = np.arange(n) / SR
    sig = (np.sin(2 * np.pi * freq * t) * 0.85
           + np.sin(2 * np.pi * freq * 2 * t) * 0.16
           + np.sin(2 * np.pi * freq * 3 * t) * 0.05)
    e = env_ad(n, 0.006, 0, 0)
    e[:] = 1.0
    a = int(0.008 * SR)
    e[:a] = np.linspace(0, 1, a)
    rel = int(0.03 * SR)
    e[-rel:] *= np.linspace(1, 0, rel)
    return sig * e * 0.8


def pluck(freq, dur=0.9, bright=1.0):
    """加性拨弦：基频 + 泛音，指数衰减，略带金属感但不刺耳"""
    n = int(SR * dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    partials = [(1, 1.0, 1.0), (2, 0.42, 0.62), (3, 0.22, 0.45), (4, 0.12, 0.34), (5, 0.06, 0.26)]
    for h, amp, tau in partials:
        f = freq * h
        if f > SR * 0.45:
            continue
        out += amp * np.sin(2 * np.pi * f * t + rng.uniform(0, 0.4)) * np.exp(-t / (tau * (0.55 + 0.45 * bright)))
    a = int(0.004 * SR)
    out[:a] *= np.linspace(0, 1, a)
    return out * 0.5


def piano(freq, dur=3.0):
    """钢琴近似：多点泛音 + 双段衰减"""
    n = int(SR * dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for h in range(1, 11):
        f = freq * h
        if f > SR * 0.42:
            break
        amp = (1.0 / h ** 1.15)
        dec = 1.6 / (1 + h * 0.42)
        out += amp * np.sin(2 * np.pi * f * t + rng.uniform(0, 0.3)) * np.exp(-t / dec)
    out += 0.05 * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 0.6)
    a = int(0.005 * SR)
    out[:a] *= np.linspace(0, 1, a)
    return out * 0.35


def pad(freqs, dur, cutoff=2400, detune=0.0035):
    n = int(SR * dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for f in freqs:
        for d in (-detune, 0.0, detune):
            ph = rng.uniform(0, 6.28)
            base = f * (1 + d)
            for h in range(1, 9):
                fh = base * h
                if fh > SR * 0.42:
                    break
                out += (1.0 / h ** 1.5) * np.sin(2 * np.pi * fh * t + ph * h)
    out = fft_lp(out, cutoff, 2)
    # 慢起，长收
    a = int(min(0.6, dur * 0.45) * SR)
    e = np.ones(n)
    e[:a] = np.linspace(0, 1, a) ** 1.4
    r = int(min(0.9, dur * 0.5) * SR)
    e[-r:] *= np.linspace(1, 0, r) ** 1.2
    return out * e / (len(freqs) * 3 * 4.2)


def noise_sweep(dur, f0=300, f1=9000, peak=0.5):
    """上升噪声（riser）"""
    n = int(SR * dur)
    t = np.arange(n) / SR
    nz = rng.normal(0, 1, n)
    # 用分段滤波近似扫频
    out = np.zeros(n)
    bands = 8
    for k in range(bands):
        lo = f0 * (f1 / f0) ** (k / bands)
        hi = f0 * (f1 / f0) ** ((k + 1) / bands)
        seg = fft_bp(nz, lo, hi)
        w = np.clip(1.0 - np.abs(t / dur - (k + 0.5) / bands) * bands * 1.35, 0, 1)
        out += seg * w
    return out * (np.linspace(0.15, 1.0, n) ** 2.2) * peak


def impact(dur=1.1):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = 96.0 * np.exp(-t * 7.0) + 38.0
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 3.4)
    air = fft_lp(rng.normal(0, 1, n), 1400) * np.exp(-t * 12) * 0.35
    return np.tanh((body + air) * 1.2) * 0.9


def tick(dur=0.05):
    n = int(SR * dur)
    t = np.arange(n) / SR
    return (fft_bp(rng.normal(0, 1, n), 2600, 6200) * np.exp(-t * 120)
            + np.sin(2 * np.pi * 2100 * t) * np.exp(-t * 90) * 0.35)


def blip(dur=0.22):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = 900.0 + 700.0 * np.exp(-t * 24)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 14) * 0.6


# ------------------------------------------------------------------ 和声
NOTE = {"C": [60, 64, 67, 71], "G": [59, 62, 67, 71],
        "Am": [57, 60, 64, 67], "F": [53, 57, 60, 64]}
ROOT = {"C": 36, "G": 43, "Am": 45, "F": 41}
# 21 小节（每小节 2 秒）
PROG = ["C", "G", "Am", "F", "C", "G", "Am", "F", "C", "G", "Am",
        "F", "C", "G", "Am", "F", "C", "G", "Am", "F", "C"]

# 各段能量：pad 音量 / 鼓组开关 / 琶音密度
# 段落: (起始, 结束, 名称)
SECTIONS = [(0.0, 5.2, "intro"), (5.2, 10.2, "clock"), (10.2, 25.2, "groove"),
            (25.2, 30.6, "peak"), (30.6, 36.2, "warm"), (36.2, 42.0, "outro")]


def section_at(t):
    for a, b, nm in SECTIONS:
        if a <= t < b:
            return nm
    return "outro"


def build():
    buf = np.zeros((2, N))

    # ---------------- 1. Pad：全程铺，但音量随段落变化
    for i, ch in enumerate(PROG):
        at = i * BAR
        if at >= DUR:
            break
        nm = section_at(at + 0.01)
        cutoff = {"intro": 1500, "clock": 1900, "groove": 2600,
                  "peak": 3600, "warm": 2300, "outro": 1700}[nm]
        gain = {"intro": 1.30, "clock": 1.12, "groove": 1.02,
                "peak": 1.08, "warm": 1.18, "outro": 1.32}[nm]
        freqs = [midi(m) for m in NOTE[ch]]
        dur = BAR + 0.55
        sig = pad(freqs, dur, cutoff=cutoff)
        place(buf, sig, at, gain, pan=0.0)
        # 高八度薄层，只在 peak / warm 段加，增加亮度
        if nm in ("peak", "warm"):
            place(buf, pad([f * 2 for f in freqs], dur, cutoff=cutoff * 1.4), at,
                  gain * 0.34, pan=0.0)

    # ---------------- 2. Bass：8 分音符，带侧链感的短促
    for i, ch in enumerate(PROG):
        at = i * BAR
        nm = section_at(at + 0.01)
        if nm in ("intro", "outro"):
            continue
        root = midi(ROOT[ch])
        pattern = [1, 0, 0, 1, 0, 0, 1, 0] if nm != "peak" else [1, 0, 1, 0, 1, 0, 1, 1]
        for k, on in enumerate(pattern):
            if not on:
                continue
            tt = at + k * BEAT / 2
            if tt >= DUR:
                break
            g = {"clock": 0.55, "groove": 0.95, "peak": 1.0, "warm": 0.72}[nm]
            place(buf, bass(root, BEAT / 2 * 0.92), tt, g * 0.56, pan=0.0)

    # ---------------- 3. 鼓：kick / clap / hat
    kick_times = []
    for i, ch in enumerate(PROG):
        at = i * BAR
        nm = section_at(at + 0.01)
        if nm == "intro":
            # 只在第 2 小节末尾放一下，提示节奏要来了
            if i == 1:
                place(buf, kick(), at + BEAT * 3.5, 0.5)
                kick_times.append(at + BEAT * 3.5)
            continue
        if nm == "outro":
            break
        # kick：clock 段只放 1、3 拍，groove 起放 4/4
        steps = [0, 2] if nm == "clock" else [0, 1, 2, 3]
        gk = {"clock": 0.45, "groove": 0.9, "peak": 1.0, "warm": 0.7}[nm]
        for b in steps:
            tt = at + b * BEAT
            place(buf, kick(), tt, gk * 0.84)
            kick_times.append(tt)
        # clap：2、4 拍
        if nm in ("groove", "peak", "warm"):
            for b in (1, 3):
                place(buf, clap(), at + b * BEAT, {"groove": 0.30, "peak": 0.36, "warm": 0.20}[nm])
        # hat：8 分 / 16 分
        div = 4 if nm in ("peak", "groove") else 2
        for k in range(div * 4):
            tt = at + k * BAR / (div * 4)
            acc = 1.0 if k % 2 == 0 else 0.55
            if nm == "peak" and k % 4 == 3:
                place(buf, hat(open_=True), tt, 0.042 * acc)
            else:
                place(buf, hat(), tt, (0.030 if nm != "warm" else 0.018) * acc)

    # ---------------- 4. 琶音：S2 进来，S3 加密，S4 最亮
    for i, ch in enumerate(PROG):
        at = i * BAR
        nm = section_at(at + 0.01)
        if nm in ("intro", "outro"):
            continue
        tones = [midi(m) + 12 for m in NOTE[ch]]
        seq = [0, 1, 2, 3, 2, 1, 3, 2]
        div = {"clock": 2, "groove": 4, "peak": 4, "warm": 2}[nm]   # 每拍细分
        gain = {"clock": 0.55, "groove": 0.9, "peak": 1.0, "warm": 0.7}[nm]
        for k in range(4 * div):
            tt = at + k * BEAT / div
            if tt >= DUR:
                break
            tone = tones[seq[k % len(seq)]]
            # peak 段偶发高八度
            if nm == "peak" and k % 8 == 6:
                tone += 12
            place(buf, pluck(midi_of(tone), 0.55, bright=gain), tt,
                  gain * 0.088, pan=-0.28 if k % 2 == 0 else 0.28)

    # ---------------- 5. 主旋律：只在 S4 数据段与 S6 收尾出现，避免单调也避免吵
    lead = [(25.2, 76, 0.9), (25.7, 79, 0.9), (26.2, 81, 1.4),
            (27.2, 79, 0.9), (27.7, 76, 0.9), (28.2, 74, 1.6),
            (29.2, 72, 0.9), (29.7, 74, 0.9), (30.2, 76, 1.8)]
    for tt, m, d in lead:
        place(buf, pluck(midi(m), d * 1.4, bright=1.0), tt, 0.30, pan=0.0)
        place(buf, pluck(midi(m - 12), d * 1.2, bright=0.7), tt, 0.10, pan=0.0)

    # ---------------- 6. S2 时钟滴答（对应时间码飞转）
    for k in range(int((10.2 - 5.2) / (BEAT / 2))):
        tt = 5.2 + k * BEAT / 2
        place(buf, tick(), tt, 0.10 if k % 2 == 0 else 0.055, pan=0.22)

    # ---------------- 7. 转场音效
    # 10.2 进入主段：短 riser + 落点
    place(buf, noise_sweep(1.5, 400, 7000, 0.5), 8.7, 0.19)
    place(buf, impact(), 10.2, 0.42)
    # 16.0 App 被弹出：轻 impact + 反向 whoosh
    place(buf, noise_sweep(0.7, 1200, 6000, 0.45), 15.3, 0.16)
    place(buf, impact(0.8), 16.0, 0.34)
    # 21.3 点击后被请出：blip + 小 whoosh
    place(buf, blip(), 21.15, 0.24, pan=0.15)
    place(buf, noise_sweep(0.45, 800, 5000, 0.4), 20.9, 0.11)
    # 25.2 数据段：大 riser + 强落点（全片最高点）
    place(buf, noise_sweep(1.9, 300, 9000, 0.55), 23.2, 0.28)
    place(buf, impact(1.4), 25.2, 0.55)
    # 36.2 收尾：缓和的重音，不要炸
    place(buf, impact(1.6), 36.2, 0.30)
    place(buf, noise_sweep(1.0, 600, 4000, 0.3), 35.4, 0.12)

    # ---------------- 8. 收尾和弦：钢琴，落在 37.2，尾音到 42
    for m, g in [(60, 0.34), (64, 0.28), (67, 0.26), (72, 0.20), (74, 0.14)]:
        place(buf, piano(midi(m), 4.6), 37.2, g, pan=0.0)
    # 最后一下低音，稳住结尾
    place(buf, bass(midi(36), 2.6), 40.0, 0.30)
    place(buf, piano(midi(48), 3.0), 40.0, 0.22)

    # ---------------- 9. 侧链（模拟 compression，让低频随 kick 起伏）
    side = np.ones(N)
    for kt in kick_times:
        i0 = int(kt * SR)
        ln = int(0.16 * SR)
        if i0 >= N:
            continue
        n = min(ln, N - i0)
        curve = 1.0 - 0.30 * np.exp(-np.arange(n) / SR * 26.0)
        side[i0:i0 + n] = np.minimum(side[i0:i0 + n], curve)
    buf *= side

    # ---------------- 10. 混响：短促、只给旋律层一点空间，不做长尾巴
    ir_n = int(1.1 * SR)
    irt = np.arange(ir_n) / SR
    ir = rng.normal(0, 1, ir_n) * np.exp(-irt / 0.26)
    ir = fft_hp(ir, 320, 2)
    ir[:int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))
    ir /= np.sqrt(np.sum(ir ** 2))

    # 只对中高频做混响（低频保持干净，避免糊）
    wet_src = np.stack([fft_hp(buf[0], 420, 2), fft_hp(buf[1], 420, 2)])
    L = 1 << int(np.ceil(np.log2(N + ir_n)))
    wet = np.zeros_like(buf)
    for c in range(2):
        X = np.fft.rfft(wet_src[c], L)
        H = np.fft.rfft(ir, L)
        wet[c] = np.fft.irfft(X * H, L)[:N]
    buf = buf + wet * 0.13

    # ---------------- 10.5 母带倾斜 EQ：压掉 5k 以上的嘶感，补一点 200Hz 厚度
    for c in range(2):
        X = np.fft.rfft(buf[c])
        f = np.fft.rfftfreq(N, 1.0 / SR)
        # 高频搁架：4.5k 起坡，到 12k 衰减约 5dB
        hs = 1.0 - 0.44 * np.clip((f - 4500.0) / 7500.0, 0, 1) ** 1.2
        # 低频轻微抬升，让底鼓和贝斯更实
        ls = 1.0 + 0.16 * np.exp(-((np.log(np.maximum(f, 1.0) / 180.0)) ** 2) / 0.55)
        buf[c] = np.fft.irfft(X * hs * ls, N)

    # ---------------- 11. 母带：柔限幅 + 归一化
    # 结尾淡出，避免被 42 秒硬切产生咔哒
    fs = int(41.4 * SR)
    buf[:, fs:] *= (np.linspace(1.0, 0.0, N - fs) ** 0.75)
    buf = np.tanh(buf * 1.15) / 1.15
    peak = np.max(np.abs(buf))
    buf = buf / peak * 0.891          # -1 dBFS
    return buf


def midi_of(m):
    return midi(m)


def write_wav(path, buf):
    data = (np.clip(buf.T, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


def main():
    print("合成中…")
    buf = build()
    root = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.join(root, "out")
    os.makedirs(out_dir, exist_ok=True)
    wav = os.path.join(out_dir, "music.wav")
    write_wav(wav, buf)
    rms = np.sqrt(np.mean(buf ** 2))
    print("已写出 %s  |  峰值 %.1f dBFS  |  RMS %.1f dBFS" % (
        wav, 20 * np.log10(np.max(np.abs(buf))), 20 * np.log10(rms)))
    return wav


if __name__ == "__main__":
    main()

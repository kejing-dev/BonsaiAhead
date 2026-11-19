"""Cuts the CC0 recordings (design assets.sounds) into app/src/main/assets/sounds/*.ogg and writes
apps/bonsai/build/sound-sources.md in the factory (ui_gates sound-source).

  ~/.factory-venv/bin/python tools/import_sounds.py

Each cut: ffmpeg decode -> mono 44.1 kHz -> pick the loudest window (onset detection for the three snips) -> short fades
-> peak normalised (events -1 dBFS; the bowl sounds 6 dB lower, B30) -> Ogg Vorbis.
"""
import os, subprocess, sys
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds")
FACTORY = os.path.expanduser("~/Desktop/Spatial Apps/spatial-app-factory")
SR = 44100

# id: (file, page, author, length s, attack s, peak dBFS, how many cuts)
CUTS = {
    "snip": ("freesound-bonsai/563654_12640470-hq.mp3", "https://freesound.org/people/fernandogisbert/sounds/563654/", "fernandogisbert", 0.35, 0.002, -1.0, 3),
    "drop": ("freesound-aura-sand/389611_6068748-hq.mp3", "https://freesound.org/people/_stubb/sounds/389611/", "_stubb", 0.30, 0.01, -1.0, 1),
    "water": ("freesound-aquarium/173930.mp3", "https://freesound.org/people/JohnsonBrandEditing/sounds/173930/", "JohnsonBrandEditing", 1.0, 0.03, -1.0, 1),
    "grow": ("freesound-zen-orb/417115_8224400.mp3", "https://freesound.org/people/dersinnsspace/sounds/417115/", "dersinnsspace", 2.0, 0.25, -1.0, 1),
    "done": ("freesound-zen-orb/204915_710671.mp3", "https://freesound.org/people/brodjaman/sounds/204915/", "brodjaman", 3.0, 0.005, -1.0, 1),
    "tick": ("freesound-zen-orb/218460_3344538.mp3", "https://freesound.org/people/thomasjaunism/sounds/218460/", "thomasjaunism", 0.06, 0.001, -1.0, 1),
    "season": ("freesound-bonsai/531239_7414526-hq.mp3", "https://freesound.org/people/DigPro120/sounds/531239/", "DigPro120", 0.40, 0.02, -1.0, 1),
}


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def onsets(x, n, length):
    """Start times of the [n] loudest separate events (envelope peaks at least [length] apart)."""
    hop = 256
    env = np.sqrt(np.convolve(x * x, np.ones(hop) / hop, "same"))[::hop]
    order = np.argsort(env)[::-1]
    picks = []
    for i in order:
        t = i * hop / SR
        if all(abs(t - p) > length * 1.5 for p in picks):
            picks.append(t)
        if len(picks) == n:
            break
    return sorted(max(0.0, p - 0.03) for p in picks)


def cut(x, start, length, attack, peak_db):
    a = int(start * SR); b = min(len(x), a + int(length * SR))
    y = x[a:b].copy()
    na = max(1, int(attack * SR)); nr = max(1, int(min(0.25, length * 0.4) * SR))
    y[:na] *= np.linspace(0, 1, na)
    y[-nr:] *= np.linspace(1, 0, nr) ** 2
    y *= 10 ** (peak_db / 20) / max(1e-9, np.abs(y).max())
    return y


def write_ogg(y, path):
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-", "-c:a", "libvorbis", "-q:a", "5", path], input=y.astype(np.float32).tobytes(), check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for sid, (f, page, author, length, attack, peak, n) in CUTS.items():
        x = decode(os.path.join(LIB, f))
        starts = onsets(x, n, length)
        for k, s in enumerate(starts):
            y = cut(x, s, length, attack, peak)
            name = sid + ("" if k == 0 else "_%d" % (k + 1)) + ".ogg"
            write_ogg(y, os.path.join(OUT, name))
            rms = 20 * np.log10(np.sqrt((y ** 2).mean()) + 1e-12)
            print("%-12s from %.2f s  %.2f s  peak %.1f dBFS  rms %.1f dBFS" % (name, s, len(y) / SR, peak, rms))
            rows.append("| %s | CC0 录音（freesound，%s）| %s | %s 起 %.2f s，截 %.2f s，峰值 %.0f dBFS |" % (name, author, page, f, s, length, peak))
    md = ["# 剪一年 · 声音来源（D51：全部真实录音 CC0，无合成）", "",
          "| 文件 | 来源 | 页面 | 截取 |", "|---|---|---|---|"] + rows + ["",
          "许可：本机 ~/Downloads/cc0-sounds/*/LICENSE.txt（页面写明 Creative Commons 0）。截取脚本 tools/import_sounds.py。"]
    dst = os.path.join(FACTORY, "apps", "bonsai", "build", "sound-sources.md")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "w").write("\n".join(md) + "\n")


if __name__ == "__main__":
    main()

"""Loudness evidence (B20 / ui_gates loudness): renders 30 s of play from the app's own ogg cuts at the default volume
(stop 2 = 100 %) with the app's gains (SoundPlayer v0.2.0: tick x 0.6, snip x 1, dry leaves x 0.9) and timing of a scrub
(stop tick, a prune day passed, a leaf-drop month passed). Rendered, not recorded (kj-mbp-m5 has no audio loopback): the
first sounds on the headset go on the device check list. Prints the voiced-window RMS (ui_gates method) and the peak.

  ~/.factory-venv/bin/python tools/loudness.py OUT.wav
"""
import os, subprocess, sys, wave
import numpy as np

SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
GAIN = {"snip": 1.0, "tick": 0.6, "season": 0.9}


def load(name):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", os.path.join(SND, name + ".ogg"), "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def main(out):
    buf = np.zeros(30 * SR, dtype=np.float32)
    rnd = np.random.default_rng(5)

    def put(name, t, rate=1.0):
        x = load(name) * GAIN[name.split("_")[0]]
        if rate != 1.0:
            idx = np.arange(0, len(x) - 1, rate)
            x = np.interp(idx, np.arange(len(x)), x).astype(np.float32)
        a = int(t * SR); n = min(len(x), len(buf) - a)
        if n <= 0: return
        b = a + n
        buf[a:b] += x[:n]

    t = 0.5
    k = 0
    while t < 28:  # scrubbing: a stop tick, a prune day passed (snip), a leaf-drop month passed (dry leaves)
        put("tick", t); t += 0.8
        put(["snip", "snip_2", "snip_3"][k % 3], t, 0.96 + rnd.random() * 0.08); k += 1; t += 0.9
        put("season", t); t += 1.0
        put("tick", t); t += 0.7
    peak = np.abs(buf).max()
    if peak > 10 ** (-1 / 20):
        buf *= 10 ** (-1 / 20) / peak  # soft headroom only when overlaps pass -1 dBFS
    pcm = (np.clip(buf, -1, 1) * 32767).astype("<i2")
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    d = pcm.astype(np.float64)
    win = 882
    e = np.array([(d[i:i + win] ** 2).mean() for i in range(0, len(d) - win + 1, win)])
    pk = np.abs(d).max()
    voiced = e[e >= (pk * 0.0316) ** 2]
    print("rendered %s: voiced RMS %.1f dBFS, peak %.1f dBFS" % (out, 20 * np.log10(np.sqrt(voiced.mean()) / 32768), 20 * np.log10(pk / 32768)))


if __name__ == "__main__":
    main(sys.argv[1])

"""Snip & Grow icon (design appIcon): terracotta #A5573A round, a slanting little tree (dark brown trunk, two moss-green
cloud pads) in a sand-white pot, a small pair of shears line art at the upper right. No text, flat (D2).

  ~/.factory-venv/bin/python tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes app/src/main/res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon) and
mipmap-xxxhdpi/ic_spatial_launcher.png. The adaptive launcher icon is the vector ic_launcher_fg.xml (same drawing).
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 4
TERRA = (0xA5, 0x57, 0x3A)
SAND = (0xED, 0xE6, 0xD8)
BARK = (0x4A, 0x33, 0x24)
MOSS = (0x5E, 0x7D, 0x4A)
MOSS_LIGHT = (0x86, 0xA8, 0x62)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def layer_bg():
    def d(dr, k):
        dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=TERRA + (255,))
    return big(d)


def layer_pot():
    def d(dr, k):
        P = lambda pts: [(x * k, y * k) for x, y in pts]
        dr.polygon(P([(30, 70), (78, 70), (73, 82), (35, 82)]), fill=SAND + (255,))
        dr.rectangle((28 * k, 67 * k, 80 * k, 71 * k), fill=SAND + (255,))
        # slanting trunk
        dr.line(P([(52, 68), (50, 58), (56, 48), (64, 40)]), fill=BARK + (255,), width=round(5 * k), joint="curve")
        dr.line(P([(51, 56), (40, 50)]), fill=BARK + (255,), width=round(3 * k))
    return big(d)


def layer_crown():
    def d(dr, k):
        dr.ellipse((48 * k, 26 * k, 82 * k, 42 * k), fill=MOSS + (255,))
        dr.ellipse((54 * k, 27 * k, 72 * k, 34 * k), fill=MOSS_LIGHT + (255,))
        dr.ellipse((26 * k, 44 * k, 48 * k, 55 * k), fill=MOSS + (255,))
        dr.ellipse((30 * k, 45 * k, 41 * k, 49 * k), fill=MOSS_LIGHT + (255,))
        # shears (line art) upper right
        w = round(2.2 * k)
        dr.ellipse((72 * k, 18 * k, 79 * k, 25 * k), outline=SAND + (255,), width=w)
        dr.ellipse((80 * k, 22 * k, 87 * k, 29 * k), outline=SAND + (255,), width=w)
        dr.line((78 * k, 22 * k, 88 * k, 12 * k), fill=SAND + (255,), width=w)
        dr.line((82 * k, 24 * k, 90 * k, 16 * k), fill=SAND + (255,), width=w)
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def flatten(layers):
    out = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def main():
    layers = [layer_bg(), layer_pot(), layer_crown()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = flatten(layers)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        tiles = layers + [flat]
        sheet = Image.new("RGB", (w * len(tiles), w), (60, 64, 70))
        for i, t in enumerate(tiles):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()

#!/usr/bin/env python3
"""Tripo bonsai -> app assets (design rules.model / assetFiles.pipeline).

For each species: load the textured Tripo Smart Mesh GLB, normalise it (base centre at the origin, height = design tree
height, front = +Z), split every triangle into wood (trunk, branches, pot, soil) or foliage by the colour of its texture
under it (hue 62-200 deg, saturation > 0.16, above 22 % of the height), and write

  app/src/main/assets/trees/<file>/wood.glb
  app/src/main/assets/trees/<file>/leaf_<sp|su|au|wi>.glb   (same geometry, the leaf pixels recoloured per season,
                                                              already darkened x REAL; no leaf_wi for bare winters)
  app/src/main/assets/trees/<file>/meta.json                (24 outward anchors on the foliage shell, dashed rings,
                                                              leaf colour, height, bounds)

Run with the factory venv (trimesh, numpy, pillow, scipy):
  ~/.factory-venv/bin/python tools/prepare_trees.py ~/Downloads/bonsai-tripo/smart/textured
"""
import json, os, sys
import numpy as np, trimesh
from PIL import Image

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Downloads/bonsai-tripo/smart/textured')
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'trees')
REAL = np.array([0.62, 0.72, 0.55])
TEX = 1024
# design content.species[].season.tint (summer reference = month 7); key months per season variant
SPECIES = {
    'pine':    {'h': 0.40, 'tint': {4: '#7fa36a', 7: '#668c63', 10: '#6a8a63', 1: '#5f7a63'}},
    'juniper': {'h': 0.40, 'tint': {4: '#7d9a52', 7: '#6a8f47', 10: '#6f8a49', 1: '#6b6a4c'}},
    'elm':     {'h': 0.40, 'tint': {4: '#86ad55', 7: '#5a843e', 11: '#9a8a44', 1: None}},
    'quemei':  {'h': 0.40, 'tint': {4: '#86b555', 7: '#629842', 10: '#6d9444', 1: '#7c6a3e'}},
    'ficus':   {'h': 0.34, 'tint': {4: '#4f7d40', 7: '#4a7a3e', 10: '#46743c', 1: '#3f6b3a'}},
}
VARIANT = {4: 'sp', 7: 'su', 10: 'au', 11: 'au', 1: 'wi'}


def hexrgb(h):
    return np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)])


def hsv(rgb):
    mx, mn = rgb.max(-1), rgb.min(-1); d = mx - mn + 1e-6
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    h = np.where(mx == r, (60 * (g - b) / d) % 360, np.where(mx == g, 60 * (b - r) / d + 120, 60 * (r - g) / d + 240))
    return h % 360, (mx - mn) / (mx + 1e-6), mx


def leafy(rgb):
    h, s, v = hsv(rgb)
    return (h > 62) & (h < 200) & (s > 0.16) & (v > 0.07)


def export(verts, faces, uv, img, path):
    mat = trimesh.visual.material.PBRMaterial(baseColorTexture=img, metallicFactor=0.0, roughnessFactor=0.85)
    m = trimesh.Trimesh(verts, faces, visual=trimesh.visual.TextureVisuals(uv=uv, material=mat), process=False)
    m.export(path)
    return len(faces)


def run(name, cfg):
    m = trimesh.load(os.path.join(SRC, f'{name}.glb'), force='mesh', process=False)
    img = m.visual.material.baseColorTexture.convert('RGB')
    if max(img.size) > TEX: img = img.resize((TEX, TEX), Image.LANCZOS)
    tex = np.asarray(img).astype(np.float32) / 255
    H, W = tex.shape[:2]
    # normalise: base centre at the origin, height cfg['h'] (Tripo +Y up, front +Z)
    v = m.vertices.copy(); lo, hi = v.min(0), v.max(0)
    s = cfg['h'] / (hi[1] - lo[1])
    v = (v - np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])) * s
    uv = m.visual.uv
    # unweld so each triangle owns its corners (faces of one class keep their UVs)
    tri = m.faces; V = v[tri].reshape(-1, 3); UV = uv[tri].reshape(-1, 2); F = np.arange(len(V)).reshape(-1, 3)
    fuv = UV[F].mean(1)
    px = np.clip((fuv[:, 0] * W).astype(int), 0, W - 1); py = np.clip(((1 - fuv[:, 1]) * H).astype(int), 0, H - 1)
    cy = V[F][:, :, 1].mean(1)
    fol = leafy(tex[py, px]) & (cy > 0.22 * cfg['h'])
    out = os.path.join(OUT, name); os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.endswith('.glb'): os.remove(os.path.join(out, f))
    nw = export(V, F[~fol], UV, img, os.path.join(out, 'wood.glb'))
    green = leafy(tex)
    ref = hexrgb(cfg['tint'][7]); variants = {}
    for month, hx in cfg['tint'].items():
        if hx is None: continue
        mult = np.clip(hexrgb(hx) / ref, 0, 2.4) * REAL
        t2 = tex.copy(); t2[green] = np.clip(t2[green] * mult, 0, 1)
        im2 = Image.fromarray((t2 * 255).astype(np.uint8))
        key = VARIANT[month]
        export(V, F[fol], UV, im2, os.path.join(out, f'leaf_{key}.glb')); variants[key] = month
    # average leaf colour (summer, darkened) for the new-shoot leaf clusters
    leaf = (tex[green].mean(0) * REAL).tolist()
    # anchors: outward foliage triangles on the shell, farthest-point sampled (design rules.model, 24)
    C = V[F[fol]].mean(1); N = np.cross(V[F[fol]][:, 1] - V[F[fol]][:, 0], V[F[fol]][:, 2] - V[F[fol]][:, 0])
    N /= np.linalg.norm(N, axis=1, keepdims=True) + 1e-9
    cyc = C[:, 1].mean(); outd = np.c_[C[:, 0], (C[:, 1] - cyc) * 0.8, C[:, 2]]; outd /= np.linalg.norm(outd, axis=1, keepdims=True) + 1e-9
    r = np.hypot(C[:, 0], C[:, 2]); maxR, maxY = r.max(), C[:, 1].max()
    shell = np.where((r > 0.55 * maxR) | (C[:, 1] > 0.86 * maxY))[0]
    sel = [shell[np.argmax(C[shell, 1])]]; dmin = np.linalg.norm(C[shell] - C[sel[0]], axis=1)
    while len(sel) < min(24, len(shell)):
        i = shell[np.argmax(dmin)]; sel.append(i); dmin = np.minimum(dmin, np.linalg.norm(C[shell] - C[i], axis=1))
    anchors = [{'p': np.round(C[i], 4).tolist(), 'd': np.round(outd[i], 3).tolist()} for i in sel]
    # dashed rings: foliage split into 5 height layers, ring at the 92nd-percentile radius + 1.5 cm
    rings = []
    y0a = 0.25 * cfg['h']
    for L in range(5):
        y0 = y0a + (maxY - y0a) * L / 5; y1 = y0a + (maxY - y0a) * (L + 1) / 5
        k = (C[:, 1] >= y0) & (C[:, 1] < y1)
        if k.sum() < 20: continue
        mx, mz = C[k, 0].mean(), C[k, 2].mean(); rr = np.sort(np.hypot(C[k, 0] - mx, C[k, 2] - mz))
        rings.append({'x': round(float(mx), 4), 'z': round(float(mz), 4), 'y': round(float((y0 + y1) / 2), 4), 'r': round(float(rr[int(len(rr) * 0.92)] + 0.015), 4)})
    meta = {'name': name, 'height': cfg['h'], 'bounds': np.round([V.min(0), V.max(0)], 4).tolist(), 'leaf': [round(x, 4) for x in leaf],
            'variants': variants, 'anchors': anchors, 'rings': rings, 'trisWood': int(nw), 'trisLeaf': int(fol.sum()), 'maxY': round(float(maxY), 4), 'maxR': round(float(maxR), 4)}
    json.dump(meta, open(os.path.join(out, 'meta.json'), 'w'))
    print(f'{name}: wood {nw} tris, leaf {int(fol.sum())} tris, variants {sorted(variants)}, anchors {len(anchors)}, rings {len(rings)}, bounds {meta["bounds"]}')


if __name__ == '__main__':
    for n, c in SPECIES.items(): run(n, c)

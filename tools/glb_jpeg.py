"""Shrink every image in a GLB to <=N px JPEG (keeps geometry). usage: in.glb out.glb N"""
import sys, struct, json, io
from PIL import Image
src, dst, N = sys.argv[1], sys.argv[2], int(sys.argv[3])
b = open(src, 'rb').read()
_, _, total = struct.unpack('<III', b[:12]); off = 12; chunks = []
while off < total:
    ln, ty = struct.unpack('<II', b[off:off+8]); chunks.append((ty, b[off+8:off+8+ln])); off += 8 + ln
js = json.loads(chunks[0][1]); bin_ = chunks[1][1]
views = js['bufferViews']; imgviews = {im['bufferView'] for im in js.get('images', [])}
newbin = bytearray(); remap = {}
for i, v in enumerate(views):
    data = bin_[v.get('byteOffset', 0): v.get('byteOffset', 0) + v['byteLength']]
    if i in imgviews:
        im = Image.open(io.BytesIO(data)).convert('RGB'); im.thumbnail((N, N), Image.LANCZOS)
        o = io.BytesIO(); im.save(o, 'JPEG', quality=85); data = o.getvalue()
    while len(newbin) % 4: newbin.append(0)
    v['byteOffset'] = len(newbin); v['byteLength'] = len(data); newbin += data
for im in js.get('images', []): im['mimeType'] = 'image/jpeg'
js['buffers'] = [{'byteLength': len(newbin)}]
jb = json.dumps(js, separators=(',', ':')).encode();
while len(jb) % 4: jb += b' '
while len(newbin) % 4: newbin.append(0)
out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(jb) + 8 + len(newbin)) + struct.pack('<II', len(jb), 0x4E4F534A) + jb + struct.pack('<II', len(newbin), 0x004E4942) + bytes(newbin)
open(dst, 'wb').write(out); print(src.split('/')[-1], len(b)//1024, '->', len(out)//1024, 'KB')

"""Turn the K screen frames recorded by KGifTour (-PkGifs) into one GIF per scene.
Usage: python3 kgifs.py <game dir> <out dir>
Frames are cropped to the K window (kgif-crop.txt), identical consecutive frames are merged, and
each frame lasts as long as the real time until the next grab (kgif-frames.txt)."""
import sys, os, collections
from PIL import Image, ImageChops

game, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
x, y, w, h = map(int, open(os.path.join(game, "kgif-crop.txt")).read().split())
rows = [l.split() for l in open(os.path.join(game, "kgif-frames.txt")) if l.strip()]
scenes = collections.OrderedDict()
for name, ns in rows:
    scenes.setdefault(name[len("kgif_"):name.rindex("_")], []).append((name, int(ns)))
# scenes that show tooltips: grow the crop to every pixel that changes outside the K window
# (a tooltip may hang past the window's edge), so no tooltip is ever cut off
TOOLTIP_SCENES = {"3_traits"}
for scene, fr in scenes.items():
    imgs, durs = [], []
    cx, cy, cw, ch = x, y, w, h
    paths = [os.path.join(game, "screenshots", n) for n, _ in fr]
    if scene in TOOLTIP_SCENES:
        base = None
        x0, y0, x1, y1 = x, y, x + w, y + h
        for pth in paths:
            if not os.path.exists(pth):
                continue
            full = Image.open(pth).convert("RGB")
            if base is None:
                base = full
                continue
            diff = ImageChops.difference(full, base).convert("L").point(lambda v: 255 if v > 24 else 0)
            bb = diff.getbbox()
            if bb:
                x0, y0, x1, y1 = min(x0, bb[0]), min(y0, bb[1]), max(x1, bb[2]), max(y1, bb[3])
        pad = 6
        x0, y0 = max(0, x0 - pad), max(0, y0 - pad)
        x1, y1 = min(base.width, x1 + pad), min(base.height, y1 + pad)
        cx, cy, cw, ch = x0, y0, x1 - x0, y1 - y0
        print(scene, "crop grown to", cx, cy, cw, ch)
    for i, (name, ns) in enumerate(fr):
        path = os.path.join(game, "screenshots", name)
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGB").crop((cx, cy, cx + cw, cy + ch))
        nxt = fr[i + 1][1] if i + 1 < len(fr) else ns + 100_000_000
        d = max(20, round((nxt - ns) / 1e7) * 10)
        if imgs and ImageChops.difference(im, imgs[-1]).getbbox() is None:
            durs[-1] += d
            continue
        imgs.append(im)
        durs.append(d)
    if not imgs:
        print(scene, "no frames")
        continue
    imgs[0].save(os.path.join(out, f"k_{scene}_first.png"))
    imgs[-1].save(os.path.join(out, f"k_{scene}_last.png"))
    # one palette for the whole GIF, taken from a strip of frames, so colours never flicker
    sample = Image.new("RGB", (cw, ch * min(6, len(imgs))))
    for k, im in enumerate(imgs[:: max(1, len(imgs) // 6)][:6]):
        sample.paste(im, (0, k * ch))
    pal = sample.quantize(255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q = [im.quantize(palette=pal, dither=Image.Dither.NONE) for im in imgs]
    q[0].save(os.path.join(out, f"k_{scene}.gif"), save_all=True, append_images=q[1:], duration=durs,
              loop=0, optimize=False, disposal=1)
    print(scene, len(imgs), "frames", sum(durs) / 1000, "s",
          os.path.getsize(os.path.join(out, f"k_{scene}.gif")) // 1024, "KB")

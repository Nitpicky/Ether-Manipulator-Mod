"""Turn the K screen frames recorded by KGifTour (-PkGifs) into one GIF per scene.
Usage: python3 kgifs.py <game dir> <out dir>
Frames are cropped to the K window (kgif-crop.txt), identical consecutive frames are merged, and
each frame lasts as long as the real time until the next grab (kgif-frames.txt)."""
import sys, os, collections
from PIL import Image, ImageChops
import numpy as np

game, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
x, y, w, h = map(int, open(os.path.join(game, "kgif-crop.txt")).read().split())
rows = [l.split() for l in open(os.path.join(game, "kgif-frames.txt")) if l.strip()]
scenes = collections.OrderedDict()
for name, ns in rows:
    scenes.setdefault(name[len("kgif_"):name.rindex("_")], []).append((name, int(ns)))
# scenes that show tooltips: a tooltip may hang past the K window's edge. The crop grows to fit every
# tooltip (found by its fill colour), and everything outside the window and the tooltip is transparent.
TOOLTIP_SCENES = {"3_traits"}
TIP_FILL = (18, 4, 21)


def tip_rect(full, inside):
    a = np.asarray(full).astype(int)
    t = (np.abs(a - TIP_FILL).max(-1) <= 3) & ~inside
    ys, xs = np.nonzero(t)
    return (xs.min() - 3, ys.min() - 3, xs.max() + 4, ys.max() + 4) if len(xs) > 100 else None


for scene, fr in scenes.items():
    imgs, durs, masks = [], [], []
    cx, cy, cw, ch = x, y, w, h
    tips = scene in TOOLTIP_SCENES
    rects = {}
    if tips:
        x0, y0, x1, y1 = x, y, x + w, y + h
        for n, _ in fr:
            pth = os.path.join(game, "screenshots", n)
            if not os.path.exists(pth):
                continue
            full = Image.open(pth).convert("RGB")
            inside = np.zeros((full.height, full.width), bool)
            inside[y:y + h, x:x + w] = True
            r = rects[n] = tip_rect(full, inside)
            if r:
                x0, y0, x1, y1 = min(x0, r[0]), min(y0, r[1]), max(x1, r[2]), max(y1, r[3])
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(full.width, x1), min(full.height, y1)
        cx, cy, cw, ch = x0, y0, x1 - x0, y1 - y0
        print(scene, "crop grown to", cx, cy, cw, ch)
    for i, (name, ns) in enumerate(fr):
        path = os.path.join(game, "screenshots", name)
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGB").crop((cx, cy, cx + cw, cy + ch))
        mask = None
        if tips:
            m = np.zeros((ch, cw), bool)
            m[y - cy:y - cy + h, x - cx:x - cx + w] = True
            r = rects.get(name)
            if r:
                m[max(0, r[1] - cy):r[3] - cy, max(0, r[0] - cx):r[2] - cx] = True
            mask = m
        nxt = fr[i + 1][1] if i + 1 < len(fr) else ns + 100_000_000
        d = max(20, round((nxt - ns) / 1e7) * 10)
        if imgs and ImageChops.difference(im, imgs[-1]).getbbox() is None:
            durs[-1] += d
            continue
        imgs.append(im)
        durs.append(d)
        masks.append(mask)
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
    extra = {}
    if tips:  # index 255 is transparent: outside the window and the tooltip
        for k, (p, m) in enumerate(zip(q, masks)):
            a = np.array(p)
            a[~m] = 255
            np_ = Image.fromarray(a, "P")
            np_.putpalette(pal.getpalette()[:255 * 3] + [0, 0, 0])
            q[k] = np_
        extra = {"transparency": 255}
    q[0].save(os.path.join(out, f"k_{scene}.gif"), save_all=True, append_images=q[1:], duration=durs,
              loop=0, optimize=False, disposal=2 if tips else 1, **extra)
    print(scene, len(imgs), "frames", sum(durs) / 1000, "s",
          os.path.getsize(os.path.join(out, f"k_{scene}.gif")) // 1024, "KB")

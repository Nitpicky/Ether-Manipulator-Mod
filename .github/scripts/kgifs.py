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
for scene, fr in scenes.items():
    imgs, durs = [], []
    for i, (name, ns) in enumerate(fr):
        path = os.path.join(game, "screenshots", name)
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGB").crop((x, y, x + w, y + h))
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
    sample = Image.new("RGB", (w, h * min(6, len(imgs))))
    for k, im in enumerate(imgs[:: max(1, len(imgs) // 6)][:6]):
        sample.paste(im, (0, k * h))
    pal = sample.quantize(255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q = [im.quantize(palette=pal, dither=Image.Dither.NONE) for im in imgs]
    q[0].save(os.path.join(out, f"k_{scene}.gif"), save_all=True, append_images=q[1:], duration=durs,
              loop=0, optimize=False, disposal=1)
    print(scene, len(imgs), "frames", sum(durs) / 1000, "s",
          os.path.getsize(os.path.join(out, f"k_{scene}.gif")) // 1024, "KB")

"""Download free sound packs (CC0) for the FX bench: never shipped in the mod as-is."""
import io, os, re, sys, urllib.request, zipfile, json

PACKS = os.environ.get("PACKS") or "sci-fi-sounds,impact-sounds,interface-sounds,ui-audio,digital-audio,rpg-audio"
OUT = os.environ.get("OUT") or "sfx-libs"
UA = {"User-Agent": "Mozilla/5.0 (sfx-fetch)"}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()

index = {}
for slug in [p.strip() for p in PACKS.split(",") if p.strip()]:
    try:
        page = get(f"https://kenney.nl/assets/{slug}").decode("utf8", "replace")
        links = re.findall(r'href="([^"]+\.zip)"', page)
        if not links:
            print("no zip for", slug); continue
        url = links[0] if links[0].startswith("http") else "https://kenney.nl" + links[0]
        z = zipfile.ZipFile(io.BytesIO(get(url)))
        files = []
        for n in z.namelist():
            if n.lower().endswith((".ogg", ".wav", ".mp3")):
                dst = os.path.join(OUT, slug, os.path.basename(n))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                open(dst, "wb").write(z.read(n)); files.append(os.path.basename(n))
            elif os.path.basename(n).lower().startswith("license"):
                os.makedirs(os.path.join(OUT, slug), exist_ok=True)
                open(os.path.join(OUT, slug, "LICENSE.txt"), "wb").write(z.read(n))
        index[slug] = {"source": url, "license": "CC0 (Kenney)", "files": sorted(files)}
        print(slug, len(files))
    except Exception as e:
        print("failed", slug, e, file=sys.stderr)
os.makedirs(OUT, exist_ok=True)
json.dump(index, open(os.path.join(OUT, "index.json"), "w"), indent=1)

"""Download free sound packs (CC0) for the FX bench: never shipped in the mod as-is."""
import io, os, re, sys, urllib.request, zipfile, json

PACKS = os.environ.get("PACKS") or "sci-fi-sounds,impact-sounds,interface-sounds,ui-audio,digital-audio,rpg-audio"
OUT = os.environ.get("OUT") or "sfx-libs"
UA = {"User-Agent": "Mozilla/5.0 (sfx-fetch)"}

import http.cookiejar
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def get(url, ref=None):
    h = dict(UA)
    if ref:
        h["Referer"] = ref
    return OPENER.open(urllib.request.Request(url, headers=h), timeout=90).read()

index = {}
for slug in [p.strip() for p in PACKS.split(",") if p.strip()]:
    try:
        page = get(f"https://kenney.nl/assets/{slug}").decode("utf8", "replace")
        links = re.findall(r'(https?://[^"\'\s<>]+?\.zip|/[^"\'\s<>]+?\.zip)', page.replace("\\/", "/"))
        if not links:
            # the page's own "Continue without donating" link: ask for a fresh signed link to the zip
            m = re.search(r"data-download-id='([0-9a-f]+)'", page)
            if m:
                try:
                    d = json.loads(get("https://kenney.nl/download-link/" + m.group(1), ref=f"https://kenney.nl/assets/{slug}"))
                    if d.get("url"):
                        links = [d["url"]]
                    else:
                        print("download-link answer", str(d)[:200])
                except Exception as e:
                    print("download-link failed", slug, e)
        if not links:
            # Kirby keeps a page's files in one media folder: the zip sits next to the preview image
            m = re.search(r"https://kenney\.nl/media/pages/assets/[^'\"]+?/", page)
            base = m.group(0) if m else None
            names = re.findall(r"kenney_[A-Za-z0-9_.-]+?\.zip", page)
            cands = [base + n for n in names] if base else []
            if base:
                cands += [base + f"kenney_{slug}.zip", base + f"kenney_{slug.replace('-', '')}.zip", base + f"kenney_{slug.replace('-', '_')}.zip"]
            for c in cands:
                try:
                    urllib.request.urlopen(urllib.request.Request(c, headers=UA, method="HEAD"), timeout=30); links = [c]; break
                except Exception:
                    pass
        if not links:
            print("no zip for", slug, len(page))
            continue
        url = links[0] if links[0].startswith("http") else "https://kenney.nl" + links[0]
        z = zipfile.ZipFile(io.BytesIO(get(url, ref=f"https://kenney.nl/assets/{slug}")))
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

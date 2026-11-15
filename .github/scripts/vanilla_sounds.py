"""Reference only (the user asked for it, 2026-11-15): downloads a set of vanilla 1.21.1 sounds and measures them for the
mod's sound mix pass. Nothing here ships in the mod. Writes vanilla-ref/report.json and vanilla-ref/ogg/<event>/<n>.ogg:
loudest 400ms (momentary max, LUFS), integrated loudness, peak, length, plus each event's sounds.json volume/pitch."""
import json, os, re, subprocess, urllib.request
PREFIX = ['entity.iron_golem.', 'entity.ravager.', 'entity.warden.', 'entity.zombie.ambient', 'entity.zombie.hurt', 'entity.zombie.step',
          'entity.hoglin.', 'entity.piglin_brute.', 'entity.evoker.', 'entity.vindicator.', 'entity.breeze.', 'entity.blaze.ambient',
          'entity.blaze.hurt', 'entity.camel.step', 'entity.sniffer.step', 'entity.player.hurt', 'entity.player.attack.', 'entity.generic.explode',
          'block.stone.step', 'block.piston.', 'entity.firework_rocket.launch', 'block.amethyst_block.hit', 'block.beacon.',
          'block.respawn_anchor.', 'block.trial_spawner.', 'entity.wind_charge.', 'block.anvil.land', 'entity.elder_guardian.ambient', 'entity.wither.ambient']
get = lambda u: urllib.request.urlopen(u, timeout=60).read()
man = json.loads(get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
ver = json.loads(get(next(v['url'] for v in man['versions'] if v['id'] == '1.21.1')))
idx = json.loads(get(ver['assetIndex']['url']))['objects']
obj = lambda name: get('https://resources.download.minecraft.net/%s/%s' % (idx[name]['hash'][:2], idx[name]['hash']))
sj = json.loads(obj('minecraft/sounds.json'))
os.makedirs('vanilla-ref/ogg', exist_ok=True)
def meas(f):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', f, '-af', 'apad=pad_dur=0.6,ebur128=peak=true', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    I = float(re.findall(r'I:\s+(-?[\d.]+) LUFS', r)[-1]); P = float(re.findall(r'Peak:\s+(-?[\d.]+) dBFS', r)[-1])
    M = [float(x) for x in re.findall(r' M:\s*(-?[\d.]+)', r)]
    d = float(re.findall(r'Duration: (\d+):(\d+):([\d.]+)', r)[0][2])
    return {'I': I, 'Mmax': max(M) if M else None, 'peak': P, 'dur': d}
rep = {}
for ev, d in sj.items():
    if not any(ev.startswith(p) for p in PREFIX): continue
    files = []
    for k, s in enumerate(d['sounds']):
        s = s if isinstance(s, dict) else {'name': s}
        if s.get('type') == 'event': files.append({'event_ref': s['name']}); continue
        name = 'minecraft/sounds/%s.ogg' % s['name'].replace('minecraft:', '')
        if name not in idx: continue
        os.makedirs('vanilla-ref/ogg/' + ev, exist_ok=True); p = 'vanilla-ref/ogg/%s/%d.ogg' % (ev, k)
        open(p, 'wb').write(obj(name))
        files.append({'file': p, 'name': s['name'], 'volume': s.get('volume', 1), 'pitch': s.get('pitch', 1), 'weight': s.get('weight', 1), **meas(p)})
    rep[ev] = {'subtitle': d.get('subtitle'), 'sounds': files}
    print(ev, len(files), flush=True)
json.dump(rep, open('vanilla-ref/report.json', 'w'), indent=1)

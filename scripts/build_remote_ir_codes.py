"""Builds the Remote tool's TV code set from Flipper-IRDB.

Flipper-IRDB (https://github.com/Lucaslhm/Flipper-IRDB) is dedicated to the
public domain under CC0 1.0. This picks, for each TV brand, the code sets most
of its remotes share, keeps the fifteen buttons the Remote tool draws, drops
service and hotel remotes (their codes open factory menus), and writes one
compact JSON file.

    python3 scripts/build_remote_ir_codes.py <Flipper-IRDB checkout> app/src/main/assets/remote/ir-tv.json
"""
import sys, os, re, json, collections

SRC = os.path.join(sys.argv[1], 'TVs')
OUT = sys.argv[2]

KEYS = {
    'POWER': ['Power', 'Power_toggle', 'Pwr'],
    'VOL_UP': ['Vol_up', 'Volume_up', 'Vol+'],
    'VOL_DOWN': ['Vol_dn', 'Vol_down', 'Volume_down', 'Vol-'],
    'MUTE': ['Mute'],
    'CH_UP': ['Ch_next', 'Ch_up', 'Channel_up'],
    'CH_DOWN': ['Ch_prev', 'Ch_dn', 'Ch_down', 'Channel_down'],
    'INPUT': ['Source', 'Input', 'Inputs', 'Av', 'Tv_av', 'Av_tv', 'Input_source'],
    'UP': ['Up'],
    'DOWN': ['Down'],
    'LEFT': ['Left'],
    'RIGHT': ['Right'],
    'OK': ['Ok', 'Enter', 'Select', 'Center'],
    'BACK': ['Return', 'Back', 'Exit'],
    'HOME': ['Home', 'Smart_hub', 'Smart', 'Apps'],
    'MENU': ['Menu', 'Settings', 'Tools'],
}
SUPPORTED = {'NEC', 'NECext', 'Samsung32', 'SIRC', 'SIRC15', 'SIRC20', 'RC5', 'RC5X', 'RC6', 'Kaseikyo', 'RCA'}
SKIP_FILE = re.compile(r'service|factory|hotel|enseo', re.I)
SKIP_BRAND = {'Hotels', 'Enseo'}
MAX_SETS = 3
EXTRA = {'Akai', 'Amazon', 'Blaupunkt', 'Daewoo', 'Funai', 'Haier', 'Kogan', 'Loewe', 'Onn', 'Pioneer', 'Roku', 'Seiki', 'Xiaomi'}
MAX_RAW = 400

def le(hexbytes):
    return int.from_bytes(bytes(int(b, 16) for b in hexbytes.split()), 'little')

def parse(path):
    signals = []
    cur = None
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.strip()
        if line.startswith('#') or not line:
            if cur: signals.append(cur); cur = None
            continue
        if ':' not in line: continue
        k, v = line.split(':', 1)
        k, v = k.strip(), v.strip()
        if k == 'name':
            if cur: signals.append(cur)
            cur = {'name': v}
        elif cur is not None:
            cur[k] = v
    if cur: signals.append(cur)
    return signals

def code_of(s):
    if s.get('type') == 'parsed':
        p = s.get('protocol')
        if p not in SUPPORTED or 'address' not in s or 'command' not in s: return None
        return '%s:%x:%x' % (p, le(s['address']), le(s['command']))
    if s.get('type') == 'raw':
        try:
            f = int(float(s.get('frequency', '38000')))
            d = [int(x) for x in s.get('data', '').split()]
        except ValueError:
            return None
        if not d or len(d) > MAX_RAW or f < 20000 or f > 60000: return None
        return 'raw:%d:%s' % (f, ','.join(map(str, d)))
    return None

def keys_of(signals):
    by = {}
    for s in signals:
        by.setdefault(s['name'].strip().lower(), s)
    out = {}
    for key, names in KEYS.items():
        for n in names:
            s = by.get(n.lower())
            if s:
                c = code_of(s)
                if c: out[key] = c; break
    return out

brands = collections.defaultdict(list)
for root, _dirs, names in sorted(os.walk(SRC)):
  for fn in sorted(names):
    if not fn.endswith('.ir'): continue
    brand = os.path.relpath(root, SRC).split(os.sep)[0]
    model = fn[:-3]
    if brand in SKIP_BRAND or SKIP_FILE.search(model): continue
    k = keys_of(parse(os.path.join(root, fn)))
    if 'POWER' not in k or len(k) < 5: continue
    brands[brand].append((model, k))

result = []
for brand in sorted(brands, key=lambda b: (-len(brands[b]), b)):
    files = brands[brand]
    if len(files) < 2 and brand not in EXTRA: continue
    groups = collections.OrderedDict()
    for model, k in files:
        sig = (k.get('POWER'), k.get('VOL_UP'))
        groups.setdefault(sig, []).append((model, k))
    ranked = sorted(groups.values(), key=lambda g: (-len(g), -max(len(k) for _, k in g)))
    sets = []
    for g in ranked[:MAX_SETS]:
        merged = {}
        for _, k in sorted(g, key=lambda mk: -len(mk[1])):
            for key, c in k.items():
                merged.setdefault(key, c)
        best = max(g, key=lambda mk: len(mk[1]))[0]
        sets.append({'name': best.replace('_', ' '), 'models': len(g), 'keys': {k: merged[k] for k in KEYS if k in merged}})
    result.append({'brand': brand, 'sets': sets})

doc = {
    'source': 'Flipper-IRDB, https://github.com/Lucaslhm/Flipper-IRDB, CC0 1.0 Universal (public domain). '
              'Picked and reshaped for WM Keyboard by scripts/build_remote_ir_codes.py.',
    'format': 'Each code is protocol:address:command in hex (the Flipper fields read little-endian), '
              'or raw:carrierHz:mark,space,... in microseconds.',
    'brands': result,
}
text = json.dumps(doc, separators=(',', ':'), ensure_ascii=False)
open(OUT, 'w').write(text)
print(len(result), 'brands', sum(len(b['sets']) for b in result), 'sets', len(text), 'bytes')

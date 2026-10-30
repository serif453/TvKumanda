#!/usr/bin/env python3
"""
TV IR kod veritabanını Flipper-IRDB'den (CC0, github.com/Lucaslhm/Flipper-IRDB) uygulamanın okuduğu
satır biçimine çevirir: app/src/main/assets/ir/tv.txt

Kullanım: python3 tools/ir_db_build.py <Flipper-IRDB/TVs klasörü> app/src/main/assets/ir/tv.txt

Biçim (sekmeyle ayrılmış, UTF-8):
  B <marka>
  S <kaç kumandadan birleşti> <tuş=sinyal;tuş=sinyal;...>
Sinyal: P,<protokol>,<adres hex>,<komut hex>   ya da   W,<taşıyıcı Hz>,<süre süre ...> (ham, mikrosaniye)
Aynı markadaki kumandalar güç + ses tuşlarına göre gruplanır; sık görülen kod seti önce gelir.
"""
import os, re, sys
from collections import OrderedDict

ALIASES = {
    'power': 'power', 'on_off': 'power', 'standby': 'power', 'power_toggle': 'power',
    'vol_up': 'vol_up', 'vol+': 'vol_up', 'volume_up': 'vol_up', 'plus': 'vol_up',
    'vol_dn': 'vol_dn', 'vol_down': 'vol_dn', 'vol-': 'vol_dn', 'vol_dwn': 'vol_dn', 'volume_down': 'vol_dn', 'minus': 'vol_dn',
    'mute': 'mute',
    'ch_next': 'ch_up', 'ch_up': 'ch_up', 'ch+': 'ch_up', 'pr_up': 'ch_up', 'page_up': 'ch_up',
    'ch_prev': 'ch_dn', 'ch_dn': 'ch_dn', 'ch_down': 'ch_dn', 'ch-': 'ch_dn', 'pr_down': 'ch_dn',
    'up': 'up', 'up_arrow': 'up', 'arrow_up': 'up',
    'down': 'down', 'dwn': 'down', 'arrow_down': 'down',
    'left': 'left', 'left_arrow': 'left', 'arrow_left': 'left',
    'right': 'right', 'right_arrow': 'right', 'arrow_right': 'right',
    'ok': 'ok', 'enter': 'ok', 'select': 'ok', 'ent': 'ok',
    'back': 'back', 'return': 'back',
    'exit': 'exit',
    'home': 'home', 'smart_hub': 'home',
    'menu': 'menu',
    'settings': 'settings', 'setup': 'settings',
    'info': 'info', 'display': 'info',
    'source': 'source', 'input': 'source', 'sources': 'source',
    'guide': 'guide', 'epg': 'guide', 'tv_guide': 'guide',
    'play': 'play', 'pause': 'pause', 'stop': 'stop',
    'play_pause': 'play_pause', 'play/pause': 'play_pause', 'play_pa': 'play_pause',
    'rewind': 'rewind', 'rew': 'rewind', 'rwd': 'rewind', 'fast_ba': 'rewind', 'rw': 'rewind',
    'fast_forward': 'ffwd', 'fast_fo': 'ffwd', 'ff': 'ffwd', 'fwd': 'ffwd', 'fastforward': 'ffwd',
    'fast_fwd': 'ffwd', 'forward': 'ffwd', 'fast forward': 'ffwd', 'ffw': 'ffwd', 'fw': 'ffwd',
    'red': 'red', 'green': 'green', 'yellow': 'yellow', 'blue': 'blue',
    'netflix': 'netflix', 'youtube': 'youtube',
    'subtitle': 'subtitle', 'subtitles': 'subtitle', 'cc': 'subtitle', 'sttl': 'subtitle',
}
for d in '0123456789':
    ALIASES[d] = d

PROTOCOLS = {'NEC', 'NECext', 'Samsung32', 'RC5', 'RC5X', 'RC6', 'SIRC', 'SIRC15', 'SIRC20', 'Kaseikyo', 'RCA', 'Pioneer'}

def le(hexbytes):
    b = [int(x, 16) for x in hexbytes.split()]
    return sum(v << (8 * i) for i, v in enumerate(b))

def parse_file(path):
    keys = OrderedDict()
    cur = {}
    def flush():
        name = cur.get('name', '').strip().lower()
        k = ALIASES.get(name)
        if not k or k in keys:
            return
        t = cur.get('type', '').strip()
        if t == 'parsed':
            p = cur.get('protocol', '').strip()
            if p not in PROTOCOLS or 'address' not in cur or 'command' not in cur:
                return
            keys[k] = 'P,%s,%X,%X' % (p, le(cur['address']), le(cur['command']))
        elif t == 'raw':
            try:
                f = int(float(cur.get('frequency', '38000')))
                data = [int(x) for x in cur.get('data', '').split()]
            except ValueError:
                return
            if len(data) < 4 or sum(data) > 1_900_000 or any(v <= 0 for v in data):
                return
            keys[k] = 'W,%d,%s' % (f, ' '.join(map(str, data)))
    with open(path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if line.startswith('#'):
                flush(); cur = {}
                continue
            if ':' in line:
                a, b = line.split(':', 1)
                a = a.strip()
                if a == 'name' and 'name' in cur:
                    flush(); cur = {}
                cur[a] = b.strip()
    flush()
    return keys

def main(src, dst):
    out = []
    total_sets = 0
    for brand in sorted(os.listdir(src), key=str.lower):
        bdir = os.path.join(src, brand)
        if not os.path.isdir(bdir):
            continue
        remotes = []
        for root, _, files in os.walk(bdir):
            for f in sorted(files):
                if f.endswith('.ir'):
                    k = parse_file(os.path.join(root, f))
                    # Kumanda sayılması için güç ve en az bir ses/yön tuşu olmalı
                    if 'power' in k and len(k) >= 4:
                        remotes.append(k)
        if not remotes:
            continue
        groups = OrderedDict()
        for k in remotes:
            sig = (k.get('power'), k.get('vol_up') or k.get('mute') or k.get('up'))
            if sig not in groups:
                groups[sig] = [0, OrderedDict()]
            g = groups[sig]
            g[0] += 1
            for name, s in k.items():
                g[1].setdefault(name, s)
        sets = sorted(groups.values(), key=lambda g: (-g[0], -len(g[1])))
        name = re.sub(r'\s+', ' ', brand.replace('_', ' ')).strip()
        out.append('B\t' + name)
        for count, keys in sets:
            out.append('S\t%d\t%s' % (count, ';'.join('%s=%s' % kv for kv in keys.items())))
            total_sets += 1
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, 'w', encoding='utf-8') as fh:
        fh.write('# TV IR kodlari: Flipper-IRDB (CC0), tools/ir_db_build.py ile uretildi\n')
        fh.write('\n'.join(out) + '\n')
    print('markalar:', sum(1 for l in out if l.startswith('B\t')), 'kod setleri:', total_sets, 'boyut:', os.path.getsize(dst))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])

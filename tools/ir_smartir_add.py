#!/usr/bin/env python3
"""
SmartIR (MIT, github.com/smartHomeHub/SmartIR ve litinoveweedle/SmartIR) TV kodlarını tv.txt'ye ekler.
ir_db_build.py'den SONRA çalıştırılır; Flipper setlerinin sırasını bozmaz (eski kayıtlar marka + set sırasıyla bakar):
SmartIR setleri her markanın kendi setlerinin sonuna, yeni markalar dosyanın sonuna eklenir.

Kullanım: python3 tools/ir_smartir_add.py app/src/main/assets/ir/tv.txt <SmartIR klasörü> [<SmartIR klasörü> ...]

- Broadlink (base64) ve ESPHome (ham) kodları süre dizisine çevrilir; NEC, Samsung32, RC5 ve RC6 olarak çözülebilenler
  kısa biçimde (P,...) yazılır, kalanlar ham (W,...) kalır. Taşıyıcı frekansı Broadlink'te yok, 38 kHz varsayılır
  (RC5/RC6 çözülürse 36 kHz).
- Yalnızca TV dosyaları alınır (aşağıdaki liste); soundbar, alıcı, projektör ve uydu kutusu dosyaları alınmaz.
- Markada aynı güç + ses kodu zaten varsa (Flipper'da ya da başka SmartIR dosyasında) set eklenmez. Başka markada
  aynı kod olsa da eklenir: kullanıcı markasının adıyla bulabilsin.
- Güç: "off" kodu "power" olur (tarama ve deneme TV açıkken yapılır); "on" farklıysa "Aç" diye "Diğer tuşlar"a gider.
  Kaynaklar (HDMI 1…) da "Diğer tuşlar"a gider.
"""
import base64, json, os, sys
from collections import OrderedDict

# Yalnızca TV olan dosyalar (dosya adı → marka). Liste elle, dosyaların supportedModels alanına bakılarak çıkarıldı.
TV_FILES = {
    '1000.json': 'Philips', '1001.json': 'Philips', '1002.json': 'Philips',
    '1020.json': 'Sony', '7020.json': 'Sony',
    '1040.json': 'LG', '1041.json': 'LG', '1042.json': 'LG', '1043.json': 'LG',
    '1060.json': 'Samsung', '1061.json': 'Samsung', '1062.json': 'Samsung', '1063.json': 'Samsung',
    '1064.json': 'Samsung', '1065.json': 'Samsung', '7060.json': 'Samsung',
    '1080.json': 'Insignia', '1100.json': 'Toshiba',
    '1180.json': 'TCL', '1181.json': 'TCL', '1182.json': 'TCL',
    '1240.json': 'TurboX', '1260.json': 'Thomson', '1300.json': 'OKI', '1340.json': 'Bauhn',
    '1380.json': 'Xiaomi', '1440.json': 'Andersson', '1620.json': 'Sharp', '1640.json': 'Noblex',
}
# Aynı numara iki depoda farklı cihaz olabiliyor: dosyanın üreticisi bu olmalı.
MAKER_CHECK = {'1440.json': 'andersson', '1600.json': 'kivi'}
TV_FILES['1600.json'] = 'Kivi'

KEYS = {'off': 'power', 'volumeUp': 'vol_up', 'volumeDown': 'vol_dn', 'mute': 'mute',
        'nextChannel': 'ch_up', 'previousChannel': 'ch_dn'}
for d in '0123456789':
    KEYS[d] = d


def broadlink(b64):
    b = base64.b64decode(b64)
    if len(b) < 6 or b[0] != 0x26:
        return None
    n = b[2] | (b[3] << 8)
    data, out, i = b[4:4 + n], [], 0
    while i < len(data):
        v = data[i]; i += 1
        if v == 0:
            if i + 1 >= len(data):
                break
            v = (data[i] << 8) | data[i + 1]; i += 2
        out.append(round(v * 8192 / 269))
    return out


def pronto(s):
    w = [int(x, 16) for x in s.split()]
    if len(w) < 6 or w[0] != 0:
        return None, 38000
    f = 1_000_000 / (w[1] * 0.241246)
    n1, n2 = w[2], w[3]
    seq = w[4:4 + 2 * (n1 + n2)]
    return [round(x * 1_000_000 / f) for x in seq], int(f)


def first_frame(d):
    """İlk çerçeve: 20 ms'den uzun ilk boşlukta kesilir; sondaki boşluk atılır (dizi işaretle biter)."""
    out = []
    for i, v in enumerate(d):
        if i % 2 == 1 and v > 20000:
            break
        out.append(v)
    if len(out) % 2 == 0:
        out = out[:-1]
    return out


def near(v, t, tol=0.3):
    return abs(v - t) <= t * tol


def decode_pdm(d, head_mark, head_space, one, zero_space, bits=32):
    if len(d) < 2 + 2 * bits or not near(d[0], head_mark) or not near(d[1], head_space):
        return None
    v = 0
    for i in range(bits):
        m, s = d[2 + 2 * i], d[3 + 2 * i]
        if not near(m, 560, 0.45):
            return None
        if near(s, one, 0.35):
            v |= 1 << i
        elif not near(s, zero_space, 0.45):
            return None
    return v


def manchester_levels(d, t):
    """Süre dizisini t uzunluğunda yarım-bit seviyelerine böler (1 = ışık)."""
    lv = []
    for i, v in enumerate(d):
        n = round(v / t)
        if n < 1 or abs(v - n * t) > t * 0.5:
            return None
        lv += [1 if i % 2 == 0 else 0] * n
    return lv


def decode_rc5(d):
    lv = manchester_levels(d, 889)
    if lv is None:
        return None
    lv = [0] + lv  # ilk bit boşluk + işaret ile başlar
    lv += [0] * (28 - len(lv))
    if len(lv) != 28:
        return None
    bits = []
    for i in range(14):
        a, b = lv[2 * i], lv[2 * i + 1]
        if (a, b) == (0, 1):
            bits.append(1)
        elif (a, b) == (1, 0):
            bits.append(0)
        else:
            return None
    if bits[0] != 1:
        return None
    addr = int(''.join(map(str, bits[3:8])), 2)
    cmd = int(''.join(map(str, bits[8:14])), 2)
    return ('RC5' if bits[1] == 1 else 'RC5X', addr, cmd)


def decode_rc6(d):
    if len(d) < 4 or not near(d[0], 2666) or not near(d[1], 889):
        return None
    lv = manchester_levels(d[2:], 444)
    if lv is None:
        return None
    lv += [0] * (2 * 21 + 2 - len(lv))
    bits, i = [], 0
    for n in range(21):
        w = 2 if n == 4 else 1  # değişim biti çift uzunlukta
        if i + 2 * w > len(lv):
            return None
        a, b = lv[i], lv[i + w]
        i += 2 * w
        if (a, b) == (1, 0):
            bits.append(1)
        elif (a, b) == (0, 1):
            bits.append(0)
        else:
            return None
    if bits[0] != 1 or bits[1:4] != [0, 0, 0]:
        return None
    addr = int(''.join(map(str, bits[5:13])), 2)
    cmd = int(''.join(map(str, bits[13:21])), 2)
    return ('RC6', addr, cmd)


def to_code(d, freq=38000):
    if not d:
        return None
    d = first_frame([abs(x) for x in d if x != 0])
    if len(d) < 3 or sum(d) > 1_900_000:
        return None
    v = decode_pdm(d, 9000, 4500, 1690, 560)
    if v is not None:
        a, na, c, nc = v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF
        if na == a ^ 0xFF and nc == c ^ 0xFF:
            return 'P,NEC,%X,%X' % (a, c)
        return 'P,NECext,%X,%X' % (v & 0xFFFF, v >> 16)
    v = decode_pdm(d, 4500, 4500, 1650, 560)
    if v is not None:
        a, a2, c, nc = v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF
        if a == a2 and nc == c ^ 0xFF:
            return 'P,Samsung32,%X,%X' % (a, c)
    for dec in (decode_rc6, decode_rc5):
        r = dec(d)
        if r:
            return 'P,%s,%X,%X' % r
    return 'W,%d,%s' % (freq, ' '.join(map(str, d)))


def convert(enc, raw):
    try:
        return _convert(enc, raw)
    except (ValueError, TypeError, IndexError):
        return None


def _convert(enc, raw):
    if enc in ('Base64', 'Pronto') and isinstance(raw, list):
        raw = raw[0] if raw else ''  # birden çok kod sırayla gönderilecekse ilki
    if enc == 'Base64':
        return to_code(broadlink(raw))
    if enc == 'Raw':
        return to_code(raw if isinstance(raw, list) else [int(x) for x in str(raw).replace(',', ' ').split()])
    if enc == 'Pronto':
        d, f = pronto(raw)
        return to_code(d, f)
    return None


def load_file(path, enc):
    j = json.load(open(path, encoding='utf-8'))
    enc = j.get('commandsEncoding', enc)
    c = j.get('commands', {})
    keys = OrderedDict()
    for src, dst in KEYS.items():
        if isinstance(c.get(src), (str, list)):
            code = convert(enc, c[src])
            if code:
                keys[dst] = code
    if 'on' in c and 'power' in keys:
        on = convert(enc, c['on'])
        if on and on != keys['power']:
            keys['x:Aç'] = on
    if 'power' in c and 'power' not in keys:
        p = convert(enc, c['power'])
        if p:
            keys['power'] = p
    if isinstance(c.get('sources'), dict):
        for name, raw in list(c['sources'].items())[:12]:
            code = convert(enc, raw) if isinstance(raw, (str, list)) else None
            if code:
                keys['x:' + str(name).replace('\t', ' ').replace(';', ' ').replace('=', ' ')[:20]] = code
    return j.get('manufacturer', ''), keys


def sig(keys):
    return (keys.get('power'), keys.get('vol_up') or keys.get('mute') or keys.get('up'))


def main(db_path, dirs):
    lines = open(db_path, encoding='utf-8').read().splitlines()
    header = [l for l in lines if l.startswith('#')]
    brands, order = OrderedDict(), []
    cur = None
    for l in lines:
        if l.startswith('B\t'):
            cur = l.split('\t', 1)[1]
            brands.setdefault(cur, [])
            order.append(cur)
        elif l.startswith('S\t') and cur:
            p = l.split('\t')
            keys = OrderedDict(kv.split('=', 1) for kv in p[2].split(';') if '=' in kv)
            brands[cur].append((p[1], keys))
    seen = {(b.lower(), sig(k)) for b, sets in brands.items() for _, k in sets}
    added, raw_count = 0, 0
    for d in dirs:
        mp = os.path.join(d, 'codes', 'media_player')
        for f in sorted(os.listdir(mp)):
            if f not in TV_FILES:
                continue
            try:
                maker, keys = load_file(os.path.join(mp, f), 'Base64')
            except (ValueError, KeyError, OSError):
                continue
            if f in MAKER_CHECK and MAKER_CHECK[f] not in maker.lower():
                continue
            if 'power' not in keys or len(keys) < 2:
                continue
            brand = next((b for b in brands if b.lower() == TV_FILES[f].lower()), TV_FILES[f])
            s = (brand.lower(), sig(keys))
            if s in seen:
                continue
            seen.add(s)
            if brand not in brands:
                brands[brand] = []
                order.append(brand)
            brands[brand].append(('1', keys))
            added += 1
            raw_count += sum(1 for v in keys.values() if v.startswith('W,'))
    out = ['# TV IR kodlari: Flipper-IRDB (CC0), tools/ir_db_build.py ile uretildi',
           '# + SmartIR (MIT, (c) 2019 Vassilis Panos, 2024 Li Tin O\'ve Weedle), tools/ir_smartir_add.py ile eklendi']
    for b in order:
        out.append('B\t' + b)
        for n, keys in brands[b]:
            out.append('S\t%s\t%s' % (n, ';'.join('%s=%s' % kv for kv in keys.items())))
    with open(db_path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(out) + '\n')
    print('eklenen set:', added, 'ham kod:', raw_count, 'marka:', len(order))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])

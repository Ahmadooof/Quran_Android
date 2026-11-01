# Builds the app's mushaf data and page fonts from QCF v4 (King Fahd Complex, Madinah Mushaf 1441).
# Usage: python tools/qcf4_build.py <quran-qcf4 package dir> <QCF4 font dir> <v2 mushaf.json>
#   package: npm quran-qcf4 (pages/, font-map.json); fonts: QCF4_Hafs_NN_W.ttf + QCF4_QBSML.ttf (nuqayah/qpc-fonts mushaf-v4-hafs)
#   v2 mushaf.json, checked against: git show 79039dd:app/src/main/assets/data/mushaf.json
import json, os, shutil, sys
from fontTools.ttLib import TTFont

PKG, FONTS, OLD = sys.argv[1], sys.argv[2], sys.argv[3]
ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main')
OUT_FONTS = os.path.join(ROOT, 'fonts-qcf4')
DATA = os.path.join(ROOT, 'assets', 'data', 'mushaf.json')

# One recited word printed as two glyphs in v4; the timings count it once
JOINED = {(37, 130): 3}
# The sajdah sign comes as a word of its own; it belongs to the word before it
SAJDAH = '#1969'

old = json.load(open(OLD, encoding='utf-8'))
font_map = json.load(open(os.path.join(PKG, 'font-map.json')))
pages = {p: json.load(open(os.path.join(PKG, 'pages', f'{p:03d}.json'), encoding='utf-8')) for p in range(1, 605)}

os.makedirs(OUT_FONTS, exist_ok=True)
fonts = {}


def font(name):
    if name not in fonts:
        f = TTFont(os.path.join(FONTS, f'{name}_W.ttf' if name.startswith('QCF4_Hafs') else f'{name}.ttf'))
        fonts[name] = f
    return fonts[name]


def number(name):
    return int(name.split('_')[-1]) if name.startswith('QCF4_Hafs') else 0


def table(name, out):
    f = font(name)
    cm, mt = f.getBestCmap(), f['hmtx'].metrics
    rows = [str(f['head'].unitsPerEm)]
    for cp in sorted(cm):
        rows.append(f'{cp} {f.getGlyphID(cm[cp])} {mt[cm[cp]][0]}')
    open(os.path.join(OUT_FONTS, out), 'w', newline='\n').write('\n'.join(rows) + '\n')


# --- tokens in reading order, per font, so marks skipped by the page data can be put back ---
tokens = []  # [page, line, type, codes, verse_key]
for p in range(1, 605):
    name = font_map[str(p)]
    for li, line in enumerate(pages[p]['lines']):
        for w in line['words']:
            if w['font'] == name and w['type'] in ('word', 'end', 'quarter'):
                tokens.append([p, li, w['type'] if w.get('text') != SAJDAH else 'sajdah', [w['code']], w.get('verse_key')])

put_back = 0
for a, b in zip(tokens, tokens[1:]):
    if font_map[str(a[0])] != font_map[str(b[0])]:
        continue
    cm = font(font_map[str(a[0])]).getBestCmap()
    gap = [c for c in range(a[3][0] + 1, b[3][0]) if c in cm]
    if not gap:
        continue
    # A pause mark sits over the word before it; never on an ayah number
    assert a[2] in ('word', 'sajdah'), (a, b, gap)
    a[3] += gap
    put_back += len(gap)

# --- merge into printed words: hizb mark opens the next word, sajdah closes the one before ---
lines = {}  # (page, line) -> list of word strings
ends = {}   # page -> ayah-end glyphs
words_of = {}  # verse -> count
pending = None
last = None
for p, li, kind, codes, key in tokens:
    text = ''.join(chr(c) for c in codes)
    row = lines.setdefault((p, li), [])
    if kind == 'quarter':
        pending = text
        continue
    if kind == 'sajdah':
        lp, ll, i = last
        lines[(lp, ll)][i] += ' ' + text
        continue
    if kind == 'end':
        ends[p] = ends.get(p, '') + text
        row.append(text)
        last = None
        continue
    s, v = map(int, key.split(':'))
    n = words_of.get((s, v), 0) + 1
    if JOINED.get((s, v)) == n - 1:
        lp, ll, i = last
        lines[(lp, ll)][i] += ' ' + text
        continue
    words_of[(s, v)] = n
    if pending:
        text = pending + ' ' + text
        pending = None
    row.append(text)
    last = (p, li, len(row) - 1)

# --- the same words per ayah as the old edition, but for the pairs it printed as one ---
counts = {}
cur = (1, 1)
for p in range(1, 605):
    marks = old['marks'][str(p)]
    for line in old['pages'][str(p)]:
        if line['t'] == 'surah':
            cur = (line['s'], 1)
        if line['t'] != 'ayah':
            continue
        for w in line['v2'].split('|'):
            if not w:
                continue
            if w in marks:
                cur = (cur[0], cur[1] + 1)
            else:
                counts[cur] = counts.get(cur, 0) + 1
pairs = {(2, 181), (8, 6), (13, 37)}
for k, n in counts.items():
    assert words_of.get(k, 0) == n + (1 if k in pairs else 0), (k, n, words_of.get(k))
assert len(words_of) == 6236

# --- pages: the same line kinds as before, words from v4 ---
out_pages = {}
basmalah = None
for p in range(1, 605):
    rows = []
    for li, line in enumerate(pages[p]['lines']):
        types = [w['type'] for w in line['words']]
        if 'surah_header' in types:
            rows.append({'t': 'surah', 's': line['words'][0]['sura']})
        elif 'bismillah' in types:
            rows.append({'t': 'basmalah'})
            b = next(w for w in line['words'] if w['type'] == 'bismillah')
            basmalah = basmalah or (p, b['code'], b['font'])
        else:
            rows.append({'t': 'ayah', 'v4': '|'.join(lines.get((p, li), []))})
    out_pages[str(p)] = rows

# --- every page opens and closes on the same ayah as before; a surah's title may move to the next page's top ---
def spans(rows_of, field, marks_of):
    out, cur = {}, (1, 1)
    for p in range(1, 605):
        marks, seen = marks_of(p), []
        for r in rows_of(p):
            if r['t'] == 'surah':
                cur = (r['s'], 1)
            if r['t'] != 'ayah':
                continue
            for w in r[field].split('|'):
                if not w:
                    continue
                if w in marks:
                    seen.append(cur)
                    cur = (cur[0], cur[1] + 1)
        out[p] = (seen[0], seen[-1]) if seen else None
    return out
before = spans(lambda p: old['pages'][str(p)], 'v2', lambda p: old['marks'][str(p)])
after = spans(lambda p: out_pages[str(p)], 'v4', lambda p: ends.get(p, ''))
assert before == after, [p for p in before if before[p] != after[p]][:5]

# --- how wide a full line is, in ems, and the share below which a line is centred ---
widths = []
for p in range(3, 605):
    f = font(font_map[str(p)])
    up, cm, mt = f['head'].unitsPerEm, f.getBestCmap(), f['hmtx'].metrics
    for r in out_pages[str(p)]:
        if r['t'] == 'ayah':
            widths.append(sum(mt[cm[ord(c)]][0] for c in r['v4'] if c not in '| ') / up)
widths.sort()
body = round(widths[int(len(widths) * 0.99)] * 1.0095, 2)

bp, bcode, bfont = basmalah
assert number(bfont) == number(font_map[str(bp)]) or bp == 1
data = {
    'fit': {'body': {'v4': body}, 'centreBelow': {'v4': old['fit']['centreBelow']['v2']}},
    'rubPages': old['rubPages'],
    'juzPages': old['juzPages'],
    'fonts': [number(font_map[str(p)]) for p in range(1, 605)],
    'basmalah': {'font': number(bfont), 'v4': chr(bcode)},
    'pages': out_pages,
    'marks': ends,
}
json.dump(data, open(DATA, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))

for n in range(1, 48):
    name = f'QCF4_Hafs_{n:02d}'
    shutil.copy(os.path.join(FONTS, f'{name}_W.ttf'), os.path.join(OUT_FONTS, f'q{n}.ttf'))
    table(name, f'gq{n}.txt')
shutil.copy(os.path.join(FONTS, 'QCF4_QBSML.ttf'), os.path.join(OUT_FONTS, 'qh.ttf'))
table('QCF4_QBSML', 'gqh.txt')
print('marks put back', put_back, 'body em', body, 'basmalah', hex(bcode), bfont)

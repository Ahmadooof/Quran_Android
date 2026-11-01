# Builds the Tajweed edition's data and each page font's glyph table. The fonts are drawn for the 1441 lines, numbered as
# the 1421 edition's glyphs: their words are taken in the 1421 data's order and set in the 1441 edition's rows.
# The fonts themselves are downloaded by the app (TajweedFonts); only their measures ship.
# Usage: python tools/tajweed_build.py <v2 mushaf.json> <Tajweed font dir: p1.ttf .. p604.ttf>
#   v2 mushaf.json: git show 79039dd:app/src/main/assets/data/mushaf.json
#   fonts: https://static-cdn.tarteel.ai/qul/fonts/quran_fonts/v4-tajweed/ttf/p{page}.ttf
import json, os, sys
from fontTools.ttLib import TTFont

OLD, FONTS = sys.argv[1], sys.argv[2]
ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main')
TABLES = os.path.join(ROOT, 'fonts-tajweed')
DATA = os.path.join(ROOT, 'assets', 'data', 'mushaf-tajweed.json')
ROWS = os.path.join(ROOT, 'assets', 'data', 'mushaf.json')

# Printed as one slot, recited as two words: split here so each word is counted as the recitation counts it
PAIRS = {(2, 181): 2, (8, 6): 3, (13, 37): 7}

old = json.load(open(OLD, encoding='utf-8'))
layout = json.load(open(ROWS, encoding='utf-8'))
basmalah = old['basmalah']['v2']
os.makedirs(TABLES, exist_ok=True)

pages, widths = {}, []
s = a = w = 0
for p in range(1, 605):
    f = TTFont(os.path.join(FONTS, f'p{p}.ttf'))
    cm, mt, up = f.getBestCmap(), f['hmtx'].metrics, f['head'].unitsPerEm
    rows = [str(up)] + [f'{c} {f.getGlyphID(cm[c])} {mt[cm[c]][0]}' for c in sorted(cm)]
    open(os.path.join(TABLES, f'gt{p}.txt'), 'w', newline='\n').write('\n'.join(rows) + '\n')

    marks = old['marks'][str(p)]
    # The page's words in reading order, the recited pairs split as the recitation counts them
    words = []
    for line in old['pages'][str(p)]:
        if line['t'] == 'surah':
            s, a, w = line['s'], 1, 0
        if line['t'] != 'ayah':
            continue
        for word in (x for x in line['v2'].split('|') if x):
            if word in marks:
                a, w = a + 1, 0
                words.append(word)
                continue
            if PAIRS.get((s, a)) == w:
                gap = word.find(' ')
                cut = gap + 1 if gap >= 0 else 1
                words += [word[:cut].strip(), word[cut:].strip()]
                w += 2
            else:
                words.append(word)
                w += 1
    # Set in the 1441 rows: its titles and Basmalahs where they stand, each row as many words as it holds there
    out, at = [], 0
    for line in layout['pages'][str(p)]:
        if line['t'] == 'surah':
            out.append({'t': 'surah', 's': line['s']})
        elif line['t'] == 'basmalah':
            # Drawn from the first page's font, where its four words are
            out.append({'t': 'basmalah', 'v2': basmalah})
        else:
            n = sum(1 for x in line['v4'].split('|') if x)
            row = words[at:at + n]
            at += n
            out.append({'t': 'ayah', 'v2': '|'.join(row)})
            if p > 2:
                widths.append(sum(mt[cm[ord(c)]][0] for c in ''.join(row) if c != ' ') / up)
    assert at == len(words), (p, at, len(words))
    pages[str(p)] = out

widths.sort()
# Wide enough for all but the widest few lines, which the page shrinks on their own, as the plain edition
body = round(widths[int(len(widths) * 0.99)] * 1.0095, 2)
data = {
    'fit': {'body': {'v2': body}, 'centreBelow': {'v2': old['fit']['centreBelow']['v2']}},
    'fonts': list(range(1, 605)),
    'basmalah': {'font': 1},
    'pages': pages,
    'marks': old['marks'],
}
json.dump(data, open(DATA, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('body em', body)

# Builds assets/data/end-pages.json from tools/end-pages.txt: the prose as typed, each Quranic example as the
# mushaf's own glyphs, found in its ayah and checked letter by letter against the QCF4 page data's word texts.
# Usage: python tools/end_pages.py <quran-qcf4 package dir>
import json, os, re, sys

PKG = sys.argv[1]
HERE = os.path.dirname(__file__)
SRC = os.path.join(HERE, 'end-pages.txt')
DATA = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'data')
OUT = os.path.join(DATA, 'end-pages.json')

mushaf = json.load(open(os.path.join(DATA, 'mushaf.json'), encoding='utf-8'))


def skeleton(text):
    """The letters without vowels, marks, alifs or hamza seats, so the mushaf's and the plain spelling meet."""
    out = []
    for ch in text:
        if 'ء' <= ch <= 'ي' or ch in 'ٱ':
            if ch in 'اأإآٱءؤئـ':
                continue
            out.append({'ة': 'ه', 'ى': 'ي'}.get(ch, ch))
    return ''.join(out)


# --- every printed token in reading order: (page, glyphs, kind, surah, ayah, its words' text) ---
info = {}
for p in range(1, 605):
    page = json.load(open(os.path.join(PKG, 'pages', f'{p:03d}.json'), encoding='utf-8'))
    for line in page['lines']:
        for w in line['words']:
            if w['type'] in ('word', 'end'):
                info[(p, w['code'])] = w

tokens = []
for p in range(1, 605):
    marks = mushaf['marks'][str(p)]
    for line in mushaf['pages'][str(p)]:
        if line['t'] != 'ayah':
            continue
        for word in line['v4'].split('|'):
            if not word:
                continue
            codes = [ord(c) for c in word if c != ' ']
            # A hizb mark leads its word; it is not part of the example
            lead = next(c for c in codes if (p, c) in info)
            glyphs = word[word.index(chr(lead)):]
            w = info[(p, lead)]
            s, a = map(int, w['verse_key'].split(':'))
            tokens.append({'page': p, 'glyphs': glyphs, 'end': word in marks, 's': s, 'a': a,
                           'text': '' if word in marks else w['text']})


def find(ref, words):
    """The tokens of [words] within the ayahs of [ref]: (surah, first ayah, last ayah, keep the closing ayah mark)."""
    keep_end = ref.endswith('!')
    ref = ref.rstrip('!')
    s, ayahs = ref.split(':')
    s = int(s)
    a0, a1 = (int(x) for x in (ayahs.split('-') + [ayahs])[:2])
    # Words in braces are the example; the rest only place it, as when its word comes more than once
    keep = [i for i, w in enumerate(words.split()) if w.startswith('{')] or None
    want = [skeleton(w.strip('{}')) for w in words.split()]
    span = [i for i, t in enumerate(tokens) if t['s'] == s and a0 <= t['a'] <= a1]
    plain = [i for i in span if not tokens[i]['end']]
    for k in range(len(plain) - len(want) + 1):
        if all(skeleton(tokens[plain[k + j]]['text']) == want[j] for j in range(len(want))):
            chosen = keep or range(len(want))
            first, last = plain[k + chosen[0]], plain[k + chosen[-1]]
            if keep_end and last + 1 < len(tokens) and tokens[last + 1]['end']:
                last += 1
            return tokens[first:last + 1]
    near = [tokens[i]['text'] for i in plain]
    raise SystemExit(f'not found: {ref} {words}\n  {" ".join(skeleton(x) for x in near)}\n  want {" ".join(want)}')


def parts(text):
    """A line's text and examples in order; an example is a list of words, each its page and glyphs."""
    out, at = [], 0
    for m in re.finditer(r'\[\[([^|\]]+)\|([^\]]+)\]\]', text):
        if m.start() > at:
            out.append(text[at:m.start()])
        out.append({'q': [[t['page'], t['glyphs']] for t in find(m.group(1), m.group(2))]})
        at = m.end()
    if at < len(text):
        out.append(text[at:])
    return out


pages, examples = [], 0
for raw in open(SRC, encoding='utf-8').read().split('\n'):
    line = raw.strip()
    if not line or line.startswith('# ') and not pages or line.startswith('#') and not line.startswith('# ') and not line.startswith('## '):
        continue
    if line.startswith('= '):
        pages.append({'letter': line[2:], 'blocks': []})
        continue
    if line == '@basmalah':
        pages[-1]['blocks'].append({'k': 'basmalah'})
        continue
    kind, body = 'p', line
    for mark, k in (('## ', 'h2'), ('# ', 'h1'), ('- ', 'li')):
        if line.startswith(mark):
            kind, body = k, line[len(mark):]
            break
    block = {'k': kind, 'parts': parts(body)}
    examples += sum(1 for x in block['parts'] if isinstance(x, dict))
    pages[-1]['blocks'].append(block)

json.dump({'pages': pages}, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('pages', len(pages), 'examples', examples)

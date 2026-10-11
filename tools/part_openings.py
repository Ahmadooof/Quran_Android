"""
Writes app/src/main/res/raw/part_openings.json: the ayah each juz and hizb begins at and its first words, as a printed
mushaf's index names them. Where they begin comes from the reference copy's ayah data; the words from the app's own
plain text, which the UI font draws cleanly.

    python tools/part_openings.py [reference.json]
"""
import json
import os
import sys

HERE = os.path.dirname(__file__)
REFERENCE = sys.argv[1] if len(sys.argv) > 1 else r'C:\Projects\Quran\reference\alquran-cloud.json'
TEXT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'data', 'quran-simple.txt')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'raw', 'part_openings.json')
BASMALAH = 'بسم الله الرحمن الرحيم '
WORDS = 4


def opening(text, surah, ayah):
    # The basmalah heads a surah's first ayah in this text; the words that open the part come after it
    if ayah == 1 and surah != 1 and text.startswith(BASMALAH):
        text = text[len(BASMALAH):]
    # Pause marks are not words
    words = [w for w in text.split() if any('\u0621' <= c <= '\u064a' for c in w)]
    return ' '.join(words[:WORDS])


def main():
    with open(TEXT, encoding='utf-8') as f:
        lines = {}
        for line in f:
            if '|' in line and not line.startswith('#'):
                s, a, t = line.rstrip('\n').split('|', 2)
                lines[(int(s), int(a))] = t
    with open(REFERENCE, encoding='utf-8') as f:
        surahs = json.load(f)['data']['surahs']

    juz, hizb = {}, {}
    for surah in surahs:
        for ayah in surah['ayahs']:
            at = (surah['number'], ayah['numberInSurah'])
            juz.setdefault(ayah['juz'], at)
            quarter = ayah['hizbQuarter']
            if quarter % 4 == 1:
                hizb.setdefault((quarter + 3) // 4, at)

    def said(starts):
        return [[s, a, opening(lines[(s, a)], s, a)] for s, a in (starts[n] for n in sorted(starts))]

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'juz': said(juz), 'hizb': said(hizb)}, f, ensure_ascii=False, separators=(',', ':'))
    print(len(juz), 'juz,', len(hizb), 'hizb')


if __name__ == '__main__':
    main()

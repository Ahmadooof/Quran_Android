"""
Writes app/src/main/res/raw/part_openings.json: the ayah each juz and hizb begins at, from the reference copy's
ayah data.

    python tools/part_openings.py [reference.json]
"""
import json
import os
import sys

HERE = os.path.dirname(__file__)
REFERENCE = sys.argv[1] if len(sys.argv) > 1 else r'C:\Projects\Quran\reference\alquran-cloud.json'
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'raw', 'part_openings.json')


def main():
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
        return [list(starts[n]) for n in sorted(starts)]

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'juz': said(juz), 'hizb': said(hizb)}, f, separators=(',', ':'))
    print(len(juz), 'juz,', len(hizb), 'hizb')


if __name__ == '__main__':
    main()

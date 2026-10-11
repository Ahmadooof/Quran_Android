"""
Writes app/src/main/res/raw/word_counts.json: how many word recordings each ayah has, and their size in all, read
from the word-by-word folder (files named sss_aaa_www.mp3), so the app can fetch every word for listening offline.

    python tools/word_counts.py [folder]
"""
import json
import os
import sys

FOLDER = sys.argv[1] if len(sys.argv) > 1 else r'C:\Projects\quran-wbw-audio-64k'
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw', 'word_counts.json')
SURAHS = 114


def main():
    counts = {}
    total = 0
    for entry in os.scandir(FOLDER):
        name = entry.name
        if not name.endswith('.mp3'):
            continue
        surah, ayah, word = (int(p) for p in name[:-4].split('_'))
        key = (surah, ayah)
        counts[key] = max(counts.get(key, 0), word)
        total += entry.stat().st_size
    ayahs = [[counts[(s, a)] for a in range(1, 1 + max(a for (x, a) in counts if x == s))] for s in range(1, SURAHS + 1)]
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'bytes': total, 'ayahs': ayahs}, f, separators=(',', ':'))
    print(sum(map(sum, ayahs)), 'words,', round(total / 1e6), 'MB')


if __name__ == '__main__':
    main()

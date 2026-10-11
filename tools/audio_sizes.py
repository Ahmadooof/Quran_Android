"""
Writes app/src/main/res/raw/audio_sizes.json: the size in bytes of every surah's recitation, by reciter, so the
downloads screen knows the whole Quran's size at once instead of asking the server surah by surah.

Run again whenever a recitation is added or its files change:

    python tools/audio_sizes.py
"""
import json
import os
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main')
ASSETS = os.path.join(ROOT, 'assets')
OUT = os.path.join(ROOT, 'res', 'raw', 'audio_sizes.json')
BUCKET = 'https://audio.readqurantoday.com'
SURAHS = 114


# The same address the app uses (Recite.urlFor): the timing file's audioPath, or <reciter>/<nnn>.mp3
def url(reciter, surah):
    padded = '%03d' % surah
    path = ''
    try:
        with open(os.path.join(ASSETS, 'surah', str(surah), '%s.%s.timing.json' % (padded, reciter)), encoding='utf-8') as f:
            path = json.load(f).get('audioPath', '')
    except OSError:
        pass
    return '%s/%s' % (BUCKET, path or '%s/%s.mp3' % (reciter, padded))


def size(address):
    # As the app's own requests present themselves; the bucket turns away Python's default
    request = urllib.request.Request(address, method='HEAD', headers={'User-Agent': 'Dalvik/2.1.0 (Linux; U; Android 14)'})
    with urllib.request.urlopen(request, timeout=20) as r:
        return int(r.headers['Content-Length'])


def main():
    with open(os.path.join(ASSETS, 'data', 'recitations.json'), encoding='utf-8') as f:
        reciters = [r['id'] for r in json.load(f)['recitations']]
    sizes = {}
    with ThreadPoolExecutor(8) as pool:
        for reciter in reciters:
            sizes[reciter] = list(pool.map(lambda s: size(url(reciter, s)), range(1, SURAHS + 1)))
            print('%-28s %6.0f MB' % (reciter, sum(sizes[reciter]) / 1e6))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(sizes, f, separators=(',', ':'))


if __name__ == '__main__':
    main()

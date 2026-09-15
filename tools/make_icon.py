import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

# Regenerates the launcher icon from the page fonts: python tools/make_icon.py
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main')
WORDS = [(283, 0xFC51), (266, 0xFCB2)]  # القرآن (17:9), العظيم (15:87)

def glyph(page, cp):
    f = TTFont(f'{ROOT}/fonts-ttf/p{page}.ttf')
    gs = f.getGlyphSet()
    g = gs[f.getBestCmap()[cp]]
    b = BoundsPen(gs); g.draw(b)
    return gs, g, b.bounds

def paths(box_size, center):
    """Both words stacked, scaled to fit a square of box_size, centred on center; returns path data."""
    items = [glyph(p, c) for p, c in WORDS]
    gap = -350  # font units: the lines tuck into each other a little
    widths = [b[2] - b[0] for _, _, b in items]
    heights = [b[3] - b[1] for _, _, b in items]
    total_h = sum(heights) + gap
    total_w = max(widths)
    s = box_size / max(total_w, total_h)
    out = []
    y_top = center[1] - total_h * s / 2
    for (gs, g, b), w, h in zip(items, widths, heights):
        x_left = center[0] - w * s / 2
        # font y up -> vector y down: x' = s*(x - minx) + x_left, y' = y_top + s*(maxy - y)
        t = (s, 0, 0, -s, x_left - s * b[0], y_top + s * b[3])
        pen = SVGPathPen(gs, lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
        g.draw(TransformPen(pen, t))
        out.append(pen.getCommands())
        y_top += (h + gap) * s
    return out

def vector(size, body, extra=''):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp" android:height="{size}dp"
    android:viewportWidth="{size}" android:viewportHeight="{size}">
{extra}{body}</vector>
'''

def glyph_paths(ps, colour):
    # A thin stroke of the same colour thickens the letters enough to hold at launcher size
    return ''.join(f'''    <path android:fillColor="{colour}" android:strokeColor="{colour}" android:strokeWidth="0.45" android:strokeLineJoin="round" android:pathData="{d}" />\n''' for d in ps)

# adaptive foreground: 108dp canvas, words inside the 66dp safe circle
fg = vector(108, glyph_paths(paths(54, (55, 53)), '@color/icon_ink'))
os.makedirs(f'{ROOT}/res/drawable', exist_ok=True)
open(f'{ROOT}/res/drawable/ic_launcher_foreground.xml', 'w', encoding='utf-8').write(
    fg.replace('<?xml version="1.0" encoding="utf-8"?>\n', '<?xml version="1.0" encoding="utf-8"?>\n<!-- App icon: القرآن العظيم in the mushaf\'s own calligraphy (17:9 and 15:87). Generated from the page fonts. -->\n'))

# before Android 8: a round badge drawn whole
legacy_bg = '    <path android:fillColor="@color/icon_ground" android:pathData="M24,2 A22,22 0 1 1 23.99,2 Z" />\n'
legacy = vector(48, glyph_paths(paths(30, (24.5, 23.5)), '@color/icon_ink'), legacy_bg)
os.makedirs(f'{ROOT}/res/mipmap-anydpi', exist_ok=True)
open(f'{ROOT}/res/mipmap-anydpi/ic_launcher.xml', 'w', encoding='utf-8').write(
    legacy.replace('<?xml version="1.0" encoding="utf-8"?>\n', '<?xml version="1.0" encoding="utf-8"?>\n<!-- App icon before Android 8, which has no adaptive icons: the same words on a round badge. -->\n'))

os.makedirs(f'{ROOT}/res/mipmap-anydpi-v26', exist_ok=True)
open(f'{ROOT}/res/mipmap-anydpi-v26/ic_launcher.xml', 'w', encoding='utf-8').write('''<?xml version="1.0" encoding="utf-8"?>
<!-- Adaptive app icon; monochrome lets themed launchers tint the words. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_ground" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
''')
print('ok')

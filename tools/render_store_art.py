"""Render Play Store art from the launcher icon's vector paths: the 512px icon, the 1024x500 feature graphic and the developer page header.

    python tools/render_store_art.py

Fills with the non-zero winding rule, as Android draws vector paths, supersampled for smooth edges.
"""
import os
import re

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
MAIN = os.path.join(ROOT, 'app', 'src', 'main')
OUT = os.path.join(ROOT, 'store')

GROUND = (0x1A, 0x6F, 0xA3)
INK = (0xFF, 0xFD, 0xF7)
SS = 4  # supersampling factor
ICON_FILL = 0.84  # of the icon's width the name takes; the rest is the margin Play's rounded corners need


def contours(path_data):
    """Flatten SVG path data (M L H V Q C Z, absolute) into point lists."""
    toks = re.findall(r'[MLHVQCZ]|-?\d*\.?\d+(?:e-?\d+)?', path_data)
    out, cur, cmd, i = [], None, None, 0

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if t in 'MLHVQCZ':
            cmd = t; i += 1
            if cmd == 'Z':
                cur = None
            continue
        if cmd == 'M':
            cur = [(num(), num())]; out.append(cur); cmd = 'L'
        elif cmd == 'L':
            cur.append((num(), num()))
        elif cmd == 'H':
            cur.append((num(), cur[-1][1]))
        elif cmd == 'V':
            cur.append((cur[-1][0], num()))
        elif cmd == 'Q':
            ax, ay, bx, by = num(), num(), num(), num()
            x0, y0 = cur[-1]
            for k in range(1, 13):
                u = k / 12; m = 1 - u
                cur.append((m * m * x0 + 2 * m * u * ax + u * u * bx, m * m * y0 + 2 * m * u * ay + u * u * by))
        elif cmd == 'C':
            ax, ay, bx, by, cx, cy = (num() for _ in range(6))
            x0, y0 = cur[-1]
            for k in range(1, 17):
                u = k / 16; m = 1 - u
                cur.append((m**3 * x0 + 3 * m * m * u * ax + 3 * m * u * u * bx + u**3 * cx,
                            m**3 * y0 + 3 * m * m * u * ay + 3 * m * u * u * by + u**3 * cy))
    return [c for c in out if len(c) >= 3]


def coverage(polys, width, height):
    """Anti-aliased coverage mask (0..1) of polygons in pixel space, non-zero winding."""
    W, H = width * SS, height * SS
    edges = []
    for poly in polys:
        pts = [(x * SS, y * SS) for x, y in poly]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
            if y0 != y1:
                edges.append((x0, y0, x1, y1))
    e = np.array(edges, dtype=np.float64)
    x0, y0, x1, y1 = e.T
    ymin, ymax = np.minimum(y0, y1), np.maximum(y0, y1)
    wind = np.where(y1 > y0, 1, -1)
    mask = np.zeros((H, W), dtype=np.uint8)
    for row in range(H):
        y = row + 0.5
        hit = (ymin <= y) & (ymax > y)
        if not hit.any():
            continue
        xs = x0[hit] + (y - y0[hit]) * (x1[hit] - x0[hit]) / (y1[hit] - y0[hit])
        order = np.argsort(xs)
        xs, ws = xs[order], wind[hit][order]
        total = np.cumsum(ws)
        for k in range(len(xs) - 1):
            if total[k] != 0:
                a, b = int(np.ceil(xs[k] - 0.5)), int(np.ceil(xs[k + 1] - 0.5))
                if b > a:
                    mask[row, max(a, 0):min(b, W)] = 1
    return mask.reshape(height, SS, width, SS).mean(axis=(1, 3))


def glyph_paths():
    src = open(os.path.join(MAIN, 'res', 'drawable', 'ic_launcher_foreground.xml'), encoding='utf-8').read()
    return [c for d in re.findall(r'android:pathData="([^"]+)"', src) for c in contours(d)]


def paint(width, height, polys):
    alpha = coverage(polys, width, height)[..., None]
    ground = np.array(GROUND, dtype=np.float64)
    ink = np.array(INK, dtype=np.float64)
    img = ground * (1 - alpha) + ink * alpha
    return Image.fromarray(img.round().astype(np.uint8), 'RGB')


def transformed(polys, scale, dx, dy):
    return [[(x * scale + dx, y * scale + dy) for x, y in p] for p in polys]


def main():
    os.makedirs(OUT, exist_ok=True)
    polys = glyph_paths()
    xs = [x for p in polys for x, _ in p]
    ys = [y for p in polys for _, y in p]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)

    # Icon: the name fills the square, since Play draws the whole picture rather than cropping to a launcher's mask
    box = 512 * ICON_FILL
    scale = box / max(maxx - minx, maxy - miny)
    dx = (512 - (maxx - minx) * scale) / 2 - minx * scale
    dy = (512 - (maxy - miny) * scale) / 2 - miny * scale
    paint(512, 512, transformed(polys, scale, dx, dy)).save(os.path.join(OUT, 'icon-512.png'))

    # Feature graphic: the words large on the left, the English name on the right
    scale = 380 / (maxy - miny)
    words_w = (maxx - minx) * scale
    left = 90
    art = paint(1024, 500, transformed(polys, scale, left - minx * scale, 250 - (miny + maxy) / 2 * scale))
    draw = ImageDraw.Draw(art)
    font = ImageFont.truetype(os.path.join(MAIN, 'res', 'font', 'cairo.ttf'), 64)
    small = ImageFont.truetype(os.path.join(MAIN, 'res', 'font', 'cairo.ttf'), 30)
    x = left + words_w + 70
    draw.text((x, 170), 'The Great Quran', font=font, fill=INK)
    draw.text((x, 262), 'Madinah Mushaf · recitation', font=small, fill=(0xD6, 0xE6, 0xF1))
    art.save(os.path.join(OUT, 'feature-graphic-1024x500.png'))

    # Developer page header: the name centred, since phones crop the sides
    W, H = 4096, 2304
    scale = 1150 / (maxy - miny)
    words_w = (maxx - minx) * scale
    left = (W - words_w) / 2
    top = 330
    header = paint(W, H, transformed(polys, scale, left - minx * scale, top - miny * scale))
    draw = ImageDraw.Draw(header)
    big = ImageFont.truetype(os.path.join(MAIN, 'res', 'font', 'cairo.ttf'), 150)
    line = 'Read Quran Today'
    width = draw.textlength(line, font=big)
    draw.text(((W - width) / 2, top + 1150 + 130), line, font=big, fill=(0xD6, 0xE6, 0xF1))
    header.save(os.path.join(OUT, 'developer-header-4096x2304.png'), optimize=True)
    print('wrote', os.listdir(OUT))


if __name__ == '__main__':
    main()

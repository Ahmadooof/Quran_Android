package com.readqurantoday.quran

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.createBitmap

/**
 * Draws a recitation video's pictures. The page is drawn whole as the reader shows it; for a line or a word, each
 * word is drawn alone, every other ink clear, so nothing of the lines above and below or of the next word shows.
 */
class VideoFrames(context: Context, private val style: Style, look: VideoLook, phonetics: Boolean) {

    enum class Style { WORD, LINE, PAGE }

    private class Drawn(val view: MushafPageView, val boxes: Map<Long, RectF>) {
        // The ayah number marks alone, for a line's own marks
        var marks: Bitmap? = null
    }

    // The pages are drawn with the video's own choice of pronunciation, whatever the reader's
    private val context = object : ContextWrapper(context), Settings.OwnPhonetics {
        override val phonetics = phonetics && style == Style.PAGE
    }
    private val paper = look.paper
    private val ink = look.ink
    private val lit = look.lit
    // The word alone is drawn from a page twice as large, so it stays sharp shown big
    private val scale = if (style == Style.WORD) 2 else 1
    private val pageW = WIDE * scale
    private val pageH = TALL * scale
    // A word alone: its ink in white on clear, and where that ink sits on the page
    private class Alone(val mask: Bitmap, val ink: RectF)

    private val drawn = LinkedHashMap<Int, Drawn>()
    private val alone = HashMap<Long, Alone>()
    private val scratch by lazy { createBitmap(pageW, pageH) }
    private val tint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private var last = 0

    private fun drawnPage(page: Int): Drawn = drawn.getOrPut(page) {
        if (drawn.size >= KEPT) drawn.remove(drawn.keys.first())?.marks?.recycle()
        val view = MushafPageView(context).apply {
            show(page)
            measure(View.MeasureSpec.makeMeasureSpec(pageW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pageH, View.MeasureSpec.EXACTLY))
            layout(0, 0, pageW, pageH)
        }
        Drawn(view, boxesOf(view))
    }

    // The page names the word under a point; asked across the whole page, that gives each word's box
    private fun boxesOf(view: MushafPageView): Map<Long, RectF> {
        val boxes = HashMap<Long, RectF>()
        val step = GRID * scale
        var y = 0f
        while (y < pageH) {
            var x = 0f
            while (x < pageW) {
                view.wordUnder(x, y)?.let { (s, a, w) -> boxes.getOrPut(key(s, a, w)) { RectF(x, y, x, y) }.union(x, y) }
                x += step
            }
            y += step
        }
        boxes.values.forEach { it.inset(-step.toFloat(), -step.toFloat()) }
        return boxes
    }

    /** The page [word] of [surah]:[ayah] is on, looked for from the last one found. */
    fun pageOf(surah: Int, ayah: Int, word: Int): Int {
        val from = Surahs.list().firstOrNull { it.id == surah } ?: return 0
        val start = if (last in from.from..from.to) last else Ayat.pageOf(surah, ayah).takeIf { it > 0 } ?: from.from
        val found = (start..from.to).firstOrNull { drawnPage(it).view.holds(surah, ayah, word) }
            ?: (from.from until start).firstOrNull { drawnPage(it).view.holds(surah, ayah, word) }
            ?: return 0
        last = found
        return found
    }

    /** One picture of [out]'s size, with [word] of [surah]:[ayah] lit on [page]. */
    fun draw(page: Int, surah: Int, ayah: Int, word: Int, out: Bitmap) {
        val at = drawnPage(page)
        val canvas = Canvas(out)
        canvas.drawColor(paper)
        val litKey = key(surah, ayah, word)
        val box = at.boxes[litKey]
        if (style == Style.PAGE || box == null) {
            at.view.tryOn(lit = lit, mark = ink, ink = ink, paper = paper)
            at.view.light(surah, ayah, word)
            // The upright page fitted whole into the picture, whatever its shape
            scratch.eraseColor(paper)
            at.view.draw(Canvas(scratch))
            val fit = minOf(out.width.toFloat() / pageW, out.height.toFloat() / pageH)
            val left = (out.width - pageW * fit) / 2f
            val top = (out.height - pageH * fit) / 2f
            canvas.drawBitmap(scratch, null, RectF(left, top, left + pageW * fit, top + pageH * fit), plain)
            return
        }
        // The line: every word at the lit word's height, with the ayah marks between them
        val words = (if (style == Style.LINE) at.boxes.filter { it.value.centerY() in box.top..box.bottom } else mapOf(litKey to box))
            .mapValues { (k, b) -> aloneOf(at, k, b) }
        // As tall and wide as the ink itself, every mark above and below included
        val area = RectF(words.getValue(litKey).ink)
        words.values.forEach { area.union(it.ink) }
        if (style == Style.LINE) area.inset(-box.height() * MARK_ROOM, -box.height() * BREATH)
        else area.inset(-box.height() * BREATH, -box.height() * BREATH)

        // In a strip the line is the whole picture; elsewhere it keeps to a band across the middle
        val lineTall = if (out.width > out.height * STRIP_FROM) STRIP_TALL else LINE_TALL
        val fit = if (style == Style.LINE) minOf(out.width * LINE_WIDE / area.width(), out.height * lineTall / area.height())
                  else minOf(out.width * WORD_WIDE / area.width(), out.height * WORD_TALL / area.height())
        val left = (out.width - area.width() * fit) / 2f
        val top = (out.height - area.height() * fit) / 2f
        fun place(r: RectF) = RectF(left + (r.left - area.left) * fit, top + (r.top - area.top) * fit,
            left + (r.right - area.left) * fit, top + (r.bottom - area.top) * fit)

        if (style == Style.LINE) {
            val cut = Rect(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
            if (cut.intersect(0, 0, pageW, pageH)) paint(canvas, lineMarks(at, cut, box), null, place(RectF(cut)), ink)
        }
        words.forEach { (k, word) -> paint(canvas, word.mask, null, place(word.ink), if (k == litKey) lit else ink) }
    }

    // The page drawn with only this word, in white, all else clear; its ink is measured, not guessed, so no mark is cut
    private fun aloneOf(at: Drawn, k: Long, box: RectF): Alone = alone.getOrPut(k) {
        scratch.eraseColor(Color.TRANSPARENT)
        at.view.tryOn(lit = Color.WHITE, mark = Color.TRANSPARENT, ink = Color.TRANSPARENT, paper = Color.TRANSPARENT)
        at.view.light((k / 1_000_000).toInt(), (k / 1000 % 1000).toInt(), (k % 1000).toInt())
        at.view.draw(Canvas(scratch))
        // Looked for well past the tap box: nothing else is drawn, so only this word's ink is found there
        val reach = box.height() * LOOK
        val look = Rect((box.left - reach).toInt(), (box.top - reach).toInt(), (box.right + reach).toInt(), (box.bottom + reach).toInt())
        if (!look.intersect(0, 0, pageW, pageH)) return@getOrPut Alone(createBitmap(1, 1), RectF(box))
        val w = look.width()
        val h = look.height()
        val px = IntArray(w * h)
        scratch.getPixels(px, 0, w, look.left, look.top, w, h)
        // The word is white; the transliteration label over it is dark, and left out
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val a = Color.alpha(c) * Color.red(c) / 255
            px[y * w + x] = Color.argb(a, 255, 255, 255)
            if (a > 0) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        }
        if (r < 0) return@getOrPut Alone(createBitmap(1, 1), RectF(box))
        val mask = createBitmap(r - l + 1, b - t + 1)
        mask.setPixels(px, t * w + l, w, 0, 0, r - l + 1, b - t + 1)
        Alone(mask, RectF((look.left + l).toFloat(), (look.top + t).toFloat(), (look.left + r + 1).toFloat(), (look.top + b + 1).toFloat()))
    }

    // The page's ayah marks alone, white on clear
    private fun marksOf(at: Drawn): Bitmap = at.marks ?: createBitmap(pageW, pageH).also { marks ->
        at.view.tryOn(lit = Color.TRANSPARENT, mark = Color.WHITE, ink = Color.TRANSPARENT, paper = Color.TRANSPARENT)
        at.view.light(0, 0, -1)
        at.view.draw(Canvas(marks))
        at.marks = marks
    }

    // This line's ayah marks alone: each mark in [cut] is kept only if its middle sits on the line of [line]
    private fun lineMarks(at: Drawn, cut: Rect, line: RectF): Bitmap {
        val w = cut.width()
        val h = cut.height()
        val px = IntArray(w * h)
        marksOf(at).getPixels(px, 0, w, cut.left, cut.top, w, h)
        val kept = IntArray(w * h)
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        for (start in px.indices) {
            if (seen[start] || Color.alpha(px[start]) == 0) continue
            // One mark, or one piece of it: every touching inked pixel
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var top = h
            var bottom = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (y < top) top = y
                if (y > bottom) bottom = y
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (n >= 0 && !seen[n] && Color.alpha(px[n]) > 0) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
            }
            if (cut.top + (top + bottom) / 2f in line.top..line.bottom) for (j in 0 until tail) kept[queue[j]] = px[queue[j]]
        }
        return createBitmap(w, h).apply { setPixels(kept, 0, w, 0, 0, w, h) }
    }

    private fun paint(canvas: Canvas, white: Bitmap, from: Rect?, to: RectF, colour: Int) {
        tint.colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(white, from, to, tint)
    }

    fun release() {
        drawn.values.forEach { it.marks?.recycle() }
        drawn.clear()
        alone.values.forEach { it.mask.recycle() }
        alone.clear()
    }

    private fun key(surah: Int, ayah: Int, word: Int) = (surah * 1000L + ayah) * 1000L + word

    companion object {
        // The page is drawn upright at this size, whatever the video's shape
        private const val WIDE = 1080
        private const val TALL = 1920
        private const val GRID = 6
        private const val KEPT = 3
        // How far past its tap box a word's ink is looked for, as a share of the box's height
        private const val LOOK = 1f
        // Room around the ink, as a share of a line's height
        private const val BREATH = 0.25f
        // Room each side of a line for an ayah mark that ends it, as a share of the line's height
        private const val MARK_ROOM = 1f
        private const val LINE_WIDE = 0.94f
        private const val LINE_TALL = 0.3f
        private const val STRIP_TALL = 0.9f
        private const val STRIP_FROM = 3
        private const val WORD_WIDE = 0.85f
        private const val WORD_TALL = 0.4f
    }
}

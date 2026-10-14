package com.readqurantoday.quran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.createBitmap

/** Draws a recitation video's pictures: a page with one word lit, shown whole, as its line, or as the word alone. */
class VideoFrames(private val context: Context, private val style: Style) {

    enum class Style { WORD, LINE, PAGE }

    private class Drawn(val view: MushafPageView, val boxes: Map<Long, RectF>, val shot: Bitmap)

    private val paper = Settings.paperColor(context)
    // The word alone is cut from a page drawn twice as large, so it stays sharp when shown big
    private val scale = if (style == Style.WORD) 2 else 1
    private val pageW = WIDE * scale
    private val pageH = TALL * scale
    private val drawn = HashMap<Int, Drawn>()
    private var last = 0

    // A page is kept drawn while its words are recited; one more is laid out as the recitation turns to it
    private fun drawnPage(page: Int): Drawn = drawn.getOrPut(page) {
        if (drawn.size >= KEPT) drawn.remove(drawn.keys.first())?.shot?.recycle()
        val view = MushafPageView(context).apply {
            show(page)
            measure(View.MeasureSpec.makeMeasureSpec(pageW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pageH, View.MeasureSpec.EXACTLY))
            layout(0, 0, pageW, pageH)
        }
        Drawn(view, boxesOf(view), createBitmap(pageW, pageH))
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
        at.view.light(surah, ayah, word)
        val canvas = Canvas(out)
        canvas.drawColor(paper)
        if (style == Style.PAGE) {
            at.view.draw(canvas)
            return
        }
        at.view.draw(Canvas(at.shot))
        val box = at.boxes[key(surah, ayah, word)] ?: return at.view.draw(canvas)
        val pad = PAD * scale
        // The line is every word at the lit word's height; the word is its own box
        val shown = if (style == Style.LINE) lineOf(at.boxes, box) else box
        val cut = Rect((shown.left - pad).toInt(), (shown.top - pad).toInt(), (shown.right + pad).toInt(), (shown.bottom + pad).toInt())
        if (!cut.intersect(0, 0, pageW, pageH)) return at.view.draw(canvas)
        val fit = if (style == Style.LINE) out.width * LINE_WIDE / cut.width()
                  else minOf(out.width * WORD_WIDE / cut.width(), out.height * WORD_TALL / cut.height())
        val w = cut.width() * fit
        val h = cut.height() * fit
        val left = (out.width - w) / 2f
        val top = (out.height - h) / 2f
        canvas.drawBitmap(at.shot, cut, RectF(left, top, left + w, top + h), null)
    }

    private fun lineOf(boxes: Map<Long, RectF>, word: RectF): RectF {
        val line = RectF(word)
        boxes.values.filter { it.centerY() in word.top..word.bottom }.forEach { line.union(it) }
        return line
    }

    fun release() {
        drawn.values.forEach { it.shot.recycle() }
        drawn.clear()
    }

    private fun key(surah: Int, ayah: Int, word: Int) = (surah * 1000L + ayah) * 1000L + word

    companion object {
        /** The video's own size: upright, as a phone holds it. */
        const val WIDE = 1080
        const val TALL = 1920
        private const val GRID = 6
        private const val PAD = 12
        private const val KEPT = 3
        // The word alone fills at most this share of the picture's width and height
        private const val WORD_WIDE = 0.85f
        private const val WORD_TALL = 0.4f
        private const val LINE_WIDE = 0.94f
    }
}

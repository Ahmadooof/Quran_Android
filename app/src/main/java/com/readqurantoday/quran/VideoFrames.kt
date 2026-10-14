package com.readqurantoday.quran

import android.content.Context
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
class VideoFrames(private val context: Context, private val style: Style) {

    enum class Style { WORD, LINE, PAGE }

    private class Drawn(val view: MushafPageView, val boxes: Map<Long, RectF>) {
        // The ayah number marks alone, for a line's own marks
        var marks: Bitmap? = null
    }

    private val paper = Settings.paperColor(context)
    private val ink = Settings.inkColor(context)
    private val lit = Settings.highlightColor(context)
    private val markInk = Settings.resolvedAyahColor(context)
    // The word alone is drawn from a page twice as large, so it stays sharp shown big
    private val scale = if (style == Style.WORD) 2 else 1
    private val pageW = WIDE * scale
    private val pageH = TALL * scale
    private val drawn = LinkedHashMap<Int, Drawn>()
    private val alone = HashMap<Long, Bitmap>()
    private val scratch by lazy { createBitmap(pageW, pageH) }
    private val tint = Paint(Paint.FILTER_BITMAP_FLAG)
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
            at.view.tryOn()
            at.view.light(surah, ayah, word)
            at.view.draw(canvas)
            return
        }
        // The line: every word at the lit word's height, with the ayah marks between them
        val words = if (style == Style.LINE) at.boxes.filter { it.value.centerY() in box.top..box.bottom } else mapOf(litKey to box)
        val area = RectF(grown(box))
        words.values.forEach { area.union(grown(it)) }
        if (style == Style.LINE) area.inset(-box.height() * MARK_ROOM, 0f)

        val fit = if (style == Style.LINE) minOf(out.width * LINE_WIDE / area.width(), out.height * LINE_TALL / area.height())
                  else minOf(out.width * WORD_WIDE / area.width(), out.height * WORD_TALL / area.height())
        val left = (out.width - area.width() * fit) / 2f
        val top = (out.height - area.height() * fit) / 2f
        fun place(r: RectF) = RectF(left + (r.left - area.left) * fit, top + (r.top - area.top) * fit,
            left + (r.right - area.left) * fit, top + (r.bottom - area.top) * fit)

        if (style == Style.LINE) {
            val cut = Rect(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
            if (cut.intersect(0, 0, pageW, pageH)) paint(canvas, marksOf(at), cut, place(RectF(cut)), markInk)
        }
        words.forEach { (k, b) ->
            val g = grown(b)
            paint(canvas, aloneOf(at, k, g), null, place(g), if (k == litKey) lit else ink)
        }
    }

    // A word's marks above and below reach a little past the box it answers taps in
    private fun grown(box: RectF) = RectF(box.left, box.top - box.height() * GROW, box.right, box.bottom + box.height() * GROW)

    // The page drawn with only this word in ink, white on clear, cut to its grown box
    private fun aloneOf(at: Drawn, k: Long, grownBox: RectF): Bitmap = alone.getOrPut(k) {
        scratch.eraseColor(Color.TRANSPARENT)
        at.view.tryOn(lit = Color.WHITE, mark = Color.TRANSPARENT, ink = Color.TRANSPARENT, paper = Color.TRANSPARENT)
        at.view.light((k / 1_000_000).toInt(), (k / 1000 % 1000).toInt(), (k % 1000).toInt())
        at.view.draw(Canvas(scratch))
        val cut = Rect(grownBox.left.toInt(), grownBox.top.toInt(), grownBox.right.toInt(), grownBox.bottom.toInt())
        if (!cut.intersect(0, 0, pageW, pageH)) return@getOrPut createBitmap(1, 1)
        Bitmap.createBitmap(scratch, cut.left, cut.top, cut.width(), cut.height())
    }

    // The page's ayah marks alone, white on clear
    private fun marksOf(at: Drawn): Bitmap = at.marks ?: createBitmap(pageW, pageH).also { marks ->
        at.view.tryOn(lit = Color.TRANSPARENT, mark = Color.WHITE, ink = Color.TRANSPARENT, paper = Color.TRANSPARENT)
        at.view.light(0, 0, -1)
        at.view.draw(Canvas(marks))
        at.marks = marks
    }

    private fun paint(canvas: Canvas, white: Bitmap, from: Rect?, to: RectF, colour: Int) {
        tint.colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(white, from, to, tint)
    }

    fun release() {
        drawn.values.forEach { it.marks?.recycle() }
        drawn.clear()
        alone.values.forEach { it.recycle() }
        alone.clear()
    }

    private fun key(surah: Int, ayah: Int, word: Int) = (surah * 1000L + ayah) * 1000L + word

    companion object {
        /** The video's own size: upright, as a phone holds it. */
        const val WIDE = 1080
        const val TALL = 1920
        private const val GRID = 6
        private const val KEPT = 3
        // How far a word's marks reach past its tap box, as a share of the box's height
        private const val GROW = 0.18f
        // Room each side of a line for an ayah mark that ends it, as a share of the line's height
        private const val MARK_ROOM = 1f
        private const val LINE_WIDE = 0.94f
        private const val LINE_TALL = 0.3f
        private const val WORD_WIDE = 0.85f
        private const val WORD_TALL = 0.4f
    }
}

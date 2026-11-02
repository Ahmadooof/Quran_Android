package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.ceil

/**
 * One printed line of a closing page, kept whole as the print has it: its words spread to the full width,
 * or set from the right as a paragraph's last line, or centred as a title. Its size is set by its page.
 */
@SuppressLint("ViewConstructor")
class EndLine(context: Context, private val text: CharSequence, private val mode: Mode, private val paint: TextPaint) : View(context) {

    enum class Mode { JUSTIFY, START, CENTER }

    // The words, as ranges of the text between its spaces
    private val runs: List<IntRange> = buildList {
        var start = -1
        for (i in 0..text.length) {
            val space = i == text.length || text[i] == ' '
            if (!space && start < 0) start = i
            if (space && start >= 0) { add(start until i); start = -1 }
        }
    }

    /** The type size, set by the page so its lines share one. */
    var size = 0f
        set(value) {
            field = value
            laid.clear()
        }

    /** The line's height, set by the page so its lines share it down the frame; at least its own. */
    var tall = 0f

    private val laid = ArrayList<StaticLayout>()
    private val widths = ArrayList<Float>()

    /** How wide the line is at [size] with the least gap between words. */
    fun natural(size: Float): Float {
        paint.textSize = size
        return runs.sumOf { Layout.getDesiredWidth(text, it.first, it.last + 1, paint).toDouble() }.toFloat() +
            size * GAP * (runs.size - 1).coerceAtLeast(0)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), ceil(maxOf(tall, size * TALL)).toInt())
    }

    private fun lay() {
        if (laid.isNotEmpty() || runs.isEmpty()) return
        paint.textSize = size
        for (run in runs) {
            val w = Layout.getDesiredWidth(text, run.first, run.last + 1, paint)
            widths.add(w)
            laid.add(StaticLayout.Builder.obtain(text, run.first, run.last + 1, paint, ceil(w).toInt() + 1)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build())
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (size <= 0f) return
        widths.clear()
        laid.clear()
        lay()
        paint.textSize = size
        val total = widths.sum()
        val gaps = (runs.size - 1).coerceAtLeast(1)
        val least = size * GAP
        val gap = if (mode == Mode.JUSTIFY && runs.size > 1) ((width - total) / gaps).coerceAtLeast(least) else least
        val used = total + gap * (runs.size - 1)
        // Right to left from the line's start; a title stands in the middle
        var right = if (mode == Mode.CENTER) (width + used) / 2f else width.toFloat()
        // Its words stand in the middle of the line's height, as they would in its own
        val baseline = height / 2f + size * (BASELINE - TALL / 2f)
        for (i in laid.indices) {
            val layout = laid[i]
            canvas.save()
            canvas.translate(right - layout.width, baseline - layout.getLineBaseline(0))
            layout.draw(canvas)
            canvas.restore()
            right -= widths[i] + gap
        }
    }

    private companion object {
        // In ems of the line's size: the least gap between words, the line's height, and where its baseline sits
        const val GAP = 0.28f
        const val TALL = 2.3f
        const val BASELINE = 1.5f
    }
}

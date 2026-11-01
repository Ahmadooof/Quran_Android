package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils

/** The page's frame, after the printed mushaf's: a tinted strip between two rules, and cartouches for its labels. */
class PageFrame {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val strip = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val shape = Path()

    /** The frame along [left], [top], [right], [bottom], its rules [edge] thick, in [ink]. */
    fun frame(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, edge: Float, ink: Int) {
        val inset = edge * STRIP
        // Only the strip between the rules is tinted, so what is drawn inside stays as it is
        strip.reset()
        strip.addRect(left, top, right, bottom, Path.Direction.CW)
        strip.addRect(left + inset, top + inset, right - inset, bottom - inset, Path.Direction.CW)
        fill.color = ColorUtils.setAlphaComponent(ink, TINT)
        canvas.drawPath(strip, fill)
        line.color = ink
        line.strokeWidth = edge
        box.set(left, top, right, bottom)
        canvas.drawRect(box, line)
        line.strokeWidth = edge / 2f
        box.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRect(box, line)
        // A diamond at each corner of the strip
        fill.color = ink
        val half = inset / 2f
        for (x in floatArrayOf(left + half, right - half)) for (y in floatArrayOf(top + half, bottom - half)) {
            shape.reset()
            shape.moveTo(x, y - half * DIAMOND)
            shape.lineTo(x + half * DIAMOND, y)
            shape.lineTo(x, y + half * DIAMOND)
            shape.lineTo(x - half * DIAMOND, y)
            shape.close()
            canvas.drawPath(shape, fill)
        }
    }

    /** A cartouche for a label [half] either side of [centre], [tall] high about [mid], filled with [paper] and edged in [ink]. */
    fun label(canvas: Canvas, centre: Float, mid: Float, half: Float, tall: Float, edge: Float, ink: Int, paper: Int) {
        shape.cartouche(centre, mid, half, tall, box)
        fill.color = paper
        canvas.drawPath(shape, fill)
        line.color = ink
        line.strokeWidth = edge
        canvas.drawPath(shape, line)
    }

    private companion object {
        // The strip's width in rules, its tint out of 255, and the corner diamonds' size within it
        const val STRIP = 3.5f
        const val TINT = 60
        const val DIAMOND = 0.7f
    }
}

package com.readqurantoday.quran

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

// A small label over one word of the page: the lit word's transliteration
class WordTip(context: Context) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // The system face: it carries the dotted and macroned Latin letters
        typeface = Typeface.DEFAULT
        textSize = context.resources.getDimension(R.dimen.text_body)
    }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    /** Over the word bounded by [left]..[right], [top]..[bottom]; below it when there is no room above. */
    fun draw(
        canvas: Canvas, said: String,
        left: Float, top: Float, right: Float, bottom: Float,
        minX: Float, maxX: Float, minY: Float,
        ink: Int, paper: Int
    ) {
        val padX = text.textSize * 0.55f
        val padY = text.textSize * 0.32f
        val w = text.measureText(said) + 2 * padX
        val h = text.textSize + 2 * padY
        // The word's box stops short of its marks above and below, so the label clears them too
        val gap = text.textSize * 0.25f + (bottom - top) * MARK_CLEARANCE

        val cx = ((left + right) / 2f).coerceIn(minX + w / 2f, maxOf(minX + w / 2f, maxX - w / 2f))
        val above = top - gap - h >= minY
        val y0 = if (above) top - gap - h else bottom + gap
        box.set(cx - w / 2f, y0, cx + w / 2f, y0 + h)

        ground.color = ink or 0xFF000000.toInt()
        val round = h / 2f
        canvas.drawRoundRect(box, round, round, ground)
        text.color = paper
        val baseline = box.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.drawText(said, cx, baseline, text)
    }

    private companion object {
        const val MARK_CLEARANCE = 0.25f
    }
}

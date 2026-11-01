package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation

/** The band a surah opens with, after the printed mushaf's: a framed strip, starred either side, a cartouche for the title. */
class SurahBand {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val star = Path()
    private var starSize = 0f

    /**
     * Draws the band [wide] across and [tall] high about ([centre], [mid]), leaving a cartouche [titleWide] across for the title.
     * [ink] is the frame's colour; [paper] the page's, which the cartouche is filled with.
     */
    fun draw(canvas: Canvas, centre: Float, mid: Float, wide: Float, tall: Float, titleWide: Float, ink: Int, paper: Int) {
        val thick = tall * FRAME
        val left = centre - wide / 2f
        val right = centre + wide / 2f
        val top = mid - tall / 2f
        val bottom = mid + tall / 2f

        // The strip: a soft tint of the frame colour, edged twice
        fill.color = ColorUtils.setAlphaComponent(ink, TINT)
        box.set(left, top, right, bottom)
        canvas.drawRoundRect(box, thick * 2f, thick * 2f, fill)
        line.color = ink
        line.strokeWidth = thick
        canvas.drawRoundRect(box, thick * 2f, thick * 2f, line)
        val inset = thick * 3f
        box.set(left + inset, top + inset, right - inset, bottom - inset)
        line.strokeWidth = thick / 2f
        canvas.drawRoundRect(box, thick, thick, line)

        // The cartouche: rounded ends, the page's own colour, so the title reads as on the page
        val half = titleWide / 2f + tall * CARTOUCHE_PAD
        val high = tall * CARTOUCHE / 2f
        box.set(centre - half, mid - high, centre + half, mid + high)
        fill.color = paper
        canvas.drawRoundRect(box, high, high, fill)
        line.strokeWidth = thick
        canvas.drawRoundRect(box, high, high, line)

        // Eight-pointed stars along each side, from the cartouche out to the frame
        val size = tall * STAR
        if (size != starSize) shapeStar(size)
        fill.color = ColorUtils.setAlphaComponent(ink, STARS)
        val step = size * 1.8f
        var x = centre + half + step / 2f
        while (x + size < right - inset * 2f) {
            canvas.withTranslation(x, mid) { drawPath(star, fill) }
            canvas.withTranslation(2f * centre - x, mid) { drawPath(star, fill) }
            x += step
        }
    }

    // Two squares, one turned an eighth, about the origin
    private fun shapeStar(size: Float) {
        starSize = size
        val r = size / 2f
        val d = r * DIAGONAL
        star.reset()
        star.addRect(-r, -r, r, r, Path.Direction.CW)
        star.moveTo(0f, -d)
        star.lineTo(d, 0f)
        star.lineTo(0f, d)
        star.lineTo(-d, 0f)
        star.close()
    }

    private companion object {
        // Shares of the band's height
        const val FRAME = 0.035f
        const val CARTOUCHE = 0.72f
        const val CARTOUCHE_PAD = 0.45f
        const val STAR = 0.28f
        // Alphas out of 255
        const val TINT = 28
        const val STARS = 150
        // Half a square's diagonal, over half its side
        const val DIAGONAL = 1.4142f
    }
}

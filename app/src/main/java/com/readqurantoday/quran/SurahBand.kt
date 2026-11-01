package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The band a surah opens with, after the printed mushaf's: a framed strip of arabesque either side,
 * a medallion holding a lotus, scrolls curling out from it, and a notched cartouche for the title.
 */
class SurahBand {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val box = RectF()
    private val cartouche = Path()

    // One side panel in units of the band's height, x out from the cartouche; rebuilt when its width changes
    private val shapes = Path()
    private val ribbons = Path()
    private val dots = Path()
    private var panelWide = -1f

    /** The height a title may take inside a band [tall] high, its marks clear of the cartouche's edge. */
    fun titleRoom(tall: Float) = tall * CARTOUCHE * CLEAR

    /**
     * Draws the band [wide] across and [tall] high about ([centre], [mid]), leaving a cartouche [titleWide] across for the title.
     * [ink] draws the frame and outlines; [paper], the page's colour, fills the cartouche and the motifs.
     */
    fun draw(canvas: Canvas, centre: Float, mid: Float, wide: Float, tall: Float, titleWide: Float, ink: Int, paper: Int) {
        val edge = tall * FRAME
        val left = centre - wide / 2f
        val right = centre + wide / 2f
        val top = mid - tall / 2f
        val bottom = mid + tall / 2f

        // The strip: a tint of the frame colour inside a double frame
        fill.color = ColorUtils.setAlphaComponent(ink, GROUND)
        box.set(left, top, right, bottom)
        canvas.drawRect(box, fill)
        line.color = ink
        line.strokeWidth = edge
        canvas.drawRect(box, line)
        val inset = edge * 2.6f
        box.set(left + inset, top + inset, right - inset, bottom - inset)
        line.strokeWidth = edge / 2f
        canvas.drawRect(box, line)

        // The arabesque either side, mirrored about the cartouche
        val half = titleWide / 2f + tall * CARTOUCHE_PAD
        val panel = (right - edge * 4f - (centre + half)) / tall
        if (panel > 0f) {
            if (panel != panelWide) shapePanel(panel)
            for (side in intArrayOf(1, -1)) canvas.withSave {
                translate(centre + side * half, mid)
                scale(side * tall, tall)
                drawPanel(this, ink, paper)
            }
        }

        // The cartouche: the page's own colour, so the title reads as on the page
        cartouche.cartouche(centre, mid, half, tall * CARTOUCHE, box)
        fill.color = paper
        canvas.drawPath(cartouche, fill)
        line.strokeWidth = edge
        canvas.drawPath(cartouche, line)
    }

    // Outlines in units of the band's height, as the canvas is scaled to it
    private fun drawPanel(canvas: Canvas, ink: Int, paper: Int) {
        line.color = ink
        line.strokeWidth = RIBBON_EDGE
        canvas.drawPath(ribbons, line)
        line.color = paper
        line.strokeWidth = RIBBON
        canvas.drawPath(ribbons, line)
        fill.color = paper
        canvas.drawPath(shapes, fill)
        line.color = ink
        line.strokeWidth = OUTLINE
        canvas.drawPath(shapes, line)
        fill.color = ink
        canvas.drawPath(dots, fill)
    }

    private fun shapePanel(wide: Float) {
        panelWide = wide
        shapes.reset()
        ribbons.reset()
        dots.reset()
        val cx = wide * 0.5f
        val a = min(0.78f, wide * 0.24f)

        // The medallion, edged twice, with a lotus inside pointing outward
        almond(cx, a, 0.36f)
        almond(cx, a * 0.84f, 0.29f)
        val base = cx - a * 0.42f
        petal(base, 0f, cx + a * 0.5f, 0f, 0.12f)
        for (s in intArrayOf(-1, 1)) {
            petal(base, 0f, cx + a * 0.18f, s * 0.19f, 0.075f)
            petal(base, 0f, cx - a * 0.05f, s * 0.24f, 0.055f)
        }
        dots.addCircle(base - 0.02f, 0f, 0.045f, Path.Direction.CW)

        // Scrolls out of both tips, above and below, each with a leaf and ending in a curl round a bud
        for ((tip, end, way) in listOf(Triple(cx + a, wide - 0.06f, 1f), Triple(cx - a, 0.06f, -1f))) {
            val span = end - tip
            for (s in intArrayOf(-1, 1)) {
                val c1x = tip + span * 0.25f; val c1y = s * 0.36f
                val c2x = tip + span * 0.75f; val c2y = s * 0.4f
                val ex = tip + span * 0.92f; val ey = s * 0.16f
                ribbons.moveTo(tip, 0f)
                ribbons.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
                val r = 0.11f
                val ox = ex - way * r
                val start = if (way > 0) 0f else PI.toFloat()
                val turn = -s * way
                for (i in 1..CURL_STEPS) {
                    val t = i / CURL_STEPS.toFloat()
                    val angle = start + turn * t * CURL_TURNS * 2f * PI.toFloat()
                    val radius = r * (1f - 0.75f * t)
                    ribbons.lineTo(ox + radius * cos(angle), ey + radius * sin(angle))
                }
                dots.addCircle(ox, ey, 0.025f, Path.Direction.CW)
                // The leaf sits a third of the way along the scroll
                val lx = cubicAt(tip, c1x, c2x, ex, LEAF_AT)
                val ly = cubicAt(0f, c1y, c2y, ey, LEAF_AT)
                petal(lx, ly, lx + way * 0.16f, ly - s * 0.13f, 0.045f)
            }
        }
    }

    // A pointed oval from (cx - a, 0) to (cx + a, 0), b high either side
    private fun almond(cx: Float, a: Float, b: Float) {
        shapes.moveTo(cx - a, 0f)
        shapes.cubicTo(cx - a * 0.45f, -b * 1.35f, cx + a * 0.45f, -b * 1.35f, cx + a, 0f)
        shapes.cubicTo(cx + a * 0.45f, b * 1.35f, cx - a * 0.45f, b * 1.35f, cx - a, 0f)
        shapes.close()
    }

    // A teardrop from (bx, by) to (tx, ty), [width] across at its fullest
    private fun petal(bx: Float, by: Float, tx: Float, ty: Float, width: Float) {
        val dx = tx - bx
        val dy = ty - by
        val length = hypot(dx, dy)
        val nx = -dy / length * width
        val ny = dx / length * width
        shapes.moveTo(bx, by)
        shapes.cubicTo(bx + dx * 0.3f + nx, by + dy * 0.3f + ny, bx + dx * 0.85f + nx * 0.5f, by + dy * 0.85f + ny * 0.5f, tx, ty)
        shapes.cubicTo(bx + dx * 0.85f - nx * 0.5f, by + dy * 0.85f - ny * 0.5f, bx + dx * 0.3f - nx, by + dy * 0.3f - ny, bx, by)
        shapes.close()
    }

    private fun cubicAt(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }

    private companion object {
        // Shares of the band's height
        const val FRAME = 0.028f
        const val CARTOUCHE = 0.86f
        // Of the cartouche's height, what a title may fill
        const val CLEAR = 0.86f
        const val CARTOUCHE_PAD = 0.55f
        const val RIBBON = 0.035f
        const val RIBBON_EDGE = 0.06f
        const val OUTLINE = 0.0154f
        // The strip's tint, out of 255
        const val GROUND = 72
        // How a scroll curls at its end, and where its leaf grows
        const val CURL_STEPS = 40
        const val CURL_TURNS = 0.85f
        const val LEAF_AT = 0.33f
    }
}

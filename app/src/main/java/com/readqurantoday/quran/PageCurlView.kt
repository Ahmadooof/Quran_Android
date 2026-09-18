package com.readqurantoday.quran

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Play Books style turn: the far edge stays frozen, the page humps near it and lifts toward the eye as it flips. */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var over: Bitmap? = null
    private var under: Bitmap? = null

    // Where the page image sits; the sheet itself is the whole view, camera area included
    private val page = RectF()

    // Next page flips over the right edge (RTL), previous over the left
    private var next = true

    private var holdX = 0f
    private var fx = 0f

    // Where the finger is; the sheet eases toward it so it feels like paper
    private var tx = 0f
    private var lastFrame = 0L
    private val follow = Runnable { step() }

    private var settling: ValueAnimator? = null

    val turning get() = over != null

    private val picture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paper = Paint()
    private val shade = Paint()
    private val shadeAt = Matrix()
    private var paperTex: Bitmap? = null

    private var travelPx = 0f

    // Shape from the frozen edge to the pulled edge, rebuilt as the drag grows: distance from the frozen edge, height, arc length
    private val shapeX = FloatArray(2 * SAMPLES + 1)
    private val shapeZ = FloatArray(2 * SAMPLES + 1)
    private val shapeLength = FloatArray(2 * SAMPLES + 1)
    private var shapeMoved = -1f

    private val sheetVerts = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val pageVerts = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val colors = IntArray((COLS + 1) * (ROWS + 1))

    // Result of place(): screen x, y and brightness
    private var outX = 0f
    private var outY = 0f
    private var outLight = 1f

    init {
        isClickable = false
        isFocusable = false
    }

    fun start(current: Bitmap, target: Bitmap, at: RectF, toNext: Boolean, x: Float, y: Float) {
        settling?.cancel()
        over = current
        under = target
        page.set(at)
        next = toNext
        holdX = x + at.left
        fx = holdX
        tx = fx
        val colour = Settings.paperColor(context) or 0xFF000000.toInt()
        paper.color = colour
        paperTex = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        shapeMoved = -1f
        travelPx = measureTravel()
        drag(x, y)
    }

    fun drag(x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        if (!turning) return
        val dir = if (next) 1f else -1f
        tx = holdX + dir * (dir * (x + page.left - holdX)).coerceIn(0f, travel())
        if (settling == null && lastFrame == 0L) {
            lastFrame = System.nanoTime()
            postOnAnimation(follow)
        }
    }

    // Frame-rate independent ease toward the finger
    private fun step() {
        if (!turning || settling != null) { lastFrame = 0L; return }
        val now = System.nanoTime()
        val dt = (now - lastFrame) / 1e9
        lastFrame = now
        fx = tx + (fx - tx) * Math.exp(-dt * 1000.0 / FOLLOW_MS).toFloat()
        invalidate()
        if (abs(fx - tx) < 0.5f) { fx = tx; lastFrame = 0L } else postOnAnimation(follow)
    }

    fun progress(): Float = abs(tx - holdX) / width

    private fun travel() = travelPx

    // How far the pulled edge must travel until it is drawn past the frozen screen edge
    private fun measureTravel(): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        var moved = w * 0.3f
        val most = w * (FLIP_START + FLIP_LENGTH)
        while (moved < most) {
            place(0f, h / 2f, w, h, 1f, moved)
            if (outX >= w + 1f) return moved
            moved += w * 0.01f
        }
        return most
    }

    // done() runs with the turn still drawn so the pager can catch up without a gap
    fun settle(complete: Boolean, done: (Boolean) -> Unit) {
        if (!turning) return
        removeCallbacks(follow)
        lastFrame = 0L
        val toX = if (complete) holdX + (if (next) 1f else -1f) * travel() else holdX
        val fromX = fx
        val remaining = abs(toX - fromX) / travel()
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SETTLE_MS * remaining.coerceIn(0.5f, 1f)).toLong()
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener {
                fx = fromX + (toX - fromX) * (it.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settling = null
                    done(complete)
                }
            })
            start()
        }
    }

    // Jump a settling turn to its end so a fast next drag can start; false if it is still held
    fun finish(): Boolean {
        val running = settling ?: return !turning
        running.end()
        return true
    }

    fun clear() {
        removeCallbacks(follow)
        lastFrame = 0L
        settling?.cancel()
        settling = null
        over = null
        under = null
        paperTex = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val top = over ?: return
        val bottom = under ?: return
        val tex = paperTex ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, paper)
        canvas.drawBitmap(bottom, page.left, page.top, picture)

        val moved = abs(fx - holdX)
        if (moved <= 0f) {
            canvas.drawBitmap(top, page.left, page.top, picture)
            return
        }

        val dir = if (next) 1f else -1f

        // Shadow on the page beneath, starting under the free edge as drawn
        place(0f, 0f, w, h, dir, moved)
        val edge = outX
        val reach = SHADOW_DP * resources.displayMetrics.density * minOf(1f, moved / w * 3f)
        // One gradient, moved by a matrix: a new shader every frame allocates through the whole turn
        if (shade.shader == null) {
            shade.shader = LinearGradient(0f, 0f, 1f, 0f, SHADOW_COLOR, 0, Shader.TileMode.CLAMP)
        }
        shadeAt.setScale(-dir * reach, 1f)
        shadeAt.postTranslate(edge, 0f)
        shade.shader.setLocalMatrix(shadeAt)
        canvas.drawRect(minOf(edge, edge - dir * reach), 0f, maxOf(edge, edge - dir * reach), h, shade)

        mesh(sheetVerts, 0f, 0f, w, h, w, h, dir, moved)
        canvas.drawBitmapMesh(tex, COLS, ROWS, sheetVerts, 0, colors, 0, picture)
        mesh(pageVerts, page.left, page.top, page.width(), page.height(), w, h, dir, moved)
        canvas.drawBitmapMesh(top, COLS, ROWS, pageVerts, 0, colors, 0, picture)
    }

    private fun mesh(
        into: FloatArray, rx: Float, ry: Float, rw: Float, rh: Float,
        w: Float, h: Float, dir: Float, moved: Float
    ) {
        val base = if (dir > 0f) 0f else w
        var i = 0
        for (row in 0..ROWS) {
            val py = ry + rh * row / ROWS
            for (col in 0..COLS) {
                val px = rx + rw * col / COLS
                place(dir * (px - base), py, w, h, dir, moved)
                into[i * 2] = outX
                into[i * 2 + 1] = outY
                val c = (255 * outLight).toInt().coerceIn(0, 255)
                colors[i] = 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c
                i++
            }
        }
    }

    // Hump height that makes the curve from the frozen edge to the pulled edge exactly one page long
    private fun buildShape(w: Float, moved: Float) {
        if (moved == shapeMoved) return
        shapeMoved = moved
        var lo = 0f
        var hi = w
        repeat(30) {
            val mid = (lo + hi) / 2f
            if (trace(w, moved, mid) < w) lo = mid else hi = mid
        }
        trace(w, moved, hi)
    }

    // Frozen edge up to the hump, then easing down to the pulled edge; returns the length
    private fun trace(w: Float, moved: Float, hump: Float): Float {
        val span = w - moved
        val rise = minOf(w * ROLL_PART, span * 0.9f)
        val rest = span - rise
        var total = 0f
        for (i in 0..2 * SAMPLES) {
            val x: Float
            val z: Float
            if (i <= SAMPLES) {
                val u = i.toFloat() / SAMPLES
                x = rise * u
                z = hump * sin(u * PI.toFloat() / 2f)
            } else {
                val v = (i - SAMPLES).toFloat() / SAMPLES
                x = rise + rest * v
                // Bow along the rest so it is not a flat board; positive bulges toward the eye
                z = hump * (1f - v * v) + rest * REST_BEND * 0.25f * sin(v * PI.toFloat())
            }
            if (i > 0) total += kotlin.math.hypot(x - shapeX[i - 1], z - shapeZ[i - 1])
            shapeX[i] = x
            shapeZ[i] = z
            shapeLength[i] = total
        }
        return total
    }

    // Page point [s] from the pulled edge: the curve forms first, then the whole shape tips over the frozen edge
    private fun place(s: Float, py: Float, w: Float, h: Float, dir: Float, moved: Float) {
        val curved = minOf(moved, w * FLIP_START)
        buildShape(w, curved)
        val q = w - s
        var i = 1
        val last = 2 * SAMPLES
        while (i < last && shapeLength[i] < q) i++
        val span = shapeLength[i] - shapeLength[i - 1]
        val r = if (span > 0f) ((q - shapeLength[i - 1]) / span).coerceIn(0f, 1f) else 0f
        val fromEdge = shapeX[i - 1] + (shapeX[i] - shapeX[i - 1]) * r
        val flatZ = shapeZ[i - 1] + (shapeZ[i] - shapeZ[i - 1]) * r
        // Tip toward the eye around the frozen edge: the part near it squeezes away, the rest rises and grows
        val tip = ((moved - curved) / (w * FLIP_LENGTH)).coerceIn(0f, 1f) * PI.toFloat() / 2f
        val fromEdgeTipped = fromEdge * cos(tip) - flatZ * sin(tip)
        val z = fromEdge * sin(tip) + flatZ * cos(tip)
        outLight = if (i <= SAMPLES) 1f - ROLL_SHADE * (1f - (i - 1 + r) / SAMPLES) else 1f
        val spine = if (dir > 0f) w else 0f
        val x3 = spine - dir * fromEdgeTipped
        val eye = w * EYE_DISTANCE
        val k = eye / (eye - minOf(z, eye * 0.9f))
        outX = w / 2f + (x3 - w / 2f) * k
        outY = h / 2f + (py - h / 2f) * k
    }

    companion object {
        // Share of the page width dragged past which a released turn completes
        const val PAST = 0.3f

        private const val ROLL_PART = 0.2f
        private const val REST_BEND = 0.3f
        private const val FLIP_START = 0.15f
        private const val FLIP_LENGTH = 0.6f
        private const val ROLL_SHADE = 0.15f
        private const val EYE_DISTANCE = 4f
        private const val SETTLE_MS = 700L
        private const val FOLLOW_MS = 90.0
        private const val SHADOW_DP = 36f
        private const val SHADOW_COLOR = 96 shl 24
        private const val SAMPLES = 80
        private const val COLS = 40
        private const val ROWS = 24
    }
}

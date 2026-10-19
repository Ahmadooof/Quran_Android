package com.readqurantoday.quran

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withRotation
import kotlin.random.Random

/** A short burst of confetti over [this]; with the phone's animations off, nothing is shown. */
fun ViewGroup.celebrate() {
    if (!motionOn(context)) return
    val burst = Confetti(context)
    addView(burst, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    burst.play { removeView(burst) }
}

private class Confetti(context: Context) : View(context) {

    private class Bit(val x: Float, val drift: Float, val fall: Float, val spin: Float, val size: Float, val colour: Int, val round: Boolean)

    private val d = resources.displayMetrics.density
    private val rnd = Random(System.nanoTime())
    // The app's own colours and gold, so it is a celebration of this page and not a sticker on it
    private val colours = intArrayOf(
        context.getColor(R.color.accent), context.getColor(R.color.ink_lit),
        context.getColor(R.color.ayah_mark), context.getColor(R.color.confetti_gold)
    )
    private val bits = List(PIECES) {
        Bit(
            x = rnd.nextFloat(), drift = (rnd.nextFloat() - 0.5f) * 0.3f,
            fall = 0.7f + rnd.nextFloat() * 0.6f, spin = (rnd.nextFloat() - 0.5f) * 720f,
            size = (5 + rnd.nextFloat() * 5) * d, colour = colours[rnd.nextInt(colours.size)], round = rnd.nextBoolean()
        )
    }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    fun play(done: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHOW_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            doOnEnd(done)
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Everything fades over the last fifth, so it leaves rather than vanishes
        val fade = ((1f - t) / 0.2f).coerceIn(0f, 1f)
        for (b in bits) {
            val y = -b.size + (height + 2 * b.size) * (t * b.fall)
            val x = width * (b.x + b.drift * t)
            pen.color = b.colour
            pen.alpha = (255 * fade).toInt()
            canvas.withRotation(b.spin * t, x, y) {
                if (b.round) drawCircle(x, y, b.size / 2f, pen)
                else drawRect(x - b.size / 2f, y - b.size / 4f, x + b.size / 2f, y + b.size / 4f, pen)
            }
        }
    }

    private companion object {
        const val PIECES = 70
        const val SHOW_MS = 2400L
    }
}

private fun ValueAnimator.doOnEnd(then: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = then()
})

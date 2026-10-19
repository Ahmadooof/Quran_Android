package com.readqurantoday.quran

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat

/** A week of reading as bars, each day against its own goal; a badge marks a goal reached. */
class DayBars(context: Context) : View(context) {

    private var pages: List<Int> = emptyList()
    private var goals: List<Int> = emptyList()
    private var dayName: (Int) -> String = { "" }

    /** Hears a tapped day, by its place in the week, oldest first. */
    var onDay: ((Int) -> Unit)? = null

    /** The day whose details are open, marked by a dot under its name; -1 for none. */
    var picked = -1
        set(value) {
            field = value
            invalidate()
        }

    private val dot = resources.getDimension(R.dimen.chart_dot)

    // 0 to 1 across the whole rise; each bar takes its own slice of it, oldest first
    private var grown = 1f

    private val d = resources.displayMetrics.density
    private val face = ResourcesCompat.getFont(context, R.font.cairo)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent_soft) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.line) }
    // A goal reached: a light tick on a filled accent disc
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val tick = AppCompatResources.getDrawable(context, R.drawable.ic_check)?.mutate()?.apply {
        setTint(context.getColor(R.color.on_dark))
    }
    private val tickSize = resources.getDimensionPixelSize(R.dimen.chart_tick)
    // Pages over each bar read first, the day's name quieter
    private val count = text(R.color.text, R.dimen.chart_count, bold = true)
    private val label = text(R.color.text_mute, R.dimen.chart_day, bold = false)
    private val todayLabel = text(R.color.text, R.dimen.chart_day, bold = true)
    private val gap = resources.getDimension(R.dimen.chart_gap)
    private val barWide = resources.getDimension(R.dimen.chart_bar)
    private val corner = resources.getDimension(R.dimen.chart_bar_corner)

    private fun text(colour: Int, size: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(colour)
        textSize = resources.getDimension(size)
        typeface = Typeface.create(face, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** [pages] and [goals] oldest first; [dayName] names a day by days ago; [grow] raises the bars in turn. */
    fun show(pages: List<Int>, goals: List<Int>, dayName: (Int) -> String, grow: Boolean = false) {
        this.pages = pages
        this.goals = goals
        this.dayName = dayName
        grown = if (grow) 0f else 1f
        invalidate()
        if (!grow) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                grown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // A bar's own progress: it starts when the wave reaches it and eases in over a few bars' time
    private fun rise(i: Int): Float {
        val local = ((grown * (pages.size + WAVE) - i) / WAVE).coerceIn(0f, 1f)
        return 1f - (1f - local) * (1f - local)
    }

    // Room for a tick and the pages over the tallest bar, and the day's name beneath the baseline
    private fun over() = tickSize + count.textSize + 2 * gap
    private fun under() = label.textSize + 3 * gap + 2 * dot

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val tall = resources.getDimensionPixelSize(R.dimen.chart_height) + paddingTop + paddingBottom + (over() + under()).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), resolveSize(tall, heightSpec))
    }

    // A tap names the day under it; a drag is left to the scroll around the chart
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val heard = onDay ?: return super.onTouchEvent(e)
        if (pages.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val left = paddingLeft.toFloat()
                val slot = (width - paddingRight - left) / pages.size
                val at = ((e.x - left) / slot).toInt().coerceIn(0, pages.size - 1)
                heard(if (layoutDirection == LAYOUT_DIRECTION_RTL) pages.size - 1 - at else at)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        if (pages.isEmpty()) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val top = maxOf(pages.max(), goals.maxOrNull() ?: 0, 1)
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val roof = paddingTop + over()
        val base = height - paddingBottom - under()
        val room = base - roof

        val slot = (right - left) / pages.size
        // Bars of one width however wide the screen; softly rounded, so a day of few pages is a low bar, not a dot
        val wide = minOf(barWide, slot * 0.62f)
        val round = minOf(corner, wide / 2f)
        pages.forEachIndexed { i, n ->
            // Oldest first, so the newest day sits where the line of reading ends
            val centre = if (rtl) right - (i + 0.5f) * slot else left + (i + 0.5f) * slot
            val l = centre - wide / 2f
            val r = centre + wide / 2f
            val goal = goals.getOrElse(i) { 0 }
            val goalTop = base - room * goal / top
            if (goal > 0) canvas.drawRoundRect(l, goalTop, r, base, round, round, track)
            val tall = if (n > 0) maxOf(room * n / top, round * 2f) * rise(i) else 0f
            if (n > 0) canvas.drawRoundRect(l, base - tall, r, base, round, round, bar)
            // A day with neither reading nor goal keeps a dot, so it still holds its place in the week
            else if (goal == 0) canvas.drawRoundRect(l, base - round * 2f, r, base, round, round, empty)
            if (n > 0 && rise(i) == 1f) {
                val numberY = minOf(base - tall, goalTop) - gap
                canvas.drawText(figures(n, resources), centre, numberY, count)
                // A day that reached its goal says so over its number
                if (goal > 0 && n >= goal) {
                    val half = tickSize / 2f
                    val cy = numberY - count.textSize - gap / 2f - half
                    canvas.drawCircle(centre, cy, half, badge)
                    val inset = half * TICK_INSET
                    tick?.setBounds((centre - half + inset).toInt(), (cy - half + inset).toInt(), (centre + half - inset).toInt(), (cy + half - inset).toInt())
                    tick?.draw(canvas)
                }
            }
        }

        // Every day's name fits under its own bar; on a narrow screen each shrinks to fit, all alike
        val last = pages.size - 1
        val names = pages.indices.map { dayName(last - it) }
        val dayFull = label.textSize
        val dayFit = minOf(1f, (slot - gap / 2f) / names.maxOf { todayLabel.measureText(it) })
        label.textSize = dayFull * dayFit
        todayLabel.textSize = dayFull * dayFit
        val nameY = base + gap + label.textSize * 0.9f
        pages.indices.forEach { i ->
            val centre = if (rtl) right - (i + 0.5f) * slot else left + (i + 0.5f) * slot
            canvas.drawText(names[i], centre, nameY, if (i == last || i == picked) todayLabel else label)
            if (i == picked) canvas.drawCircle(centre, nameY + gap + dot, dot, bar)
        }
        label.textSize = dayFull
        todayLabel.textSize = dayFull
    }

    private companion object {
        const val GROW_MS = 900L
        const val WAVE = 3f
        // How far in from the badge's edge its tick is drawn
        const val TICK_INSET = 0.3f
    }
}

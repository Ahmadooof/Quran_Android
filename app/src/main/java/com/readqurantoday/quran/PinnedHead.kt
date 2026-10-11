package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * A heading pinned over a long list, naming the part the top row belongs to; the next part's heading pushes it
 * up as it arrives. [part] gives a row's part, and [said] how the heading writes it. The list makes room for it
 * at the top, so the first row is never under it.
 */
fun RecyclerView.addPinnedHead(part: (Int) -> Int, said: (Int) -> String) {
    val head = PinnedHead(this, part, said)
    setPadding(paddingLeft, paddingTop + head.tall.toInt(), paddingRight, paddingBottom)
    addItemDecoration(head)
}

private class PinnedHead(
    private val list: RecyclerView,
    private val part: (Int) -> Int,
    private val said: (Int) -> String
) : RecyclerView.ItemDecoration() {

    private val res = list.resources
    val tall = res.getDimension(R.dimen.pinned_head_height)

    // A pill floating at the top, as a chat's day is marked, solid so the rows never show through it
    private val pillTall = res.getDimension(R.dimen.pinned_pill_height)
    private val pillPad = res.getDimension(R.dimen.pinned_pill_pad)
    private val pill = RectF()
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = list.context.getColor(R.color.reader_chrome) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = list.context.getColor(R.color.line)
        style = Paint.Style.STROKE
        strokeWidth = res.displayMetrics.density
    }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = list.context.getColor(R.color.text)
        textSize = res.getDimension(R.dimen.row_meta)
        typeface = Typeface.create(ResourcesCompat.getFont(list.context, R.font.cairo), Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // The heading stands over the rows; the row just under its foot is the one it names
        val foot = list.paddingTop
        var now = -1
        var push = 0f
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val at = list.getChildAdapterPosition(child)
            if (at == RecyclerView.NO_POSITION) continue
            if (now < 0) {
                if (child.bottom > foot) now = part(at)
                continue
            }
            // The next part's first row, coming up under the heading, pushes it out ahead of it
            if (part(at) != now) {
                push = minOf(0f, child.top - foot - tall)
                break
            }
        }
        if (now < 0) return

        val text = said(now)
        val centre = (foot - tall + push) + tall / 2f
        val half = words.measureText(text) / 2f + pillPad
        pill.set(list.width / 2f - half, centre - pillTall / 2f, list.width / 2f + half, centre + pillTall / 2f)
        c.drawRoundRect(pill, pillTall / 2f, pillTall / 2f, ground)
        c.drawRoundRect(pill, pillTall / 2f, pillTall / 2f, edge)
        c.drawText(text, pill.centerX(), centre - (words.descent() + words.ascent()) / 2f, words)
    }
}

package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A drag handle in its own edge column, always shown on long lists; [label] names the top row while held. */
fun RecyclerView.addHandle(label: ((Int) -> String)? = null) {
    val handle = ListHandle(this, label)
    setPaddingRelative(paddingStart, paddingTop, paddingEnd + resources.getDimensionPixelSize(R.dimen.scroll_column), paddingBottom)
    addItemDecoration(handle)
    addOnItemTouchListener(handle)
}

private class ListHandle(
    private val list: RecyclerView,
    private val label: ((Int) -> String)?
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    private val res = list.resources
    private val wide = res.getDimension(R.dimen.scroll_handle)
    // Held, it widens, so the thumb feels it has it
    private val heldWide = res.getDimension(R.dimen.scroll_handle_held)
    private val tall = res.getDimension(R.dimen.scroll_handle_tall)
    private val inset = res.getDimension(R.dimen.scroll_handle_inset)
    // The column is the handle's own: all of it takes the thumb, none of it is a row
    private val column = res.getDimension(R.dimen.scroll_column)

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rest = list.context.getColor(R.color.scroll_thumb)
    private val held = list.context.getColor(R.color.accent)
    private val box = RectF()
    private val track = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = list.context.getColor(R.color.line) }
    private val trackWide = res.getDimension(R.dimen.scroll_track)
    private val keepOut = Rect()
    private val keptOut = Rect()

    // The bubble: the accent filled, its words light on it
    private val bubbleGap = res.getDimension(R.dimen.scroll_bubble_gap)
    private val bubblePadX = res.getDimension(R.dimen.scroll_bubble_pad_x)
    private val bubblePadY = res.getDimension(R.dimen.scroll_bubble_pad_y)
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = list.context.getColor(R.color.accent) }
    private val bubbleWords = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = list.context.getColor(R.color.on_dark)
        textSize = res.getDimension(R.dimen.scroll_bubble_text)
        typeface = Typeface.create(ResourcesCompat.getFont(list.context, R.font.cairo), Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bubble = RectF()

    private var dragging = false

    // Only worth a handle when the list runs well past one screen
    private fun long() = list.computeVerticalScrollRange() > list.height * 2

    private fun place() {
        val range = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent()
        val share = if (range > 0) list.computeVerticalScrollOffset().toFloat() / range else 0f
        val top = list.paddingTop + inset
        val travel = list.height - list.paddingBottom - inset - top - tall
        val y = top + travel * share.coerceIn(0f, 1f)
        // Centred in its column, on the side Android gives a scrollbar: left in Arabic, right in English
        val mid = edgeStart() + column / 2f
        val w = if (dragging) heldWide else wide
        box.set(mid - w / 2f, y, mid + w / 2f, y + tall)
        track.set(mid - trackWide / 2f, top, mid + trackWide / 2f, top + travel + tall)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!long()) return
        place()
        c.drawRoundRect(track, trackWide / 2f, trackWide / 2f, trackPaint)
        pill.color = if (dragging) held else rest
        c.drawRoundRect(box, box.width() / 2f, box.width() / 2f, pill)
        if (dragging) drawBubble(c)
        // The system leaves this strip to the handle, rather than taking it as a back swipe
        keepOut.set(edgeStart(), box.top.toInt(), edgeStart() + column.toInt(), box.bottom.toInt())
        // Told again only when it moves: each telling makes the window draw once more
        if (keepOut != keptOut) {
            keptOut.set(keepOut)
            ViewCompat.setSystemGestureExclusionRects(list, listOf(Rect(keepOut)))
        }
    }

    // Beside the handle, level with it, far enough off that the thumb holding it does not cover it
    private fun drawBubble(c: Canvas) {
        val named = label ?: return
        val at = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
        if (at == RecyclerView.NO_POSITION) return
        val words = named(at)
        val w = bubbleWords.measureText(words) + 2 * bubblePadX
        val h = bubbleWords.textSize + 2 * bubblePadY
        val rtl = list.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = if (rtl) box.right + bubbleGap else box.left - bubbleGap - w
        val y = (box.centerY() - h / 2f).coerceIn(list.paddingTop.toFloat(), list.height - list.paddingBottom - h)
        bubble.set(x, y, x + w, y + h)
        c.drawRoundRect(bubble, h / 2f, h / 2f, bubbleFill)
        val baseline = bubble.centerY() - (bubbleWords.descent() + bubbleWords.ascent()) / 2f
        c.drawText(words, bubble.centerX(), baseline, bubbleWords)
    }

    private fun onHandle(e: MotionEvent): Boolean {
        if (!long()) return false
        place()
        val from = edgeStart().toFloat()
        return e.x in from..from + column && e.y in box.top - inset..box.bottom + inset
    }

    // Where the column begins: the screen edge on the handle's side
    private fun edgeStart(): Int =
        if (list.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0 else (list.width - column).toInt()

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && onHandle(e)) {
            dragging = true
            // Holding the handle is not a swipe to the next list
            list.parent?.requestDisallowInterceptTouchEvent(true)
            list.invalidate()
            return true
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val top = list.paddingTop + inset + tall / 2f
                val bottom = list.height - list.paddingBottom - inset - tall / 2f
                val share = ((e.y - top) / (bottom - top)).coerceIn(0f, 1f)
                val count = list.adapter?.itemCount ?: return
                (list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset((share * (count - 1)).toInt(), 0)
                list.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                list.invalidate()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
}

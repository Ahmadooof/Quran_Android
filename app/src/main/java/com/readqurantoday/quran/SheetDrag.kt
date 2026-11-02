package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Holds a sheet so it can be slid down to close, as its grip promises. A list inside it scrolls first:
 * the sheet only follows a downward drag that nothing under the finger can scroll.
 */
@SuppressLint("ViewConstructor")
class SheetDrag(context: Context, private val close: () -> Unit) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLING
    private var downX = 0f
    private var downY = 0f
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e)
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (dy > slop && dy > abs(e.rawX - downX) && !scrollsUp(this, e.x, e.y)) return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        when (e.actionMasked) {
            // A touch on the sheet's bare ground is held, so it can become a drag
            MotionEvent.ACTION_DOWN -> start(e)
            MotionEvent.ACTION_MOVE -> translationY = (e.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val speed = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                tracker?.recycle()
                tracker = null
                if (e.actionMasked == MotionEvent.ACTION_UP && (translationY > height * PAST || speed > fling)) {
                    animate().translationY(height.toFloat()).setDuration(SETTLE_MS).withEndAction(close).start()
                } else {
                    animate().translationY(0f).setDuration(SETTLE_MS).start()
                }
            }
        }
        return true
    }

    private fun start(e: MotionEvent) {
        downX = e.rawX
        downY = e.rawY
        tracker?.recycle()
        tracker = VelocityTracker.obtain().apply { addMovement(e) }
    }

    // Whether something under ([x], [y]) in [view] can still scroll back up, and so should have the drag
    private fun scrollsUp(view: View, x: Float, y: Float): Boolean {
        if (view !== this && view.canScrollVertically(-1)) return true
        if (view !is ViewGroup) return false
        for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val cx = x + view.scrollX - child.left - child.translationX
            val cy = y + view.scrollY - child.top - child.translationY
            if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height) return scrollsUp(child, cx, cy)
        }
        return false
    }

    private companion object {
        // Past this share of its height, or flung faster than this many times the least fling, the sheet closes
        const val PAST = 0.3f
        const val FLING = 4
        const val SETTLE_MS = 180L
    }
}

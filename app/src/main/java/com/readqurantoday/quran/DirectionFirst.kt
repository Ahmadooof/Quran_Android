package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Holds one page of a sideways pager. The pager is kept off a touch until the drag shows its way: mostly up or down
 * stays the page's scroll, mostly sideways goes to the pager. Without it a flick's sideways first pixels let the pager
 * take the touch, cancelling the scroll without turning the page.
 */
// Made only in code, around the page it is handed
@SuppressLint("ViewConstructor")
class DirectionFirst(context: Context, page: View) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var fromX = 0f
    private var fromY = 0f
    private var decided = false

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fromX = e.x
                fromY = e.y
                decided = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!decided) {
                val across = abs(e.x - fromX)
                val down = abs(e.y - fromY)
                if (across > slop || down > slop) {
                    decided = true
                    // Sideways more than up or down: the pager may have it
                    if (across > down) parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }
}

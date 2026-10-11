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
 * Holds one page of a sideways pager whose content scrolls up and down. A drag that begins mostly up or down stays
 * the page's to scroll; only a clearly sideways one is let through to turn the page, so a slanted scroll never
 * slips into the next tab.
 */
// Made only in code, around a page it is handed; never from a layout
@SuppressLint("ViewConstructor")
class UpDownFirst(context: Context, page: View) : FrameLayout(context) {

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
                // The pager waits until the drag shows which way it means to go
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!decided) {
                val across = abs(e.x - fromX)
                val down = abs(e.y - fromY)
                // Up or down wins as soon as it shows; sideways must go further, as a thumb's first pixels wander
                val sideways = across > down * SIDEWAYS
                if (down > slop && !sideways) {
                    decided = true
                } else if (across > slop * SIDEWAYS_SLOPS && sideways) {
                    decided = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    private companion object {
        // How much more sideways than up or down a drag must be before it turns the page
        const val SIDEWAYS = 1.5f
        // How many touch slops sideways before the page turns
        const val SIDEWAYS_SLOPS = 2
    }
}

package com.readqurantoday.quran

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * The switch over a pager, as the index, places and achievements have it: tap a segment or swipe, and the segment
 * of the page in view is marked.
 */
class SwipeTabs(
    private val pager: ViewPager2,
    strip: View,
    names: IntArray,
    pages: RecyclerView.Adapter<*>
) {

    // As many segments as names; the strip's spare ones are hidden
    private val segs = SEGMENTS.take(names.size).map { strip.findViewById<TextView>(it) }

    /* The switch's own face, read before the first bolding, so the app font survives it. */
    private val face = segs[0].typeface

    private var on = pager.currentItem

    init {
        SEGMENTS.drop(names.size).forEach { strip.findViewById<View>(it).visibility = View.GONE }
        segs.forEachIndexed { i, seg ->
            seg.setText(names[i])
            seg.setOnClickListener { pager.currentItem = i }
        }
        pager.adapter = pages
        // Every page kept laid out, so a swipe back finds one where it was left
        pager.offscreenPageLimit = names.size - 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // The pager names its first page on first layout too; only a real move is heard
                if (position == on) return
                on = position
                mark(position)
            }
        })
        mark(pager.currentItem)
    }

    val current: Int get() = pager.currentItem

    private fun mark(on: Int) = segs.forEachIndexed { i, seg -> seg.markSegment(i == on, face) }

    private companion object {
        val SEGMENTS = intArrayOf(R.id.seg_1, R.id.seg_2, R.id.seg_3, R.id.seg_4)
    }
}

/** A segment of a switch marked chosen or not; [face] is the switch's own, read before any bolding. */
fun TextView.markSegment(on: Boolean, face: Typeface?) {
    setBackgroundResource(if (on) R.drawable.seg_on else R.drawable.row_flat)
    setTextColor(context.getColor(if (on) R.color.text else R.color.text_mute))
    // Built from the theme's own face: defaultFromStyle would put the system font here
    typeface = Typeface.create(face, if (on) Typeface.BOLD else Typeface.NORMAL)
    isSelected = on
}

/** Pages made beforehand, one view each, for a pager whose pages are not lists of rows. */
class ViewPages(private val views: List<View>) : RecyclerView.Adapter<ViewPages.Page>() {

    class Page(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = views.size

    // One view type a page, so each made view is handed out once and only once
    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Page(views[viewType].apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    })

    override fun onBindViewHolder(holder: Page, position: Int) = Unit
}

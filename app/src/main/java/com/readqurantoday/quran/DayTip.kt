package com.readqurantoday.quran

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** A small card beside a held point of [anchor]: shown while a finger is down there, moved and refilled as it slides. */
class DayTip(private val anchor: View) {

    private val blow = LayoutInflater.from(anchor.context)
    private val res = anchor.resources
    private val card = blow.inflate(R.layout.part_day_tip, null)
    private val title = card.findViewById<TextView>(R.id.tip_title)
    private val rows = card.findViewById<LinearLayout>(R.id.tip_rows)
    private val scroll = card.findViewById<View>(R.id.tip_scroll)
    private val wide = res.getDimensionPixelSize(R.dimen.day_tip_wide)
    private val most = res.getDimensionPixelSize(R.dimen.day_tip_tall)
    private val gap = res.getDimensionPixelSize(R.dimen.day_tip_gap)
    private val margin = res.getDimensionPixelSize(R.dimen.screen_margin)

    // Never touched itself: the finger stays on the chart
    private val popup = PopupWindow(card, wide, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isTouchable = false
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = res.getDimension(R.dimen.elevation_chrome)
    }

    /** Shows [heading] and each line's label and value beside the point [x] along the chart. */
    fun show(x: Float, heading: String, lines: List<Pair<String, String>>) {
        title.text = heading
        rows.removeAllViews()
        for ((label, value) in lines) rows.addView(blow.inflate(R.layout.item_day_tip, rows, false).apply {
            findViewById<TextView>(R.id.tip_label).text = label
            findViewById<TextView>(R.id.tip_value).apply {
                text = value
                visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        // As tall as its lines, up to a height past which they scroll
        scroll.layoutParams = scroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        card.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        if (card.measuredHeight > most) {
            scroll.layoutParams = scroll.layoutParams.apply { height = scroll.measuredHeight - (card.measuredHeight - most) }
            card.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        }
        val tall = card.measuredHeight

        // Beside the held bar, on the side with more room, so the finger never covers it; level with the chart
        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        val finger = at[0] + x
        val screenW = anchor.rootView.width
        val screenH = anchor.rootView.height
        val left = (if (finger > screenW / 2f) finger - gap - wide else finger + gap).toInt()
            .coerceIn(margin, maxOf(margin, screenW - wide - margin))
        val top = (at[1] + anchor.height / 2 - tall / 2).coerceIn(margin, maxOf(margin, screenH - tall - margin))
        if (popup.isShowing) popup.update(left, top, wide, tall)
        else popup.showAtLocation(anchor, Gravity.NO_GRAVITY, left, top)
    }

    fun hide() = popup.dismiss()
}

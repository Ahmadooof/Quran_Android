package com.readqurantoday.quran

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A small card floating over [anchor] above the point [x] along it: [title], then each line's label and value.
 * A touch anywhere else closes it and still goes where it was meant, so a scroll or another bar works at once.
 */
fun showDayTip(anchor: View, x: Float, title: String, lines: List<Pair<String, String>>, closed: () -> Unit): PopupWindow {
    val blow = LayoutInflater.from(anchor.context)
    val res = anchor.resources
    val card = blow.inflate(R.layout.part_day_tip, null)
    card.findViewById<TextView>(R.id.tip_title).text = title
    val rows = card.findViewById<LinearLayout>(R.id.tip_rows)
    for ((label, value) in lines) rows.addView(blow.inflate(R.layout.item_day_tip, rows, false).apply {
        findViewById<TextView>(R.id.tip_label).text = label
        findViewById<TextView>(R.id.tip_value).apply {
            text = value
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }
    })

    // As tall as its lines, up to a height past which they scroll
    val wide = res.getDimensionPixelSize(R.dimen.day_tip_wide)
    card.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    val most = res.getDimensionPixelSize(R.dimen.day_tip_tall)
    if (card.measuredHeight > most) {
        val scroll = card.findViewById<View>(R.id.tip_scroll)
        scroll.layoutParams = scroll.layoutParams.apply { height = scroll.measuredHeight - (card.measuredHeight - most) }
        card.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    }
    val tall = card.measuredHeight

    val tip = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = res.getDimension(R.dimen.elevation_chrome)
        setOnDismissListener { closed() }
    }
    // Over the chart, centred on the bar but kept on the screen; under it when there is no room above
    val at = IntArray(2)
    anchor.getLocationInWindow(at)
    val margin = res.getDimensionPixelSize(R.dimen.screen_margin)
    val screen = anchor.rootView.width
    val left = (at[0] + x - wide / 2f).toInt().coerceIn(margin, maxOf(margin, screen - wide - margin))
    val top = if (at[1] - tall > margin) at[1] - tall + anchor.paddingTop else at[1] + anchor.height
    tip.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, left, top)
    return tip
}

package com.readqurantoday.quran

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A titled card of rows added to [into]: the title on the pane's ground, the rows
 * together in one card beneath it, a seam between each pair. A [title] of 0 is a
 * card on its own, for rows that need no heading. The settings and the places tab
 * both lay out this way, so both build through here.
 */
fun LayoutInflater.card(into: LinearLayout, title: Int, rows: List<View>) {
    val frame = inflate(R.layout.part_settings_group, into, false)
    val head = frame.findViewById<TextView>(R.id.group_title)
    if (title != 0) {
        head.setText(title)
    } else {
        /* No title, so no title's height: just the gap between cards. Kept whole, an
           empty title stood 26dp of air above the card it was not naming. */
        head.visibility = View.GONE
        frame.setPadding(
            frame.paddingLeft, into.resources.getDimensionPixelSize(R.dimen.group_gap),
            frame.paddingRight, 0
        )
    }
    val body = frame.findViewById<LinearLayout>(R.id.group_rows)
    rows.forEachIndexed { i, row ->
        if (i > 0) body.addView(inflate(R.layout.part_divider, body, false))
        body.addView(row)
    }
    into.addView(frame)
}

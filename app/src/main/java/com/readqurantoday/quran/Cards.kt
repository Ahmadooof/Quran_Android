package com.readqurantoday.quran

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// Titled card of rows with seams between them; title 0 means no heading
fun LayoutInflater.card(into: LinearLayout, title: Int, rows: List<View>) =
    card(into, if (title != 0) context.getString(title) else "", rows)

fun LayoutInflater.card(into: LinearLayout, title: String, rows: List<View>) {
    val frame = inflate(R.layout.part_settings_group, into, false)
    val head = frame.findViewById<TextView>(R.id.group_title)
    if (title.isNotEmpty()) {
        head.text = title
    } else {
        // An empty title would still leave its height as a gap
        head.visibility = View.GONE
        frame.setPadding(
            frame.paddingLeft, into.resources.getDimensionPixelSize(R.dimen.group_gap),
            frame.paddingRight, 0
        )
    }
    val body = frame.findViewById<LinearLayout>(R.id.group_rows)
    // Set here: the XML attribute is read only from Android 12
    body.clipToOutline = true
    rows.forEachIndexed { i, row ->
        if (i > 0) body.addView(inflate(R.layout.part_divider, body, false))
        body.addView(row)
    }
    into.addView(frame)
}

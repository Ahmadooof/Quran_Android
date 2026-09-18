package com.readqurantoday.quran

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/** Say what the play button in a list row is doing; the colours live in part_row_actions. */
fun sayPlayButton(row: View, playing: Boolean, waiting: Boolean) {
    // Hidden rather than gone while waiting, so the spinner lands on the disc it replaces
    row.findViewById<ImageView>(R.id.play).apply {
        setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        imageAlpha = if (waiting) 0 else 255
    }
    row.findViewById<ProgressBar>(R.id.play_wait).visibility =
        if (waiting) View.VISIBLE else View.GONE
    row.findViewById<TextView>(R.id.play_label).setText(if (playing) R.string.stop else R.string.play)
}

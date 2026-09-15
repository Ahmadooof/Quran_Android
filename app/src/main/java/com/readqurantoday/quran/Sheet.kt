package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** One line in a sheet: what it says, an optional second line, and whether it is the current pick. */
data class Choice(val label: String, val note: String = "", val on: Boolean = false)

/**
 * The app's one menu shape: choices on a sheet against the bottom edge, within
 * reach of the thumb that opened it. Nothing floats over the middle of a page.
 */
fun Activity.sheet(title: String, choices: List<Choice>, pick: (Int) -> Unit) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_sheet, null)
    view.findViewById<TextView>(R.id.sheet_title).text = title

    val rows = view.findViewById<LinearLayout>(R.id.sheet_rows)
    choices.forEachIndexed { i, choice ->
        val row = layoutInflater.inflate(R.layout.item_sheet_row, rows, false)
        row.findViewById<TextView>(R.id.sheet_label).text = choice.label

        val note = row.findViewById<TextView>(R.id.sheet_note)
        if (choice.note.isEmpty()) note.visibility = View.GONE else note.text = choice.note

        /* Invisible rather than gone: the labels stay on one column down the sheet. */
        row.findViewById<View>(R.id.sheet_tick).visibility =
            if (choice.on) View.VISIBLE else View.INVISIBLE

        row.setOnClickListener { dialog.dismiss(); pick(i) }
        rows.addView(row)
    }

    raise(dialog, view)
}

/** A sheet with nothing to choose — it only has something to say. */
fun Activity.notice(said: String) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_sheet, null)
    view.findViewById<TextView>(R.id.sheet_title).text = said
    view.setOnClickListener { dialog.dismiss() }
    raise(dialog, view)
}

/* Full width against the bottom edge, and the host's system bars left as they
   were — the reader reads with its bars hidden and a sheet must not blink them back. */
internal fun Activity.raise(dialog: Dialog, view: View) {
    dialog.setContentView(view)

    val bare = ViewCompat.getRootWindowInsets(window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.systemBars()) == false

    dialog.window?.let { pane ->
        pane.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pane.setGravity(Gravity.BOTTOM)
        if (bare) WindowCompat.setDecorFitsSystemWindows(pane, false)
    }

    dialog.show()

    /* Only takes once the sheet's window is up. */
    if (bare) dialog.window?.let { pane ->
        WindowInsetsControllerCompat(pane, pane.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

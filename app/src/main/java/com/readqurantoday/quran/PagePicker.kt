package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val TILES_IN_ROW = 5
// Room left above the sheet, so it never covers the whole screen
private const val MOST_OF_SCREEN = 0.55f

/** Pages as numbered tiles that tick on and off; [done] hears the pages chosen once the reader is finished. */
fun Activity.pickPages(title: String, note: String, pages: List<Int>, chosen: Set<Int>, done: (Set<Int>) -> Unit) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_page_picker, null)
    view.findViewById<TextView>(R.id.picker_title).text = title
    view.findViewById<TextView>(R.id.picker_note).text = note
    val now = chosen.toMutableSet()
    val all = view.findViewById<TextView>(R.id.picker_all)
    val tiles = mutableListOf<Pair<Int, TextView>>()

    fun show() {
        tiles.forEach { (page, tile) -> markChoice(tile, page in now) }
        all.setText(if (now.containsAll(pages)) R.string.pages_none else R.string.pages_all)
    }

    val grid = view.findViewById<LinearLayout>(R.id.picker_tiles)
    pages.chunked(TILES_IN_ROW).forEach { chunk ->
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // A short last row keeps the tiles the size of those above it
        for (i in 0 until TILES_IN_ROW) {
            val tile = layoutInflater.inflate(R.layout.item_page_tile, line, false) as TextView
            val page = chunk.getOrNull(i)
            if (page == null) tile.visibility = View.INVISIBLE else {
                tile.text = figures(page, resources)
                tile.contentDescription = pageNamed(page, resources)
                tile.setOnClickListener {
                    if (!now.remove(page)) now.add(page)
                    show()
                }
                tiles += page to tile
            }
            line.addView(tile)
        }
        grid.addView(line)
    }

    all.setOnClickListener {
        if (now.containsAll(pages)) now.removeAll(pages.toSet()) else now.addAll(pages)
        show()
    }
    view.findViewById<View>(R.id.picker_done).setOnClickListener {
        dialog.dismiss()
        done(now)
    }
    show()

    // Many pages scroll under the buttons, which stay where the thumb is
    val rows = (pages.size + TILES_IN_ROW - 1) / TILES_IN_ROW
    val rowHeight = resources.getDimensionPixelSize(R.dimen.choice_tile) + 2 * resources.getDimensionPixelSize(R.dimen.choice_gap)
    val most = (resources.displayMetrics.heightPixels * MOST_OF_SCREEN).toInt()
    if (rows * rowHeight > most) view.findViewById<ScrollView>(R.id.picker_scroll).layoutParams.height = most

    raise(dialog, view)
}

/** A toggle drawn as chosen (filled) or not (outlined), as the page tiles and the surah buttons both are. */
fun markChoice(button: TextView, on: Boolean) {
    button.setBackgroundResource(if (on) R.drawable.choice_on else R.drawable.choice_off)
    button.setTextColor(button.context.getColor(if (on) R.color.on_dark else R.color.text))
    button.isSelected = on
}

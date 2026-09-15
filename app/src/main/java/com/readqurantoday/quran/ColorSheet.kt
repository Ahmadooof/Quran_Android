package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jaredrummler.android.colorpicker.ColorPickerView

// Quick picks per kind of colour: a good highlight colour is no colour for a page

/** Accents, for the things that should stand out: the lit word, the ayah numbers. */
val ACCENT_PICKS = intArrayOf(
    0xFF1053A8.toInt(), 0xFF128C8C.toInt(), 0xFF1E7A4C.toInt(),
    0xFFD9B95F.toInt(), 0xFFD2691E.toInt(), 0xFFB03030.toInt(), 0xFF6B3FA0.toInt()
)

/** Grounds to read on: whites and warm papers by day, soft blacks by night. */
val PAPER_PICKS = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFFF6EEDC.toInt(), 0xFFEFF4EA.toInt(), 0xFFEEF2F6.toInt(),
    0xFF1A1F25.toInt(), 0xFF121212.toInt(), 0xFF000000.toInt()
)

/** Inks: near-blacks with a little warmth or colour, and light greys for dark paper. */
val INK_PICKS = intArrayOf(
    0xFF000000.toInt(), 0xFF3B2A1A.toInt(), 0xFF1B2A4A.toInt(), 0xFF1F3D2B.toInt(),
    0xFF5A5A5A.toInt(), 0xFFA2A7AD.toInt(), 0xFFE8E6E1.toInt()
)

// Colour picker that restyles a real page line live; saves only on Done
fun Activity.colorSheet(
    title: String,
    look: Context,
    initial: Int,
    fallback: Int,
    picks: IntArray,
    inUse: IntArray,
    trial: (MushafPageView, Int) -> Unit,
    keep: (Int) -> Unit
) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_color_sheet, null)
    view.findViewById<TextView>(R.id.color_title).text = title

    val preview = MushafPageView(look).apply {
        previewMode = true
        show(MushafPageView.PREVIEW_PAGE)
    }
    view.findViewById<FrameLayout>(R.id.color_preview).addView(preview)

    val field = view.findViewById<ColorPickerView>(R.id.color_field)
    val hex = view.findViewById<EditText>(R.id.color_hex)
    /* The default first; a quick pick that is the default already is not offered twice. */
    val offered = intArrayOf(fallback) + picks.filter { it != fallback }
    val used = inUse.distinct()

    /* Every swatch on the sheet, whichever row, with the colour it stands for. */
    val rings = ArrayList<Pair<View, Int>>(offered.size + used.size)

    var chosen = initial

    // A colour in use can also be a quick pick, so every matching swatch is ringed
    fun ring() {
        for ((r, c) in rings) r.setBackgroundResource(if (c == chosen) R.drawable.swatch_ring else 0)
    }

    // The hex box is not rewritten while being typed in
    var typing = false
    var echoing = false
    fun sayHex(color: Int) {
        if (typing) return
        echoing = true
        hex.setText(String.format("#%06X", 0xFFFFFF and color))
        hex.setSelection(hex.text.length)
        echoing = false
    }

    fun land(color: Int) {
        chosen = color
        trial(preview, color)
        ring()
        sayHex(color)
    }

    val edge = resources.getDimensionPixelSize(R.dimen.hairline)

    /* One row of swatches, padded to [columns] cells so both rows line up. */
    fun row(into: LinearLayout, colours: List<Int>, columns: Int) {
        for (c in colours) {
            val cell = layoutInflater.inflate(R.layout.item_swatch, into, false)
            cell.findViewById<View>(R.id.swatch_dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(edge, getColor(R.color.line))
            }
            rings.add(cell.findViewById<View>(R.id.swatch_ring) to c)
            /* Moving the field fires its listener, which lands the colour. */
            cell.setOnClickListener { field.setColor(c, true) }
            into.addView(cell)
        }
        repeat((columns - colours.size).coerceAtLeast(0)) {
            val spacer = layoutInflater.inflate(R.layout.item_swatch, into, false)
            spacer.visibility = View.INVISIBLE
            into.addView(spacer)
        }
    }

    val columns = maxOf(offered.size, used.size)
    row(view.findViewById(R.id.color_in_use), used, columns)
    row(view.findViewById(R.id.color_swatches), offered.toList(), columns)
    if (used.isEmpty()) {
        view.findViewById<View>(R.id.color_in_use_title).visibility = View.GONE
        view.findViewById<View>(R.id.color_in_use).visibility = View.GONE
    }

    /* A whole code, with or without its #, moves the field there; a part code waits. */
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (echoing) return
            val code = s?.toString()?.trim()?.removePrefix("#").orEmpty()
            if (code.length != 6) return
            val rgb = code.toIntOrNull(16) ?: return
            typing = true
            field.setColor(0xFF000000.toInt() or rgb, true)
            typing = false
        }
    })

    field.setAlphaSliderVisible(false)
    field.setBorderColor(getColor(R.color.line))
    field.setSliderTrackerColor(getColor(R.color.text_mute))
    field.setColor(initial)
    field.setOnColorChangedListener { land(it) }
    land(initial)

    /* The sheet rises with the keyboard, so the code box is never under it. */
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    view.findViewById<View>(R.id.color_done).setOnClickListener {
        keep(chosen)
        dialog.dismiss()
    }
    view.findViewById<View>(R.id.color_cancel).setOnClickListener { dialog.dismiss() }

    raise(dialog, view)
}

package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// One colour of the Tajweed edition: its swatch, the rule it marks, and how that is read with a word it is on
private data class Rule(val colour: Int, val name: Int, val note: Int)

private val RULES = listOf(
    Rule(R.color.tajweed_necessary, R.string.tajweed_necessary, R.string.tajweed_necessary_note),
    Rule(R.color.tajweed_obligatory, R.string.tajweed_obligatory, R.string.tajweed_obligatory_note),
    Rule(R.color.tajweed_permissible, R.string.tajweed_permissible, R.string.tajweed_permissible_note),
    Rule(R.color.tajweed_natural, R.string.tajweed_natural, R.string.tajweed_natural_note),
    Rule(R.color.tajweed_ghunnah, R.string.tajweed_ghunnah, R.string.tajweed_ghunnah_note),
    Rule(R.color.tajweed_qalqalah, R.string.tajweed_qalqalah, R.string.tajweed_qalqalah_note),
    Rule(R.color.tajweed_heavy, R.string.tajweed_heavy, R.string.tajweed_heavy_note),
    Rule(R.color.tajweed_silent, R.string.tajweed_silent, R.string.tajweed_silent_note),
)

/** What the Tajweed edition's colours mean, on a sheet; letters left in the page's ink are read as ever. */
fun Activity.showTajweedLegend() {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_sheet, null)
    view.findViewById<TextView>(R.id.sheet_title).setText(R.string.tajweed_legend)
    val rows = view.findViewById<LinearLayout>(R.id.sheet_rows)
    rows.addView((layoutInflater.inflate(R.layout.row_setting_note, rows, false) as TextView).apply {
        setText(R.string.tajweed_legend_intro)
    })
    for (rule in RULES) rows.addView(layoutInflater.inflate(R.layout.item_tajweed_rule, rows, false).apply {
        findViewById<ImageView>(R.id.rule_swatch).imageTintList = ColorStateList.valueOf(getColor(rule.colour))
        findViewById<TextView>(R.id.rule_name).setText(rule.name)
        findViewById<TextView>(R.id.rule_note).setText(rule.note)
    })
    raise(dialog, view)
}

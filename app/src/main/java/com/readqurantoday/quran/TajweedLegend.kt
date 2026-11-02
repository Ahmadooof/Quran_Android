package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.text.SpannableString
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// One colour of the Tajweed edition: the font's palette slots it is painted from, its swatch, the rule it marks and how it is read
private data class Rule(val slots: Set<Int>, val colour: Int, val name: Int, val note: Int)

// Read off the font's own words, one colour at a time
private val RULES = listOf(
    Rule(setOf(3), R.color.tajweed_necessary, R.string.tajweed_necessary, R.string.tajweed_necessary_note),
    Rule(setOf(9), R.color.tajweed_obligatory, R.string.tajweed_obligatory, R.string.tajweed_obligatory_note),
    Rule(setOf(4), R.color.tajweed_permissible, R.string.tajweed_permissible, R.string.tajweed_permissible_note),
    Rule(setOf(5), R.color.tajweed_natural, R.string.tajweed_natural, R.string.tajweed_natural_note),
    Rule(setOf(6), R.color.tajweed_ghunnah, R.string.tajweed_ghunnah, R.string.tajweed_ghunnah_note),
    Rule(setOf(8), R.color.tajweed_qalqalah, R.string.tajweed_qalqalah, R.string.tajweed_qalqalah_note),
    Rule(setOf(7), R.color.tajweed_heavy, R.string.tajweed_heavy, R.string.tajweed_heavy_note),
    Rule(setOf(1, 2, 15), R.color.tajweed_silent, R.string.tajweed_silent, R.string.tajweed_silent_note),
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
    for (rule in RULES) rows.addView(ruleRow(rows, rule))
    raise(dialog, view)
}

/** Word [word] of [surah]:[ayah] on [page] as the Tajweed edition prints it, large, with only the rules its colours mark; [closed] hears it go. */
fun Activity.showTajweedWord(page: Int, surah: Int, ayah: Int, word: Int, closed: () -> Unit) {
    val glyphs = Mushaf.tajweed.glyphsOf(page, surah, ayah, word) ?: return
    val table = Mushaf.tajweed.glyphs(this, page) ?: return
    val ids = glyphs.codePoints().toArray().map { table.id(it) }.filter { it >= 0 }
    val used = TajweedFonts.slotsOf(this, page, ids)
    val rules = RULES.filter { rule -> rule.slots.any { it in used } }

    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_sheet, null)
    view.findViewById<TextView>(R.id.sheet_title).setText(R.string.tajweed_word)
    val rows = view.findViewById<LinearLayout>(R.id.sheet_rows)
    rows.addView(TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(Settings.inkColor(this@showTajweedWord))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_heading))
        val shown = (WORD_PAD * resources.displayMetrics.density).toInt()
        setPadding(0, shown, 0, shown)
        text = SpannableString(OBJECT).apply {
            setSpan(QuranWordSpan(this@showTajweedWord, EndPages.Word(page, glyphs), WORD_SCALE, Mushaf.tajweed), 0, 1, 0)
        }
    })
    if (rules.isEmpty()) rows.addView((layoutInflater.inflate(R.layout.row_setting_note, rows, false) as TextView).apply {
        setText(R.string.tajweed_word_plain)
    })
    for (rule in rules) rows.addView(ruleRow(rows, rule))
    dialog.setOnDismissListener { closed() }
    raise(dialog, view)
}

private fun Activity.ruleRow(rows: LinearLayout, rule: Rule) = layoutInflater.inflate(R.layout.item_tajweed_rule, rows, false).apply {
    findViewById<ImageView>(R.id.rule_swatch).imageTintList = ColorStateList.valueOf(getColor(rule.colour))
    findViewById<TextView>(R.id.rule_name).setText(rule.name)
    findViewById<TextView>(R.id.rule_note).setText(rule.note)
}

// The object character a word's span stands on; the word drawn three times the sheet's text, with room above and below
private const val OBJECT = "￼"
private const val WORD_SCALE = 3f
private const val WORD_PAD = 12f

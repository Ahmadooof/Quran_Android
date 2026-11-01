package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One of the mushaf's closing pages, framed as the Quran's pages are, its text scrolling inside the frame. */
@SuppressLint("ViewConstructor")
class EndPageView(context: Context, private val tapped: () -> Unit) : FrameLayout(context) {

    private val frame = PageFrame()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = uiFont(context)
    }
    private val dp = resources.displayMetrics.density
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        // A tap anywhere on the text brings the reader's bars, as on a Quran page
        setOnClickListener { tapped() }
    }
    private var letter = ""

    init {
        setWillNotDraw(false)
        val inset = (INSET + TEXT_INSET) * dp
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(inset.toInt(), inset.toInt(), inset.toInt(), (inset + FOOT * dp).toInt())
        })
        setOnClickListener { tapped() }
    }

    /** Shows the closing page at [index], from أ. */
    fun show(index: Int) {
        val page = EndPages.pages.getOrNull(index) ?: return
        letter = page.letter
        setBackgroundColor(Settings.paperColor(context))
        rows.removeAllViews()
        for (block in page.blocks) rows.addView(blockView(block))
        invalidate()
    }

    private fun blockView(block: EndPages.Block): TextView = TextView(context).apply {
        val ink = Settings.inkColor(context)
        setTextColor(ink)
        textDirection = View.TEXT_DIRECTION_RTL
        typeface = Typeface.SERIF
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.end_page_text))
        setLineSpacing(0f, LINE)
        val gap = (PARAGRAPH * dp).toInt()
        when (block.kind) {
            EndPages.Kind.BASMALAH -> {
                gravity = Gravity.CENTER
                text = quran(listOf(EndPages.Word(Mushaf.basmalahPage, BASMALAH)), BASMALAH_SCALE, brackets = false)
            }
            EndPages.Kind.TITLE, EndPages.Kind.SUBTITLE -> {
                gravity = Gravity.CENTER
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
                    if (block.kind == EndPages.Kind.TITLE) R.dimen.end_page_title else R.dimen.end_page_text))
                text = runs(block.parts)
                setPadding(0, gap * 2, 0, gap)
            }
            else -> {
                text = runs(block.parts)
                // A list's items stand in from the text's edge
                val indent = if (block.kind == EndPages.Kind.ITEM) (ITEM_INDENT * dp).toInt() else 0
                setPaddingRelative(indent, 0, 0, gap)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
            }
        }
    }

    // The text as typed, each Quranic example in brackets and in the mushaf's own hand
    private fun runs(parts: List<EndPages.Part>): CharSequence = SpannableStringBuilder().apply {
        for (part in parts) when (part) {
            is EndPages.Part.Text -> append(part.text)
            is EndPages.Part.Quran -> append(quran(part.words, WORD_SCALE, brackets = true))
        }
    }

    private fun quran(words: List<EndPages.Word>, scale: Float, brackets: Boolean): CharSequence = SpannableStringBuilder().apply {
        if (brackets) append("(")
        words.forEachIndexed { i, word ->
            if (i > 0) append(' ')
            val at = length
            append(OBJECT)
            setSpan(QuranWordSpan(context.applicationContext, word, scale), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (brackets) append(")")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = INSET * dp
        val edge = EDGE * dp
        val ink = Settings.resolvedAyahColor(context)
        val bottom = height - inset - FOOT * dp / 2f
        frame.frame(canvas, inset, inset, width - inset, bottom, edge, ink)
        // The page's letter in a cartouche on the frame's foot, as a Quran page's number
        label.textSize = LABEL * dp
        label.color = ink
        val tall = label.textSize * LABEL_TALL
        frame.label(canvas, width / 2f, bottom, label.measureText(letter) / 2f + tall * LABEL_PAD, tall, edge, ink, Settings.paperColor(context))
        canvas.drawText(letter, width / 2f, bottom - (label.ascent() + label.descent()) / 2f, label)
    }

    private companion object {
        // The object character a word's span stands on
        const val OBJECT = "￼"
        // The compact Basmalah in the first page font
        const val BASMALAH = ""
        const val BASMALAH_SCALE = 2.2f
        const val WORD_SCALE = 1.25f
        const val LINE = 1.15f
        // In dp: the frame's distance from the edge, the text's inside it, the foot left for the letter,
        // the frame's rule, the letter's size, a paragraph's gap and a list item's indent
        const val INSET = 10f
        const val TEXT_INSET = 14f
        const val FOOT = 24f
        const val EDGE = 1.2f
        const val LABEL = 15f
        const val PARAGRAPH = 10f
        const val ITEM_INDENT = 12f
        // The letter's cartouche: its height over the letter's size, and padding either side
        const val LABEL_TALL = 1.6f
        const val LABEL_PAD = 0.5f
    }
}

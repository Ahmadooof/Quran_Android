package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils

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
    private val lines = ArrayList<Pair<EndPages.Kind, EndLine>>()
    // The width the lines were last sized for
    private var fitted = -1
    private var fittedTall = -1
    private val prose = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SERIF }
    private val titles = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

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
        val ink = Settings.inkColor(context)
        prose.color = ink
        titles.color = ink
        rows.removeAllViews()
        lines.clear()
        for (line in page.lines) {
            val view = when (line.kind) {
                EndPages.Kind.BASMALAH -> EndLine(context, quran(listOf(EndPages.Word(Mushaf.basmalahPage, BASMALAH)), BASMALAH_SCALE, "-"), EndLine.Mode.CENTER, prose)
                EndPages.Kind.TITLE -> EndLine(context, runs(line.parts), EndLine.Mode.CENTER, titles)
                EndPages.Kind.LINE -> EndLine(context, runs(line.parts), EndLine.Mode.JUSTIFY, prose)
                EndPages.Kind.END -> EndLine(context, runs(line.parts), EndLine.Mode.START, prose)
            }
            lines.add(line.kind to view)
            rows.addView(view)
        }
        fitted = -1
        fittedTall = -1
        requestLayout()
        invalidate()
    }

    // Sized as the page is measured, when its width is known
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val wide = MeasureSpec.getSize(widthSpec)
        val high = MeasureSpec.getSize(heightSpec)
        if (wide != fitted || high != fittedTall) {
            fitted = wide
            fittedTall = high
            fit(wide)
            // The lines share the frame's height, as printed, unless they need more and scroll
            val room = high - (2 * (INSET + TEXT_INSET) + FOOT) * dp
            if (room > 0f) for ((_, line) in lines) line.tall = room / lines.size
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    // One size for the page's text: the largest at which every printed line still fits the width whole
    private fun fit(wide: Int) {
        val room = wide - 2 * (INSET + TEXT_INSET) * dp
        if (room <= 0f || lines.isEmpty()) return
        val most = resources.getDimension(R.dimen.end_page_text)
        var size = most
        for ((kind, line) in lines) {
            val scale = if (kind == EndPages.Kind.TITLE) TITLE_SCALE else 1f
            val wide = line.natural(most * scale)
            if (wide > room) size = minOf(size, most * room / wide)
        }
        for ((kind, line) in lines) line.size = size * if (kind == EndPages.Kind.TITLE) TITLE_SCALE else 1f
    }

    // The text as typed, each Quranic example in the mushaf's own hand, in the brackets it shows
    private fun runs(parts: List<EndPages.Part>): CharSequence = SpannableStringBuilder().apply {
        for (part in parts) when (part) {
            is EndPages.Part.Text -> append(part.text)
            is EndPages.Part.Quran -> append(quran(part.words, WORD_SCALE, part.brackets))
        }
    }

    private fun quran(words: List<EndPages.Word>, scale: Float, brackets: String): CharSequence = SpannableStringBuilder().apply {
        if ('(' in brackets) append("(")
        words.forEachIndexed { i, word ->
            if (i > 0) append(' ')
            val at = length
            append(OBJECT)
            setSpan(QuranWordSpan(context.applicationContext, word, scale), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (')' in brackets) append(")")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = INSET * dp
        val edge = EDGE * dp
        // As on the Quran's pages: the ornament drawn back toward the page by night
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val mark = Settings.resolvedAyahColor(context)
        val ink = if (night) ColorUtils.blendARGB(mark, Settings.paperColor(context), NIGHT_RECEDE) else mark
        val bottom = height - inset - FOOT * dp / 2f
        frame.frame(canvas, inset, inset, width - inset, bottom, edge, ink)
        // The page's letter in a cartouche on the frame's foot, as a Quran page's number
        label.textSize = LABEL * dp
        label.color = mark
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
        const val TITLE_SCALE = 1.4f
        // In dp: the frame's distance from the edge, the text's inside it, the foot left for the letter,
        // the frame's rule and the letter's size
        const val INSET = 10f
        const val TEXT_INSET = 14f
        const val FOOT = 24f
        const val EDGE = 1.2f
        const val LABEL = 15f
        // The letter's cartouche: its height over the letter's size, and padding either side
        const val LABEL_TALL = 1.6f
        const val LABEL_PAD = 0.5f
        const val NIGHT_RECEDE = 0.3f
    }
}

package com.readqurantoday.quran

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * One page of the mushaf drawn by placing pre-resolved glyphs.
 * Each page has its own TTF; same codepoint = different word on a different page.
 */
class MushafPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        /* No hinting: TrueType instructions distort these pre-shaped outlines. */
        hinting = Paint.HINTING_OFF
        isLinearText = true
    }

    /* Labels around the page (juz, surah name, folio) use the phone's own font. */
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        hinting = Paint.HINTING_OFF
        isLinearText = true
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        hinting = Paint.HINTING_OFF
        isLinearText = true
    }

    /* Lit word drawn in ink_lit colour on top of the glow rect. */
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        hinting = Paint.HINTING_OFF
        isLinearText = true
    }

    private var lines: List<Mushaf.Line> = emptyList()
    private var table: Mushaf.Glyphs? = null
    private var font: android.graphics.fonts.Font? = null
    private var pageNo = 0

    /* Short closing lines are centred with a fixed gap rather than justified. */
    private val centreGap = 0.32f

    /* Words written with an internal space (two glyphs, one word): 0.04em gap. */
    private val innerSpace = 0.04f

    private var padX = 0f
    private var padTop = 0f
    private var padBottom = 0f
    private var headBand = 0f

    /* Reused across draws to avoid per-frame allocation. */
    private var ids = IntArray(320)
    private var spots = FloatArray(640)

    /* Ayah markers are drawn in a separate colour from the rest of the line. */
    private var markIds = IntArray(64)
    private var markSpots = FloatArray(128)

    /* Lit word glyphs, separated so they draw in litPaint on top of the glow. */
    private var litIds = IntArray(64)
    private var litSpots = FloatArray(128)

    /* Word bounds rebuilt each draw so a tap can name the word under it. */
    private val placed = ArrayList<FloatArray>(200)

    private var litSurah = -1
    private var litAyah = -1
    private var litWord = -1

    private var atSurah = 0
    private var atAyah = 0
    private var atWord = 0

    init {
        val d = resources.displayMetrics.density
        padX      = PAD_X      * d
        padTop    = PAD_TOP    * d
        padBottom = PAD_BOTTOM * d
        headBand  = HEAD_BAND  * d
        label.textSize = LABEL_SP * resources.displayMetrics.scaledDensity
    }

    /* Re-read colours on every page bind; a view is reused across theme changes. */
    private fun dress() {
        setBackgroundColor(context.getColor(R.color.paper))
        paint.color = context.getColor(R.color.ink)
        val labelling = context.getColor(R.color.accent)
        label.color = labelling
        markPaint.color = labelling
        titlePaint.color = context.getColor(R.color.ornament)
        litPaint.color = context.getColor(R.color.ink_lit)
    }

    fun light(surah: Int, ayah: Int, word: Int) {
        if (surah == litSurah && ayah == litAyah && word == litWord) return
        litSurah = surah
        litAyah = ayah
        litWord = word
        invalidate()
    }

    fun wordUnder(x: Float, y: Float): IntArray? {
        for (w in placed) {
            if (x >= w[0] && x <= w[1] && y >= w[2] && y <= w[3]) {
                return intArrayOf(w[4].toInt(), w[5].toInt(), w[6].toInt())
            }
        }
        return null
    }

    /** Visual bounds [left, right, top, bottom] of the word at (x, y). */
    fun wordRectUnder(x: Float, y: Float): FloatArray? {
        for (w in placed) {
            if (x >= w[0] && x <= w[1] && y >= w[2] && y <= w[3]) {
                return floatArrayOf(w[0], w[1], w[2], w[3])
            }
        }
        return null
    }

    fun show(page: Int) {
        dress()
        pageNo = page
        lines = Mushaf.lines(page)
        table = Mushaf.glyphs(context, page)
        font = Mushaf.font(context, page)
        paint.typeface = Mushaf.face(context, page)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val glyphs = table ?: return
        if (lines.isEmpty()) return

        val left = padX
        val right = width - padX
        val measure = right - left
        if (measure <= 0) return

        /* Type size = width / ems-per-full-line. Every line starts here and shrinks from it. */
        val body = (measure / Mushaf.emWidth).toFloat()

        val grid = 15
        val top = padTop + headBand
        val step = (height - top - padBottom) / grid

        runningHead(canvas, left, measure)
        folio(canvas)

        atSurah = Ayat.surahAt(pageNo)
        atAyah = Ayat.ayahAt(pageNo)
        atWord = Ayat.wordAt(pageNo)
        placed.clear()

        paint.textSize = body
        val centreOffset = -(paint.ascent() + paint.descent()) / 2f

        var slot = 0
        for (line in lines) {
            if (slot >= grid) break
            val y = top + step * slot + step / 2f + centreOffset
            slot++
            when (line.kind) {
                "surah" -> if (line.surah > 0) {
                    atSurah = line.surah
                    atAyah = 1
                    atWord = 0
                    title(canvas, line.surah, left + measure / 2f, step * 0.95f, y)
                }
                "basmalah" -> basmalah(canvas, left + measure / 2f, body * 0.86f, y)
                else -> if (line.words.isNotEmpty()) {
                    drawLine(canvas, line, glyphs, left, measure, body, y, step)
                }
            }
        }
    }

    /* One line fitted to the measure, right to left. Short lines are centred. */
    private fun drawLine(
        canvas: Canvas,
        line: Mushaf.Line,
        glyphs: Mushaf.Glyphs,
        left: Float,
        measure: Float,
        body: Float,
        y: Float,
        slot: Float
    ) {
        val words = line.words.map { it.joinToString(" ") }

        var size = body
        var natural = lineWidth(words, glyphs, size)
        if (natural <= 0f) return

        if (natural > measure) {
            size = body * (measure / natural) * 0.995f
            natural = lineWidth(words, glyphs, size)
        }

        val gaps = words.size - 1
        val spare = (measure - natural).coerceAtLeast(0f)
        val short = natural < measure * Mushaf.centreBelow
        val gap = when {
            gaps <= 0 -> 0f
            short -> minOf(centreGap * size, spare / gaps)
            else -> spare / gaps
        }

        paint.textSize = size

        val start = if (short) left + (measure + natural + gap * gaps) / 2f else left + measure

        val scale = size / glyphs.upem
        val marks = Mushaf.marksOn(pageNo)
        var x = start
        var n = 0
        var m = 0
        var litN = 0

        /* Save state before the loop for the API < 31 fallback path. */
        val fbSurah0 = atSurah
        val fbAyah0  = atAyah
        val fbWord0  = atWord

        for (word in words) {
            val isMark = word.isNotEmpty() && marks.contains(word)

            val ofSurah = atSurah
            val ofAyah = atAyah
            val ofWord = if (isMark) -1 else atWord

            /* Detect lit word before collecting glyphs so we can route them correctly. */
            val isLit = !isMark && ofSurah == litSurah && ofAyah == litAyah && ofWord == litWord

            val began = x
            var pen = x
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                i += Character.charCount(cp)

                val id = glyphs.id(cp)
                if (id < 0) {
                    /* Space inside a word — no glyph, draw a gap. */
                    pen -= innerSpace * size
                    continue
                }

                val advance = glyphs.advance(cp) * scale
                if (isMark) {
                    if (m * 2 + 1 >= markSpots.size) growMarks()
                    markIds[m] = id
                    markSpots[m * 2] = pen - advance
                    markSpots[m * 2 + 1] = y
                    m++
                    pen -= advance
                    continue
                }
                /* Lit word goes to separate arrays so it can be drawn in ink_lit colour. */
                if (isLit) {
                    if (litN * 2 + 1 >= litSpots.size) growLit()
                    litIds[litN] = id
                    litSpots[litN * 2] = pen - advance
                    litSpots[litN * 2 + 1] = y
                    litN++
                } else {
                    if (n * 2 + 1 >= spots.size) grow()
                    ids[n] = id
                    spots[n * 2] = pen - advance
                    spots[n * 2 + 1] = y
                    n++
                }
                pen -= advance
            }

            if (!isMark) {
                placed.add(
                    floatArrayOf(
                        pen, began, y - slot * 0.44f, y + slot * 0.24f,
                        ofSurah.toFloat(), ofAyah.toFloat(), ofWord.toFloat()
                    )
                )
                atWord++
            } else {
                atAyah++
                atWord = 0
            }

            x = pen - gap
        }
        if (n == 0 && m == 0 && litN == 0) return

        val face = font
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && face != null) {
            if (n > 0) canvas.drawGlyphs(ids, 0, spots, 0, n, face, paint)
            if (m > 0) {
                markPaint.textSize = size
                canvas.drawGlyphs(markIds, 0, markSpots, 0, m, face, markPaint)
            }
            if (litN > 0) {
                litPaint.textSize = size
                canvas.drawGlyphs(litIds, 0, litSpots, 0, litN, face, litPaint)
            }
        } else {
            /* API < 31: draw as text; the Arabic shaper is back in the way. */
            markPaint.textSize = size
            markPaint.typeface = paint.typeface
            markPaint.textAlign = Paint.Align.LEFT
            litPaint.textSize = size
            litPaint.typeface = paint.typeface
            litPaint.textAlign = Paint.Align.LEFT
            var wx = start
            var fbSurah = fbSurah0
            var fbAyah  = fbAyah0
            var fbWord  = fbWord0
            for (word in words) {
                val isMk = word.isNotEmpty() && marks.contains(word)
                val isLit2 = !isMk && fbSurah == litSurah && fbAyah == litAyah && fbWord == litWord
                val pen = when {
                    isMk    -> markPaint
                    isLit2  -> litPaint
                    else    -> paint
                }
                for (part in word.split(' ')) {
                    if (part.isEmpty()) continue
                    val w = wordWidth(part, glyphs, size)
                    canvas.drawText(part, wx - w, y, pen)
                    wx -= w + innerSpace * size
                }
                wx += innerSpace * size
                wx -= gap
                if (!isMk) fbWord++ else { fbAyah++; fbWord = 0 }
            }
        }
    }

    /* Running head: juz on the right, surah name centred, page number on the left. */
    private fun runningHead(canvas: Canvas, left: Float, measure: Float) {
        if (pageNo <= 0) return
        val y = padTop + headBand * 0.62f

        val juz = Surahs.juzOfPage(pageNo)
        if (juz > 0) {
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(context.getString(R.string.head_juz, figures(juz, resources)), left + measure, y, label)
        }

        Surahs.ofPage(pageNo)?.let {
            title(canvas, it.id, left + measure / 2f, headBand * 0.82f, y)
        }

        label.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.head_page, figures(pageNo, resources)), left, y, label)
    }

    /* Surah name drawn as glyphs from the names face, not as typed text. */
    private fun title(canvas: Canvas, surah: Int, centre: Float, size: Float, y: Float) {
        val names = Mushaf.names(context) ?: return
        val scale = size / names.upem

        val word = Mushaf.SURAH_WORD
        val name = Mushaf.nameCode(surah)
        val between = 0.1f * size

        val wordW = names.advance(word) * scale
        val nameW = names.advance(name) * scale
        val total = wordW + between + nameW
        if (total <= 0f) return

        titlePaint.textSize = size

        var pen = centre + total / 2f
        for (cp in intArrayOf(word, name)) {
            val advance = names.advance(cp) * scale
            glyph(canvas, names, cp, pen - advance, y)
            pen -= advance + between
        }
    }

    private fun glyph(canvas: Canvas, names: Mushaf.Glyphs, cp: Int, x: Float, y: Float) {
        val id = names.id(cp)
        if (id < 0) return
        val face = Mushaf.nameFace(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && face != null) {
            canvas.drawGlyphs(intArrayOf(id), 0, floatArrayOf(x, y), 0, 1, face, titlePaint)
        } else {
            titlePaint.typeface = Mushaf.nameTypeface(context)
            titlePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(String(Character.toChars(cp)), x, y, titlePaint)
        }
    }

    /* Basmalah drawn from page 1's face — its four words live there. */
    private fun basmalah(canvas: Canvas, centre: Float, size: Float, y: Float) {
        val words = Mushaf.basmalah
        if (words.isEmpty()) return
        val from = Mushaf.basmalahPage
        val glyphs = Mushaf.glyphs(context, from) ?: return

        val scale = size / glyphs.upem
        var total = 0f
        for (word in words) total += wordWidth(word, glyphs, size)
        if (total <= 0f) return

        titlePaint.textSize = size

        var pen = centre + total / 2f
        var n = 0
        for (word in words) {
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                i += Character.charCount(cp)
                val id = glyphs.id(cp)
                if (id < 0) {
                    pen -= innerSpace * size
                    continue
                }
                val advance = glyphs.advance(cp) * scale
                if (n * 2 + 1 >= spots.size) grow()
                ids[n] = id
                spots[n * 2] = pen - advance
                spots[n * 2 + 1] = y
                n++
                pen -= advance
            }
        }
        if (n == 0) return

        val face = Mushaf.font(context, from)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && face != null) {
            canvas.drawGlyphs(ids, 0, spots, 0, n, face, titlePaint)
        } else {
            titlePaint.typeface = Mushaf.face(context, from)
            titlePaint.textAlign = Paint.Align.LEFT
            var wx = centre + total / 2f
            for (word in words) {
                val w = wordWidth(word, glyphs, size)
                canvas.drawText(word, wx - w, y, titlePaint)
                wx -= w
            }
        }
    }

    private fun folio(canvas: Canvas) {
        if (pageNo <= 0) return
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(figures(pageNo, resources), width / 2f, height - padBottom / 2f, label)
    }

    private fun lineWidth(words: List<String>, glyphs: Mushaf.Glyphs, size: Float): Float {
        var sum = 0f
        for (word in words) sum += wordWidth(word, glyphs, size)
        return sum
    }

    private fun wordWidth(word: String, glyphs: Mushaf.Glyphs, size: Float): Float {
        val scale = size / glyphs.upem
        var sum = 0f
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            sum += if (glyphs.id(cp) < 0) innerSpace * size else glyphs.advance(cp) * scale
        }
        return sum
    }

    private fun grow() {
        ids = ids.copyOf(ids.size * 2)
        spots = spots.copyOf(spots.size * 2)
    }

    private fun growMarks() {
        markIds = markIds.copyOf(markIds.size * 2)
        markSpots = markSpots.copyOf(markSpots.size * 2)
    }

    private fun growLit() {
        litIds = litIds.copyOf(litIds.size * 2)
        litSpots = litSpots.copyOf(litSpots.size * 2)
    }

    companion object {
        // --- layout tuning (all in dp) ---
        // Adjust these to control spacing around the 15-line grid.

        /** Gap between the left/right edge and the text grid. */
        const val PAD_X = 14f

        /** Gap above the header band. */
        const val PAD_TOP = 8f

        /** Gap below the footer band, between footer and screen bottom. */
        const val PAD_BOTTOM = 32f

        /** Height of the header band (holds juz + surah name + page number). */
        const val HEAD_BAND = 30f

        /** Header/footer label size in sp. */
        const val LABEL_SP = 14f
    }
}

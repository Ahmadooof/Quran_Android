package com.readqurantoday.quran

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Picture
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs

// One mushaf page drawn from pre-resolved glyphs; each page has its own font
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

    /** The page on show. */
    val page get() = pageNo

    // Pre-rendered page images, or null to always draw live
    var shots: ((Int, Int, Int) -> Bitmap?)? = null

    // Pinch and pan draw from the shot while fingers are down; the page is redrawn sharp on lift
    private var fingers = false

    /** What a sideways drag turns the page with, when pages turn rather than slide. */
    interface Turner {
        /** A turn toward the next page ([next]) or back, taken at ([x], [y]). False if there is none. */
        fun begin(next: Boolean, x: Float, y: Float): Boolean
        fun move(x: Float, y: Float)
        /** Released at ([x], [y]) moving at [velocityX] px/s, or [cancelled]. */
        fun end(x: Float, y: Float, velocityX: Float, cancelled: Boolean)
    }

    /* Set when pages turn; null when they slide, and the pager takes sideways drags. */
    var turner: Turner? = null

    /** Called when a pinch or a pan of the zoomed page begins: the reader is looking closely. */
    var onCloseLook: (() -> Unit)? = null
    private var turning = false

    /** This page runs taller than the screen and scrolls, so sideways drags must stay the pager's. */
    val scrolls get() = maxScroll > 0f
    private val shotPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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

    /* Bounding box of the lit word, used to draw the optional bg rect. */
    /* Word bounds rebuilt each draw so a tap can name the word under it. */
    private val placed = ArrayList<FloatArray>(200)

    private var litSurah = -1
    private var litAyah = -1
    private var litWord = -1

    private var atSurah = 0
    private var atAyah = 0
    private var atWord = 0

    // An ayah shown briefly after it was picked from search
    private var flashSurah = -1
    private var flashAyah = -1
    private var flashStart = 0L
    private var flashReveal = false
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashRect = RectF()

    // --- zoom + pan ---

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var dragX = 0f
    private var dragY = 0f

    // A pinch or pan must not reach click listeners, or it lights a word by mistake
    private var pinching = false
    private var panning = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Zoom when the pinch began, so letting go can tell a pinch in from a pinch out
    private var pinchFrom = 1f
    private var settle: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                settle?.cancel()
                pinchFrom = zoom
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newZoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
                val dF = newZoom / zoom          // actual factor after clamping
                // Keeps the content under the fingers still: pan = dF*pan + (1-dF)*(focus - center)
                panX = dF * panX + (1f - dF) * (d.focusX - width  / 2f)
                panY = dF * panY + (1f - dF) * (d.focusY - height / 2f)
                zoom = newZoom
                clampPan()
                invalidate()
                return true
            }
            // A pinch in that stops short of full size still means the whole page
            override fun onScaleEnd(d: ScaleGestureDetector) {
                val shrinking = zoom < pinchFrom
                if (zoom < SNAP_EXACT || (shrinking && zoom < SNAP_BACK_ZOOM)) settleToFull()
            }
        }
    )

    private fun settleToFull() {
        settle?.cancel()
        if (zoom == 1f) return
        val fromZoom = zoom
        val fromX = panX
        val fromY = panY
        settle = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val left = it.animatedValue as Float
                zoom = 1f + (fromZoom - 1f) * left
                panX = fromX * left
                panY = fromY * left
                invalidate()
            }
            start()
        }
    }

    private fun clampPan() {
        val maxX = (zoom - 1f) * width  / 2f
        val maxY = (zoom - 1f) * height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    // --- shots ---

    /* Draw live even where a shot would do: set only while laying out for a tap. */
    private var forceLive = false

    // The shot stands in at rest unzoomed and mid-gesture; never with a lit word, a scrolling page, or zoomed at rest
    private fun shotToDraw(): Bitmap? {
        if (forceLive || litWord >= 0 || flashAyah > 0 || maxScroll > 0f || scrollTop != 0f) return null
        if (zoom != 1f && !fingers && settle?.isRunning != true) return null
        return shots?.invoke(pageNo, width, height)
    }

    private fun drawShot(canvas: Canvas, shot: Bitmap) {
        val zoomed = zoom != 1f
        if (zoomed) {
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(zoom, zoom, width / 2f, height / 2f)
        }
        canvas.drawBitmap(shot, 0f, 0f, shotPaint)
        if (zoomed) canvas.restore()
    }

    // A shot-only page has no word bounds, so taps need one hidden layout pass; onDraw skips the background
    @SuppressLint("WrongCall")
    private fun ensureLaidOut() {
        if (laidOut || width == 0 || height == 0) return
        forceLive = true
        val scratch = Picture()
        onDraw(scratch.beginRecording(width, height))
        scratch.endRecording()
        forceLive = false
    }

    // Overlapping pre-shaped glyphs leave hairline cracks when magnified; a 1/3px outward stroke closes them
    private fun seamGuard(on: Boolean) {
        val w = if (on) SEAM_STROKE / zoom else 0f
        for (p in arrayOf(paint, markPaint, litPaint, titlePaint)) {
            p.style = if (on) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            p.strokeWidth = w
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A new touch finishes a running glide at once; end() on a finished one would replay it
                settle?.takeIf { it.isRunning }?.end()
                fingers = true
                pinching = false
                panning = false
                scrolling = false
                turning = false
                /* A finger on a moving page stops it, as a hand stops a turning one. */
                flinger.forceFinished(true)
                downX = e.x; downY = e.y
                dragX = e.x; dragY = e.y
            }

            /* A second finger landed: this gesture is a pinch from here on. */
            MotionEvent.ACTION_POINTER_DOWN -> if (!pinching) {
                /* A second finger turns a turn into a pinch: the page falls back. */
                if (turning) {
                    turner?.end(e.x, e.y, 0f, cancelled = true)
                    turning = false
                }
                pinching = true
                onCloseLook?.invoke()
                scrolling = false
                dropPress(e)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pinching && e.pointerCount == 1) {
                    val dx = e.x - dragX
                    val dy = e.y - dragY
                    val turns = turner
                    // A mostly sideways drag on an unzoomed, non-scrolling page turns it; right is next
                    if (turns != null && zoom == 1f && !scrolls && !turning && !panning &&
                        abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)
                    ) {
                        if (turns.begin(e.x > downX, downX, downY)) turning = true
                        // A sideways drag is never a tap, even when no turn could start
                        dropPress(e)
                    }
                    if (turning) {
                        turns?.move(e.x, e.y)
                    } else if (zoom > 1f) {
                        /* Only past the slop, so a steady finger is still a press. */
                        if (!panning && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                            panning = true
                            onCloseLook?.invoke()
                            dropPress(e)
                        }
                        if (panning) {
                            panX += dx
                            val wantY = panY + dy
                            panY = wantY
                            clampPan()
                            // Vertical pan the zoomed view cannot take becomes page scroll
                            spill(wantY - panY)
                            invalidate()
                        }
                    } else if (maxScroll > 0f) {
                        // Mostly vertical drags scroll; sideways ones are left to the pager
                        if (!scrolling && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                            scrolling = true
                            dropPress(e)
                        }
                        if (scrolling) {
                            scrollTop = (scrollTop - dy).coerceIn(0f, maxScroll)
                            invalidate()
                        }
                    }
                }
                dragX = e.x; dragY = e.y
            }

            MotionEvent.ACTION_UP -> {
                if (scrolling) fling()
                if (turning) {
                    val vx = tracker?.let { it.computeCurrentVelocity(1000, flingMax); it.xVelocity } ?: 0f
                    turner?.end(e.x, e.y, vx, cancelled = false)
                    turning = false
                }
                release()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (turning) {
                    turner?.end(e.x, e.y, 0f, cancelled = true)
                    turning = false
                }
                release()
            }
        }

        // Held for the whole gesture, or a pinch passing 1x turns into a page swipe
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f || pinching || panning || scrolling || turning)

        // Clean presses still reach listeners at any zoom
        if (pinching || panning || scrolling || turning) return true
        return super.onTouchEvent(e) || zoom > 1f
    }

    // Divided by zoom: scroll is in page pixels
    private fun spill(over: Float) {
        if (over == 0f || maxScroll <= 0f) return
        scrollTop = (scrollTop - over / zoom).coerceIn(0f, maxScroll)
    }

    /* Let a scroll carry on after the finger leaves, at the speed it left at. */
    private fun fling() {
        val t = tracker ?: return
        t.computeCurrentVelocity(1000, flingMax)
        val vy = t.yVelocity
        if (abs(vy) < flingMin) return
        flinger.fling(0, scrollTop.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll.toInt())
        postInvalidateOnAnimation()
    }

    private fun release() {
        tracker?.recycle()
        tracker = null
        /* The gesture drew from the shot; the page it leaves is drawn sharp. */
        fingers = false
        if (zoom != 1f) invalidate()
    }

    /* Tell the View the press is over, so no click or long-click comes of it. */
    private fun dropPress(e: MotionEvent) {
        cancelLongPress()
        val cancel = MotionEvent.obtain(e)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    // screen → canvas coordinate (inverse of the scale+translate transform in onDraw)
    private fun toCanvas(screen: Float, pan: Float, size: Float): Float =
        if (zoom == 1f) screen else (screen - pan - size / 2f) / zoom + size / 2f

    init {
        val d = resources.displayMetrics.density
        padX      = PAD_X      * d
        padTop    = PAD_TOP    * d
        padBottom = PAD_BOTTOM * d
        headBand  = HEAD_BAND  * d
        label.textSize = LABEL_SP * resources.displayMetrics.scaledDensity
    }

    /* The style version this view last dressed at. -1 so the first draw dresses. */
    private var dressedAt = -1

    // Read when dressing, not per frame: a pinch redraws the page 60 times a second
    private var inkSpread = 0f
    private var markSpread = 0f
    private var litSpread = 0f

    // Depends only on the page, so built once in show()
    private var lineWords: List<List<String>> = emptyList()
    private var pageMarks = ""
    private var headJuz = ""
    private var headPage = ""
    private var headSurah = 0
    private var folioText = ""

    /* The glyph and position for one title glyph, reused rather than allocated per draw. */
    private val oneId = IntArray(1)
    private val oneSpot = FloatArray(2)

    // --- page geometry and scrolling ---

    // Type is sized by width; rows share the height, or keep upright proportions and scroll
    private var body = 0f
    private var step = 0f

    // Rows are fixed by the mushaf; only draw size and scroll change
    private var pageTall = 0f
    private var scrollTop = 0f
    private val maxScroll get() = (pageTall - height).coerceAtLeast(0f)

    // A lit word waits to be revealed until words are placed
    private var scrolling = false
    private var tracker: VelocityTracker? = null
    private val flinger = OverScroller(context)
    private val flingMin = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val flingMax = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var revealPending = false

    private fun layOut() {
        val wide = width - 2 * padX
        if (wide <= 0f) return
        body = (wide / Mushaf.emWidth).toFloat()
        val top = padTop + headBand
        val fit = (height - top - padBottom) / GRID
        step = if (previewMode || (fit > 0f && body / fit <= TALL_FROM)) fit else body / READING_ROW
        pageTall = if (previewMode) height.toFloat() else top + step * GRID + padBottom
        scrollTop = scrollTop.coerceIn(0f, maxScroll)
    }

    // Word bounds are in page coordinates, so built once per page or size change
    private var laidOut = false

    // Unsaved picker colours for the preview line; null uses the saved setting
    private var litTrial: Int? = null
    private var markTrial: Int? = null
    private var inkTrial: Int? = null
    private var paperTrial: Int? = null

    /** Show these colours instead of the saved ones, until called again with nulls. */
    fun tryOn(lit: Int? = null, mark: Int? = null, ink: Int? = null, paper: Int? = null) {
        litTrial = lit
        markTrial = mark
        inkTrial = ink
        paperTrial = paper
        dress()
        invalidate()
    }

    /* Re-read colours on every page bind; a view is reused across theme changes. */
    private fun dress() {
        /* Taken before the reads, so a change landing mid-dress is caught next draw. */
        dressedAt = Settings.styleVersion
        setBackgroundColor(paperTrial ?: Settings.paperColor(context))
        paint.color = inkTrial ?: Settings.inkColor(context)
        // The juz and page numbers are the page's own figures, so they take the ayah number's colour
        label.color = markTrial ?: Settings.resolvedAyahColor(context)
        markPaint.color = markTrial ?: Settings.resolvedAyahColor(context)
        // Surah titles and the Basmalah are the page's own words, so they take the page's ink
        titlePaint.color = inkTrial ?: Settings.inkColor(context)
        litPaint.color = litTrial ?: Settings.highlightColor(context)
        inkSpread = spread(Settings.inkWeight(context))
        markSpread = spread(Settings.ayahWeight(context))
        litSpread = spread(Settings.litWeight(context))
        // Never fake bold: it tears these glyphs. drawRun thickens by dilation instead
        paint.isFakeBoldText = false
        markPaint.isFakeBoldText = false
        litPaint.isFakeBoldText = false
        litPaint.strokeWidth = 0f
        litPaint.style = Paint.Style.FILL
    }

    fun flash(surah: Int, ayah: Int) {
        flashSurah = surah
        flashAyah = ayah
        flashStart = SystemClock.uptimeMillis()
        flashReveal = maxScroll > 0f
        invalidate()
    }

    // Soft band behind each line of the flashed ayah: held, then faded out
    private fun drawFlash(canvas: Canvas) {
        if (flashAyah <= 0 || !laidOut) return
        val age = SystemClock.uptimeMillis() - flashStart
        if (age >= FLASH_HOLD_MS + FLASH_FADE_MS) {
            flashAyah = -1
            flashSurah = -1
            return
        }
        val fade = if (age <= FLASH_HOLD_MS) 1f else 1f - (age - FLASH_HOLD_MS).toFloat() / FLASH_FADE_MS
        val colour = Settings.highlightColor(context)
        flashPaint.color = (colour and 0x00FFFFFF) or ((FLASH_ALPHA * fade).toInt() shl 24)
        val pad = step * 0.08f
        val round = step * 0.18f
        var lineY = Float.NaN
        for (w in placed) {
            if (w[4].toInt() != flashSurah || w[5].toInt() != flashAyah) continue
            if (w[2] != lineY) {
                if (!lineY.isNaN()) canvas.drawRoundRect(flashRect, round, round, flashPaint)
                lineY = w[2]
                flashRect.set(w[0], w[2] - pad, w[1], w[3] + pad)
            } else {
                flashRect.left = minOf(flashRect.left, w[0])
                flashRect.right = maxOf(flashRect.right, w[1])
            }
        }
        if (!lineY.isNaN()) canvas.drawRoundRect(flashRect, round, round, flashPaint)
        postInvalidateOnAnimation()
    }

    // On a page that scrolls, bring the flashed ayah's first word into view
    private fun revealFlash() {
        flashReveal = false
        val w = placed.firstOrNull { it[4].toInt() == flashSurah && it[5].toInt() == flashAyah } ?: return
        if (w[2] >= scrollTop + step && w[3] <= scrollTop + height - step) return
        val target = (w[2] - height / 3f).coerceIn(0f, maxScroll)
        flinger.forceFinished(true)
        flinger.startScroll(0, scrollTop.toInt(), 0, (target - scrollTop).toInt(), REVEAL_MS)
        postInvalidateOnAnimation()
    }

    fun light(surah: Int, ayah: Int, word: Int) {
        if (surah == litSurah && ayah == litAyah && word == litWord) return
        litSurah = surah
        litAyah = ayah
        litWord = word
        /* On a scrolling page, recitation must not run off the bottom of the screen. */
        revealPending = word >= 0 && maxScroll > 0f
        invalidate()
    }

    // Scrolls a lit word to about a third down, so the lines about to be read show too
    private fun reveal() {
        revealPending = false
        if (maxScroll <= 0f) return
        val w = placed.firstOrNull {
            it[4].toInt() == litSurah && it[5].toInt() == litAyah && it[6].toInt() == litWord
        } ?: return

        val wordTop = w[2]
        val wordBottom = w[3]
        if (wordTop >= scrollTop + step && wordBottom <= scrollTop + height - step) return

        val target = (wordTop - height / 3f).coerceIn(0f, maxScroll)
        flinger.forceFinished(true)
        flinger.startScroll(0, scrollTop.toInt(), 0, (target - scrollTop).toInt(), REVEAL_MS)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (flinger.computeScrollOffset()) {
            scrollTop = flinger.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    fun wordUnder(x: Float, y: Float): IntArray? {
        ensureLaidOut()
        val cx = toCanvas(x, panX, width.toFloat())
        /* Undo the zoom, then the scroll: the word bounds are in page coordinates. */
        val cy = toCanvas(y, panY, height.toFloat()) + scrollTop
        for (w in placed) {
            if (cx >= w[0] && cx <= w[1] && cy >= w[2] && cy <= w[3]) {
                return intArrayOf(w[4].toInt(), w[5].toInt(), w[6].toInt())
            }
        }
        return null
    }

    /** When true, onDraw renders only the first text line centered in the view. */
    var previewMode = false

    fun show(page: Int) {
        dress()
        /* A recycled view arrives still holding the last reader's zoom. */
        settle?.cancel()
        zoom = 1f; panX = 0f; panY = 0f
        pageNo = page
        lines = Mushaf.lines(page)
        table = Mushaf.glyphs(context, page)
        font = Mushaf.font(context, page)
        paint.typeface = Mushaf.face(context, page)

        lineWords = lines.map { line -> line.words.map { it.joinToString(" ") } }
        pageMarks = Mushaf.marksOn(page)
        val juz = Surahs.juzOfPage(page)
        headJuz = if (juz > 0) context.getString(R.string.head_juz, figures(juz, resources)) else ""
        headPage = context.getString(R.string.head_page, figures(page, resources))
        // A page that opens with a surah's own title needs no name above it
        val opensWithTitle = lines.firstOrNull { it.kind == "surah" || it.kind == "ayah" }?.kind == "surah"
        headSurah = if (opensWithTitle) 0 else Surahs.headOfPage(page)?.id ?: 0
        folioText = figures(page, resources)
        laidOut = false

        /* A page, new or recycled, opens at its top. */
        flinger.forceFinished(true)
        scrollTop = 0f
        revealPending = false

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        /* Word bounds were placed for the old size. */
        laidOut = false

        // Rotation keeps the top row, measured in rows; a lit word then takes priority
        val top = padTop + headBand
        val row = if (step > 0f && scrollTop > top) (scrollTop - top) / step else -1f
        flinger.forceFinished(true)
        layOut()
        scrollTop = if (row >= 0f) (top + row * step).coerceIn(0f, maxScroll) else 0f
        revealPending = litWord >= 0
    }

    override fun onDraw(canvas: Canvas) {
        // The view may have been cached when the style changed, with no bind to tell it
        if (dressedAt != Settings.styleVersion) dress()
        val glyphs = table ?: return
        if (lines.isEmpty()) return

        val wide = width - 2 * padX
        if (wide <= 0) return

        if (previewMode) {
            /* Type size = width / ems-per-full-line. Every line starts here and shrinks from it. */
            drawPreviewLine(canvas, glyphs, padX, wide, (wide / Mushaf.emWidth).toFloat())
            return
        }

        if (step <= 0f) layOut()

        val shot = shotToDraw()
        if (shot != null) {
            drawShot(canvas, shot)
            return
        }

        val top = padTop + headBand
        val left = padX
        val measure = wide

        /* Zoom about the screen, then the page slid up by however far it is scrolled. */
        val moved = zoom != 1f || scrollTop != 0f
        if (moved) canvas.save()
        if (zoom != 1f) {
            canvas.translate(panX, panY)
            canvas.scale(zoom, zoom, width / 2f, height / 2f)
        }
        if (scrollTop != 0f) canvas.translate(0f, -scrollTop)
        // Seam guard on for large type by zoom or by pixel size, moving frames included, or cracks show mid-pinch
        seamGuard(zoom > SEAM_ZOOM || body * zoom > SEAM_FROM_PX)
        drawFlash(canvas)
        runningHead(canvas, left, measure)
        folio(canvas)

        atSurah = Ayat.surahAt(pageNo)
        atAyah = Ayat.ayahAt(pageNo)
        atWord = Ayat.wordAt(pageNo)
        if (!laidOut) placed.clear()

        paint.textSize = body
        val centreOffset = -(paint.ascent() + paint.descent()) / 2f

        var slot = 0
        for (i in lines.indices) {
            if (slot >= GRID) break
            val line = lines[i]
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
                    drawLine(canvas, lineWords[i], glyphs, left, measure, body, y, step)
                }
            }
        }
        val firstLayout = !laidOut
        laidOut = true
        seamGuard(false)
        if (moved) canvas.restore()
        // Bounds only exist after the first draw, so the flash starts on the next frame
        if (firstLayout && flashAyah > 0) postInvalidateOnAnimation()
        if (flashReveal && laidOut) revealFlash()

        /* Now that the words are placed, bring a newly lit one into view if it is not. */
        if (revealPending) reveal()
    }

    /* Renders exactly one line (the first text line) centered vertically — used in settings preview. */
    private fun drawPreviewLine(canvas: Canvas, glyphs: Mushaf.Glyphs, left: Float, measure: Float, body: Float) {
        paint.textSize = body
        val centreOffset = -(paint.ascent() + paint.descent()) / 2f
        val y = height / 2f + centreOffset

        // The head and the folio are in the preview too, since they take the same colour as the ayah numbers
        previewLabels(canvas, left, measure)

        atSurah = Ayat.surahAt(pageNo)
        atAyah  = Ayat.ayahAt(pageNo)
        atWord  = Ayat.wordAt(pageNo)
        if (!laidOut) placed.clear()

        for (i in lines.indices) {
            val line = lines[i]
            when (line.kind) {
                "surah" -> if (line.surah > 0) { atSurah = line.surah; atAyah = 1; atWord = 0 }
                "basmalah" -> { /* skip */ }
                else -> if (line.words.isNotEmpty()) {
                    /* Auto-light the first word of the line so all style settings are visible. */
                    litSurah = atSurah
                    litAyah  = atAyah
                    litWord  = atWord
                    drawLine(canvas, lineWords[i], glyphs, left, measure, body, y, body)
                    laidOut = true
                    return
                }
            }
        }
    }

    // A page's figures, drawn small at the edges of the preview
    private fun previewLabels(canvas: Canvas, left: Float, measure: Float) {
        val edge = label.textSize * 0.9f
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(headJuz, left + measure, edge, label)
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(headPage, left, edge, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(folioText, left + measure / 2f, height - edge * 0.4f, label)
    }

    /* One line fitted to the measure, right to left. Short lines are centred. */
    /* [words] is the line's words, each joined from its parts — built once per page in show(). */
    private fun drawLine(
        canvas: Canvas,
        words: List<String>,
        glyphs: Mushaf.Glyphs,
        left: Float,
        measure: Float,
        body: Float,
        y: Float,
        slot: Float
    ) {

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
        val marks = pageMarks
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
                if (!laidOut) {
                    placed.add(floatArrayOf(
                        pen, began, y - slot * 0.44f, y + slot * 0.24f,
                        ofSurah.toFloat(), ofAyah.toFloat(), ofWord.toFloat()
                    ))
                }
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
            if (n > 0) drawRun(canvas, face, ids, spots, n, paint, inkSpread)
            if (m > 0) {
                markPaint.textSize = size
                drawRun(canvas, face, markIds, markSpots, m, markPaint, markSpread)
            }
            if (litN > 0) {
                litPaint.textSize = size
                drawRun(canvas, face, litIds, litSpots, litN, litPaint, litSpread)
            }
        } else {
            // API < 31 has no glyph runs, so it falls back to text and fake bold
            paint.isFakeBoldText = inkSpread > 0f
            markPaint.isFakeBoldText = markSpread > 0f
            markPaint.textSize = size
            markPaint.typeface = paint.typeface
            markPaint.textAlign = Paint.Align.LEFT
            litPaint.textSize = size
            litPaint.typeface = paint.typeface
            litPaint.textAlign = Paint.Align.LEFT
            litPaint.isFakeBoldText = litSpread > 0f
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

    // Weight by dilation: redraws the glyphs nudged each way. Fake bold grows outlines and tears overlapping glyphs
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawRun(
        canvas: Canvas,
        face: android.graphics.fonts.Font,
        glyphIds: IntArray,
        at: FloatArray,
        count: Int,
        pen: Paint,
        spread: Float
    ) {
        canvas.drawGlyphs(glyphIds, 0, at, 0, count, face, pen)
        if (spread <= 0f) return
        /* Divided by zoom so the spread is the same in screen pixels at any zoom. */
        val d = pen.textSize * spread / zoom
        nudge(canvas, face, glyphIds, at, count, pen, d, 0f)
        nudge(canvas, face, glyphIds, at, count, pen, -d, 0f)
        nudge(canvas, face, glyphIds, at, count, pen, 0f, d)
        nudge(canvas, face, glyphIds, at, count, pen, 0f, -d)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun nudge(
        canvas: Canvas,
        face: android.graphics.fonts.Font,
        glyphIds: IntArray,
        at: FloatArray,
        count: Int,
        pen: Paint,
        dx: Float,
        dy: Float
    ) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawGlyphs(glyphIds, 0, at, 0, count, face, pen)
        canvas.restore()
    }

    /* Running head: juz on the right, surah name centred, page number on the left. */
    private fun runningHead(canvas: Canvas, left: Float, measure: Float) {
        if (pageNo <= 0) return
        val y = padTop + headBand * 0.62f

        if (headJuz.isNotEmpty()) {
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(headJuz, left + measure, y, label)
        }

        if (headSurah > 0) title(canvas, headSurah, left + measure / 2f, headBand * 0.82f, y)

        label.textAlign = Paint.Align.LEFT
        canvas.drawText(headPage, left, y, label)
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

        /* Right to left: «سورة», then the name. */
        val pen = centre + total / 2f
        glyph(canvas, names, word, pen - wordW, y)
        glyph(canvas, names, name, pen - wordW - between - nameW, y)
    }

    private fun glyph(canvas: Canvas, names: Mushaf.Glyphs, cp: Int, x: Float, y: Float) {
        val id = names.id(cp)
        if (id < 0) return
        val face = Mushaf.nameFace(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && face != null) {
            oneId[0] = id
            oneSpot[0] = x
            oneSpot[1] = y
            canvas.drawGlyphs(oneId, 0, oneSpot, 0, 1, face, titlePaint)
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
        /* At the foot of the page, which on a scrolling page is below the screen. */
        canvas.drawText(folioText, width / 2f, pageTall - padBottom / 2f, label)
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
        /** The page whose first line stands in as the style preview: An-Nahl, 273. */
        private const val FLASH_HOLD_MS = 2200L
        private const val FLASH_FADE_MS = 800L
        private const val FLASH_ALPHA = 0x55
        const val PREVIEW_PAGE = 273

        /** Lines to a mushaf page. */
        const val GRID = 15

        // Type-to-row ratio past which a page scrolls: portrait phones are 0.44-0.56, landscape about 2.6
        const val TALL_FROM = 0.6f

        // Row proportion for scrolling pages: a portrait phone's, so landscape keeps the same line spacing
        const val READING_ROW = 0.45f

        /** How long bringing a lit word into view takes, in ms: quick enough to keep up with recitation. */
        const val REVEAL_MS = 280

        /** How far each bold pass is offset, as a fraction of the type size. */
        const val BOLD_SPREAD = 0.018f

        /** The spread for each weight, indexed by Settings.WEIGHT_*: regular, light, medium, bold. */
        private val WEIGHTS = floatArrayOf(0f, 0.006f, 0.011f, BOLD_SPREAD)

        /* A weight as a dilation spread; anything unknown draws regular. */
        private fun spread(weight: Int) = WEIGHTS.getOrElse(weight) { 0f }

        /** Furthest the page may be pinched. Past 3x the glyphs gain nothing. */
        const val MAX_ZOOM = 3f

        // Letting go of a pinch in below this zoom returns to the whole page
        const val SNAP_BACK_ZOOM = 1.4f
        // Below this any pinch ends at the whole page
        const val SNAP_EXACT = 1.05f
        const val SETTLE_MS = 200L

        // On-screen type size where glyph seams start to show
        const val SEAM_FROM_PX = 76f

        /** Zoom past which the seam guard comes on whatever the type's size — the original rule, kept for small screens. */
        const val SEAM_ZOOM = 1.2f

        /** Outward stroke that closes that seam, in screen pixels. */
        const val SEAM_STROKE = 0.35f

        // --- layout tuning (dp) ---

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

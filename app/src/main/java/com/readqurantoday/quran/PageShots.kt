package com.readqurantoday.quran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors

/**
 * Pages drawn ahead as finished images, so turning and zooming move pictures rather
 * than drawing type.
 *
 * A page drawn live is stroked glyph by glyph — the seam guard that keeps joined
 * letters from cracking makes every glyph an outline, which the glyph cache cannot
 * hold — and a swipe redrew all of that on every frame: on a 1440px phone the slow
 * frames reached 53 to 69ms. Drawn once, with the guard, into a GPU bitmap, a page
 * costs a single texture draw per frame after that, and nothing in it can crack when
 * it is moved or scaled, because nothing in it is being drawn.
 *
 * The page in view and the ones either side are kept. Each is recorded on the main
 * thread as a Picture — laying out the lines, a few ms, done one page per message
 * while the reader is still, never mid-swipe — and rasterised into a hardware bitmap
 * on a background thread. A shot is only used while it still matches: the same page,
 * the same size, and the same style version, so a change of colour or weight is never
 * shown stale. [landed] hears which page has a fresh shot, to redraw it.
 */
class PageShots(private val context: Context, private val landed: (Int) -> Unit) {

    private class Shot(val w: Int, val h: Int, val style: Int, val bitmap: Bitmap)

    private val shots = HashMap<Int, Shot>()
    private val waiting = ArrayDeque<Int>()
    private val underway = HashSet<Int>()
    private var w = 0
    private var h = 0

    /* A page view never attached to anything, used only to record pages into Pictures. */
    private val painter by lazy { MushafPageView(context) }
    private val main = Handler(Looper.getMainLooper())
    private val raster = Executors.newSingleThreadExecutor { r -> Thread(r, "page-shots") }

    /** The finished image of [page] at this size and style, or null to draw it live. */
    fun get(page: Int, width: Int, height: Int): Bitmap? {
        val s = shots[page] ?: return null
        return if (s.w == width && s.h == height && s.style == Settings.styleVersion) s.bitmap else null
    }

    /**
     * Have [page] and its neighbours ready at [width] × [height]. Call when the reader
     * settles on a page. Far pages are let go — each image is a screen of GPU memory.
     */
    fun around(page: Int, width: Int, height: Int) {
        /* Recording to a hardware bitmap arrived in Android 9; below that pages are live. */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (width <= 0 || height <= 0) return
        w = width
        h = height

        val near = (page - 1..page + 1).filter { it in 1..Mushaf.PAGES }
        shots.keys.retainAll(near.toSet())

        waiting.clear()
        /* The page in view first, then the ones a swipe will bring. */
        for (p in listOf(page, page + 1, page - 1)) {
            if (p in near && get(p, w, h) == null && p !in underway) waiting.addLast(p)
        }
        next()
    }

    private fun next() {
        val page = waiting.removeFirstOrNull() ?: return
        underway += page
        /* Posted, so each page's recording is its own short piece of main-thread work
           rather than three laid end to end. */
        main.post {
            val width = w
            val height = h
            val style = Settings.styleVersion
            val picture = record(page, width, height)
            raster.execute {
                val bitmap = try {
                    Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE)
                } catch (_: Exception) {
                    null
                }
                main.post {
                    underway -= page
                    if (bitmap != null) {
                        shots[page] = Shot(width, height, style, bitmap)
                        landed(page)
                    }
                    next()
                }
            }
        }
    }

    /**
     * The image of [page] now, made on the spot if it is not ready: for a page turn that
     * has begun, which cannot wait. One page's worth of main-thread work, once; null
     * below Android 9.
     */
    fun now(page: Int, width: Int, height: Int): Bitmap? {
        get(page, width, height)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || width <= 0 || height <= 0) return null
        val style = Settings.styleVersion
        val bitmap = try {
            Bitmap.createBitmap(record(page, width, height), width, height, Bitmap.Config.HARDWARE)
        } catch (_: Exception) {
            return null
        }
        shots[page] = Shot(width, height, style, bitmap)
        return bitmap
    }

    /* The page as the reader draws it at rest: unzoomed, unscrolled, no word lit. */
    private fun record(page: Int, width: Int, height: Int): Picture {
        painter.show(page)
        painter.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        painter.layout(0, 0, width, height)
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        painter.draw(canvas)
        picture.endRecording()
        return picture
    }
}

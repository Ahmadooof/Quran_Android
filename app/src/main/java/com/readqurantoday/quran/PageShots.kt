package com.readqurantoday.quran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors

// Pages pre-rendered to hardware bitmaps so swipes and zoom move images instead of stroked glyphs
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

    // Keeps only this page and its neighbours: each shot is a screen of GPU memory
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

    @RequiresApi(Build.VERSION_CODES.P)
    private fun next() {
        val page = waiting.removeFirstOrNull() ?: return
        underway += page
        // One page recorded per message, so the main thread never does three at once
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

    // Made on the spot for a page turn that cannot wait; null below Android 9
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

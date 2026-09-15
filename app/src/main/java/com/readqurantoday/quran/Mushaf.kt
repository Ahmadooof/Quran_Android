package com.readqurantoday.quran

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** The mushaf: 604 pages of lines and a per-page font. */
object Mushaf {

    /** Pages in the mushaf. */
    const val PAGES = 604

    /** A word is a list because some Arabic words contain an internal space (two glyphs). */
    data class Line(val kind: String, val words: List<List<String>>, val surah: Int = 0)

    /** Glyph ids and advances for one page, pre-extracted from the font. */
    class Glyphs(val upem: Float, private val gid: HashMap<Int, Int>, private val adv: HashMap<Int, Int>) {
        fun id(cp: Int): Int = gid[cp] ?: -1
        fun advance(cp: Int): Float = (adv[cp] ?: 0).toFloat()
    }

    /** How wide a full line is in ems of its own type size. */
    var emWidth = 15.98
        private set

    /** Lines narrower than this fraction of the measure are centred, not justified. */
    var centreBelow = 0.92
        private set

    var basmalah: List<String> = emptyList()
        private set
    var basmalahPage = 1
        private set

    private var pages: JSONObject? = null
    private var marks: JSONObject? = null
    /*
      Concurrent maps, not plain ones. Pages are loaded ahead on a background thread
      (warm), and at startup the ayah map walks every page on another (Ayat.build),
      while the pager reads them on the main thread and keepOnly prunes them there.
      A plain HashMap written and read, or pruned mid-write, across threads can load
      a font twice, lose an entry, or break outright — part of a page turn's stutter.
    */
    private val faces = ConcurrentHashMap<Int, Typeface>()
    private val tables = ConcurrentHashMap<Int, Glyphs>()
    private var nameTable: Glyphs? = null
    private var nameFont: android.graphics.fonts.Font? = null
    private var nameFamily: Typeface? = null
    private val fonts = ConcurrentHashMap<Int, android.graphics.fonts.Font>()

    /* Parsed lines cached to avoid re-parsing on every page turn. */
    private val parsed = ConcurrentHashMap<Int, List<Line>>()

    /* Below normal, so it never competes with drawing, but not the lowest: at the
       lowest it could lag a quick run of swipes and leave the page arriving unloaded. */
    private val ahead = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mushaf-ahead").apply { priority = Thread.NORM_PRIORITY - 2 }
    }

    /** How many pages either side of the one in view are loaded ahead. */
    const val AHEAD = 3

    fun load(context: Context) {
        if (pages != null) return

        val json = context.assets.open("data/mushaf.json").use { it.readBytes() }
        val root = JSONObject(String(json, Charsets.UTF_8))

        pages = root.getJSONObject("pages")
        marks = root.optJSONObject("marks")

        /* Basmalah is the same four words wherever it appears; stored once. */
        root.optJSONObject("basmalah")?.let { b ->
            basmalahPage = b.optInt("page", 1)
            val text = b.optString("v2", "")
            basmalah = if (text.isEmpty()) emptyList() else text.split('|').filter { it.isNotEmpty() }
        }

        root.optJSONObject("fit")?.let { fit ->
            fit.optJSONObject("body")?.let { emWidth = it.optDouble("v2", emWidth) }
            fit.optJSONObject("centreBelow")?.let { centreBelow = it.optDouble("v2", centreBelow) }
        }
    }

    @Synchronized
    fun lines(page: Int): List<Line> {
        parsed[page]?.let { return it }

        val all = pages ?: return emptyList()
        val arr = all.optJSONArray(page.toString()) ?: return emptyList()

        val out = ArrayList<Line>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("t")
            /* v2 = this edition; the file also carries v1 for the older one. */
            val text = o.optString("v2", "")
            out.add(
                Line(
                    kind = kind,
                    words = if (text.isEmpty()) emptyList()
                            else text.split('|').map { w -> w.split(' ').filter { it.isNotEmpty() } },
                    surah = o.optInt("s", 0)
                )
            )
        }
        parsed[page] = out
        return out
    }

    /** Pre-load pages around the current one on a background thread to avoid jank. */
    /*
      Load the pages around [page] off the main thread, nearest first, so the page a
      swipe brings in is already loaded when it binds. A page loaded on the main
      thread mid-swipe means its TTF parsed twice and its glyph table read, all
      inside the animation. Each loader returns at once for a page already cached,
      so warming the same neighbourhood again costs almost nothing.
    */
    fun warm(context: Context, page: Int) {
        val ctx = context.applicationContext
        ahead.execute {
            for (p in nearestFirst(page)) {
                if (p in 1..604) {
                    lines(p)
                    face(ctx, p)
                    glyphs(ctx, p)
                    font(ctx, p)
                }
            }
        }
    }

    /* page, page+1, page-1, page+2, ... out to AHEAD either side: the next page to
       arrive, whichever way the reader is going, is loaded before the far ones. */
    private fun nearestFirst(page: Int): List<Int> {
        val out = ArrayList<Int>(AHEAD * 2 + 1)
        out.add(page)
        for (d in 1..AHEAD) {
            out.add(page + d)
            out.add(page - d)
        }
        return out
    }

    @Synchronized
    fun face(context: Context, page: Int): Typeface? {
        faces[page]?.let { return it }
        return try {
            val t = Typeface.createFromAsset(context.assets, "p$page.ttf")
            faces[page] = t
            t
        } catch (_: RuntimeException) {
            null
        }
    }

    @Synchronized
    fun glyphs(context: Context, page: Int): Glyphs? {
        tables[page]?.let { return it }
        val g = read(context, "g$page.txt") ?: return null
        tables[page] = g
        return g
    }

    /** The ayah-marker glyphs on this page, as a string for contains() checks. */
    fun marksOn(page: Int): String = marks?.optString(page.toString(), "").orEmpty()

    private fun read(context: Context, asset: String): Glyphs? {
        return try {
            val text = context.assets.open(asset).use { it.readBytes() }
            val rows = String(text, Charsets.US_ASCII).lineSequence()
            var upem = 0f
            val gid = HashMap<Int, Int>(512)
            val adv = HashMap<Int, Int>(512)
            for (row in rows) {
                if (row.isEmpty()) continue
                if (upem == 0f) { upem = row.trim().toFloat(); continue }
                val a = row.indexOf(' ')
                val b = row.indexOf(' ', a + 1)
                if (a < 0 || b < 0) continue
                val cp = row.substring(0, a).toInt()
                gid[cp] = row.substring(a + 1, b).toInt()
                adv[cp] = row.substring(b + 1).trim().toInt()
            }
            Glyphs(upem, gid, adv)
        } catch (_: Exception) {
            null
        }
    }

    /* Font (for drawGlyphs) vs Typeface (for drawText): same file, different handle. */
    @Synchronized
    fun font(context: Context, page: Int): android.graphics.fonts.Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        fonts[page]?.let { return it }
        return try {
            val f = android.graphics.fonts.Font.Builder(context.assets, "p$page.ttf").build()
            fonts[page] = f
            f
        } catch (_: Exception) {
            null
        }
    }

    /** The surah names font: 114 names + the word سورة, addressed by surah number. */
    @Synchronized
    fun names(context: Context): Glyphs? {
        nameTable?.let { return it }
        nameTable = read(context, "gsura-names.txt")
        return nameTable
    }

    @Synchronized
    fun nameFace(context: Context): android.graphics.fonts.Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        nameFont?.let { return it }
        nameFont = try {
            android.graphics.fonts.Font.Builder(context.assets, "sura-names.ttf").build()
        } catch (_: Exception) {
            null
        }
        return nameFont
    }

    @Synchronized
    fun nameTypeface(context: Context): Typeface? {
        nameFamily?.let { return it }
        nameFamily = try {
            Typeface.createFromAsset(context.assets, "sura-names.ttf")
        } catch (_: RuntimeException) {
            null
        }
        return nameFamily
    }

    /** Codepoint for surah N in the names face: E004 for surah 4, E114 for 114. */
    fun nameCode(surah: Int): Int = 0xE000 + Integer.parseInt(surah.toString().padStart(3, '0'), 16)

    /** The word سورة in the names face. */
    const val SURAH_WORD = 0xE000

    /** Release pages outside this range; keep the Basmalah page always. */
    @Synchronized
    fun keepOnly(near: IntRange) {
        fun spare(p: Int) = p in near || p == basmalahPage

        val f = faces.keys.iterator()
        while (f.hasNext()) if (!spare(f.next())) f.remove()

        val o = fonts.keys.iterator()
        while (o.hasNext()) if (!spare(o.next())) o.remove()

        val t = tables.keys.iterator()
        while (t.hasNext()) if (!spare(t.next())) t.remove()

        /* Keep a wider window of parsed lines — they're small. */
        val wide = (near.first - 8)..(near.last + 8)
        val l = parsed.keys.iterator()
        while (l.hasNext()) if (l.next() !in wide) l.remove()
    }
}

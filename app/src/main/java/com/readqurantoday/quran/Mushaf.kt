package com.readqurantoday.quran

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** The mushaf (QCF v4, Madinah 1441): 604 pages of lines, each drawn from one of 47 fonts. */
object Mushaf {

    /** Pages in the mushaf. */
    const val PAGES = 604

    // The edition's key in mushaf.json
    private const val EDITION = "v4"

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

    /** A page drawn from the Basmalahs' font, so their glyphs are read through that page's handles. */
    var basmalahPage = 1
        private set

    private var pages: JSONObject? = null
    private var marks: JSONObject? = null
    // Which of the 47 fonts each page is drawn from, by page - 1
    private var fontOfPage = IntArray(0)
    // Keyed by font; concurrent, as pages load on background threads while the pager reads and prunes on main
    private val faces = ConcurrentHashMap<Int, Typeface>()
    private val tables = ConcurrentHashMap<Int, Glyphs>()
    private var nameTable: Glyphs? = null
    private var nameFont: android.graphics.fonts.Font? = null
    private var nameFamily: Typeface? = null
    private var headTable: Glyphs? = null
    private var headFont: android.graphics.fonts.Font? = null
    private var headFamily: Typeface? = null
    private val fonts = ConcurrentHashMap<Int, android.graphics.fonts.Font>()

    /* Parsed lines cached to avoid re-parsing on every page turn. */
    private val parsed = ConcurrentHashMap<Int, List<Line>>()

    // Below normal priority, but not lowest, or a quick run of swipes outpaces it
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
        root.getJSONArray("fonts").let { all -> fontOfPage = IntArray(all.length()) { all.getInt(it) } }

        /* Each Basmalah line holds its own glyph; all of them live in one font. */
        root.optJSONObject("basmalah")?.let { b ->
            val font = b.optInt("font", 1)
            basmalahPage = (fontOfPage.indexOfFirst { it == font } + 1).coerceAtLeast(1)
        }

        root.optJSONObject("fit")?.let { fit ->
            fit.optJSONObject("body")?.let { emWidth = it.optDouble(EDITION, emWidth) }
            fit.optJSONObject("centreBelow")?.let { centreBelow = it.optDouble(EDITION, centreBelow) }
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
            val text = o.optString(EDITION, "")
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
    // Loads nearby pages off the main thread so a swipe never parses a font mid-animation
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

    // Nearest pages first in both directions
    private fun nearestFirst(page: Int): List<Int> {
        val out = ArrayList<Int>(AHEAD * 2 + 1)
        out.add(page)
        for (d in 1..AHEAD) {
            out.add(page + d)
            out.add(page - d)
        }
        return out
    }

    // The font a page is drawn from; neighbouring pages mostly share one
    private fun fontOf(page: Int): Int = fontOfPage.getOrElse(page - 1) { 1 }

    @Synchronized
    fun face(context: Context, page: Int): Typeface? {
        val n = fontOf(page)
        faces[n]?.let { return it }
        return try {
            val t = Typeface.createFromAsset(context.assets, "q$n.ttf")
            faces[n] = t
            t
        } catch (_: RuntimeException) {
            null
        }
    }

    @Synchronized
    fun glyphs(context: Context, page: Int): Glyphs? {
        val n = fontOf(page)
        tables[n]?.let { return it }
        val g = read(context, "gq$n.txt") ?: return null
        tables[n] = g
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
        val n = fontOf(page)
        fonts[n]?.let { return it }
        return try {
            val f = android.graphics.fonts.Font.Builder(context.assets, "q$n.ttf").build()
            fonts[n] = f
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

    /** The surah titles font (QCF4 BSML): each surah's printed title is one glyph. */
    @Synchronized
    fun heads(context: Context): Glyphs? {
        headTable?.let { return it }
        headTable = read(context, "gqh.txt")
        return headTable
    }

    @Synchronized
    fun headFace(context: Context): android.graphics.fonts.Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        headFont?.let { return it }
        headFont = try {
            android.graphics.fonts.Font.Builder(context.assets, "qh.ttf").build()
        } catch (_: Exception) {
            null
        }
        return headFont
    }

    @Synchronized
    fun headTypeface(context: Context): Typeface? {
        headFamily?.let { return it }
        headFamily = try {
            Typeface.createFromAsset(context.assets, "qh.ttf")
        } catch (_: RuntimeException) {
            null
        }
        return headFamily
    }

    /** Surah N's printed title in the titles font. */
    fun headCode(surah: Int): Int = 0xF100 + surah - 1

    /** Codepoint for surah N in the names face: E004 for surah 4, E114 for 114. */
    fun nameCode(surah: Int): Int = 0xE000 + Integer.parseInt(surah.toString().padStart(3, '0'), 16)

    /** The word سورة in the names face. */
    const val SURAH_WORD = 0xE000

    /** Release the fonts no page in this range uses; keep the Basmalah's always. */
    @Synchronized
    fun keepOnly(near: IntRange) {
        val used = near.map { fontOf(it) }.toSet() + fontOf(basmalahPage)
        faces.keys.retainAll(used)
        fonts.keys.retainAll(used)
        tables.keys.retainAll(used)

        /* Keep a wider window of parsed lines — they're small. */
        val wide = (near.first - 8)..(near.last + 8)
        val l = parsed.keys.iterator()
        while (l.hasNext()) if (l.next() !in wide) l.remove()
    }
}

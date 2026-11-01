package com.readqurantoday.quran

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Where an edition's page fonts come from, by font number. */
interface FontSource {
    fun font(context: Context, n: Int): Font?
    fun face(context: Context, n: Int): Typeface?
}

/**
 * One edition of the mushaf's 604 pages: their lines in [asset] under [key], and the glyph tables [table] names by font number.
 * Lines are words of glyph codes; each page is drawn from one of the edition's fonts.
 */
class Book(private val asset: String, private val key: String, private val table: (Int) -> String, private val source: FontSource) {

    /** A word is a list because some Arabic words contain an internal space (two glyphs). */
    data class Line(val kind: String, val words: List<List<String>>, val surah: Int = 0)

    /** Glyph ids and advances for one font, pre-extracted from it. */
    class Glyphs(val upem: Float, private val gid: HashMap<Int, Int>, private val adv: HashMap<Int, Int>) {
        fun id(cp: Int): Int = gid[cp] ?: -1
        fun advance(cp: Int): Float = (adv[cp] ?: 0).toFloat()
    }

    /** How wide a full line is in ems of its own type size. */
    var emWidth = 16.0
        private set

    /** Lines narrower than this fraction of the measure are centred, not justified. */
    var centreBelow = 0.92
        private set

    /** A page drawn from the Basmalahs' font, so their glyphs are read through that page's handles. */
    var basmalahPage = 1
        private set

    private var pages: JSONObject? = null
    private var marks: JSONObject? = null
    // Which font each page is drawn from, by page - 1
    private var fontOfPage = IntArray(0)
    // Keyed by font; concurrent, as pages load on background threads while the pager reads and prunes on main
    private val tables = ConcurrentHashMap<Int, Glyphs>()
    /* Parsed lines cached to avoid re-parsing on every page turn. */
    private val parsed = ConcurrentHashMap<Int, List<Line>>()

    @Synchronized
    fun load(context: Context) {
        if (pages != null) return
        val json = context.assets.open(asset).use { it.readBytes() }
        val root = JSONObject(String(json, Charsets.UTF_8))
        marks = root.optJSONObject("marks")
        root.getJSONArray("fonts").let { all -> fontOfPage = IntArray(all.length()) { all.getInt(it) } }
        /* Each Basmalah line holds its own glyphs; all of them live in one font. */
        root.optJSONObject("basmalah")?.let { b ->
            val font = b.optInt("font", 1)
            basmalahPage = (fontOfPage.indexOfFirst { it == font } + 1).coerceAtLeast(1)
        }
        root.optJSONObject("fit")?.let { fit ->
            fit.optJSONObject("body")?.let { emWidth = it.optDouble(key, emWidth) }
            fit.optJSONObject("centreBelow")?.let { centreBelow = it.optDouble(key, centreBelow) }
        }
        pages = root.getJSONObject("pages")
    }

    @Synchronized
    fun lines(page: Int): List<Line> {
        parsed[page]?.let { return it }
        val arr = pages?.optJSONArray(page.toString()) ?: return emptyList()
        val out = ArrayList<Line>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString(key, "")
            out.add(Line(
                kind = o.optString("t"),
                words = if (text.isEmpty()) emptyList() else text.split('|').map { w -> w.split(' ').filter { it.isNotEmpty() } },
                surah = o.optInt("s", 0)
            ))
        }
        parsed[page] = out
        return out
    }

    /** The ayah-marker glyphs on this page, as a string for contains() checks. */
    fun marksOn(page: Int): String = marks?.optString(page.toString(), "").orEmpty()

    // The font a page is drawn from; neighbouring pages often share one
    fun fontOf(page: Int): Int = fontOfPage.getOrElse(page - 1) { 1 }

    fun font(context: Context, page: Int): Font? = source.font(context, fontOf(page))

    fun face(context: Context, page: Int): Typeface? = source.face(context, fontOf(page))

    @Synchronized
    fun glyphs(context: Context, page: Int): Glyphs? {
        val n = fontOf(page)
        tables[n]?.let { return it }
        val g = readGlyphs(context, table(n)) ?: return null
        tables[n] = g
        return g
    }

    /** Release what no page in this range uses; keep the Basmalahs' always. */
    @Synchronized
    fun keepOnly(near: IntRange) {
        val used = near.map { fontOf(it) }.toSet() + fontOf(basmalahPage)
        tables.keys.retainAll(used)
        /* Keep a wider window of parsed lines — they're small. */
        val wide = (near.first - 8)..(near.last + 8)
        parsed.keys.retainAll { it in wide }
    }
}

/** A font's glyph table from its asset: units per em, then a row per code of its glyph id and advance. */
fun readGlyphs(context: Context, asset: String): Book.Glyphs? = try {
    val rows = String(context.assets.open(asset).use { it.readBytes() }, Charsets.US_ASCII).lineSequence()
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
    Book.Glyphs(upem, gid, adv)
} catch (_: Exception) {
    null
}

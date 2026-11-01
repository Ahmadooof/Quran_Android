package com.readqurantoday.quran

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/** The mushaf: its plain edition (QCF v4, Madinah 1441, 47 fonts) and its Tajweed edition, and the surah titles' fonts. */
object Mushaf {

    /** Pages in the mushaf. */
    const val PAGES = 604

    /** How many pages either side of the one in view are loaded ahead. */
    const val AHEAD = 3

    // The plain edition's fonts ship in the app's assets
    private object Packed : FontSource {
        val fonts = ConcurrentHashMap<Int, Font>()
        val faces = ConcurrentHashMap<Int, Typeface>()

        override fun font(context: Context, n: Int): Font? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            fonts[n]?.let { return it }
            return try {
                Font.Builder(context.assets, "q$n.ttf").build().also { fonts[n] = it }
            } catch (_: Exception) {
                null
            }
        }

        override fun face(context: Context, n: Int): Typeface? {
            faces[n]?.let { return it }
            return try {
                Typeface.createFromAsset(context.assets, "q$n.ttf").also { faces[n] = it }
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    /** The plain edition, which every page has. */
    val plain = Book("data/mushaf.json", "v4", { "gq$it.txt" }, Packed)

    /** The Tajweed edition: the 1421 lines, coloured, each page's font downloaded on first need. */
    val tajweed = Book("data/mushaf-tajweed.json", "v2", { "gt$it.txt" }, TajweedFonts)

    private var nameTable: Book.Glyphs? = null
    private var nameFont: Font? = null
    private var nameFamily: Typeface? = null
    private var headTable: Book.Glyphs? = null
    private var headFont: Font? = null
    private var headFamily: Typeface? = null

    // Below normal priority, but not lowest, or a quick run of swipes outpaces it
    private val ahead = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mushaf-ahead").apply { priority = Thread.NORM_PRIORITY - 2 }
    }

    fun load(context: Context) {
        plain.load(context)
        tajweed.load(context)
    }

    // The plain edition, as most of the app reads it
    val emWidth get() = plain.emWidth
    val basmalahPage get() = plain.basmalahPage
    fun lines(page: Int) = plain.lines(page)
    fun marksOn(page: Int) = plain.marksOn(page)
    fun glyphs(context: Context, page: Int) = plain.glyphs(context, page)
    fun font(context: Context, page: Int) = plain.font(context, page)
    fun face(context: Context, page: Int) = plain.face(context, page)

    /** Pre-load pages around the current one on a background thread to avoid jank. */
    fun warm(context: Context, page: Int, book: Book = plain) {
        val ctx = context.applicationContext
        ahead.execute {
            for (p in nearestFirst(page)) {
                if (p in 1..PAGES) {
                    book.lines(p)
                    book.glyphs(ctx, p)
                    book.face(ctx, p)
                    book.font(ctx, p)
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

    /** Release the fonts no page in this range uses; keep the Basmalahs' always. */
    fun keepOnly(near: IntRange) {
        plain.keepOnly(near)
        tajweed.keepOnly(near)
        val used = near.map { plain.fontOf(it) }.toSet() + plain.fontOf(plain.basmalahPage)
        Packed.fonts.keys.retainAll(used)
        Packed.faces.keys.retainAll(used)
        TajweedFonts.keepOnly(near)
    }

    /** The surah names font: 114 names + the word سورة, addressed by surah number. */
    @Synchronized
    fun names(context: Context): Book.Glyphs? {
        nameTable?.let { return it }
        nameTable = readGlyphs(context, "gsura-names.txt")
        return nameTable
    }

    @Synchronized
    fun nameFace(context: Context): Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        nameFont?.let { return it }
        nameFont = try {
            Font.Builder(context.assets, "sura-names.ttf").build()
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
    fun heads(context: Context): Book.Glyphs? {
        headTable?.let { return it }
        headTable = readGlyphs(context, "gqh.txt")
        return headTable
    }

    @Synchronized
    fun headFace(context: Context): Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        headFont?.let { return it }
        headFont = try {
            Font.Builder(context.assets, "qh.ttf").build()
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

    /** The title glyphs' full height with their marks, in ems, and how far its middle stands above the baseline. */
    const val HEAD_TALL = 1.54f
    const val HEAD_RISE = 0.2f

    /** Codepoint for surah N in the names face: E004 for surah 4, E114 for 114. */
    fun nameCode(surah: Int): Int = 0xE000 + Integer.parseInt(surah.toString().padStart(3, '0'), 16)

    /** The word سورة in the names face. */
    const val SURAH_WORD = 0xE000
}

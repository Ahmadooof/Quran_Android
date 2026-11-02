package com.readqurantoday.quran

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The Tajweed edition's page fonts (King Fahd Complex, coloured by rule), fetched one page at a time as they are first needed.
 * Each is rewritten for the screen: its plain letters take the reader's ink, and by night it takes the font's own night colours.
 */
object TajweedFonts : FontSource {

    private const val SOURCE = "https://static-cdn.tarteel.ai/qul/fonts/quran_fonts/v4-tajweed/ttf/p%d.ttf"
    private const val FOLDER = "tajweed"
    // The palette slots of the plain letters, which then follow the paint
    private val LETTERS = setOf(0, 13, 14)
    private const val FOREGROUND = 0xFFFF
    private const val NIGHT_PALETTE = 1

    /** Heard on the main thread when a page's font has arrived. */
    var arrived: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val fetching = Executors.newFixedThreadPool(2)
    private val asked = ConcurrentHashMap.newKeySet<Int>()
    // Keyed by page and by day or night
    private val fonts = ConcurrentHashMap<Int, Font>()
    private val faces = ConcurrentHashMap<Int, Typeface>()

    private fun folder(context: Context) = File(context.filesDir, FOLDER)
    private fun file(context: Context, page: Int) = File(folder(context), "p$page.ttf")

    /** Whether [page] can be drawn in colour now. */
    fun has(context: Context, page: Int) = file(context, page).exists()

    /** Fetches the fonts of [pages] not yet here, nearest first; [arrived] hears each. */
    fun fetch(context: Context, pages: List<Int>) {
        val app = context.applicationContext
        for (page in pages) {
            if (page !in 1..Mushaf.PAGES || has(app, page) || !asked.add(page)) continue
            fetching.execute {
                if (download(app, page)) main.post { arrived?.invoke() }
                asked.remove(page)
            }
        }
    }

    private fun download(context: Context, page: Int): Boolean {
        val target = file(context, page)
        val part = File(target.path + ".part")
        return try {
            target.parentFile?.mkdirs()
            val link = URL(SOURCE.format(page)).openConnection() as HttpURLConnection
            link.connectTimeout = 15_000
            link.readTimeout = 30_000
            if (link.responseCode != HttpURLConnection.HTTP_OK) return false
            link.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            part.renameTo(target)
        } catch (_: Exception) {
            part.delete()
            false
        }
    }

    private fun night(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun keyOf(context: Context, page: Int) = page * 2 + if (night(context)) 1 else 0

    // The page's font as the screen wants it: written once into the cache, by day and by night
    private fun ready(context: Context, page: Int): File? {
        if (!has(context, page)) return null
        val dark = night(context)
        val out = File(context.cacheDir, "$FOLDER/p$page-${if (dark) "night" else "day"}.ttf")
        if (out.exists()) return out
        return try {
            out.parentFile?.mkdirs()
            out.writeBytes(recolour(file(context, page).readBytes(), dark))
            out
        } catch (_: Exception) {
            null
        }
    }

    override fun font(context: Context, n: Int): Font? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val key = keyOf(context, n)
        fonts[key]?.let { return it }
        val file = ready(context, n) ?: return null
        return try {
            Font.Builder(file).build().also { fonts[key] = it }
        } catch (_: Exception) {
            null
        }
    }

    override fun face(context: Context, n: Int): Typeface? {
        val key = keyOf(context, n)
        faces[key]?.let { return it }
        val file = ready(context, n) ?: return null
        return try {
            Typeface.createFromFile(file).also { faces[key] = it }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Lets go of the fonts of pages away from [near]; the first page's, which holds the Basmalah, stays. */
    fun keepOnly(near: IntRange) {
        fonts.keys.retainAll { it / 2 in near || it / 2 == 1 }
        faces.keys.retainAll { it / 2 in near || it / 2 == 1 }
    }

    /** Forgets every downloaded page, freeing their space. */
    fun clear(context: Context) {
        fonts.clear()
        faces.clear()
        folder(context).deleteRecursively()
        File(context.cacheDir, FOLDER).deleteRecursively()
    }

    /** Bytes the downloaded pages take. */
    fun size(context: Context): Long = folder(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** How many of the 604 pages are here. */
    fun count(context: Context): Int = folder(context).listFiles()?.count { it.name.endsWith(".ttf") } ?: 0

    // Each page's glyphs and the palette slots their colour layers use, read once from its font
    private val slots = ConcurrentHashMap<Int, Map<Int, Set<Int>>>()

    /** The palette slots [glyphIds] on [page] are coloured with: the Tajweed rules they carry. */
    fun slotsOf(context: Context, page: Int, glyphIds: List<Int>): Set<Int> {
        val all = slots.getOrPut(page) {
            if (!has(context, page)) return emptySet()
            layersOf(file(context, page).readBytes())
        }
        return glyphIds.flatMap { all[it].orEmpty() }.toSet()
    }

    // COLR v0: each coloured glyph's layers, as glyph and the palette slot each layer is painted with
    private fun layersOf(bytes: ByteArray): Map<Int, Set<Int>> {
        val b = ByteBuffer.wrap(bytes)
        var colr = -1
        for (i in 0 until (b.getShort(4).toInt() and 0xFFFF)) {
            val record = 12 + 16 * i
            if (String(bytes, record, 4, Charsets.US_ASCII) == "COLR") colr = b.getInt(record + 8)
        }
        if (colr < 0) return emptyMap()
        val bases = colr + b.getInt(colr + 4)
        val layers = colr + b.getInt(colr + 8)
        val out = HashMap<Int, Set<Int>>()
        for (i in 0 until (b.getShort(colr + 2).toInt() and 0xFFFF)) {
            val at = bases + i * 6
            val glyph = b.getShort(at).toInt() and 0xFFFF
            val first = b.getShort(at + 2).toInt() and 0xFFFF
            val count = b.getShort(at + 4).toInt() and 0xFFFF
            out[glyph] = (first until first + count).map { b.getShort(layers + it * 4 + 2).toInt() and 0xFFFF }.toSet()
        }
        return out
    }

    // The font's colour layers: plain letters follow the paint, and by night the night palette stands in for the day's
    private fun recolour(bytes: ByteArray, dark: Boolean): ByteArray {
        val b = ByteBuffer.wrap(bytes)
        var colr = -1
        var cpal = -1
        for (i in 0 until (b.getShort(4).toInt() and 0xFFFF)) {
            val record = 12 + 16 * i
            when (String(bytes, record, 4, Charsets.US_ASCII)) {
                "COLR" -> colr = b.getInt(record + 8)
                "CPAL" -> cpal = b.getInt(record + 8)
            }
        }
        if (colr >= 0) {
            val layers = colr + b.getInt(colr + 8)
            for (i in 0 until (b.getShort(colr + 12).toInt() and 0xFFFF)) {
                val slot = layers + i * 4 + 2
                if ((b.getShort(slot).toInt() and 0xFFFF) in LETTERS) b.putShort(slot, FOREGROUND.toShort())
            }
        }
        if (dark && cpal >= 0) {
            val entries = b.getShort(cpal + 2).toInt() and 0xFFFF
            val palettes = b.getShort(cpal + 4).toInt() and 0xFFFF
            if (palettes > NIGHT_PALETTE) {
                val records = cpal + b.getInt(cpal + 8)
                val day = records + 4 * (b.getShort(cpal + 12).toInt() and 0xFFFF)
                val nightAt = records + 4 * (b.getShort(cpal + 12 + 2 * NIGHT_PALETTE).toInt() and 0xFFFF)
                System.arraycopy(bytes, nightAt, bytes, day, entries * 4)
            }
        }
        return bytes
    }
}

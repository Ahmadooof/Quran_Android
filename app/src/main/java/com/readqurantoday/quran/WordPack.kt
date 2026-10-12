package com.readqurantoday.quran

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Every word's recording on the phone for offline Word; resumable, and a word with no recording counts as done. */
object WordPack {

    private const val BASE = "https://audio.readqurantoday.com/wbw"
    private const val AT_ONCE = 16
    private const val TIMEOUT_MS = 15000
    // Progress is said this often, so the screen moves without redrawing for every word
    private const val SAY_EVERY = 200

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val done = AtomicInteger(0)

    /** Heard on the main thread as words come down, and once more when the run ends. */
    var changed: (() -> Unit)? = null

    @Volatile private var plan: Plan? = null

    private class Plan(val bytes: Long, val ayahs: List<IntArray>) {
        val words = ayahs.sumOf { it.sum() }
    }

    private fun plan(context: Context): Plan = plan ?: run {
        val text = context.resources.openRawResource(R.raw.word_counts).use { String(it.readBytes(), Charsets.UTF_8) }
        val root = JSONObject(text)
        val surahs = root.getJSONArray("ayahs")
        Plan(root.getLong("bytes"), List(surahs.length()) { s ->
            val ayahs = surahs.getJSONArray(s)
            IntArray(ayahs.length()) { ayahs.getInt(it) }
        }).also { plan = it }
    }

    private fun dir(context: Context) = File(context.getExternalFilesDir(null), "words")

    private fun name(surah: Int, ayah: Int, word: Int) = "%03d_%03d_%03d.mp3".format(Locale.ROOT, surah, ayah, word)

    // A word the recordings lack leaves an empty mark, so it is not asked for again
    private fun lacking(file: File) = File(file.parentFile, file.name + ".none")

    /** [word] counted from 0, as the page counts; the file if it is on the phone. */
    fun file(context: Context, surah: Int, ayah: Int, word: Int): File? =
        File(dir(context), name(surah, ayah, word + 1)).takeIf { it.exists() }

    fun words(context: Context) = plan(context).words

    fun bytes(context: Context) = plan(context).bytes

    val fetching get() = running.get()

    /** Words on the phone, or known to be lacking: counted afresh when nothing is fetching. Blocking. */
    fun have(context: Context): Int {
        if (running.get()) return done.get()
        return counted(dir(context)).also { done.set(it) }
    }

    // Whole words and known gaps; a word cut off half-way is not one
    private fun counted(folder: File): Int =
        folder.list()?.count { it.endsWith(".mp3") || it.endsWith(".none") } ?: 0

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val folder = dir(app).apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(AT_ONCE)
        Thread {
            done.set(counted(folder))
            plan(app).ayahs.forEachIndexed { s, ayahs ->
                ayahs.forEachIndexed { a, count ->
                    for (w in 1..count) {
                        if (!running.get()) break
                        val file = File(folder, name(s + 1, a + 1, w))
                        if (file.exists() || lacking(file).exists()) continue
                        pool.execute { fetch(file, s + 1, a + 1, w) }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            running.set(false)
            main.post { changed?.invoke() }
        }.start()
    }

    fun stop() = running.set(false)

    /** Every word removed from the phone; the cached ones Word heard lately stay in its own cache. */
    fun remove(context: Context) {
        stop()
        dir(context).deleteRecursively()
        done.set(0)
    }

    private fun fetch(file: File, surah: Int, ayah: Int, word: Int) {
        if (!running.get()) return
        try {
            val c = URL("$BASE/${name(surah, ayah, word)}").openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            try {
                when (c.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val part = File(file.parentFile, file.name + ".part")
                        c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                        part.renameTo(file)
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> lacking(file).createNewFile()
                    else -> return
                }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            // No connection: this word waits for the next start
            return
        }
        if (done.incrementAndGet() % SAY_EVERY == 0) main.post { changed?.invoke() }
    }
}

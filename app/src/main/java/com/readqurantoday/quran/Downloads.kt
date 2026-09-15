package com.readqurantoday.quran

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Offline audio in app storage, one folder per reciter, via DownloadManager so it survives the app closing
object Downloads {

    const val SURAHS = 114

    fun file(context: Context, surah: Int, reciter: String): File {
        val dir = File(context.getExternalFilesDir("audio"), reciter)
        return File(dir, surah.toString().padStart(3, '0') + ".mp3")
    }

    /** True only when the file is complete; partial writes are rejected to avoid seek errors. */
    fun has(context: Context, surah: Int, reciter: String) =
        file(context, surah, reciter).let { it.exists() && it.length() > 0 } &&
            !fetching(context, surah, reciter)

    /* In-flight downloads by "reciter/surah" key. Held in memory only. */
    private val running = HashMap<String, Long>()

    private fun key(surah: Int, reciter: String) = "$reciter/$surah"

    /** Check the DownloadManager by destination path, not by id — ids don't survive process death. */
    fun fetching(context: Context, surah: Int, reciter: String): Boolean =
        inFlight(context, reciter).containsKey(surah).also { if (!it) running.remove(key(surah, reciter)) }

    /** A download under way: its DownloadManager id, and bytes so far of the whole (0 while unknown). */
    data class Flight(val id: Long, val done: Long, val total: Long)

    // One query for all of a reciter's downloads instead of one per row
    fun inFlight(context: Context, reciter: String): Map<Int, Flight> {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dir = File(context.getExternalFilesDir("audio"), reciter).absolutePath
        // A queued download may have no local path yet, so it is also matched by its web address
        val byUrl = (1..SURAHS).associateBy { Recite.urlFor(context, it, reciter) }
        val out = HashMap<Int, Flight>()
        val moving = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED
        )
        try {
            manager.query(moving)?.use { c ->
                val where = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val remote = c.getColumnIndex(DownloadManager.COLUMN_URI)
                val id = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val done = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    val path = (if (where >= 0) c.getString(where) else null)
                        ?.let { runCatching { it.toUri().path }.getOrNull() }
                    val local = path?.let { File(it) }?.takeIf { it.parentFile?.absolutePath == dir }
                    // DownloadManager renames a clashing file to "005-1.mp3"; the number before the dash is the surah
                    val surah = local?.nameWithoutExtension?.substringBefore('-')?.toIntOrNull()
                        ?: (if (remote >= 0) byUrl[c.getString(remote)] else null)
                        ?: continue
                    out[surah] = Flight(
                        id = if (id >= 0) c.getLong(id) else -1L,
                        done = if (done >= 0) c.getLong(done).coerceAtLeast(0) else 0L,
                        total = if (total >= 0) c.getLong(total).coerceAtLeast(0) else 0L
                    )
                }
            }
        } catch (_: Exception) {
            // unreadable download database — treat as nothing in flight
        }
        return out
    }

    /** The surahs of [reciter] fully downloaded, worked out once rather than surah by surah. */
    fun kept(context: Context, reciter: String): Set<Int> {
        val flying = inFlight(context, reciter).keys
        tidy(context, reciter, flying)
        return (1..SURAHS).filterTo(HashSet()) { s ->
            s !in flying && file(context, s, reciter).let { it.exists() && it.length() > 0 }
        }
    }

    /* Finished copies DownloadManager renamed because the name was taken: kept as the surah's file if it has none, else deleted. */
    private fun tidy(context: Context, reciter: String, flying: Set<Int>) {
        val dir = File(context.getExternalFilesDir("audio"), reciter)
        val extras = dir.listFiles { f -> f.name.matches(Regex("""\d{3}-\d+\.mp3""")) } ?: return
        for (extra in extras) {
            val surah = extra.name.substringBefore('-').toIntOrNull() ?: continue
            if (surah in flying) continue
            val main = file(context, surah, reciter)
            if (!main.exists() || main.length() == 0L) extra.renameTo(main) else extra.delete()
        }
    }

    /** Bytes the kept surahs of [reciter] take on the phone. */
    fun keptBytes(context: Context, reciter: String, kept: Set<Int>): Long =
        kept.sumOf { file(context, it, reciter).length() }

    fun start(context: Context, surah: Int, reciter: String) {
        if (has(context, surah, reciter) || fetching(context, surah, reciter)) return

        val target = file(context, surah, reciter)
        target.parentFile?.mkdirs()
        // An empty leftover would make DownloadManager save under another name
        if (target.exists() && target.length() == 0L) target.delete()

        val name = Surahs.list().firstOrNull { it.id == surah }?.name.orEmpty()
        val request = DownloadManager.Request(Recite.urlFor(context, surah, reciter).toUri())
            .setTitle(context.getString(R.string.app_name))
            // Named by surah, not page, in the notification
            .setDescription(context.getString(R.string.surah_named, name))
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        running[key(surah, reciter)] = manager.enqueue(request)
    }

    /** Queue every surah of [reciter] not already kept or on its way. Returns how many were queued. */
    fun startAll(context: Context, reciter: String): Int {
        val kept = kept(context, reciter)
        val flying = inFlight(context, reciter).keys
        var queued = 0
        for (s in 1..SURAHS) {
            if (s in kept || s in flying) continue
            start(context, s, reciter)
            queued++
        }
        return queued
    }

    /** Stop every download of [reciter] under way, and clear what each had written so far. */
    fun stopAll(context: Context, reciter: String) {
        for ((surah, flight) in inFlight(context, reciter)) cancel(context, surah, reciter, flight)
    }

    /** Stop one download under way, if [surah] of [reciter] has one. */
    fun stop(context: Context, surah: Int, reciter: String) {
        inFlight(context, reciter)[surah]?.let { cancel(context, surah, reciter, it) }
    }

    private fun cancel(context: Context, surah: Int, reciter: String, flight: Flight) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (flight.id >= 0) manager.remove(flight.id)
        running.remove(key(surah, reciter))
        file(context, surah, reciter).delete()
    }

    fun remove(context: Context, surah: Int, reciter: String) {
        file(context, surah, reciter).delete()
    }

    /** Delete every kept surah of [reciter]. Copies saved to the phone are the reader's, and stay. */
    fun removeAll(context: Context, reciter: String) {
        for (s in kept(context, reciter)) remove(context, s, reciter)
    }

    // --- sizes ---

    // File sizes from a HEAD request, remembered per reciter; 0 means not known yet
    private fun sizes(context: Context) =
        context.getSharedPreferences("download-sizes", Context.MODE_PRIVATE)

    fun size(context: Context, surah: Int, reciter: String): Long =
        sizes(context).getLong("$reciter/$surah", 0L)

    private val asking = Executors.newSingleThreadExecutor { r -> Thread(r, "download-sizes") }

    // Off the main thread; landed runs on the main thread in batches
    fun learnSizes(context: Context, reciter: String, landed: () -> Unit) {
        val ctx = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        asking.execute {
            val store = sizes(ctx)
            var fresh = 0
            for (s in 1..SURAHS) {
                if (store.getLong("$reciter/$s", 0L) > 0L) continue
                val length = try {
                    val c = URL(Recite.urlFor(ctx, s, reciter)).openConnection() as HttpURLConnection
                    c.requestMethod = "HEAD"
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    try { c.contentLengthLong } finally { c.disconnect() }
                } catch (_: Exception) {
                    -1L
                }
                if (length > 0) {
                    store.edit().putLong("$reciter/$s", length).apply()
                    if (++fresh % 10 == 0) main.post(landed)
                }
            }
            main.post(landed)
        }
    }
}

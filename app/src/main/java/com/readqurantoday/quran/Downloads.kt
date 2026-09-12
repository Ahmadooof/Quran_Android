package com.readqurantoday.quran

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/** Download and cache individual surahs via Android's DownloadManager. */
object Downloads {

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
    fun fetching(context: Context, surah: Int, reciter: String): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val target = file(context, surah, reciter)

        val moving = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED
        )
        try {
            manager.query(moving)?.use { c ->
                val column = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (c.moveToNext()) {
                    val where = if (column >= 0) c.getString(column) else null
                    if (where != null && sameFile(where, target)) return true
                }
            }
        } catch (_: Exception) {
            // unreadable download database — treat as not in flight
        }

        running.remove(key(surah, reciter))
        return false
    }

    private fun sameFile(uri: String, target: File): Boolean {
        val path = try {
            uri.toUri().path
        } catch (_: Exception) {
            null
        } ?: return false
        return File(path).absolutePath == target.absolutePath
    }

    fun busy(context: Context) = running.keys.toList().any { k ->
        val parts = k.split('/')
        fetching(context, parts[1].toInt(), parts[0])
    }

    fun start(context: Context, surah: Int, reciter: String) {
        if (has(context, surah, reciter) || fetching(context, surah, reciter)) return

        val target = file(context, surah, reciter)
        target.parentFile?.mkdirs()

        val request = DownloadManager.Request(Recite.urlFor(context, surah, reciter).toUri())
            .setTitle(context.getString(R.string.app_name))
            .setDescription(context.getString(R.string.page_named, surah))
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        running[key(surah, reciter)] = manager.enqueue(request)
    }

    fun remove(context: Context, surah: Int, reciter: String) {
        file(context, surah, reciter).delete()
    }
}

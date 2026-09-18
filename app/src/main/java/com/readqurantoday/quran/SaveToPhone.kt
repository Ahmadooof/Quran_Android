package com.readqurantoday.quran

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File

// Copies kept downloads to Download/Quran/<reciter>/, which survives uninstall; never downloads

// Each saved surah keeps the address of its copy, since the phone renames a copy whose name is taken
private fun saved(context: Context) =
    context.getSharedPreferences("saved-to-phone-copies", Context.MODE_PRIVATE)

/** Whether surah [surah] of [reciter] has been saved to the phone from here. */
fun isOnPhone(context: Context, surah: Int, reciter: String) =
    saved(context).contains("$reciter/$surah")

/** True below Android 10, where writing to the Download folder needs the storage permission. */
val saveNeedsPermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

// Blocking file work: call off the main thread
fun saveToPhone(context: Context, surah: Int, reciter: Recite.Reciter): Boolean {
    val source = Downloads.file(context, surah, reciter.id)
    if (!source.exists() || source.length() == 0L) return false
    if (isOnPhone(context, surah, reciter.id)) return true

    val title = phoneTitle(surah)
    val folder = phoneFolder(reciter)

    val copy: String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$title.mp3")
                put(MediaStore.Downloads.TITLE, title)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                // Apps may only place files under standard folders; Download takes any type
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                /* Hidden from other apps until the copy is whole. */
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return false
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("no output stream")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } catch (e: Exception) {
                /* Leave no half-written entry behind in the reader's downloads. */
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
            dir.mkdirs()
            val target = File(dir, "$title.mp3")
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mpeg"), null)
            target.absolutePath
        }
    } catch (_: Exception) {
        null
    }

    if (copy != null) saved(context).edit { putString("${reciter.id}/$surah", copy) }
    return copy != null
}

private fun phoneTitle(surah: Int): String {
    val english = Surahs.list().firstOrNull { it.id == surah }?.english.orEmpty()
    return "${surah.toString().padStart(3, '0')} $english".trim()
}

private fun phoneFolder(reciter: Recite.Reciter) = "Quran/${reciter.name}"

/** The saved copy of [surah] by [reciter] as a shareable content address; null, and no longer counted as saved, if it is gone. Blocking. */
fun phoneUri(context: Context, surah: Int, reciter: Recite.Reciter): Uri? {
    val key = "${reciter.id}/$surah"
    val copy = saved(context).getString(key, null) ?: return null
    val uri = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val stored = copy.toUri()
            context.contentResolver.query(stored, arrayOf(MediaStore.Downloads._ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) stored else null }
        } else {
            val collection = MediaStore.Files.getContentUri("external")
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DATA}=?", arrayOf(copy), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }
    } catch (_: Exception) {
        null
    }
    // Deleted from the phone: the surah can be saved again
    if (uri == null) saved(context).edit { remove(key) }
    return uri
}

/** Open the share sheet for [uris], one file or many. */
fun Activity.shareAudio(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "audio/mpeg"
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, getString(R.string.dl_share)))
}

// No standard folder intent: try Samsung My Files, then the system picker, then Downloads
fun Activity.openPhoneFolder(reciter: Recite.Reciter) {
    @Suppress("DEPRECATION")
    val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val quran = File(root, "Quran")
    val target = File(root, phoneFolder(reciter)).takeIf { it.exists() } ?: quran.takeIf { it.exists() } ?: root
    val relative = target.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath).trimStart('/')
    val tries = listOf(
        Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
            .setPackage("com.sec.android.app.myfiles")
            .putExtra("samsung.myfiles.intent.extra.START_PATH", target.absolutePath),
        Intent(Intent.ACTION_VIEW).setDataAndType(
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative"),
            DocumentsContract.Document.MIME_TYPE_DIR
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
    )
    for (intent in tries) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    notice(getString(R.string.dl_folder_failed))
}

// --- save once downloaded ---

private fun waiting(context: Context) =
    context.getSharedPreferences("save-after-download", Context.MODE_PRIVATE)

/** Whether [surah] of [reciter] is to be saved to the phone as soon as its download ends. */
fun savesAfterDownload(context: Context, surah: Int, reciter: String) =
    waiting(context).getBoolean("$reciter/$surah", false)

fun saveAfterDownload(context: Context, surah: Int, reciter: String, on: Boolean) {
    waiting(context).edit {
        if (on) putBoolean("$reciter/$surah", true) else remove("$reciter/$surah")
    }
}

/** Save every waiting surah whose download has finished. Blocking: call off the main thread. Returns how many were saved. */
// The receiver and the open downloads screen may both run this; one at a time, so no surah is copied twice
private val finishing = Any()

fun saveFinishedDownloads(context: Context): Int = synchronized(finishing) {
    var saved = 0
    for (key in waiting(context).all.keys) {
        val reciterId = key.substringBefore('/')
        val surah = key.substringAfter('/').toIntOrNull() ?: continue
        val voice = Recite.reciters().firstOrNull { it.id == reciterId }
        if (voice == null) { saveAfterDownload(context, surah, reciterId, false); continue }
        if (!Downloads.has(context, surah, reciterId)) continue
        if (saveToPhone(context, surah, voice)) saved++
        saveAfterDownload(context, surah, reciterId, false)
    }
    saved
}

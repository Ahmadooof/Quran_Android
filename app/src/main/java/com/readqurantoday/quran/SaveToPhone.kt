package com.readqurantoday.quran

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/*
  A downloaded surah copied out to where the reader can see it: the phone's Download
  folder, as Download/Quran/<reciter>/002 Al-Baqarah.mp3. There it shows in file
  managers, can be shared or moved to a computer, and stays when the app is
  uninstalled — none of which is true of the app's own downloads.

  Only a kept download is copied, so saving never spends data. Which surahs have been
  saved, per reciter, is remembered, so the screen can say so and "save all" can skip
  them; a file the reader deletes from the phone later is theirs to delete.
*/

private fun saved(context: Context) =
    context.getSharedPreferences("saved-to-phone-download", Context.MODE_PRIVATE)

/** Whether surah [surah] of [reciter] has been saved to the phone from here. */
fun isOnPhone(context: Context, surah: Int, reciter: String) =
    saved(context).getBoolean("$reciter/$surah", false)

/** True below Android 10, where writing to the Download folder needs the storage permission. */
val saveNeedsPermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/**
 * Copy a kept download of [surah] by [reciter] into the phone's Download folder. Returns
 * whether it is there afterwards. Blocking file work: call it off the main thread.
 */
fun saveToPhone(context: Context, surah: Int, reciter: Recite.Reciter): Boolean {
    val source = Downloads.file(context, surah, reciter.id)
    if (!source.exists() || source.length() == 0L) return false
    if (isOnPhone(context, surah, reciter.id)) return true

    val title = phoneTitle(surah)
    val folder = phoneFolder(reciter)

    val ok = try {
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
                true
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
            true
        }
    } catch (_: Exception) {
        false
    }

    if (ok) saved(context).edit().putBoolean("${reciter.id}/$surah", true).apply()
    return ok
}

private fun phoneTitle(surah: Int): String {
    val english = Surahs.list().firstOrNull { it.id == surah }?.english.orEmpty()
    return "${surah.toString().padStart(3, '0')} $english".trim()
}

private fun phoneFolder(reciter: Recite.Reciter) = "Quran/${reciter.name}"

/** The saved copy of [surah] by [reciter] as a shareable content address, or null if it is gone. Blocking. */
fun phoneUri(context: Context, surah: Int, reciter: Recite.Reciter): Uri? {
    val name = "${phoneTitle(surah)}.mp3"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${phoneFolder(reciter)}/", name), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        } else {
            @Suppress("DEPRECATION")
            val path = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), phoneFolder(reciter)), name).absolutePath
            val collection = MediaStore.Files.getContentUri("external")
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DATA}=?", arrayOf(path), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }
    } catch (_: Exception) {
        null
    }
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

/*
  Show where saved surahs are. There is no one standard way to open a folder: Samsung's
  file manager takes a path, the system file picker a folder address, and failing both
  the Downloads app opens, one tap away from Quran.
*/
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
    val edit = waiting(context).edit()
    if (on) edit.putBoolean("$reciter/$surah", true) else edit.remove("$reciter/$surah")
    edit.apply()
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

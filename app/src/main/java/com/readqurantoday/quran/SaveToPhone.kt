package com.readqurantoday.quran

import android.app.Activity
import android.content.ActivityNotFoundException
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

    val title = surahFileTitle(surah)
    val folder = phoneFolder(reciter)

    val copy = copyToShared(context, source, "$title.mp3", "audio/mpeg", Environment.DIRECTORY_DOWNLOADS, folder)
    if (copy != null) saved(context).edit { putString("${reciter.id}/$surah", copy) }
    return copy != null
}

/** A made recitation video copied to Movies/Quran, where the phone's gallery finds it. Blocking. */
fun saveVideoToPhone(context: Context, video: File): Boolean =
    copyToShared(context, video, video.name, "video/mp4", Environment.DIRECTORY_MOVIES, "Quran") != null

// Copies [source] into [standard]/[folder] for every app to see; where it now is, or null. Blocking
private fun copyToShared(context: Context, source: File, name: String, mime: String, standard: String, folder: String): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.TITLE, name.substringBeforeLast('.'))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // Apps may only place files under standard folders
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$standard/$folder")
            /* Hidden from other apps until the copy is whole. */
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val into = if (standard == Environment.DIRECTORY_MOVIES) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                   else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(into, values) ?: throw IllegalStateException("no entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            /* Leave no half-written entry behind. */
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(standard), folder)
        dir.mkdirs()
        val target = File(dir, name)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        target.absolutePath
    }
} catch (_: Exception) {
    null
}

/** A surah's file named as a person would look for it: "018 Al-Kahf". */
fun surahFileTitle(surah: Int): String {
    val english = Surahs.of(surah)?.english.orEmpty()
    return "${surah.toString().padStart(3, '0')} $english".trim()
}

private fun phoneFolder(reciter: Recite.Reciter) = "Quran/${reciter.name}"

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

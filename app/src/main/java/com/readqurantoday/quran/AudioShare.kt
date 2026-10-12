package com.readqurantoday.quran

import android.app.Activity
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider

/** Shares downloads under readable names: "018 Al-Kahf - Reciter.mp3", not "018.mp3". */
class AudioShare : FileProvider() {

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val found = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val name = sharedName(uri) ?: return found
        found.use { c ->
            val named = MatrixCursor(c.columnNames, 1)
            if (c.moveToFirst()) named.addRow(c.columnNames.mapIndexed { i, column ->
                if (column == OpenableColumns.DISPLAY_NAME) name else when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_NULL -> null
                    else -> c.getString(i)
                }
            })
            return named
        }
    }

    // ".../audio/<reciter>/018.mp3" -> "018 Al-Kahf - <reciter's name>.mp3"
    private fun sharedName(uri: Uri): String? {
        val parts = uri.pathSegments
        val surah = parts.lastOrNull()?.substringBefore('.')?.toIntOrNull() ?: return null
        // Asked from another app, perhaps with this one closed: the reciters are read in first
        context?.let { Recite.load(it) }
        val reciter = Recite.reciters().firstOrNull { it.id == parts.getOrNull(parts.size - 2) }
        val title = surahFileTitle(surah)
        return if (reciter == null) "$title.mp3" else "$title - ${reciter.name}.mp3"
    }
}

/** The share sheet for downloaded [surahs] of [reciter], handed over from the app's own storage. */
fun Activity.shareDownloads(surahs: List<Int>, reciter: Recite.Reciter) {
    val uris = surahs.map { FileProvider.getUriForFile(this, "$packageName.share", Downloads.file(this, it, reciter.id)) }
    shareAudio(uris)
}

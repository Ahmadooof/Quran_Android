package com.readqurantoday.quran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The ayah each juz and hizb begins at. */
object Openings {

    data class Opening(val surah: Int, val ayah: Int)

    private var juz = emptyList<Opening>()
    private var hizb = emptyList<Opening>()

    private fun load(context: Context) {
        if (juz.isNotEmpty()) return
        val text = context.resources.openRawResource(R.raw.part_openings).use { String(it.readBytes(), Charsets.UTF_8) }
        val root = JSONObject(text)
        juz = read(root.getJSONArray("juz"))
        hizb = read(root.getJSONArray("hizb"))
    }

    private fun read(rows: JSONArray) = List(rows.length()) {
        val row = rows.getJSONArray(it)
        Opening(row.getInt(0), row.getInt(1))
    }

    fun ofJuz(context: Context, n: Int): Opening? = juz(context).getOrNull(n - 1)

    fun ofHizb(context: Context, n: Int): Opening? = hizb(context).getOrNull(n - 1)

    private fun juz(context: Context) = juz.ifEmpty { load(context); juz }

    private fun hizb(context: Context) = hizb.ifEmpty { load(context); hizb }
}
